package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MergeType
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Handshake
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.hub.CoopBannerEvent
import com.example.hub.CursorOverlaySnapshot
import com.example.hub.CursorRenderMode
import com.example.hub.HubSlotAssignment
import com.example.hub.HubSlotId
import com.example.hub.InstalledAppEntry
import com.example.hub.RoutingMode
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.CoralError
import com.example.ui.theme.EmeraldActive
import com.example.usb.ConnectedUsbDeviceInfo
import com.example.usb.UsbPeripheralKind
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubScreen(
    usbDevices: List<ConnectedUsbDeviceInfo>,
    usbEventLog: String,
    hubSlots: List<HubSlotAssignment>,
    installedApps: List<InstalledAppEntry>,
    coopEvent: CoopBannerEvent?,
    simulatedClosedPackages: Set<String>,
    cursorSnapshot: CursorOverlaySnapshot,
    engineActive: Boolean,
    onBack: () -> Unit,
    onRefreshDevices: () -> Unit,
    onRequestUsbPermission: (String) -> Unit,
    onAssignPhysicalDevice: (HubSlotId, String?, String) -> Unit,
    onAssignCombinedDevices: (HubSlotId, String?, String, String?, String?) -> Unit,
    onAssignTargetApp: (HubSlotId, InstalledAppEntry?) -> Unit,
    onImportCustomCursor: (HubSlotId, Uri) -> Unit,
    onClearCustomCursor: (HubSlotId) -> Unit,
    onUpdateCursorHotspot: (HubSlotId, Float, Float) -> Unit,
    onSelectCursorMode: (CursorRenderMode) -> Unit,
    onChangeCursorScale: (Float) -> Unit,
    onGrantOverlayViaAdb: () -> Unit,
    onToggleSimulatedAppClosed: (String, String) -> Unit,
    onDismissCoopBanner: () -> Unit
) {
    val context = LocalContext.current
    var pickingDeviceForSlot by remember { mutableStateOf<HubSlotId?>(null) }
    var pickingAppForSlot by remember { mutableStateOf<HubSlotId?>(null) }
    var cursorPickerSlot by remember { mutableStateOf<HubSlotId?>(null) }
    var editingHotspotSlot by remember { mutableStateOf<HubSlotId?>(null) }

    val openDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        val slot = cursorPickerSlot
        if (uri != null && slot != null) {
            onImportCustomCursor(slot, uri)
        }
        cursorPickerSlot = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Hub Multi-Dispositivo", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "2 Mouses · 2 Teclados · 2 Mesas · Cursor Global",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("hub_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    IconButton(
                        onClick = onRefreshDevices,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("hub_refresh_button")
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = "Atualizar USB e apps")
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
            LazyColumn(
                modifier = Modifier
                    .widthIn(max = 860.dp)
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .testTag("hub_lazy_column"),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Coop Mode Notification Banner
                item {
                    AnimatedVisibility(visible = coopEvent != null) {
                        coopEvent?.let { ev ->
                            Surface(
                                color = AmberAccent.copy(alpha = 0.16f),
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, AmberAccent, MaterialTheme.shapes.medium)
                                    .testTag("coop_mode_banner")
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Icon(Icons.Default.Handshake, contentDescription = null, tint = AmberAccent)
                                        Column {
                                            Text(
                                                text = ev.message,
                                                style = MaterialTheme.typography.titleMedium,
                                                color = AmberAccent
                                            )
                                            Text(
                                                text = "Alvo '${ev.closedAppLabel}' encerrado — operação global cooperativa ativa.",
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        }
                                    }
                                    IconButton(onClick = onDismissCoopBanner, modifier = Modifier.size(48.dp)) {
                                        Icon(Icons.Default.Close, contentDescription = "Dispensar aviso")
                                    }
                                }
                            }
                        }
                    }
                }

                // Global Cursor Mode & Overlay Controls Card (Windows-style .cur / .ani / .png on top of everything)
                item {
                    GlobalCursorSettingsCard(
                        snapshot = cursorSnapshot,
                        engineActive = engineActive,
                        onSelectMode = onSelectCursorMode,
                        onChangeScale = onChangeCursorScale,
                        onGrantViaAdb = onGrantOverlayViaAdb,
                        onOpenOverlaySettings = {
                            runCatching {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                            }
                        }
                    )
                }

                // Connected USB Devices Section (Mice, Tablets, Keyboards)
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Dispositivos Conectados (USB + Sistema)", style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = "Mesas e Mouses USB · Comandos ADB, APK e MTP seguem livres no sistema",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "${usbDevices.size} ativo(s)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                if (usbDevices.isEmpty()) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(Icons.Default.Usb, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                    Text("Nenhum periférico USB detectado ainda", style = MaterialTheme.typography.titleMedium)
                                }
                                Text(
                                    text = "$usbEventLog\nConecte mouse USB, teclado ou mesa digitalizadora via OTG/Hub USB e toque em Atualizar acima.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(usbDevices, key = { it.deviceKey }) { dev ->
                        ConnectedUsbDeviceCard(
                            device = dev,
                            onRequestPermission = { onRequestUsbPermission(dev.deviceKey) }
                        )
                    }
                }

                // Slot Assignment Table Section (Mouse 1/2, Teclado 1/2, Mesa 1/2)
                item {
                    Spacer(Modifier.height(4.dp))
                    Text("Atribuição de Slots & Cursor Global", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "Você pode combinar Mesa + Mouse juntos no Mouse 1 ao mesmo tempo e personalizar o ponteiro .cur / .ani / .png.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                items(hubSlots, key = { it.slotId.name }) { slot ->
                    HubSlotCard(
                        slot = slot,
                        isTargetAppSimulatedClosed = slot.targetAppPackage in simulatedClosedPackages,
                        onPickPhysicalDevice = { pickingDeviceForSlot = slot.slotId },
                        onPickTargetApp = { pickingAppForSlot = slot.slotId },
                        onPickCustomCursor = {
                            cursorPickerSlot = slot.slotId
                            openDocumentLauncher.launch(arrayOf("*/*"))
                        },
                        onEditHotspot = { editingHotspotSlot = slot.slotId },
                        onClearCustomCursor = { onClearCustomCursor(slot.slotId) },
                        onToggleSimulateClose = {
                            val pkg = slot.targetAppPackage
                            if (pkg != null) {
                                onToggleSimulatedAppClosed(pkg, slot.targetAppLabel)
                            }
                        }
                    )
                }

                // UsageStats fallback helper card
                item {
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Configurar Acesso de Uso (Fallback Coop sem ADB)")
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }

    // Dialog: Pick Physical USB Device (supports single OR Combined Mouse + Tablet in same slot!)
    pickingDeviceForSlot?.let { targetSlot ->
        val currentAssignment = hubSlots.firstOrNull { it.slotId == targetSlot }
        CombinedDevicePickerDialog(
            targetSlot = targetSlot,
            devices = usbDevices,
            currentPrimaryKey = currentAssignment?.physicalDeviceKey,
            currentSecondaryKey = currentAssignment?.secondaryDeviceKey,
            onDismiss = { pickingDeviceForSlot = null },
            onUnbindAll = {
                onAssignPhysicalDevice(targetSlot, null, "Nenhum atribuído")
                pickingDeviceForSlot = null
            },
            onConfirmSingle = { dev ->
                onAssignCombinedDevices(targetSlot, dev.deviceKey, dev.productName, null, null)
                pickingDeviceForSlot = null
            },
            onConfirmCombined = { dev1, dev2 ->
                onAssignCombinedDevices(
                    targetSlot,
                    dev1.deviceKey,
                    dev1.productName,
                    dev2?.deviceKey,
                    dev2?.productName
                )
                pickingDeviceForSlot = null
            }
        )
    }

    // Dialog: Fine-tune Hotspot (Ponta de encaixe estilo Windows PC)
    editingHotspotSlot?.let { slotId ->
        val slot = hubSlots.firstOrNull { it.slotId == slotId }
        val cursor = slot?.customCursor
        if (cursor != null) {
            HotspotEditorDialog(
                cursor = cursor,
                onDismiss = { editingHotspotSlot = null },
                onSaveHotspot = { hx, hy ->
                    onUpdateCursorHotspot(slotId, hx, hy)
                    editingHotspotSlot = null
                }
            )
        }
    }

    // Dialog: Pick Target App for Slot
    pickingAppForSlot?.let { targetSlot ->
        AlertDialog(
            onDismissRequest = { pickingAppForSlot = null },
            title = { Text("App de destino · ${targetSlot.label}") },
            text = {
                LazyColumn(
                    modifier = Modifier.height(340.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    item {
                        Surface(
                            onClick = {
                                onAssignTargetApp(targetSlot, null)
                                pickingAppForSlot = null
                            },
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "🤝 Global (Sistema / Coop permanente)",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                    items(installedApps, key = { it.packageName }) { app ->
                        Surface(
                            onClick = {
                                onAssignTargetApp(targetSlot, app)
                                pickingAppForSlot = null
                            },
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(app.label, style = MaterialTheme.typography.titleMedium)
                                Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickingAppForSlot = null }) { Text("Cancelar") }
            }
        )
    }
}

@Composable
private fun GlobalCursorSettingsCard(
    snapshot: CursorOverlaySnapshot,
    engineActive: Boolean,
    onSelectMode: (CursorRenderMode) -> Unit,
    onChangeScale: (Float) -> Unit,
    onGrantViaAdb: () -> Unit,
    onOpenOverlaySettings: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f), MaterialTheme.shapes.large)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
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
                    Icon(Icons.Default.Layers, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text("Cursor Global Unificado (.cur / .ani / .png)", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = buildString {
                                append("Canal 1 (Mouse 1 + Mesa 1): ")
                                append(snapshot.channel1Cursor?.fileName ?: "Padrão")
                                append("\nCanal 2 (Mouse 2 + Mesa 2): ")
                                append(snapshot.channel2Cursor?.fileName ?: "Padrão")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
                snapshot.activeCursor?.let { cur ->
                    Image(
                        bitmap = cur.bitmap.asImageBitmap(),
                        contentDescription = "Preview do cursor",
                        modifier = Modifier
                            .size(36.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.background)
                            .padding(4.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = snapshot.mode == CursorRenderMode.NATIVE,
                    onClick = { onSelectMode(CursorRenderMode.NATIVE) },
                    label = { Text("Modo Nativo") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cursor_mode_native")
                )
                FilterChip(
                    selected = snapshot.mode == CursorRenderMode.SIMULATED,
                    onClick = { onSelectMode(CursorRenderMode.SIMULATED) },
                    label = { Text("Modo Simulado") },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("cursor_mode_simulated")
                )
            }

            Text(
                text = snapshot.mode.subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Cursor scale slider
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.ZoomOutMap, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("Tamanho do Cursor na Tela", style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        text = String.format(Locale.US, "%.1fx", snapshot.cursorScale),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Slider(
                    value = snapshot.cursorScale,
                    onValueChange = onChangeScale,
                    valueRange = 0.5f..2.2f
                )
            }

            Surface(
                color = if (snapshot.overlayPermissionGranted) {
                    EmeraldActive.copy(alpha = 0.14f)
                } else {
                    AmberAccent.copy(alpha = 0.15f)
                },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (snapshot.overlayPermissionGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (snapshot.overlayPermissionGranted) EmeraldActive else AmberAccent,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = if (snapshot.overlayPermissionGranted) {
                                "Cursor por cima de tudo ATIVO (Overlay do sistema liberado)"
                            } else {
                                "Permita 'Sobrepor a outros apps' para o cursor ficar acima de todos os aplicativos"
                            },
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }

            if (!snapshot.overlayPermissionGranted) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenOverlaySettings,
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Text("Abrir Permissões", maxLines = 1)
                    }
                    if (engineActive) {
                        Button(
                            onClick = onGrantViaAdb,
                            modifier = Modifier
                                .weight(1f)
                                .height(46.dp)
                        ) {
                            Text("Liberar via ADB", maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CombinedDevicePickerDialog(
    targetSlot: HubSlotId,
    devices: List<ConnectedUsbDeviceInfo>,
    currentPrimaryKey: String?,
    currentSecondaryKey: String?,
    onDismiss: () -> Unit,
    onUnbindAll: () -> Unit,
    onConfirmSingle: (ConnectedUsbDeviceInfo) -> Unit,
    onConfirmCombined: (ConnectedUsbDeviceInfo, ConnectedUsbDeviceInfo?) -> Unit
) {
    var combineMode by remember {
        mutableStateOf(
            currentSecondaryKey != null || targetSlot == HubSlotId.MOUSE_1
        )
    }
    var selectedKeys by remember {
        mutableStateOf(
            listOfNotNull(currentPrimaryKey, currentSecondaryKey).distinct().take(2)
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (targetSlot == HubSlotId.MOUSE_1) {
                    "Selecionar para ${targetSlot.label} (Mouse + Mesa)"
                } else {
                    "Selecionar físico para ${targetSlot.label}"
                }
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.MergeType, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Text(
                            text = "Combinar Mesa + Mouse juntos",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Switch(
                        checked = combineMode,
                        onCheckedChange = { combineMode = it }
                    )
                }

                Text(
                    text = if (combineMode) {
                        "Marque até 2 dispositivos (ex.: Mouse USB + Mesa Digitalizadora) para controlar o ${targetSlot.label} ao mesmo tempo."
                    } else {
                        "Toque em um dispositivo conectado para atribuir ao slot."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                TextButton(onClick = onUnbindAll) {
                    Text("Desvincular tudo deste slot", color = CoralError)
                }
                HorizontalDivider()

                if (devices.isEmpty()) {
                    Text(
                        "Nenhum Mouse USB, Mesa ou Teclado detectado no momento. Feche e toque no botão Atualizar.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    devices.forEach { dev ->
                        val isChecked = dev.deviceKey in selectedKeys
                        val kindColor = when (dev.kind) {
                            UsbPeripheralKind.MOUSE -> MaterialTheme.colorScheme.secondary
                            UsbPeripheralKind.TABLET -> MaterialTheme.colorScheme.primary
                            UsbPeripheralKind.KEYBOARD -> AmberAccent
                        }
                        Surface(
                            onClick = {
                                if (!combineMode) {
                                    onConfirmSingle(dev)
                                } else {
                                    selectedKeys = if (isChecked) {
                                        selectedKeys - dev.deviceKey
                                    } else {
                                        (selectedKeys + dev.deviceKey).takeLast(2)
                                    }
                                }
                            },
                            shape = MaterialTheme.shapes.small,
                            color = if (isChecked && combineMode) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (combineMode) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            selectedKeys = if (checked) {
                                                (selectedKeys + dev.deviceKey).takeLast(2)
                                            } else {
                                                selectedKeys - dev.deviceKey
                                            }
                                        }
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(dev.productName, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        text = "${dev.kind.label} · VID:PID ${dev.vendorId.toString(16)}:${dev.productId.toString(16)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = kindColor
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (combineMode) {
                Button(
                    enabled = selectedKeys.isNotEmpty(),
                    onClick = {
                        val picked = selectedKeys.mapNotNull { k -> devices.firstOrNull { it.deviceKey == k } }
                        val first = picked.firstOrNull() ?: return@Button
                        val second = picked.getOrNull(1)
                        onConfirmCombined(first, second)
                    }
                ) {
                    Text("Aplicar (${selectedKeys.size})")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Fechar") }
        }
    )
}

@Composable
private fun HotspotEditorDialog(
    cursor: com.example.hub.DecodedCursor,
    onDismiss: () -> Unit,
    onSaveHotspot: (Float, Float) -> Unit
) {
    var hx by remember { mutableFloatStateOf(cursor.hotspotX) }
    var hy by remember { mutableFloatStateOf(cursor.hotspotY) }
    val w = cursor.bitmap.width.coerceAtLeast(1)
    val h = cursor.bitmap.height.coerceAtLeast(1)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajustar Ponta exata (Hotspot PC)") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Toque exatamente onde fica a ponta de clique deste cursor (${hx.toInt()}, ${hy.toInt()} px).",
                    style = MaterialTheme.typography.bodyMedium
                )
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(Color(0xFF1A1C2E))
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                hx = (offset.x / size.width * w).coerceIn(0f, (w - 1).toFloat())
                                hy = (offset.y / size.height * h).coerceIn(0f, (h - 1).toFloat())
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = cursor.bitmap.asImageBitmap(),
                        contentDescription = "Preview hotspot",
                        modifier = Modifier.fillMaxSize()
                    )
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val px = (hx / w) * size.width
                        val py = (hy / h) * size.height
                        drawLine(Color.Red, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2f)
                        drawLine(Color.Red, Offset(0f, py), Offset(size.width, py), strokeWidth = 2f)
                        drawCircle(Color.Cyan, radius = 6f, center = Offset(px, py))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { hx = 0f; hy = 0f }) {
                        Text("Ponta (0,0)")
                    }
                    OutlinedButton(onClick = { hx = w / 2f; hy = h / 2f }) {
                        Text("Centro")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSaveHotspot(hx, hy) }) {
                Text("Salvar Ponta")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

@Composable
private fun ConnectedUsbDeviceCard(
    device: ConnectedUsbDeviceInfo,
    onRequestPermission: () -> Unit
) {
    val icon = when (device.kind) {
        UsbPeripheralKind.TABLET -> Icons.Default.Draw
        UsbPeripheralKind.MOUSE -> Icons.Default.Mouse
        UsbPeripheralKind.KEYBOARD -> Icons.Default.Keyboard
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = device.kind.label, tint = MaterialTheme.colorScheme.primary)
                Column {
                    Text(device.productName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "${device.kind.label} · ${device.manufacturerName} · VID:PID ${device.vendorId.toString(16)}:${device.productId.toString(16)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = when {
                            device.isClaimed -> "Driver exclusivo Koda ativo (force=true)"
                            device.isSystemInputFallback -> "Disponível via Android InputManager"
                            else -> "VBUS OK · Pronto para assumir"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (device.isClaimed || device.isSystemInputFallback) EmeraldActive else AmberAccent
                    )
                }
            }
            if (!device.isClaimed && !device.isSystemInputFallback) {
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(if (device.hasPermission) "Assumir" else "Permitir USB")
                }
            }
        }
    }
}

@Composable
private fun HubSlotCard(
    slot: HubSlotAssignment,
    isTargetAppSimulatedClosed: Boolean,
    onPickPhysicalDevice: () -> Unit,
    onPickTargetApp: () -> Unit,
    onPickCustomCursor: () -> Unit,
    onEditHotspot: () -> Unit,
    onClearCustomCursor: () -> Unit,
    onToggleSimulateClose: () -> Unit
) {
    val icon: ImageVector = when (slot.slotId.kind) {
        UsbPeripheralKind.MOUSE -> Icons.Default.Mouse
        UsbPeripheralKind.KEYBOARD -> Icons.Default.Keyboard
        UsbPeripheralKind.TABLET -> Icons.Default.Draw
    }
    val isRouted = slot.routingMode == RoutingMode.ROUTED && slot.targetAppPackage != null
    val statusColor = if (isRouted) EmeraldActive else AmberAccent
    val statusLabel = if (isRouted) {
        "🎯 Roteado → ${slot.targetAppLabel}"
    } else {
        "🤝 Coop (global)"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
            .testTag("hub_slot_${slot.slotId.name.lowercase()}")
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(icon, contentDescription = slot.slotId.label, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(slot.slotId.label, style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = if (slot.secondaryDeviceKey != null) {
                                "Combinado (Mesa + Mouse) · #${slot.slotId.virtualDeviceId}"
                            } else {
                                "deviceId virtual: #${slot.slotId.virtualDeviceId}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = statusColor.copy(alpha = 0.14f)
                ) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onPickPhysicalDevice,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("pick_device_${slot.slotId.name.lowercase()}")
                ) {
                    Text(slot.deviceSummaryLabel(), maxLines = 1)
                }
                FilledTonalButton(
                    onClick = onPickTargetApp,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("pick_app_${slot.slotId.name.lowercase()}")
                ) {
                    Icon(Icons.Default.Apps, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(slot.targetAppLabel, maxLines = 1)
                }
            }

            // Custom Cursor importer for both Mice & Tablets (.cur / .ani / .png)
            if (slot.slotId.kind == UsbPeripheralKind.MOUSE || slot.slotId.kind == UsbPeripheralKind.TABLET) {
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
                        if (slot.customCursor != null) {
                            Image(
                                bitmap = slot.customCursor.bitmap.asImageBitmap(),
                                contentDescription = "Cursor personalizado",
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(4.dp)
                            )
                            Column {
                                Text(
                                    text = "${slot.customCursor.fileName} (${slot.customCursor.sourceFormat})",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                val channelHint = if (slot.slotId.cursorChannel() == 2) {
                                    "Unificado: Mouse 2 + Mesa 2"
                                } else {
                                    "Unificado: Mouse 1 + Mesa 1"
                                }
                                Text(
                                    text = "$channelHint · Ponta ${slot.customCursor.hotspotX.toInt()},${slot.customCursor.hotspotY.toInt()} px",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Icon(Icons.Default.TrackChanges, contentDescription = null, modifier = Modifier.size(18.dp))
                            val emptyHint = if (slot.slotId.cursorChannel() == 2) {
                                "Cursor padrão (Canal 2: Mouse 2 + Mesa 2)"
                            } else {
                                "Cursor padrão (Canal 1: Mouse 1 + Mesa 1)"
                            }
                            Text(emptyHint, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (slot.customCursor != null) {
                            IconButton(
                                onClick = onEditHotspot,
                                modifier = Modifier.size(44.dp)
                            ) {
                                Icon(Icons.Default.MyLocation, contentDescription = "Ajustar ponta do cursor", tint = MaterialTheme.colorScheme.primary)
                            }
                            TextButton(onClick = onClearCustomCursor) { Text("Limpar") }
                        }
                        OutlinedButton(
                            onClick = onPickCustomCursor,
                            modifier = Modifier.height(46.dp)
                        ) {
                            Text(".cur / .ani / .png")
                        }
                    }
                }
            }

            if (slot.targetAppPackage != null) {
                Text(
                    text = if (isTargetAppSimulatedClosed) {
                        "↻ Simular reabertura de '${slot.targetAppLabel}' (voltar a Roteado)"
                    } else {
                        "⚡ Simular fechamento de '${slot.targetAppLabel}' (testar Modo Coop)"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onToggleSimulateClose() }
                        .padding(vertical = 6.dp)
                        .testTag("simulate_coop_${slot.slotId.name.lowercase()}")
                )
            }
        }
    }
}
