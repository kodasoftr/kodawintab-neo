package com.example.hub

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import com.example.koda.engine.KodaEngineClient
import com.example.koda.engine.KodaEngineProtocol
import com.example.koda.engine.UsbPortPowerResult
import com.example.usb.UsbPeripheralKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

enum class HubSlotId(
    val label: String,
    val kind: UsbPeripheralKind,
    val virtualDeviceId: Int
) {
    MOUSE_1("Mouse 1", UsbPeripheralKind.MOUSE, 101),
    MOUSE_2("Mouse 2", UsbPeripheralKind.MOUSE, 102),
    KEYBOARD_1("Teclado 1", UsbPeripheralKind.KEYBOARD, 103),
    KEYBOARD_2("Teclado 2", UsbPeripheralKind.KEYBOARD, 104),
    TABLET_1("Mesa 1", UsbPeripheralKind.TABLET, 105),
    TABLET_2("Mesa 2", UsbPeripheralKind.TABLET, 106);

    fun cursorChannel(): Int = if (this == MOUSE_2 || this == TABLET_2) 2 else 1

    fun pairedCursorSlots(): Set<HubSlotId> = when (cursorChannel()) {
        2 -> setOf(MOUSE_2, TABLET_2)
        else -> setOf(MOUSE_1, TABLET_1)
    }
}

enum class RoutingMode {
    ROUTED,
    COOP
}

data class InstalledAppEntry(
    val packageName: String,
    val label: String
)

data class HubSlotAssignment(
    val slotId: HubSlotId,
    val physicalDeviceKey: String? = null,
    val physicalDeviceName: String = "Nenhum atribuído",
    val secondaryDeviceKey: String? = null,
    val secondaryDeviceName: String? = null,
    val targetAppPackage: String? = null,
    val targetAppLabel: String = "Global (Sistema)",
    val routingMode: RoutingMode = RoutingMode.COOP,
    val customCursor: DecodedCursor? = null
) {
    fun deviceSummaryLabel(): String {
        return when {
            physicalDeviceKey != null && secondaryDeviceKey != null && !secondaryDeviceName.isNullOrBlank() ->
                "$physicalDeviceName + $secondaryDeviceName"
            physicalDeviceKey != null -> physicalDeviceName
            secondaryDeviceKey != null && !secondaryDeviceName.isNullOrBlank() -> secondaryDeviceName
            else -> "Nenhum atribuído"
        }
    }
}

data class CoopBannerEvent(
    val message: String,
    val closedAppLabel: String,
    val timestampMs: Long
)

enum class HubOperationMode(
    val protocolCode: Int,
    val title: String,
    val subtitle: String
) {
    STANDARD(
        protocolCode = KodaEngineProtocol.HUB_MODE_STANDARD,
        title = "Hub Padrão Seletivo",
        subtitle = "Reivindicação seletiva de Mesa e Mouse · ADB/APK livre no sistema"
    ),
    MULTI_PERIPHERAL_3PLUS(
        protocolCode = KodaEngineProtocol.HUB_MODE_MULTI_3PLUS,
        title = "Hub 3+ Periféricos",
        subtitle = "Mouse + Teclado (pass-through) + Mesa simultâneos sem travar OTG"
    ),
    ENERGETIC_CHARGING(
        protocolCode = KodaEngineProtocol.HUB_MODE_ENERGETIC_PD,
        title = "Hub Energético (Carga + OTG)",
        subtitle = "Negocia USB-C PD (sink+host) para carregar celular e alimentar Hub"
    )
}

data class HubPowerTelemetry(
    val operationMode: HubOperationMode = HubOperationMode.STANDARD,
    val isCharging: Boolean = false,
    val batteryPercent: Int = 0,
    val pluggedLabel: String = "Bateria interna",
    val currentMa: Int = 0,
    val voltageMv: Int = 0,
    val usbPortId: String = "port0",
    val powerRole: String = "desconhecido",
    val dataRole: String = "desconhecido",
    val supportsDualRole: Boolean = false,
    val statusDetail: String = "Pronto para operar em Modo Padrão OTG"
)

/**
 * Manages the 6-slot Multi-Device Hub (2 Mice + 2 Keyboards + 2 Tablets) with distinct virtual
 * deviceIds, combined Mouse+Tablet simultaneous slot attribution, per-window routing, global
 * .cur/.ani/.png cursor overlay sync, and automatic 3-tier Coop Mode transition.
 */
