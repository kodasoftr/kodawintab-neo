package com.example

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import com.example.display.DisplayTargetManager
import com.example.display.TargetDisplayInfo
import com.example.driver.TabletPipeline
import com.example.driver.TabletProfileRepository
import com.example.driver.TabletSlotId
import com.example.hub.CursorOverlayManager
import com.example.hub.HubManager
import com.example.koda.engine.KodaEngineClient
import com.example.pendisplay.PenDisplayEngineManager
import com.example.service.KodaHubForegroundService
import com.example.usb.UsbTabletCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class AppErrorReport(
    val errorName: String,
    val reason: String,
    val detail: String = ""
)

class KodaWintabApp : Application() {

    val coopMonitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val startedActivities = HashSet<Int>()
    @Volatile var isAppInForeground: Boolean = false
        private set

    lateinit var engineClient: KodaEngineClient
        private set
    lateinit var profileRepository: TabletProfileRepository
        private set
    lateinit var cursorOverlayManager: CursorOverlayManager
        private set
    lateinit var pipelineMesa1: TabletPipeline
        private set
    lateinit var pipelineMesa2: TabletPipeline
        private set
    lateinit var usbCoordinator: UsbTabletCoordinator
        private set
    lateinit var displayManager: DisplayTargetManager
        private set
    lateinit var hubManager: HubManager
        private set
    lateinit var penDisplayManager: PenDisplayEngineManager
        private set

    private var globalPointerJob: Job? = null
    @Volatile var onDisplayMetricsChanged: ((TargetDisplayInfo) -> Unit)? = null

