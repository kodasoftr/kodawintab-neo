package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.koda.engine.KodaEngineState
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.EmeraldActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivilegeStatusScreen(
    engineState: KodaEngineState,
    overlayGranted: Boolean,
    usbDeviceCount: Int,
    onBack: () -> Unit,
    onSilentReconnect: () -> Unit,
    onGrantOverlayViaAdb: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showAdbReferenceDialog by remember { mutableStateOf(false) }
    var copiedCommand by remember { mutableStateOf(false) }

    val statusAccent = if (engineState.isActive) EmeraldActive else Color(0xFF00D4B4)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Privilégios Permanentes", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = if (engineState.isActive) {
                                "Shell Ativo (UID ${engineState.serverUid}) · Modo OTG Direto"
                            } else {
                                "Ativação 1-Vez Salva · Operando via OTG Permanente"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = statusAccent
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("privileges_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
                .testTag("privilege_status_screen"),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 840.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Hero Permanent Activation Card (Read-only guarantee: never revokes or deactivates privileges)
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.5.dp, statusAccent.copy(alpha = 0.65f), MaterialTheme.shapes.extraLarge)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        statusAccent.copy(alpha = 0.18f),
                                        Color.Transparent
                                    )
                                )
                            )
                            .padding(20.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                            .size(52.dp)
                                            .clip(CircleShape)
                                            .background(statusAccent.copy(alpha = 0.20f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.VerifiedUser,
                                            contentDescription = "Privilégios permanentes ativos",
                                            tint = statusAccent,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = "ATIVADO PERMANENTE",
                                            style = MaterialTheme.typography.titleLarge,
                                            color = statusAccent,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Privilégio salvo · Só reseta ao limpar dados do app",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Surface(
                                    color = statusAccent.copy(alpha = 0.16f),
                                    shape = CircleShape
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = "Protegido contra desativação",
                                        tint = statusAccent,
                                        modifier = Modifier
                                            .padding(8.dp)
                                            .size(18.dp)
                                    )
                                }
                            }

                            Text(
                                text = "Você já concluiu a ativação única via ADB. Pode desconectar o cabo do PC e desligar o Modo Desenvolvedor: o driver USB OTG da mesa e suas permissões continuam liberados automaticamente sempre que abrir o app (mesmo após Forçar Parada).",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                // Privilege Breakdown Rows
                PrivilegeLayerCard(
                    icon = Icons.Default.Usb,
                    title = "USB OTG Seletivo & Pass-Through ADB",
                    badge = if (usbDeviceCount > 0) "$usbDeviceCount periférico(s) em uso" else "OTG Pronto",
                    badgeColor = EmeraldActive,
                    description = "O Koda reivindica apenas mesas digitalizadoras e mouses configurados no Hub. Comandos ADB do PC, instalação de APKs, MTP/arquivos e teclados passam livremente pelo sistema sem bloqueio."
                )

                PrivilegeLayerCard(
                    icon = Icons.Default.Terminal,
                    title = if (engineState.isActive) {
                        "KodaShellServer Conectado (UID ${engineState.serverUid} · PID ${engineState.serverPid})"
                    } else {
                        "Motor Shell em Standby OTG"
                    },
                    badge = if (engineState.isActive) "Socket Ativo" else "OTG Direto Ativo",
                    badgeColor = if (engineState.isActive) EmeraldActive else Color(0xFF00D4B4),
                    description = if (engineState.isActive) {
                        "Injeção global de eventos e ocultamento nativo do cursor estão operando em tempo real entre apps."
                    } else {
                        "Com o PC desconectado ou Opções do Desenvolvedor desligadas, os recursos OTG, curvas, calibração e overlay continuam ativos normalmente sem pedir ADB novamente."
                    }
                )

                PrivilegeLayerCard(
                    icon = Icons.Default.Layers,
                    title = "Cursor Global sobre Outros Apps (.cur / .ani / .png)",
                    badge = if (overlayGranted) "Liberado" else "Pendente",
                    badgeColor = if (overlayGranted) EmeraldActive else AmberAccent,
                    description = if (overlayGranted) {
                        "Permissão de sobreposição concedida. Ao pausar ou usar Forçar Parada ela congela e retorna ativa quando você reabrir o Koda Wintab."
                    } else {
                        "Conceda a permissão 'Sobrepor a outros apps' uma vez para manter o cursor customizado por cima de outros aplicativos."
                    }
                )

                if (!overlayGranted) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = onOpenOverlaySettings,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        ) {
                            Text("Abrir Permissão Overlay")
                        }
                        if (engineState.isActive) {
                            Button(
                                onClick = onGrantOverlayViaAdb,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                            ) {
                                Text("Liberar via Engine")
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilledTonalButton(
                        onClick = onSilentReconnect,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("privileges_silent_reconnect_button")
                    ) {
                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Atualizar Status", maxLines = 1)
                    }
                    OutlinedButton(
                        onClick = { showAdbReferenceDialog = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("show_adb_reference_button")
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Comando ADB", maxLines = 1)
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = "developed by Gabriel Freitas · Ativação permanente protegida",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
        }
    }

    if (showAdbReferenceDialog) {
        AlertDialog(
            onDismissRequest = { showAdbReferenceDialog = false },
            icon = { Icon(Icons.Default.Terminal, contentDescription = null, tint = EmeraldActive) },
            title = { Text("Comando ADB de Referência") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Seu app já está com a ativação permanente salva. Só use este comando novamente caso você limpe os dados de armazenamento do Koda Wintab ou reinicie o aparelho e queira religar o daemon Shell:",
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
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        cm?.setPrimaryClip(ClipData.newPlainText("Koda ADB", engineState.adbCommand))
                        copiedCommand = true
                    }
                ) {
                    Icon(
                        imageVector = if (copiedCommand) Icons.Default.CheckCircle else Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (copiedCommand) "Copiado" else "Copiar")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAdbReferenceDialog = false }) {
                    Text("Fechar")
                }
            }
        )
    }
}

@Composable
private fun PrivilegeLayerCard(
    icon: ImageVector,
    title: String,
    badge: String,
    badgeColor: Color,
    description: String
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
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
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(icon, contentDescription = null, tint = badgeColor, modifier = Modifier.size(22.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium)
                }
                Surface(
                    color = badgeColor.copy(alpha = 0.14f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
