package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import android.os.SystemClock
import android.view.PointerIcon as AndroidPointerIcon
import android.view.WindowManager
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon as ComposePointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.driver.DriverTelemetry
import com.example.hub.CursorOverlaySnapshot
import com.example.hub.CursorRenderMode
import com.example.ui.theme.CoralError
import com.example.ui.theme.EmeraldActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

private data class SheetStrokeSegment(
    val start: Offset,
    val end: Offset,
    val pressure: Float,
    val color: Color
)

private fun CursorOverlaySnapshot.isGlobalSystemOverlayDrawing(): Boolean {
    return overlayPermissionGranted &&
        activeCursor != null &&
        activeCursor.sourceFormat != "KODA" &&
        !isPaused &&
        (mode == CursorRenderMode.SIMULATED || isFromTablet || !nativeCursorApplied)
}

/**
 * Full-Screen Blank White Paper Sheet for Stroke & Pressure Curve testing (v2.0).
 *
 * Fixes:
 * - Single cursor rendering (never draws white KODA arrow over white sheet; shows custom .cur/.ani/.png or cyan crosshair)
 * - Draws strokes from BOTH Compose touch/stylus/mouse input AND live USB tablet pipeline telemetry
 */
@Composable
fun FullScreenBlankSheetStrokeDialog(
    telemetryFlow: StateFlow<DriverTelemetry>,
    cursorSnapshotFlow: StateFlow<CursorOverlaySnapshot>,
    @Suppress("UNUSED_PARAMETER") onFeedTestPacket: (normX: Float, normY: Float, pressure: Float, isDown: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val telemetry by telemetryFlow.collectAsStateWithLifecycle()
    val cursorSnapshot by cursorSnapshotFlow.collectAsStateWithLifecycle()
    val strokes = remember { mutableStateListOf<SheetStrokeSegment>() }
    var sheetSize by remember { mutableStateOf(IntSize(1, 1)) }
    var sheetWindowOrigin by remember { mutableStateOf(Offset.Zero) }
    var lastTouchPoint by remember { mutableStateOf<Offset?>(null) }
    var lastTabletPoint by remember { mutableStateOf<Offset?>(null) }
    var localCursor by remember { mutableStateOf<Offset?>(null) }
    var inkColor by remember { mutableStateOf(Color(0xFF14141F)) }
    val showFallbackSprite = !cursorSnapshot.isGlobalSystemOverlayDrawing()

    // Draw live strokes directly from the USB Tablet Driver pipeline (works with or without ADB)
    LaunchedEffect(
        telemetry.totalPackets,
        telemetry.lastScreenX,
        telemetry.lastScreenY,
        telemetry.isPenInRange,
        telemetry.isTipDown,
        telemetry.lastCurvedPressure
    ) {
        if (sheetSize.width <= 1 || sheetSize.height <= 1) return@LaunchedEffect
        val localPt = Offset(
            (telemetry.lastScreenX - sheetWindowOrigin.x).coerceIn(0f, sheetSize.width.toFloat()),
            (telemetry.lastScreenY - sheetWindowOrigin.y).coerceIn(0f, sheetSize.height.toFloat())
        )
        if (telemetry.isPenInRange || telemetry.isTipDown) {
            localCursor = localPt
        }
        if (telemetry.isTipDown) {
            val prev = lastTabletPoint
            if (prev != null && hypot(localPt.x - prev.x, localPt.y - prev.y) > 0.35f) {
                strokes.add(
                    SheetStrokeSegment(
                        start = prev,
                        end = localPt,
                        pressure = telemetry.lastCurvedPressure.coerceIn(0.05f, 1f),
                        color = inkColor
                    )
                )
            } else if (prev == null) {
                strokes.add(
                    SheetStrokeSegment(
                        start = localPt,
                        end = localPt,
                        pressure = telemetry.lastCurvedPressure.coerceIn(0.05f, 1f),
                        color = inkColor
                    )
                )
            }
            lastTabletPoint = localPt
        } else {
            lastTabletPoint = null
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val dialogView = LocalView.current
        val context = LocalContext.current
        val hideNativePointer = cursorSnapshot.nativePointerHidden ||
            cursorSnapshot.isGlobalSystemOverlayDrawing() ||
            (cursorSnapshot.activeCursor != null && !cursorSnapshot.isPaused &&
                (cursorSnapshot.mode == CursorRenderMode.SIMULATED || cursorSnapshot.isFromTablet || !cursorSnapshot.nativeCursorApplied))
        val nullAndroidIcon = remember(context) {
            runCatching { AndroidPointerIcon.getSystemIcon(context, AndroidPointerIcon.TYPE_NULL) }.getOrNull()
        }
        LaunchedEffect(dialogView, hideNativePointer, cursorSnapshot.mode, cursorSnapshot.activeCursor) {
            (dialogView.parent as? DialogWindowProvider)?.window?.let { win ->
                if (!android.os.Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
                    val lp = win.attributes
                    if (lp.layoutInDisplayCutoutMode == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT) {
                        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        win.attributes = lp
                    }
                }
                win.decorView.pointerIcon = when {
                    hideNativePointer -> nullAndroidIcon
                    cursorSnapshot.mode == CursorRenderMode.NATIVE -> cursorSnapshot.activeCursor?.pointerIcon
                    else -> null
                }
            }
        }

        val rootDialogModifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .let { base ->
                if (hideNativePointer && nullAndroidIcon != null) {
                    base.pointerHoverIcon(ComposePointerIcon(nullAndroidIcon), overrideDescendants = true)
                } else {
                    base
                }
            }
            .testTag("fullscreen_blank_sheet")

        Box(
            modifier = rootDialogModifier
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { sheetSize = it }
                    .onGloballyPositioned { coords ->
                        sheetWindowOrigin = runCatching {
                            if (coords.isAttached) coords.localToScreen(Offset.Zero)
                            else coords.boundsInWindow().topLeft
                        }.getOrElse { coords.boundsInWindow().topLeft }
                    }
                    .pointerInput(inkColor) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: continue
                                localCursor = change.position
                                if (event.type == PointerEventType.Press) {
                                    lastTouchPoint = change.position
                                    val p = change.pressure.takeIf { it > 0f } ?: 0.55f
                                    strokes.add(
                                        SheetStrokeSegment(
                                            start = change.position,
                                            end = change.position,
                                            pressure = p,
                                            color = inkColor
                                        )
                                    )
                                } else if (event.type == PointerEventType.Move && change.pressed) {
                                    val prev = lastTouchPoint ?: change.position
                                    val p = change.pressure.takeIf { it > 0f } ?: 0.72f
                                    strokes.add(
                                        SheetStrokeSegment(
                                            start = prev,
                                            end = change.position,
                                            pressure = p,
                                            color = inkColor
                                        )
                                    )
                                    lastTouchPoint = change.position
                                } else if (event.type == PointerEventType.Release) {
                                    lastTouchPoint = null
                                }
                            }
                        }
                    }
                    .pointerInput(inkColor) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                lastTouchPoint = offset
                                localCursor = offset
                            },
                            onDrag = { change, _ ->
                                val prev = lastTouchPoint ?: change.position
                                val pressure = change.pressure.takeIf { it > 0f } ?: 0.72f
                                if (hypot(change.position.x - prev.x, change.position.y - prev.y) > 0.35f) {
                                    strokes.add(
                                        SheetStrokeSegment(
                                            start = prev,
                                            end = change.position,
                                            pressure = pressure,
                                            color = inkColor
                                        )
                                    )
                                }
                                lastTouchPoint = change.position
                                localCursor = change.position
                                change.consume()
                            },
                            onDragEnd = {
                                lastTouchPoint = null
                            },
                            onDragCancel = {
                                lastTouchPoint = null
                            }
                        )
                    }
                    .testTag("blank_sheet_canvas")
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    for (seg in strokes) {
                        drawLine(
                            color = seg.color,
                            start = seg.start,
                            end = seg.end,
                            strokeWidth = 2.5f + seg.pressure * 18f,
                            cap = StrokeCap.Round
                        )
                    }
                }

                if (strokes.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "Folha em Branco · Teste de Traço & Pressão",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color(0xFF4E556B)
                        )
                        Text(
                            text = "Desenhe livremente com a caneta da mesa, mouse ou dedo",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF7B8299)
                        )
                    }
                }
            }

            // Compact Floating Top Control Bar
            Surface(
                color = Color(0xEE121426),
                shape = RoundedCornerShape(18.dp),
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp)
                    .fillMaxWidth()
            ) {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilledTonalButton(
                        onClick = onDismiss,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("blank_sheet_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Voltar", maxLines = 1)
                    }

                    Column(
                        modifier = Modifier.padding(horizontal = 2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = String.format(Locale.US, "Pressão: %.0f%%", telemetry.lastCurvedPressure * 100f),
                            style = MaterialTheme.typography.labelLarge,
                            color = EmeraldActive,
                            maxLines = 1
                        )
                        Text(
                            text = "${telemetry.lastRawPressure}/${telemetry.maxNativePressure} · ${telemetry.pollingRateHz}Hz",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFB5BBD4),
                            maxLines = 1
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        listOf(
                            Color(0xFF14141F),
                            Color(0xFF1D4ED8),
                            Color(0xFFD9264E)
                        ).forEach { swatch ->
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(swatch)
                                    .border(
                                        width = if (inkColor == swatch) 2.5.dp else 1.dp,
                                        color = if (inkColor == swatch) EmeraldActive else Color.White.copy(alpha = 0.6f),
                                        shape = CircleShape
                                    )
                                    .clickable { inkColor = swatch }
                            )
                        }
                    }

                    Button(
                        onClick = {
                            strokes.clear()
                            lastTabletPoint = null
                            lastTouchPoint = null
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CoralError,
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("blank_sheet_delete_stroke_button")
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Excluir Traço", maxLines = 1)
                    }
                }
            }

            // Render in-app cursor sprite ONLY when global overlay isn't already drawing on top
            if (showFallbackSprite) {
                localCursor?.let { pt ->
                    InAppActiveCursorSprite(
                        cursorSnapshot = cursorSnapshot,
                        position = pt
                    )
                }
            }
        }
    }
}

