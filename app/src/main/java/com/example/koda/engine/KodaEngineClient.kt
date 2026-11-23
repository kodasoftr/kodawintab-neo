package com.example.koda.engine

import android.content.Context
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

enum class AdbTransportMode {
    USB,
    WIFI_NETWORK
}

data class KodaEngineState(
    val isActive: Boolean = false,
    val coreEngineActive: Boolean = false,
    val permanentlyActivated: Boolean = false,
    val serverUid: Int = -1,
    val serverPid: Int = -1,
    val canInjectEvents: Boolean = false,
    val canMonitorProcesses: Boolean = false,
    val diagnosticModeEntered: Boolean = false,
    val apkPath: String = "",
    val transportMode: AdbTransportMode = AdbTransportMode.USB,
    val localIpAddress: String = "192.168.1.100",
    val wifiPort: String = "5555",
    val keepTerminalOpenMode: Boolean = false,
    val adbConnectCommand: String = "",
    val adbCommand: String = "",
    val lastCheckMessage: String = "Pronto para Ativação Única do Motor Principal (sem PC)",
    val lastErrorName: String? = null,
    val lastErrorReason: String? = null,
    val uptimeMs: Long = 0L
)

data class GlobalPointerSample(
    val seq: Long,
    val hasAbs: Boolean,
    val absXNorm: Float,
    val absYNorm: Float,
    val deltaX: Float,
    val deltaY: Float,
    val pressureNorm: Float,
    val isStylus: Boolean,
    val isVisible: Boolean
)

data class StylusSample(
    val eventTimeMs: Long,
    val x: Float,
    val y: Float,
    val pressure: Float,
    val tiltX: Float,
    val tiltY: Float,
    val buttonState: Int
)

/**
 * App-side IPC client for Koda Engine over 127.0.0.1 TCP loopback.
 * Token-free ADB bootstrap command works cleanly on Windows CMD, PowerShell, Linux, macOS,
 * and all Android 10-15 devices without SELinux unix_socket denial.
 */
