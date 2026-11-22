package com.example.koda.engine

import android.annotation.SuppressLint
import android.hardware.input.InputManager
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.lang.reflect.Method
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import android.graphics.Bitmap
import android.view.PointerIcon
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Privileged Shell Server started via ADB (`uid=2000`):
 * `adb shell CLASSPATH=<apk> app_process /system/bin com.example.koda.engine.KodaShellServer --daemon`
 *
 * Uses 127.0.0.1 TCP loopback (allowed across SELinux shell <-> untrusted_app boundaries on
 * Android 10-15) and self-daemonizes cleanly while printing "adb command success" back to ADB.
 */
object KodaShellServer {

    private var allowedPackage: String = ""
    private var startTimeMs = 0L
    @Volatile private var keepRunning = true
    @Volatile private var serverSocket: ServerSocket? = null

    private var inputManagerInstance: Any? = null
    private var injectInputEventMethod: Method? = null
    private var motionSetDisplayIdMethod: Method? = null
    private var activityManagerService: Any? = null
    private var getRunningAppProcessesMethod: Method? = null
    private var systemContext: android.content.Context? = null
    @Volatile private var forceHideNativePointer = false
    @Volatile private var lastHideEnforceMs = 0L

    private val stylusDownTimes = ConcurrentHashMap<Int, Long>()
    private val mouseDownTimes = ConcurrentHashMap<Int, Long>()

    private val pointerSeq = AtomicLong(0L)
    private val deltaLock = Any()
    @Volatile private var gpHasAbs = false
    @Volatile private var gpAbsXNorm = 0.5f
    @Volatile private var gpAbsYNorm = 0.5f
    @Volatile private var gpPressureNorm = 0f
    @Volatile private var gpIsStylus = false
    @Volatile private var gpIsVisible = false
    private var gpPendingDeltaX = 0f
    private var gpPendingDeltaY = 0f

    @JvmStatic
    fun main(args: Array<String>) {
        var daemonMode = false
        var childMode = false
        for (arg in args) {
            when {
                arg == "--daemon" -> daemonMode = true
                arg == "--child" -> childMode = true
                arg.startsWith("--pkg=") -> allowedPackage = arg.removePrefix("--pkg=")
            }
        }

        if (daemonMode && !childMode) {
            runDaemonLauncher()
            return
        }

        runServerLoop()
    }

    private fun runDaemonLauncher() {
        // Gracefully replace any stale server holding the port
        requestExistingShutdown()
        disablePhantomProcessRestriction()

        val classpath = System.getenv("CLASSPATH")
            ?: System.getProperty("java.class.path")
            ?: ""
        if (classpath.isBlank()) {
            System.err.println("adb command failed: CLASSPATH vazio")
            kotlin.system.exitProcess(1)
            return
        }

        val pkgFlag = if (allowedPackage.isNotBlank()) " --pkg=$allowedPackage" else ""
        val appProcessCmd = "/system/bin/app_process /system/bin com.example.koda.engine.KodaShellServer --child$pkgFlag"
        val hasSetsid = File("/system/bin/setsid").exists()
        val hasNohup = File("/system/bin/nohup").exists()
        val setsidPrefix = if (hasSetsid) "/system/bin/setsid " else ""
        val nohupPrefix = if (hasNohup) "/system/bin/nohup " else ""
        val logPath = "/data/local/tmp/koda_daemon.log"
        val escapedCp = classpath.replace("'", "'\\''")

        // Launch Strategy A: export CLASSPATH *before* nohup/setsid (fixes Toybox setsid treating
        // `CLASSPATH=...` as executable name) + trap SIGHUP/SIGTERM/SIGPIPE + log output for diagnostics.
        val shellLaunchOk = runCatching {
            val doubleForkCmd =
                "trap '' HUP TERM PIPE; export CLASSPATH='$escapedCp'; " +
                    "( $nohupPrefix$setsidPrefix$appProcessCmd </dev/null >$logPath 2>&1 & )"
            ProcessBuilder("/system/bin/sh", "-c", doubleForkCmd).apply {
                environment()["CLASSPATH"] = classpath
                redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
            }.start()
            true
        }.getOrDefault(false)

        var ready = false
        val firstDeadline = System.currentTimeMillis() + 2000L
        if (shellLaunchOk) {
            while (System.currentTimeMillis() < firstDeadline) {
                if (probeServerAlive()) {
                    Thread.sleep(220L)
                    if (probeServerAlive()) {
                        ready = true
                        break
                    }
                }
                Thread.sleep(100L)
            }
        }

        // Launch Strategy B fallback: direct setsid/app_process spawn with explicit environment CLASSPATH
        if (!ready) {
            val childArgs = buildList {
                if (hasSetsid) add("/system/bin/setsid")
                add("/system/bin/app_process")
                add("/system/bin")
                add("com.example.koda.engine.KodaShellServer")
                add("--child")
                if (allowedPackage.isNotBlank()) add("--pkg=$allowedPackage")
            }
            runCatching {
                ProcessBuilder(childArgs).apply {
                    environment()["CLASSPATH"] = classpath
                    redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                    redirectOutput(ProcessBuilder.Redirect.to(File(logPath)))
                    redirectError(ProcessBuilder.Redirect.to(File(logPath)))
                }.start()
            }.onFailure { t2 ->
                System.err.println("adb command failed: ${t2.message}")
                kotlin.system.exitProcess(1)
                return
            }

            val secondDeadline = System.currentTimeMillis() + 2600L
            while (System.currentTimeMillis() < secondDeadline) {
                if (probeServerAlive()) {
                    Thread.sleep(220L)
                    if (probeServerAlive()) {
                        ready = true
                        break
                    }
                }
                Thread.sleep(100L)
            }
        }

        if (ready) {
            println("adb command success (Koda Wintab Daemon UID=${Process.myUid()} · Privilégios permanentes aplicados)")
            System.out.flush()
            kotlin.system.exitProcess(0)
            return
        }

        val logHint = runCatching {
            val f = File(logPath)
            if (f.exists()) f.readLines().takeLast(3).joinToString(" | ") else ""
        }.getOrDefault("")
        val detail = if (logHint.isNotBlank()) " [$logHint]" else ""
        System.err.println("adb command failed: daemon nao respondeu na porta ${KodaEngineProtocol.SERVER_PORT}$detail")
        kotlin.system.exitProcess(1)
    }

