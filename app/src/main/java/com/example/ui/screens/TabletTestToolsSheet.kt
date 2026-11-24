package com.example.ui.screens

import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.driver.DriverTelemetry
import com.example.driver.HidHexFrame
import com.example.ui.theme.EmeraldActive
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabletTestToolsSheet(
    telemetryFlow: StateFlow<DriverTelemetry>,
    hexFramesFlow: StateFlow<List<HidHexFrame>>,
    onFeedTestPacket: (normX: Float, normY: Float, pressure: Float, isDown: Boolean) -> Unit,
    @Suppress("UNUSED_PARAMETER") onOpenFullScreenBlankSheet: () -> Unit = {},
    @Suppress("UNUSED_PARAMETER") onOpenFullScreenCalibration: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedToolTab by remember { mutableIntStateOf(0) }
    val toolTabs = listOf(
        "Hex HID" to Icons.Default.DataObject,
        "Latência" to Icons.Default.Speed,
        "Telemetria" to Icons.Default.Insights
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Diagnóstico HID & Precisão",
                    style = MaterialTheme.typography.titleLarge
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Fechar ferramentas")
                }
            }

            PrimaryTabRow(selectedTabIndex = selectedToolTab) {
                toolTabs.forEachIndexed { idx, (label, icon) ->
                    Tab(
                        selected = selectedToolTab == idx,
                        onClick = { selectedToolTab = idx },
                        icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
                        text = { Text(label, style = MaterialTheme.typography.labelLarge) }
                    )
                }
            }

            when (selectedToolTab) {
                0 -> {
                    val hexFrames by hexFramesFlow.collectAsStateWithLifecycle()
                    HexDumpMonitorTool(hexFrames = hexFrames, onFeedTestPacket = onFeedTestPacket)
                }
                1 -> {
                    val telemetry by telemetryFlow.collectAsStateWithLifecycle()
                    LatencyMeterTool(telemetry = telemetry, onFeedTestPacket = onFeedTestPacket)
                }
                2 -> {
                    val telemetry by telemetryFlow.collectAsStateWithLifecycle()
                    LiveTelemetryTab(telemetry = telemetry, onFeedTestPacket = onFeedTestPacket)
                }
            }
        }
    }
}

@Composable
private fun LiveTelemetryTab(
    telemetry: DriverTelemetry,
    onFeedTestPacket: (normX: Float, normY: Float, pressure: Float, isDown: Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricRow("Coordenadas Raw da Mesa", "${telemetry.lastRawX} × ${telemetry.lastRawY}")
        MetricRow(
            "Posição Projetada na Tela",
            String.format(Locale.US, "%.1f × %.1f px", telemetry.lastScreenX, telemetry.lastScreenY)
        )
        MetricRow(
            "Pressão (Raw / Curvada)",
            "${telemetry.lastRawPressure} / ${telemetry.maxNativePressure} (${(telemetry.lastCurvedPressure * 100f).toInt()}%)"
        )
        MetricRow(
            "Inclinação (Tilt X / Y)",
            String.format(Locale.US, "%.1f° / %.1f°", telemetry.lastTiltX, telemetry.lastTiltY)
        )
        MetricRow(
            "Estado da Caneta",
            when {
                telemetry.isTipDown -> "Ponta Pressionada (TIP DOWN)"
                telemetry.isPenInRange -> "Campo Magnético (HOVER)"
                else -> "Aguardando Caneta"
            },
            highlight = telemetry.isTipDown || telemetry.isPenInRange
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                onClick = { onFeedTestPacket(0.50f, 0.50f, 0f, false) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text("Simular Hover", maxLines = 1)
            }
            Button(
                onClick = { onFeedTestPacket(0.50f, 0.50f, 0.80f, true) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text("Simular Pressão 80%", maxLines = 1)
            }
        }
    }
}

@Composable
private fun HexDumpMonitorTool(
    hexFrames: List<HidHexFrame>,
    onFeedTestPacket: (normX: Float, normY: Float, pressure: Float, isDown: Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Pacotes HID Brutos (Zero-Alloc Ring)",
                style = MaterialTheme.typography.titleMedium
            )
            FilledTonalButton(
                onClick = { onFeedTestPacket(0.32f, 0.64f, 0.5f, true) },
                modifier = Modifier
                    .height(48.dp)
                    .testTag("send_ugee_test_packet_button")
            ) {
                Text("Injetar Quadro")
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.background,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
        ) {
            if (hexFrames.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Nenhum pacote HID capturado ainda.\nConecte a mesa ou toque em 'Injetar Quadro'.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(hexFrames) { frame ->
                        Column {
                            Text(
                                text = "[${frame.length}B · ${frame.sourceLabel}] ${frame.hexString}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LatencyMeterTool(
    telemetry: DriverTelemetry,
    onFeedTestPacket: (normX: Float, normY: Float, pressure: Float, isDown: Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Speed, contentDescription = null, tint = EmeraldActive)
            Text("Medidor de Latência do Pipeline", style = MaterialTheme.typography.titleMedium)
        }

        MetricRow("Taxa de Polling Real", "${telemetry.pollingRateHz} Hz")
        MetricRow("Parser Zero-Alloc", String.format(Locale.US, "%.2f µs", telemetry.parseLatencyUs))
        MetricRow("Matriz Afim 2x3 + LUT", String.format(Locale.US, "%.2f µs", telemetry.matrixLatencyUs))
        MetricRow("Despacho IPC / Lote", String.format(Locale.US, "%.2f µs", telemetry.injectLatencyUs))
        MetricRow(
            "Latência Total de Pipeline",
            String.format(Locale.US, "%.3f ms (Meta: < 16 ms)", telemetry.totalLatencyMs),
            highlight = true
        )
        MetricRow("Pacotes Processados / Perdidos", "${telemetry.totalPackets} / ${telemetry.droppedPackets}")

        Button(
            onClick = {
                repeat(12) { idx ->
                    onFeedTestPacket(0.1f + idx * 0.07f, 0.4f + idx * 0.03f, 0.6f, true)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Executar Rajada de Benchmark (12 pacotes)")
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String, highlight: Boolean = false) {
    Surface(
        color = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer else Color.Unspecified
            )
        }
    }
}