private enum class CalibrationPhase {
    INTRO,
    CAPTURING,
    COMPLETED
}

/**
 * Full-Screen 5-Point Guided Calibration v2.0 (Intro -> 1 Point at a Time -> Green Save Flash -> Success).
 *
 * - No white mouse cursor overlay (both in-app sprite, Koda floating overlay, and native OS pointer are hidden)
 * - No instruction popup overlapping target crosshairs
 * - Displays 1 target point at a time; turns Green when clicked/captured
 * - Uses active profile's real rawMaxX/rawMaxY and step timing debounce to prevent accidental double-captures
 */
@Composable
fun FullScreenCalibrationDialog(
    telemetryFlow: StateFlow<DriverTelemetry>,
    maxRawX: Int = 32767,
    maxRawY: Int = 32767,
    onSetCalibrationCursorHidden: (Boolean) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onFeedTestPacket: (normX: Float, normY: Float, pressure: Float, isDown: Boolean) -> Unit,
    onApplyCalibration: (minX: Int, maxX: Int, minY: Int, maxY: Int) -> Unit,
    onResetFactoryCalibration: () -> Unit,
    onDismiss: () -> Unit
) {
    DisposableEffect(Unit) {
        onSetCalibrationCursorHidden(true)
        onDispose {
            onSetCalibrationCursorHidden(false)
        }
    }

    val telemetry by telemetryFlow.collectAsStateWithLifecycle()
    val targetPoints = remember {
        listOf(
            Offset(0.10f, 0.14f) to "1/5 · Canto Superior Esquerdo",
            Offset(0.90f, 0.14f) to "2/5 · Canto Superior Direito",
            Offset(0.90f, 0.86f) to "3/5 · Canto Inferior Direito",
            Offset(0.10f, 0.86f) to "4/5 · Canto Inferior Esquerdo",
            Offset(0.50f, 0.50f) to "5/5 · Ponto Central (Meio da Mesa)"
        )
    }

    var phase by remember { mutableStateOf(CalibrationPhase.INTRO) }
    var currentStep by remember { mutableIntStateOf(0) }
    var pointConfirmedGreen by remember { mutableStateOf(false) }
    var factoryResetFeedback by remember { mutableStateOf(false) }
    val capturedRawX = remember { mutableStateListOf<Int>() }
    val capturedRawY = remember { mutableStateListOf<Int>() }
    var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }
    var prevTelemetryTipDown by remember { mutableStateOf(false) }
    var stepEnteredAtMs by remember { mutableLongStateOf(0L) }
    val scope = rememberCoroutineScope()

    fun commitPointCapture(tapNorm: Offset?) {
        if (phase != CalibrationPhase.CAPTURING || pointConfirmedGreen) return
        val safeMaxX = maxRawX.coerceAtLeast(1).toFloat()
        val safeMaxY = maxRawY.coerceAtLeast(1).toFloat()
        val rx = if (telemetry.lastRawX > 0) {
            telemetry.lastRawX
        } else {
            val fallback = tapNorm ?: targetPoints[currentStep].first
            (fallback.x * safeMaxX).roundToInt()
        }
        val ry = if (telemetry.lastRawY > 0) {
            telemetry.lastRawY
        } else {
            val fallback = tapNorm ?: targetPoints[currentStep].first
            (fallback.y * safeMaxY).roundToInt()
        }
        capturedRawX.add(rx)
        capturedRawY.add(ry)
        pointConfirmedGreen = true

        scope.launch {
            delay(320L)
            pointConfirmedGreen = false
            if (currentStep < 4) {
                currentStep++
                stepEnteredAtMs = SystemClock.uptimeMillis()
            } else {
                val cornerXs = capturedRawX.take(4)
                val cornerYs = capturedRawY.take(4)
                val minX = cornerXs.minOrNull() ?: 320
                val maxX = cornerXs.maxOrNull() ?: (safeMaxX * 0.99f).roundToInt()
                val minY = cornerYs.minOrNull() ?: 320
                val maxY = cornerYs.maxOrNull() ?: (safeMaxY * 0.99f).roundToInt()
                onApplyCalibration(minX, maxX, minY, maxY)
                phase = CalibrationPhase.COMPLETED
                delay(850L)
                onDismiss()
            }
        }
    }

    // Track real tablet stylus tip press without bouncing feedback loops or capturing the Start click
    LaunchedEffect(
        telemetry.totalPackets,
        telemetry.isTipDown
    ) {
        val nowMs = SystemClock.uptimeMillis()
        if (phase == CalibrationPhase.CAPTURING &&
            telemetry.isTipDown &&
            !prevTelemetryTipDown &&
            (nowMs - stepEnteredAtMs) >= 220L
        ) {
            val nx = if (canvasSize.width > 1) (telemetry.lastScreenX / canvasSize.width).coerceIn(0f, 1f) else null
            val ny = if (canvasSize.height > 1) (telemetry.lastScreenY / canvasSize.height).coerceIn(0f, 1f) else null
            commitPointCapture(if (nx != null && ny != null) Offset(nx, ny) else null)
        }
        prevTelemetryTipDown = telemetry.isTipDown
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        val dialogView = LocalView.current
        val context = LocalContext.current
        val nullAndroidIcon = remember(context) {
            runCatching { AndroidPointerIcon.getSystemIcon(context, AndroidPointerIcon.TYPE_NULL) }.getOrNull()
        }
        LaunchedEffect(dialogView, nullAndroidIcon) {
            (dialogView.parent as? DialogWindowProvider)?.window?.let { win ->
                if (!android.os.Build.FINGERPRINT.equals("robolectric", ignoreCase = true)) {
                    val lp = win.attributes
                    if (lp.layoutInDisplayCutoutMode == WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT) {
                        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                        win.attributes = lp
                    }
                }
                win.decorView.pointerIcon = nullAndroidIcon
            }
        }

        val calibrationRootModifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0C18))
            .let { base ->
                if (nullAndroidIcon != null) {
                    base.pointerHoverIcon(ComposePointerIcon(nullAndroidIcon), overrideDescendants = true)
                } else {
                    base
                }
            }
            .onSizeChanged { canvasSize = it }
            .testTag("fullscreen_calibration_dialog")

        Box(
            modifier = calibrationRootModifier
        ) {
            when (phase) {
                CalibrationPhase.INTRO -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF14182D)),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier
                                .widthIn(max = 540.dp)
                                .fillMaxWidth()
                                .border(1.5.dp, Color(0xFF3E4470), RoundedCornerShape(24.dp))
                        ) {
                            Column(
                                modifier = Modifier.padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        Icons.Default.MyLocation,
                                        contentDescription = null,
                                        tint = Color(0xFF00E5FF),
                                        modifier = Modifier.size(30.dp)
                                    )
                                    Text(
                                        text = "Calibração 4 Cantos + Centro",
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Text(
                                    text = "Como funciona:",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color(0xFF00E5FF)
                                )
                                Text(
                                    text = "• A tela abrirá limpa mostrando 1 alvo por vez (4 cantos e o centro).\n" +
                                        "• Leve a ponta da caneta sobre o alvo e clique com a mesa digitalizadora.\n" +
                                        "• Ao salvar, o ponto ficará VERDE e avançará automaticamente.\n" +
                                        "• Após os 5 pontos, a área ativa será calibrada com sucesso.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color(0xFFD5DAF0)
                                )

                                if (factoryResetFeedback) {
                                    Surface(
                                        color = EmeraldActive.copy(alpha = 0.16f),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = "Padrão de fábrica restaurado (100% área ativa, 0°).",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = EmeraldActive,
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                }

                                Button(
                                    onClick = {
                                        currentStep = 0
                                        capturedRawX.clear()
                                        capturedRawY.clear()
                                        pointConfirmedGreen = false
                                        prevTelemetryTipDown = true
                                        stepEnteredAtMs = SystemClock.uptimeMillis()
                                        phase = CalibrationPhase.CAPTURING
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp)
                                        .testTag("calibration_start_button")
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Começar Calibração")
                                }

                                OutlinedButton(
                                    onClick = {
                                        onResetFactoryCalibration()
                                        factoryResetFeedback = true
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("calibration_factory_reset_button")
                                ) {
                                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Resetar para Padrão de Fábrica")
                                }

                                FilledTonalButton(
                                    onClick = onDismiss,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp)
                                        .testTag("calibration_back_button")
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Voltar")
                                }
                            }
                        }
                    }
                }

                CalibrationPhase.CAPTURING -> {
                    val activeTarget = targetPoints[currentStep]
                    // Place compact status pill AWAY from the current target point so nothing overlaps
                    val pillAlignment = if (currentStep <= 1) Alignment.BottomCenter else Alignment.TopCenter

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(currentStep, pointConfirmedGreen) {
                                detectTapGestures { offset ->
                                    if (SystemClock.uptimeMillis() - stepEnteredAtMs >= 160L) {
                                        val nx = (offset.x / canvasSize.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                        val ny = (offset.y / canvasSize.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                                        commitPointCapture(Offset(nx, ny))
                                    }
                                }
                            }
                    ) {
                        // Draw subtle grid + ONLY THE SINGLE CURRENT TARGET POINT
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val gridColor = Color(0xFF1F2442)
                            for (i in 1..5) {
                                val gx = size.width * (i / 6f)
                                val gy = size.height * (i / 6f)
                                drawLine(gridColor, Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1f)
                                drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
                            }

                            val center = Offset(
                                activeTarget.first.x * size.width,
                                activeTarget.first.y * size.height
                            )
                            val pointColor = if (pointConfirmedGreen) EmeraldActive else Color(0xFF00E5FF)
                            val radius = if (pointConfirmedGreen) 42f else 36f

                            drawCircle(
                                color = pointColor.copy(alpha = if (pointConfirmedGreen) 0.35f else 0.20f),
                                radius = radius + 18f,
                                center = center
                            )
                            drawCircle(
                                color = pointColor,
                                radius = radius,
                                center = center,
                                style = Stroke(width = 5f)
                            )
                            drawLine(
                                pointColor,
                                Offset(center.x - radius - 16f, center.y),
                                Offset(center.x + radius + 16f, center.y),
                                strokeWidth = 3f
                            )
                            drawLine(
                                pointColor,
                                Offset(center.x, center.y - radius - 16f),
                                Offset(center.x, center.y + radius + 16f),
                                strokeWidth = 3f
                            )
                            drawCircle(color = pointColor, radius = 9f, center = center)
                        }

                        Surface(
                            color = Color(0xEE14162A),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier
                                .align(pillAlignment)
                                .statusBarsPadding()
                                .navigationBarsPadding()
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                                .border(
                                    1.dp,
                                    if (pointConfirmedGreen) EmeraldActive else Color(0xFF3E4470),
                                    RoundedCornerShape(18.dp)
                                )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = onDismiss,
                                    modifier = Modifier
                                        .height(42.dp)
                                        .testTag("calibration_back_button")
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                                    Spacer(Modifier.width(4.dp))
                                    Text("Sair")
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (pointConfirmedGreen) "Ponto Salvo!" else activeTarget.second,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (pointConfirmedGreen) EmeraldActive else Color(0xFF00E5FF),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Clique exatamente no alvo visível",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFC5CAE9)
                                    )
                                }

                                Button(
                                    onClick = { commitPointCapture(null) },
                                    modifier = Modifier
                                        .height(42.dp)
                                        .testTag("calibration_confirm_step_button")
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Salvar ${currentStep + 1}/5")
                                }
                            }
                        }
                    }
                }

                CalibrationPhase.COMPLETED -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("calibration_success_banner"),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF102A22)),
                            shape = RoundedCornerShape(24.dp),
                            modifier = Modifier.border(2.dp, EmeraldActive, RoundedCornerShape(24.dp))
                        ) {
                            Column(
                                modifier = Modifier.padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = EmeraldActive,
                                    modifier = Modifier.size(56.dp)
                                )
                                Text(
                                    text = "Calibração com sucesso!",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = EmeraldActive,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Os 5 pontos foram salvos na área ativa da mesa.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tip-aligned custom .cur/.ani/.png cursor renderer fallback for the test drawing sheet.
 * Built-in "KODA" white arrow is replaced with a crisp cyan crosshair on light/test surfaces.
 */
@Composable
fun BoxScope.InAppActiveCursorSprite(
    cursorSnapshot: CursorOverlaySnapshot,
    position: Offset
) {
    val active = cursorSnapshot.activeCursor
    val density = LocalDensity.current

    if (active != null && active.sourceFormat != "KODA") {
        val scale = cursorSnapshot.cursorScale
        val bmp = active.frames.getOrNull(cursorSnapshot.currentFrameIndex) ?: active.bitmap
        val wPx = (bmp.width * scale).roundToInt().coerceAtLeast(16)
        val hPx = (bmp.height * scale).roundToInt().coerceAtLeast(16)
        val tipOffX = (active.hotspotX * scale).roundToInt()
        val tipOffY = (active.hotspotY * scale).roundToInt()
        val widthDp = with(density) { wPx.toDp() }
        val heightDp = with(density) { hPx.toDp() }

        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Cursor ativo ${active.fileName}",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = position.x.roundToInt() - tipOffX,
                        y = position.y.roundToInt() - tipOffY
                    )
                }
                .size(width = widthDp, height = heightDp)
        )
    } else {
        Canvas(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = position.x.roundToInt() - 18,
                        y = position.y.roundToInt() - 18
                    )
                }
                .size(36.dp)
        ) {
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(Color(0xFF00E5FF), radius = 10f, center = c, style = Stroke(width = 2.5f))
            drawLine(Color(0xFF00E5FF), Offset(c.x - 16f, c.y), Offset(c.x + 16f, c.y), strokeWidth = 2f)
            drawLine(Color(0xFF00E5FF), Offset(c.x, c.y - 16f), Offset(c.x, c.y + 16f), strokeWidth = 2f)
        }
    }
}
