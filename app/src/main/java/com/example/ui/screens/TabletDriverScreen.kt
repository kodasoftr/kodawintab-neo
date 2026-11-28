package com.example.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.ScreenLockRotation
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.display.TargetDisplayInfo
import com.example.driver.ActiveAreaRect
import com.example.driver.DriverTelemetry
import com.example.driver.HidHexFrame
import com.example.driver.PressureControlPoint
import com.example.driver.TabletConfig
import com.example.driver.TabletProfileJson
import com.example.driver.TabletSlotId
import com.example.hub.CursorOverlaySnapshot
import com.example.hub.CursorRenderMode
import com.example.hub.DecodedCursor
import com.example.hub.InstalledAppEntry
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.EmeraldActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Locale
import kotlin.math.hypot

private data class DriverCursorBannerMeta(
    val activeCursor: DecodedCursor? = null,
    val mode: CursorRenderMode = CursorRenderMode.SIMULATED
)

private data class TopBarTelemetryMeta(
    val activeProfileName: String = "",
    val effectiveRotationDeg: Int = 0,
    val effectiveDpiX: Int = 420
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabletDriverScreen(
    selectedSlot: TabletSlotId,
    tabletConfig: TabletConfig,
    availableProfiles: List<TabletProfileJson>,
    telemetryFlow: StateFlow<DriverTelemetry>,
    hexFramesFlow: StateFlow<List<HidHexFrame>>,
    displays: List<TargetDisplayInfo>,
    installedApps: List<InstalledAppEntry>,
    connectedTabletCount: Int,
    cursorSnapshotFlow: StateFlow<CursorOverlaySnapshot>,
    onBack: () -> Unit,
    onSelectSlot: (TabletSlotId) -> Unit,
    onSelectProfile: (String) -> Unit,
    onUpdateConfig: ((TabletConfig) -> TabletConfig) -> Unit,
    onApplyPressurePreset: (List<PressureControlPoint>, String) -> Unit,
    onUpdatePressurePoint: (Int, Float, Float) -> Unit,
    onResetPressureCurve: () -> Unit,
    onFeedTestPacket: (Float, Float, Float, Boolean) -> Unit,
    onApplyCalibration: (Int, Int, Int, Int) -> Unit,
    onResetFactoryCalibration: () -> Unit,
    onResetTabletTab: () -> Unit = {},
    onResetPenTab: () -> Unit = {},
    onResetBindingsTab: () -> Unit = {},
    onResetFiltersTab: () -> Unit = {},
    onResetOutputTab: () -> Unit = {},
    onResetAllTabs: () -> Unit = {},
    onActivateAutoDpi: () -> Unit,
    onOpenCursorHub: () -> Unit,
    onSetCalibrationCursorHidden: (Boolean) -> Unit = {}
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showTestTools by remember { mutableStateOf(false) }
    var showFullScreenBlankSheet by remember { mutableStateOf(false) }
    var showFullScreenCalibration by remember { mutableStateOf(false) }

    val otdTabs = remember {
        listOf(
            "Tablet" to Icons.Default.AspectRatio,
            "Pen" to Icons.Default.Brush,
            "Bindings" to Icons.Default.Keyboard,
            "Filters" to Icons.Default.Tune,
            "Output" to Icons.Default.Monitor
        )
    }
    val activeProfile = availableProfiles.firstOrNull { it.id == tabletConfig.profileId }
    val cursorBannerMeta by remember(cursorSnapshotFlow) {
        cursorSnapshotFlow
            .map { DriverCursorBannerMeta(activeCursor = it.activeCursor, mode = it.mode) }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = DriverCursorBannerMeta(
            activeCursor = cursorSnapshotFlow.value.activeCursor,
            mode = cursorSnapshotFlow.value.mode
        )
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Driver Global 2.0 · Mesa", style = MaterialTheme.typography.titleLarge)
                        TopBarTelemetrySubtitle(
                            profileNameOverride = activeProfile?.name,
                            telemetryFlow = telemetryFlow
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("mesa_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    IconButton(
                        onClick = onResetAllTabs,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("reset_all_tabs_button")
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = "Resetar todas as abas")
                    }
                    IconButton(
                        onClick = { showFullScreenCalibration = true },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("topbar_calibration_button")
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = "Calibração 4 cantos + centro")
                    }
                    IconButton(
                        onClick = { showTestTools = true },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("open_test_tools_button")
                    ) {
                        Icon(Icons.Default.Build, contentDescription = "Ferramentas de teste")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 860.dp)
                    .fillMaxSize()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenCursorHub() }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            val activeCursor = cursorBannerMeta.activeCursor
                            if (activeCursor != null) {
                                Image(
                                    bitmap = activeCursor.bitmap.asImageBitmap(),
                                    contentDescription = "Cursor de mira ativo",
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(MaterialTheme.shapes.small)
                                        .background(MaterialTheme.colorScheme.background)
                                        .padding(2.dp)
                                )
                                Text(
                                    text = "Mira ativa: ${activeCursor.fileName} (${cursorBannerMeta.mode.label})",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = EmeraldActive
                                )
                            } else {
                                Icon(
                                    Icons.Default.TrackChanges,
                                    contentDescription = null,
                                    tint = AmberAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = "Toque para escolher .cur / .ani / .png para mirar na mesa",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        if (activeProfile != null) {
                            Text(
                                text = "${activeProfile.lpi} LPI · ${activeProfile.pollingRateHz}Hz",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val slotOptions = if (connectedTabletCount >= 2) {
                        listOf(TabletSlotId.MESA_1, TabletSlotId.MESA_2)
                    } else {
                        listOf(TabletSlotId.GLOBAL, TabletSlotId.MESA_1, TabletSlotId.MESA_2)
                    }
                    slotOptions.forEach { slot ->
                        FilterChip(
                            selected = selectedSlot == slot,
                            onClick = { onSelectSlot(slot) },
                            label = { Text(slot.label) }
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    availableProfiles.forEach { prof ->
                        FilterChip(
                            selected = tabletConfig.profileId == prof.id,
                            onClick = { onSelectProfile(prof.id) },
                            label = { Text(prof.name) }
                        )
                    }
                }

                PrimaryScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    edgePadding = 16.dp
                ) {
                    otdTabs.forEachIndexed { idx, (title, tabIcon) ->
                        Tab(
                            selected = selectedTab == idx,
                            onClick = { selectedTab = idx },
                            icon = { Icon(tabIcon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            text = { Text(title) },
                            modifier = Modifier.testTag("otd_tab_${title.lowercase()}")
                        )
                    }
                }

                val tabScrollState = remember(selectedTab) { ScrollState(0) }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(tabScrollState)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    when (selectedTab) {
                        0 -> TabletAreaAndRotationTab(
                            config = tabletConfig,
                            telemetryFlow = telemetryFlow,
                            profile = activeProfile,
                            displays = displays,
                            onUpdateConfig = onUpdateConfig,
                            onOpenFullCalibration = { showFullScreenCalibration = true },
                            onResetTab = onResetTabletTab
                        )
                        1 -> PenPressureTab(
                            config = tabletConfig,
                            telemetryFlow = telemetryFlow,
                            maxPressure = activeProfile?.maxPressure ?: 8191,
                            onUpdateConfig = onUpdateConfig,
                            onApplyPreset = onApplyPressurePreset,
                            onUpdatePressurePoint = onUpdatePressurePoint,
                            onResetTab = onResetPenTab
                        )
                        2 -> BindingsTabWrapper(
                            config = tabletConfig,
                            profileName = activeProfile?.name ?: "Mesa Selecionada",
                            expressCount = activeProfile?.expressKeysCount ?: 10,
                            telemetryFlow = telemetryFlow,
                            onUpdateConfig = onUpdateConfig,
                            onResetTab = onResetBindingsTab
                        )
                        3 -> FiltersTab(
                            config = tabletConfig,
                            onUpdateConfig = onUpdateConfig,
                            onResetTab = onResetFiltersTab
                        )
                        4 -> OutputTabWrapper(
                            config = tabletConfig,
                            profile = activeProfile,
                            telemetryFlow = telemetryFlow,
                            displays = displays,
                            installedApps = installedApps,
                            onUpdateConfig = onUpdateConfig,
                            onActivateAutoDpi = onActivateAutoDpi,
                            onResetTab = onResetOutputTab
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { showFullScreenBlankSheet = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("open_blank_sheet_button")
                        ) {
                            Icon(Icons.Default.Draw, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Testar Traço (Tela Cheia)", maxLines = 1)
                        }
                        FilledTonalButton(
                            onClick = { showTestTools = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("launch_tablet_test_tools_cta")
                        ) {
                            Icon(Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Hex HID & Latência", maxLines = 1)
                        }
                    }

                    Spacer(Modifier.height(72.dp))
                }
            }
        }
    }

    if (showTestTools) {
        TabletTestToolsSheet(
            telemetryFlow = telemetryFlow,
            hexFramesFlow = hexFramesFlow,
            onFeedTestPacket = onFeedTestPacket,
            onOpenFullScreenBlankSheet = {
                showTestTools = false
                showFullScreenBlankSheet = true
            },
            onOpenFullScreenCalibration = {
                showTestTools = false
                showFullScreenCalibration = true
            },
            onDismiss = { showTestTools = false }
        )
    }

    if (showFullScreenBlankSheet) {
        FullScreenBlankSheetStrokeDialog(
            telemetryFlow = telemetryFlow,
            cursorSnapshotFlow = cursorSnapshotFlow,
            onFeedTestPacket = onFeedTestPacket,
            onDismiss = { showFullScreenBlankSheet = false }
        )
    }

    if (showFullScreenCalibration) {
        FullScreenCalibrationDialog(
            telemetryFlow = telemetryFlow,
            maxRawX = activeProfile?.maxX ?: 32767,
            maxRawY = activeProfile?.maxY ?: 32767,
            onSetCalibrationCursorHidden = onSetCalibrationCursorHidden,
            onFeedTestPacket = onFeedTestPacket,
            onApplyCalibration = onApplyCalibration,
            onResetFactoryCalibration = onResetFactoryCalibration,
            onDismiss = { showFullScreenCalibration = false }
        )
    }
}

@Composable
private fun TopBarTelemetrySubtitle(
    profileNameOverride: String?,
    telemetryFlow: StateFlow<DriverTelemetry>
) {
    val meta by remember(telemetryFlow) {
        telemetryFlow
            .map {
                TopBarTelemetryMeta(
                    activeProfileName = it.activeProfileName,
                    effectiveRotationDeg = it.effectiveRotationDeg,
                    effectiveDpiX = it.effectiveDpiX.toInt()
                )
            }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(
        initialValue = TopBarTelemetryMeta(
            activeProfileName = telemetryFlow.value.activeProfileName,
            effectiveRotationDeg = telemetryFlow.value.effectiveRotationDeg,
            effectiveDpiX = telemetryFlow.value.effectiveDpiX.toInt()
        )
    )
    Text(
        text = "${profileNameOverride ?: meta.activeProfileName} · ${meta.effectiveRotationDeg}° · ${meta.effectiveDpiX} DPI",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun BindingsTabWrapper(
    config: TabletConfig,
    profileName: String,
    expressCount: Int,
    telemetryFlow: StateFlow<DriverTelemetry>,
    onUpdateConfig: ((TabletConfig) -> TabletConfig) -> Unit,
    onResetTab: () -> Unit
) {
    val lastShortcut by remember(telemetryFlow) {
        telemetryFlow.map { it.lastTriggeredShortcut }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = telemetryFlow.value.lastTriggeredShortcut)
    BindingsTab(
        config = config,
        profileName = profileName,
        expressCount = expressCount,
        telemetry = remember(lastShortcut) { DriverTelemetry(lastTriggeredShortcut = lastShortcut) },
        onUpdateConfig = onUpdateConfig,
        onResetTab = onResetTab
    )
}

@Composable
private fun OutputTabWrapper(
    config: TabletConfig,
    profile: TabletProfileJson?,
    telemetryFlow: StateFlow<DriverTelemetry>,
    displays: List<TargetDisplayInfo>,
    installedApps: List<InstalledAppEntry>,
    onUpdateConfig: ((TabletConfig) -> TabletConfig) -> Unit,
    onActivateAutoDpi: () -> Unit,
    onResetTab: () -> Unit
) {
    val staticMetrics by remember(telemetryFlow) {
        telemetryFlow
            .map {
                DriverTelemetry(
                    effectiveDpiX = it.effectiveDpiX,
                    effectiveDpiY = it.effectiveDpiY,
                    pxPerMmX = it.pxPerMmX,
                    activeWidthMm = it.activeWidthMm,
                    activeHeightMm = it.activeHeightMm,
                    maxNativePressure = it.maxNativePressure
                )
            }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = telemetryFlow.value)
    OutputTab(
        config = config,
        profile = profile,
        telemetry = staticMetrics,
        displays = displays,
        installedApps = installedApps,
        onUpdateConfig = onUpdateConfig,
        onActivateAutoDpi = onActivateAutoDpi,
        onResetTab = onResetTab
    )
}

@Composable
private fun TabletAreaAndRotationTab(
    config: TabletConfig,
    telemetryFlow: StateFlow<DriverTelemetry>,
    profile: TabletProfileJson?,
    displays: List<TargetDisplayInfo>,
    onUpdateConfig: ((TabletConfig) -> TabletConfig) -> Unit,
    onOpenFullCalibration: () -> Unit,
    onResetTab: () -> Unit
) {
    val activeDisplay = displays.firstOrNull { it.displayId == config.targetDisplayId } ?: displays.firstOrNull()
    val orientationLabel = if ((activeDisplay?.widthPx ?: 1080) > (activeDisplay?.heightPx ?: 1920)) {
        "Paisagem (${activeDisplay?.rotationDeg ?: 90}°)"
    } else {
        "Retrato (${activeDisplay?.rotationDeg ?: 0}°)"
    }
    val effectiveRotationDeg by remember(telemetryFlow) {
        telemetryFlow.map { it.effectiveRotationDeg }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = telemetryFlow.value.effectiveRotationDeg)

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Pré-visualização & Área Ativa", style = MaterialTheme.typography.titleMedium)
                    if (profile != null) {
                        Text(
                            text = "${profile.widthMm.toInt()}×${profile.heightMm.toInt()}mm · Raw ${profile.maxX}×${profile.maxY}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = "$orientationLabel · Efetivo: ${effectiveRotationDeg}°" +
                        (if (config.invertX) " · InvX" else "") +
                        (if (config.invertY) " · InvY" else ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            val primary = MaterialTheme.colorScheme.primary
            val secondary = MaterialTheme.colorScheme.secondary
            val outline = MaterialTheme.colorScheme.outline
            var areaCanvasSize by remember { mutableStateOf(IntSize(1, 1)) }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(175.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(Color(0xFF0B0E1A))
                    .border(1.dp, outline, MaterialTheme.shapes.medium)
                    .padding(12.dp)
                    .onSizeChanged { areaCanvasSize = it }
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            val wPx = areaCanvasSize.width.coerceAtLeast(1).toFloat()
                            val hPx = areaCanvasSize.height.coerceAtLeast(1).toFloat()
                            val dxNorm = dragAmount.x / wPx
                            val dyNorm = dragAmount.y / hPx
                            onUpdateConfig { cur ->
                                val a = cur.activeArea
                                val maxL = (1f - a.widthNorm).coerceAtLeast(0f)
                                val maxT = (1f - a.heightNorm).coerceAtLeast(0f)
                                var nextL = (a.leftNorm + dxNorm).coerceIn(0f, maxL)
                                var nextT = (a.topNorm + dyNorm).coerceIn(0f, maxT)
                                val centerL = maxL * 0.5f
                                val centerT = maxT * 0.5f
                                if (kotlin.math.abs(nextL - centerL) < 0.015f) nextL = centerL
                                if (kotlin.math.abs(nextT - centerT) < 0.015f) nextT = centerT
                                cur.copy(activeArea = a.copy(leftNorm = nextL, topNorm = nextT))
                            }
                            change.consume()
                        }
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Subtle grid & center crosshair
                    for (i in 1..3) {
                        val gx = size.width * (i / 4f)
                        val gy = size.height * (i / 4f)
                        drawLine(outline.copy(alpha = 0.22f), Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1.2f)
                        drawLine(outline.copy(alpha = 0.22f), Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1.2f)
                    }
                    drawRect(
                        color = outline.copy(alpha = 0.50f),
                        style = Stroke(width = 2.5f)
                    )
                    val a = config.activeArea
                    val left = a.leftNorm * size.width
                    val top = a.topNorm * size.height
                    val w = a.widthNorm * size.width
                    val h = a.heightNorm * size.height
                    drawRect(
                        brush = Brush.verticalGradient(
                            listOf(primary.copy(alpha = 0.28f), secondary.copy(alpha = 0.14f))
                        ),
                        topLeft = Offset(left, top),
                        size = Size(w, h)
                    )
                    drawRect(
                        color = primary,
                        topLeft = Offset(left, top),
                        size = Size(w, h),
                        style = Stroke(width = 4f)
                    )
                    // Corner handles visual cues
                    val corners = listOf(
                        Offset(left, top),
                        Offset(left + w, top),
                        Offset(left, top + h),
                        Offset(left + w, top + h)
                    )
                    corners.forEach { corner ->
                        drawCircle(color = primary, radius = 6f, center = corner)
                    }
                    val ox = if (config.invertX) left + w - 12f else left + 12f
                    val oy = if (config.invertY) top + h - 12f else top + 12f
                    drawCircle(color = secondary, radius = 7f, center = Offset(ox, oy))
                }
                TabletAreaLivePenDotCanvas(
                    profile = profile,
                    telemetryFlow = telemetryFlow
                )
                Surface(
                    color = Color(0xBA0E1222),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.align(Alignment.BottomEnd)
                ) {
                    Text(
                        text = "Arraste p/ mover · ${(config.activeArea.widthNorm * 100).toInt()}%×${(config.activeArea.heightNorm * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = secondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = config.activeArea.widthNorm >= 0.99f && config.activeArea.heightNorm >= 0.99f,
                    onClick = { onUpdateConfig { it.copy(activeArea = ActiveAreaRect(0f, 0f, 1f, 1f)) } },
                    label = { Text("Área Total (100%)") }
                )
                FilterChip(
                    selected = config.activeArea.widthNorm in 0.72f..0.78f,
                    onClick = { onUpdateConfig { it.copy(activeArea = ActiveAreaRect(0.125f, 0.125f, 0.75f, 0.75f)) } },
                    label = { Text("Conforto (75%)") }
                )
                FilterChip(
                    selected = config.activeArea.widthNorm in 0.52f..0.58f,
                    onClick = { onUpdateConfig { it.copy(activeArea = ActiveAreaRect(0.225f, 0.225f, 0.55f, 0.55f)) } },
                    label = { Text("Precisão / FPS (55%)") }
                )
                FilterChip(
                    selected = config.activeArea.widthNorm >= 0.99f && config.activeArea.heightNorm in 0.54f..0.60f,
                    onClick = { onUpdateConfig { it.copy(activeArea = ActiveAreaRect(0f, 0.218f, 1f, 0.5625f)) } },
                    label = { Text("Widescreen 16:9") }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (config.autoRotation) Icons.Default.ScreenRotation else Icons.Default.ScreenLockRotation,
                        contentDescription = null,
                        tint = if (config.autoRotation) MaterialTheme.colorScheme.primary else AmberAccent
                    )
                    Column {
                        Text(
                            text = if (config.autoRotation) {
                                "Rotação Automática (segue o celular)"
                            } else {
                                "Giro Automático Desativado (Mesa Fixa)"
                            },
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = if (config.autoRotation) {
                                "Adapta o driver entre Retrato ↔ Paisagem; toque para travar a direção"
                            } else {
                                "A mesa permanece na mesma direção mesmo se você girar o celular"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = config.autoRotation,
                    onCheckedChange = { checked -> onUpdateConfig { it.copy(autoRotation = checked) } },
                    modifier = Modifier.testTag("auto_rotation_switch")
                )
            }

            if (config.autoRotation) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !config.rotateTabletAxesWithScreen,
                        onClick = { onUpdateConfig { it.copy(rotateTabletAxesWithScreen = false) } },
                        label = { Text("Mesa Fixa (Sem Inverter Paisagem)") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = config.rotateTabletAxesWithScreen,
                        onClick = { onUpdateConfig { it.copy(rotateTabletAxesWithScreen = true) } },
                        label = { Text("Girar Eixos com a Tela") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Text(
                text = "Direção Manual da Mesa (${config.manualRotationDeg}°)",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(0, 90, 180, 270).forEach { deg ->
                    FilterChip(
                        selected = config.manualRotationDeg == deg,
                        onClick = { onUpdateConfig { it.copy(manualRotationDeg = deg) } },
                        label = { Text("${deg}°") },
                        modifier = Modifier.weight(1f)
                    )
                }
                FilledTonalButton(
                    onClick = {
                        onUpdateConfig {
                            it.copy(manualRotationDeg = (it.manualRotationDeg + 90) % 360)
                        }
                    },
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("manual_rotate_tablet_90_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = "Girar +90°", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("+90°")
                }
            }
            OutlinedButton(
                onClick = {
                    onUpdateConfig {
                        it.copy(manualRotationDeg = (it.manualRotationDeg + 180) % 360)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("manual_flip_180_button")
            ) {
                Text("Inverter 180° (Corrigir Cabo / Cabeça p/ Baixo)")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilterChip(
                    selected = config.invertX,
                    onClick = { onUpdateConfig { it.copy(invertX = !it.invertX) } },
                    leadingIcon = { Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Inverter Eixo X") },
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = config.invertY,
                    onClick = { onUpdateConfig { it.copy(invertY = !it.invertY) } },
                    leadingIcon = { Icon(Icons.Default.SwapVert, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    label = { Text("Inverter Eixo Y") },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ajuste Fino da Área Ativa (%)", style = MaterialTheme.typography.titleMedium)

            AreaSliderRow("Largura", config.activeArea.widthNorm, 0.2f..1f) { w ->
                onUpdateConfig {
                    val clampedL = it.activeArea.leftNorm.coerceIn(0f, 1f - w)
                    it.copy(activeArea = it.activeArea.copy(widthNorm = w, leftNorm = clampedL))
                }
            }
            AreaSliderRow("Altura", config.activeArea.heightNorm, 0.2f..1f) { h ->
                onUpdateConfig {
                    val clampedT = it.activeArea.topNorm.coerceIn(0f, 1f - h)
                    it.copy(activeArea = it.activeArea.copy(heightNorm = h, topNorm = clampedT))
                }
            }
            val maxOffsetX = (1f - config.activeArea.widthNorm).coerceAtLeast(0f)
            val maxOffsetY = (1f - config.activeArea.heightNorm).coerceAtLeast(0f)
            AreaSliderRow(
                label = "Deslocamento X",
                value = config.activeArea.leftNorm,
                range = 0f..maxOffsetX.coerceAtLeast(0.001f),
                enabled = maxOffsetX > 0.005f
            ) { x ->
                onUpdateConfig {
                    val mx = (1f - it.activeArea.widthNorm).coerceAtLeast(0f)
                    it.copy(activeArea = it.activeArea.copy(leftNorm = x.coerceIn(0f, mx)))
                }
            }
            AreaSliderRow(
                label = "Deslocamento Y",
                value = config.activeArea.topNorm,
                range = 0f..maxOffsetY.coerceAtLeast(0.001f),
                enabled = maxOffsetY > 0.005f
            ) { y ->
                onUpdateConfig {
                    val my = (1f - it.activeArea.heightNorm).coerceAtLeast(0f)
                    it.copy(activeArea = it.activeArea.copy(topNorm = y.coerceIn(0f, my)))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Travar proporção da tela", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = config.lockAspectRatio,
                    onCheckedChange = { locked -> onUpdateConfig { it.copy(lockAspectRatio = locked) } }
                )
            }

            Button(
                onClick = onOpenFullCalibration,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("open_fullscreen_calibration_button")
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Calibração Guiada Tela Grande (4 Cantos + Centro)")
            }

            OutlinedButton(
                onClick = onResetTab,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("reset_tab_tablet_button")
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Resetar Aba Tablet")
            }
        }
    }
}

@Composable
private fun AreaSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit
) {
    val safeRange = if (range.endInclusive > range.start) range else range.start..(range.start + 0.001f)
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = String.format(Locale.US, "%.0f%%", value * 100f),
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value.coerceIn(safeRange.start, safeRange.endInclusive),
            onValueChange = onValueChange,
            enabled = enabled,
            valueRange = safeRange
        )
    }
}

private fun matchesCurvePreset(current: List<PressureControlPoint>, preset: List<PressureControlPoint>): Boolean {
    if (current.size != preset.size) return false
    return current.indices.all { i ->
        kotlin.math.abs(current[i].inputNorm - preset[i].inputNorm) < 0.025f &&
            kotlin.math.abs(current[i].outputNorm - preset[i].outputNorm) < 0.025f
    }
}

@Composable
private fun TabletAreaLivePenDotCanvas(
    profile: TabletProfileJson?,
    telemetryFlow: StateFlow<DriverTelemetry>
) {
    val penPos by remember(telemetryFlow) {
        telemetryFlow
            .map { Triple(it.isPenInRange || it.isTipDown, it.lastRawX, it.lastRawY) }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = Triple(false, 0, 0))

    Canvas(modifier = Modifier.fillMaxSize()) {
        val (visible, rawX, rawY) = penPos
        if (visible && profile != null && profile.maxX > 0 && profile.maxY > 0) {
            val px = (rawX.toFloat() / profile.maxX).coerceIn(0f, 1f) * size.width
            val py = (rawY.toFloat() / profile.maxY).coerceIn(0f, 1f) * size.height
            drawCircle(color = EmeraldActive.copy(alpha = 0.30f), radius = 14f, center = Offset(px, py))
            drawCircle(color = EmeraldActive, radius = 6f, center = Offset(px, py))
        }
    }
}

@Composable
private fun PenPressureTab(
    config: TabletConfig,
    telemetryFlow: StateFlow<DriverTelemetry>,
    maxPressure: Int,
    onUpdateConfig: ((TabletConfig) -> TabletConfig) -> Unit,
    onApplyPreset: (List<PressureControlPoint>, String) -> Unit,
    onUpdatePressurePoint: (Int, Float, Float) -> Unit,
    onResetTab: () -> Unit
) {
    var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }
    var activeDragIndex by remember { mutableIntStateOf(-1) }
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val outline = MaterialTheme.colorScheme.outline
    val dashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(12f, 10f)) }

    val sortedPts = remember(config.pressureCurve) { config.pressureCurve.sortedBy { it.inputNorm } }
    val precomputedSplineY = remember(sortedPts) {
        if (sortedPts.size >= 2) {
            FloatArray(65) { s -> evalSplinePreview(sortedPts, s / 64f) }
        } else {
            FloatArray(0)
        }
    }

    val linearPreset = remember {
        listOf(
            PressureControlPoint(0f, 0f),
            PressureControlPoint(0.25f, 0.25f),
            PressureControlPoint(0.50f, 0.50f),
            PressureControlPoint(0.75f, 0.75f),
            PressureControlPoint(1f, 1f)
        )
    }
    val softPreset = remember {
        listOf(
            PressureControlPoint(0f, 0f),
            PressureControlPoint(0.20f, 0.42f),
            PressureControlPoint(0.45f, 0.72f),
            PressureControlPoint(0.75f, 0.90f),
            PressureControlPoint(1f, 1f)
        )
    }
    val hardPreset = remember {
        listOf(
            PressureControlPoint(0f, 0f),
            PressureControlPoint(0.30f, 0.12f),
            PressureControlPoint(0.55f, 0.34f),
            PressureControlPoint(0.80f, 0.68f),
            PressureControlPoint(1f, 1f)
        )
    }
    val sCurvePreset = remember {
        listOf(
            PressureControlPoint(0f, 0f),
            PressureControlPoint(0.25f, 0.14f),
            PressureControlPoint(0.50f, 0.50f),
            PressureControlPoint(0.75f, 0.88f),
            PressureControlPoint(1f, 1f)
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, outline, MaterialTheme.shapes.large)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Gráfico de Curva de Pressão (OTD)", style = MaterialTheme.typography.titleMedium)
                    PenPressureLiveSubtitle(maxPressure = maxPressure, telemetryFlow = telemetryFlow)
                }
                Icon(Icons.Default.Brush, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = matchesCurvePreset(config.pressureCurve, linearPreset),
                    onClick = { onApplyPreset(linearPreset, "Linear 1:1") },
                    label = { Text("Linear") }
                )
                FilterChip(
                    selected = matchesCurvePreset(config.pressureCurve, softPreset),
                    onClick = { onApplyPreset(softPreset, "Suave (Soft)") },
                    label = { Text("Suave") }
                )
                FilterChip(
                    selected = matchesCurvePreset(config.pressureCurve, hardPreset),
                    onClick = { onApplyPreset(hardPreset, "Firme (Hard)") },
                    label = { Text("Firme") }
                )
                FilterChip(
                    selected = matchesCurvePreset(config.pressureCurve, sCurvePreset),
                    onClick = { onApplyPreset(sCurvePreset, "Curva S (Precisão)") },
                    label = { Text("Curva S") }
                )
            }

            val currentSortedCurve by rememberUpdatedState(sortedPts)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(Color(0xFF0B0D18))
                    .border(1.dp, outline, MaterialTheme.shapes.medium)
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { pos ->
                                val pts = currentSortedCurve
                                val w = canvasSize.width.coerceAtLeast(1).toFloat()
                                val h = canvasSize.height.coerceAtLeast(1).toFloat()
                                var bestIdx = -1
                                var bestDist = 110f
                                pts.forEachIndexed { idx, pt ->
                                    val px = pt.inputNorm * w
                                    val py = (1f - pt.outputNorm) * h
                                    val d = hypot(pos.x - px, pos.y - py)
                                    if (d < bestDist) {
                                        bestDist = d
                                        bestIdx = idx
                                    }
                                }
                                activeDragIndex = bestIdx
                            },
                            onDrag = { change, _ ->
                                val pts = currentSortedCurve
                                if (activeDragIndex in pts.indices) {
                                    val w = canvasSize.width.coerceAtLeast(1).toFloat()
                                    val h = canvasSize.height.coerceAtLeast(1).toFloat()
                                    val nx = (change.position.x / w).coerceIn(0f, 1f)
                                    val ny = (1f - change.position.y / h).coerceIn(0f, 1f)
                                    onUpdatePressurePoint(activeDragIndex, nx, ny)
                                }
                                change.consume()
                            },
                            onDragEnd = { activeDragIndex = -1 },
                            onDragCancel = { activeDragIndex = -1 }
                        )
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    for (i in 1..3) {
                        val gx = size.width * (i / 4f)
                        val gy = size.height * (i / 4f)
                        drawLine(outline.copy(alpha = 0.28f), Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1.5f)
                        drawLine(outline.copy(alpha = 0.28f), Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1.5f)
                    }
                    drawLine(
                        color = outline.copy(alpha = 0.55f),
                        start = Offset(0f, size.height),
                        end = Offset(size.width, 0f),
                        strokeWidth = 2f,
                        pathEffect = dashEffect
                    )

                    if (sortedPts.size >= 2 && precomputedSplineY.size == 65) {
                        val strokePath = Path()
                        val fillPath = Path()
                        for (s in 0..64) {
                            val xNorm = s / 64f
                            val yNorm = precomputedSplineY[s]
                            val px = xNorm * size.width
                            val py = (1f - yNorm) * size.height
                            if (s == 0) {
                                strokePath.moveTo(px, py)
                                fillPath.moveTo(px, size.height)
                                fillPath.lineTo(px, py)
                            } else {
                                strokePath.lineTo(px, py)
                                fillPath.lineTo(px, py)
                            }
                        }
                        fillPath.lineTo(size.width, size.height)
                        fillPath.close()

                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                listOf(primary.copy(alpha = 0.36f), primary.copy(alpha = 0.02f))
                            )
                        )
                        drawPath(path = strokePath, color = primary, style = Stroke(width = 6f))

                        sortedPts.forEachIndexed { idx, p ->
                            val x = p.inputNorm * size.width
                            val y = (1f - p.outputNorm) * size.height
                            val isDragged = idx == activeDragIndex
                            drawCircle(
                                color = (if (isDragged) EmeraldActive else primary).copy(alpha = 0.26f),
                                radius = if (isDragged) 26f else 20f,
                                center = Offset(x, y)
                            )
                            drawCircle(
                                color = if (isDragged) EmeraldActive else Color.White,
                                radius = if (isDragged) 18f else 14f,
                                center = Offset(x, y)
                            )
                            drawCircle(
                                color = if (isDragged) EmeraldActive else secondary,
                                radius = if (isDragged) 11f else 8f,
                                center = Offset(x, y)
                            )
                        }
                    }
                }
                PenPressureLiveDotCanvas(telemetryFlow = telemetryFlow)

                // Y-axis subtle tick labels
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("100%", style = MaterialTheme.typography.labelSmall, color = outline)
                    Text("50%", style = MaterialTheme.typography.labelSmall, color = outline)
                    Text("0%", style = MaterialTheme.typography.labelSmall, color = outline)
                }

                if (activeDragIndex in sortedPts.indices) {
                    val activePt = sortedPts[activeDragIndex]
                    Surface(
                        color = Color(0xCC121426),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                    ) {
                        Text(
                            text = "Ponto ${activeDragIndex + 1}: In ${(activePt.inputNorm * 100).toInt()}% → Out ${(activePt.outputNorm * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldActive,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("0% Leve", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Arraste os 5 pontos · Curva Catmull-Rom", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text("100% Forte", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            AreaSliderRow("Limiar Mínimo de Ponta", config.minPressureThreshold, 0f..0.4f) { v ->
                onUpdateConfig { it.copy(minPressureThreshold = v) }
            }
            AreaSliderRow("Limite Máximo de Pressão", config.maxPressureCap, 0.3f..1f) { v ->
                onUpdateConfig { it.copy(maxPressureCap = v) }
            }

            OutlinedButton(
                onClick = onResetTab,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("reset_tab_pen_button")
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Resetar Aba Pen")
            }
        }
    }
}

private fun evalSplinePreview(pts: List<PressureControlPoint>, x: Float): Float {
    if (x <= pts.first().inputNorm) return pts.first().outputNorm
    if (x >= pts.last().inputNorm) return pts.last().outputNorm
    var seg = 0
    for (i in 0 until pts.size - 1) {
        if (x >= pts[i].inputNorm && x <= pts[i + 1].inputNorm) {
            seg = i
            break
        }
    }
    val p0 = pts[(seg - 1).coerceAtLeast(0)]
    val p1 = pts[seg]
    val p2 = pts[seg + 1]
    val p3 = pts[(seg + 2).coerceAtMost(pts.size - 1)]
    val span = (p2.inputNorm - p1.inputNorm).coerceAtLeast(1e-5f)
    val t = ((x - p1.inputNorm) / span).coerceIn(0f, 1f)
    val t2 = t * t
    val t3 = t2 * t
    val y = 0.5f * (
        (2f * p1.outputNorm) +
            (-p0.outputNorm + p2.outputNorm) * t +
            (2f * p0.outputNorm - 5f * p1.outputNorm + 4f * p2.outputNorm - p3.outputNorm) * t2 +
            (-p0.outputNorm + 3f * p1.outputNorm - 3f * p2.outputNorm + p3.outputNorm) * t3
        )
    return y.coerceIn(0f, 1f)
}

@Composable
private fun PenPressureLiveSubtitle(
    maxPressure: Int,
    telemetryFlow: StateFlow<DriverTelemetry>
) {
    val pct by remember(telemetryFlow) {
        telemetryFlow.map { (it.lastCurvedPressure * 100f).toInt() }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = (telemetryFlow.value.lastCurvedPressure * 100f).toInt())
    Text(
        text = "${maxPressure + 1} níveis nativos · Pressão ao vivo: $pct%",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun PenPressureLiveDotCanvas(
    telemetryFlow: StateFlow<DriverTelemetry>
) {
    val livePressure by remember(telemetryFlow) {
        telemetryFlow
            .map {
                val rawNorm = if (it.maxNativePressure > 0) {
                    (it.lastRawPressure.toFloat() / it.maxNativePressure).coerceIn(0f, 1f)
                } else 0f
                rawNorm to it.lastCurvedPressure.coerceIn(0f, 1f)
            }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = 0f to 0f)

    Canvas(modifier = Modifier.fillMaxSize()) {
        val (rawNorm, curved) = livePressure
        if (rawNorm > 0f) {
            val lx = rawNorm * size.width
            val ly = (1f - curved) * size.height
            drawCircle(color = EmeraldActive.copy(alpha = 0.25f), radius = 24f, center = Offset(lx, ly))
            drawCircle(color = EmeraldActive, radius = 9f, center = Offset(lx, ly))
        }
    }
}
