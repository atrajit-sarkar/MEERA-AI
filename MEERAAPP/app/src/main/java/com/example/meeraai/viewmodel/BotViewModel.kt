package com.example.meeraai.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.meeraai.data.BotConfig
import com.example.meeraai.data.BotStatus
import com.example.meeraai.data.LogEntry
import com.example.meeraai.data.SettingsStore
import com.example.meeraai.service.EncryptionService
import com.example.meeraai.service.FirebaseService
import com.example.meeraai.service.TelegramBotService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class BotViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)
    private var botService: TelegramBotService? = null

    private val _botStatus = MutableStateFlow<BotStatus>(BotStatus.Stopped)
    val botStatus: StateFlow<BotStatus> = _botStatus

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    // Settings flows
    val botToken = settingsStore.botToken.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val encryptionKey = settingsStore.encryptionKey.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val ollamaHost = settingsStore.ollamaHost.stateIn(viewModelScope, SharingStarted.Eagerly, "https://ollama.com")
    val ollamaModel = settingsStore.ollamaModel.stateIn(viewModelScope, SharingStarted.Eagerly, "gemini-3-flash-preview:cloud")
    val elevenlabsVoiceId = settingsStore.elevenlabsVoiceId.stateIn(viewModelScope, SharingStarted.Eagerly, "21m00Tcm4TlvDq8ikWAM")

    // Validate config
    val isConfigValid: StateFlow<Boolean> = combine(botToken, encryptionKey) { token, key ->
        token.isNotBlank() && key.isNotBlank()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun saveBotToken(token: String) {
        viewModelScope.launch { settingsStore.saveBotToken(token) }
    }

    fun saveEncryptionKey(key: String) {
        viewModelScope.launch { settingsStore.saveEncryptionKey(key) }
    }

    fun saveOllamaHost(host: String) {
        viewModelScope.launch { settingsStore.saveOllamaHost(host) }
    }

    fun saveOllamaModel(model: String) {
        viewModelScope.launch { settingsStore.saveOllamaModel(model) }
    }

    fun saveElevenlabsVoiceId(voiceId: String) {
        viewModelScope.launch { settingsStore.saveElevenlabsVoiceId(voiceId) }
    }

    fun generateEncryptionKey() {
        val key = EncryptionService.generateEncryptionKey()
        viewModelScope.launch { settingsStore.saveEncryptionKey(key) }
    }

    fun startBot() {
        viewModelScope.launch {
            try {
                _botStatus.value = BotStatus.Starting

                val config = settingsStore.getBotConfig()
                if (config.telegramBotToken.isBlank()) {
                    _botStatus.value = BotStatus.Error("Bot token is required")
                    return@launch
                }
                if (config.encryptionKey.isBlank()) {
                    _botStatus.value = BotStatus.Error("Encryption key is required")
                    return@launch
                }

                val tempDir = File(getApplication<Application>().cacheDir, "meera_temp")
                val firebase = FirebaseService(getApplication())
                botService = TelegramBotService(config, tempDir, firebase)

                // Observe logs
                viewModelScope.launch {
                    botService!!.logs.collect { _logs.value = it }
                }

                botService!!.start(viewModelScope)
                _botStatus.value = BotStatus.Running

            } catch (e: Exception) {
                _botStatus.value = BotStatus.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun stopBot() {
        botService?.stop()
        botService = null
        _botStatus.value = BotStatus.Stopped
    }

    override fun onCleared() {
        super.onCleared()
        botService?.stop()
    }
}
