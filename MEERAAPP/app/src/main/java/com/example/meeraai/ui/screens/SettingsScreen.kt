package com.example.meeraai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.meeraai.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    botName: String,
    ollamaHost: String,
    ollamaModel: String,
    elevenlabsVoiceId: String,
    onBotNameChange: (String) -> Unit,
    onOllamaHostChange: (String) -> Unit,
    onOllamaModelChange: (String) -> Unit,
    onElevenlabsVoiceIdChange: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = MeeraOnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MeeraBackground,
                    titleContentColor = MeeraOnSurface,
                ),
            )
        },
        containerColor = MeeraBackground,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            // ── Bot Identity ──
            SettingsSection(title = "🤖 Bot Identity") {
                SettingsTextField(
                    label = "Bot Name",
                    value = botName,
                    onValueChange = onBotNameChange,
                    placeholder = "Meera",
                )
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MeeraPurple.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(Icons.Filled.Person, contentDescription = null, tint = MeeraPurpleLight, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "The bot will introduce itself with this name and use it throughout all conversations. Restart the bot after changing.",
                            fontSize = 12.sp,
                            color = MeeraGrayLight,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Ollama Settings ──
            SettingsSection(title = "🤖 Ollama Configuration") {
                SettingsTextField(
                    label = "Ollama Host URL",
                    value = ollamaHost,
                    onValueChange = onOllamaHostChange,
                    placeholder = "https://your-ollama-cloud-endpoint",
                )
                Spacer(Modifier.height(12.dp))
                SettingsTextField(
                    label = "Model Name",
                    value = ollamaModel,
                    onValueChange = onOllamaModelChange,
                    placeholder = "gemini-3-flash-preview:cloud",
                )

                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MeeraPurple.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(Icons.Filled.Info, contentDescription = null, tint = MeeraPurpleLight, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "The Ollama host should point to your cloud-hosted Ollama instance. Each user adds their own API key via /add_ollama_key in Telegram.",
                            fontSize = 12.sp,
                            color = MeeraGrayLight,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── ElevenLabs Settings ──
            SettingsSection(title = "🎙 Voice Settings") {
                SettingsTextField(
                    label = "Default Voice ID",
                    value = elevenlabsVoiceId,
                    onValueChange = onElevenlabsVoiceIdChange,
                    placeholder = "21m00Tcm4TlvDq8ikWAM",
                )
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MeeraPink.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, tint = MeeraPinkLight, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Default voice is Rachel (free). Users can set custom voices via /setvoice. Each user provides their own ElevenLabs key.",
                            fontSize = 12.sp,
                            color = MeeraGrayLight,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Features Overview ──
            SettingsSection(title = "✨ Features") {
                FeatureItem("💬", "Smart Chat", "AI-powered conversations with personality tiers")
                FeatureItem("🎤", "Voice Messages", "Send & receive voice using ElevenLabs TTS/STT")
                FeatureItem("😎", "Reactions", "Context-aware emoji reactions to messages")
                FeatureItem("📢", "Proactive Chat", "Meera texts first when users go quiet")
                FeatureItem("🔑", "Key Rotation", "Multiple API keys with automatic failover")
                FeatureItem("👤", "Profiles", "Per-user names, bios, tone & voice preferences")
                FeatureItem("🔒", "Encryption", "All API keys encrypted with AES-GCM")
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column {
        Text(
            title,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = MeeraOnSurface,
        )
        Spacer(Modifier.height(12.dp))
        Card(
            colors = CardDefaults.cardColors(containerColor = MeeraSurfaceVariant),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                content()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    Column {
        Text(label, fontSize = 13.sp, color = MeeraGrayLight, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, color = MeeraGray, fontSize = 14.sp) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = MeeraOnSurface,
                unfocusedTextColor = MeeraOnSurface,
                cursorColor = MeeraPurpleLight,
                focusedBorderColor = MeeraPurple,
                unfocusedBorderColor = MeeraSurface,
                focusedContainerColor = MeeraSurface,
                unfocusedContainerColor = MeeraSurface,
            ),
            shape = RoundedCornerShape(12.dp),
            singleLine = true,
            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
        )
    }
}

@Composable
private fun FeatureItem(emoji: String, title: String, desc: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 20.sp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MeeraOnSurface)
            Text(desc, fontSize = 12.sp, color = MeeraGrayLight)
        }
    }
}
