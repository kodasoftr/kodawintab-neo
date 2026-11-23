package com.example.pendisplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.MotionEvent
import android.view.Surface
import com.example.display.DisplayTargetManager
import com.example.driver.TabletPipeline
import com.example.driver.TabletProfileJson
import com.example.driver.TabletProfileRepository
import com.example.hub.InstalledAppEntry
import com.example.koda.engine.KodaEngineClient
import com.example.koda.engine.StylusSample
import com.example.usb.UsbTabletCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PenDisplayPreset(
    val id: String,
    val label: String,
    val widthPx: Int,
    val heightPx: Int,
    val dpi: Int
)

val DEFAULT_PEN_DISPLAY_PRESETS = listOf(
    PenDisplayPreset("fhd_165", "1920×1080 · 165 DPI (FHD 13\")", 1920, 1080, 165),
    PenDisplayPreset("fhd_140", "1920×1080 · 140 DPI (FHD 16\")", 1920, 1080, 140),
    PenDisplayPreset("qhd_213", "2560×1440 · 213 DPI (2K Pro)", 2560, 1440, 213),
    PenDisplayPreset("hd_120", "1280×720 · 120 DPI (Modo Leve)", 1280, 720, 120)
)

data class PenDisplayState(
    val isPenDisplayConnected: Boolean = false,
    val isVirtualDisplayActive: Boolean = false,
    val isKodasoftrProjected: Boolean = false,
    val autoDetectedHardwareName: String? = null,
    val autoDetectedVidPid: String? = null,
    val selectedPreset: PenDisplayPreset = DEFAULT_PEN_DISPLAY_PRESETS.first(),
    val virtualDisplayId: Int = -1,
    val lastStylusNormX: Float = 0.5f,
    val lastStylusNormY: Float = 0.5f,
    val lastStylusPressure: Float = 0f,
    val lastStylusDown: Boolean = false,
    val statusMessage: String = "Pronto · Conecte uma Pen Display USB ou ative o Display Virtual Kodasoftr"
)

/**
 * Coordinates Pen Display hardware auto-detection, isolated VirtualDisplay creation,
 * Kodasoftr Desktop Presentation, KodaStreamEngine (MediaCodec + USB bulkTransfer),
 * KodaAppRouter (displayId routing), and direct WinTab stylus injection onto the virtual display.
 */
class PenDisplayEngineManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val usbCoordinator: UsbTabletCoordinator,
    private val displayManager: DisplayTargetManager,
    private val profileRepo: TabletProfileRepository,
    private val pipelineMesa1: TabletPipeline,
    private val engineClient: KodaEngineClient,
    private val installedAppsProvider: () -> List<InstalledAppEntry>
) {
    private val androidDisplayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    val streamEngine = KodaStreamEngine(usbCoordinator)
    val appRouter = KodaAppRouter(context, engineClient)

    private val _state = MutableStateFlow(PenDisplayState())
    val state: StateFlow<PenDisplayState> = _state.asStateFlow()

    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: KodasoftrDesktopPresentation? = null

    init {
        scope.launch {
            usbCoordinator.penDisplayConnected.collect { connected ->
                val profile = usbCoordinator.detectedPenDisplayProfile.value
                handleHardwareConnectionChanged(connected, profile)
            }
        }
    }

    private fun handleHardwareConnectionChanged(connected: Boolean, profile: TabletProfileJson?) {
        val current = _state.value
        val nextPreset = if (profile != null && profile.nativeWidthPx > 0 && profile.nativeHeightPx > 0) {
            PenDisplayPreset(
                id = "native_${profile.id}",
                label = "${profile.nativeWidthPx}×${profile.nativeHeightPx} · ${profile.nativeDpi} DPI (${profile.brand})",
                widthPx = profile.nativeWidthPx,
                heightPx = profile.nativeHeightPx,
                dpi = profile.nativeDpi.coerceIn(120, 480)
            )
        } else current.selectedPreset

        val vidPidStr = if (profile != null && profile.vendorId != 0) {
            val pid = profile.productIds.firstOrNull() ?: 0
            String.format("%04X:%04X", profile.vendorId, pid)
        } else null

        _state.value = current.copy(
            isPenDisplayConnected = connected,
            autoDetectedHardwareName = if (connected) (profile?.name ?: "Pen Display USB Detectada") else null,
            autoDetectedVidPid = if (connected) vidPidStr else null,
            selectedPreset = nextPreset,
            statusMessage = if (connected) {
                "Mesa com Tela Conectada: ${profile?.name ?: "USB Pen Display"}"
            } else if (current.isVirtualDisplayActive) {
                "Display Virtual Kodasoftr ativo (#${current.virtualDisplayId})"
            } else {
                "Pronto · Conecte uma Pen Display USB ou ative o Display Virtual Kodasoftr"
            }
        )

        if (connected && !current.isVirtualDisplayActive) {
            startPenDisplaySession()
        }
    }

    fun setPenDisplayConnectedManual(enabled: Boolean) {
        if (enabled) {
            _state.value = _state.value.copy(
                isPenDisplayConnected = true,
                autoDetectedHardwareName = _state.value.autoDetectedHardwareName ?: "Pen Display (Modo Ativo)"
            )
            startPenDisplaySession()
        } else {
            stopPenDisplaySession()
            _state.value = _state.value.copy(
                isPenDisplayConnected = false,
                statusMessage = "Modo Mesa com Tela desativado"
            )
        }
    }

    fun selectPreset(preset: PenDisplayPreset) {
        val wasActive = _state.value.isVirtualDisplayActive
        _state.value = _state.value.copy(selectedPreset = preset)
        if (wasActive) {
            startPenDisplaySession()
        }
    }

    fun startPenDisplaySession(): Boolean {
        stopPenDisplaySession()
        val preset = _state.value.selectedPreset
        val surface: Surface? = streamEngine.start(preset.widthPx, preset.heightPx)

        val vd = createVirtualDisplayCompat(
            name = "Kodasoftr_PenDisplay",
            width = preset.widthPx,
            height = preset.heightPx,
            dpi = preset.dpi,
            surface = surface
        )

        if (vd == null) {
            streamEngine.stop()
            _state.value = _state.value.copy(
                isVirtualDisplayActive = false,
                isKodasoftrProjected = false,
                virtualDisplayId = -1,
                statusMessage = "Não foi possível criar VirtualDisplay neste dispositivo."
            )
            return false
        }

        virtualDisplay = vd
        val disp = vd.display
        val dispId = disp?.displayId ?: -1

        var projected = false
        if (disp != null) {
            scope.launch(Dispatchers.Main) {
                runCatching {
                    val pres = KodasoftrDesktopPresentation(
                        outerContext = context,
                        display = disp,
                        resolutionLabel = "${preset.widthPx}×${preset.heightPx} · ${preset.dpi} DPI",
                        boundAppsProvider = {
                            val bound = appRouter.boundPackages.value
                            installedAppsProvider().filter { it.packageName in bound }
                        },
                        onLaunchApp = { app ->
                            appRouter.launchAppOnPenDisplay(app.packageName, app.label, dispId)
                        }
                    )
                    pres.show()
                    presentation = pres
                    projected = true
                    _state.value = _state.value.copy(isKodasoftrProjected = true)
                }
            }
        }

        displayManager.refreshDisplays()
        if (dispId > 0) {
            appRouter.syncWithEngine(dispId)
            pipelineMesa1.config = pipelineMesa1.config.copy(targetDisplayId = dispId)
        }

        _state.value = _state.value.copy(
            isPenDisplayConnected = true,
            isVirtualDisplayActive = true,
            isKodasoftrProjected = projected || disp != null,
            virtualDisplayId = dispId,
            statusMessage = "Kodasoftr Desktop ativo no Display Virtual #$dispId (${preset.widthPx}×${preset.heightPx} · ${preset.dpi} DPI)"
        )
        return true
    }

    private fun createVirtualDisplayCompat(
        name: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface?
    ): VirtualDisplay? {
        val mgr = androidDisplayManager ?: return null
        val publicFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
        val privateFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION

        return runCatching {
            mgr.createVirtualDisplay(name, width, height, dpi, surface, publicFlags)
        }.getOrNull() ?: runCatching {
            mgr.createVirtualDisplay(name, width, height, dpi, surface, privateFlags)
        }.getOrNull()
    }

    fun stopPenDisplaySession() {
        runCatching { presentation?.dismiss() }
        presentation = null
        runCatching { virtualDisplay?.release() }
        virtualDisplay = null
        streamEngine.stop()
        displayManager.refreshDisplays()
        if (pipelineMesa1.config.targetDisplayId > 0) {
            pipelineMesa1.config = pipelineMesa1.config.copy(targetDisplayId = 0)
        }
        _state.value = _state.value.copy(
            isVirtualDisplayActive = false,
            isKodasoftrProjected = false,
            virtualDisplayId = -1
        )
    }

    fun toggleBoundApp(packageName: String) {
        appRouter.togglePackageBinding(packageName, _state.value.virtualDisplayId)
        presentation?.refreshAppMenu()
    }

    fun launchAppOnPenDisplay(app: InstalledAppEntry): Boolean {
        val dispId = _state.value.virtualDisplayId
        if (dispId <= 0) {
            startPenDisplaySession()
        }
        val activeId = _state.value.virtualDisplayId
        if (activeId <= 0) return false
        return appRouter.launchAppOnPenDisplay(app.packageName, app.label, activeId)
    }

    fun dispatchStylusInputToPenDisplay(
        normX: Float,
        normY: Float,
        pressureNorm: Float,
        isTipDown: Boolean
    ) {
        val nx = normX.coerceIn(0f, 1f)
        val ny = normY.coerceIn(0f, 1f)
        val p = if (isTipDown) pressureNorm.coerceIn(0f, 1f) else 0f
        val st = _state.value
        _state.value = st.copy(
            lastStylusNormX = nx,
            lastStylusNormY = ny,
            lastStylusPressure = p,
            lastStylusDown = isTipDown
        )
        presentation?.updateStylusCursor(nx, ny, p, isTipDown)

        if (st.virtualDisplayId > 0 && engineClient.state.value.isActive) {
            val sx = nx * st.selectedPreset.widthPx
            val sy = ny * st.selectedPreset.heightPx
            val action = if (isTipDown) MotionEvent.ACTION_MOVE else MotionEvent.ACTION_HOVER_MOVE
            engineClient.sendStylusBatch(
                deviceId = 105,
                displayId = st.virtualDisplayId,
                action = action,
                isEraser = false,
                samples = listOf(
                    StylusSample(
                        eventTimeMs = android.os.SystemClock.uptimeMillis(),
                        x = sx,
                        y = sy,
                        pressure = p,
                        tiltX = 0f,
                        tiltY = 0f,
                        buttonState = 0
                    )
                )
            )
        }
    }

    fun recognizeUsbVidPidForDisplay(vendorId: Int, productId: Int, productName: String = ""): Boolean {
        val matched = profileRepo.findByVidPid(vendorId, productId, productName)
        if (matched != null && matched.isPenDisplay) {
            handleHardwareConnectionChanged(true, matched)
            return true
        }
        return false
    }
}
