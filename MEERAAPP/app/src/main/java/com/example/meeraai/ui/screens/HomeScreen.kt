package com.example.meeraai.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meeraai.data.BotStatus
import com.example.meeraai.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    botStatus: BotStatus,
    isConfigValid: Boolean,
    onStartBot: () -> Unit,
    onStopBot: () -> Unit,
    onLogout: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToLogs: () -> Unit,
) {
    val scrollState = rememberScrollState()

    // Animated gradient for the header
    val infiniteTransition = rememberInfiniteTransition(label = "gradient")
    val gradientOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "offset"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MeeraBackground)
            .verticalScroll(scrollState)
            .padding(bottom = 32.dp),
    ) {
        // ── Header with gradient ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(MeeraPurpleDark, MeeraPurple, MeeraPink),
                        start = Offset(gradientOffset, 0f),
                        end = Offset(gradientOffset + 500f, 500f),
                    )
                )
                .padding(top = 48.dp, bottom = 32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Meera avatar
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(MeeraPurpleLight, MeeraPink)
                            )
                        )
                        .border(3.dp, Color.White.copy(alpha = 0.3f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("M", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    "Meera AI",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    "Your AI Bestie on Telegram",
                    fontSize = 14.sp,
                    color = Color.White.copy(alpha = 0.7f),
                )

                Spacer(Modifier.height(16.dp))

                // Status badge
                StatusBadge(botStatus)
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Start/Stop Button ──
        Column(modifier = Modifier.padding(horizontal = 20.dp)) {
            val isRunning = botStatus is BotStatus.Running
            val isStarting = botStatus is BotStatus.Starting

            Button(
                onClick = { if (isRunning) onStopBot() else onStartBot() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .shadow(
                        elevation = if (isRunning) 0.dp else 12.dp,
                        shape = RoundedCornerShape(20.dp),
                        ambientColor = if (isRunning) MeeraRed else MeeraPurple,
                        spotColor = if (isRunning) MeeraRed else MeeraPurple,
                    ),
                enabled = !isStarting && (isRunning || isConfigValid),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) MeeraRed else MeeraPurple,
                    disabledContainerColor = MeeraGray.copy(alpha = 0.3f),
                ),
            ) {
                if (isStarting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Starting...", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Icon(
                        if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isRunning) "Stop Bot" else "Run Bot",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // Error message
            AnimatedVisibility(visible = botStatus is BotStatus.Error) {
                if (botStatus is BotStatus.Error) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        colors = CardDefaults.cardColors(containerColor = MeeraRed.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Error, contentDescription = null, tint = MeeraRed, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(botStatus.message, color = MeeraRed, fontSize = 13.sp)
                        }
                    }
                }
            }

            Spacer(Modifier.height(28.dp))

            // ── Quick Access Cards ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QuickCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Settings,
                    label = "Settings",
                    color = MeeraPurpleLight,
                    onClick = onNavigateToSettings,
                )
                QuickCard(
                    modifier = Modifier.weight(1f),
                    icon = Icons.Outlined.Terminal,
                    label = "Logs",
                    color = MeeraGreen,
                    onClick = onNavigateToLogs,
                )
            }

            Spacer(Modifier.height(12.dp))

            // ── Logout ──
            OutlinedButton(
                onClick = onLogout,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MeeraRed,
                ),
                border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                    brush = Brush.linearGradient(listOf(MeeraRed.copy(alpha = 0.5f), MeeraRed.copy(alpha = 0.5f)))
                ),
            ) {
                Icon(Icons.Filled.Logout, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Disconnect Bot", fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(20.dp))

            // ── Info Card ──
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MeeraSurfaceVariant),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("💡 How it works", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = MeeraOnSurface)
                    Spacer(Modifier.height(8.dp))
                    InfoItem("1. Configure Ollama host in Settings")
                    InfoItem("2. Hit Run Bot — Meera goes online!")
                    InfoItem("3. Users add their own API keys via /start")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "🔒 Encryption key was generated at login and is tied to your bot.",
                        fontSize = 12.sp,
                        color = MeeraGrayLight,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadge(status: BotStatus) {
    val (color, text, icon) = when (status) {
        is BotStatus.Stopped -> Triple(MeeraGray, "Offline", Icons.Filled.Circle)
        is BotStatus.Starting -> Triple(MeeraYellow, "Starting...", Icons.Filled.Circle)
        is BotStatus.Running -> Triple(MeeraGreen, "Running", Icons.Filled.Circle)
        is BotStatus.Error -> Triple(MeeraRed, "Error", Icons.Filled.Error)
    }

    // Pulsing animation for running state
    val pulseAlpha = if (status is BotStatus.Running) {
        val anim = rememberInfiniteTransition(label = "pulse")
        anim.animateFloat(
            initialValue = 0.4f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alpha"
        ).value
    } else 1f

    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.15f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = pulseAlpha))
            )
            Spacer(Modifier.width(8.dp))
            Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        color = MeeraOnSurface.copy(alpha = 0.8f),
    )
}

@Composable
private fun QuickCard(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier,
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MeeraSurfaceVariant),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, color = MeeraOnSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun InfoItem(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        color = MeeraGrayLight,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun meeraTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MeeraOnSurface,
    unfocusedTextColor = MeeraOnSurface,
    cursorColor = MeeraPurpleLight,
    focusedBorderColor = MeeraPurple,
    unfocusedBorderColor = MeeraSurfaceVariant,
    focusedContainerColor = MeeraSurface,
    unfocusedContainerColor = MeeraSurface,
)