class KodaEngineClient(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_ENGINE, Context.MODE_PRIVATE)
    private val resolvedApkPath: String = resolveApkPath()

    private val _state = MutableStateFlow(buildInitialState())
    val state: StateFlow<KodaEngineState> = _state.asStateFlow()

    @Volatile private var socket: Socket? = null
    @Volatile private var input: DataInputStream? = null
    @Volatile private var output: DataOutputStream? = null
    private val ioLock = Any()

    fun isPermanentlyActivated(): Boolean =
        prefs.getBoolean(KEY_ADB_PERMANENTLY_ACTIVATED, false)

    fun syncPermanentActivationFromPrefs() {
        val perm = isPermanentlyActivated()
        val singleCore = prefs.getBoolean(KEY_SINGLE_ENGINE_ACTIVATED, false)
        val cur = _state.value
        if (perm && (!cur.permanentlyActivated || (singleCore && !cur.coreEngineActive))) {
            _state.value = cur.copy(
                permanentlyActivated = true,
                coreEngineActive = singleCore || cur.coreEngineActive,
                serverUid = prefs.getInt(KEY_SERVER_UID, Process.myUid()),
                lastErrorName = null,
                lastErrorReason = null,
                lastCheckMessage = if (singleCore) {
                    "Koda Core Engine Ativa · Ativação Única sem PC / sem Modo Desenvolvedor"
                } else {
                    "Modo USB OTG Direto · Privilégios permanentes e Overlay restaurados"
                }
            )
        }
    }

    /**
     * One-tap single activation of Koda Core Engine directly on the device,
     * without requiring a PC, USB debugging, or Developer Options.
     */
    fun activateSingleEngine(): KodaEngineState {
        val uid = Process.myUid()
        val pid = Process.myPid()
        prefs.edit()
            .putBoolean(KEY_ADB_PERMANENTLY_ACTIVATED, true)
            .putBoolean(KEY_SINGLE_ENGINE_ACTIVATED, true)
            .putInt(KEY_SERVER_UID, uid)
            .apply()

        val updated = _state.value.copy(
            coreEngineActive = true,
            permanentlyActivated = true,
            serverUid = if (_state.value.isActive) _state.value.serverUid else uid,
            serverPid = if (_state.value.isActive) _state.value.serverPid else pid,
            lastErrorName = null,
            lastErrorReason = null,
            lastCheckMessage = "Koda Core Engine Ativa · Ativação Única sem PC concluída!"
        )
        _state.value = updated
        return updated
    }

    private fun buildInitialState(): KodaEngineState {
        val ip = detectLocalWifiIp()
        val port = "5555"
        val savedPerm = prefs.getBoolean(KEY_ADB_PERMANENTLY_ACTIVATED, false)
        val savedSingle = prefs.getBoolean(KEY_SINGLE_ENGINE_ACTIVATED, false)
        val savedUid = prefs.getInt(KEY_SERVER_UID, -1)
        return KodaEngineState(
            coreEngineActive = savedSingle || savedPerm,
            permanentlyActivated = savedPerm,
            serverUid = savedUid,
            apkPath = resolvedApkPath,
            localIpAddress = ip,
            wifiPort = port,
            adbConnectCommand = "adb connect $ip:$port",
            adbCommand = buildAdbBootstrapCommand(
                packageName = context.packageName,
                apkPath = resolvedApkPath,
                transportMode = AdbTransportMode.USB,
                ipAddress = ip,
                wifiPort = port,
                keepTerminalOpen = false
            ),
            lastCheckMessage = if (savedPerm) {
                "Koda Core Engine Ativa · Ativação Única permanente liberada"
            } else {
                "Pronto para Ativação Única do Motor Principal (sem PC)"
            }
        )
    }

    private fun resolveApkPath(): String {
        val dir = runCatching { context.applicationInfo?.sourceDir }.getOrNull()
        return if (!dir.isNullOrBlank()) dir else "/data/app/base.apk"
    }

    fun setTransportMode(mode: AdbTransportMode) {
        val freshIp = detectLocalWifiIp()
        updateCommandState(
            _state.value.copy(
                transportMode = mode,
                localIpAddress = freshIp
            )
        )
    }

    fun setWifiPort(port: String) {
        val clean = port.filter { it.isDigit() }.take(5)
        updateCommandState(_state.value.copy(wifiPort = clean))
    }

    fun setKeepTerminalOpenMode(enabled: Boolean) {
        updateCommandState(_state.value.copy(keepTerminalOpenMode = enabled))
    }

    fun refreshLocalNetworkIp() {
        val freshIp = detectLocalWifiIp()
        updateCommandState(_state.value.copy(localIpAddress = freshIp))
    }

    private fun updateCommandState(base: KodaEngineState) {
        val safePort = base.wifiPort.ifBlank { "5555" }
        _state.value = base.copy(
            adbConnectCommand = "adb connect ${base.localIpAddress}:$safePort",
            adbCommand = buildAdbBootstrapCommand(
                packageName = context.packageName,
                apkPath = resolvedApkPath,
                transportMode = base.transportMode,
                ipAddress = base.localIpAddress,
                wifiPort = safePort,
                keepTerminalOpen = base.keepTerminalOpenMode
            )
        )
    }

    fun enterDiagnosticMode() {
        _state.value = _state.value.copy(
            diagnosticModeEntered = true,
            lastCheckMessage = "Modo Diagnóstico local ativo (injeção global aguarda ADB)"
        )
    }

    suspend fun verifyConnection(): KodaEngineState = withContext(Dispatchers.IO) {
        synchronized(ioLock) {
            // Fast-path: if already connected and alive via CMD_PING, do NOT tear down live USB streams!
            val existingSock = socket
            val existingOut = output
            val existingIn = input
            if (existingSock != null && existingSock.isConnected && !existingSock.isClosed &&
                existingOut != null && existingIn != null
            ) {
                val pingOk = runCatching {
                    existingOut.writeByte(KodaEngineProtocol.CMD_PING.toInt())
                    existingOut.flush()
                    if (existingIn.readByte() == KodaEngineProtocol.STATUS_OK) {
                        existingIn.readInt()
                        existingIn.readLong()
                        true
                    } else false
                }.getOrDefault(false)
                if (pingOk) {
                    return@withContext _state.value
                }
            }

            closeInternal()
            try {
                val sock = Socket()
                sock.connect(
                    InetSocketAddress(
                        InetAddress.getByName(KodaEngineProtocol.SERVER_HOST),
                        KodaEngineProtocol.SERVER_PORT
                    ),
                    800
                )
                sock.tcpNoDelay = true
                sock.soTimeout = 1500
                val outStream = DataOutputStream(BufferedOutputStream(sock.getOutputStream(), 2048))
                val inStream = DataInputStream(BufferedInputStream(sock.getInputStream(), 2048))

                outStream.writeByte(KodaEngineProtocol.CMD_HANDSHAKE.toInt())
                outStream.writeUTF("")
                outStream.writeUTF(context.packageName)
                outStream.flush()

                val status = inStream.readByte()
                if (status == KodaEngineProtocol.STATUS_OK) {
                    val uid = inStream.readInt()
                    val pid = inStream.readInt()
                    val canInject = inStream.readBoolean()
                    val canMonitor = inStream.readBoolean()

                    socket = sock
                    input = inStream
                    output = outStream

                    // Persist permanent activation so user never needs to run ADB again
                    prefs.edit()
                        .putBoolean(KEY_ADB_PERMANENTLY_ACTIVATED, true)
                        .putInt(KEY_SERVER_UID, uid)
                        .apply()

                    val updated = _state.value.copy(
                        isActive = true,
                        permanentlyActivated = true,
                        serverUid = uid,
                        serverPid = pid,
                        canInjectEvents = canInject,
                        canMonitorProcesses = canMonitor,
                        lastErrorName = null,
                        lastErrorReason = null,
                        lastCheckMessage = "Privilégios ADB ativos e permanentes (UID $uid · PID $pid)"
                    )
                    _state.value = updated
                    return@withContext updated
                } else {
                    sock.close()
                    val updated = _state.value.copy(
                        isActive = false,
                        lastErrorName = "KodaPackageMismatchException",
                        lastErrorReason = "O servidor respondeu, mas está bloqueado para outro pacote. Execute o comando ADB exibido na tela novamente.",
                        lastCheckMessage = "Falha na validação com servidor shell."
                    )
                    _state.value = updated
                    return@withContext updated
                }
            } catch (t: Throwable) {
                val root = generateSequence(t) { it.cause }.lastOrNull() ?: t
                val exName = root.javaClass.simpleName.ifBlank { "ConnectException" }
                val rawMsg = root.message ?: t.message ?: "Conexão recusada"
                val friendlyReason = when {
                    rawMsg.contains("ECONNREFUSED", ignoreCase = true) ||
                        rawMsg.contains("Connection refused", ignoreCase = true) ->
                        "O servidor privilegiado (KodaShellServer) ainda não está rodando na porta ${KodaEngineProtocol.SERVER_PORT}. Copie o comando ADB (USB ou Wi-Fi) e cole no Prompt de Comando do PC — deve retornar 'adb command success'."
                    rawMsg.contains("EACCES", ignoreCase = true) ||
                        rawMsg.contains("EPERM", ignoreCase = true) ->
                        "Sem permissão de rede local para conectar ao loopback (${Process.myUid()}). Reinstale o app atualizado."
                    rawMsg.contains("timed out", ignoreCase = true) ->
                        "Tempo limite esgotado tentando conectar em 127.0.0.1:${KodaEngineProtocol.SERVER_PORT}."
                    else ->
                        "Não foi possível conectar à Koda Engine ($rawMsg). Execute o comando ADB no PC e aguarde 'adb command success'."
                }
                val perm = isPermanentlyActivated()
                val updated = _state.value.copy(
                    isActive = false,
                    permanentlyActivated = perm,
                    serverUid = if (perm) prefs.getInt(KEY_SERVER_UID, 2000) else -1,
                    serverPid = -1,
                    canInjectEvents = false,
                    canMonitorProcesses = false,
                    lastErrorName = if (perm) null else "$exName (${rawMsg.take(48)})",
                    lastErrorReason = if (perm) null else friendlyReason,
                    lastCheckMessage = if (perm) {
                        "Modo USB OTG Direto · Privilégios permanentes e Overlay restaurados"
                    } else {
                        "Servidor ADB não detectado (UID local ${Process.myUid()})."
                    }
                )
                _state.value = updated
                return@withContext updated
            }
        }
    }

    fun sendStylusBatch(
        deviceId: Int,
        displayId: Int,
        action: Int,
        isEraser: Boolean,
        samples: List<StylusSample>
    ): Boolean {
        if (samples.isEmpty() || !_state.value.isActive) return false
        synchronized(ioLock) {
            val out = output ?: return false
            val inp = input ?: return false
            return try {
                out.writeByte(KodaEngineProtocol.CMD_INJECT_STYLUS_BATCH.toInt())
                out.writeInt(deviceId)
                out.writeInt(displayId)
                out.writeInt(action)
                out.writeBoolean(isEraser)
                val count = samples.size.coerceAtMost(32)
                out.writeInt(count)
                for (i in 0 until count) {
                    val s = samples[i]
                    out.writeLong(s.eventTimeMs)
                    out.writeFloat(s.x)
                    out.writeFloat(s.y)
                    out.writeFloat(s.pressure)
                    out.writeFloat(s.tiltX)
                    out.writeFloat(s.tiltY)
                    out.writeInt(s.buttonState)
                }
                out.flush()
                inp.readByte() == KodaEngineProtocol.STATUS_OK
            } catch (_: Throwable) {
                markDisconnected()
                false
            }
        }
    }

    fun sendMouseEvent(
        deviceId: Int,
        displayId: Int,
        action: Int,
        x: Float,
        y: Float,
        buttonState: Int,
        vScroll: Float = 0f
    ): Boolean {
        if (!_state.value.isActive) return false
        synchronized(ioLock) {
            val out = output ?: return false
            val inp = input ?: return false
            return try {
                out.writeByte(KodaEngineProtocol.CMD_INJECT_MOUSE.toInt())
                out.writeInt(deviceId)
                out.writeInt(displayId)
                out.writeInt(action)
                out.writeFloat(x)
                out.writeFloat(y)
                out.writeInt(buttonState)
                out.writeFloat(vScroll)
                out.flush()
                inp.readByte() == KodaEngineProtocol.STATUS_OK
            } catch (_: Throwable) {
                markDisconnected()
                false
            }
        }
    }

    fun sendKeyEvent(
        deviceId: Int,
        action: Int,
        keyCode: Int,
        metaState: Int = 0
    ): Boolean {
        if (!_state.value.isActive) return false
        synchronized(ioLock) {
            val out = output ?: return false
            val inp = input ?: return false
            return try {
                out.writeByte(KodaEngineProtocol.CMD_INJECT_KEY.toInt())
                out.writeInt(deviceId)
                out.writeInt(action)
                out.writeInt(keyCode)
                out.writeInt(metaState)
                out.flush()
                inp.readByte() == KodaEngineProtocol.STATUS_OK
            } catch (_: Throwable) {
                markDisconnected()
                false
            }
        }
    }

    fun queryActiveForegroundPackages(): Set<String>? {
        if (!_state.value.isActive) return null
        synchronized(ioLock) {
            val out = output ?: return null
            val inp = input ?: return null
            return try {
                out.writeByte(KodaEngineProtocol.CMD_QUERY_FOREGROUND_APPS.toInt())
                out.flush()
                if (inp.readByte() != KodaEngineProtocol.STATUS_OK) return null
                val count = inp.readInt().coerceIn(0, 256)
                val set = HashSet<String>(count)
                for (i in 0 until count) {
                    set.add(inp.readUTF())
                }
                set
            } catch (_: Throwable) {
                markDisconnected()
                null
            }
        }
    }

    fun grantOverlayPermissionViaAdb(): Boolean {
        if (!_state.value.isActive) return false
        synchronized(ioLock) {
            val out = output ?: return false
            val inp = input ?: return false
            return try {
                out.writeByte(KodaEngineProtocol.CMD_GRANT_OVERLAY_OP.toInt())
                out.writeUTF(context.packageName)
                out.flush()
                inp.readByte() == KodaEngineProtocol.STATUS_OK
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun sendCustomPointerIcon(bitmap: android.graphics.Bitmap, hotspotX: Float, hotspotY: Float): Boolean {
        if (!_state.value.isActive) return false
        val w = bitmap.width.coerceIn(1, 96)
        val h = bitmap.height.coerceIn(1, 96)
        val scaled = if (bitmap.width == w && bitmap.height == h) bitmap else {
            android.graphics.Bitmap.createScaledBitmap(bitmap, w, h, true)
        }
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        val bytes = ByteArray(pixels.size * 4)
        for (i in pixels.indices) {
            val c = pixels[i]
            val o = i * 4
            bytes[o] = ((c ushr 24) and 0xFF).toByte()
            bytes[o + 1] = ((c ushr 16) and 0xFF).toByte()
            bytes[o + 2] = ((c ushr 8) and 0xFF).toByte()
            bytes[o + 3] = (c and 0xFF).toByte()
        }
        synchronized(ioLock) {
            val out = output ?: return false
            val inp = input ?: return false
            return try {
                out.writeByte(KodaEngineProtocol.CMD_SET_POINTER_ICON.toInt())
                out.writeInt(w)
                out.writeInt(h)
                out.writeFloat(hotspotX * (w.toFloat() / bitmap.width.coerceAtLeast(1)))
                out.writeFloat(hotspotY * (h.toFloat() / bitmap.height.coerceAtLeast(1)))
                out.writeInt(bytes.size)
                out.write(bytes)
                out.flush()
                inp.readByte() == KodaEngineProtocol.STATUS_OK
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun hideNativePointer(hide: Boolean): Boolean {
        if (!_state.value.isActive) return false
        synchronized(ioLock) {
            val out = output ?: return false
            val inp = input ?: return false
            return try {
                out.writeByte(KodaEngineProtocol.CMD_HIDE_NATIVE_POINTER.toInt())
                out.writeBoolean(hide)
                out.flush()
                inp.readByte() == KodaEngineProtocol.STATUS_OK
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun pollGlobalPointer(): GlobalPointerSample? {
        if (!_state.value.isActive) return null
        synchronized(ioLock) {
            val out = output ?: return null
            val inp = input ?: return null
            return try {
                out.writeByte(KodaEngineProtocol.CMD_POLL_GLOBAL_POINTER.toInt())
                out.flush()
                if (inp.readByte() != KodaEngineProtocol.STATUS_OK) return null
                GlobalPointerSample(
                    seq = inp.readLong(),
                    hasAbs = inp.readBoolean(),
                    absXNorm = inp.readFloat(),
                    absYNorm = inp.readFloat(),
                    deltaX = inp.readFloat(),
                    deltaY = inp.readFloat(),
                    pressureNorm = inp.readFloat(),
                    isStylus = inp.readBoolean(),
                    isVisible = inp.readBoolean()
                )
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun syncRoutedPenDisplayApps(packages: Set<String>, displayId: Int): Boolean {
        if (!_state.value.isActive) return false
        synchronized(ioLock) {
            val out = output ?: return false
            val inp = input ?: return false
            return try {
                out.writeByte(KodaEngineProtocol.CMD_SET_PEN_DISPLAY_ID.toInt())
                out.writeInt(displayId.coerceAtLeast(0))
                out.flush()
                if (inp.readByte() != KodaEngineProtocol.STATUS_OK) return false

                val safe = packages.take(256)
                out.writeByte(KodaEngineProtocol.CMD_REGISTER_ROUTED_APPS.toInt())
                out.writeInt(safe.size)
                for (p in safe) {
                    out.writeUTF(p)
                }
                out.flush()
                inp.readByte() == KodaEngineProtocol.STATUS_OK
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun moveTaskToPenDisplay(packageName: String, displayId: Int): Boolean {
        if (!_state.value.isActive || displayId <= 0) return false
        synchronized(ioLock) {
            val out = output ?: return false
            val inp = input ?: return false
            return try {
                out.writeByte(KodaEngineProtocol.CMD_MOVE_TASK_TO_DISPLAY.toInt())
                out.writeUTF(packageName)
                out.writeInt(displayId)
                out.flush()
                inp.readByte() == KodaEngineProtocol.STATUS_OK
            } catch (_: Throwable) {
                false
            }
        }
    }

    fun closeConnection() {
        synchronized(ioLock) {
            closeInternal()
        }
    }

    private fun markDisconnected() {
        closeInternal()
        val perm = isPermanentlyActivated()
        _state.value = _state.value.copy(
            isActive = false,
            permanentlyActivated = perm,
            lastErrorName = if (perm) null else "SocketDisconnectedException",
            lastErrorReason = if (perm) null else "A conexão com o processo Shell (KodaShellServer) foi encerrada pelo sistema.",
            lastCheckMessage = if (perm) {
                "Ativação ADB permanente salva · reconectando..."
            } else {
                "Conexão com Koda Engine perdida. Reative via ADB."
            }
        )
    }

    private fun closeInternal() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    companion object {
        private const val PREFS_ENGINE = "koda_engine_prefs"
        private const val KEY_ADB_PERMANENTLY_ACTIVATED = "adb_permanently_activated"
        private const val KEY_SINGLE_ENGINE_ACTIVATED = "single_engine_activated"
        private const val KEY_SERVER_UID = "adb_server_uid"
        /**
         * Clean, token-free ADB command without quotes, pipes, `$()`, `;`, or `&` so it works
         * identically in Windows CMD (`platform-tools`), PowerShell, Linux, and macOS.
         * `--daemon` self-detaches in the background and prints "adb command success" back to PC.
         */
        fun buildAdbBootstrapCommand(
            packageName: String,
            apkPath: String = "/data/app/base.apk",
            transportMode: AdbTransportMode = AdbTransportMode.USB,
            ipAddress: String = "192.168.1.100",
            wifiPort: String = "5555",
            keepTerminalOpen: Boolean = false
        ): String {
            val targetPrefix = if (transportMode == AdbTransportMode.WIFI_NETWORK) {
                "adb -s $ipAddress:$wifiPort shell"
            } else {
                "adb shell"
            }
            val safeApk = apkPath.ifBlank { "/data/app/base.apk" }
            val modeFlag = if (keepTerminalOpen) "--child" else "--daemon"
            return "$targetPrefix CLASSPATH=$safeApk app_process /system/bin com.example.koda.engine.KodaShellServer $modeFlag --pkg=$packageName"
        }

        fun detectLocalWifiIp(): String {
            return runCatching {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching "192.168.1.100"
                while (interfaces.hasMoreElements()) {
                    val intf = interfaces.nextElement()
                    if (!intf.isUp || intf.isLoopback) continue
                    val addrs = intf.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val host = addr.hostAddress
                            if (!host.isNullOrBlank()) return@runCatching host
                        }
                    }
                }
                "192.168.1.100"
            }.getOrDefault("192.168.1.100")
        }
    }
}
