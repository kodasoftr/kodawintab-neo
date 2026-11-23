package com.example.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.input.InputManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Process
import android.view.InputDevice
import android.view.MotionEvent
import androidx.core.content.ContextCompat
import com.example.driver.TabletPipeline
import com.example.driver.TabletProfileJson
import com.example.driver.TabletProfileRepository
import com.example.driver.TabletSlotId
import com.example.koda.engine.KodaEngineClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class UsbPeripheralKind(val label: String) {
    TABLET("Mesa Digitalizadora"),
    MOUSE("Mouse USB"),
    KEYBOARD("Teclado USB")
}

data class ConnectedUsbDeviceInfo(
    val deviceKey: String,
    val usbDeviceName: String,
    val productName: String,
    val manufacturerName: String,
    val vendorId: Int,
    val productId: Int,
    val kind: UsbPeripheralKind,
    val hasPermission: Boolean,
    val isClaimed: Boolean,
    val matchedProfileId: String?,
    val vbusActive: Boolean = true,
    val isSystemInputFallback: Boolean = false,
    val isPenDisplay: Boolean = false
)

/**
 * Coordinates Android USB Host API enumeration + InputManager external peripheral discovery,
 * permission grants, force-claiming HID interfaces (both Tablets and USB Mice), Ugee/Huion init
 * transfers, and URGENT_DISPLAY reader threads feeding zero-allocation buffers.
 */
