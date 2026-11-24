package com.example.driver

import android.os.Process
import android.os.SystemClock
import android.view.MotionEvent
import com.example.koda.engine.KodaEngineClient
import com.example.koda.engine.StylusSample
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

/**
 * High-precision PC-grade Tablet Input Pipeline (Koda Wintab 2.0).
 *
 * Architecture:
 * [USB Reader Thread (URGENT_DISPLAY)] -> [ZeroAllocRingBuffer + LockSupport.unpark] -> [Pipeline Worker]:
 *   1. Zero-alloc HidReportParser into reusable TabletReport
 *   2. Zero-alloc DSP filters (One-Euro 1€, Anti-Jitter, Hysteresis, Tilt, EMA, Predictive lead)
 *   3. Pre-baked O(1) PressureCurveLut (100% native pressure levels preserved across DPI scaling)
 *   4. Pre-calculated 2x3 CoordinateMatrix (sub-pixel float, auto-rotation + DPI X/Y normalization)
 *   5. Real Pen Barrel & Express Key shortcut dispatcher + batched IPC injection
 */
class TabletPipeline(
    private val engineClient: KodaEngineClient
) {
    val ringBuffer = ZeroAllocRingBuffer(capacity = 256, packetSize = 64)
    val matrix = CoordinateMatrix()
    val pressureLut = PressureCurveLut()
    val filters = OptionalDspFilters()
    val latencyMeter = LatencyMeter()

    @Volatile var activeParser: HidReportParser = UgeeS640Parser()
    @Volatile var config: TabletConfig = TabletConfig()
    @Volatile private var cachedB1Action: ParsedBindingAction = KeyBindingCatalog.parseAction("Botão Direito / Apagar")
    @Volatile private var cachedB2Action: ParsedBindingAction = KeyBindingCatalog.parseAction("Borracha / Pan")
    @Volatile var virtualDeviceId: Int = TabletSlotId.GLOBAL.virtualDeviceId
    @Volatile var onCursorCoordinate: ((x: Float, y: Float, isVisible: Boolean, fromTablet: Boolean) -> Unit)? = null

    private val running = AtomicBoolean(false)
    @Volatile private var workerThread: Thread? = null

    private val _telemetry = MutableStateFlow(DriverTelemetry())
    val telemetry: StateFlow<DriverTelemetry> = _telemetry.asStateFlow()

    private val _hexFrames = MutableStateFlow<List<HidHexFrame>>(emptyList())
    val hexFrames: StateFlow<List<HidHexFrame>> = _hexFrames.asStateFlow()
    private val hexRing = ArrayDeque<HidHexFrame>(24)

    // Pre-allocated hot-path scratch buffers (zero GC)
    private val scratchPacket = ByteArray(64)
    private val scratchMeta = LongArray(1)
    private val scratchReport = TabletReport()
    private val scratchFilteredXY = FloatArray(2)
    private val scratchScreenXY = FloatArray(2)
    private val batchBuffer = ArrayList<StylusSample>(16)

    private var prevTipDown = false
    private var prevHovering = false
    private var prevBarrel1 = false
    private var prevBarrel2 = false
    private var prevExpressMask = 0
    @Volatile private var lastShortcutLabel = ""
    private var lastRelRawX = -1f
    private var lastRelRawY = -1f
    private var relCursorX = 540f
    private var relCursorY = 960f
    @Volatile private var displayWidthPx = 1080f
    @Volatile private var displayHeightPx = 1920f
    @Volatile private var currentDisplayRotationDeg = 0

    fun updateConfiguration(
        newConfig: TabletConfig,
        profile: TabletProfileJson,
        parser: HidReportParser,
        displayWidth: Float,
        displayHeight: Float,
        displayRotationDeg: Int,
        displayXdpi: Float = 420f,
        displayYdpi: Float = 420f
    ) {
        config = newConfig
        cachedB1Action = KeyBindingCatalog.parseAction(newConfig.bindings.button1Action)
        cachedB2Action = KeyBindingCatalog.parseAction(newConfig.bindings.button2Action)
        activeParser = parser
        displayWidthPx = displayWidth.coerceAtLeast(100f)
        displayHeightPx = displayHeight.coerceAtLeast(100f)
        currentDisplayRotationDeg = displayRotationDeg

        val effDpiX: Float
        val effDpiY: Float
        when (newConfig.dpiMode) {
            DpiNormalizationMode.AUTO -> {
                effDpiX = displayXdpi.coerceIn(72f, 1200f)
                effDpiY = displayYdpi.coerceIn(72f, 1200f)
            }
            DpiNormalizationMode.MANUAL -> {
                effDpiX = newConfig.manualDpiX.toFloat().coerceIn(72f, 1200f)
                effDpiY = newConfig.manualDpiY.toFloat().coerceIn(72f, 1200f)
            }
            DpiNormalizationMode.OFF -> {
                effDpiX = 420f
                effDpiY = 420f
            }
        }

        pressureLut.rebuild(
            points = newConfig.pressureCurve,
            minThreshold = newConfig.minPressureThreshold,
            maxCap = newConfig.maxPressureCap
        )
        matrix.rebuild(
            tabletMaxX = profile.maxX,
            tabletMaxY = profile.maxY,
            activeArea = newConfig.activeArea,
            screenArea = newConfig.screenArea,
            displayWidthPx = displayWidthPx,
            displayHeightPx = displayHeightPx,
            manualRotationDeg = newConfig.manualRotationDeg,
            autoRotation = newConfig.autoRotation,
            displayRotationDeg = displayRotationDeg,
            invertX = newConfig.invertX,
            invertY = newConfig.invertY,
            rotateAxesWithScreen = newConfig.rotateTabletAxesWithScreen,
            lockAspectRatio = newConfig.lockAspectRatio,
            dpiMode = newConfig.dpiMode,
            targetDpiX = effDpiX,
            targetDpiY = effDpiY,
            tabletWidthMm = profile.widthMm,
            tabletHeightMm = profile.heightMm
        )

        _telemetry.value = _telemetry.value.copy(
            activeProfileName = profile.name,
            brand = parser.brand,
            maxNativePressure = profile.maxPressure,
            effectiveRotationDeg = matrix.effectiveRotationDeg,
            effectiveDpiX = effDpiX,
            effectiveDpiY = effDpiY,
            activeWidthMm = matrix.activeWidthMm,
            activeHeightMm = matrix.activeHeightMm,
            pxPerMmX = matrix.effectivePxPerMmX
        )
    }

    fun wakeWorker() {
        workerThread?.let { LockSupport.unpark(it) }
    }

    fun start() {
        if (running.getAndSet(true)) return
        workerThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            var lastUiPublishMs = 0L
            while (running.get()) {
                var processed = 0
                batchBuffer.clear()
                var batchAction = MotionEvent.ACTION_HOVER_MOVE
                var batchEraser = false

                while (processed < 8) {
                    val len = ringBuffer.poll(scratchPacket, scratchMeta)
                    if (len < 0) break
                    processed++

                    val t0 = System.nanoTime()
                    val parsed = activeParser.parse(scratchPacket, len, scratchReport)
                    val t1 = System.nanoTime()
                    if (!parsed || !scratchReport.valid) continue

                    // Dispatch custom keyboard/shortcut bindings for Express Keys & Pen Barrel Buttons
                    dispatchButtonAndExpressBindings(scratchReport)
                    if (scratchReport.isExpressKeyFrame) {
                        // Express-key frame (both press and release 0xF0) does not contain pen coordinates
                        continue
                    }

                    // Ignore idle out-of-range reports (0xC0 / zeroed idle) once hover/tip have already exited
                    if (!scratchReport.isHovering && !scratchReport.isTipDown && !prevTipDown && !prevHovering) {
                        continue
                    }

                    // Optional zero-alloc DSP filters (bypassed cleanly if all disabled)
                    val fx: Float
                    val fy: Float
                    val f = config.filters
                    if (f.antiJitterEnabled ||
                        f.smoothingEnabled ||
                        f.oneEuroEnabled ||
                        f.tiltCompensationEnabled ||
                        f.hysteresisEnabled ||
                        f.predictionEnabled
                    ) {
                        filters.process(
                            scratchReport.rawX.toFloat(),
                            scratchReport.rawY.toFloat(),
                            scratchReport.tiltXDeg,
                            scratchReport.tiltYDeg,
                            f,
                            scratchFilteredXY,
                            scratchMeta[0].takeIf { it > 0L } ?: t1
                        )
                        fx = scratchFilteredXY[0]
                        fy = scratchFilteredXY[1]
                    } else {
                        fx = scratchReport.rawX.toFloat()
                        fy = scratchReport.rawY.toFloat()
                    }

                    // Coordinate matrix or Relative delta
                    if (config.outputMode == OutputMode.ABSOLUTE) {
                        matrix.transform(fx, fy, scratchScreenXY)
                    } else {
                        if (lastRelRawX < 0f) {
                            lastRelRawX = fx
                            lastRelRawY = fy
                        }
                        val dx = (fx - lastRelRawX) * 0.06f * config.relativeSensitivity
                        val dy = (fy - lastRelRawY) * 0.06f * config.relativeSensitivity
                        lastRelRawX = fx
                        lastRelRawY = fy
                        relCursorX = (relCursorX + dx).coerceIn(0f, displayWidthPx)
                        relCursorY = (relCursorY + dy).coerceIn(0f, displayHeightPx)
                        scratchScreenXY[0] = relCursorX
                        scratchScreenXY[1] = relCursorY
                    }

                    val curvedPressure = pressureLut.lookup(
                        scratchReport.rawPressure,
                        scratchReport.maxPressure
                    )
                    val effectivePressure = if (scratchReport.isTipDown) {
                        curvedPressure.coerceAtLeast(0.02f)
                    } else {
                        0f
                    }
                    val t2 = System.nanoTime()

                    val wasTipDown = prevTipDown
                    val wasHovering = prevHovering
                    val action = when {
                        scratchReport.isTipDown && !wasTipDown -> MotionEvent.ACTION_DOWN
                        scratchReport.isTipDown && wasTipDown -> MotionEvent.ACTION_MOVE
                        !scratchReport.isTipDown && wasTipDown -> MotionEvent.ACTION_UP
                        scratchReport.isHovering && !wasHovering -> MotionEvent.ACTION_HOVER_ENTER
                        !scratchReport.isHovering && wasHovering -> MotionEvent.ACTION_HOVER_EXIT
                        else -> MotionEvent.ACTION_HOVER_MOVE
                    }
                    prevTipDown = scratchReport.isTipDown
                    prevHovering = scratchReport.isHovering

                    val b1Action = cachedB1Action
                    val b2Action = cachedB2Action
                    val eraserActive = scratchReport.isEraser ||
                        (scratchReport.barrelButton1 && b1Action.isEraserToggle) ||
                        (scratchReport.barrelButton2 && b2Action.isEraserToggle)

                    var buttons = 0
                    if (scratchReport.barrelButton1) {
                        buttons = buttons or if (b1Action.isRightClick) MotionEvent.BUTTON_SECONDARY else MotionEvent.BUTTON_STYLUS_PRIMARY
                    }
                    if (scratchReport.barrelButton2) {
                        buttons = buttons or if (b2Action.isRightClick) MotionEvent.BUTTON_SECONDARY else if (b2Action.isMiddleClick) MotionEvent.BUTTON_TERTIARY else MotionEvent.BUTTON_STYLUS_SECONDARY
                    }

                    val t3Start = System.nanoTime()
                    // Update global/in-app .cur/.ani/.png cursor position for both magnetic field hover and pen pressure
                    if (scratchReport.isHovering || scratchReport.isTipDown) {
                        onCursorCoordinate?.invoke(
                            scratchScreenXY[0],
                            scratchScreenXY[1],
                            true,
                            true
                        )
                    }

                    if (engineClient.state.value.isActive) {
                        val isSingleTransition = action == MotionEvent.ACTION_DOWN ||
                            action == MotionEvent.ACTION_UP ||
                            action == MotionEvent.ACTION_HOVER_ENTER ||
                            action == MotionEvent.ACTION_HOVER_EXIT
                        if (batchBuffer.isNotEmpty() && (isSingleTransition || action != batchAction || eraserActive != batchEraser)) {
                            engineClient.sendStylusBatch(
                                deviceId = virtualDeviceId,
                                displayId = config.targetDisplayId,
                                action = batchAction,
                                isEraser = batchEraser,
                                samples = batchBuffer
                            )
                            batchBuffer.clear()
                        }
                        batchAction = action
                        batchEraser = eraserActive
                        batchBuffer.add(
                            StylusSample(
                                eventTimeMs = SystemClock.uptimeMillis(),
                                x = scratchScreenXY[0],
                                y = scratchScreenXY[1],
                                pressure = effectivePressure,
                                tiltX = scratchReport.tiltXDeg,
                                tiltY = scratchReport.tiltYDeg,
                                buttonState = buttons
                            )
                        )
                        if (isSingleTransition || batchBuffer.size >= 4) {
                            engineClient.sendStylusBatch(
                                deviceId = virtualDeviceId,
                                displayId = config.targetDisplayId,
                                action = batchAction,
                                isEraser = batchEraser,
                                samples = batchBuffer
                            )
                            batchBuffer.clear()
                        }
                    } else {
                        if (batchBuffer.isNotEmpty()) batchBuffer.clear()
                        // Standalone PC-free global stylus stroke dispatch via KodaAccessibilityService
                        val a11y = com.example.hub.KodaAccessibilityService.activeInstance()
                        if (a11y != null && !a11y.isForegroundInOwnApp()) {
                            when (action) {
                                MotionEvent.ACTION_DOWN -> a11y.dispatchPointerDown(scratchScreenXY[0], scratchScreenXY[1])
                                MotionEvent.ACTION_MOVE -> a11y.dispatchPointerMove(scratchScreenXY[0], scratchScreenXY[1])
                                MotionEvent.ACTION_UP -> a11y.dispatchPointerUp(scratchScreenXY[0], scratchScreenXY[1])
                            }
                        }
                    }
                    val t3End = System.nanoTime()

                    latencyMeter.recordPacket(
                        parseNs = t1 - t0,
                        transformNs = t2 - t1,
                        injectNs = t3End - t3Start,
                        nowNs = t3End
                    )

                    val nowMs = SystemClock.uptimeMillis()
                    if (nowMs - lastUiPublishMs >= 16L) {
                        lastUiPublishMs = nowMs
                        recordHexPreview(scratchPacket, len, activeParser.name)
                        _telemetry.value = _telemetry.value.copy(
                            pollingRateHz = latencyMeter.currentPollingRateHz,
                            totalLatencyMs = latencyMeter.getTotalLatencyMs(),
                            parseLatencyUs = latencyMeter.getParseUs(),
                            matrixLatencyUs = latencyMeter.getMatrixUs(),
                            injectLatencyUs = latencyMeter.getInjectUs(),
                            droppedPackets = ringBuffer.getDroppedCount(),
                            totalPackets = latencyMeter.getTotalPackets(),
                            batchSize = processed,
                            lastRawX = scratchReport.rawX,
                            lastRawY = scratchReport.rawY,
                            lastScreenX = scratchScreenXY[0],
                            lastScreenY = scratchScreenXY[1],
                            lastRawPressure = scratchReport.rawPressure,
                            maxNativePressure = scratchReport.maxPressure,
                            lastCurvedPressure = effectivePressure,
                            lastTiltX = scratchReport.tiltXDeg,
                            lastTiltY = scratchReport.tiltYDeg,
                            isPenInRange = scratchReport.isHovering,
                            isTipDown = scratchReport.isTipDown,
                            effectiveRotationDeg = matrix.effectiveRotationDeg,
                            effectiveDpiX = matrix.effectiveDpiX,
                            effectiveDpiY = matrix.effectiveDpiY,
                            activeWidthMm = matrix.activeWidthMm,
                            activeHeightMm = matrix.activeHeightMm,
                            pxPerMmX = matrix.effectivePxPerMmX,
                            lastTriggeredShortcut = lastShortcutLabel
                        )
                    }
                }

                if (batchBuffer.isNotEmpty()) {
                    engineClient.sendStylusBatch(
                        deviceId = virtualDeviceId,
                        displayId = config.targetDisplayId,
                        action = batchAction,
                        isEraser = batchEraser,
                        samples = batchBuffer
                    )
                    batchBuffer.clear()
                }

                if (processed == 0) {
                    LockSupport.parkNanos(250_000L) // 0.25ms ultra-low latency wake
                }
            }
        }, "KodaTabletPipelineWorker").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun dispatchButtonAndExpressBindings(report: TabletReport) {
        val bindings = config.bindings
        val kbDevId = virtualDeviceId + 20

        if (report.barrelButton1 != prevBarrel1) {
            prevBarrel1 = report.barrelButton1
            val act = bindings.button1Action
            if (engineClient.state.value.isActive) {
                KeyBindingCatalog.dispatchBindingPress(engineClient, kbDevId, act, report.barrelButton1)
            } else if (report.barrelButton1) {
                val a11y = com.example.hub.KodaAccessibilityService.activeInstance()
                if (a11y != null && !a11y.isForegroundInOwnApp()) {
                    if (cachedB1Action.isRightClick) a11y.performSystemBack()
                    else if (cachedB1Action.isMiddleClick) a11y.performSystemHome()
                }
            }
            if (report.barrelButton1) lastShortcutLabel = "Caneta 1 → $act"
        }

        if (report.barrelButton2 != prevBarrel2) {
            prevBarrel2 = report.barrelButton2
            val act = bindings.button2Action
            if (engineClient.state.value.isActive) {
                KeyBindingCatalog.dispatchBindingPress(engineClient, kbDevId, act, report.barrelButton2)
            } else if (report.barrelButton2) {
                val a11y = com.example.hub.KodaAccessibilityService.activeInstance()
                if (a11y != null && !a11y.isForegroundInOwnApp()) {
                    if (cachedB2Action.isRightClick) a11y.performSystemBack()
                    else if (cachedB2Action.isMiddleClick) a11y.performSystemHome()
                }
            }
            if (report.barrelButton2) lastShortcutLabel = "Caneta 2 → $act"
        }

        val changed = report.expressKeyMask xor prevExpressMask
        if (changed != 0) {
            for (bit in 0 until 12) {
                val mask = 1 shl bit
                if ((changed and mask) != 0) {
                    val isDown = (report.expressKeyMask and mask) != 0
                    val act = bindings.expressKeyActions[bit] ?: continue
                    KeyBindingCatalog.dispatchBindingPress(engineClient, kbDevId, act, isDown)
                    if (isDown) lastShortcutLabel = "Express ${bit + 1} → $act"
                }
            }
            prevExpressMask = report.expressKeyMask
        }
    }

    /**
     * Allows synchronous single-packet testing (e.g., from Diagnostic Test Tools or Robolectric)
     * without requiring physical USB hardware.
     */
    fun feedPacketFromDiagnostic(packet: ByteArray, length: Int) {
        ringBuffer.offer(packet, length, System.nanoTime())
        wakeWorker()
    }

    private fun recordHexPreview(packet: ByteArray, len: Int, source: String) {
        val sb = StringBuilder(len * 3)
        for (i in 0 until len.coerceAtMost(16)) {
            if (i > 0) sb.append(' ')
            val v = packet[i].toInt() and 0xFF
            sb.append(HEX_CHARS[v ushr 4]).append(HEX_CHARS[v and 0x0F])
        }
        synchronized(hexRing) {
            if (hexRing.size >= 20) hexRing.removeFirst()
            hexRing.addLast(
                HidHexFrame(
                    timestampMs = System.currentTimeMillis(),
                    length = len,
                    hexString = sb.toString(),
                    sourceLabel = source
                )
            )
            _hexFrames.value = hexRing.toList().asReversed()
        }
    }

    fun stop() {
        running.set(false)
        workerThread?.let {
            LockSupport.unpark(it)
            it.interrupt()
        }
        workerThread = null
        filters.reset()
        lastRelRawX = -1f
        lastRelRawY = -1f
        prevTipDown = false
        prevHovering = false
        prevBarrel1 = false
        prevBarrel2 = false
        prevExpressMask = 0
    }

    companion object {
        private val HEX_CHARS = "0123456789ABCDEF".toCharArray()
    }
}