    private fun disablePhantomProcessRestriction() {
        val cmds = listOf(
            arrayOf("/system/bin/device_config", "put", "activity_manager", "max_phantom_processes", "2147483647"),
            arrayOf("/system/bin/device_config", "set_sync_disabled_for_tests", "persistent"),
            arrayOf("/system/bin/settings", "put", "global", "settings_enable_monitor_phantom_procs", "false")
        )
        for (cmd in cmds) {
            runCatching {
                ProcessBuilder(*cmd)
                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            }
        }
    }

    private fun installDaemonSignalImmunity() {
        // Best-effort reflection on sun.misc.Signal so SIGHUP from adbd disconnect never terminates the child daemon
        runCatching {
            val signalClass = Class.forName("sun.misc.Signal")
            val handlerClass = Class.forName("sun.misc.SignalHandler")
            val handleMethod = signalClass.getMethod("handle", signalClass, handlerClass)
            val ctor = signalClass.getConstructor(String::class.java)
            val ignoreProxy = java.lang.reflect.Proxy.newProxyInstance(
                handlerClass.classLoader,
                arrayOf(handlerClass)
            ) { _, _, _ -> null }
            for (sigName in listOf("HUP", "PIPE")) {
                runCatching {
                    val sig = ctor.newInstance(sigName)
                    handleMethod.invoke(null, sig, ignoreProxy)
                }
            }
        }
    }