class UsbTabletCoordinator(
    private val context: Context,
    private val profileRepo: TabletProfileRepository,
    private val pipelineMesa1: TabletPipeline,
    private val pipelineMesa2: TabletPipeline,
    private val engineClient: KodaEngineClient? = null
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager

    private val _devices = MutableStateFlow<List<ConnectedUsbDeviceInfo>>(emptyList())
    val devices: StateFlow<List<ConnectedUsbDeviceInfo>> = _devices.asStateFlow()

    private val _lastUsbEventLog = MutableStateFlow("Aguardando dispositivos USB OTG...")
    val lastUsbEventLog: StateFlow<String> = _lastUsbEventLog.asStateFlow()

    private val _penDisplayConnected = MutableStateFlow(false)
    val penDisplayConnected: StateFlow<Boolean> = _penDisplayConnected.asStateFlow()

    private val _detectedPenDisplayProfile = MutableStateFlow<TabletProfileJson?>(null)
    val detectedPenDisplayProfile: StateFlow<TabletProfileJson?> = _detectedPenDisplayProfile.asStateFlow()

    @Volatile private var activeVideoConnection: UsbDeviceConnection? = null
    @Volatile private var activeVideoBulkOutEndpoint: UsbEndpoint? = null

    @Volatile var onMouseCoordinate: ((x: Float, y: Float, isVisible: Boolean, channel: Int) -> Unit)? = null
    @Volatile var resolveCursorChannel: ((deviceKey: String) -> Int)? = null
    @Volatile var mouseDisplayWidthPx: Float = 1080f
    @Volatile var mouseDisplayHeightPx: Float = 2400f
    @Volatile var mouseSensitivity: Float = 1.0f

    private var mouseCursorX = 540f
    private var mouseCursorY = 960f
    private var mouse2CursorX = 540f
    private var mouse2CursorY = 960f

    fun updateMouseDisplayBounds(widthPx: Float, heightPx: Float) {
        mouseDisplayWidthPx = widthPx.coerceAtLeast(100f)
        mouseDisplayHeightPx = heightPx.coerceAtLeast(100f)
        mouseCursorX = mouseCursorX.coerceIn(0f, mouseDisplayWidthPx)
        mouseCursorY = mouseCursorY.coerceIn(0f, mouseDisplayHeightPx)
        mouse2CursorX = mouse2CursorX.coerceIn(0f, mouseDisplayWidthPx)
        mouse2CursorY = mouse2CursorY.coerceIn(0f, mouseDisplayHeightPx)
    }

    private val activeConnections = ConcurrentHashMap<String, ActiveReaderSession>()
    private var receiverRegistered = false

    private class ActiveReaderSession(
        val connection: UsbDeviceConnection,
        val usbInterface: UsbInterface,
        val running: AtomicBoolean,
        val thread: Thread
    )

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    val device = getDeviceExtra(intent)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null && granted) {
                        _lastUsbEventLog.value = "Permissão USB concedida: ${device.productName ?: device.deviceName}"
                        openAndClaimDevice(device, TabletSlotId.MESA_1)
                    } else {
                        _lastUsbEventLog.value = "Permissão USB recusada pelo usuário."
                    }
                    refreshConnectedDevices()
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = getDeviceExtra(intent)
                    val kind = device?.let { classifyDevice(it) }
                    if (device != null && kind == null) {
                        // Deixe ADB, MTP, Armazenamento e outras partes USB passarem livres pelo sistema
                        return
                    }
                    _lastUsbEventLog.value = "USB conectado: ${device?.productName ?: "Dispositivo"}"
                    refreshConnectedDevices()
                    if (device != null && kind != null) {
                        val engineReady = engineClient?.state?.value?.isActive == true
                        if (kind == UsbPeripheralKind.TABLET || (kind == UsbPeripheralKind.MOUSE && engineReady)) {
                            if (usbManager?.hasPermission(device) == true) {
                                openAndClaimDevice(device, TabletSlotId.MESA_1)
                            } else {
                                requestPermission(device)
                            }
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = getDeviceExtra(intent)
                    if (device != null) {
                        stopDeviceSession(buildDeviceKey(device))
                        if (classifyDevice(device) != null) {
                            _lastUsbEventLog.value = "USB desconectado: ${device.productName ?: device.deviceName}"
                        }
                    }
                    refreshConnectedDevices()
                }
            }
        }
    }

    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshConnectedDevices()
        override fun onInputDeviceRemoved(deviceId: Int) = refreshConnectedDevices()
        override fun onInputDeviceChanged(deviceId: Int) = refreshConnectedDevices()
    }

    @Suppress("DEPRECATION")
    private fun getDeviceExtra(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(
            context,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        runCatching { inputManager?.registerInputDeviceListener(inputDeviceListener, null) }
        receiverRegistered = true
        refreshConnectedDevices()
    }

    fun unregisterReceiver() {
        if (!receiverRegistered) return
        runCatching { context.unregisterReceiver(usbReceiver) }
        runCatching { inputManager?.unregisterInputDeviceListener(inputDeviceListener) }
        receiverRegistered = false
        stopAllSessions()
    }

    fun onNewUsbIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = getDeviceExtra(intent) ?: return
            val kind = classifyDevice(device) ?: return // Ignora ADB/MTP/Storage/System USB
            _lastUsbEventLog.value = "USB conectado: ${device.productName ?: "Dispositivo"}"
            refreshConnectedDevices()
            val engineReady = engineClient?.state?.value?.isActive == true
            if (kind == UsbPeripheralKind.TABLET || (kind == UsbPeripheralKind.MOUSE && engineReady)) {
                if (usbManager?.hasPermission(device) == true) {
                    openAndClaimDevice(device, TabletSlotId.MESA_1)
                } else {
                    requestPermission(device)
                }
            }
        }
    }

    fun refreshConnectedDevices() {
        val result = ArrayList<ConnectedUsbDeviceInfo>()
        val seenVidPids = HashSet<String>()
        val engineReady = engineClient?.state?.value?.isActive == true

        // 1. Direct USB Host API enumeration (apenas periféricos de entrada HID suportados; ADB/MTP/Storage passam livre)
        val mgr = usbManager
        if (mgr != null) {
            for (dev in mgr.deviceList.values) {
                val kind = classifyDevice(dev) ?: continue
                val key = buildDeviceKey(dev)
                val shouldAutoClaim = kind == UsbPeripheralKind.TABLET ||
                    (kind == UsbPeripheralKind.MOUSE && engineReady)
                if (shouldAutoClaim && mgr.hasPermission(dev) && !activeConnections.containsKey(key)) {
                    openAndClaimDevice(dev, TabletSlotId.MESA_1)
                }
                val matched = if (kind == UsbPeripheralKind.TABLET) {
                    profileRepo.findByVidPid(dev.vendorId, dev.productId, dev.productName.orEmpty())
                } else null
                val nameLower = "${dev.productName.orEmpty()} ${dev.manufacturerName.orEmpty()}".lowercase()
                val isPenDisp = (matched?.isPenDisplay == true) ||
                    TabletProfileRepository.PEN_DISPLAY_NAME_HINTS.any { nameLower.contains(it) }

                seenVidPids.add("${dev.vendorId}:${dev.productId}")
                result.add(
                    ConnectedUsbDeviceInfo(
                        deviceKey = key,
                        usbDeviceName = dev.deviceName,
                        productName = dev.productName ?: matched?.name ?: "${kind.label} ${hexId(dev.vendorId)}:${hexId(dev.productId)}",
                        manufacturerName = dev.manufacturerName ?: matched?.brand ?: "USB HID",
                        vendorId = dev.vendorId,
                        productId = dev.productId,
                        kind = kind,
                        hasPermission = mgr.hasPermission(dev),
                        isClaimed = activeConnections.containsKey(key),
                        matchedProfileId = matched?.id,
                        isSystemInputFallback = false,
                        isPenDisplay = isPenDisp
                    )
                )
            }
        }

        // 2. Android InputManager external peripheral fallback (ensures USB OTG / wireless / BT mice
        //    and keyboards always appear in Hub selection even if UsbManager omits HID mice on some OEM ROMs)
        val im = inputManager
        if (im != null) {
            for (id in im.inputDeviceIds) {
                val inDev = im.getInputDevice(id) ?: continue
                if (inDev.isVirtual || !isExternalInputDevice(inDev)) continue
                val vidPidSig = "${inDev.vendorId}:${inDev.productId}"
                val kind = classifyInputDevice(inDev) ?: continue

                // Skip adding duplicate only if UsbManager already listed the exact same kind + VID:PID
                val alreadyListed = inDev.vendorId != 0 && inDev.productId != 0 &&
                    seenVidPids.contains(vidPidSig) &&
                    result.any { it.vendorId == inDev.vendorId && it.productId == inDev.productId && it.kind == kind }
                if (alreadyListed) continue

                val matchedInput = if (kind == UsbPeripheralKind.TABLET) {
                    profileRepo.findByVidPid(inDev.vendorId, inDev.productId, inDev.name)
                } else null
                val inputNameLower = inDev.name.lowercase()
                val isInputPenDisp = (matchedInput?.isPenDisplay == true) ||
                    TabletProfileRepository.PEN_DISPLAY_NAME_HINTS.any { inputNameLower.contains(it) }

                val key = "INPUT:${hexId(inDev.vendorId)}:${hexId(inDev.productId)}:${inDev.id}"
                result.add(
                    ConnectedUsbDeviceInfo(
                        deviceKey = key,
                        usbDeviceName = "input/${inDev.id}",
                        productName = inDev.name.ifBlank { "${kind.label} #${inDev.id}" },
                        manufacturerName = "Sistema Android Input",
                        vendorId = inDev.vendorId,
                        productId = inDev.productId,
                        kind = kind,
                        hasPermission = true,
                        isClaimed = false,
                        matchedProfileId = matchedInput?.id,
                        isSystemInputFallback = true,
                        isPenDisplay = isInputPenDisp
                    )
                )
            }
        }

        _devices.value = result
        val penDisplayDev = result.firstOrNull { it.isPenDisplay }
        if (penDisplayDev != null) {
            _penDisplayConnected.value = true
            _detectedPenDisplayProfile.value = penDisplayDev.matchedProfileId?.let {
                profileRepo.getProfileById(it)
            } ?: profileRepo.findByVidPid(penDisplayDev.vendorId, penDisplayDev.productId, penDisplayDev.productName)
        } else {
            _penDisplayConnected.value = false
            _detectedPenDisplayProfile.value = null
        }
    }

    private fun isExternalInputDevice(dev: InputDevice): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (dev.isExternal) return true
        }
        val sources = dev.sources
        if ((sources and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) return true
        if ((sources and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS) return true
        return false
    }

    private fun classifyInputDevice(dev: InputDevice): UsbPeripheralKind? {
        val lower = dev.name.lowercase()
        val src = dev.sources
        if (dev.vendorId in KNOWN_TABLET_VIDS ||
            TABLET_KEYWORDS.any { lower.contains(it) } ||
            (src and InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS
        ) {
            return UsbPeripheralKind.TABLET
        }
        if ((src and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE ||
            (src and InputDevice.SOURCE_TRACKBALL) == InputDevice.SOURCE_TRACKBALL ||
            MOUSE_KEYWORDS.any { lower.contains(it) }
        ) {
            return UsbPeripheralKind.MOUSE
        }
        if ((src and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD &&
            dev.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
        ) {
            return UsbPeripheralKind.KEYBOARD
        }
        return null
    }

    fun requestPermissionByKey(deviceKey: String, targetSlot: TabletSlotId = TabletSlotId.MESA_1) {
        if (deviceKey.startsWith("INPUT:")) {
            _lastUsbEventLog.value = "Periférico pronto via Android InputManager."
            return
        }
        val mgr = usbManager ?: return
        val dev = mgr.deviceList.values.firstOrNull { buildDeviceKey(it) == deviceKey } ?: return
        val kind = classifyDevice(dev) ?: return
        if (kind == UsbPeripheralKind.KEYBOARD) {
            _lastUsbEventLog.value = "Teclado USB em pass-through nativo (sem bloquear sistema)."
            return
        }
        if (mgr.hasPermission(dev)) {
            openAndClaimDevice(dev, targetSlot)
            refreshConnectedDevices()
        } else {
            requestPermission(dev)
        }
    }

    private fun requestPermission(device: UsbDevice) {
        val mgr = usbManager ?: return
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(context, device.deviceId, Intent(ACTION_USB_PERMISSION), flags)
        mgr.requestPermission(device, pi)
    }

    fun openAndClaimDevice(device: UsbDevice, targetSlot: TabletSlotId) {
        val mgr = usbManager ?: return
        if (!mgr.hasPermission(device)) return
        val kind = classifyDevice(device) ?: return
        if (kind == UsbPeripheralKind.KEYBOARD) {
            // Nunca reivindicar teclado físico para manter atalhos e pass-through do sistema livres
            return
        }
        val key = buildDeviceKey(device)
        if (activeConnections.containsKey(key)) return

        val matchedProfile = if (kind == UsbPeripheralKind.TABLET) {
            profileRepo.findByVidPid(device.vendorId, device.productId, device.productName.orEmpty())
        } else null

        val targetIntf = selectBestHidInterface(device, matchedProfile, kind) ?: run {
            _lastUsbEventLog.value = "Pass-through preservado: nenhuma interface HID alvo em ${device.productName ?: "USB"}."
            return
        }
        if (isAdbOrNonInputInterface(targetIntf) && matchedProfile == null) {
            return
        }

        val connection = mgr.openDevice(device) ?: run {
            _lastUsbEventLog.value = "Falha ao abrir dispositivo USB."
            return
        }

        // Force-claim ONLY the specific HID tablet/mouse interface so ADB, MTP, and other interfaces pass through freely
        val claimed = connection.claimInterface(targetIntf, true)
        if (!claimed) {
            connection.close()
            _lastUsbEventLog.value = "claimInterface(force=true) falhou — verifique Koda Engine."
            return
        }

        if (matchedProfile != null) {
            runVendorInitialization(connection, matchedProfile)
        }

        // Detect optional vendor bulk-out endpoint for proprietary Koda Stream Engine USB video frames
        findVendorVideoBulkOutEndpoint(device)?.let { (videoIntf, bulkEp) ->
            if (videoIntf == targetIntf || connection.claimInterface(videoIntf, true)) {
                activeVideoConnection = connection
                activeVideoBulkOutEndpoint = bulkEp
            }
        }

        val endpoint = findInterruptInEndpoint(targetIntf, matchedProfile)
        if (endpoint == null) {
            connection.releaseInterface(targetIntf)
            connection.close()
            _lastUsbEventLog.value = "Endpoint IN não encontrado."
            return
        }

        if (kind == UsbPeripheralKind.MOUSE) {
            startMouseReaderSession(device, key, connection, targetIntf, endpoint)
        } else {
            startTabletReaderSession(device, key, connection, targetIntf, endpoint, matchedProfile, targetSlot, kind)
        }
    }

    private fun findVendorVideoBulkOutEndpoint(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (isAdbOrNonInputInterface(intf)) continue
            if (intf.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC) {
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.direction == UsbConstants.USB_DIR_OUT
                    ) {
                        return intf to ep
                    }
                }
            }
        }
        return null
    }

    fun sendVideoFrameBulk(packet: ByteArray, length: Int): Boolean {
        val conn = activeVideoConnection ?: return false
        val ep = activeVideoBulkOutEndpoint ?: return false
        return runCatching {
            conn.bulkTransfer(ep, packet, length.coerceAtMost(packet.size), 12) >= 0
        }.getOrDefault(false)
    }

    fun hasUsbVideoBulkTransport(): Boolean =
        activeVideoConnection != null && activeVideoBulkOutEndpoint != null

    private fun startMouseReaderSession(
        device: UsbDevice,
        key: String,
        connection: UsbDeviceConnection,
        targetIntf: UsbInterface,
        endpoint: UsbEndpoint
    ) {
        val running = AtomicBoolean(true)
        val packetSize = endpoint.maxPacketSize.coerceIn(4, 64)
        val mouseParser = MouseHidParser()
        var prevLeft = false

        val thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            val buffer = ByteArray(packetSize)
            while (running.get()) {
                val bytesRead = connection.bulkTransfer(endpoint, buffer, packetSize, 40)
                if (bytesRead > 0 && mouseParser.parse(buffer, bytesRead, mouseSensitivity)) {
                    val channel = resolveCursorChannel?.invoke(key) ?: 1
                    val curX: Float
                    val curY: Float
                    if (channel == 2) {
                        mouse2CursorX = (mouse2CursorX + mouseParser.deltaX).coerceIn(0f, mouseDisplayWidthPx)
                        mouse2CursorY = (mouse2CursorY + mouseParser.deltaY).coerceIn(0f, mouseDisplayHeightPx)
                        curX = mouse2CursorX
                        curY = mouse2CursorY
                    } else {
                        mouseCursorX = (mouseCursorX + mouseParser.deltaX).coerceIn(0f, mouseDisplayWidthPx)
                        mouseCursorY = (mouseCursorY + mouseParser.deltaY).coerceIn(0f, mouseDisplayHeightPx)
                        curX = mouseCursorX
                        curY = mouseCursorY
                    }
                    onMouseCoordinate?.invoke(curX, curY, true, channel)

                    var btnState = 0
                    if (mouseParser.buttonLeft) btnState = btnState or MotionEvent.BUTTON_PRIMARY
                    if (mouseParser.buttonRight) btnState = btnState or MotionEvent.BUTTON_SECONDARY
                    if (mouseParser.buttonMiddle) btnState = btnState or MotionEvent.BUTTON_TERTIARY

                    val action = when {
                        mouseParser.buttonLeft && !prevLeft -> MotionEvent.ACTION_DOWN
                        !mouseParser.buttonLeft && prevLeft -> MotionEvent.ACTION_UP
                        mouseParser.buttonLeft -> MotionEvent.ACTION_MOVE
                        mouseParser.wheelDelta != 0f -> MotionEvent.ACTION_SCROLL
                        else -> MotionEvent.ACTION_HOVER_MOVE
                    }
                    prevLeft = mouseParser.buttonLeft

                    engineClient?.sendMouseEvent(
                        deviceId = if (channel == 2) 102 else 101,
                        displayId = 0,
                        action = action,
                        x = curX,
                        y = curY,
                        buttonState = btnState,
                        vScroll = mouseParser.wheelDelta
                    )
                }
            }
        }, "KodaMouseReader-${device.productId}").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        activeConnections[key] = ActiveReaderSession(connection, targetIntf, running, thread)
        _lastUsbEventLog.value = "Mouse USB ativo: ${device.productName ?: "HID Mouse"} (cursor global)"
        refreshConnectedDevices()
    }

    private fun startTabletReaderSession(
        device: UsbDevice,
        key: String,
        connection: UsbDeviceConnection,
        targetIntf: UsbInterface,
        endpoint: UsbEndpoint,
        matchedProfile: TabletProfileJson?,
        targetSlot: TabletSlotId,
        kind: UsbPeripheralKind
    ) {
        val pipeline = if (targetSlot == TabletSlotId.MESA_2) pipelineMesa2 else pipelineMesa1
        if (matchedProfile != null) {
            val parser = profileRepo.createParserFor(matchedProfile, connection.rawDescriptors)
            pipeline.activeParser = parser
            pipeline.start()
        } else if (kind == UsbPeripheralKind.TABLET) {
            val fallbackProfile = profileRepo.getProfileById("generic_hid")
            pipeline.activeParser = profileRepo.createParserFor(fallbackProfile, connection.rawDescriptors)
            pipeline.start()
        }

        val running = AtomicBoolean(true)
        val packetSize = endpoint.maxPacketSize.coerceIn(8, 64)
        val thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            val preAllocBuffer = ByteArray(packetSize)
            while (running.get()) {
                val bytesRead = connection.bulkTransfer(endpoint, preAllocBuffer, packetSize, 50)
                if (bytesRead > 0) {
                    pipeline.ringBuffer.offer(preAllocBuffer, bytesRead, System.nanoTime())
                    pipeline.wakeWorker()
                }
            }
        }, "KodaUsbReader-${device.productId}").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        activeConnections[key] = ActiveReaderSession(connection, targetIntf, running, thread)
        val engineReady = engineClient?.state?.value?.isActive == true
        val permOtg = engineClient?.isPermanentlyActivated() == true
        val modeTag = when {
            engineReady -> "Shell UID 2000 + OTG force=true"
            permOtg -> "USB OTG Direto Permanente · pass-through ADB livre"
            else -> "OTG force=true"
        }
        _lastUsbEventLog.value = "Driver ativo: ${matchedProfile?.name ?: device.productName ?: "HID"} ($modeTag)"
        refreshConnectedDevices()
    }

    private fun runVendorInitialization(connection: UsbDeviceConnection, profile: TabletProfileJson) {
        for (cmd in profile.initControlTransfers) {
            val bytes = hexToBytes(cmd.dataHex)
            connection.controlTransfer(cmd.requestType, cmd.request, cmd.value, cmd.index, bytes, bytes.size, 250)
        }
        if (profile.vendorId == VID_HUION) {
            val descBuf = ByteArray(64)
            connection.controlTransfer(0x80, 0x06, 0x03C8, 0x0409, descBuf, descBuf.size, 200)
            connection.controlTransfer(0x80, 0x06, 0x0364, 0x0409, descBuf, descBuf.size, 200)
        }
    }

    private fun selectBestHidInterface(
        device: UsbDevice,
        profile: TabletProfileJson?,
        kind: UsbPeripheralKind
    ): UsbInterface? {
        if (profile != null && profile.interfaceNumber < device.interfaceCount) {
            val candidate = device.getInterface(profile.interfaceNumber)
            if (!isAdbOrNonInputInterface(candidate)) return candidate
        }
        if (kind == UsbPeripheralKind.MOUSE) {
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_HID &&
                    intf.interfaceSubclass == 1 && intf.interfaceProtocol == 2
                ) {
                    return intf
                }
            }
        }
        if (kind == UsbPeripheralKind.TABLET) {
            // Prefer non-boot digitizer HID interface (protocol == 0) with largest IN endpoint (>= 8 bytes)
            var bestTabletIntf: UsbInterface? = null
            var bestMaxPkt = -1
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_HID && intf.interfaceProtocol == 0) {
                    for (e in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(e)
                        if (ep.direction == UsbConstants.USB_DIR_IN && ep.maxPacketSize > bestMaxPkt) {
                            bestMaxPkt = ep.maxPacketSize
                            bestTabletIntf = intf
                        }
                    }
                }
            }
            if (bestTabletIntf != null) return bestTabletIntf
        }
        // Fallback strictly to HID interfaces ONLY — never hijack ADB, Mass Storage, CDC, Audio, or Hubs
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_HID) {
                return intf
            }
        }
        return null
    }

    private fun findInterruptInEndpoint(intf: UsbInterface, profile: TabletProfileJson?): UsbEndpoint? {
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.direction == UsbConstants.USB_DIR_IN &&
                (profile == null || ep.address == profile.endpointAddress)
            ) {
                return ep
            }
        }
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.direction == UsbConstants.USB_DIR_IN) return ep
        }
        return null
    }

    fun stopDeviceSession(deviceKey: String) {
        val session = activeConnections.remove(deviceKey) ?: return
        session.running.set(false)
        session.thread.interrupt()
        if (activeVideoConnection == session.connection) {
            activeVideoConnection = null
            activeVideoBulkOutEndpoint = null
        }
        runCatching {
            session.connection.releaseInterface(session.usbInterface)
            session.connection.close()
        }
    }

    fun stopAllSessions() {
        activeConnections.keys.toList().forEach { stopDeviceSession(it) }
    }

    /**
     * Checks if a USB interface belongs to ADB (255/0x42/0x01), Mass Storage, USB Hub, MTP/PTP,
     * Audio, Video, or CDC networking — so Koda NEVER steals or blocks them.
     */
    fun isAdbOrNonInputInterface(intf: UsbInterface): Boolean =
        isAdbOrSystemClass(intf.interfaceClass, intf.interfaceSubclass, intf.interfaceProtocol)

    /**
     * Accurately classifies USB HID Mice, Keyboards, and Graphics Tablets across all interfaces,
     * returning `null` for ADB, APK transfers, MTP, Mass Storage, USB Hubs, and non-HID devices
     * so they pass straight through to the Android OS untouched.
     */
    fun classifyDevice(device: UsbDevice): UsbPeripheralKind? {
        if (device.vendorId in KNOWN_TABLET_VIDS) {
            return UsbPeripheralKind.TABLET
        }

        var hasAnyHidInterface = false
        var hasMouseBootIntf = false
        var hasKeyboardBootIntf = false
        var hasNonBootDigitizerHid = false
        var hasAdbOrSystemOnly = false
        var maxHidPacketSize = 0

        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (isAdbOrNonInputInterface(intf)) {
                hasAdbOrSystemOnly = true
            }
            if (intf.interfaceClass == UsbConstants.USB_CLASS_HID) {
                hasAnyHidInterface = true
                if (intf.interfaceSubclass == 1 && intf.interfaceProtocol == 2) {
                    hasMouseBootIntf = true
                } else if (intf.interfaceSubclass == 1 && intf.interfaceProtocol == 1) {
                    hasKeyboardBootIntf = true
                } else if (intf.interfaceProtocol == 0) {
                    hasNonBootDigitizerHid = true
                }
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    if (ep.direction == UsbConstants.USB_DIR_IN) {
                        maxHidPacketSize = maxOf(maxHidPacketSize, ep.maxPacketSize)
                    }
                }
            }
        }

        // Ignore ADB bridges, flash drives, MTP, hubs, audio, and any device with zero HID interfaces
        if (!hasAnyHidInterface || (hasAdbOrSystemOnly && !hasNonBootDigitizerHid && !hasMouseBootIntf)) {
            return null
        }

        val nameLower = "${device.productName.orEmpty()} ${device.manufacturerName.orEmpty()}".lowercase()
        if (ADB_OR_SYSTEM_KEYWORDS.any { nameLower.contains(it) }) {
            return null
        }
        if (TABLET_KEYWORDS.any { nameLower.contains(it) }) {
            return UsbPeripheralKind.TABLET
        }
        if (MOUSE_KEYWORDS.any { nameLower.contains(it) }) {
            return UsbPeripheralKind.MOUSE
        }
        if (KEYBOARD_KEYWORDS.any { nameLower.contains(it) }) {
            return UsbPeripheralKind.KEYBOARD
        }

        if (hasMouseBootIntf) return UsbPeripheralKind.MOUSE
        if (device.vendorId in KNOWN_MOUSE_VIDS && !hasKeyboardBootIntf) return UsbPeripheralKind.MOUSE
        if (hasKeyboardBootIntf && !hasNonBootDigitizerHid) return UsbPeripheralKind.KEYBOARD

        // Generic HID without boot protocol: mice typically use <= 8 byte interrupt IN reports
        if (maxHidPacketSize in 1..8) return UsbPeripheralKind.MOUSE
        if (hasNonBootDigitizerHid && maxHidPacketSize > 8) return UsbPeripheralKind.TABLET
        return null
    }

    private fun buildDeviceKey(device: UsbDevice): String =
        "${hexId(device.vendorId)}:${hexId(device.productId)}:${device.deviceId}"

    private fun hexId(v: Int): String = v.toString(16).uppercase().padStart(4, '0')

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.replace(" ", "")
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.aistudio.kodawintab.USB_PERMISSION"
        const val VID_WACOM = 0x056A
        const val VID_HUION = 0x256C
        const val VID_UGEE_XPPEN = 0x28BD
        val KNOWN_TABLET_VIDS = setOf(VID_WACOM, VID_HUION, VID_UGEE_XPPEN, 0x172F, 0x2179, 0x2FEB)
        val KNOWN_MOUSE_VIDS = setOf(0x046D, 0x1532, 0x093A, 0x1B1C, 0x1038, 0x045E, 0x258A, 0x1BCF, 0x3938)
        private val TABLET_KEYWORDS = listOf("tablet", "ugee", "huion", "wacom", "xp-pen", "xppen", "gaomon", "veikk", "parblo", "stylus", "digitizer", "pen display")
        private val MOUSE_KEYWORDS = listOf("mouse", "optical", "trackball", "gaming mouse", "wireless receiver", "2.4g", "deathadder", "viper", "g102", "g203", "g305", "g502")
        private val KEYBOARD_KEYWORDS = listOf("keyboard", "teclado", "keypad", "mechanical")
        private val ADB_OR_SYSTEM_KEYWORDS = listOf("adb", "android", "mtp", "ptp", "mass storage", "flash drive", "usb hub", "ethernet")

        fun isAdbOrSystemClass(cls: Int, subclass: Int, protocol: Int): Boolean {
            if (cls == UsbConstants.USB_CLASS_VENDOR_SPEC && subclass == 0x42 && protocol == 0x01) {
                return true // Android ADB Debug Bridge interface
            }
            return cls == UsbConstants.USB_CLASS_MASS_STORAGE ||
                cls == UsbConstants.USB_CLASS_HUB ||
                cls == UsbConstants.USB_CLASS_AUDIO ||
                cls == UsbConstants.USB_CLASS_COMM ||
                cls == UsbConstants.USB_CLASS_CDC_DATA ||
                cls == UsbConstants.USB_CLASS_VIDEO ||
                cls == UsbConstants.USB_CLASS_STILL_IMAGE ||
                cls == UsbConstants.USB_CLASS_PRINTER
        }
    }
}
