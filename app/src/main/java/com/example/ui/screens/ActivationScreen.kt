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
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.AppErrorReport
import com.example.R
import com.example.koda.engine.AdbTransportMode
import com.example.koda.engine.KodaEngineState
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var isBootingCore by remember { mutableStateOf(false) }
    var bootStage by remember { mutableIntStateOf(0) }
    var showOptionalWirelessSheet by remember { mutableStateOf(false) }
    var copiedCommand by remember { mutableStateOf(false) }

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
                        text = "Koda Core Engine Ativa!",
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Ativação única concluída diretamente no aparelho.\nPen Display, Kodasoftr Desktop, Stream MediaCodec e WinTab 8192 liberados sem PC e sem Modo Desenvolvedor.",
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
                    .widthIn(max = 620.dp)
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Header Brand & Zero-PC Badge
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
                                    text = "Ativação Única · Engine Própria",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = ElectricCyan
                                )
                            }
                        }

                        Surface(
                            shape = CircleShape,
                            color = ElectricCyan.copy(alpha = 0.14f),
                            modifier = Modifier.border(1.dp, ElectricCyan.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Text(
                                text = "SEM PC · ZERO ADB",
                                style = MaterialTheme.typography.labelSmall,
                                color = ElectricCyan,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    // Hero Engine Card
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF121426).copy(alpha = 0.92f)
                        ),
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.5.dp,
                                Brush.linearGradient(listOf(WintabViolet.copy(alpha = 0.7f), ElectricCyan.copy(alpha = 0.7f))),
                                MaterialTheme.shapes.extraLarge
                            )
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Text(
                                text = "Motor Principal Autônomo",
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White
                            )
                            Text(
                                text = "Ative uma única vez no próprio aparelho para liberar o driver USB OTG, Display Virtual Kodasoftr para Pen Display, codificador GPU MediaCodec e caneta WinTab 8192 níveis — sem cabo de PC e sem Modo Desenvolvedor.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White.copy(alpha = 0.84f)
                            )

                            HorizontalDivider(color = Color.White.copy(alpha = 0.10f))

                            CoreSubsystemRow(
                                icon = Icons.Default.Monitor,
                                title = "Display Virtual & Área Kodasoftr",
                                detail = "Cria displayId isolado (1920×1080 / 2560×1440) com barra de tarefas, menu e relógio.",
                                ready = bootStage >= 1,
                                accent = ElectricCyan
                            )
                            CoreSubsystemRow(
                                icon = Icons.Default.Speed,
                                title = "Koda Stream Engine (GPU + USB Bulk)",
                                detail = "Captura Surface + MediaCodec H.264 direta e envio USB UsbDeviceConnection.bulkTransfer().",
                                ready = bootStage >= 2,
                                accent = WintabViolet
                            )
                            CoreSubsystemRow(
                                icon = Icons.Default.Apps,
                                title = "Koda App Router & Multitarefa Nativa",
                                detail = "Vincula aplicativos ao displayId da Pen Display com controle pelos Apps Recentes.",
                                ready = bootStage >= 3,
                                accent = ElectricCyan
                            )
                            CoreSubsystemRow(
                                icon = Icons.Default.Draw,
                                title = "Driver HID WinTab 8192 + Pen Display",
                                detail = "Detecção automática por VID/PID (Wacom, Huion, XP-Pen, Ugee) e eixo de pressão.",
                                ready = bootStage >= 4,
                                accent = EmeraldActive
                            )

                            Spacer(Modifier.height(4.dp))

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
                                        "Inicializando Motor Principal ($bootStage/4)..."
                                    } else {
                                        "Ativar Motor Principal (1 Toque)"
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                // Bottom Optional Wireless Shell Bridge + Signature
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(
                        onClick = {
                            onSelectTransportMode(AdbTransportMode.WIFI_NETWORK)
                            onRefreshNetworkIp()
                            showOptionalWirelessSheet = true
                        },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("optional_wireless_bridge_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.78f),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "Ponte Shell Sem Fio (Opcional para TaskStack global)",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.78f)
                        )
                    }

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

    if (showOptionalWirelessSheet) {
        AlertDialog(
            onDismissRequest = { showOptionalWirelessSheet = false },
            icon = { Icon(Icons.Default.Terminal, contentDescription = null, tint = ElectricCyan) },
            title = { Text("Ponte Shell Sem Fio (Opcional)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "O Koda Core Engine já funciona sem PC e sem Modo Desenvolvedor. Caso queira também interceptar qualquer app do sistema via TaskStackListener (moveRootTaskToDisplay), conecte a Depuração Sem Fio nativa:",
                        style = MaterialTheme.typography.bodyMedium
                    )
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
                            onClick = {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Ajustes", maxLines = 1)
                        }
                        FilledTonalButton(
                            onClick = {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(ClipData.newPlainText("Koda Shell", engineState.adbCommand))
                                copiedCommand = true
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(if (copiedCommand) "Copiado" else "Copiar", maxLines = 1)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onVerifyConnection()
                    onSilentPoll()
                    showOptionalWirelessSheet = false
                }) {
                    Text("Verificar Ponte")
                }
            },
            dismissButton = {
                TextButton(onClick = { showOptionalWirelessSheet = false }) {
                    Text("Fechar")
                }
            }
        )
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
