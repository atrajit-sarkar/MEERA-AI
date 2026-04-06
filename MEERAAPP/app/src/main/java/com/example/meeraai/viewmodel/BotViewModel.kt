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
import com.example.meeraai.service.FirebaseService
import com.example.meeraai.service.ReliableDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class BotViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsStore = SettingsStore(application)

    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient = OkHttpClient.Builder()
        .dns(ReliableDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val _botStatus = MutableStateFlow<BotStatus>(BotStatus.Stopped)
    val botStatus: StateFlow<BotStatus> = _botStatus

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    // Settings flows
    val isLoggedIn = settingsStore.isLoggedIn.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val botToken = settingsStore.botToken.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val encryptionKey = settingsStore.encryptionKey.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val botName = settingsStore.botName.stateIn(viewModelScope, SharingStarted.Eagerly, "Meera")
    val customSystemPrompt = settingsStore.customSystemPrompt.stateIn(viewModelScope, SharingStarted.Eagerly, "")
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

    // Login state
    private val _isVerifying = MutableStateFlow(false)
    val isVerifying: StateFlow<Boolean> = _isVerifying
    private val _loginError = MutableStateFlow<String?>(null)
    val loginError: StateFlow<String?> = _loginError

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
        viewModelScope.launch {
            settingsStore.saveBotName(name)
            syncBotConfigToFirestore()
        }
    }

    fun saveCustomSystemPrompt(prompt: String) {
        viewModelScope.launch {
            settingsStore.saveCustomSystemPrompt(prompt)
            syncBotConfigToFirestore()
        }
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

    fun verifyAndLogin(token: String) {
        if (token.isBlank()) {
            _loginError.value = "Please enter a bot token"
            return
        }
        _isVerifying.value = true
        _loginError.value = null

        viewModelScope.launch {
            try {
                val botInfo = withContext(Dispatchers.IO) {
                    val req = Request.Builder()
                        .url("https://api.telegram.org/bot$token/getMe")
                        .build()
                    val resp = httpClient.newCall(req).execute()
                    val body = resp.body?.string() ?: throw RuntimeException("Empty response")
                    val obj = json.parseToJsonElement(body).jsonObject
                    val ok = obj["ok"]?.jsonPrimitive?.content == "true"
                    if (!ok) {
                        val desc = obj["description"]?.jsonPrimitive?.content ?: "Invalid token"
                        throw RuntimeException(desc)
                    }
                    obj["result"]?.jsonObject
                }

                val botUsername = botInfo?.get("username")?.jsonPrimitive?.content ?: "unknown"

                // Save token
                settingsStore.saveBotToken(token)

                // Generate encryption key one-time (only if not already set)
                val existingKey = settingsStore.getBotConfig().encryptionKey
                if (existingKey.isBlank()) {
                    val newKey = EncryptionService.generateEncryptionKey()
                    settingsStore.saveEncryptionKey(newKey)
                }

                // Mark logged in
                settingsStore.setLoggedIn(true)

                _isVerifying.value = false
            } catch (e: Exception) {
                _loginError.value = e.message ?: "Connection failed"
                _isVerifying.value = false
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            stopBot()
            settingsStore.saveBotToken("")
            settingsStore.setLoggedIn(false)
        }
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

    private fun syncBotConfigToFirestore() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val firebase = FirebaseService(getApplication())
                firebase.init(dbId = "pussy")
                val config = settingsStore.getBotConfig()
                firebase.saveBotAppConfig(
                    mapOf(
                        "bot_name" to config.botName,
                        "custom_system_prompt" to config.customSystemPrompt,
                    )
                )
            } catch (_: Exception) { /* best-effort sync */ }
        }
    }
}
