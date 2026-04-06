package com.example.meeraai.data

import kotlinx.serialization.Serializable

@Serializable
data class BotConfig(
    val telegramBotToken: String = "",
    val firebaseCredentialsJson: String = "",
    val firebaseDatabaseId: String = "(default)",
    val encryptionKey: String = "",
    val ollamaHost: String = "https://ollama.com",
    val ollamaModel: String = "gemini-3-flash-preview:cloud",
    val elevenlabsDefaultVoiceId: String = "21m00Tcm4TlvDq8ikWAM",
    val maxChatHistory: Int = 20,
    val typingDelayMin: Float = 1.0f,
    val typingDelayMax: Float = 3.0f,
    val debugReactions: Boolean = false,
    val debugVoice: Boolean = false,
)

@Serializable
data class UserData(
    val telegramUsername: String? = null,
    val firstName: String? = null,
    val profileName: String? = null,
    val profileBio: String? = null,
    val tone: String = "casual",
    val replyLength: String = "medium",
    val voiceId: String? = null,
    val voiceOnly: Boolean = false,
    val ollamaKeys: List<String> = emptyList(),
    val elevenlabsKeys: List<String> = emptyList(),
    val lastInteraction: Double = 0.0,
    val chatId: Long = 0,
    val proactiveSent: Boolean = false,
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
)

data class OllamaMessage(
    val role: String,
    val content: String,
)

sealed class BotStatus {
    data object Stopped : BotStatus()
    data object Starting : BotStatus()
    data object Running : BotStatus()
    data class Error(val message: String) : BotStatus()
}

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: String,
    val tag: String,
    val message: String,
)