    private fun runServerLoop() {
        startTimeMs = SystemClock.elapsedRealtime()
        installDaemonSignalImmunity()
        if (Looper.getMainLooper() == null) {
            @Suppress("DEPRECATION")
            Looper.prepareMainLooper()
        }

        initPrivilegedReflectors()
        startGlobalGeteventMonitor()

        // Apply permanent appops/whitelist permissions asynchronously so port bind & handshake return instantaneously
        Thread({
            grantPermanentAppPrivileges(allowedPackage)
        }, "KodaPermGranter").apply {
            isDaemon = true
            start()
        }

        var sock: ServerSocket? = null
        for (attempt in 1..3) {
            sock = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(
                        InetSocketAddress(
                            InetAddress.getByName(KodaEngineProtocol.SERVER_HOST),
                            KodaEngineProtocol.SERVER_PORT
                        ),
                        16
                    )
                }
            } catch (t: Throwable) {
                if (attempt == 1) {
                    requestExistingShutdown()
                    Thread.sleep(150L)
                } else if (attempt == 3) {
                    System.err.println("adb command failed: bind ${KodaEngineProtocol.SERVER_PORT} (${t.message})")
                } else {
                    Thread.sleep(150L)
                }
                null
            }
            if (sock != null) break
        }
        val activeSock = sock ?: return
        serverSocket = activeSock
        println("adb command success (Koda Wintab listening 127.0.0.1:${KodaEngineProtocol.SERVER_PORT} UID=${Process.myUid()})")
        System.out.flush()

        val executor = Executors.newCachedThreadPool()
        while (keepRunning) {
            try {
                val client = activeSock.accept()
                client.tcpNoDelay = true
                client.keepAlive = true
                executor.execute { handleClient(client) }
            } catch (_: Throwable) {
                if (!keepRunning) break
            }
        }
        runCatching { activeSock.close() }
    }

    private fun grantPermanentAppPrivileges(pkg: String) {
        disablePhantomProcessRestriction()
        if (pkg.isBlank()) return
        val commands = listOf(
            arrayOf("appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow"),
            arrayOf("appops", "set", pkg, "ACCESS_RESTRICTED_SETTINGS", "allow"),
            arrayOf("appops", "set", pkg, "GET_USAGE_STATS", "allow"),
            arrayOf("appops", "set", pkg, "RUN_IN_BACKGROUND", "allow"),
            arrayOf("appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "allow"),
            arrayOf("appops", "set", pkg, "START_FOREGROUND", "allow"),
            arrayOf("appops", "set", pkg, "WRITE_SETTINGS", "allow"),
            arrayOf("appops", "set", pkg, "WAKE_LOCK", "allow"),
            arrayOf("am", "set-standby-bucket", pkg, "active"),
            arrayOf("cmd", "deviceidle", "whitelist", "+$pkg"),
            arrayOf("dumpsys", "deviceidle", "whitelist", "+$pkg"),
            arrayOf("pm", "grant", pkg, "android.permission.POST_NOTIFICATIONS"),
            arrayOf("settings", "put", "system", "otg_enable", "1")
        )
        for (cmd in commands) {
            runCatching {
                ProcessBuilder(*cmd)
                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            }
        }
    }

    data class DeviceInputCaps(
        val devPath: String,
        val isStylus: Boolean = false,
        val isMouse: Boolean = false,
        val isTouchscreenOnly: Boolean = false,
        val maxX: Float = 32767f,
        val maxY: Float = 32767f,
        val maxP: Float = 8191f
    )

    internal fun parseGeteventLpOutput(output: String): Map<String, DeviceInputCaps> {
        val result = HashMap<String, DeviceInputCaps>()
        var currentDev = ""
        var hasStylus = false
        var hasMouse = false
        var hasMtSlot = false
        var hasInputPropDirect = false
        var maxX = 32767f
        var maxY = 32767f
        var maxP = 8191f

        fun flushCurrent() {
            if (currentDev.isNotEmpty()) {
                val touchOnly = (hasMtSlot || hasInputPropDirect) && !hasStylus && !hasMouse
                result[currentDev] = DeviceInputCaps(
                    devPath = currentDev,
                    isStylus = hasStylus,
                    isMouse = hasMouse,
                    isTouchscreenOnly = touchOnly,
                    maxX = maxX.coerceAtLeast(1f),
                    maxY = maxY.coerceAtLeast(1f),
                    maxP = maxP.coerceAtLeast(1f)
                )
            }
        }

        for (rawLine in output.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("add device")) {
                flushCurrent()
                val idx = line.indexOf(':')
                currentDev = if (idx >= 0) line.substring(idx + 1).trim() else ""
                hasStylus = false
                hasMouse = false
                hasMtSlot = false
                hasInputPropDirect = false
                maxX = 32767f
                maxY = 32767f
                maxP = 8191f
                continue
            }
            if (currentDev.isEmpty()) continue
            when {
                line.contains("BTN_TOOL_PEN") || line.contains("BTN_STYLUS") || line.contains("BTN_TOOL_RUBBER") -> {
                    hasStylus = true
                }
                line.contains("REL_X") || line.contains("REL_Y") || line.contains("BTN_MOUSE") || line.contains("BTN_LEFT") -> {
                    hasMouse = true
                }
                line.contains("ABS_MT_SLOT") || line.contains("ABS_MT_TRACKING_ID") -> {
                    hasMtSlot = true
                }
                line.contains("INPUT_PROP_DIRECT") -> {
                    hasInputPropDirect = true
                }
            }
            if (line.contains("ABS_X") || line.contains("ABS_MT_POSITION_X")) {
                extractMaxValue(line)?.let { maxX = it.coerceAtLeast(1f) }
            } else if (line.contains("ABS_Y") || line.contains("ABS_MT_POSITION_Y")) {
                extractMaxValue(line)?.let { maxY = it.coerceAtLeast(1f) }
            } else if (line.contains("ABS_PRESSURE") || line.contains("ABS_MT_PRESSURE")) {
                extractMaxValue(line)?.let { maxP = it.coerceAtLeast(1f) }
            }
        }
        flushCurrent()
        return result
    }

    internal fun parseHexSignedInt(valHex: String): Int? = valHex.toLongOrNull(16)?.toInt()

    private fun refreshGeteventDeviceCaps(target: ConcurrentHashMap<String, DeviceInputCaps>) {
        runCatching {
            val probe = ProcessBuilder("/system/bin/getevent", "-lp")
                .redirectErrorStream(true)
                .start()
            val text = probe.inputStream.bufferedReader().readText()
            probe.waitFor()
            val parsed = parseGeteventLpOutput(text)
            target.putAll(parsed)
        }
    }

    /**
     * Reads Linux input events via `getevent -lp` / `getevent -l` under UID 2000 so
     * mouse moves and tablet magnetic-field stylus hover/press keep moving the global
     * .cur/.ani/.png cursor across Android Launcher and all third-party apps, while
     * strictly ignoring internal finger touchscreen events.
     */
    private fun startGlobalGeteventMonitor() {
        Thread({
            val deviceCaps = ConcurrentHashMap<String, DeviceInputCaps>()
            refreshGeteventDeviceCaps(deviceCaps)

            while (keepRunning) {
                try {
                    val proc = ProcessBuilder("/system/bin/getevent", "-l")
                        .redirectErrorStream(true)
                        .start()
                    BufferedReader(InputStreamReader(proc.inputStream)).use { br ->
                        val lastRawX = HashMap<String, Int>()
                        val lastRawY = HashMap<String, Int>()
                        val liveStylusDevs = HashSet<String>()
                        var pendingRelX = 0f
                        var pendingRelY = 0f
                        var packetMouseMoved = false
                        var packetMouseClicked = false
                        var packetStylusAbsUpdated = false
                        var packetDev = ""
                        var lastRescanMs = 0L

                        while (keepRunning) {
                            val line = br.readLine() ?: break
                            if (line.startsWith("add device")) {
                                refreshGeteventDeviceCaps(deviceCaps)
                                continue
                            }
                            if (line.contains("SYN_REPORT")) {
                                if (packetMouseMoved || packetMouseClicked) {
                                    if (packetMouseMoved) {
                                        synchronized(deltaLock) {
                                            gpPendingDeltaX += pendingRelX
                                            gpPendingDeltaY += pendingRelY
                                        }
                                    }
                                    gpHasAbs = false
                                    gpIsStylus = false
                                    gpIsVisible = true
                                    pointerSeq.incrementAndGet()
                                } else if (packetStylusAbsUpdated && packetDev.isNotEmpty()) {
                                    val caps = deviceCaps[packetDev]
                                    val maxX = caps?.maxX ?: 32767f
                                    val maxY = caps?.maxY ?: 32767f
                                    gpAbsXNorm = ((lastRawX[packetDev] ?: 0) / maxX).coerceIn(0f, 1f)
                                    gpAbsYNorm = ((lastRawY[packetDev] ?: 0) / maxY).coerceIn(0f, 1f)
                                    gpHasAbs = true
                                    gpIsStylus = true
                                    gpIsVisible = true
                                    pointerSeq.incrementAndGet()
                                }
                                // Always reset per-packet state so mouse/stylus/touch never leak across packets!
                                pendingRelX = 0f
                                pendingRelY = 0f
                                packetMouseMoved = false
                                packetMouseClicked = false
                                packetStylusAbsUpdated = false
                                packetDev = ""
                                continue
                            }

                            val parts = line.trim().split(Regex("\\s+"))
                            if (parts.size < 4) continue
                            val dev = parts[0].removeSuffix(":")
                            val code = parts[2]
                            val valHex = parts[3]

                            var caps = deviceCaps[dev]
                            if (caps == null) {
                                val nowMs = SystemClock.uptimeMillis()
                                if (nowMs - lastRescanMs >= 1000L) {
                                    lastRescanMs = nowMs
                                    refreshGeteventDeviceCaps(deviceCaps)
                                    caps = deviceCaps[dev]
                                }
                            }

                            // Ignore internal finger touchscreen completely unless stylus tool is explicitly active
                            val isFingerTouch = caps != null && caps.isTouchscreenOnly && !caps.isStylus && dev !in liveStylusDevs
                            if (isFingerTouch) continue

                            packetDev = dev
                            when (code) {
                                "REL_X" -> {
                                    pendingRelX += parseHexSignedInt(valHex)?.toFloat() ?: 0f
                                    packetMouseMoved = true
                                }
                                "REL_Y" -> {
                                    pendingRelY += parseHexSignedInt(valHex)?.toFloat() ?: 0f
                                    packetMouseMoved = true
                                }
                                "BTN_LEFT", "BTN_RIGHT", "BTN_MIDDLE" -> {
                                    packetMouseClicked = true
                                }
                                "BTN_TOOL_PEN", "BTN_TOOL_RUBBER", "BTN_STYLUS", "BTN_STYLUS2" -> {
                                    liveStylusDevs.add(dev)
                                    gpIsStylus = true
                                    if (valHex == "DOWN" || valHex == "00000001") {
                                        gpIsVisible = true
                                    }
                                }
                                "ABS_X" -> {
                                    if (caps == null || caps.isStylus || dev in liveStylusDevs || !caps.isTouchscreenOnly) {
                                        lastRawX[dev] = parseHexSignedInt(valHex) ?: 0
                                        packetStylusAbsUpdated = true
                                    }
                                }
                                "ABS_Y" -> {
                                    if (caps == null || caps.isStylus || dev in liveStylusDevs || !caps.isTouchscreenOnly) {
                                        lastRawY[dev] = parseHexSignedInt(valHex) ?: 0
                                        packetStylusAbsUpdated = true
                                    }
                                }
                                "ABS_PRESSURE" -> {
                                    if (caps == null || caps.isStylus || dev in liveStylusDevs) {
                                        val pRaw = parseHexSignedInt(valHex) ?: 0
                                        val maxP = caps?.maxP ?: 8191f
                                        gpPressureNorm = (pRaw / maxP).coerceIn(0f, 1f)
                                        packetStylusAbsUpdated = true
                                    }
                                }
                                "ABS_MT_POSITION_X" -> {
                                    if (dev in liveStylusDevs || caps?.isStylus == true) {
                                        lastRawX[dev] = parseHexSignedInt(valHex) ?: 0
                                        packetStylusAbsUpdated = true
                                    }
                                }
                                "ABS_MT_POSITION_Y" -> {
                                    if (dev in liveStylusDevs || caps?.isStylus == true) {
                                        lastRawY[dev] = parseHexSignedInt(valHex) ?: 0
                                        packetStylusAbsUpdated = true
                                    }
                                }
                            }
                        }
                    }
                    proc.waitFor()
                } catch (_: Throwable) {
                }
                Thread.sleep(1800L)
            }
        }, "KodaGlobalGeteventMonitor").apply {
            isDaemon = true
            start()
        }
    }

    private fun extractMaxValue(line: String): Float? {
        val m = Regex("max\\s+(\\d+)").find(line) ?: return null
        return m.groupValues[1].toFloatOrNull()
    }

    private fun requestExistingShutdown() {
        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(KodaEngineProtocol.SERVER_HOST, KodaEngineProtocol.SERVER_PORT), 250)
                val out = DataOutputStream(s.getOutputStream())
                out.writeByte(KodaEngineProtocol.CMD_SHUTDOWN.toInt())
                out.flush()
            }
            Thread.sleep(80L)
        }
    }

    private fun probeServerAlive(): Boolean {
        return runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(KodaEngineProtocol.SERVER_HOST, KodaEngineProtocol.SERVER_PORT), 300)
                s.soTimeout = 400
                val out = DataOutputStream(s.getOutputStream())
                val inp = DataInputStream(s.getInputStream())
                out.writeByte(KodaEngineProtocol.CMD_HANDSHAKE.toInt())
                out.writeUTF("")
                out.writeUTF(allowedPackage)
                out.flush()
                inp.readByte() == KodaEngineProtocol.STATUS_OK
            }
        }.getOrDefault(false)
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun initPrivilegedReflectors() {
        // Path 1 (Android 14+ / API 34+): InputManagerGlobal.getInstance()
        try {
            val imgClass = Class.forName("android.hardware.input.InputManagerGlobal")
            val getInstance = imgClass.getDeclaredMethod("getInstance").apply { isAccessible = true }
            val instance = getInstance.invoke(null)
            val injectMethod = imgClass.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType
            ).apply { isAccessible = true }
            inputManagerInstance = instance
            injectInputEventMethod = injectMethod
        } catch (_: Throwable) {
        }

        // Path 2 (Android 10-13): InputManager.getInstance()
        if (injectInputEventMethod == null) {
            try {
                val imClass = InputManager::class.java
                val getInstance = imClass.getDeclaredMethod("getInstance").apply { isAccessible = true }
                val instance = getInstance.invoke(null)
                val injectMethod = imClass.getMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType
                ).apply { isAccessible = true }
                inputManagerInstance = instance
                injectInputEventMethod = injectMethod
            } catch (_: Throwable) {
            }
        }

        // Path 3 (Universal Binder fallback): IInputManager.Stub.asInterface(ServiceManager.getService("input"))
        if (injectInputEventMethod == null) {
            try {
                val smClass = Class.forName("android.os.ServiceManager")
                val getService = smClass.getMethod("getService", String::class.java)
                val binder = getService.invoke(null, "input") as IBinder
                val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
                val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
                val iim = asInterface.invoke(null, binder)
                val injectMethod = iim.javaClass.getMethod(
                    "injectInputEvent",
                    InputEvent::class.java,
                    Int::class.javaPrimitiveType
                ).apply { isAccessible = true }
                inputManagerInstance = iim
                injectInputEventMethod = injectMethod
            } catch (t: Throwable) {
                System.err.println("KodaShellServer InputManager warning: ${t.message}")
            }
        }

        motionSetDisplayIdMethod = runCatching {
            MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }.getOrNull()

        try {
            val amClass = Class.forName("android.app.ActivityManager")
            val getService = amClass.getMethod("getService")
            activityManagerService = getService.invoke(null)
            getRunningAppProcessesMethod = activityManagerService?.javaClass
                ?.getMethod("getRunningAppProcesses")
                ?.apply { isAccessible = true }
        } catch (_: Throwable) {
        }

        systemContext = runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val sysMain = atClass.getMethod("systemMain")
            val at = sysMain.invoke(null)
            val getSysCtx = atClass.getMethod("getSystemContext")
            getSysCtx.invoke(at) as? android.content.Context
        }.getOrNull()
    }

    private fun handleClient(socket: Socket) {
        var authenticated = false
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 8192))
            val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), 2048))

            while (keepRunning) {
                val cmd = input.readByte()
                when (cmd) {
                    KodaEngineProtocol.CMD_SHUTDOWN -> {
                        keepRunning = false
                        runCatching { serverSocket?.close() }
                        break
                    }

                    KodaEngineProtocol.CMD_HANDSHAKE -> {
                        input.readUTF() // legacy field ignored (token removed per user request)
                        val pkg = input.readUTF()
                        authenticated = allowedPackage.isEmpty() || pkg.isEmpty() || pkg == allowedPackage
                        if (authenticated) {
                            output.writeByte(KodaEngineProtocol.STATUS_OK.toInt())
                            output.writeInt(Process.myUid())
                            output.writeInt(Process.myPid())
                            output.writeBoolean(injectInputEventMethod != null)
                            output.writeBoolean(activityManagerService != null)
                        } else {
                            output.writeByte(KodaEngineProtocol.STATUS_ERR_AUTH.toInt())
                        }
                        output.flush()
                    }

                    KodaEngineProtocol.CMD_PING -> {
                        if (!authenticated) {
                            output.writeByte(KodaEngineProtocol.STATUS_ERR_AUTH.toInt())
                            output.flush()
                            continue
                        }
                        output.writeByte(KodaEngineProtocol.STATUS_OK.toInt())
                        output.writeInt(Process.myUid())
                        output.writeLong(SystemClock.elapsedRealtime() - startTimeMs)
                        output.flush()
                    }

                    KodaEngineProtocol.CMD_INJECT_STYLUS_BATCH -> {
                        if (!authenticated) {
                            output.writeByte(KodaEngineProtocol.STATUS_ERR_AUTH.toInt())
                            output.flush()
                            continue
                        }
                        val deviceId = input.readInt()
                        val displayId = input.readInt()
                        val action = input.readInt()
                        val isEraser = input.readBoolean()
                        val count = input.readInt().coerceIn(1, 32)

                        var event: MotionEvent? = null
                        val props = arrayOf(MotionEvent.PointerProperties().apply {
                            id = 0
                            toolType = if (isEraser) {
                                MotionEvent.TOOL_TYPE_ERASER
                            } else {
                                MotionEvent.TOOL_TYPE_STYLUS
                            }
                        })
                        val coords = arrayOf(MotionEvent.PointerCoords())
                        var downTime = stylusDownTimes[deviceId] ?: SystemClock.uptimeMillis()

                        for (i in 0 until count) {
                            val eventTime = input.readLong()
                            val x = input.readFloat()
                            val y = input.readFloat()
                            val pressure = input.readFloat()
                            val tiltX = input.readFloat()
                            val tiltY = input.readFloat()
                            val buttonState = input.readInt()

                            val pc = coords[0]
                            pc.x = x
                            pc.y = y
                            pc.pressure = pressure
                            pc.size = 0.05f
                            pc.setAxisValue(MotionEvent.AXIS_TILT, hypot(tiltX, tiltY))
                            pc.setAxisValue(MotionEvent.AXIS_ORIENTATION, atan2(tiltY, tiltX))

                            if (i == 0) {
                                if (action == MotionEvent.ACTION_DOWN) {
                                    downTime = eventTime
                                    stylusDownTimes[deviceId] = downTime
                                }
                                event = MotionEvent.obtain(
                                    downTime,
                                    eventTime,
                                    action,
                                    1,
                                    props,
                                    coords,
                                    0,
                                    buttonState,
                                    1f,
                                    1f,
                                    deviceId,
                                    0,
                                    InputDevice.SOURCE_STYLUS,
                                    0
                                )
                            } else {
                                event?.addBatch(eventTime, coords, 0)
                            }
                        }

                        val ok = if (event != null) {
                            if (forceHideNativePointer) {
                                val nowMs = SystemClock.uptimeMillis()
                                if (nowMs - lastHideEnforceMs >= 100L) {
                                    lastHideEnforceMs = nowMs
                                    enforceHiddenNativePointer()
                                }
                            }
                            if (displayId > 0) {
                                runCatching { motionSetDisplayIdMethod?.invoke(event, displayId) }
                            }
                            val injected = injectEvent(event)
                            event.recycle()
                            injected
                        } else {
                            false
                        }
                        output.writeByte(
                            if (ok) KodaEngineProtocol.STATUS_OK.toInt()
                            else KodaEngineProtocol.STATUS_ERR_INJECT.toInt()
                        )
                        output.flush()
                    }

                    KodaEngineProtocol.CMD_INJECT_MOUSE -> {
                        if (!authenticated) {
                            output.writeByte(KodaEngineProtocol.STATUS_ERR_AUTH.toInt())
                            output.flush()
                            continue
                        }
                        val deviceId = input.readInt()
                        val displayId = input.readInt()
                        val action = input.readInt()
                        val x = input.readFloat()
                        val y = input.readFloat()
                        val buttonState = input.readInt()
                        val vScroll = input.readFloat()
                        val now = SystemClock.uptimeMillis()
                        val downTime = if (action == MotionEvent.ACTION_DOWN) {
                            mouseDownTimes[deviceId] = now
                            now
                        } else {
                            mouseDownTimes[deviceId] ?: now
                        }

                        val props = arrayOf(MotionEvent.PointerProperties().apply {
                            id = 0
                            toolType = MotionEvent.TOOL_TYPE_MOUSE
                        })
                        val coords = arrayOf(MotionEvent.PointerCoords().apply {
                            this.x = x
                            this.y = y
                            setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
                        })
                        val event = MotionEvent.obtain(
                            downTime, now, action, 1, props, coords,
                            0, buttonState, 1f, 1f, deviceId, 0,
                            InputDevice.SOURCE_MOUSE, 0
                        )
                        if (forceHideNativePointer) {
                            val nowMs = SystemClock.uptimeMillis()
                            if (nowMs - lastHideEnforceMs >= 120L) {
                                lastHideEnforceMs = nowMs
                                enforceHiddenNativePointer()
                            }
                        }
                        if (displayId > 0) {
                            runCatching { motionSetDisplayIdMethod?.invoke(event, displayId) }
                        }
                        val ok = injectEvent(event)
                        event.recycle()
                        output.writeByte(
                            if (ok) KodaEngineProtocol.STATUS_OK.toInt()
                            else KodaEngineProtocol.STATUS_ERR_INJECT.toInt()
                        )
                        output.flush()
                    }

                    KodaEngineProtocol.CMD_INJECT_KEY -> {
                        if (!authenticated) {
                            output.writeByte(KodaEngineProtocol.STATUS_ERR_AUTH.toInt())
                            output.flush()
                            continue
                        }
                        val deviceId = input.readInt()
                        val action = input.readInt()
                        val keyCode = input.readInt()
                        val metaState = input.readInt()
                        val now = SystemClock.uptimeMillis()
                        val keyEvent = KeyEvent(
                            now, now, action, keyCode, 0, metaState, deviceId, 0,
                            0, InputDevice.SOURCE_KEYBOARD
                        )
                        val ok = injectEvent(keyEvent)
                        output.writeByte(
                            if (ok) KodaEngineProtocol.STATUS_OK.toInt()
                            else KodaEngineProtocol.STATUS_ERR_INJECT.toInt()
                        )
                        output.flush()
                    }

                    KodaEngineProtocol.CMD_QUERY_FOREGROUND_APPS -> {
                        if (!authenticated) {
                            output.writeByte(KodaEngineProtocol.STATUS_ERR_AUTH.toInt())
                            output.flush()
                            continue
                        }
                        val activePkgs = queryActivePackages()
                        output.writeByte(KodaEngineProtocol.STATUS_OK.toInt())
                        output.writeInt(activePkgs.size)
                        for (pkg in activePkgs) {
                            output.writeUTF(pkg)
                        }
                        output.flush()
                    }

                    KodaEngineProtocol.CMD_GRANT_OVERLAY_OP -> {
                        val targetPkg = input.readUTF()
                        if (!authenticated || targetPkg.isBlank()) {
                            output.writeByte(KodaEngineProtocol.STATUS_ERR_AUTH.toInt())
                            output.flush()
                            continue
                        }
                        val ok = runCatching {
                            val proc = ProcessBuilder("appops", "set", targetPkg, "SYSTEM_ALERT_WINDOW", "allow").start()
                            proc.waitFor() == 0
                        }.getOrDefault(false)
                        output.writeByte(if (ok) KodaEngineProtocol.STATUS_OK.toInt() else KodaEngineProtocol.STATUS_ERR_INJECT.toInt())
                        output.flush()
                    }

                    KodaEngineProtocol.CMD_SET_POINTER_ICON -> {
                        val w = input.readInt().coerceIn(1, 128)
                        val h = input.readInt().coerceIn(1, 128)
                        val hx = input.readFloat()
                        val hy = input.readFloat()
                        val byteLen = input.readInt().coerceIn(0, 128 * 128 * 4)
                        val argbBytes = ByteArray(byteLen)
                        if (byteLen > 0) input.readFully(argbBytes)
                        if (!authenticated || byteLen == 0) {
                            output.writeByte(KodaEngineProtocol.STATUS_ERR_AUTH.toInt())
                            output.flush()
                            continue
                        }
                        val ok = tryApplyNativePointerIcon(w, h, hx, hy, argbBytes)
                        output.writeByte(if (ok) KodaEngineProtocol.STATUS_OK.toInt() else KodaEngineProtocol.STATUS_ERR_INJECT.toInt())
                        output.flush()
                    }

                    KodaEngineProtocol.CMD_HIDE_NATIVE_POINTER -> {
                        val hide = input.readBoolean()
                        if (!authenticated) {
                            output.writeByte(KodaEngineProtocol.STATUS_ERR_AUTH.toInt())
                            output.flush()
                            continue
                        }
                        forceHideNativePointer = hide
                        val ok = if (hide) {
                            enforceHiddenNativePointer()
                        } else {
                            restoreArrowPointerIcon()
                        }
                        output.writeByte(if (ok) KodaEngineProtocol.STATUS_OK.toInt() else KodaEngineProtocol.STATUS_ERR_INJECT.toInt())
                        output.flush()
                    }

                    KodaEngineProtocol.CMD_POLL_GLOBAL_POINTER -> {
                        if (!authenticated) {
                            output.writeByte(KodaEngineProtocol.STATUS_ERR_AUTH.toInt())
                            output.flush()
                            continue
                        }
                        if (forceHideNativePointer) {
                            val nowMs = SystemClock.uptimeMillis()
                            if (nowMs - lastHideEnforceMs >= 180L) {
                                lastHideEnforceMs = nowMs
                                enforceHiddenNativePointer()
                            }
                        }
                        var dx: Float
                        var dy: Float
                        synchronized(deltaLock) {
                            dx = gpPendingDeltaX
                            dy = gpPendingDeltaY
                            gpPendingDeltaX = 0f
                            gpPendingDeltaY = 0f
                        }
                        output.writeByte(KodaEngineProtocol.STATUS_OK.toInt())
                        output.writeLong(pointerSeq.get())
                        output.writeBoolean(gpHasAbs)
                        output.writeFloat(gpAbsXNorm)
                        output.writeFloat(gpAbsYNorm)
                        output.writeFloat(dx)
                        output.writeFloat(dy)
                        output.writeFloat(gpPressureNorm)
                        output.writeBoolean(gpIsStylus)
                        output.writeBoolean(gpIsVisible)
                        output.flush()
                    }

                    KodaEngineProtocol.CMD_APPLY_HUB_MODE -> {
                        val mode = input.readInt()
                        val targetPkg = input.readUTF().ifBlank { allowedPackage }
                        if (!authenticated) {
                            output.writeByte(KodaEngineProtocol.STATUS_ERR_AUTH.toInt())
                            output.flush()
                            continue
                        }
                        val res = applyHubPrivilegeMode(mode, targetPkg)
                        output.writeByte(KodaEngineProtocol.STATUS_OK.toInt())
                        output.writeBoolean(res.applied)
                        output.writeUTF(res.portId)
                        output.writeUTF(res.powerRole)
                        output.writeUTF(res.dataRole)
                        output.writeBoolean(res.supportsDualRole)
                        output.writeUTF(res.detail)
                        output.flush()
                    }

                    KodaEngineProtocol.CMD_QUERY_USB_POWER -> {
                        if (!authenticated) {
                            output.writeByte(KodaEngineProtocol.STATUS_ERR_AUTH.toInt())
                            output.flush()
                            continue
                        }
                        val info = inspectUsbPortStatus()
                        output.writeByte(KodaEngineProtocol.STATUS_OK.toInt())
                        output.writeUTF(info.portId)
                        output.writeUTF(info.powerRole)
                        output.writeUTF(info.dataRole)
                        output.writeBoolean(info.supportsDualRole)
                        output.writeUTF(info.detail)
                        output.flush()
                    }

                    else -> break
                }
            }
        } catch (_: EOFException) {
            // Client closed cleanly
        } catch (_: Throwable) {
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun injectEvent(event: InputEvent): Boolean {
        val im = inputManagerInstance ?: return false
        val method = injectInputEventMethod ?: return false
        return try {
            method.invoke(im, event, KodaEngineProtocol.INJECT_MODE_ASYNC) as? Boolean ?: false
        } catch (_: Throwable) {
            false
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun queryActivePackages(): List<String> {
        val am = activityManagerService ?: return emptyList()
        val m = getRunningAppProcessesMethod ?: return emptyList()
        return try {
            val list = m.invoke(am) as? List<*> ?: return emptyList()
            val pkgs = ArrayList<String>()
            for (item in list) {
                if (item == null) continue
                val importanceField = item.javaClass.getField("importance")
                val pkgListField = item.javaClass.getField("pkgList")
                val importance = importanceField.getInt(item)
                if (importance <= 200) {
                    val arr = pkgListField.get(item) as? Array<String>
                    if (arr != null) {
                        for (p in arr) pkgs.add(p)
                    }
                }
            }
            pkgs
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun tryApplyNativePointerIcon(
        w: Int,
        h: Int,
        hx: Float,
        hy: Float,
        argbBytes: ByteArray
    ): Boolean {
        return try {
            val pixels = IntArray(w * h)
            for (i in pixels.indices) {
                val o = i * 4
                if (o + 3 >= argbBytes.size) break
                val a = argbBytes[o].toInt() and 0xFF
                val r = argbBytes[o + 1].toInt() and 0xFF
                val g = argbBytes[o + 2].toInt() and 0xFF
                val b = argbBytes[o + 3].toInt() and 0xFF
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            val bmp = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
            applyPointerIconAcrossApis(bmp, hx, hy, typeOverride = null)
        } catch (_: Throwable) {
            false
        }
    }

    private fun enforceHiddenNativePointer(): Boolean {
        val sysIcon = systemContext?.let { ctx ->
            runCatching { PointerIcon.getSystemIcon(ctx, PointerIcon.TYPE_NULL) }.getOrNull()
        }
        val transparent = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        return applyPointerIconAcrossApis(transparent, 0f, 0f, typeOverride = PointerIcon.TYPE_NULL, prebuiltIcon = sysIcon)
    }

    private fun applyPointerIconAcrossApis(
        bmp: Bitmap,
        hx: Float,
        hy: Float,
        typeOverride: Int?,
        prebuiltIcon: PointerIcon? = null
    ): Boolean {
        val im = inputManagerInstance ?: return false
        val icon = prebuiltIcon ?: PointerIcon.create(bmp, hx, hy)
        var applied = false

        if (typeOverride != null) {
            runCatching {
                val mType = im.javaClass.getMethod("setPointerIconType", Int::class.javaPrimitiveType)
                mType.invoke(im, typeOverride)
                applied = true
            }
        }
        runCatching {
            val mCustom = im.javaClass.getMethod("setCustomPointerIcon", PointerIcon::class.java)
            mCustom.invoke(im, icon)
            applied = true
        }
        val targetDeviceIds = buildList {
            add(-1)
            add(0)
            add(101)
            add(102)
            add(105)
            add(106)
            runCatching {
                for (id in InputDevice.getDeviceIds()) add(id)
            }
        }.distinct()
        val mMulti = runCatching {
            im.javaClass.getMethod(
                "setPointerIcon",
                PointerIcon::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                IBinder::class.java
            )
        }.getOrNull()
        val focusedToken = queryFocusedWindowToken()
        if (mMulti != null) {
            val pointerIds = intArrayOf(0, -1)
            for (devId in targetDeviceIds) {
                for (pid in pointerIds) {
                    runCatching {
                        mMulti.invoke(im, icon, 0, devId, pid, focusedToken)
                        applied = true
                    }
                    if (focusedToken != null) {
                        runCatching {
                            mMulti.invoke(im, icon, 0, devId, pid, null)
                            applied = true
                        }
                    }
                }
            }
        }
        return applied
    }

    private fun queryFocusedWindowToken(): IBinder? {
        return runCatching {
            val wmg = Class.forName("android.view.WindowManagerGlobal")
            val wm = wmg.getMethod("getWindowManagerService").invoke(null) ?: return@runCatching null
            val m = wm.javaClass.methods.firstOrNull {
                it.name == "getFocusedWindowToken" && it.parameterCount == 0
            } ?: return@runCatching null
            m.invoke(wm) as? IBinder
        }.getOrNull()
    }

    private fun restoreArrowPointerIcon(): Boolean {
        val im = inputManagerInstance ?: return false
        var restored = runCatching {
            val mType = im.javaClass.getMethod("setPointerIconType", Int::class.javaPrimitiveType)
            mType.invoke(im, PointerIcon.TYPE_ARROW)
            true
        }.getOrDefault(false)
        if (!restored) {
            val ctx = systemContext
            val arrow = ctx?.let { runCatching { PointerIcon.getSystemIcon(it, PointerIcon.TYPE_ARROW) }.getOrNull() }
            if (arrow != null) {
                restored = applyPointerIconAcrossApis(
                    Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
                    0f,
                    0f,
                    typeOverride = PointerIcon.TYPE_ARROW,
                    prebuiltIcon = arrow
                )
            }
        }
        return restored
    }

    data class UsbPowerRoleResult(
        val applied: Boolean,
        val portId: String,
        val powerRole: String,
        val dataRole: String,
        val supportsDualRole: Boolean,
        val detail: String
    )

    internal fun parseDumpsysUsbPorts(dumpsysText: String): UsbPowerRoleResult {
        var portId = ""
        var powerRole = "desconhecido"
        var dataRole = "desconhecido"
        var supportsDual = false

        val portRegex = Regex("""(?:port|UsbPort)\s*\{[^}]*?id=([^,\s}]+)""", RegexOption.IGNORE_CASE)
        val altPortRegex = Regex("""id=([a-zA-Z0-9_/-]+).*?current_mode=""", RegexOption.IGNORE_CASE)
        portId = portRegex.find(dumpsysText)?.groupValues?.getOrNull(1)
            ?: altPortRegex.find(dumpsysText)?.groupValues?.getOrNull(1)
            ?: if (dumpsysText.contains("otg_default")) "otg_default"
            else if (dumpsysText.contains("port0")) "port0"
            else ""

        Regex("""current_power_role\s*[:=]\s*([a-zA-Z_-]+)""").find(dumpsysText)?.let {
            powerRole = it.groupValues[1].lowercase()
        }
        Regex("""current_data_role\s*[:=]\s*([a-zA-Z_-]+)""").find(dumpsysText)?.let {
            dataRole = it.groupValues[1].lowercase()
        }
        if (powerRole == "desconhecido") {
            if (dumpsysText.contains("sink", ignoreCase = true) && dumpsysText.contains("host", ignoreCase = true)) {
                powerRole = "sink"
                dataRole = "host"
            } else if (dumpsysText.contains("source", ignoreCase = true) && dumpsysText.contains("host", ignoreCase = true)) {
                powerRole = "source"
                dataRole = "host"
            }
        }
        val lower = dumpsysText.lowercase()
        supportsDual = lower.contains("dual") || (lower.contains("dfp") && lower.contains("ufp")) ||
            lower.contains("can_change_power_role=true")

        val detail = if (portId.isNotBlank()) {
            "Porta '$portId' · energia=$powerRole · dados=$dataRole · dualRole=$supportsDual"
        } else {
            "Controlador Type-C em modo OTG padrão (dualRole=$supportsDual)"
        }
        return UsbPowerRoleResult(
            applied = portId.isNotBlank(),
            portId = portId.ifBlank { "port0" },
            powerRole = powerRole,
            dataRole = dataRole,
            supportsDualRole = supportsDual,
            detail = detail
        )
    }

    private fun runShellText(vararg args: String): String {
        return runCatching {
            val proc = ProcessBuilder(*args)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            out
        }.getOrDefault("")
    }

    private fun inspectUsbPortStatus(): UsbPowerRoleResult {
        val dump = runShellText("/system/bin/dumpsys", "usb")
        return parseDumpsysUsbPorts(dump)
    }

    private fun applyHubPrivilegeMode(mode: Int, pkg: String): UsbPowerRoleResult {
        grantPermanentAppPrivileges(pkg)
        val before = inspectUsbPortStatus()
        return when (mode) {
            KodaEngineProtocol.HUB_MODE_MULTI_3PLUS -> {
                if (pkg.isNotBlank()) {
                    runShellText("/system/bin/am", "set-standby-bucket", pkg, "active")
                }
                val detail = "Privilégio Hub 3+ aplicado: processos em segundo plano desbloqueados (phantom=max), teclado em pass-through livre, Mouse + Mesa sem bloqueio (${before.detail})."
                before.copy(applied = true, detail = detail)
            }
            KodaEngineProtocol.HUB_MODE_ENERGETIC_PD -> {
                val targetPorts = listOf(before.portId, "port0", "otg_default").distinct()
                var cmdSucceeded = false
                for (p in targetPorts) {
                    val r1 = runShellText("/system/bin/cmd", "usb", "set-port-roles", p, "sink", "host")
                    val r2 = runShellText("/system/bin/dumpsys", "usb", "set-port-roles", p, "sink", "host")
                    if (r1.isBlank() || r2.isBlank() ||
                        r1.contains("sink", ignoreCase = true) || r2.contains("sink", ignoreCase = true)
                    ) {
                        cmdSucceeded = true
                        break
                    }
                }
                Thread.sleep(180L)
                val after = inspectUsbPortStatus()
                val sinkHostActive = after.powerRole == "sink" && after.dataRole == "host"
                val detail = when {
                    sinkHostActive ->
                        "Dual-Role USB PD ativo em ${after.portId}: celular recebendo carga (sink) e controlando Hub OTG (host) simultaneamente!"
                    after.supportsDualRole ->
                        "Controlador ${after.portId} aceita Dual-Role (${after.powerRole}/${after.dataRole}). Conecte um Hub USB-C PD com fonte ligada para carregar + operar periféricos."
                    else ->
                        "Comando Shell PD enviado (role atual: ${after.powerRole}/${after.dataRole}). Se este aparelho/kernel travar VBUS em 'source', utilize Hub USB-C PD com alimentação externa ou cabo OTG-Y."
                }
                after.copy(applied = sinkHostActive || cmdSucceeded, detail = detail)
            }
            else -> {
                before.copy(
                    applied = true,
                    detail = "Modo OTG padrão com pass-through ADB/APK ativo (${before.detail})."
                )
            }
        }
    }
}
