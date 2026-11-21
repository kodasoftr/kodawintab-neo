package com.example.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.display.TargetDisplayInfo
import com.example.driver.DEFAULT_MANUAL_DPI_PRESETS
import com.example.driver.DpiNormalizationMode
import com.example.driver.DriverTelemetry
import com.example.driver.FilterSettings
import com.example.driver.KeyBindingCatalog
import com.example.driver.OutputMode
import com.example.driver.TabletConfig
import com.example.driver.TabletProfileJson
import com.example.hub.InstalledAppEntry
import com.example.ui.theme.EmeraldActive
import java.util.Locale

private data class BindingEditTarget(
    val title: String,
    val currentValue: String,
    val onApply: (String) -> Unit
)

@Composable
fun BindingsTab(
    config: TabletConfig,
    profileName: String,
    expressCount: Int,
    telemetry: DriverTelemetry,
    onUpdateConfig: ((TabletConfig) -> TabletConfig) -> Unit,
    onResetTab: () -> Unit = {}
) {
    var editingTarget by remember { mutableStateOf<BindingEditTarget?>(null) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Atalhos Manuais · $profileName", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Toque em qualquer botão para escolher tecla ou atalho manualmente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(Icons.Default.Keyboard, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }

            if (telemetry.lastTriggeredShortcut.isNotBlank()) {
                Surface(
                    color = EmeraldActive.copy(alpha = 0.14f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Último atalho disparado: ${telemetry.lastTriggeredShortcut}",
                        style = MaterialTheme.typography.labelMedium,
                        color = EmeraldActive,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )
                }
            }

            Text("Botões da Caneta", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            BindingPickRow(
                label = "Botão Lateral 1",
                currentValue = config.bindings.button1Action,
                tag = "binding_pen_button_1"
            ) {
                editingTarget = BindingEditTarget(
                    title = "Botão Lateral 1 da Caneta",
                    currentValue = config.bindings.button1Action
                ) { chosen ->
                    onUpdateConfig { it.copy(bindings = it.bindings.copy(button1Action = chosen)) }
                }
            }
            BindingPickRow(
                label = "Botão Lateral 2",
                currentValue = config.bindings.button2Action,
                tag = "binding_pen_button_2"
            ) {
                editingTarget = BindingEditTarget(
                    title = "Botão Lateral 2 da Caneta",
                    currentValue = config.bindings.button2Action
                ) { chosen ->
                    onUpdateConfig { it.copy(bindings = it.bindings.copy(button2Action = chosen)) }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "Express Keys da Mesa ($expressCount)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            val count = expressCount.coerceIn(4, 12)
            for (k in 0 until count) {
                val current = config.bindings.expressKeyActions[k] ?: "Desativado"
                BindingPickRow(
                    label = "Tecla Express ${k + 1}",
                    currentValue = current,
                    tag = "binding_express_key_$k"
                ) {
                    editingTarget = BindingEditTarget(
                        title = "Tecla Express ${k + 1} ($profileName)",
                        currentValue = current
                    ) { chosen ->
                        val updatedMap = config.bindings.expressKeyActions.toMutableMap().apply { put(k, chosen) }
                        onUpdateConfig { it.copy(bindings = it.bindings.copy(expressKeyActions = updatedMap)) }
                    }
                }
            }
            OutlinedButton(
                onClick = onResetTab,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("reset_tab_bindings_button")
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Resetar Aba Bindings")
            }
        }
    }

    editingTarget?.let { target ->
        ManualShortcutPickerDialog(
            title = target.title,
            initialAction = target.currentValue,
            onConfirm = { selected ->
                target.onApply(selected)
                editingTarget = null
            },
            onDismiss = { editingTarget = null }
        )
    }
}

@Composable
private fun BindingPickRow(label: String, currentValue: String, tag: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(currentValue, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Icon(
                    Icons.Default.Keyboard,
                    contentDescription = "Escolher tecla",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualShortcutPickerDialog(
    title: String,
    initialAction: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var modCtrl by remember { mutableStateOf(initialAction.contains("Ctrl", ignoreCase = true)) }
    var modShift by remember { mutableStateOf(initialAction.contains("Shift", ignoreCase = true)) }
    var modAlt by remember { mutableStateOf(initialAction.contains("Alt", ignoreCase = true)) }
    var modSuper by remember { mutableStateOf(initialAction.contains("Super", ignoreCase = true)) }
    var selectedActionText by remember { mutableStateOf(initialAction) }
    var selectedCategory by remember { mutableStateOf("Letras") }
    val captureFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { captureFocus.requestFocus() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Live hardware keyboard capture box
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), MaterialTheme.shapes.medium)
                        .focusRequester(captureFocus)
                        .focusable()
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown) {
                                val native = keyEvent.nativeKeyEvent
                                val formatted = KeyBindingCatalog.formatFromKeyEvent(
                                    keyCode = native.keyCode,
                                    isCtrl = keyEvent.isCtrlPressed || modCtrl,
                                    isShift = keyEvent.isShiftPressed || modShift,
                                    isAlt = keyEvent.isAltPressed || modAlt,
                                    isMeta = keyEvent.isMetaPressed || modSuper
                                )
                                if (formatted != null) {
                                    selectedActionText = formatted
                                    return@onPreviewKeyEvent true
                                }
                            }
                            false
                        }
                        .clickable { runCatching { captureFocus.requestFocus() } }
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Atalho Selecionado: $selectedActionText",
                            style = MaterialTheme.typography.titleSmall,
                            color = EmeraldActive,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.testTag("shortcut_preview_text")
                        )
                        Text(
                            text = "Toque aqui e aperte qualquer tecla no teclado físico, ou escolha abaixo:",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Text("Modificadores", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilterChip(selected = modCtrl, onClick = { modCtrl = !modCtrl }, label = { Text("Ctrl") }, modifier = Modifier.weight(1f))
                    FilterChip(selected = modShift, onClick = { modShift = !modShift }, label = { Text("Shift") }, modifier = Modifier.weight(1f))
                    FilterChip(selected = modAlt, onClick = { modAlt = !modAlt }, label = { Text("Alt") }, modifier = Modifier.weight(1f))
                    FilterChip(selected = modSuper, onClick = { modSuper = !modSuper }, label = { Text("Win") }, modifier = Modifier.weight(1f))
                }

                Text("Teclado Virtual Completo", style = MaterialTheme.typography.labelMedium)
                val categories = listOf("Letras", "Números", "Função", "Especiais", "Símbolos", "Setas", "Navegação")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat) }
                        )
                    }
                }

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    KeyBindingCatalog.selectableKeys
                        .filter { it.category == selectedCategory }
                        .forEach { entry ->
                            OutlinedButton(
                                onClick = {
                                    selectedActionText = KeyBindingCatalog.formatCustomShortcut(
                                        ctrl = modCtrl,
                                        shift = modShift,
                                        alt = modAlt,
                                        meta = modSuper,
                                        keyLabel = entry.label
                                    )
                                },
                                modifier = Modifier.testTag("key_pick_${entry.label}")
                            ) {
                                Text(entry.label)
                            }
                        }
                }

                Text("Atalhos Rápidos & Mouse", style = MaterialTheme.typography.labelMedium)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    KeyBindingCatalog.quickPresets.forEach { preset ->
                        FilterChip(
                            selected = selectedActionText == preset,
                            onClick = { selectedActionText = preset },
                            label = { Text(preset) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedActionText) },
                modifier = Modifier.testTag("shortcut_confirm_button")
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Salvar Atalho")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}

@Composable
fun FiltersTab(
    config: TabletConfig,
    onUpdateConfig: ((TabletConfig) -> TabletConfig) -> Unit,
    onResetTab: () -> Unit = {}
) {
    val f = config.filters
    val allOff = !f.antiJitterEnabled &&
        !f.smoothingEnabled &&
        !f.oneEuroEnabled &&
        !f.tiltCompensationEnabled &&
        !f.hysteresisEnabled &&
        !f.predictionEnabled

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                color = if (allOff) EmeraldActive.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (allOff) {
                        "⚡ Modo Raw PC 2.0 Ativo — Latência mínima (micro-park 0.25ms) e fidelidade bruta."
                    } else {
                        "🎛️ Cadeia DSP 2.0 Ativa — Precisão sub-pixel com filtro adaptativo 1€."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (allOff) EmeraldActive else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Text("Perfis DSP Prontos", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = allOff,
                    onClick = { onUpdateConfig { it.copy(filters = FilterSettings()) } },
                    label = { Text("Raw PC (0ms)") }
                )
                FilterChip(
                    selected = f.oneEuroEnabled && f.antiJitterEnabled && !f.smoothingEnabled,
                    onClick = {
                        onUpdateConfig {
                            it.copy(
                                filters = FilterSettings(
                                    oneEuroEnabled = true,
                                    oneEuroMinCutoff = 1.6f,
                                    oneEuroBeta = 0.09f,
                                    antiJitterEnabled = true,
                                    antiJitterRadius = 2.0f
                                )
                            )
                        }
                    },
                    label = { Text("Lineart Estúdio (1€)") }
                )
                FilterChip(
                    selected = f.oneEuroEnabled && f.predictionEnabled,
                    onClick = {
                        onUpdateConfig {
                            it.copy(
                                filters = FilterSettings(
                                    oneEuroEnabled = true,
                                    oneEuroMinCutoff = 2.2f,
                                    oneEuroBeta = 0.14f,
                                    predictionEnabled = true,
                                    predictionLeadMs = 3.5f
                                )
                            )
                        }
                    },
                    label = { Text("Esboço Rápido + Predição") }
                )
                FilterChip(
                    selected = f.smoothingEnabled && f.hysteresisEnabled,
                    onClick = {
                        onUpdateConfig {
                            it.copy(
                                filters = FilterSettings(
                                    smoothingEnabled = true,
                                    smoothingAlpha = 0.42f,
                                    hysteresisEnabled = true,
                                    hysteresisThreshold = 3.5f
                                )
                            )
                        }
                    },
                    label = { Text("Pintura Suave") }
                )
            }

            FilterToggleRow(
                icon = Icons.Default.GraphicEq,
                title = "Filtro One-Euro (1€ Adaptativo)",
                subtitle = "Estabiliza traços lentos sem adicionar atraso em movimentos rápidos",
                checked = f.oneEuroEnabled
            ) { enabled ->
                onUpdateConfig { it.copy(filters = it.filters.copy(oneEuroEnabled = enabled)) }
            }
            if (f.oneEuroEnabled) {
                FilterSliderRow("Estabilidade Lenta (MinCutoff)", f.oneEuroMinCutoff, 0.3f..6.0f, "Hz") { c ->
                    onUpdateConfig { it.copy(filters = it.filters.copy(oneEuroMinCutoff = c)) }
                }
                FilterSliderRow("Agilidade Rápida (Beta)", f.oneEuroBeta, 0.01f..0.40f, "") { b ->
                    onUpdateConfig { it.copy(filters = it.filters.copy(oneEuroBeta = b)) }
                }
            }

            FilterToggleRow(
                icon = Icons.Default.Bolt,
                title = "Predição de Latência (2.0)",
                subtitle = "Compensa atraso do display com avanço preditivo sem overshoot",
                checked = f.predictionEnabled
            ) { enabled ->
                onUpdateConfig { it.copy(filters = it.filters.copy(predictionEnabled = enabled)) }
            }
            if (f.predictionEnabled) {
                FilterSliderRow("Avanço Preditivo", f.predictionLeadMs, 0.5f..10.0f, "ms") { ms ->
                    onUpdateConfig { it.copy(filters = it.filters.copy(predictionLeadMs = ms)) }
                }
            }

            FilterToggleRow(
                icon = Icons.Default.FilterCenterFocus,
                title = "Anti-Jitter (Zona Morta)",
                subtitle = "Remove micro-oscilação estática da ponta",
                checked = f.antiJitterEnabled
            ) { enabled ->
                onUpdateConfig { it.copy(filters = it.filters.copy(antiJitterEnabled = enabled)) }
            }
            if (f.antiJitterEnabled) {
                FilterSliderRow("Raio Anti-Jitter", f.antiJitterRadius, 0.5f..20f, "px") { r ->
                    onUpdateConfig { it.copy(filters = it.filters.copy(antiJitterRadius = r)) }
                }
            }

            FilterToggleRow(
                icon = Icons.Default.WaterDrop,
                title = "Suavização EMA",
                subtitle = "Filtro exponencial clássico para sombreamento contínuo",
                checked = f.smoothingEnabled
            ) { enabled ->
                onUpdateConfig { it.copy(filters = it.filters.copy(smoothingEnabled = enabled)) }
            }
            if (f.smoothingEnabled) {
                FilterSliderRow("Fator Alpha", f.smoothingAlpha, 0.1f..0.9f, "") { a ->
                    onUpdateConfig { it.copy(filters = it.filters.copy(smoothingAlpha = a)) }
                }
            }

            FilterToggleRow(
                icon = Icons.Default.Architecture,
                title = "Compensação de Tilt",
                subtitle = "Corrige deslocamento angular da ponta por inclinação",
                checked = f.tiltCompensationEnabled
            ) { enabled ->
                onUpdateConfig { it.copy(filters = it.filters.copy(tiltCompensationEnabled = enabled)) }
            }
            if (f.tiltCompensationEnabled) {
                FilterSliderRow("Intensidade Tilt", f.tiltFactor, 0.05f..0.8f, "") { tf ->
                    onUpdateConfig { it.copy(filters = it.filters.copy(tiltFactor = tf)) }
                }
            }

            FilterToggleRow(
                icon = Icons.Default.LinearScale,
                title = "Histerese",
                subtitle = "Estabiliza início e parada de traço lento",
                checked = f.hysteresisEnabled
            ) { enabled ->
                onUpdateConfig { it.copy(filters = it.filters.copy(hysteresisEnabled = enabled)) }
            }
            if (f.hysteresisEnabled) {
                FilterSliderRow("Limiar Histerese", f.hysteresisThreshold, 1f..16f, "u") { ht ->
                    onUpdateConfig { it.copy(filters = it.filters.copy(hysteresisThreshold = ht)) }
                }
            }
            OutlinedButton(
                onClick = onResetTab,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("reset_tab_filters_button")
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Resetar Aba Filters")
            }
        }
    }
}

