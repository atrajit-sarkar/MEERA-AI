package com.example.meeraai.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "meera_settings")

class SettingsStore(private val context: Context) {

    companion object {
        private val BOT_TOKEN_KEY = stringPreferencesKey("bot_token")
        private val FIREBASE_CREDS_KEY = stringPreferencesKey("firebase_creds")
        private val FIREBASE_DB_ID_KEY = stringPreferencesKey("firebase_db_id")
        private val ENCRYPTION_KEY = stringPreferencesKey("encryption_key")
        private val OLLAMA_HOST_KEY = stringPreferencesKey("ollama_host")
        private val OLLAMA_MODEL_KEY = stringPreferencesKey("ollama_model")
        private val ELEVENLABS_VOICE_KEY = stringPreferencesKey("elevenlabs_voice_id")
    }

    val botToken: Flow<String> = context.dataStore.data.map { it[BOT_TOKEN_KEY] ?: "" }
    val firebaseCreds: Flow<String> = context.dataStore.data.map { it[FIREBASE_CREDS_KEY] ?: "" }
    val firebaseDbId: Flow<String> = context.dataStore.data.map { it[FIREBASE_DB_ID_KEY] ?: "(default)" }
    val encryptionKey: Flow<String> = context.dataStore.data.map { it[ENCRYPTION_KEY] ?: "" }
    val ollamaHost: Flow<String> = context.dataStore.data.map { it[OLLAMA_HOST_KEY] ?: "https://ollama.com" }
    val ollamaModel: Flow<String> = context.dataStore.data.map { it[OLLAMA_MODEL_KEY] ?: "gemini-3-flash-preview:cloud" }
    val elevenlabsVoiceId: Flow<String> = context.dataStore.data.map { it[ELEVENLABS_VOICE_KEY] ?: "21m00Tcm4TlvDq8ikWAM" }

    suspend fun saveBotToken(token: String) {
        context.dataStore.edit { it[BOT_TOKEN_KEY] = token }
    }

    suspend fun saveFirebaseCreds(creds: String) {
        context.dataStore.edit { it[FIREBASE_CREDS_KEY] = creds }
    }

    suspend fun saveFirebaseDbId(dbId: String) {
        context.dataStore.edit { it[FIREBASE_DB_ID_KEY] = dbId }
    }

    suspend fun saveEncryptionKey(key: String) {
        context.dataStore.edit { it[ENCRYPTION_KEY] = key }
    }

    suspend fun saveOllamaHost(host: String) {
        context.dataStore.edit { it[OLLAMA_HOST_KEY] = host }
    }

    suspend fun saveOllamaModel(model: String) {
        context.dataStore.edit { it[OLLAMA_MODEL_KEY] = model }
    }

    suspend fun saveElevenlabsVoiceId(voiceId: String) {
        context.dataStore.edit { it[ELEVENLABS_VOICE_KEY] = voiceId }
    }

    suspend fun getBotConfig(): BotConfig {
        var config = BotConfig()
        context.dataStore.edit { prefs ->
            config = BotConfig(
                telegramBotToken = prefs[BOT_TOKEN_KEY] ?: "",
                firebaseCredentialsJson = prefs[FIREBASE_CREDS_KEY] ?: "",
                firebaseDatabaseId = prefs[FIREBASE_DB_ID_KEY] ?: "(default)",
                encryptionKey = prefs[ENCRYPTION_KEY] ?: "",
                ollamaHost = prefs[OLLAMA_HOST_KEY] ?: "https://ollama.com",
                ollamaModel = prefs[OLLAMA_MODEL_KEY] ?: "gemini-3-flash-preview:cloud",
                elevenlabsDefaultVoiceId = prefs[ELEVENLABS_VOICE_KEY] ?: "21m00Tcm4TlvDq8ikWAM",
            )
        }
        return config
    }
}