    private val _startupError = MutableStateFlow<AppErrorReport?>(null)
    val startupError: StateFlow<AppErrorReport?> = _startupError.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        installCrashGuard()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                synchronized(startedActivities) {
                    startedActivities.add(System.identityHashCode(activity))
                    isAppInForeground = true
                }
                if (::cursorOverlayManager.isInitialized) {
                    cursorOverlayManager.setOwnAppForeground(true)
                }
            }
            override fun onActivityStopped(activity: Activity) {
                val nowInForeground = synchronized(startedActivities) {
                    startedActivities.remove(System.identityHashCode(activity))
                    val any = startedActivities.isNotEmpty()
                    isAppInForeground = any
                    any
                }
                if (::cursorOverlayManager.isInitialized) {
                    cursorOverlayManager.setOwnAppForeground(nowInForeground)
                }
            }
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        engineClient = KodaEngineClient(this)
        profileRepository = TabletProfileRepository(this)
        cursorOverlayManager = CursorOverlayManager(this).apply {
            setOwnAppForeground(isAppInForeground)
            onNativePointerHideChanged = { hide ->
                coopMonitorScope.launch(Dispatchers.IO) {
                    runCatching { engineClient.hideNativePointer(hide) }
                }
            }
        }
        pipelineMesa1 = TabletPipeline(engineClient).apply {
            virtualDeviceId = TabletSlotId.MESA_1.virtualDeviceId
            onCursorCoordinate = { x, y, visible, fromTablet ->
                cursorOverlayManager.updateCursorPosition(x, y, visible, fromTablet, channel = 1)
            }
        }
        pipelineMesa2 = TabletPipeline(engineClient).apply {
            virtualDeviceId = TabletSlotId.MESA_2.virtualDeviceId
            onCursorCoordinate = { x, y, visible, fromTablet ->
                cursorOverlayManager.updateCursorPosition(x, y, visible, fromTablet, channel = 2)
            }
        }
        usbCoordinator = UsbTabletCoordinator(
            context = this,
            profileRepo = profileRepository,
            pipelineMesa1 = pipelineMesa1,
            pipelineMesa2 = pipelineMesa2,
            engineClient = engineClient
        )
        displayManager = DisplayTargetManager(this)
        hubManager = HubManager(this, engineClient, cursorOverlayManager).apply {
            restorePersistedCursors()
        }
        penDisplayManager = PenDisplayEngineManager(
            context = this,
            scope = coopMonitorScope,
            usbCoordinator = usbCoordinator,
            displayManager = displayManager,
            profileRepo = profileRepository,
            pipelineMesa1 = pipelineMesa1,
            engineClient = engineClient,
            installedAppsProvider = { hubManager.installedApps.value }
        )
        pipelineMesa1.onCursorCoordinate = { x, y, visible, fromTablet ->
            cursorOverlayManager.updateCursorPosition(x, y, visible, fromTablet, channel = 1)
            val pdState = penDisplayManager.state.value
            if (pdState.isVirtualDisplayActive && pdState.selectedPreset.widthPx > 0 && pdState.selectedPreset.heightPx > 0) {
                val tel = pipelineMesa1.telemetry.value
                penDisplayManager.dispatchStylusInputToPenDisplay(
                    normX = x / pdState.selectedPreset.widthPx.toFloat(),
                    normY = y / pdState.selectedPreset.heightPx.toFloat(),
                    pressureNorm = tel.lastCurvedPressure,
                    isTipDown = tel.isTipDown,
                    tiltX = tel.lastTiltX,
                    tiltY = tel.lastTiltY,
                    injectSystemEvent = pipelineMesa1.config.targetDisplayId != pdState.virtualDisplayId
                )
            }
        }
        usbCoordinator.onTabletProfileConnected = { slot, profile ->
            val targetDisplayId = if (slot == com.example.driver.TabletSlotId.MESA_2) {
                pipelineMesa2.config.targetDisplayId
            } else {
                pipelineMesa1.config.targetDisplayId
            }
            val disp = displayManager.getDisplayOrDefault(targetDisplayId)
            if (slot == com.example.driver.TabletSlotId.MESA_2) {
                val updatedCfg = pipelineMesa2.config.copy(profileId = profile.id)
                pipelineMesa2.updateConfiguration(
                    newConfig = updatedCfg,
                    profile = profile,
                    parser = profileRepository.createParserFor(profile),
                    displayWidth = disp.widthPx.toFloat(),
                    displayHeight = disp.heightPx.toFloat(),
                    displayRotationDeg = disp.rotationDeg,
                    displayXdpi = disp.xdpi,
                    displayYdpi = disp.ydpi
                )
            } else {
                val updatedCfg = pipelineMesa1.config.copy(profileId = profile.id)
                pipelineMesa1.updateConfiguration(
                    newConfig = updatedCfg,
                    profile = profile,
                    parser = profileRepository.createParserFor(profile),
                    displayWidth = disp.widthPx.toFloat(),
                    displayHeight = disp.heightPx.toFloat(),
                    displayRotationDeg = disp.rotationDeg,
                    displayXdpi = disp.xdpi,
                    displayYdpi = disp.ydpi
                )
                cursorOverlayManager.syncTabletMatrixConfig(updatedCfg, profile, disp)
            }
        }
        usbCoordinator.resolveCursorChannel = { key ->
            hubManager.resolveChannelForDeviceKey(key)
        }
        usbCoordinator.onMouseCoordinate = { x, y, visible, channel ->
            cursorOverlayManager.updateCursorPosition(x, y, visible, fromTablet = false, channel = channel, directSource = true)
        }

        // Permanent application-scoped display + rotation listener (never killed by Activity/ViewModel lifecycle)
        displayManager.startListening { disp ->
            syncDisplayStateEverywhere(disp)
        }
        registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) {
                displayManager.refreshDisplays()
                syncDisplayStateEverywhere(displayManager.getDisplayOrDefault(0))
            }

            override fun onLowMemory() = Unit
            override fun onTrimMemory(level: Int) = Unit
        })

        // Apply saved config immediately to pipeline 1 & 2 on startup
        coopMonitorScope.launch {
            runCatching {
                val cfg1 = profileRepository.loadConfig(TabletSlotId.GLOBAL)
                val prof1 = profileRepository.getProfileById(cfg1.profileId)
                val disp1 = displayManager.getDisplayOrDefault(cfg1.targetDisplayId)
                pipelineMesa1.updateConfiguration(
                    newConfig = cfg1,
                    profile = prof1,
                    parser = profileRepository.createParserFor(prof1),
                    displayWidth = disp1.widthPx.toFloat(),
                    displayHeight = disp1.heightPx.toFloat(),
                    displayRotationDeg = disp1.rotationDeg,
                    displayXdpi = disp1.xdpi,
                    displayYdpi = disp1.ydpi
                )
                usbCoordinator.updateMouseDisplayBounds(
                    widthPx = disp1.widthPx.toFloat(),
                    heightPx = disp1.heightPx.toFloat()
                )
                cursorOverlayManager.syncTabletMatrixConfig(cfg1, prof1, disp1)
            }
        }

        // Keep background driver, USB claim, and overlay alive across app switches / launcher restarts
        if (engineClient.isPermanentlyActivated() && !KodaHubForegroundService.wasUserTerminated(this)) {
            ensureBackgroundDriverActive()
        }
    }

    fun ensureBackgroundDriverActive() {
        runCatching { usbCoordinator.registerReceiver() }
        runCatching { pipelineMesa1.start() }
        runCatching { hubManager.startCoopMonitor(coopMonitorScope) }
        runCatching { cursorOverlayManager.setPaused(false) }
        runCatching { cursorOverlayManager.refreshOverlayPermission() }
        runCatching { startGlobalPointerStream() }
        if (engineClient.isPermanentlyActivated() && !KodaHubForegroundService.wasUserTerminated(this)) {
            runCatching { KodaHubForegroundService.start(this) }
        }
        runCatching { usbCoordinator.refreshConnectedDevices() }
    }

    fun syncDisplayStateEverywhere(disp: TargetDisplayInfo) {
        usbCoordinator.updateMouseDisplayBounds(
            widthPx = disp.widthPx.toFloat(),
            heightPx = disp.heightPx.toFloat()
        )
        cursorOverlayManager.updateDisplayBounds(
            widthPx = disp.widthPx.toFloat(),
            heightPx = disp.heightPx.toFloat(),
            rotationDeg = disp.rotationDeg
        )

        val cfg1 = pipelineMesa1.config
        if (cfg1.targetDisplayId == disp.displayId || cfg1.targetDisplayId == 0) {
            val targetDisp = displayManager.getDisplayOrDefault(cfg1.targetDisplayId)
            val prof = profileRepository.getProfileById(cfg1.profileId)
            pipelineMesa1.updateConfiguration(
                newConfig = cfg1,
                profile = prof,
                parser = pipelineMesa1.activeParser,
                displayWidth = targetDisp.widthPx.toFloat(),
                displayHeight = targetDisp.heightPx.toFloat(),
                displayRotationDeg = targetDisp.rotationDeg,
                displayXdpi = targetDisp.xdpi,
                displayYdpi = targetDisp.ydpi
            )
            cursorOverlayManager.syncTabletMatrixConfig(cfg1, prof, targetDisp)
        }
        val cfg2 = pipelineMesa2.config
        if (cfg2.targetDisplayId == disp.displayId || cfg2.targetDisplayId == 0) {
            val targetDisp2 = displayManager.getDisplayOrDefault(cfg2.targetDisplayId)
            val prof2 = profileRepository.getProfileById(cfg2.profileId)
            pipelineMesa2.updateConfiguration(
                newConfig = cfg2,
                profile = prof2,
                parser = pipelineMesa2.activeParser,
                displayWidth = targetDisp2.widthPx.toFloat(),
                displayHeight = targetDisp2.heightPx.toFloat(),
                displayRotationDeg = targetDisp2.rotationDeg,
                displayXdpi = targetDisp2.xdpi,
                displayYdpi = targetDisp2.ydpi
            )
        }
        onDisplayMetricsChanged?.invoke(disp)
    }

    /**
     * Streams live mouse deltas & tablet magnetic-field stylus X/Y coordinates from KodaShellServer
     * whenever the user is in the Android Launcher or any other app, and silently maintains
     * permanent ADB session reconnection.
     */
    fun startGlobalPointerStream() {
        if (globalPointerJob?.isActive == true) return
        globalPointerJob = coopMonitorScope.launch(Dispatchers.IO) {
            var reconnectTick = 0
            while (isActive) {
                val st = engineClient.state.value
                if (!st.isActive) {
                    if (st.permanentlyActivated && reconnectTick % 120 == 0) {
                        runCatching { engineClient.verifyConnection() }
                    }
                    reconnectTick++
                    delay(25L)
                    continue
                }
                reconnectTick = 0
                val snap = cursorOverlayManager.snapshot.value
                val hasDirectUsbFeed = usbCoordinator.hasActiveTabletSession() || usbCoordinator.hasActiveMouseSession()
                val hasAnyCursor = snap.channel1Cursor != null || snap.channel2Cursor != null || snap.activeCursor != null
                if (!isAppInForeground && !hasDirectUsbFeed && hasAnyCursor && !snap.isPaused) {
                    val sample = engineClient.pollGlobalPointer()
                    if (sample != null) {
                        val disp = displayManager.getDisplayOrDefault(0)
                        cursorOverlayManager.applyGlobalPointerSample(
                            sample = sample,
                            displayWidthPx = disp.widthPx.toFloat(),
                            displayHeightPx = disp.heightPx.toFloat(),
                            displayRotationDeg = disp.rotationDeg
                        )
                    }
                    delay(12L)
                } else {
                    delay(48L)
                }
            }
        }
    }

    fun stopGlobalPointerStream() {
        globalPointerJob?.cancel()
        globalPointerJob = null
    }

    private fun installCrashGuard() {
        val prefs = getSharedPreferences("koda_crash_guard", MODE_PRIVATE)
        val savedName = prefs.getString("last_crash_name", null)
        val savedReason = prefs.getString("last_crash_reason", null)
        val savedDetail = prefs.getString("last_crash_detail", "")
        if (!savedName.isNullOrBlank() && !savedReason.isNullOrBlank()) {
            _startupError.value = AppErrorReport(
                errorName = savedName,
                reason = savedReason,
                detail = savedDetail.orEmpty()
            )
            prefs.edit().clear().apply()
        }

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val root = generateSequence(throwable) { it.cause }.lastOrNull() ?: throwable
            prefs.edit()
                .putString("last_crash_name", root.javaClass.simpleName)
                .putString(
                    "last_crash_reason",
                    "Ocorreu uma falha inesperada (${root.message ?: "sem detalhes"}) — verifique os privilégios ADB da Koda Engine."
                )
                .putString("last_crash_detail", throwable.stackTraceToString().take(600))
                .commit()
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun recordNonFatalError(errorName: String, reason: String, detail: String = "") {
        _startupError.value = AppErrorReport(errorName, reason, detail)
    }

    fun dismissStartupError() {
        _startupError.value = null
    }
}
