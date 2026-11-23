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
    private val startTimeMs = SystemClock.elapsedRealtime()
    @Volatile private var keepRunning = true
    @Volatile private var serverSocket: ServerSocket? = null

    private var inputManagerInstance: Any? = null
    private var injectInputEventMethod: Method? = null
    private var motionSetDisplayIdMethod: Method? = null
    private var activityManagerService: Any? = null
    private var getRunningAppProcessesMethod: Method? = null
    private var activityTaskManagerService: Any? = null
    private var moveRootTaskToDisplayMethod: Method? = null
    private var getTasksMethod: Method? = null
    private var systemContext: android.content.Context? = null
    @Volatile private var forceHideNativePointer = false
    @Volatile private var lastHideEnforceMs = 0L
    @Volatile private var targetPenDisplayId = 0
    private val routedPenDisplayPackages = ConcurrentHashMap.newKeySet<String>()

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
        val launcherPrefix = if (File("/system/bin/setsid").exists()) "/system/bin/setsid " else ""

        // Double-fork inside sh with HUP/TERM/PIPE trapped so grandchild is immediately
        // reparented to PID 1 (init), detached from adbd pty/cgroup when PC unplugs or
        // USB Debugging is toggled off.
        var launched = false
        try {
            val doubleForkCmd = "trap '' HUP TERM PIPE; ( ${launcherPrefix}CLASSPATH=$classpath $appProcessCmd </dev/null >/dev/null 2>&1 & )"
            ProcessBuilder("/system/bin/sh", "-c", doubleForkCmd)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                .redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
                .start()
            launched = true
        } catch (_: Throwable) {
            launched = false
        }

        if (!launched) {
            val childArgs = buildList {
                if (File("/system/bin/setsid").exists()) add("/system/bin/setsid")
                add("/system/bin/app_process")
                add("/system/bin")
                add("com.example.koda.engine.KodaShellServer")
                add("--child")
                if (allowedPackage.isNotBlank()) add("--pkg=$allowedPackage")
            }
            try {
                val pb = ProcessBuilder(childArgs)
                pb.environment()["CLASSPATH"] = classpath
                pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                pb.redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                pb.redirectError(ProcessBuilder.Redirect.to(File("/dev/null")))
                pb.start()
            } catch (t2: Throwable) {
                System.err.println("adb command failed: ${t2.message}")
                kotlin.system.exitProcess(1)
            }
        }

        // Wait up to 4.0 seconds for the child server to bind 127.0.0.1:47291 and verify readiness
        val deadline = System.currentTimeMillis() + 4000L
        while (System.currentTimeMillis() < deadline) {
            if (probeServerAlive()) {
                println("adb command success (Koda Wintab UID=${Process.myUid()} · Privilégios permanentes aplicados)")
                System.out.flush()
                kotlin.system.exitProcess(0)
            }
            Thread.sleep(100L)
        }

        System.err.println("adb command failed: servidor nao respondeu na porta ${KodaEngineProtocol.SERVER_PORT}")
        kotlin.system.exitProcess(1)
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
        if (pkg.isBlank()) return
        val commands = listOf(
            arrayOf("appops", "set", pkg, "SYSTEM_ALERT_WINDOW", "allow"),
            arrayOf("appops", "set", pkg, "GET_USAGE_STATS", "allow"),
            arrayOf("appops", "set", pkg, "RUN_IN_BACKGROUND", "allow"),
            arrayOf("appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", "allow"),
            arrayOf("appops", "set", pkg, "START_FOREGROUND", "allow"),
            arrayOf("appops", "set", pkg, "WRITE_SETTINGS", "allow"),
            arrayOf("cmd", "deviceidle", "whitelist", "+$pkg"),
            arrayOf("dumpsys", "deviceidle", "whitelist", "+$pkg"),
            arrayOf("pm", "grant", pkg, "android.permission.POST_NOTIFICATIONS")
        )
        for (cmd in commands) {
            runCatching {
                ProcessBuilder(*cmd)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor()
            }
        }
    }

    /**
     * Reads Linux input events via `getevent -lp` / `getevent -l` under UID 2000 so
     * mouse moves and tablet magnetic-field stylus hover/press keep moving the global
     * .cur/.ani/.png cursor across Android Launcher and all third-party apps.
     */
    private fun startGlobalGeteventMonitor() {
        Thread({
            val devMaxX = ConcurrentHashMap<String, Float>()
            val devMaxY = ConcurrentHashMap<String, Float>()
            val devMaxP = ConcurrentHashMap<String, Float>()

            runCatching {
                val probe = ProcessBuilder("/system/bin/getevent", "-lp")
                    .redirectErrorStream(true)
                    .start()
                var currentDev = ""
                BufferedReader(InputStreamReader(probe.inputStream)).use { br ->
                    while (true) {
                        val line = br.readLine() ?: break
                        if (line.startsWith("add device")) {
                            val idx = line.indexOf(':')
                            if (idx >= 0) currentDev = line.substring(idx + 1).trim()
                        } else if (currentDev.isNotEmpty()) {
                            when {
                                line.contains("ABS_X") || line.contains("ABS_MT_POSITION_X") -> {
                                    extractMaxValue(line)?.let { devMaxX[currentDev] = it.coerceAtLeast(1f) }
                                }
                                line.contains("ABS_Y") || line.contains("ABS_MT_POSITION_Y") -> {
                                    extractMaxValue(line)?.let { devMaxY[currentDev] = it.coerceAtLeast(1f) }
                                }
                                line.contains("ABS_PRESSURE") || line.contains("ABS_MT_PRESSURE") -> {
                                    extractMaxValue(line)?.let { devMaxP[currentDev] = it.coerceAtLeast(1f) }
                                }
                            }
                        }
                    }
                }
                probe.waitFor()
            }

            while (keepRunning) {
                try {
                    val proc = ProcessBuilder("/system/bin/getevent", "-l")
                        .redirectErrorStream(true)
                        .start()
                    BufferedReader(InputStreamReader(proc.inputStream)).use { br ->
                        val lastRawX = HashMap<String, Int>()
                        val lastRawY = HashMap<String, Int>()
                        var pendingRelX = 0f
                        var pendingRelY = 0f
                        var sawEventInPacket = false
                        var packetIsStylus = false
                        var packetIsMouse = false
                        var packetDev = ""

                        while (keepRunning) {
                            val line = br.readLine() ?: break
                            if (line.contains("SYN_REPORT")) {
                                if (sawEventInPacket) {
                                    if (packetIsMouse && (pendingRelX != 0f || pendingRelY != 0f)) {
                                        synchronized(deltaLock) {
                                            gpPendingDeltaX += pendingRelX
                                            gpPendingDeltaY += pendingRelY
                                        }
                                        gpHasAbs = false
                                        gpIsStylus = false
                                        gpIsVisible = true
                                        pointerSeq.incrementAndGet()
                                        pendingRelX = 0f
                                        pendingRelY = 0f
                                    } else if (packetDev.isNotEmpty() &&
                                        (lastRawX.containsKey(packetDev) || lastRawY.containsKey(packetDev))
                                    ) {
                                        val maxX = devMaxX[packetDev] ?: 32767f
                                        val maxY = devMaxY[packetDev] ?: 32767f
                                        gpAbsXNorm = ((lastRawX[packetDev] ?: 0) / maxX).coerceIn(0f, 1f)
                                        gpAbsYNorm = ((lastRawY[packetDev] ?: 0) / maxY).coerceIn(0f, 1f)
                                        gpHasAbs = true
                                        gpIsStylus = packetIsStylus
                                        gpIsVisible = true
                                        pointerSeq.incrementAndGet()
                                    }
                                    sawEventInPacket = false
                                }
                                continue
                            }

                            val parts = line.trim().split(Regex("\\s+"))
                            if (parts.size < 4) continue
                            val dev = parts[0].removeSuffix(":")
                            val code = parts[2]
                            val valHex = parts[3]
                            packetDev = dev

                            when (code) {
                                "REL_X" -> {
                                    pendingRelX += valHex.toLongOrNull(16)?.toInt()?.toFloat() ?: 0f
                                    packetIsMouse = true
                                    sawEventInPacket = true
                                }
                                "REL_Y" -> {
                                    pendingRelY += valHex.toLongOrNull(16)?.toInt()?.toFloat() ?: 0f
                                    packetIsMouse = true
                                    sawEventInPacket = true
                                }
                                "ABS_X", "ABS_MT_POSITION_X" -> {
                                    lastRawX[dev] = valHex.toLongOrNull(16)?.toInt() ?: 0
                                    sawEventInPacket = true
                                }
                                "ABS_Y", "ABS_MT_POSITION_Y" -> {
                                    lastRawY[dev] = valHex.toLongOrNull(16)?.toInt() ?: 0
                                    sawEventInPacket = true
                                }
                                "ABS_PRESSURE", "ABS_MT_PRESSURE" -> {
                                    val pRaw = valHex.toLongOrNull(16)?.toInt() ?: 0
                                    val maxP = devMaxP[dev] ?: 8191f
                                    gpPressureNorm = (pRaw / maxP).coerceIn(0f, 1f)
                                    packetIsStylus = true
                                    sawEventInPacket = true
                                }
                                "BTN_TOOL_PEN", "BTN_STYLUS", "BTN_STYLUS2" -> {
                                    packetIsStylus = true
                                    if (valHex == "DOWN" || valHex == "00000001") {
                                        gpIsVisible = true
                                    }
                                    sawEventInPacket = true
                                }
                                "BTN_LEFT", "BTN_RIGHT", "BTN_MIDDLE" -> {
                                    packetIsMouse = true
                                    gpIsVisible = true
                                    pointerSeq.incrementAndGet()
                                }
                                "BTN_TOUCH" -> {
                                    if (valHex == "DOWN" || valHex == "00000001") {
                                        gpIsVisible = true
                                    }
                                    sawEventInPacket = true
                                }
                            }
                        }
                    }
                    proc.waitFor()
                } catch (_: Throwable) {
                }
                Thread.sleep(2500L)
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

        runCatching {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val getService = atmClass.getMethod("getService")
            val atm = getService.invoke(null)
            activityTaskManagerService = atm
            moveRootTaskToDisplayMethod = atm.javaClass
                .getMethod("moveRootTaskToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            getTasksMethod = atm.javaClass
                .getMethod("getTasks", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
            registerNativeTaskStackListener(atm)
        }
    }

    @SuppressLint("PrivateApi")
    private fun registerNativeTaskStackListener(atm: Any) {
        runCatching {
            val listenerClass = Class.forName("android.app.ITaskStackListener")
            val regMethod = atm.javaClass.getMethod("registerTaskStackListener", listenerClass)
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass)
            ) { _, method, args ->
                if (method.name == "onTaskCreated" && args != null && args.size >= 2) {
                    val taskId = (args[0] as? Int) ?: -1
                    val comp = args[1] as? android.content.ComponentName
                    val pkg = comp?.packageName
                    val dispId = targetPenDisplayId
                    if (taskId > 0 && dispId > 0 && pkg != null && pkg in routedPenDisplayPackages) {
                        runCatching { moveRootTaskToDisplayMethod?.invoke(atm, taskId, dispId) }
                    }
                } else if (method.name == "onTaskMovedToFront" && args != null && args.isNotEmpty()) {
                    val taskInfo = args[0]
                    val dispId = targetPenDisplayId
                    if (taskInfo != null && dispId > 0) {
                        val taskId = runCatching { taskInfo.javaClass.getField("taskId").getInt(taskInfo) }.getOrDefault(-1)
                        val baseActivity = runCatching {
                            taskInfo.javaClass.getField("baseActivity").get(taskInfo) as? android.content.ComponentName
                        }.getOrNull()
                        val pkg = baseActivity?.packageName
                        if (taskId > 0 && pkg != null && pkg in routedPenDisplayPackages) {
                            runCatching { moveRootTaskToDisplayMethod?.invoke(atm, taskId, dispId) }
                        }
                    }
                }
                null
            }
            regMethod.invoke(atm, proxy)
        }
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

                    KodaEngineProtocol.CMD_REGISTER_ROUTED_APPS -> {
                        val count = input.readInt().coerceIn(0, 256)
                        val nextPkgs = ArrayList<String>(count)
                        for (i in 0 until count) {
                            nextPkgs.add(input.readUTF())
                        }
                        if (!authenticated) {
                            output.writeByte(KodaEngineProtocol.STATUS_ERR_AUTH.toInt())
                            output.flush()
                            continue
                        }
                        routedPenDisplayPackages.clear()
                        routedPenDisplayPackages.addAll(nextPkgs)
                        output.writeByte(KodaEngineProtocol.STATUS_OK.toInt())
                        output.flush()
                    }

                    KodaEngineProtocol.CMD_SET_PEN_DISPLAY_ID -> {
                        val dispId = input.readInt()
                        if (!authenticated) {
                            output.writeByte(KodaEngineProtocol.STATUS_ERR_AUTH.toInt())
                            output.flush()
                            continue
                        }
                        targetPenDisplayId = dispId.coerceAtLeast(0)
                        output.writeByte(KodaEngineProtocol.STATUS_OK.toInt())
                        output.flush()
                    }

                    KodaEngineProtocol.CMD_MOVE_TASK_TO_DISPLAY -> {
                        val pkg = input.readUTF()
                        val dispId = input.readInt()
                        if (!authenticated || pkg.isBlank() || dispId <= 0) {
                            output.writeByte(KodaEngineProtocol.STATUS_ERR_AUTH.toInt())
                            output.flush()
                            continue
                        }
                        val ok = movePackageTaskToDisplay(pkg, dispId)
                        output.writeByte(if (ok) KodaEngineProtocol.STATUS_OK.toInt() else KodaEngineProtocol.STATUS_ERR_INJECT.toInt())
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

    private fun movePackageTaskToDisplay(pkg: String, displayId: Int): Boolean {
        val atm = activityTaskManagerService ?: return false
        val moveMethod = moveRootTaskToDisplayMethod ?: return false
        val tasksMethod = getTasksMethod ?: return false
        return runCatching {
            val tasks = tasksMethod.invoke(atm, 32) as? List<*> ?: return@runCatching false
            var moved = false
            for (t in tasks) {
                if (t == null) continue
                val baseActivity = runCatching {
                    t.javaClass.getField("baseActivity").get(t) as? android.content.ComponentName
                }.getOrNull()
                if (baseActivity?.packageName == pkg) {
                    val taskId = t.javaClass.getField("taskId").getInt(t)
                    moveMethod.invoke(atm, taskId, displayId)
                    moved = true
                }
            }
            moved
        }.getOrDefault(false)
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
        // Android 14/15/16 IInputManager / InputManagerGlobal 5-arg overload
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
        if (mMulti != null) {
            for (devId in targetDeviceIds) {
                runCatching {
                    mMulti.invoke(im, icon, 0, devId, 0, null)
                    applied = true
                }
            }
        }
        return applied
    }

    private fun restoreArrowPointerIcon(): Boolean {
        val im = inputManagerInstance ?: return false
        return runCatching {
            val mType = im.javaClass.getMethod("setPointerIconType", Int::class.javaPrimitiveType)
            mType.invoke(im, PointerIcon.TYPE_ARROW)
            true
        }.getOrDefault(false)
    }
}
