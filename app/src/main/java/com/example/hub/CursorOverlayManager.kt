package com.example.hub

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.example.display.TargetDisplayInfo
import com.example.driver.CoordinateMatrix
import com.example.driver.TabletConfig
import com.example.driver.TabletProfileJson
import com.example.koda.engine.GlobalPointerSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

enum class CursorRenderMode(val label: String, val subtitle: String) {
    NATIVE(
        label = "Nativo (Sistema)",
        subtitle = "Substitui PointerIcon do sistema + apoio automático quando a mesa ou app oculta a seta"
    ),
    SIMULATED(
        label = "Simulado (Overlay Global)",
        subtitle = "Oculta a seta nativa do Android e desenha .cur/.ani/.png por cima do Launcher e de todos os apps"
    )
}

data class CursorOverlaySnapshot(
    val mode: CursorRenderMode = CursorRenderMode.SIMULATED,
    val activeCursor: DecodedCursor? = null,
    val channel1Cursor: DecodedCursor? = null,
    val channel2Cursor: DecodedCursor? = null,
    val activeChannel: Int = 1,
    val cursorX: Float = 540f,
    val cursorY: Float = 960f,
    val cursor2X: Float = 540f,
    val cursor2Y: Float = 960f,
    val isVisible: Boolean = false,
    val isChannel2Visible: Boolean = false,
    val isFromTablet: Boolean = false,
    val isPaused: Boolean = false,
    val cursorScale: Float = 1.0f,
    val currentFrameIndex: Int = 0,
    val currentChannel2FrameIndex: Int = 0,
    val overlayPermissionGranted: Boolean = false,
    val accessibilityServiceEnabled: Boolean = false,
    val nativeCursorApplied: Boolean = false,
    val nativePointerHidden: Boolean = false
)

/**
 * Unified Global Windows-style Cursor Overlay Controller.
 *
 * Supports 2 unified cursor channels:
 * - Canal 1: Shared identically across Mouse 1 + Mesa 1
 * - Canal 2: Shared identically across Mouse 2 + Mesa 2
 *
 * Tracks both USB Mouse relative deltas and Graphics Tablet magnetic-field hover + pen pressure X/Y
 * continuously across Koda Wintab, Android Launcher, and all third-party apps.
 */
class CursorOverlayManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_CURSORS, Context.MODE_PRIVATE)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val systemWindowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    @Volatile private var accessibilityWindowManager: WindowManager? = null
    @Volatile private var activeWindowManagerType: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private val builtInDefaultCursor: DecodedCursor = CursorDecoder.createDefaultKodaCursor()

    private val _snapshot = MutableStateFlow(
        CursorOverlaySnapshot(
            mode = runCatching {
                CursorRenderMode.valueOf(
                    prefs.getString(KEY_RENDER_MODE, CursorRenderMode.SIMULATED.name)
                        ?: CursorRenderMode.SIMULATED.name
                )
            }.getOrDefault(CursorRenderMode.SIMULATED),
            activeCursor = builtInDefaultCursor,
            channel1Cursor = builtInDefaultCursor,
            cursorScale = prefs.getFloat(KEY_CURSOR_SCALE, 1.0f).coerceIn(0.5f, 2.5f),
            overlayPermissionGranted = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false),
            accessibilityServiceEnabled = KodaAccessibilityService.isEnabledInSettings(context)
        )
    )
    val snapshot: StateFlow<CursorOverlaySnapshot> = _snapshot.asStateFlow()

    @Volatile var onNativePointerHideChanged: ((shouldHide: Boolean) -> Unit)? = null

    @Volatile private var overlayView1: CursorSpriteOverlayView? = null
    @Volatile private var overlayParams1: WindowManager.LayoutParams? = null
    @Volatile private var attached1 = false

    @Volatile private var overlayView2: CursorSpriteOverlayView? = null
    @Volatile private var overlayParams2: WindowManager.LayoutParams? = null
    @Volatile private var attached2 = false

    private val syncedTabletMatrix = CoordinateMatrix()
    private val scratchMatrixXY = FloatArray(2)
    @Volatile private var hasSyncedMatrix = false

    private val syncScheduled = AtomicBoolean(false)
    private var aniTickRunnable: Runnable? = null
    private var lastGlobalSeq = 0L
    private var lastHideDispatched: Boolean? = null
    @Volatile private var lastOverlayPermCheckMs = 0L
    @Volatile private var lastOverlaySyncMs = 0L
    @Volatile private var ownAppForeground = false
    @Volatile private var calibrationSuppressed = false

    private val syncRunnable = Runnable {
        syncScheduled.set(false)
        applySystemOverlaysOnMain()
    }

    fun setOwnAppForeground(inForeground: Boolean) {
        if (ownAppForeground == inForeground) return
        ownAppForeground = inForeground
        forceImmediateOverlaySync()
    }

    fun setCalibrationSuppressed(suppressed: Boolean) {
        if (calibrationSuppressed == suppressed) return
        calibrationSuppressed = suppressed
        forceImmediateOverlaySync()
    }

    fun isCalibrationSuppressed(): Boolean = calibrationSuppressed

    fun attachAccessibilityWindowManager(wm: WindowManager?) {
        val changed = (accessibilityWindowManager == null) != (wm == null)
        accessibilityWindowManager = wm
        if (changed) {
            mainHandler.post {
                detachAllOverlaysSync()
                refreshOverlayPermission()
            }
        }
    }

    fun syncTabletMatrixConfig(
        config: TabletConfig,
        profile: TabletProfileJson,
        display: TargetDisplayInfo
    ) {
        val effRot = display.rotationDeg
        syncedTabletMatrix.rebuild(
            tabletMaxX = profile.maxX,
            tabletMaxY = profile.maxY,
            activeArea = config.activeArea,
            screenArea = config.screenArea,
            displayWidthPx = display.widthPx.toFloat(),
            displayHeightPx = display.heightPx.toFloat(),
            manualRotationDeg = config.manualRotationDeg,
            autoRotation = config.autoRotation,
            displayRotationDeg = effRot,
            invertX = config.invertX,
            invertY = config.invertY,
            rotateAxesWithScreen = config.rotateTabletAxesWithScreen,
            lockAspectRatio = config.lockAspectRatio,
            dpiMode = config.dpiMode,
            targetDpiX = if (config.dpiMode == com.example.driver.DpiNormalizationMode.MANUAL) config.manualDpiX.toFloat() else display.xdpi,
            targetDpiY = if (config.dpiMode == com.example.driver.DpiNormalizationMode.MANUAL) config.manualDpiY.toFloat() else display.ydpi,
            tabletWidthMm = profile.widthMm,
            tabletHeightMm = profile.heightMm
        )
        hasSyncedMatrix = true
        setTabletRotationConfig(
            autoRotateWithScreen = config.autoRotation && config.rotateTabletAxesWithScreen,
            manualRotationDeg = config.manualRotationDeg
        )
        updateDisplayBounds(
            widthPx = display.widthPx.toFloat(),
            heightPx = display.heightPx.toFloat(),
            rotationDeg = effRot
        )
    }

    fun refreshOverlayPermission() {
        val appOverlay = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
        val a11yRunning = KodaAccessibilityService.isServiceRunning.value ||
            KodaAccessibilityService.isEnabledInSettings(context)
        val granted = appOverlay || accessibilityWindowManager != null
        lastOverlayPermCheckMs = SystemClock.uptimeMillis()
        _snapshot.update { cur ->
            if (cur.overlayPermissionGranted != granted || cur.accessibilityServiceEnabled != a11yRunning) {
                cur.copy(
                    overlayPermissionGranted = granted,
                    accessibilityServiceEnabled = a11yRunning
                )
            } else {
                cur
            }
        }
        forceImmediateOverlaySync()
    }

    fun setRenderMode(mode: CursorRenderMode) {
        prefs.edit().putString(KEY_RENDER_MODE, mode.name).apply()
        _snapshot.update { it.copy(mode = mode) }
        forceImmediateOverlaySync()
    }

    fun setCursorScale(scale: Float) {
        val safe = scale.coerceIn(0.5f, 2.5f)
        prefs.edit().putFloat(KEY_CURSOR_SCALE, safe).apply()
        _snapshot.update { it.copy(cursorScale = safe) }
        forceImmediateOverlaySync()
    }

    fun setPaused(paused: Boolean) {
        _snapshot.update { it.copy(isPaused = paused) }
        if (!paused) {
            restartAniTickerIfNeeded()
        }
        forceImmediateOverlaySync()
    }

    fun setNativeHookApplied(applied: Boolean) {
        _snapshot.update { it.copy(nativeCursorApplied = applied) }
        forceImmediateOverlaySync()
    }

    /**
     * Backwards-compatible setter updating Canal 1 by default.
     */
    fun setActiveCursor(cursor: DecodedCursor?) {
        setChannelCursor(channel = 1, cursor = cursor)
    }

    fun setChannelCursor(channel: Int, cursor: DecodedCursor?) {
        _snapshot.update { cur ->
            if (channel == 2) {
                val resolvedActive = if (cur.activeChannel == 2) (cursor ?: cur.channel1Cursor ?: builtInDefaultCursor) else (cur.channel1Cursor ?: cursor ?: builtInDefaultCursor)
                cur.copy(
                    channel2Cursor = cursor,
                    activeCursor = resolvedActive,
                    currentChannel2FrameIndex = 0,
                    isChannel2Visible = cursor != null && cur.isChannel2Visible,
                    isVisible = true
                )
            } else {
                val effectiveCh1 = cursor ?: builtInDefaultCursor
                cur.copy(
                    channel1Cursor = effectiveCh1,
                    activeCursor = effectiveCh1,
                    currentFrameIndex = 0,
                    isVisible = true
                )
            }
        }
        restartAniTickerIfNeeded()
        forceImmediateOverlaySync()
    }

    fun persistCursorForChannel(channel: Int, cursor: DecodedCursor?, rawBytes: ByteArray?) {
        val dir = File(context.filesDir, "cursors").apply { mkdirs() }
        val file = File(dir, "channel_$channel.bin")
        val prefix = "ch$channel"
        if (cursor == null) {
            runCatching { file.delete() }
            prefs.edit()
                .remove("${prefix}_name")
                .remove("${prefix}_hx")
                .remove("${prefix}_hy")
                .apply()
            return
        }
        if (rawBytes != null) {
            runCatching { file.writeBytes(rawBytes) }
        }
        prefs.edit()
            .putString("${prefix}_name", cursor.fileName)
            .putFloat("${prefix}_hx", cursor.hotspotX)
            .putFloat("${prefix}_hy", cursor.hotspotY)
            .apply()
    }

    fun updatePersistedHotspot(channel: Int, hx: Float, hy: Float) {
        val prefix = "ch$channel"
        prefs.edit()
            .putFloat("${prefix}_hx", hx)
            .putFloat("${prefix}_hy", hy)
            .apply()
    }

    fun restorePersistedCursors(): Pair<DecodedCursor?, DecodedCursor?> {
        val ch1 = loadPersistedChannelCursor(1)
        val ch2 = loadPersistedChannelCursor(2)
        if (ch1 != null) setChannelCursor(1, ch1)
        if (ch2 != null) setChannelCursor(2, ch2)
        return ch1 to ch2
    }

    private fun loadPersistedChannelCursor(channel: Int): DecodedCursor? {
        val file = File(File(context.filesDir, "cursors"), "channel_$channel.bin")
        if (!file.exists()) return null
        val prefix = "ch$channel"
        val name = prefs.getString("${prefix}_name", "cursor_$channel.cur") ?: return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val decoded = CursorDecoder.decodeBytes(bytes, name) ?: return null
        val savedHx = prefs.getFloat("${prefix}_hx", -1f)
        val savedHy = prefs.getFloat("${prefix}_hy", -1f)
        return if (savedHx >= 0f && savedHy >= 0f) {
            decoded.withCustomHotspot(savedHx, savedHy)
        } else {
            decoded
        }
    }

    fun updateCursorPosition(
        x: Float,
        y: Float,
        visible: Boolean = true,
        fromTablet: Boolean = false,
        channel: Int = 1,
        directSource: Boolean = true
    ) {
        val now = SystemClock.uptimeMillis()
        if (directSource) {
            lastDirectPointerUpdateMs = now
            if (fromTablet) {
                lastTabletPipelineUpdateMs = now
            }
        }
        var changed = false
        _snapshot.update { cur ->
            val nextVisible = visible || (if (channel == 2) cur.channel2Cursor != null else cur.channel1Cursor != null)
            if (channel == 2) {
                if (cur.activeChannel == 2 &&
                    kotlin.math.abs(cur.cursor2X - x) < 0.25f &&
                    kotlin.math.abs(cur.cursor2Y - y) < 0.25f &&
                    cur.isChannel2Visible == nextVisible &&
                    cur.isFromTablet == fromTablet
                ) {
                    cur
                } else {
                    changed = true
                    val nextActive = cur.channel2Cursor ?: cur.channel1Cursor ?: cur.activeCursor
                    cur.copy(
                        activeChannel = 2,
                        activeCursor = nextActive,
                        cursor2X = x,
                        cursor2Y = y,
                        isChannel2Visible = nextVisible,
                        isFromTablet = fromTablet
                    )
                }
            } else {
                if (cur.activeChannel == 1 &&
                    kotlin.math.abs(cur.cursorX - x) < 0.25f &&
                    kotlin.math.abs(cur.cursorY - y) < 0.25f &&
                    cur.isVisible == nextVisible &&
                    cur.isFromTablet == fromTablet
                ) {
                    cur
                } else {
                    changed = true
                    val nextActive = cur.channel1Cursor ?: cur.channel2Cursor ?: cur.activeCursor
                    cur.copy(
                        activeChannel = 1,
                        activeCursor = nextActive,
                        cursorX = x,
                        cursorY = y,
                        isVisible = nextVisible,
                        isFromTablet = fromTablet
                    )
                }
            }
        }
        if (changed) {
            scheduleOverlaySync()
        }
    }

    @Volatile private var displayWidthPx = 1080f
    @Volatile private var displayHeightPx = 1920f
    @Volatile private var displayRotationDeg = 0
    @Volatile private var tabletAutoRotateWithScreen = false
    @Volatile private var tabletManualRotationDeg = 0
    @Volatile private var lastDirectPointerUpdateMs = -1L
    @Volatile private var lastTabletPipelineUpdateMs = -1L

    fun setTabletRotationConfig(autoRotateWithScreen: Boolean, manualRotationDeg: Int) {
        tabletAutoRotateWithScreen = autoRotateWithScreen
        tabletManualRotationDeg = ((manualRotationDeg % 360) + 360) % 360
    }

    fun updateDisplayBounds(widthPx: Float, heightPx: Float, rotationDeg: Int) {
        displayWidthPx = widthPx.coerceAtLeast(100f)
        displayHeightPx = heightPx.coerceAtLeast(100f)
        displayRotationDeg = ((rotationDeg % 360) + 360) % 360
        var changed = false
        _snapshot.update { cur ->
            val clampedX = cur.cursorX.coerceIn(0f, displayWidthPx)
            val clampedY = cur.cursorY.coerceIn(0f, displayHeightPx)
            val clamped2X = cur.cursor2X.coerceIn(0f, displayWidthPx)
            val clamped2Y = cur.cursor2Y.coerceIn(0f, displayHeightPx)
            if (clampedX != cur.cursorX || clampedY != cur.cursorY ||
                clamped2X != cur.cursor2X || clamped2Y != cur.cursor2Y
            ) {
                changed = true
                cur.copy(
                    cursorX = clampedX,
                    cursorY = clampedY,
                    cursor2X = clamped2X,
                    cursor2Y = clamped2Y
                )
            } else {
                cur
            }
        }
        if (changed) {
            scheduleOverlaySync()
        }
    }

    /**
     * Applies live system-wide kernel /dev/input events (mouse REL deltas or stylus EMR ABS hover/press)
     * received from KodaShellServer when outside Koda Wintab (Launcher, drawing apps, etc.).
     */
    fun applyGlobalPointerSample(
        sample: GlobalPointerSample,
        displayWidthPx: Float = this.displayWidthPx,
        displayHeightPx: Float = this.displayHeightPx,
        displayRotationDeg: Int = this.displayRotationDeg
    ) {
        if (sample.seq == lastGlobalSeq || !sample.isVisible) return
        lastGlobalSeq = sample.seq

        val now = SystemClock.uptimeMillis()
        // Never fight active USB TabletPipeline coordinates
        if (sample.isStylus && lastTabletPipelineUpdateMs >= 0L && now - lastTabletPipelineUpdateMs < 250L) return
        // Never add raw getevent REL_X/REL_Y deltas on top of live native MotionEvent / claimed USB mouse coordinates
        if (!sample.hasAbs && lastDirectPointerUpdateMs >= 0L && now - lastDirectPointerUpdateMs < 140L) return

        val cur = _snapshot.value
        val w = displayWidthPx.coerceAtLeast(100f)
        val h = displayHeightPx.coerceAtLeast(100f)
        val nextX: Float
        val nextY: Float
        if (sample.hasAbs) {
            if (hasSyncedMatrix) {
                syncedTabletMatrix.transformNormalized(sample.absXNorm, sample.absYNorm, scratchMatrixXY)
                nextX = scratchMatrixXY[0].coerceIn(0f, w)
                nextY = scratchMatrixXY[1].coerceIn(0f, h)
            } else {
                val effRot = if (tabletAutoRotateWithScreen || !sample.isStylus) {
                    (((displayRotationDeg - tabletManualRotationDeg) % 360) + 360) % 360
                } else {
                    ((360 - tabletManualRotationDeg) % 360 + 360) % 360
                }
                val rotNormX: Float
                val rotNormY: Float
                when (effRot) {
                    90 -> {
                        rotNormX = sample.absYNorm
                        rotNormY = 1f - sample.absXNorm
                    }
                    180 -> {
                        rotNormX = 1f - sample.absXNorm
                        rotNormY = 1f - sample.absYNorm
                    }
                    270 -> {
                        rotNormX = 1f - sample.absYNorm
                        rotNormY = sample.absXNorm
                    }
                    else -> {
                        rotNormX = sample.absXNorm
                        rotNormY = sample.absYNorm
                    }
                }
                nextX = (rotNormX * w).coerceIn(0f, w)
                nextY = (rotNormY * h).coerceIn(0f, h)
            }
        } else {
            nextX = (cur.cursorX + sample.deltaX).coerceIn(0f, w)
            nextY = (cur.cursorY + sample.deltaY).coerceIn(0f, h)
        }
        updateCursorPosition(
            x = nextX,
            y = nextY,
            visible = true,
            fromTablet = sample.isStylus,
            channel = 1,
            directSource = false
        )
    }

    private fun scheduleOverlaySync() {
        if (syncScheduled.compareAndSet(false, true)) {
            val elapsed = SystemClock.uptimeMillis() - lastOverlaySyncMs
            if (elapsed >= 11L) {
                mainHandler.post(syncRunnable)
            } else {
                mainHandler.postDelayed(syncRunnable, (11L - elapsed).coerceAtLeast(1L))
            }
        }
    }

    private fun forceImmediateOverlaySync() {
        mainHandler.removeCallbacks(syncRunnable)
        syncScheduled.set(true)
        lastOverlaySyncMs = 0L
        mainHandler.post(syncRunnable)
    }

    private fun restartAniTickerIfNeeded() {
        aniTickRunnable?.let { mainHandler.removeCallbacks(it) }
        aniTickRunnable = null
        val snap = _snapshot.value
        val ch1 = snap.channel1Cursor
        val ch2 = snap.channel2Cursor
        val needsTick = (ch1 != null && ch1.frames.size > 1) || (ch2 != null && ch2.frames.size > 1)
        if (!needsTick) return

        val period = minOf(
            ch1?.takeIf { it.frames.size > 1 }?.frameDurationMs ?: 120L,
            ch2?.takeIf { it.frames.size > 1 }?.frameDurationMs ?: 120L
        ).coerceIn(30L, 1000L)

        val runnable = object : Runnable {
            override fun run() {
                val cur = _snapshot.value
                val count1 = cur.channel1Cursor?.frames?.size ?: 1
                val count2 = cur.channel2Cursor?.frames?.size ?: 1
                if (!cur.isPaused && (count1 > 1 || count2 > 1)) {
                    _snapshot.update { s ->
                        val c1 = s.channel1Cursor?.frames?.size ?: 1
                        val c2 = s.channel2Cursor?.frames?.size ?: 1
                        s.copy(
                            currentFrameIndex = if (c1 > 1) (s.currentFrameIndex + 1) % c1 else 0,
                            currentChannel2FrameIndex = if (c2 > 1) (s.currentChannel2FrameIndex + 1) % c2 else 0
                        )
                    }
                    overlayView1?.invalidate()
                    overlayView2?.invalidate()
                    applySystemOverlaysOnMain()
                    mainHandler.postDelayed(this, period)
                }
            }
        }
        aniTickRunnable = runnable
        mainHandler.postDelayed(runnable, period)
    }

    private fun applySystemOverlaysOnMain() {
        var snap = _snapshot.value
        val nowMs = SystemClock.uptimeMillis()
        lastOverlaySyncMs = nowMs
        val a11yWm = accessibilityWindowManager
        val canDraw = if (lastOverlayPermCheckMs == 0L || nowMs - lastOverlayPermCheckMs >= 2500L) {
            lastOverlayPermCheckMs = nowMs
            val appPerm = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
            val fresh = appPerm || a11yWm != null
            val a11yEnabled = a11yWm != null || KodaAccessibilityService.isEnabledInSettings(context)
            if (fresh != snap.overlayPermissionGranted || a11yEnabled != snap.accessibilityServiceEnabled) {
                _snapshot.update { it.copy(overlayPermissionGranted = fresh, accessibilityServiceEnabled = a11yEnabled) }
                snap = _snapshot.value
            }
            fresh
        } else {
            snap.overlayPermissionGranted || a11yWm != null
        }

        val ch1Cursor = snap.channel1Cursor ?: snap.activeCursor
        val ch2Cursor = snap.channel2Cursor
        val suppressBuiltinCh1InApp = ownAppForeground && ch1Cursor?.sourceFormat == "KODA"
        val suppressBuiltinCh2InApp = ownAppForeground && ch2Cursor?.sourceFormat == "KODA"

        val showWin1 = canDraw &&
            !calibrationSuppressed &&
            !suppressBuiltinCh1InApp &&
            ch1Cursor != null &&
            !snap.isPaused &&
            snap.isVisible &&
            (snap.mode == CursorRenderMode.SIMULATED || snap.isFromTablet || !snap.nativeCursorApplied)

        val showWin2 = canDraw &&
            !calibrationSuppressed &&
            !suppressBuiltinCh2InApp &&
            ch2Cursor != null &&
            !snap.isPaused &&
            snap.isChannel2Visible &&
            (snap.mode == CursorRenderMode.SIMULATED || snap.isFromTablet || !snap.nativeCursorApplied)

        // Hide native OS Android cursor globally whenever our custom cursor overlay is active or during calibration
        val shouldHideNative = !snap.isPaused &&
            (calibrationSuppressed ||
                ((ch1Cursor != null || ch2Cursor != null) &&
                    (snap.mode == CursorRenderMode.SIMULATED || showWin1 || showWin2)))
        if (lastHideDispatched != shouldHideNative) {
            lastHideDispatched = shouldHideNative
            _snapshot.update { it.copy(nativePointerHidden = shouldHideNative) }
            onNativePointerHideChanged?.invoke(shouldHideNative)
        }

        val targetWm = a11yWm ?: systemWindowManager ?: return
        val targetWinType = if (a11yWm != null) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        }
        if (targetWinType != activeWindowManagerType) {
            detachAllOverlaysSync()
            activeWindowManagerType = targetWinType
        }

        if (!showWin1) {
            if (attached1 && overlayView1 != null) {
                runCatching { targetWm.removeViewImmediate(overlayView1) }
                attached1 = false
            }
        } else {
            val bmp1 = ch1Cursor.frames.getOrNull(snap.currentFrameIndex) ?: ch1Cursor.bitmap
            renderOrUpdateOverlayWindow(
                wm = targetWm,
                windowType = targetWinType,
                cursor = ch1Cursor,
                bmp = bmp1,
                scale = snap.cursorScale,
                x = snap.cursorX,
                y = snap.cursorY,
                existingView = overlayView1,
                existingParams = overlayParams1,
                isAttached = attached1,
                windowTitle = "KodaCursorOverlay1",
                onCreated = { v, p ->
                    overlayView1 = v
                    overlayParams1 = p
                    attached1 = true
                }
            )
        }

        if (!showWin2) {
            if (attached2 && overlayView2 != null) {
                runCatching { targetWm.removeViewImmediate(overlayView2) }
                attached2 = false
            }
        } else {
            val bmp2 = ch2Cursor.frames.getOrNull(snap.currentChannel2FrameIndex) ?: ch2Cursor.bitmap
            renderOrUpdateOverlayWindow(
                wm = targetWm,
                windowType = targetWinType,
                cursor = ch2Cursor,
                bmp = bmp2,
                scale = snap.cursorScale,
                x = snap.cursor2X,
                y = snap.cursor2Y,
                existingView = overlayView2,
                existingParams = overlayParams2,
                isAttached = attached2,
                windowTitle = "KodaCursorOverlay2",
                onCreated = { v, p ->
                    overlayView2 = v
                    overlayParams2 = p
                    attached2 = true
                }
            )
        }
    }

    private fun renderOrUpdateOverlayWindow(
        wm: WindowManager,
        windowType: Int,
        cursor: DecodedCursor,
        bmp: android.graphics.Bitmap,
        scale: Float,
        x: Float,
        y: Float,
        existingView: CursorSpriteOverlayView?,
        existingParams: WindowManager.LayoutParams?,
        isAttached: Boolean,
        windowTitle: String,
        onCreated: (CursorSpriteOverlayView, WindowManager.LayoutParams) -> Unit
    ) {
        val drawW = (bmp.width * scale).roundToInt().coerceAtLeast(16)
        val drawH = (bmp.height * scale).roundToInt().coerceAtLeast(16)
        val winX = (x - cursor.hotspotX * scale).roundToInt()
        val winY = (y - cursor.hotspotY * scale).roundToInt()

        if (!isAttached) {
            val view = existingView ?: CursorSpriteOverlayView(context)
            view.updateSprite(bmp, drawW, drawH)
            val params = WindowManager.LayoutParams(
                drawW,
                drawH,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                alpha = if (windowType == WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) 0.78f else 1.0f
                this.x = winX
                this.y = winY
                title = windowTitle
            }
            runCatching {
                wm.addView(view, params)
                onCreated(view, params)
            }
        } else {
            val view = existingView ?: return
            val params = existingParams ?: return
            val spriteChanged = view.updateSprite(bmp, drawW, drawH)
            if (params.x != winX || params.y != winY || params.width != drawW || params.height != drawH) {
                params.x = winX
                params.y = winY
                params.width = drawW
                params.height = drawH
                runCatching { wm.updateViewLayout(view, params) }
                if (spriteChanged) {
                    view.invalidate()
                }
            } else if (spriteChanged) {
                view.invalidate()
            }
        }
    }

    private fun detachAllOverlaysSync() {
        val wm = accessibilityWindowManager ?: systemWindowManager
        if (attached1 && overlayView1 != null) {
            runCatching { wm?.removeViewImmediate(overlayView1) }
            runCatching { systemWindowManager?.removeViewImmediate(overlayView1) }
        }
        if (attached2 && overlayView2 != null) {
            runCatching { wm?.removeViewImmediate(overlayView2) }
            runCatching { systemWindowManager?.removeViewImmediate(overlayView2) }
        }
        attached1 = false
        attached2 = false
    }

    fun destroy() {
        aniTickRunnable?.let { mainHandler.removeCallbacks(it) }
        aniTickRunnable = null
        mainHandler.removeCallbacks(syncRunnable)
        syncScheduled.set(false)
        if (lastHideDispatched == true) {
            lastHideDispatched = false
            onNativePointerHideChanged?.invoke(false)
        }
        mainHandler.post {
            detachAllOverlaysSync()
            overlayView1 = null
            overlayParams1 = null
            overlayView2 = null
            overlayParams2 = null
        }
    }

    private class CursorSpriteOverlayView(context: Context) : View(context) {
        private var currentBitmap: android.graphics.Bitmap? = null
        private var targetW: Int = 64
        private var targetH: Int = 64
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val dstRect = Rect()

        fun updateSprite(bitmap: android.graphics.Bitmap, width: Int, height: Int): Boolean {
            val changed = currentBitmap !== bitmap || targetW != width || targetH != height
            if (changed) {
                currentBitmap = bitmap
                targetW = width
                targetH = height
            }
            return changed
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bmp = currentBitmap ?: return
            dstRect.set(0, 0, targetW, targetH)
            canvas.drawBitmap(bmp, null, dstRect, paint)
        }
    }

    companion object {
        private const val PREFS_CURSORS = "koda_cursor_prefs"
        private const val KEY_RENDER_MODE = "render_mode"
        private const val KEY_CURSOR_SCALE = "cursor_scale"
    }
}
