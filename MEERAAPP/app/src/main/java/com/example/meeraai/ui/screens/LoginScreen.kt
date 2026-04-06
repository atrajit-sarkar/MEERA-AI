package com.example.meeraai.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meeraai.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    isVerifying: Boolean,
    errorMessage: String?,
    onConnect: (String) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MeeraBackground),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // ── Logo ──
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(MeeraPurple, MeeraPink),
                            start = Offset(gradientOffset, 0f),
                            end = Offset(gradientOffset + 200f, 200f),
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text("M", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }

            Spacer(Modifier.height(20.dp))

            Text(
                "Meera AI",
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = MeeraOnSurface,
            )
            Text(
                "Your AI Bestie on Telegram",
                fontSize = 14.sp,
                color = MeeraGrayLight,
            )

            Spacer(Modifier.height(48.dp))

            // ── Connect with Telegram section ──
            Card(
                colors = CardDefaults.cardColors(containerColor = MeeraSurface),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Send,
                            contentDescription = null,
                            tint = Color(0xFF26A5E4),
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Connect with Telegram",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MeeraOnSurface,
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    Text(
                        "Enter the bot token from @BotFather",
                        fontSize = 13.sp,
                        color = MeeraGrayLight,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(Modifier.height(20.dp))

                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Bot Token", color = MeeraGray) },
                        visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { showToken = !showToken }) {
                                Icon(
                                    if (showToken) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = "Toggle visibility",
                                    tint = MeeraGrayLight,
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MeeraOnSurface,
                            unfocusedTextColor = MeeraOnSurface,
                            cursorColor = MeeraPurpleLight,
                            focusedBorderColor = MeeraPurple,
                            unfocusedBorderColor = MeeraSurfaceVariant,
                            focusedContainerColor = MeeraSurfaceVariant,
                            unfocusedContainerColor = MeeraSurfaceVariant,
                        ),
                        shape = RoundedCornerShape(14.dp),
                        singleLine = true,
                    )

                    Spacer(Modifier.height(20.dp))

                    // ── Connect Button ──
                    Button(
                        onClick = { onConnect(token.trim()) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        enabled = token.isNotBlank() && !isVerifying,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF26A5E4),
                            disabledContainerColor = Color(0xFF26A5E4).copy(alpha = 0.4f),
                        ),
                    ) {
                        if (isVerifying) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Color.White,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Verifying...", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        } else {
                            Icon(Icons.Filled.Send, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Connect", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // ── Error message ──
                    AnimatedVisibility(visible = errorMessage != null) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp),
                            colors = CardDefaults.cardColors(containerColor = MeeraRed.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Error, contentDescription = null, tint = MeeraRed, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(errorMessage ?: "", color = MeeraRed, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Info ──
            Text(
                "Your encryption key will be generated automatically\nand linked to this Telegram bot account.",
                fontSize = 12.sp,
                color = MeeraGrayLight.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                lineHeight = 18.sp,
            )
        }
    }
}
