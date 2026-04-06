package com.example.meeraai.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.meeraai.data.BotConfig
import com.example.meeraai.data.BotStatus
import com.example.meeraai.data.LogEntry
import com.example.meeraai.data.SettingsStore
import com.example.meeraai.service.BotForegroundService
import com.example.meeraai.service.EncryptionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class BotViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)

    private val _botStatus = MutableStateFlow<BotStatus>(BotStatus.Stopped)
    val botStatus: StateFlow<BotStatus> = _botStatus

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    // Settings flows
    val botToken = settingsStore.botToken.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val encryptionKey = settingsStore.encryptionKey.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val botName = settingsStore.botName.stateIn(viewModelScope, SharingStarted.Eagerly, "Meera")
    val ollamaHost = settingsStore.ollamaHost.stateIn(viewModelScope, SharingStarted.Eagerly, "https://ollama.com")
    val ollamaModel = settingsStore.ollamaModel.stateIn(viewModelScope, SharingStarted.Eagerly, "gemini-3-flash-preview:cloud")
    val elevenlabsVoiceId = settingsStore.elevenlabsVoiceId.stateIn(viewModelScope, SharingStarted.Eagerly, "21m00Tcm4TlvDq8ikWAM")

    // Validate config
    val isConfigValid: StateFlow<Boolean> = combine(botToken, encryptionKey) { token, key ->
        token.isNotBlank() && key.isNotBlank()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // Whether we need to request notification permission
    private val _needsNotificationPermission = MutableStateFlow(false)
    val needsNotificationPermission: StateFlow<Boolean> = _needsNotificationPermission

    init {
        // Observe the foreground service state
        viewModelScope.launch {
            BotForegroundService.isRunning.collect { running ->
                _botStatus.value = if (running) BotStatus.Running else BotStatus.Stopped
            }
        }
        viewModelScope.launch {
            BotForegroundService.logs.collect { _logs.value = it }
        }
        viewModelScope.launch {
            BotForegroundService.statusText.collect { text ->
                when {
                    text.startsWith("Error") -> _botStatus.value = BotStatus.Error(text.removePrefix("Error: "))
                    text == "Starting..." -> _botStatus.value = BotStatus.Starting
                    text == "Running" -> _botStatus.value = BotStatus.Running
                    text == "Stopped" -> _botStatus.value = BotStatus.Stopped
                }
            }
        }
    }

    fun saveBotToken(token: String) {
        viewModelScope.launch { settingsStore.saveBotToken(token) }
    }

    fun saveEncryptionKey(key: String) {
        viewModelScope.launch { settingsStore.saveEncryptionKey(key) }
    }

    fun saveBotName(name: String) {
        viewModelScope.launch { settingsStore.saveBotName(name) }
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
            val config = settingsStore.getBotConfig()
            if (config.telegramBotToken.isBlank()) {
                _botStatus.value = BotStatus.Error("Bot token is required")
                return@launch
            }
            if (config.encryptionKey.isBlank()) {
                _botStatus.value = BotStatus.Error("Encryption key is required")
                return@launch
            }

            // Check notification permission on Android 13+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = ContextCompat.checkSelfPermission(
                    getApplication(), Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    _needsNotificationPermission.value = true
                    return@launch
                }
            }

            _botStatus.value = BotStatus.Starting
            BotForegroundService.start(getApplication())
        }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        _needsNotificationPermission.value = false
        if (granted) {
            _botStatus.value = BotStatus.Starting
            BotForegroundService.start(getApplication())
        } else {
            // Start anyway — notification just won't show on Android 13+
            _botStatus.value = BotStatus.Starting
            BotForegroundService.start(getApplication())
        }
    }

    fun stopBot() {
        BotForegroundService.stop(getApplication())
        _botStatus.value = BotStatus.Stopped
    }
}
