package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.AppErrorReport
import com.example.R
import com.example.koda.engine.AdbTransportMode
import com.example.koda.engine.KodaEngineState
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.CoralError
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldActive
import com.example.ui.theme.EmeraldDeep
import com.example.ui.theme.EmeraldFullScreen
import com.example.ui.theme.WintabViolet
import kotlinx.coroutines.delay

@Composable
fun ActivationScreen(
    engineState: KodaEngineState,
    errorPopup: AppErrorReport?,
    onActivateSingleEngine: () -> Unit,
    onSilentPoll: () -> Unit,
    onVerifyConnection: () -> Unit,
    onSelectTransportMode: (AdbTransportMode) -> Unit,
    onRefreshNetworkIp: () -> Unit,
    onDismissErrorPopup: () -> Unit,
    onActivatedOpenApp: () -> Unit,
    modifier: Modifier = Modifier,
    overlayGranted: Boolean = false,
    accessibilityEnabled: Boolean = false,
    batteryUnrestricted: Boolean = false,
    onWifiPortChanged: (String) -> Unit = {},
    onKeepTerminalOpenChanged: (Boolean) -> Unit = {},
    onOpenOverlaySettings: () -> Unit = {},
    onOpenAccessibilitySettings: () -> Unit = {},
    onOpenBatteryOptimizationSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    var isBootingCore by remember { mutableStateOf(false) }
    var bootStage by remember { mutableIntStateOf(0) }
    var showPcActivationPanel by remember { mutableStateOf(false) }
    var copiedCommand by remember { mutableStateOf(false) }
    var copiedConnect by remember { mutableStateOf(false) }

    val isActivated = engineState.coreEngineActive || engineState.permanentlyActivated || engineState.isActive

    LaunchedEffect(isBootingCore) {
        if (isBootingCore) {
            for (step in 1..4) {
                bootStage = step
                delay(140L)
            }
            onActivateSingleEngine()
            isBootingCore = false
        }
    }

    LaunchedEffect(isActivated) {
        if (isActivated) {
            delay(850L)
            onActivatedOpenApp()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_activation_artistic_bg),
            contentDescription = "Fundo artístico Koda Wintab",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (isActivated) {
                        Brush.verticalGradient(
                            listOf(
                                EmeraldFullScreen.copy(alpha = 0.93f),
                                EmeraldDeep.copy(alpha = 0.97f)
                            )
                        )
                    } else {
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF070812).copy(alpha = 0.72f),
                                Color(0xFF0C0E1E).copy(alpha = 0.91f),
                                Color(0xFF070812).copy(alpha = 0.97f)
                            )
                        )
                    }
                )
        )

        if (isActivated) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(260)) + scaleIn(initialScale = 0.92f)
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(94.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.18f))
                            .border(2.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Motor Principal Ativado",
                            tint = Color.White,
                            modifier = Modifier.size(56.dp)
                        )
                    }

                    Text(
                        text = if (engineState.isActive) "Koda Engine + Shell Ativos!" else "Koda Core Engine Ativa!",
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = if (engineState.isActive) {
                            "Servidor Shell UID ${engineState.serverUid} e Driver Autônomo prontos.\nInjeção global de pressão, remoção de cursor duplo e Pen Display ativos!"
                        } else {
                            "Ativação concluída diretamente no aparelho.\nDriver OTG, Cursor Global .cur/.ani/.png e Pen Display liberados sem PC e sem Modo Desenvolvedor."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.92f),
                        textAlign = TextAlign.Center
                    )

                    Button(
                        onClick = onActivatedOpenApp,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor = EmeraldDeep
                        ),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(58.dp)
                            .testTag("activated_open_app_button")
                    ) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Abrir Estúdio Koda",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "developed by Gabriel Freitas",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.80f)
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    // Header Brand & Badge
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.wintab_app_icon),
                                contentDescription = "Ícone Koda Wintab",
                                modifier = Modifier
                                    .size(54.dp)
                                    .clip(RoundedCornerShape(15.dp))
                                    .border(1.5.dp, ElectricCyan.copy(alpha = 0.55f), RoundedCornerShape(15.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Column {
                                Text(
                                    text = "Koda WinTab",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = Color.White
                                )
                                Text(
                                    text = "Engine Autônoma · Sem PC ou Via PC",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ElectricCyan
                                )
                            }
                        }

                        Surface(
                            shape = CircleShape,
                            color = EmeraldActive.copy(alpha = 0.16f),
                            modifier = Modifier.border(1.dp, EmeraldActive.copy(alpha = 0.55f), CircleShape)
                        ) {
                            Text(
                                text = "SEM PC · POWER",
                                style = MaterialTheme.typography.labelSmall,
                                color = EmeraldActive,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    // Card 1: Autonomous Engine (Zero PC)
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF121426).copy(alpha = 0.94f)
                        ),
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.5.dp,
                                Brush.linearGradient(listOf(WintabViolet.copy(alpha = 0.75f), ElectricCyan.copy(alpha = 0.75f))),
                                MaterialTheme.shapes.extraLarge
                            )
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Motor Principal Autônomo (Sem PC)",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White
                            )
                            Text(
                                text = "Funciona sozinho sem conectar no PC: assume mesa/mouse USB OTG (force=true), desenha cursor .cur/.ani/.png único por cima de todo o Android e habilita cliques/arrastar globais.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.85f)
                            )

                            // Fast Standalone Permission Boosters (Accessibility + Overlay)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onOpenOverlaySettings,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("activation_overlay_button")
                                ) {
                                    Icon(
                                        imageVector = if (overlayGranted) Icons.Default.CheckCircle else Icons.Default.Layers,
                                        contentDescription = null,
                                        tint = if (overlayGranted) EmeraldActive else AmberAccent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = if (overlayGranted) "Overlay OK" else "Sobrepor Apps",
                                        maxLines = 1,
                                        color = if (overlayGranted) EmeraldActive else Color.White
                                    )
                                }
                                OutlinedButton(
                                    onClick = onOpenAccessibilitySettings,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(48.dp)
                                        .testTag("activation_accessibility_button")
                                ) {
                                    Icon(
                                        imageVector = if (accessibilityEnabled) Icons.Default.CheckCircle else Icons.Default.AccessibilityNew,
                                        contentDescription = null,
                                        tint = if (accessibilityEnabled) EmeraldActive else ElectricCyan,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        text = if (accessibilityEnabled) "Cursor Global OK" else "Cursor Sem PC",
                                        maxLines = 1,
                                        color = if (accessibilityEnabled) EmeraldActive else Color.White
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = onOpenBatteryOptimizationSettings,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("activation_battery_button")
                            ) {
                                Icon(
                                    imageVector = if (batteryUnrestricted) Icons.Default.CheckCircle else Icons.Default.Bolt,
                                    contentDescription = null,
                                    tint = if (batteryUnrestricted) EmeraldActive else AmberAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (batteryUnrestricted) {
                                        "Segundo Plano Sem Cortes (OK)"
                                    } else {
                                        "Liberar 2º Plano (Não Matar Driver ao Abrir Outro App)"
                                    },
                                    maxLines = 1,
                                    color = if (batteryUnrestricted) EmeraldActive else Color.White
                                )
                            }

                            HorizontalDivider(color = Color.White.copy(alpha = 0.10f))

                            CoreSubsystemRow(
                                icon = Icons.Default.Monitor,
                                title = "Display Virtual & Área Kodasoftr",
                                detail = "Tela virtual dedicada para Pen Display com apps roteados e multitarefa nativa.",
                                ready = bootStage >= 1,
                                accent = ElectricCyan
                            )
                            CoreSubsystemRow(
                                icon = Icons.Default.AccessibilityNew,
                                title = "Cursor Real Global (.cur/.ani/.png) Sem PC",
                                detail = "Cliques, traços, scroll e cursor único por cima do sistema via Acessibilidade + Overlay.",
                                ready = bootStage >= 2 || accessibilityEnabled,
                                accent = EmeraldActive
                            )
                            CoreSubsystemRow(
                                icon = Icons.Default.Speed,
                                title = "Auto-Probe Shell & USB OTG Force-Claim",
                                detail = "Captura interface USB exclusiva do mouse/mesa eliminando seta duplicada + auto daemon se houver root.",
                                ready = bootStage >= 3,
                                accent = WintabViolet
                            )
                            CoreSubsystemRow(
                                icon = Icons.Default.Draw,
                                title = "Driver HID WinTab 8192 + DPI Física",
                                detail = "Curvas Catmull-Rom, filtros 1€ e calibração de DPI X/Y sem pesar ao deitar o celular.",
                                ready = bootStage >= 4,
                                accent = EmeraldActive
                            )

                            Spacer(Modifier.height(2.dp))

                            Button(
                                onClick = {
                                    if (!isBootingCore) {
                                        isBootingCore = true
                                    }
                                },
                                enabled = !isBootingCore,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = EmeraldActive,
                                    contentColor = Color(0xFF00210C)
                                ),
                                shape = RoundedCornerShape(18.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(58.dp)
                                    .testTag("single_activate_engine_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = null,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = if (isBootingCore) {
                                        "Inicializando Motor Potente ($bootStage/4)..."
                                    } else {
                                        "Ativar Motor Principal (1 Toque · Sem PC)"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    // Card 2: Explicit "Ativar pelo PC (ADB Shell UID 2000)"
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF101322).copy(alpha = 0.94f)
                        ),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, ElectricCyan.copy(alpha = 0.45f), MaterialTheme.shapes.large)
                            .testTag("pc_activation_card")
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
                                    modifier = Modifier.weight(1f),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Computer, contentDescription = null, tint = ElectricCyan)
                                    Column {
                                        Text(
                                            text = "Ativar pelo PC (ADB Shell UID 2000)",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = Color.White
                                        )
                                        Text(
                                            text = "Opcional: libera InputManager nativo, pressão 8192 global e TaskStackListener",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.72f)
                                        )
                                    }
                                }
                                FilledTonalButton(
                                    onClick = { showPcActivationPanel = !showPcActivationPanel },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("pc_activation_toggle_button")
                                ) {
                                    Text(if (showPcActivationPanel) "Recolher" else "Configurar PC")
                                    Spacer(Modifier.width(4.dp))
                                    Icon(
                                        imageVector = if (showPcActivationPanel) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            AnimatedVisibility(visible = showPcActivationPanel) {
                                PcAdbCommandSection(
                                    engineState = engineState,
                                    copiedConnect = copiedConnect,
                                    copiedCommand = copiedCommand,
                                    onSelectTransportMode = onSelectTransportMode,
                                    onWifiPortChanged = onWifiPortChanged,
                                    onKeepTerminalOpenChanged = onKeepTerminalOpenChanged,
                                    onRefreshNetworkIp = onRefreshNetworkIp,
                                    onCopyConnect = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        cm?.setPrimaryClip(ClipData.newPlainText("ADB Connect", engineState.adbConnectCommand))
                                        copiedConnect = true
                                    },
                                    onCopyCommand = {
                                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        cm?.setPrimaryClip(ClipData.newPlainText("Koda Shell", engineState.adbCommand))
                                        copiedCommand = true
                                    },
                                    onOpenDevSettings = {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                                        }
                                    },
                                    onVerifyConnection = {
                                        onVerifyConnection()
                                        onSilentPoll()
                                    }
                                )
                            }
                        }
                    }
                }

                // Bottom Signature Footer
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.45f)
                    ) {
                        Text(
                            text = "developed by Gabriel Freitas",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                                .testTag("developer_signature_text")
                        )
                    }
                }
            }
        }
    }

    if (errorPopup != null) {
        AlertDialog(
            onDismissRequest = onDismissErrorPopup,
            icon = {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = "Aviso",
                    tint = CoralError,
                    modifier = Modifier.size(34.dp)
                )
            },
            title = { Text(errorPopup.errorName) },
            text = { Text(errorPopup.reason) },
            confirmButton = {
                Button(
                    onClick = onDismissErrorPopup,
                    modifier = Modifier.testTag("error_popup_ok_button")
                ) {
                    Text("Entendi")
                }
            }
        )
    }
}