class HubManager(
    private val context: Context,
    private val engineClient: KodaEngineClient,
    private val cursorOverlayManager: CursorOverlayManager? = null
) {
    private val _slots = MutableStateFlow(
        HubSlotId.entries.map { HubSlotAssignment(slotId = it) }
    )
    val slots: StateFlow<List<HubSlotAssignment>> = _slots.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppEntry>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppEntry>> = _installedApps.asStateFlow()

    private val _coopEvent = MutableStateFlow<CoopBannerEvent?>(null)
    val coopEvent: StateFlow<CoopBannerEvent?> = _coopEvent.asStateFlow()

    private val _simulatedClosedPackages = MutableStateFlow<Set<String>>(emptySet())
    val simulatedClosedPackages: StateFlow<Set<String>> = _simulatedClosedPackages.asStateFlow()

    private val prefs = context.getSharedPreferences(PREFS_HUB, Context.MODE_PRIVATE)

    private val _powerTelemetry = MutableStateFlow(
        HubPowerTelemetry(
            operationMode = runCatching {
                HubOperationMode.valueOf(
                    prefs.getString(KEY_HUB_MODE, HubOperationMode.STANDARD.name)
                        ?: HubOperationMode.STANDARD.name
                )
            }.getOrDefault(HubOperationMode.STANDARD)
        )
    )
    val powerTelemetry: StateFlow<HubPowerTelemetry> = _powerTelemetry.asStateFlow()

    @Volatile var onOperationModeChanged: ((HubOperationMode) -> Unit)? = null

    private var monitorJob: Job? = null

    fun currentOperationMode(): HubOperationMode = _powerTelemetry.value.operationMode

    fun refreshPowerTelemetry(shellResult: UsbPortPowerResult? = null) {
        val battIntent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = battIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100)?.coerceAtLeast(1) ?: 100
        val pct = if (level >= 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else _powerTelemetry.value.batteryPercent
        val status = battIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = battIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val voltageMv = battIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val rawUa = runCatching { bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0 }.getOrDefault(0)
        val currentMa = if (abs(rawUa) > 10_000) rawUa / 1000 else rawUa

        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0
        val pluggedLabel = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "Carregador AC / USB-C PD"
            BatteryManager.BATTERY_PLUGGED_USB -> "Porta USB / Hub Energético"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Carregador Sem Fio"
            else -> if (isCharging) "Fonte Externa Conectada" else "Sem carregador externo (Bateria)"
        }

        val cur = _powerTelemetry.value
        _powerTelemetry.value = cur.copy(
            isCharging = isCharging,
            batteryPercent = pct,
            pluggedLabel = pluggedLabel,
            currentMa = currentMa,
            voltageMv = voltageMv,
            usbPortId = shellResult?.portId ?: cur.usbPortId,
            powerRole = shellResult?.powerRole ?: cur.powerRole,
            dataRole = shellResult?.dataRole ?: cur.dataRole,
            supportsDualRole = shellResult?.supportsDualRole ?: cur.supportsDualRole,
            statusDetail = shellResult?.detail ?: cur.statusDetail
        )
    }

    fun selectHubOperationMode(mode: HubOperationMode): HubPowerTelemetry {
        prefs.edit().putString(KEY_HUB_MODE, mode.name).apply()
        val prev = _powerTelemetry.value
        val localHint = when (mode) {
            HubOperationMode.STANDARD ->
                "Modo Padrão OTG ativo · Mesa e Mouse seletivos."
            HubOperationMode.MULTI_PERIPHERAL_3PLUS ->
                "Hub 3+ Periféricos ativo · Escalonamento USB não-bloqueante para Mouse + Teclado + Mesa."
            HubOperationMode.ENERGETIC_CHARGING ->
                "Hub Energético ativo · Monitorando carregamento USB PD + portas OTG simultâneas."
        }
        _powerTelemetry.value = prev.copy(
            operationMode = mode,
            statusDetail = localHint
        )
        onOperationModeChanged?.invoke(mode)

        val shellRes = if (engineClient.state.value.isActive) {
            engineClient.applyHubPrivilegeMode(mode.protocolCode)
        } else {
            null
        }
        refreshPowerTelemetry(shellRes)
        return _powerTelemetry.value
    }

    fun syncModeWithEngineIfNeeded() {
        val mode = _powerTelemetry.value.operationMode
        val shellRes = if (engineClient.state.value.isActive) {
            if (mode == HubOperationMode.STANDARD) {
                engineClient.queryUsbPortPower()
            } else {
                engineClient.applyHubPrivilegeMode(mode.protocolCode)
            }
        } else {
            null
        }
        refreshPowerTelemetry(shellRes)
    }

    companion object {
        private const val PREFS_HUB = "koda_hub_prefs"
        private const val KEY_HUB_MODE = "hub_operation_mode"
    }

    fun loadLaunchableApps() {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        val apps = resolved
            .map { info ->
                InstalledAppEntry(
                    packageName = info.activityInfo.packageName,
                    label = info.loadLabel(pm).toString()
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
        _installedApps.value = apps
    }

    fun assignPhysicalDevice(slotId: HubSlotId, deviceKey: String?, deviceName: String) {
        _slots.value = _slots.value.map { slot ->
            if (slot.slotId == slotId) {
                slot.copy(
                    physicalDeviceKey = deviceKey,
                    physicalDeviceName = deviceName,
                    secondaryDeviceKey = if (deviceKey == null) null else slot.secondaryDeviceKey,
                    secondaryDeviceName = if (deviceKey == null) null else slot.secondaryDeviceName
                )
            } else slot
        }
    }

    fun assignCombinedDevices(
        slotId: HubSlotId,
        primaryKey: String?,
        primaryName: String,
        secondaryKey: String?,
        secondaryName: String?
    ) {
        _slots.value = _slots.value.map { slot ->
            if (slot.slotId == slotId) {
                slot.copy(
                    physicalDeviceKey = primaryKey,
                    physicalDeviceName = primaryName,
                    secondaryDeviceKey = secondaryKey,
                    secondaryDeviceName = secondaryName
                )
            } else slot
        }
    }

    fun assignTargetApp(slotId: HubSlotId, app: InstalledAppEntry?) {
        if (app != null) {
            _simulatedClosedPackages.value = _simulatedClosedPackages.value - app.packageName
        }
        _slots.value = _slots.value.map { slot ->
            if (slot.slotId == slotId) {
                if (app == null) {
                    slot.copy(
                        targetAppPackage = null,
                        targetAppLabel = "Global (Sistema)",
                        routingMode = RoutingMode.COOP
                    )
                } else {
                    slot.copy(
                        targetAppPackage = app.packageName,
                        targetAppLabel = app.label,
                        routingMode = RoutingMode.ROUTED
                    )
                }
            } else slot
        }
    }

    fun assignCustomCursor(slotId: HubSlotId, cursor: DecodedCursor?, rawBytes: ByteArray? = null) {
        val paired = slotId.pairedCursorSlots()
        val channel = slotId.cursorChannel()
        _slots.value = _slots.value.map { slot ->
            if (slot.slotId in paired) slot.copy(customCursor = cursor) else slot
        }
        cursorOverlayManager?.persistCursorForChannel(channel, cursor, rawBytes)
        cursorOverlayManager?.setChannelCursor(channel, cursor)
    }

    fun updateSlotCursorHotspot(slotId: HubSlotId, hotspotX: Float, hotspotY: Float) {
        val paired = slotId.pairedCursorSlots()
        val channel = slotId.cursorChannel()
        var updatedCursor: DecodedCursor? = null
        _slots.value = _slots.value.map { slot ->
            val cur = slot.customCursor
            if (slot.slotId in paired && cur != null) {
                val next = cur.withCustomHotspot(hotspotX, hotspotY)
                updatedCursor = next
                slot.copy(customCursor = next)
            } else slot
        }
        updatedCursor?.let { next ->
            cursorOverlayManager?.updatePersistedHotspot(channel, next.hotspotX, next.hotspotY)
            cursorOverlayManager?.setChannelCursor(channel, next)
        }
    }

    fun restorePersistedCursors() {
        val mgr = cursorOverlayManager ?: return
        val (ch1, ch2) = mgr.restorePersistedCursors()
        if (ch1 != null || ch2 != null) {
            _slots.value = _slots.value.map { slot ->
                when (slot.slotId) {
                    HubSlotId.MOUSE_1, HubSlotId.TABLET_1 -> slot.copy(customCursor = ch1)
                    HubSlotId.MOUSE_2, HubSlotId.TABLET_2 -> slot.copy(customCursor = ch2)
                    else -> slot
                }
            }
        }
    }

    fun resolveChannelForDeviceKey(deviceKey: String): Int {
        val match = _slots.value.firstOrNull {
            it.physicalDeviceKey == deviceKey || it.secondaryDeviceKey == deviceKey
        }
        return match?.slotId?.cursorChannel() ?: 1
    }

    fun getPrimaryActiveCursor(): DecodedCursor? {
        val preferredOrder = listOf(HubSlotId.MOUSE_1, HubSlotId.TABLET_1, HubSlotId.MOUSE_2, HubSlotId.TABLET_2)
        for (id in preferredOrder) {
            val found = _slots.value.firstOrNull { it.slotId == id }?.customCursor
            if (found != null) return found
        }
        return _slots.value.firstNotNullOfOrNull { it.customCursor }
    }

    fun syncActiveCursorToOverlay() {
        val ch1 = _slots.value.firstOrNull { it.slotId == HubSlotId.MOUSE_1 }?.customCursor
            ?: _slots.value.firstOrNull { it.slotId == HubSlotId.TABLET_1 }?.customCursor
        val ch2 = _slots.value.firstOrNull { it.slotId == HubSlotId.MOUSE_2 }?.customCursor
            ?: _slots.value.firstOrNull { it.slotId == HubSlotId.TABLET_2 }?.customCursor
        cursorOverlayManager?.setChannelCursor(1, ch1)
        cursorOverlayManager?.setChannelCursor(2, ch2)
    }

    fun toggleSimulatedAppClosed(packageName: String, appLabel: String) {
        val currentlyClosed = packageName in _simulatedClosedPackages.value
        if (currentlyClosed) {
            _simulatedClosedPackages.value = _simulatedClosedPackages.value - packageName
            reconcileCoopState(activePackages = setOf(packageName), forceRestorePackages = setOf(packageName))
            _coopEvent.value = null
        } else {
            _simulatedClosedPackages.value = _simulatedClosedPackages.value + packageName
            transitionPackageToCoop(packageName, appLabel)
        }
    }

    fun dismissCoopBanner() {
        _coopEvent.value = null
    }

    fun startCoopMonitor(scope: CoroutineScope) {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(1200L)
                val active = detectForegroundOrRunningPackages()
                if (active != null) {
                    reconcileCoopState(activePackages = active, forceRestorePackages = emptySet())
                }
            }
        }
    }

    fun stopCoopMonitor() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun detectForegroundOrRunningPackages(): Set<String>? {
        val engineActive = engineClient.queryActiveForegroundPackages()
        if (engineActive != null) {
            return engineActive - _simulatedClosedPackages.value
        }

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        if (usm != null) {
            val end = System.currentTimeMillis()
            val begin = end - 5000L
            val events = runCatching { usm.queryEvents(begin, end) }.getOrNull()
            if (events != null) {
                val active = HashSet<String>()
                val ev = UsageEvents.Event()
                while (events.hasNextEvent()) {
                    events.getNextEvent(ev)
                    if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                        active.add(ev.packageName)
                    } else if (ev.eventType == UsageEvents.Event.ACTIVITY_STOPPED) {
                        active.remove(ev.packageName)
                    }
                }
                if (active.isNotEmpty()) {
                    return active - _simulatedClosedPackages.value
                }
            }
        }
        return null
    }

    fun transitionPackageToCoop(packageName: String, appLabel: String) {
        var affected = false
        _slots.value = _slots.value.map { slot ->
            if (slot.targetAppPackage == packageName && slot.routingMode != RoutingMode.COOP) {
                affected = true
                slot.copy(routingMode = RoutingMode.COOP)
            } else slot
        }
        if (affected) {
            _coopEvent.value = CoopBannerEvent(
                message = "App fechado — dispositivos em modo Coop",
                closedAppLabel = appLabel,
                timestampMs = System.currentTimeMillis()
            )
        }
    }

    private fun reconcileCoopState(activePackages: Set<String>, forceRestorePackages: Set<String>) {
        var closedLabel: String? = null
        _slots.value = _slots.value.map { slot ->
            val pkg = slot.targetAppPackage ?: return@map slot
            val isClosed = pkg in _simulatedClosedPackages.value ||
                (pkg !in activePackages && pkg !in forceRestorePackages)

            if (isClosed && slot.routingMode == RoutingMode.ROUTED) {
                closedLabel = slot.targetAppLabel
                slot.copy(routingMode = RoutingMode.COOP)
            } else if (!isClosed && slot.routingMode == RoutingMode.COOP) {
                slot.copy(routingMode = RoutingMode.ROUTED)
            } else {
                slot
            }
        }
        closedLabel?.let { label ->
            _coopEvent.value = CoopBannerEvent(
                message = "App fechado — dispositivos em modo Coop",
                closedAppLabel = label,
                timestampMs = System.currentTimeMillis()
            )
        }
    }
}