@Composable
private fun FilterSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    unit: String,
    onValueChange: (Float) -> Unit
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall)
            Text(
                text = String.format(Locale.US, "%.2f %s", value, unit).trim(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValueChange, valueRange = range)
    }
}

@Composable
private fun FilterToggleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OutputTab(
    config: TabletConfig,
    profile: TabletProfileJson?,
    telemetry: DriverTelemetry,
    displays: List<TargetDisplayInfo>,
    installedApps: List<InstalledAppEntry>,
    onUpdateConfig: ((TabletConfig) -> TabletConfig) -> Unit,
    onActivateAutoDpi: () -> Unit,
    onResetTab: () -> Unit = {}
) {
    var dpiXText by remember(config.manualDpiX) { mutableStateOf(config.manualDpiX.toString()) }
    var dpiYText by remember(config.manualDpiY) { mutableStateOf(config.manualDpiY.toString()) }
    val targetDisp = displays.firstOrNull { it.displayId == config.targetDisplayId } ?: displays.firstOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // Card 1: DPI Automática & DPI Manual (10 presets + manual X/Y)
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
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Compatibilização de DPI & Pressão (2.0)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "Normaliza velocidade X/Y em telas altas (20:9) preservando 100% dos níveis de pressão.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Icon(Icons.Default.Speed, contentDescription = null, tint = EmeraldActive)
                }

                // Live readout badge
                Surface(
                    color = EmeraldActive.copy(alpha = 0.12f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "DPI Tela: %.0f×%.0f · Mesa: %d LPI · Pressão preservada: %d níveis",
                            telemetry.effectiveDpiX,
                            telemetry.effectiveDpiY,
                            profile?.lpi ?: 5080,
                            telemetry.maxNativePressure + 1
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = EmeraldActive,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                // Mode selector: Auto / Manual / Off
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = config.dpiMode == DpiNormalizationMode.AUTO,
                        onClick = { onActivateAutoDpi() },
                        label = { Text("Auto") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dpi_mode_auto_chip")
                    )
                    FilterChip(
                        selected = config.dpiMode == DpiNormalizationMode.MANUAL,
                        onClick = { onUpdateConfig { it.copy(dpiMode = DpiNormalizationMode.MANUAL) } },
                        label = { Text("Manual") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("dpi_mode_manual_chip")
                    )
                    FilterChip(
                        selected = config.dpiMode == DpiNormalizationMode.OFF,
                        onClick = { onUpdateConfig { it.copy(dpiMode = DpiNormalizationMode.OFF) } },
                        label = { Text("Desligado") },
                        modifier = Modifier.weight(1f)
                    )
                }

                // 1-click native display DPI sync calibration action
                FilledTonalButton(
                    onClick = onActivateAutoDpi,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("auto_dpi_button")
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    val dispLabel = targetDisp?.let { "${it.xdpi.toInt()}×${it.ydpi.toInt()} DPI" } ?: "Tela atual"
                    Text("Sincronizar pela DPI Nativa ($dispLabel)")
                }

                if (config.dpiMode == DpiNormalizationMode.MANUAL) {
                    Text(
                        text = "Pacote de 10 DPIs Manuais para Testar",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DEFAULT_MANUAL_DPI_PRESETS.forEach { preset ->
                            val isSelected = config.manualDpiX == preset.dpiX && config.manualDpiY == preset.dpiY
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    dpiXText = preset.dpiX.toString()
                                    dpiYText = preset.dpiY.toString()
                                    onUpdateConfig {
                                        it.copy(
                                            dpiMode = DpiNormalizationMode.MANUAL,
                                            manualDpiX = preset.dpiX,
                                            manualDpiY = preset.dpiY
                                        )
                                    }
                                },
                                label = { Text(preset.label) },
                                modifier = Modifier.testTag("manual_dpi_preset_${preset.dpiX}")
                            )
                        }
                    }

                    Text(
                        text = "Escrever DPI X / Y Manualmente (72 – 1200)",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = dpiXText,
                            onValueChange = { raw -> dpiXText = raw.filter { it.isDigit() }.take(4) },
                            label = { Text("DPI X") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("manual_dpi_x_input")
                        )
                        OutlinedTextField(
                            value = dpiYText,
                            onValueChange = { raw -> dpiYText = raw.filter { it.isDigit() }.take(4) },
                            label = { Text("DPI Y") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("manual_dpi_y_input")
                        )
                    }

                    Button(
                        onClick = {
                            val px = (dpiXText.toIntOrNull() ?: config.manualDpiX).coerceIn(72, 1200)
                            val py = (dpiYText.toIntOrNull() ?: config.manualDpiY).coerceIn(72, 1200)
                            dpiXText = px.toString()
                            dpiYText = py.toString()
                            onUpdateConfig {
                                it.copy(
                                    dpiMode = DpiNormalizationMode.MANUAL,
                                    manualDpiX = px,
                                    manualDpiY = py
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("apply_manual_dpi_xy_button")
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Aplicar DPI X/Y Personalizado")
                    }
                }
            }
        }

        // Card 2: Output Mode, Display Target & Bound App
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.TouchApp, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Text("Modo de Saída", style = MaterialTheme.typography.titleMedium)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilterChip(
                        selected = config.outputMode == OutputMode.ABSOLUTE,
                        onClick = { onUpdateConfig { it.copy(outputMode = OutputMode.ABSOLUTE) } },
                        label = { Text("Absoluto (Mesa)") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = config.outputMode == OutputMode.RELATIVE,
                        onClick = { onUpdateConfig { it.copy(outputMode = OutputMode.RELATIVE) } },
                        label = { Text("Relativo (Trackpad)") },
                        modifier = Modifier.weight(1f)
                    )
                }

                if (config.outputMode == OutputMode.RELATIVE) {
                    FilterSliderRow("Sensibilidade Relativa", config.relativeSensitivity, 0.4f..4.0f, "×") { s ->
                        onUpdateConfig { it.copy(relativeSensitivity = s) }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Monitor, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Text("Tela de Destino (DeX / Espelhamento)", style = MaterialTheme.typography.titleMedium)
                }
                displays.forEach { disp ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = config.targetDisplayId == disp.displayId,
                            onClick = { onUpdateConfig { it.copy(targetDisplayId = disp.displayId) } }
                        )
                        Icon(Icons.Default.Monitor, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("${disp.modeLabel} · ${disp.name}", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = "${disp.widthPx}×${disp.heightPx}px · ${disp.rotationDeg}° · ${disp.xdpi.toInt()}×${disp.ydpi.toInt()} DPI",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Apps, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Text("App Vinculado (${config.targetAppPackage ?: "Global"})", style = MaterialTheme.typography.titleMedium)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = config.targetAppPackage == null,
                        onClick = { onUpdateConfig { it.copy(targetAppPackage = null) } },
                        label = { Text("Global") }
                    )
                    installedApps.take(12).forEach { app ->
                        FilterChip(
                            selected = config.targetAppPackage == app.packageName,
                            onClick = { onUpdateConfig { it.copy(targetAppPackage = app.packageName) } },
                            label = { Text(app.label) }
                        )
                    }
                }

                OutlinedButton(
                    onClick = onResetTab,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("reset_tab_output_button")
                ) {
                    Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Resetar Aba Output")
                }
            }
        }
    }
}
