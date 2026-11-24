package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.AppErrorReport
import com.example.KodaWintabApp
import com.example.display.TargetDisplayInfo
import com.example.driver.DriverTelemetry
import com.example.driver.HidHexFrame
import com.example.driver.PressureControlPoint
import com.example.driver.TabletConfig
import com.example.driver.TabletProfileJson
import com.example.driver.TabletSlotId
import com.example.hub.CoopBannerEvent
import com.example.hub.CursorDecoder
import com.example.hub.CursorOverlaySnapshot
import com.example.hub.CursorRenderMode
import com.example.hub.HubSlotAssignment
import com.example.hub.HubSlotId
import com.example.hub.InstalledAppEntry
import com.example.koda.engine.KodaEngineState
import com.example.pendisplay.KodaStreamTelemetry
import com.example.pendisplay.PenDisplayPreset
import com.example.pendisplay.PenDisplayState
import com.example.pendisplay.PenDisplayTaskEntry
import com.example.service.KodaHubForegroundService
import com.example.usb.ConnectedUsbDeviceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class KodaViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as KodaWintabApp
    private val engineClient = app.engineClient
    private val profileRepo = app.profileRepository
    private val pipeline1 = app.pipelineMesa1
    private val pipeline2 = app.pipelineMesa2
    private val usbCoordinator = app.usbCoordinator
    private val displayManager = app.displayManager
    private val hubManager = app.hubManager
    private val cursorOverlayManager = app.cursorOverlayManager
    private val penDisplayManager = app.penDisplayManager

    val engineState: StateFlow<KodaEngineState> = engineClient.state
    val usbDevices: StateFlow<List<ConnectedUsbDeviceInfo>> = usbCoordinator.devices
    val usbEventLog: StateFlow<String> = usbCoordinator.lastUsbEventLog
    val displays: StateFlow<List<TargetDisplayInfo>> = displayManager.displays
    val displayRotationDeg: StateFlow<Int> = displayManager.currentRotationDeg
    val hubSlots: StateFlow<List<HubSlotAssignment>> = hubManager.slots
    val installedApps: StateFlow<List<InstalledAppEntry>> = hubManager.installedApps
    val coopEvent: StateFlow<CoopBannerEvent?> = hubManager.coopEvent
    val simulatedClosedPackages: StateFlow<Set<String>> = hubManager.simulatedClosedPackages
    val cursorSnapshot: StateFlow<CursorOverlaySnapshot> = cursorOverlayManager.snapshot
    val penDisplayState: StateFlow<PenDisplayState> = penDisplayManager.state
    val streamTelemetry: StateFlow<KodaStreamTelemetry> = penDisplayManager.streamEngine.telemetry
    val boundPenDisplayPackages: StateFlow<Set<String>> = penDisplayManager.appRouter.boundPackages
    val activePenDisplayTasks: StateFlow<List<PenDisplayTaskEntry>> = penDisplayManager.appRouter.activeTasks

    private val _selectedSlot = MutableStateFlow(TabletSlotId.GLOBAL)
    val selectedSlot: StateFlow<TabletSlotId> = _selectedSlot.asStateFlow()

    private val _tabletConfig = MutableStateFlow(TabletConfig())
    val tabletConfig: StateFlow<TabletConfig> = _tabletConfig.asStateFlow()

    private val _availableProfiles = MutableStateFlow<List<TabletProfileJson>>(emptyList())
    val availableProfiles: StateFlow<List<TabletProfileJson>> = _availableProfiles.asStateFlow()

    val telemetryMesa1: StateFlow<DriverTelemetry> = pipeline1.telemetry
    val telemetryMesa2: StateFlow<DriverTelemetry> = pipeline2.telemetry
    val hexFrames: StateFlow<List<HidHexFrame>> = pipeline1.hexFrames

    private val _statusToast = MutableStateFlow<String?>(null)
    val statusToast: StateFlow<String?> = _statusToast.asStateFlow()

    private val _errorPopup = MutableStateFlow<AppErrorReport?>(null)
    val errorPopup: StateFlow<AppErrorReport?> = _errorPopup.asStateFlow()

    private val _batteryUnrestricted = MutableStateFlow(checkBatteryIgnoringOptimizations())
    val batteryUnrestricted: StateFlow<Boolean> = _batteryUnrestricted.asStateFlow()

    private var saveConfigJob: Job? = null

    init {
        engineClient.syncPermanentActivationFromPrefs()
        _availableProfiles.value = runCatching { profileRepo.getAllProfiles() }.getOrDefault(emptyList())
        app.onDisplayMetricsChanged = { disp ->
            applyPipelineConfig(_tabletConfig.value, disp.rotationDeg)
        }
        viewModelScope.launch {
            app.startupError.collect { err ->
                if (err != null) {
                    _errorPopup.value = err
                }
            }
        }
        viewModelScope.launch {
            runCatching {
                val loaded = profileRepo.loadConfig(TabletSlotId.GLOBAL)
                _tabletConfig.value = loaded
                applyPipelineConfig(loaded, displayRotationDeg.value)
            }
            withContext(Dispatchers.IO) {
                runCatching { hubManager.loadLaunchableApps() }
            }
            runCatching { usbCoordinator.registerReceiver() }
            runCatching { hubManager.startCoopMonitor(app.coopMonitorScope) }
            if (engineClient.isPermanentlyActivated()) {
                app.ensureBackgroundDriverActive()
            }
            val connState = engineClient.verifyConnection()
            if (connState.isActive) {
                cursorOverlayManager.setPaused(false)
                startHubServiceSafely()
            }
            cursorOverlayManager.refreshOverlayPermission()
            refreshBatteryOptimizationState()
        }
    }

    fun isPermanentlyActivated(): Boolean = engineClient.isPermanentlyActivated()

    fun setCalibrationCursorSuppressed(suppressed: Boolean) {
        cursorOverlayManager.setCalibrationSuppressed(suppressed)
    }

    fun onNewUsbIntent(intent: android.content.Intent?) {
        usbCoordinator.onNewUsbIntent(intent)
    }

    fun updateCursorFromMotionEvent(x: Float, y: Float, isStylus: Boolean, visible: Boolean = true) {
        cursorOverlayManager.updateCursorPosition(
            x = x,
            y = y,
            visible = visible,
            fromTablet = isStylus,
            channel = 1
        )
    }

    fun verifyEngineConnection(showErrorPopupOnFail: Boolean = true) {
        viewModelScope.launch {
            val res = engineClient.verifyConnection()
            if (res.isActive) {
                startHubServiceSafely()
                _errorPopup.value = null
            } else if (showErrorPopupOnFail) {
                _errorPopup.value = AppErrorReport(
                    errorName = res.lastErrorName ?: "KodaPrivilegeNotActiveException",
                    reason = res.lastErrorReason
                        ?: "O aplicativo não conseguiu ativar os privilégios ADB. Conecte o aparelho no PC e execute o comando ADB.",
                    detail = "Pacote: ${app.packageName}\nStatus: ${res.lastCheckMessage}"
                )
            }
        }
    }

    fun silentPollEngineConnection() {
        viewModelScope.launch {
            val res = engineClient.verifyConnection()
            if (res.isActive) {
                startHubServiceSafely()
            }
        }
    }

    fun dismissErrorPopup() {
        _errorPopup.value = null
        app.dismissStartupError()
    }

    fun startHubServiceSafely() {
        runCatching { KodaHubForegroundService.start(app) }
            .onFailure { t ->
                _errorPopup.value = AppErrorReport(
                    errorName = t.javaClass.simpleName,
                    reason = "Não foi possível iniciar o serviço em primeiro plano: ${t.message}",
                    detail = t.stackTraceToString().take(400)
                )
            }
    }

    fun setAdbTransportMode(mode: com.example.koda.engine.AdbTransportMode) {
        engineClient.setTransportMode(mode)
    }

    fun setWifiPort(port: String) {
        engineClient.setWifiPort(port)
    }

    fun setKeepTerminalOpenMode(enabled: Boolean) {
        engineClient.setKeepTerminalOpenMode(enabled)
    }

    fun refreshLocalNetworkIp() {
        engineClient.refreshLocalNetworkIp()
    }

    fun enterDiagnosticMode() {
        engineClient.enterDiagnosticMode()
        startHubServiceSafely()
    }

    fun activateSingleEngine() {
        engineClient.activateSingleEngine()
        app.ensureBackgroundDriverActive()
        startHubServiceSafely()
        displayManager.refreshDisplays()
        refreshBatteryOptimizationState()
        _statusToast.value = "Koda Core Engine ativada em 1 toque (sem PC / sem Modo Desenvolvedor)!"
        viewModelScope.launch {
            val probed = engineClient.activateStandaloneWithAutoProbe()
            if (probed.isActive) {
                cursorOverlayManager.refreshOverlayPermission()
                _statusToast.value = "Motor Autônomo + Daemon Shell ativos (UID ${probed.serverUid})!"
            }
        }
    }

    private fun checkBatteryIgnoringOptimizations(): Boolean {
        val pm = app.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(app.packageName) }.getOrDefault(false)
    }

    fun refreshBatteryOptimizationState() {
        _batteryUnrestricted.value = checkBatteryIgnoringOptimizations()
    }

    fun openBatteryOptimizationSettings() {
        runCatching {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${app.packageName}")
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
        }.onFailure {
            runCatching {
                val fallback = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(fallback)
            }
        }
    }

    fun openAccessibilitySettings() {
        runCatching {
            val intent = android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
        }
    }

    fun openOverlaySettings() {
        runCatching {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${app.packageName}")
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
        }
    }

    fun togglePenDisplayConnected(enabled: Boolean) {
        penDisplayManager.setPenDisplayConnectedManual(enabled)
        _statusToast.value = if (enabled) {
            "Mesa com Tela ativada · Display Virtual Kodasoftr iniciado"
        } else {
            "Mesa com Tela encerrada"
        }
    }

    fun selectPenDisplayPreset(preset: PenDisplayPreset) {
        penDisplayManager.selectPreset(preset)
        _statusToast.value = "Resolução da Pen Display: ${preset.widthPx}×${preset.heightPx} · ${preset.dpi} DPI"
    }

    fun startPenDisplayVirtualDesktop() {
        val ok = penDisplayManager.startPenDisplaySession()
        _statusToast.value = if (ok) {
            "Área de trabalho Kodasoftr projetada no Display Virtual!"
        } else {
            "Não foi possível iniciar o Display Virtual"
        }
    }

    fun stopPenDisplayVirtualDesktop() {
        penDisplayManager.stopPenDisplaySession()
        _statusToast.value = "Display Virtual Kodasoftr parado"
    }

    fun toggleBoundPenDisplayApp(packageName: String) {
        penDisplayManager.toggleBoundApp(packageName)
    }

    fun launchAppOnPenDisplay(app: InstalledAppEntry) {
        val ok = penDisplayManager.launchAppOnPenDisplay(app)
        _statusToast.value = if (ok) {
            "${app.label} redirecionado para a Pen Display (#${penDisplayManager.state.value.virtualDisplayId})"
        } else {
            "Não foi possível abrir ${app.label} na Pen Display"
        }
    }

    fun removePenDisplayTask(packageName: String) {
        penDisplayManager.appRouter.removeTaskEntry(packageName)
    }

    fun sendPenDisplayTestStylus(normX: Float, normY: Float, pressure: Float, isTipDown: Boolean) {
        penDisplayManager.dispatchStylusInputToPenDisplay(normX, normY, pressure, isTipDown)
        feedSimulatedUgeePacket(normX, normY, pressure, isTipDown)
    }

    fun selectTabletSlot(slotId: TabletSlotId) {
        _selectedSlot.value = slotId
        viewModelScope.launch {
            val loaded = profileRepo.loadConfig(slotId)
            _tabletConfig.value = loaded
            applyPipelineConfig(loaded, displayRotationDeg.value)
        }
    }

    fun updateTabletConfig(transform: (TabletConfig) -> TabletConfig) {
        val updated = transform(_tabletConfig.value)
        _tabletConfig.value = updated
        applyPipelineConfig(updated, displayRotationDeg.value)
        saveConfigJob?.cancel()
        saveConfigJob = viewModelScope.launch(Dispatchers.IO) {
            delay(180L)
            profileRepo.saveConfig(updated)
        }
    }

    fun selectProfile(profileId: String) {
        updateTabletConfig { it.copy(profileId = profileId) }
    }

    fun applyPressureCurvePreset(points: List<PressureControlPoint>, label: String) {
        updateTabletConfig { it.copy(pressureCurve = points) }
        _statusToast.value = "Preset de pressão aplicado: $label"
    }

    fun updatePressurePoint(index: Int, inputNorm: Float, outputNorm: Float) {
        updateTabletConfig { cfg ->
            val mutable = cfg.pressureCurve.toMutableList()
            if (index in mutable.indices) {
                val clampedX = if (index == 0) 0f else if (index == mutable.lastIndex) 1f else {
                    inputNorm.coerceIn(mutable[index - 1].inputNorm + 0.04f, mutable[index + 1].inputNorm - 0.04f)
                }
                mutable[index] = PressureControlPoint(clampedX, outputNorm.coerceIn(0f, 1f))
            }
            cfg.copy(pressureCurve = mutable)
        }
    }

    fun resetPressureCurve() {
        updateTabletConfig {
            it.copy(
                pressureCurve = listOf(
                    PressureControlPoint(0f, 0f),
                    PressureControlPoint(0.25f, 0.25f),
                    PressureControlPoint(0.50f, 0.50f),
                    PressureControlPoint(0.75f, 0.75f),
                    PressureControlPoint(1.0f, 1.0f)
                )
            )
        }
    }

    fun feedSimulatedUgeePacket(
        normX: Float,
        normY: Float,
        normPressure: Float,
        isTipDown: Boolean,
        tiltX: Int = 6,
        tiltY: Int = -4
    ) {
        val profile = profileRepo.getProfileById(_tabletConfig.value.profileId)
        val rawX = (normX.coerceIn(0f, 1f) * profile.maxX).toInt()
        val rawY = (normY.coerceIn(0f, 1f) * profile.maxY).toInt()
        val rawP = if (isTipDown) (normPressure.coerceIn(0f, 1f) * profile.maxPressure).toInt() else 0
        val status = if (isTipDown) 0xA1 else 0xA0

        val pkt = ByteArray(10)
        pkt[0] = 0x02
        pkt[1] = status.toByte()
        pkt[2] = (rawX and 0xFF).toByte()
        pkt[3] = ((rawX ushr 8) and 0xFF).toByte()
        pkt[4] = (rawY and 0xFF).toByte()
        pkt[5] = ((rawY ushr 8) and 0xFF).toByte()
        pkt[6] = (rawP and 0xFF).toByte()
        pkt[7] = ((rawP ushr 8) and 0xFF).toByte()
        pkt[8] = tiltX.toByte()
        pkt[9] = tiltY.toByte()

        val targetPipeline = if (_selectedSlot.value == TabletSlotId.MESA_2) pipeline2 else pipeline1
        targetPipeline.start()
        targetPipeline.feedPacketFromDiagnostic(pkt, pkt.size)
    }

    fun applyFourCornerCalibration(minX: Int, maxX: Int, minY: Int, maxY: Int) {
        val profile = profileRepo.getProfileById(_tabletConfig.value.profileId)
        val left = (minX.toFloat() / profile.maxX).coerceIn(0f, 0.45f)
        val top = (minY.toFloat() / profile.maxY).coerceIn(0f, 0.45f)
        val right = (maxX.toFloat() / profile.maxX).coerceIn(left + 0.1f, 1f)
        val bottom = (maxY.toFloat() / profile.maxY).coerceIn(top + 0.1f, 1f)
        updateTabletConfig {
            it.copy(
                activeArea = it.activeArea.copy(
                    leftNorm = left,
                    topNorm = top,
                    widthNorm = (right - left).coerceIn(0.1f, 1f),
                    heightNorm = (bottom - top).coerceIn(0.1f, 1f)
                )
            )
        }
        _statusToast.value = "Calibração com sucesso! Área ativa atualizada."
    }

    fun resetCalibrationToFactory() {
        updateTabletConfig {
            it.copy(
                activeArea = com.example.driver.ActiveAreaRect(0f, 0f, 1f, 1f),
                screenArea = com.example.driver.ActiveAreaRect(0f, 0f, 1f, 1f),
                manualRotationDeg = 0,
                invertX = false,
                invertY = false,
                autoRotation = true,
                rotateTabletAxesWithScreen = false,
                lockAspectRatio = true,
                dpiMode = com.example.driver.DpiNormalizationMode.AUTO
            )
        }
        _statusToast.value = "Calibração restaurada para o padrão de fábrica da mesa."
    }

    fun resetTabletTab() {
        updateTabletConfig {
            it.copy(
                activeArea = com.example.driver.ActiveAreaRect(0f, 0f, 1f, 1f),
                screenArea = com.example.driver.ActiveAreaRect(0f, 0f, 1f, 1f),
                autoRotation = true,
                rotateTabletAxesWithScreen = false,
                manualRotationDeg = 0,
                invertX = false,
                invertY = false,
                lockAspectRatio = true
            )
        }
        _statusToast.value = "Aba Tablet resetada para o padrão de fábrica!"
    }

    fun resetPenTab() {
        updateTabletConfig {
            it.copy(
                pressureCurve = listOf(
                    PressureControlPoint(0f, 0f),
                    PressureControlPoint(0.25f, 0.25f),
                    PressureControlPoint(0.50f, 0.50f),
                    PressureControlPoint(0.75f, 0.75f),
                    PressureControlPoint(1.0f, 1.0f)
                ),
                minPressureThreshold = 0.01f,
                maxPressureCap = 1.0f
            )
        }
        _statusToast.value = "Aba Pen (pressão) resetada para o padrão linear 1:1!"
    }

    fun resetBindingsTab() {
        updateTabletConfig {
            it.copy(bindings = com.example.driver.PenBindings())
        }
        _statusToast.value = "Aba Bindings (atalhos) resetada para o padrão!"
    }

    fun resetFiltersTab() {
        updateTabletConfig {
            it.copy(filters = com.example.driver.FilterSettings())
        }
        _statusToast.value = "Aba Filters resetada (Modo Raw PC 0ms)!"
    }

    fun resetOutputTab() {
        displayManager.refreshDisplays()
        val disp = displayManager.getDisplayOrDefault(0)
        updateTabletConfig {
            it.copy(
                dpiMode = com.example.driver.DpiNormalizationMode.AUTO,
                manualDpiX = disp.xdpi.toInt().coerceIn(72, 1200),
                manualDpiY = disp.ydpi.toInt().coerceIn(72, 1200),
                outputMode = com.example.driver.OutputMode.ABSOLUTE,
                relativeSensitivity = 1.4f,
                targetDisplayId = 0,
                targetAppPackage = null
            )
        }
        _statusToast.value = "Aba Output resetada para o padrão de fábrica!"
    }

    fun resetAllTabs() {
        displayManager.refreshDisplays()
        val disp = displayManager.getDisplayOrDefault(0)
        updateTabletConfig { current ->
            TabletConfig(
                slotId = current.slotId,
                profileId = current.profileId,
                manualDpiX = disp.xdpi.toInt().coerceIn(72, 1200),
                manualDpiY = disp.ydpi.toInt().coerceIn(72, 1200)
            )
        }
        _statusToast.value = "Todas as abas resetadas para o padrão de fábrica!"
    }

    fun activateAutoDpi() {
        displayManager.refreshDisplays()
        val disp = displayManager.getDisplayOrDefault(_tabletConfig.value.targetDisplayId)
        updateTabletConfig {
            it.copy(
                dpiMode = com.example.driver.DpiNormalizationMode.AUTO,
                manualDpiX = disp.xdpi.toInt().coerceIn(72, 1200),
                manualDpiY = disp.ydpi.toInt().coerceIn(72, 1200)
            )
        }
        _statusToast.value = "DPI Automática ativada (${disp.xdpi.toInt()}×${disp.ydpi.toInt()} DPI · pressão preservada)"
    }

    fun refreshUsbAndDisplays() {
        usbCoordinator.refreshConnectedDevices()
        displayManager.refreshDisplays()
        cursorOverlayManager.refreshOverlayPermission()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { hubManager.loadLaunchableApps() }
        }
    }

    fun requestUsbPermission(deviceKey: String) {
        runCatching {
            usbCoordinator.requestPermissionByKey(deviceKey, _selectedSlot.value)
        }.onFailure { t ->
            _errorPopup.value = AppErrorReport(
                errorName = t.javaClass.simpleName,
                reason = "Falha ao solicitar permissão USB: ${t.message}"
            )
        }
    }

    fun assignHubDevice(slotId: HubSlotId, deviceKey: String?, deviceName: String) {
        hubManager.assignPhysicalDevice(slotId, deviceKey, deviceName)
    }

    fun assignHubCombinedDevices(
        slotId: HubSlotId,
        primaryKey: String?,
        primaryName: String,
        secondaryKey: String?,
        secondaryName: String?
    ) {
        hubManager.assignCombinedDevices(slotId, primaryKey, primaryName, secondaryKey, secondaryName)
        _statusToast.value = if (secondaryKey != null) {
            "Modo combinado ativo em ${slotId.label}: $primaryName + $secondaryName"
        } else {
            "${slotId.label}: $primaryName"
        }
    }

    fun assignHubTargetApp(slotId: HubSlotId, appEntry: InstalledAppEntry?) {
        hubManager.assignTargetApp(slotId, appEntry)
    }

    fun importCustomCursor(context: Context, slotId: HubSlotId, uri: Uri) {
        viewModelScope.launch {
            val fileName = resolveFileName(context, uri)
            val result = withContext(Dispatchers.IO) {
                runCatching { CursorDecoder.decodeFromUriWithBytes(context, uri, fileName) }.getOrNull()
            }
            if (result != null) {
                val (decoded, rawBytes) = result
                hubManager.assignCustomCursor(slotId, decoded, rawBytes)
                if (engineState.value.isActive) {
                    withContext(Dispatchers.IO) {
                        if (cursorSnapshot.value.mode == CursorRenderMode.NATIVE) {
                            val nativeOk = engineClient.sendCustomPointerIcon(decoded.bitmap, decoded.hotspotX, decoded.hotspotY)
                            cursorOverlayManager.setNativeHookApplied(nativeOk)
                        } else {
                            engineClient.hideNativePointer(true)
                        }
                    }
                }
                val chLabel = if (slotId.cursorChannel() == 2) "Canal 2 (Mouse 2 + Mesa 2)" else "Canal 1 (Mouse 1 + Mesa 1)"
                _statusToast.value = "Cursor ${decoded.sourceFormat} unificado em $chLabel (ponta ${decoded.hotspotX.toInt()},${decoded.hotspotY.toInt()})"
            } else {
                _errorPopup.value = AppErrorReport(
                    errorName = "CursorDecodeException",
                    reason = "Não foi possível decodificar '$fileName'. Selecione um arquivo .cur, .ani ou .png válido."
                )
            }
        }
    }

    fun clearCustomCursor(slotId: HubSlotId) {
        hubManager.assignCustomCursor(slotId, null, null)
        if (engineState.value.isActive) {
            viewModelScope.launch(Dispatchers.IO) {
                if (cursorOverlayManager.snapshot.value.activeCursor == null) {
                    engineClient.hideNativePointer(false)
                }
            }
        }
    }

    fun updateSlotCursorHotspot(slotId: HubSlotId, hotspotX: Float, hotspotY: Float) {
        hubManager.updateSlotCursorHotspot(slotId, hotspotX, hotspotY)
    }

    fun setCursorRenderMode(mode: CursorRenderMode) {
        cursorOverlayManager.setRenderMode(mode)
        val active = cursorOverlayManager.snapshot.value.activeCursor
        if (engineState.value.isActive) {
            viewModelScope.launch(Dispatchers.IO) {
                if (mode == CursorRenderMode.NATIVE && active != null) {
                    engineClient.hideNativePointer(false)
                    val ok = engineClient.sendCustomPointerIcon(active.bitmap, active.hotspotX, active.hotspotY)
                    cursorOverlayManager.setNativeHookApplied(ok)
                } else if (mode == CursorRenderMode.SIMULATED && active != null) {
                    engineClient.hideNativePointer(true)
                } else {
                    engineClient.hideNativePointer(false)
                }
            }
        }
    }

    fun setCursorScale(scale: Float) {
        cursorOverlayManager.setCursorScale(scale)
    }

    fun refreshOverlayPermission() {
        cursorOverlayManager.refreshOverlayPermission()
    }

    fun grantOverlayViaAdb() {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                engineClient.grantOverlayPermissionViaAdb()
            }
            cursorOverlayManager.refreshOverlayPermission()
            _statusToast.value = if (ok || cursorOverlayManager.snapshot.value.overlayPermissionGranted) {
                "Permissão de cursor global por cima de tudo ativada!"
            } else {
                "Ative a permissão 'Sobrepor a outros apps' nas configurações do sistema."
            }
        }
    }

    fun updateLocalCursorPreview(x: Float, y: Float, visible: Boolean = true, fromTablet: Boolean = false) {
        cursorOverlayManager.updateCursorPosition(x, y, visible, fromTablet)
    }

    fun toggleSimulatedAppClosed(packageName: String, label: String) {
        hubManager.toggleSimulatedAppClosed(packageName, label)
    }

    fun dismissCoopBanner() {
        hubManager.dismissCoopBanner()
    }

    fun clearToast() {
        _statusToast.value = null
    }

    private fun resolveFileName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                return cursor.getString(idx)
            }
        }
        return uri.lastPathSegment ?: "cursor.png"
    }

    private fun applyPipelineConfig(cfg: TabletConfig, rotDeg: Int) {
        val profile = profileRepo.getProfileById(cfg.profileId)
        val parser = profileRepo.createParserFor(profile)
        val disp = displayManager.getDisplayOrDefault(cfg.targetDisplayId)
        val effectiveRot = if (disp.rotationDeg != 0 || rotDeg == 0) disp.rotationDeg else rotDeg
        val effectiveDisp = disp.copy(rotationDeg = effectiveRot)
        usbCoordinator.updateMouseDisplayBounds(
            widthPx = effectiveDisp.widthPx.toFloat(),
            heightPx = effectiveDisp.heightPx.toFloat()
        )
        if (cfg.slotId != TabletSlotId.MESA_2) {
            cursorOverlayManager.syncTabletMatrixConfig(cfg, profile, effectiveDisp)
        }
        val pipeline = if (cfg.slotId == TabletSlotId.MESA_2) pipeline2 else pipeline1
        pipeline.updateConfiguration(
            newConfig = cfg,
            profile = profile,
            parser = parser,
            displayWidth = effectiveDisp.widthPx.toFloat(),
            displayHeight = effectiveDisp.heightPx.toFloat(),
            displayRotationDeg = effectiveRot,
            displayXdpi = effectiveDisp.xdpi,
            displayYdpi = effectiveDisp.ydpi
        )
    }

    override fun onCleared() {
        app.onDisplayMetricsChanged = null
        super.onCleared()
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return KodaViewModel(application) as T
                }
            }
    }
}
