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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.example.ui.theme.EmeraldDeep
import com.example.ui.theme.EmeraldFullScreen
import kotlinx.coroutines.delay

@Composable
fun ActivationScreen(
    engineState: KodaEngineState,
    errorPopup: AppErrorReport?,
    onSilentPoll: () -> Unit,
    onVerifyConnection: () -> Unit,
    onSelectTransportMode: (AdbTransportMode) -> Unit,
    onWifiPortChanged: (String) -> Unit,
    onKeepTerminalOpenChanged: (Boolean) -> Unit,
    onRefreshNetworkIp: () -> Unit,
    onDismissErrorPopup: () -> Unit,
    onActivatedOpenApp: () -> Unit,
    onEnterDiagnostic: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var copiedFeedback by remember { mutableStateOf(false) }
    var copiedConnectFeedback by remember { mutableStateOf(false) }

    // Fast polling while waiting for PC ADB; auto-opens Home shortly after turning full green
    LaunchedEffect(engineState.isActive) {
        if (!engineState.isActive) {
            while (true) {
                delay(1200L)
                onSilentPoll()
            }
        } else {
            delay(1100L)
            onActivatedOpenApp()
        }
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Full-screen artistic paper-collage floral + cat + horse + dog background
        Image(
            painter = painterResource(id = R.drawable.img_activation_artistic_bg),
            contentDescription = "Fundo artístico com flor de papéis, gato, cavalo e cachorro",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        if (engineState.isActive) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                EmeraldFullScreen.copy(alpha = 0.94f),
                                EmeraldDeep.copy(alpha = 0.97f)
                            )
                        )
                    )
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFF0A0816).copy(alpha = 0.62f),
                                Color(0xFF0C0A1B).copy(alpha = 0.86f),
                                Color(0xFF0A0816).copy(alpha = 0.94f)
                            )
                        )
                    )
            )
        }

        if (engineState.isActive) {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(tween(300)) + scaleIn(initialScale = 0.92f)
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                            .border(2.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Privilégios ativados",
                            tint = Color.White,
                            modifier = Modifier.size(58.dp)
                        )
                    }

                    Text(
                        text = "Privilégios ADB Ativados!",
                        style = MaterialTheme.typography.displaySmall,
                        color = Color.White,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Koda Engine conectada (UID ${engineState.serverUid} · Shell).\nPrivilégios permanentes aplicados! Você pode desconectar o PC, desligar o Modo Desenvolvedor e usar direto no cabo USB OTG.",
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
                            .height(60.dp)
                            .testTag("activated_open_app_button")
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Ativado",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "developed by Gabriel Freitas",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.78f)
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .widthIn(max = 660.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(1.5.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
                                contentScale = ContentScale.Crop
                            )
                            Column {
                                Text(
                                    text = "Koda Wintab",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = Color.White
                                )
                                Text(
                                    text = "Ativação ADB · USB ou Wi-Fi / Rede",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.80f)
                                )
                            }
                        }

                        Surface(
                            shape = CircleShape,
                            color = (if (engineState.permanentlyActivated) EmeraldFullScreen else AmberAccent).copy(alpha = 0.22f),
                            modifier = Modifier.border(
                                1.dp,
                                (if (engineState.permanentlyActivated) EmeraldFullScreen else AmberAccent).copy(alpha = 0.65f),
                                CircleShape
                            )
                        ) {
                            Text(
                                text = if (engineState.permanentlyActivated) "ADB PERMANENTE" else "AGUARDANDO ADB",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (engineState.permanentlyActivated) EmeraldFullScreen else AmberAccent,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        FilterChip(
                            selected = engineState.transportMode == AdbTransportMode.USB,
                            onClick = { onSelectTransportMode(AdbTransportMode.USB) },
                            leadingIcon = { Icon(Icons.Default.Usb, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            label = { Text("Via Cabo USB") },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("mode_adb_usb")
                        )
                        FilterChip(
                            selected = engineState.transportMode == AdbTransportMode.WIFI_NETWORK,
                            onClick = { onSelectTransportMode(AdbTransportMode.WIFI_NETWORK) },
                            leadingIcon = { Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            label = { Text("Via Wi-Fi / Rede") },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("mode_adb_wifi")
                        )
                    }

                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF131326).copy(alpha = 0.88f)
                        ),
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
                                MaterialTheme.shapes.large
                            )
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Terminal,
                                    contentDescription = "Comando ADB",
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                                Text(
                                    text = if (engineState.transportMode == AdbTransportMode.USB) {
                                        "Canto do Código ADB (Windows / PC)"
                                    } else {
                                        "Ativação ADB Sem Fio (Wi-Fi / Rede)"
                                    },
                                    style = MaterialTheme.typography.titleLarge,
                                    color = Color.White
                                )
                            }

                            if (engineState.transportMode == AdbTransportMode.WIFI_NETWORK) {
                                Text(
                                    text = "Ative Depuração por Wi-Fi nas Opções do Desenvolvedor, conecte com o IP abaixo e execute o comando de ativação no PC.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.84f)
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        color = Color(0xFF0A0A14).copy(alpha = 0.9f),
                                        shape = MaterialTheme.shapes.small,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column {
                                                Text("IP do aparelho", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                                Text(engineState.localIpAddress, style = MaterialTheme.typography.labelMedium, color = Color.White)
                                            }
                                            IconButton(onClick = onRefreshNetworkIp, modifier = Modifier.size(36.dp)) {
                                                Icon(Icons.Default.Refresh, contentDescription = "Atualizar IP", tint = Color.White)
                                            }
                                        }
                                    }
                                    OutlinedTextField(
                                        value = engineState.wifiPort,
                                        onValueChange = onWifiPortChanged,
                                        label = { Text("Porta") },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.width(105.dp)
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
                                            }.onFailure {
                                                runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
                                            }
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                    ) {
                                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Depuração Wi-Fi", maxLines = 1)
                                    }
                                    FilledTonalButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("ADB Connect", engineState.adbConnectCommand))
                                            copiedConnectFeedback = true
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(48.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(if (copiedConnectFeedback) "IP Copiado!" else "1. Copiar Connect", maxLines = 1)
                                    }
                                }
                            } else {
                                Text(
                                    text = "1. Conecte o cabo USB com Depuração USB ativada.\n" +
                                        "2. Copie o comando abaixo e cole no CMD (platform-tools).\n" +
                                        "3. Quando aparecer 'adb command success' no PC, o app fica verde e abre sozinho.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.84f)
                                )
                            }

                            Surface(
                                color = Color(0xFF0A0A14).copy(alpha = 0.94f),
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "Comando direto (sem token) · Retorno esperado: adb command success",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                    HorizontalDivider(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        color = Color.White.copy(alpha = 0.14f)
                                    )
                                    Text(
                                        text = engineState.adbCommand,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color(0xFFDCE7FF)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Modo terminal aberto (anti-bloqueio OEM)",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Use caso o celular encerre o servidor em segundo plano",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White.copy(alpha = 0.65f)
                                    )
                                }
                                Switch(
                                    checked = engineState.keepTerminalOpenMode,
                                    onCheckedChange = onKeepTerminalOpenChanged
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                FilledTonalButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(
                                            ClipData.newPlainText("Koda ADB", engineState.adbCommand)
                                        )
                                        copiedFeedback = true
                                    },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(50.dp)
                                        .testTag("copy_adb_command_button")
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(if (copiedFeedback) "Copiado!" else "Copiar ADB")
                                }

                                Button(
                                    onClick = onVerifyConnection,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(50.dp)
                                        .testTag("verify_connection_button")
                                ) {
                                    Icon(Icons.Default.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Verificar")
                                }
                            }
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (engineState.permanentlyActivated) {
                        FilledTonalButton(
                            onClick = onActivatedOpenApp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("continue_permanent_otg_button")
                        ) {
                            Icon(Icons.Default.Usb, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Continuar no Modo USB OTG Direto (Já Ativado)")
                        }
                    }

                    Text(
                        text = "Modo Diagnóstico (abrir sem ADB)",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.72f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onEnterDiagnostic() }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                            .testTag("enter_diagnostic_button")
                    )

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
                    contentDescription = "Erro na ativação",
                    tint = CoralError,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "Falha na Ativação / Execução",
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        color = CoralError.copy(alpha = 0.14f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "Nome do erro:",
                                style = MaterialTheme.typography.labelSmall,
                                color = CoralError
                            )
                            Text(
                                text = errorPopup.errorName,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Text(
                        text = "Por que não funcionou:",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = errorPopup.reason,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (errorPopup.detail.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = errorPopup.detail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onDismissErrorPopup,
                    modifier = Modifier.testTag("error_popup_ok_button")
                ) {
                    Text("Entendi")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    onDismissErrorPopup()
                    onEnterDiagnostic()
                }) {
                    Text("Abrir Diagnóstico")
                }
            }
        )
    }
}