@Composable
fun PcAdbCommandSection(
    engineState: KodaEngineState,
    copiedConnect: Boolean,
    copiedCommand: Boolean,
    onSelectTransportMode: (AdbTransportMode) -> Unit,
    onWifiPortChanged: (String) -> Unit,
    onKeepTerminalOpenChanged: (Boolean) -> Unit,
    onRefreshNetworkIp: () -> Unit,
    onCopyConnect: () -> Unit,
    onCopyCommand: () -> Unit,
    onOpenDevSettings: () -> Unit,
    onVerifyConnection: () -> Unit,
    verifyButtonTag: String = "verify_connection_button"
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = engineState.transportMode == AdbTransportMode.USB,
                onClick = { onSelectTransportMode(AdbTransportMode.USB) },
                leadingIcon = { Icon(Icons.Default.Usb, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Cabo USB") },
                modifier = Modifier
                    .weight(1f)
                    .testTag("pc_transport_usb_chip")
            )
            FilterChip(
                selected = engineState.transportMode == AdbTransportMode.WIFI_NETWORK,
                onClick = {
                    onSelectTransportMode(AdbTransportMode.WIFI_NETWORK)
                    onRefreshNetworkIp()
                },
                leadingIcon = { Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text("Wi-Fi / Rede") },
                modifier = Modifier
                    .weight(1f)
                    .testTag("pc_transport_wifi_chip")
            )
        }

        if (engineState.transportMode == AdbTransportMode.WIFI_NETWORK) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = engineState.wifiPort,
                    onValueChange = onWifiPortChanged,
                    label = { Text("Porta ADB Wi-Fi") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRefreshNetworkIp, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Refresh, contentDescription = "Atualizar IP", tint = ElectricCyan)
                }
            }
            Surface(
                color = Color(0xFF0A0D18),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = engineState.adbConnectCommand,
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricCyan,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onCopyConnect, modifier = Modifier.size(40.dp)) {
                        Icon(
                            imageVector = if (copiedConnect) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                            contentDescription = "Copiar adb connect",
                            tint = if (copiedConnect) EmeraldActive else Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Manter terminal aberto (--child)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = engineState.keepTerminalOpenMode,
                onCheckedChange = onKeepTerminalOpenChanged
            )
        }

        Surface(
            color = Color(0xFF0A0D18),
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.small)
        ) {
            Text(
                text = engineState.adbCommand,
                style = MaterialTheme.typography.labelSmall,
                color = EmeraldActive,
                modifier = Modifier.padding(10.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onOpenDevSettings,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Depuração", maxLines = 1)
            }
            FilledTonalButton(
                onClick = onCopyCommand,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = if (copiedCommand) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(if (copiedCommand) "Copiado" else "Copiar Comando", maxLines = 1)
            }
        }

        Button(
            onClick = onVerifyConnection,
            colors = ButtonDefaults.buttonColors(
                containerColor = ElectricCyan,
                contentColor = Color(0xFF021926)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag(verifyButtonTag)
        ) {
            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Verificar Conexão PC (ADB)", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun CoreSubsystemRow(
    icon: ImageVector,
    title: String,
    detail: String,
    ready: Boolean,
    accent: Color
) {
    val activeTint = if (ready) EmeraldActive else accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(Color.White.copy(alpha = if (ready) 0.08f else 0.03f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(activeTint.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (ready) Icons.Default.CheckCircle else icon,
                contentDescription = title,
                tint = activeTint,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.72f)
            )
        }
    }
}
