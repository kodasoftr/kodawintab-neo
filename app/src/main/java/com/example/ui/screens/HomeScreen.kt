package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Monitor
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.display.TargetDisplayInfo
import com.example.driver.DriverTelemetry
import com.example.hub.HubSlotAssignment
import com.example.hub.RoutingMode
import com.example.koda.engine.KodaEngineState
import com.example.ui.theme.AmberAccent
import com.example.ui.theme.EmeraldActive
import com.example.usb.ConnectedUsbDeviceInfo
import java.util.Locale

@Composable
fun HomeScreen(
    engineState: KodaEngineState,
    telemetry: DriverTelemetry,
    usbDevices: List<ConnectedUsbDeviceInfo>,
    displays: List<TargetDisplayInfo>,
    hubSlots: List<HubSlotAssignment>,
    onOpenMesa: () -> Unit,
    onOpenHub: () -> Unit,
    onOpenActivation: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeColor = when {
        engineState.isActive -> EmeraldActive
        engineState.permanentlyActivated -> Color(0xFF00D4B4)
        else -> AmberAccent
    }
    val coopSlotsCount = hubSlots.count { it.routingMode == RoutingMode.COOP }
    val routedSlotsCount = hubSlots.count { it.routingMode == RoutingMode.ROUTED }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surface
                    )
                )
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 840.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Enhanced Wintab Studio Header
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
                        contentDescription = "Koda Wintab",
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(13.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Column {
                        Text(
                            text = "Koda Wintab 2.0",
                            style = MaterialTheme.typography.headlineMedium
                        )
                        Text(
                            text = "Driver Global & Hub USB · Gabriel Freitas",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = onOpenActivation,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(activeColor.copy(alpha = 0.14f))
                        .testTag("home_engine_status_button")
                ) {
                    Icon(
                        imageVector = if (engineState.isActive || engineState.permanentlyActivated) Icons.Default.VerifiedUser else Icons.Default.Security,
                        contentDescription = if (engineState.isActive || engineState.permanentlyActivated) "Ver Privilégios Permanentes" else "Ativar Koda Engine",
                        tint = activeColor
                    )
                }
            }

            // Compact Live Telemetry Strip
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TelemetryChip(
                        icon = Icons.Default.Speed,
                        label = "Latência",
                        value = String.format(Locale.US, "%.2f ms · %d Hz", telemetry.totalLatencyMs, telemetry.pollingRateHz),
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    TelemetryChip(
                        icon = Icons.Default.Usb,
                        label = "USB OTG",
                        value = "${usbDevices.size} detec.",
                        tint = if (usbDevices.isNotEmpty()) EmeraldActive else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TelemetryChip(
                        icon = Icons.Default.Monitor,
                        label = "Telas",
                        value = "${displays.size} ativa(s)",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Privilege & USB OTG Mode Status Banner
            if (engineState.isActive) {
                Surface(
                    color = EmeraldActive.copy(alpha = 0.12f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, EmeraldActive.copy(alpha = 0.45f), MaterialTheme.shapes.medium)
                        .clickable { onOpenActivation() }
                        .testTag("home_privilege_active_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Privilégio Permanente Ativo (UID ${engineState.serverUid})",
                                style = MaterialTheme.typography.titleMedium,
                                color = EmeraldActive
                            )
                            Text(
                                text = "PC pode ser desconectado e ADB/APK seguem livres no USB. Toque para detalhes.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = "Ver Privilégios Ativos",
                            tint = EmeraldActive
                        )
                    }
                }
            } else if (engineState.permanentlyActivated) {
                Surface(
                    color = activeColor.copy(alpha = 0.12f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, activeColor.copy(alpha = 0.45f), MaterialTheme.shapes.medium)
                        .clickable { onOpenActivation() }
                        .testTag("home_otg_permanent_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Ativação Permanente · Modo USB OTG Direto",
                                style = MaterialTheme.typography.titleMedium,
                                color = activeColor
                            )
                            Text(
                                text = "Ativado 1x: driver OTG e permissões seguem ativos com Desenvolvedor desligado. Toque para ver.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = "Ver Privilégios OTG Direto",
                            tint = activeColor
                        )
                    }
                }
            } else {
                Surface(
                    color = AmberAccent.copy(alpha = 0.12f),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, AmberAccent.copy(alpha = 0.4f), MaterialTheme.shapes.medium)
                        .clickable { onOpenActivation() }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Modo Diagnóstico · Engine Inativa",
                                style = MaterialTheme.typography.titleMedium,
                                color = AmberAccent
                            )
                            Text(
                                text = "Toque para ativar uma única vez via ADB e liberar USB OTG + sistema.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = "Ir para Ativação",
                            tint = AmberAccent
                        )
                    }
                }
            }

            // Two Main Primary Action Buttons (Mesa & Hub)
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val isWide = maxWidth >= 560.dp
                if (isWide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        HomePrimaryActionCard(
                            title = "Mesa",
                            subtitle = "Driver Global · Wacom, Huion, Ugee S640, XP-Pen",
                            badge = "${telemetry.activeProfileName} · ${telemetry.maxNativePressure + 1} níveis",
                            detail = "Pipeline zero-alocação, curva OTD, rotação automática e teste de latência.",
                            icon = Icons.Default.Draw,
                            accent = MaterialTheme.colorScheme.primary,
                            onClick = onOpenMesa,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("home_mesa_button")
                        )
                        HomePrimaryActionCard(
                            title = "Hub",
                            subtitle = "2 Mouses · 2 Teclados · 2 Mesas",
                            badge = "$routedSlotsCount roteado(s) · $coopSlotsCount em Coop",
                            detail = "Roteamento por janela, cursor .cur/.ani/.png e Modo Coop automático.",
                            icon = Icons.Default.Hub,
                            accent = MaterialTheme.colorScheme.secondary,
                            onClick = onOpenHub,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("home_hub_button")
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        HomePrimaryActionCard(
                            title = "Mesa",
                            subtitle = "Driver Global · Wacom, Huion, Ugee S640, XP-Pen",
                            badge = "${telemetry.activeProfileName} · ${telemetry.maxNativePressure + 1} níveis",
                            detail = "Pipeline zero-alocação, curva OTD, rotação automática e teste de latência.",
                            icon = Icons.Default.Draw,
                            accent = MaterialTheme.colorScheme.primary,
                            onClick = onOpenMesa,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("home_mesa_button")
                        )
                        HomePrimaryActionCard(
                            title = "Hub",
                            subtitle = "2 Mouses · 2 Teclados · 2 Mesas",
                            badge = "$routedSlotsCount roteado(s) · $coopSlotsCount em Coop",
                            detail = "Roteamento por janela, cursor .cur/.ani/.png e Modo Coop automático.",
                            icon = Icons.Default.Hub,
                            accent = MaterialTheme.colorScheme.secondary,
                            onClick = onOpenHub,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("home_hub_button")
                        )
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = "developed by Gabriel Freitas · Koda Wintab Native Driver",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun TelemetryChip(
    icon: ImageVector,
    label: String,
    value: String,
    tint: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun HomePrimaryActionCard(
    title: String,
    subtitle: String,
    badge: String,
    detail: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
            .border(1.5.dp, accent.copy(alpha = 0.50f), MaterialTheme.shapes.extraLarge)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            accent.copy(alpha = 0.16f),
                            Color.Transparent
                        )
                    )
                )
                .padding(22.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(accent.copy(alpha = 0.20f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = accent,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = accent.copy(alpha = 0.16f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Abrir",
                                style = MaterialTheme.typography.labelLarge,
                                color = accent
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.titleMedium,
                    color = accent
                )
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
