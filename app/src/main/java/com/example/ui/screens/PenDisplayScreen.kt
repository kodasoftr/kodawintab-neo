package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.hub.InstalledAppEntry
import com.example.pendisplay.DEFAULT_PEN_DISPLAY_PRESETS
import com.example.pendisplay.KodaStreamTelemetry
import com.example.pendisplay.PenDisplayPreset
import com.example.pendisplay.PenDisplayState
import com.example.pendisplay.PenDisplayTaskEntry
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.WintabViolet
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PenDisplayScreen(
    penDisplayState: PenDisplayState,
    streamTelemetry: KodaStreamTelemetry,
    installedApps: List<InstalledAppEntry>,
    boundPackages: Set<String>,
    activeTasks: List<PenDisplayTaskEntry>,
    engineIsActive: Boolean,
    onBack: () -> Unit,
    onToggleConnected: (Boolean) -> Unit,
    onSelectPreset: (PenDisplayPreset) -> Unit,
    onStartVirtualDesktop: () -> Unit,
    onStopVirtualDesktop: () -> Unit,
    onToggleBoundApp: (String) -> Unit,
    onLaunchAppOnPenDisplay: (InstalledAppEntry) -> Unit,
    onRemoveTask: (String) -> Unit,
    onSendTestStylus: (Float, Float, Float, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler { onBack() }

    var appSearchQuery by remember { mutableStateOf("") }
    val filteredApps = remember(installedApps, appSearchQuery, boundPackages) {
        val base = if (appSearchQuery.isBlank()) {
            installedApps
        } else {
            val q = appSearchQuery.trim().lowercase(Locale.ROOT)
            installedApps.filter {
                it.label.lowercase(Locale.ROOT).contains(q) ||
                    it.packageName.lowercase(Locale.ROOT).contains(q)
            }
        }
        base.sortedWith(compareByDescending<InstalledAppEntry> { it.packageName in boundPackages }.thenBy { it.label })
            .take(18)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Pen Display · Kodasoftr",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = if (penDisplayState.isVirtualDisplayActive) {
                                "Display Virtual #${penDisplayState.virtualDisplayId} · ${penDisplayState.selectedPreset.widthPx}×${penDisplayState.selectedPreset.heightPx}"
                            } else {
                                "Motor de Tela Virtual & Stream USB"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (penDisplayState.isVirtualDisplayActive) EmeraldActive else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("pendisplay_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Hardware Auto-Detection & Master Switch Card
                Card(
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.5.dp,
                            if (penDisplayState.isPenDisplayConnected) EmeraldActive.copy(alpha = 0.6f)
                            else MaterialTheme.colorScheme.outline,
                            MaterialTheme.shapes.extraLarge
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(46.dp)
                                        .clip(MaterialTheme.shapes.medium)
                                        .background(
                                            (if (penDisplayState.isPenDisplayConnected) EmeraldActive else ElectricCyan)
                                                .copy(alpha = 0.16f)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DesktopWindows,
                                        contentDescription = "Mesa com Tela Conectada",
                                        tint = if (penDisplayState.isPenDisplayConnected) EmeraldActive else ElectricCyan
                                    )
                                }
                                Column {
                                    Text(
                                        text = "Mesa com Tela Conectada",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = penDisplayState.autoDetectedHardwareName
                                            ?: "Detecção automática USB VID/PID (Wacom, Huion, XP-Pen, Ugee)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = penDisplayState.isPenDisplayConnected,
                                onCheckedChange = onToggleConnected,
                                modifier = Modifier.testTag("pendisplay_connected_switch")
                            )
                        }

                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Usb,
                                    contentDescription = null,
                                    tint = if (penDisplayState.autoDetectedVidPid != null) EmeraldActive else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = buildString {
                                        append(penDisplayState.statusMessage)
                                        penDisplayState.autoDetectedVidPid?.let { append(" · VID:PID $it") }
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        // Resolution & DPI Presets
                        Text(
                            text = "Resolução & DPI Nativo da Pen Display",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DEFAULT_PEN_DISPLAY_PRESETS.forEach { preset ->
                                val selected = penDisplayState.selectedPreset.widthPx == preset.widthPx &&
                                    penDisplayState.selectedPreset.heightPx == preset.heightPx &&
                                    penDisplayState.selectedPreset.dpi == preset.dpi
                                FilterChip(
                                    selected = selected,
                                    onClick = { onSelectPreset(preset) },
                                    label = { Text(preset.label) },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("pendisplay_preset_${preset.id}")
                                )
                            }
                        }

                        Button(
                            onClick = {
                                if (penDisplayState.isVirtualDisplayActive) onStopVirtualDesktop()
                                else onStartVirtualDesktop()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (penDisplayState.isVirtualDisplayActive) AmberAccent else ElectricCyan,
                                contentColor = Color(0xFF07131A)
                            ),
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("pendisplay_toggle_virtual_button")
                        ) {
                            Icon(
                                imageVector = if (penDisplayState.isVirtualDisplayActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                                contentDescription = null
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (penDisplayState.isVirtualDisplayActive) {
                                    "Encerrar Display Virtual #${penDisplayState.virtualDisplayId}"
                                } else {
                                    "Projetar Área Kodasoftr no Display Virtual"
                                },
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // 2. Kodasoftr Desktop Live Preview + WinTab 8192 Stylus Canvas
                Card(
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, ElectricCyan.copy(alpha = 0.45f), MaterialTheme.shapes.extraLarge)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Draw, contentDescription = null, tint = ElectricCyan)
                                Text(
                                    text = "Área Kodasoftr & Caneta WinTab 8192",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = (if (penDisplayState.isKodasoftrProjected) EmeraldActive else WintabViolet).copy(alpha = 0.16f)
                            ) {
                                Text(
                                    text = if (penDisplayState.isKodasoftrProjected) {
                                        "displayId #${penDisplayState.virtualDisplayId}"
                                    } else {
                                        "Prévia Interativa"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (penDisplayState.isKodasoftrProjected) EmeraldActive else WintabViolet,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        // 16:9 Kodasoftr Interactive Preview Surface
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(16.dp))
                                .border(1.5.dp, ElectricCyan.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFF0B1120), Color(0xFF151836), Color(0xFF0A1B2A))
                                    )
                                )
                                .pointerInput(Unit) {
                                    detectTapGestures { pos ->
                                        val nx = (pos.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                        val ny = (pos.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                                        onSendTestStylus(nx, ny, 0.72f, true)
                                    }
                                }
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDrag = { change, _ ->
                                            change.consume()
                                            val nx = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                            val ny = (change.position.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                                            val simPressure = (0.35f + 0.6f * (1f - ny)).coerceIn(0.1f, 1f)
                                            onSendTestStylus(nx, ny, simPressure, true)
                                        },
                                        onDragEnd = {
                                            onSendTestStylus(
                                                penDisplayState.lastStylusNormX,
                                                penDisplayState.lastStylusNormY,
                                                0f,
                                                false
                                            )
                                        }
                                    )
                                }
                                .testTag("pendisplay_kodasoftr_preview")
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val step = size.width / 12f
                                var x = step
                                while (x < size.width) {
                                    drawLine(
                                        color = Color.White.copy(alpha = 0.06f),
                                        start = Offset(x, 0f),
                                        end = Offset(x, size.height),
                                        strokeWidth = 1f
                                    )
                                    x += step
                                }
                                var y = step
                                while (y < size.height) {
                                    drawLine(
                                        color = Color.White.copy(alpha = 0.06f),
                                        start = Offset(0f, y),
                                        end = Offset(size.width, y),
                                        strokeWidth = 1f
                                    )
                                    y += step
                                }

                                // Crosshair for live stylus input
                                val cx = penDisplayState.lastStylusNormX * size.width
                                val cy = penDisplayState.lastStylusNormY * size.height
                                val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                                drawLine(
                                    color = ElectricCyan.copy(alpha = 0.45f),
                                    start = Offset(cx, 0f),
                                    end = Offset(cx, size.height),
                                    strokeWidth = 1.5f,
                                    pathEffect = dash
                                )
                                drawLine(
                                    color = ElectricCyan.copy(alpha = 0.45f),
                                    start = Offset(0f, cy),
                                    end = Offset(size.width, cy),
                                    strokeWidth = 1.5f,
                                    pathEffect = dash
                                )
                                val radius = 8f + (penDisplayState.lastStylusPressure * 14f)
                                drawCircle(
                                    color = if (penDisplayState.lastStylusDown) EmeraldActive else ElectricCyan,
                                    radius = radius,
                                    center = Offset(cx, cy)
                                )
                            }

                            // Top badge inside preview
                            Row(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xCC0D1322)
                                ) {
                                    Text(
                                        text = "KODASOFTR DESKTOP · ${penDisplayState.selectedPreset.widthPx}×${penDisplayState.selectedPreset.heightPx}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = ElectricCyan,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            // Simulated Kodasoftr Taskbar at bottom
                            Surface(
                                color = Color(0xD90E1324),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .height(32.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            color = ElectricCyan.copy(alpha = 0.22f),
                                            shape = RoundedCornerShape(6.dp)
                                        ) {
                                            Text(
                                                text = "Kodasoftr Apps (${boundPackages.size})",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = ElectricCyan,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                        if (activeTasks.isNotEmpty()) {
                                            Text(
                                                text = activeTasks.take(2).joinToString(" · ") { it.label },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color.White.copy(alpha = 0.85f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    Text(
                                        text = if (penDisplayState.isVirtualDisplayActive) "ONLINE" else "STANDBY",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (penDisplayState.isVirtualDisplayActive) EmeraldActive else AmberAccent
                                    )
                                }
                            }
                        }

                        // Live Stylus Pressure Readout (8192 levels)
                        val level8192 = (penDisplayState.lastStylusPressure * 8191f).roundToInt()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "Caneta X=%.2f Y=%.2f · Pressão %d/8191",
                                    penDisplayState.lastStylusNormX,
                                    penDisplayState.lastStylusNormY,
                                    level8192
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FilledTonalButton(
                                onClick = { onSendTestStylus(0.5f, 0.5f, 0.85f, true) },
                                modifier = Modifier
                                    .height(48.dp)
                                    .testTag("pendisplay_test_stylus_button")
                            ) {
                                Text("Testar Traço")
                            }
                        }
                        LinearProgressIndicator(
                            progress = { penDisplayState.lastStylusPressure.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape),
                            color = EmeraldActive,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }

                // 3. Koda Stream Engine Telemetry Card
                Card(
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraLarge)
                        .testTag("pendisplay_stream_card")
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.CastConnected, contentDescription = null, tint = WintabViolet)
                                Text(
                                    text = "Koda Stream Engine (GPU MediaCodec + USB Bulk)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Text(
                                text = "${streamTelemetry.fps} FPS",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (streamTelemetry.isStreaming) EmeraldActive else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            StreamMetricPill(
                                label = "Codec GPU",
                                value = streamTelemetry.codecLabel,
                                modifier = Modifier.weight(1f)
                            )
                            StreamMetricPill(
                                label = "USB BulkOut",
                                value = if (streamTelemetry.usbBulkTransportActive) {
                                    "${streamTelemetry.usbPacketsSent} pct"
                                } else {
                                    "Aguardando EP"
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            StreamMetricPill(
                                label = "Quadros",
                                value = "${streamTelemetry.encodedFrames} fr",
                                modifier = Modifier.weight(1f)
                            )
                            StreamMetricPill(
                                label = "Latência Encode",
                                value = String.format(Locale.US, "%.2f ms", streamTelemetry.encodeLatencyMs),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // 4. Koda App Router & Native Multitasking
                Card(
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.extraLarge)
                        .testTag("pendisplay_app_router_section")
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Default.Apps, contentDescription = null, tint = ElectricCyan)
                                Column {
                                    Text(
                                        text = "Koda App Router & Multitarefa",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (engineIsActive) {
                                            "TaskStackListener ativo · redireciona automaticamente no lançamento"
                                        } else {
                                            "Modo Android Direto · lança no displayId via ActivityOptions"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        if (activeTasks.isNotEmpty()) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.testTag("pendisplay_active_tasks_section")
                            ) {
                                Text(
                                    text = "Tarefas Abertas na Pen Display (Apps Recentes)",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = EmeraldActive
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    activeTasks.forEach { task ->
                                        Surface(
                                            shape = CircleShape,
                                            color = EmeraldActive.copy(alpha = 0.14f),
                                            modifier = Modifier.border(1.dp, EmeraldActive.copy(alpha = 0.5f), CircleShape)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
                                            ) {
                                                Text(
                                                    text = "${task.label} (#${task.displayId})",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = EmeraldActive
                                                )
                                                IconButton(
                                                    onClick = { onRemoveTask(task.packageName) },
                                                    modifier = Modifier
                                                        .size(36.dp)
                                                        .testTag("pendisplay_close_task_${task.packageName}")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Close,
                                                        contentDescription = "Remover tarefa ${task.label}",
                                                        tint = EmeraldActive,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                        }

                        OutlinedTextField(
                            value = appSearchQuery,
                            onValueChange = { appSearchQuery = it },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            placeholder = { Text("Filtrar apps (Krita, Clip Studio, ibis Paint...)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pendisplay_app_search")
                        )

                        if (filteredApps.isEmpty()) {
                            Text(
                                text = "Nenhum aplicativo encontrado para o filtro atual.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                filteredApps.forEach { app ->
                                    val isBound = app.packageName in boundPackages
                                    Surface(
                                        color = if (isBound) ElectricCyan.copy(alpha = 0.10f)
                                        else MaterialTheme.colorScheme.surfaceVariant,
                                        shape = MaterialTheme.shapes.medium,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(
                                                1.dp,
                                                if (isBound) ElectricCyan.copy(alpha = 0.55f)
                                                else Color.Transparent,
                                                MaterialTheme.shapes.medium
                                            )
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = app.label,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = app.packageName,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                FilterChip(
                                                    selected = isBound,
                                                    onClick = { onToggleBoundApp(app.packageName) },
                                                    leadingIcon = if (isBound) {
                                                        {
                                                            Icon(
                                                                Icons.Default.CheckCircle,
                                                                contentDescription = null,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        }
                                                    } else null,
                                                    label = { Text(if (isBound) "Vinculado" else "Vincular") },
                                                    modifier = Modifier
                                                        .height(48.dp)
                                                        .testTag("pendisplay_bind_app_${app.packageName}")
                                                )
                                                IconButton(
                                                    onClick = { onLaunchAppOnPenDisplay(app) },
                                                    modifier = Modifier
                                                        .size(48.dp)
                                                        .testTag("pendisplay_launch_app_${app.packageName}")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.AutoMirrored.Filled.Launch,
                                                        contentDescription = "Abrir ${app.label} na Pen Display",
                                                        tint = ElectricCyan
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun StreamMetricPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
