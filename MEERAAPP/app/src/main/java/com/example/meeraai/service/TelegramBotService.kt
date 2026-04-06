package com.example.meeraai.service

import com.example.meeraai.data.BotConfig
import com.example.meeraai.data.ChatMessage
import com.example.meeraai.data.LogEntry
import com.example.meeraai.data.OllamaMessage
import com.example.meeraai.data.UserData
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Full Telegram Bot engine — mirrors main.py + bot/commands.py + bot/handlers.py
 * Uses Telegram Bot API directly via HTTP (no aiogram dependency).
 */
class TelegramBotService(
    private val config: BotConfig,
    private val tempDir: File,
    private val firebase: FirebaseService,
) {
    private val client = OkHttpClient.Builder()
        .dns(ReliableDns)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS) // Long polling needs long timeout
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private val baseUrl get() = "https://api.telegram.org/bot${config.telegramBotToken}"

    // Local cache backed by Firebase for persistence & consistency
    private val users = ConcurrentHashMap<Long, UserData>()
    private val chatHistories = ConcurrentHashMap<Long, MutableList<ChatMessage>>()
    private val fsmStates = ConcurrentHashMap<Long, String>() // user_id -> state name (transient)

    private var pollingJob: Job? = null
    private var proactiveJob: Job? = null
    private var offset = 0L

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private fun log(level: String, tag: String, message: String) {
        val entry = LogEntry(level = level, tag = tag, message = message)
        _logs.value = (_logs.value + entry).takeLast(500)
    }

    // ─── Bot Lifecycle ─────────────────────────────────────────

    suspend fun start(scope: CoroutineScope) {
        if (_isRunning.value) return
        _isRunning.value = true
        tempDir.mkdirs()

        // Initialize Firebase
        try {
            firebase.init(dbId = "pussy")
            log("INFO", "Firebase", "Firestore connected (project: meeraai-482bb)")
            // Seed default error messages if needed
            firebase.seedErrorMessages()
        } catch (e: Exception) {
            log("WARN", "Firebase", "Init failed — running with local cache only: ${e.message}")
        }

        // Verify bot token
        try {
            val me = apiCall("getMe")
            val username = me?.get("username")?.jsonPrimitive?.content ?: "unknown"
            val fullName = me?.get("first_name")?.jsonPrimitive?.content ?: "Meera"
            log("INFO", "Bot", "Started: @$username ($fullName)")
        } catch (e: Exception) {
            log("ERROR", "Bot", "Failed to verify token: ${e.message}")
            _isRunning.value = false
            throw e
        }

        // Start polling
        pollingJob = scope.launch(Dispatchers.IO) {
            log("INFO", "Polling", "Starting long polling...")
            while (isActive && _isRunning.value) {
                try {
                    val updates = getUpdates()
                    for (update in updates) {
                        try {
                            processUpdate(update)
                        } catch (e: Exception) {
                            log("ERROR", "Update", "Failed: ${e.message}")
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("ERROR", "Polling", "Error: ${e.message}")
                    delay(5000) // Wait before retrying
                }
            }
        }

        // Start proactive messaging loop
        proactiveJob = scope.launch(Dispatchers.IO) {
            delay(60_000) // Wait 1 min before first check
            while (isActive && _isRunning.value) {
                try {
                    checkProactiveMessages()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("ERROR", "Proactive", "Loop error: ${e.message}")
                }
                delay(5 * 60 * 1000) // Check every 5 minutes
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        proactiveJob?.cancel()
        _isRunning.value = false
        log("INFO", "Bot", "Stopped")
    }

    // ─── Telegram API ──────────────────────────────────────────

    private suspend fun apiCall(method: String, params: JsonObject? = null): JsonObject? {
        return withContext(Dispatchers.IO) {
            val request = if (params != null) {
                Request.Builder()
                    .url("$baseUrl/$method")
                    .post(params.toString().toRequestBody("application/json".toMediaType()))
                    .addHeader("Content-Type", "application/json")
                    .build()
            } else {
                Request.Builder()
                    .url("$baseUrl/$method")
                    .build()
            }

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw RuntimeException("Empty response")
            val jsonResponse = json.parseToJsonElement(body).jsonObject

            if (jsonResponse["ok"]?.jsonPrimitive?.boolean != true) {
                val desc = jsonResponse["description"]?.jsonPrimitive?.content ?: "Unknown error"
                throw RuntimeException("Telegram API error: $desc")
            }

            jsonResponse["result"]?.jsonObject
        }
    }

    private suspend fun apiCallRaw(method: String, params: JsonObject): JsonElement? {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$baseUrl/$method")
                .post(params.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw RuntimeException("Empty response")
            json.parseToJsonElement(body).jsonObject["result"]
        }
    }

    private suspend fun sendMessage(chatId: Long, text: String, replyToMessageId: Long? = null) {
        val params = buildJsonObject {
            put("chat_id", chatId)
            put("text", text)
            put("parse_mode", "Markdown")
            replyToMessageId?.let { put("reply_to_message_id", it) }
        }
        try {
            apiCall("sendMessage", params)
        } catch (e: Exception) {
            // Retry without parse_mode if Markdown fails
            val fallback = buildJsonObject {
                put("chat_id", chatId)
                put("text", text)
                replyToMessageId?.let { put("reply_to_message_id", it) }
            }
            apiCall("sendMessage", fallback)
        }
    }

    private suspend fun sendChatAction(chatId: Long, action: String) {
        try {
            apiCall("sendChatAction", buildJsonObject {
                put("chat_id", chatId)
                put("action", action)
            })
        } catch (_: Exception) { }
    }

    private suspend fun setMessageReaction(chatId: Long, messageId: Long, emoji: String) {
        try {
            val params = buildJsonObject {
                put("chat_id", chatId)
                put("message_id", messageId)
                put("reaction", buildJsonArray {
                    addJsonObject {
                        put("type", "emoji")
                        put("emoji", emoji)
                    }
                })
            }
            apiCall("setMessageReaction", params)
        } catch (e: Exception) {
            // Reactions may not be supported in all chats
        }
    }

    private suspend fun deleteMessage(chatId: Long, messageId: Long) {
        try {
            apiCall("deleteMessage", buildJsonObject {
                put("chat_id", chatId)
                put("message_id", messageId)
            })
        } catch (_: Exception) { }
    }

    private suspend fun sendVoiceFromFile(chatId: Long, audioFile: File, replyToMessageId: Long? = null) {
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/sendVoice"
            val multipartBody = okhttp3.MultipartBody.Builder()
                .setType(okhttp3.MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId.toString())
                .addFormDataPart(
                    "voice", audioFile.name,
                    audioFile.readBytes().toRequestBody("audio/mpeg".toMediaType())
                )

            replyToMessageId?.let {
                multipartBody.addFormDataPart("reply_to_message_id", it.toString())
            }

            val request = Request.Builder()
                .url(url)
                .post(multipartBody.build())
                .build()

            client.newCall(request).execute()
        }
    }

    private suspend fun downloadFile(fileId: String, outputFile: File): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val fileInfo = apiCall("getFile", buildJsonObject { put("file_id", fileId) })
                val filePath = fileInfo?.get("file_path")?.jsonPrimitive?.content ?: return@withContext false

                val downloadUrl = "https://api.telegram.org/file/bot${config.telegramBotToken}/$filePath"
                val request = Request.Builder().url(downloadUrl).build()
                val response = client.newCall(request).execute()

                response.body?.byteStream()?.use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                true
            } catch (e: Exception) {
                log("ERROR", "Download", "File download failed: ${e.message}")
                false
            }
        }
    }

    // ─── Polling ───────────────────────────────────────────────

    private suspend fun getUpdates(): List<JsonObject> {
        return withContext(Dispatchers.IO) {
            val params = buildJsonObject {
                put("offset", offset)
                put("timeout", 30)
                put("allowed_updates", buildJsonArray {
                    add("message")
                    add("callback_query")
                })
            }

            val request = Request.Builder()
                .url("$baseUrl/getUpdates")
                .post(params.toString().toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext emptyList()
            val jsonResponse = json.parseToJsonElement(body).jsonObject

            if (jsonResponse["ok"]?.jsonPrimitive?.boolean != true) {
                return@withContext emptyList()
            }

            val results = jsonResponse["result"]?.jsonArray ?: return@withContext emptyList()
            val updates = results.map { it.jsonObject }

            if (updates.isNotEmpty()) {
                offset = updates.last()["update_id"]!!.jsonPrimitive.long + 1
            }

            updates
        }
    }

    // ─── Update Processing ─────────────────────────────────────

    private suspend fun processUpdate(update: JsonObject) {
        val message = update["message"]?.jsonObject ?: return
        val chat = message["chat"]?.jsonObject ?: return
        val chatId = chat["id"]?.jsonPrimitive?.long ?: return
        val from = message["from"]?.jsonObject
        val userId = from?.get("id")?.jsonPrimitive?.long ?: return
        val messageId = message["message_id"]?.jsonPrimitive?.long ?: 0

        // Handle voice messages
        val voice = message["voice"]?.jsonObject
        if (voice != null) {
            handleVoiceMessage(userId, chatId, messageId, voice, from)
            return
        }

        val text = message["text"]?.jsonPrimitive?.content ?: return

        // Check FSM state first
        val currentState = fsmStates[userId]
        if (currentState != null) {
            handleFsmState(userId, chatId, messageId, text, currentState)
            return
        }

        // Handle commands
        if (text.startsWith("/")) {
            handleCommand(userId, chatId, messageId, text, from)
        } else {
            handleTextMessage(userId, chatId, messageId, text, from)
        }
    }

    // ─── Commands — mirrors bot/commands.py ────────────────────

    private suspend fun handleCommand(userId: Long, chatId: Long, messageId: Long, text: String, from: JsonObject) {
        val command = text.split(" ").first().lowercase().removePrefix("/").split("@").first()

        when (command) {
            "start" -> cmdStart(userId, chatId, from)
            "help" -> cmdHelp(chatId)
            "profile" -> cmdProfile(userId, chatId)
            "setname" -> {
                fsmStates[userId] = "waiting_for_name"
                sendMessage(chatId, "What should I call you? 😊")
            }
            "setbio" -> {
                fsmStates[userId] = "waiting_for_bio"
                sendMessage(chatId, "Tell me a bit about yourself! 📝")
            }
            "tone" -> cmdTone(chatId)
            "tone_casual" -> {
                val user = getOrCreateUser(userId)
                updateUser(userId, user.copy(tone = "casual"))
                sendMessage(chatId, "Alright, keeping it chill! 😎")
            }
            "tone_formal" -> {
                val user = getOrCreateUser(userId)
                updateUser(userId, user.copy(tone = "formal"))
                sendMessage(chatId, "Understood. I'll maintain a more formal tone. 🎩")
            }
            "replies_short" -> {
                val user = getOrCreateUser(userId)
                updateUser(userId, user.copy(replyLength = "short"))
                sendMessage(chatId, "Short and sweet it is! ✨")
            }
            "replies_medium" -> {
                val user = getOrCreateUser(userId)
                updateUser(userId, user.copy(replyLength = "medium"))
                sendMessage(chatId, "Balanced replies — got it! 👍")
            }
            "replies_long" -> {
                val user = getOrCreateUser(userId)
                updateUser(userId, user.copy(replyLength = "long"))
                sendMessage(chatId, "I'll be more detailed from now on! 📖")
            }
            "talk" -> {
                val user = getOrCreateUser(userId)
                val newVal = !user.voiceOnly
                updateUser(userId, user.copy(voiceOnly = newVal))
                if (newVal) sendMessage(chatId, "🗣 Voice-only mode ON — I'll reply with voice whenever possible!")
                else sendMessage(chatId, "💬 Voice-only mode OFF — back to normal text + occasional voice!")
            }
            "add_ollama_key" -> {
                fsmStates[userId] = "waiting_for_ollama_key"
                sendMessage(chatId, "🔑 Send me your Ollama API key.\n\n⚠️ The key will be encrypted and stored securely.\n💡 Tip: Delete your message after sending for extra safety!")
            }
            "add_elevenlabs_key" -> {
                fsmStates[userId] = "waiting_for_elevenlabs_key"
                sendMessage(chatId, "🎙 Send me your ElevenLabs API key.\n\n⚠️ The key will be encrypted and stored securely.")
            }
            "list_keys" -> cmdListKeys(userId, chatId)
            "remove_key" -> {
                fsmStates[userId] = "waiting_for_remove_key"
                sendMessage(chatId, "Which key to remove? Reply with:\n\n`ollama <index>` or `elevenlabs <index>`\n\nExample: `ollama 0` or `elevenlabs 1`\nUse /list\\_keys to see indices.")
            }
            "setvoice" -> {
                fsmStates[userId] = "waiting_for_voice_id"
                val user = getOrCreateUser(userId)
                val current = user.voiceId ?: "Default (Rachel)"
                sendMessage(chatId, "🎙 *Current voice:* $current\n\nSend me your ElevenLabs Voice ID to use a custom voice.\nSend `reset` to go back to the default voice.")
            }
            "clear" -> {
                fsmStates[userId] = "waiting_for_clear_confirm"
                sendMessage(chatId, "🧹 *Are you sure you want to clear our entire chat history?*\n\nThis will:\n• Delete all messages from memory\n• Reset our relationship back to strangers\n• Meera won't remember anything from before\n\nType `yes` to confirm or `no` to cancel.")
            }
            else -> sendMessage(chatId, "Unknown command. Use /help to see available commands.")
        }
    }

    // ─── FSM State Handlers ────────────────────────────────────

    private suspend fun handleFsmState(userId: Long, chatId: Long, messageId: Long, text: String, state: String) {
        fsmStates.remove(userId)

        when (state) {
            "waiting_for_name" -> {
                val name = text.trim().take(50)
                val user = getOrCreateUser(userId)
                updateUser(userId, user.copy(profileName = name))
                sendMessage(chatId, "Got it! I'll call you *$name* from now on 💫")
            }
            "waiting_for_bio" -> {
                val bio = text.trim().take(200)
                val user = getOrCreateUser(userId)
                updateUser(userId, user.copy(profileBio = bio))
                sendMessage(chatId, "Updated your bio! I'll keep that in mind when we chat 😊")
            }
            "waiting_for_ollama_key" -> {
                val key = text.trim()
                if (key.length < 8) {
                    fsmStates[userId] = "waiting_for_ollama_key"
                    sendMessage(chatId, "That doesn't look like a valid key. Try again?")
                    return
                }
                val encrypted = EncryptionService.encrypt(key, config.encryptionKey)
                val user = getOrCreateUser(userId)
                updateUser(userId, user.copy(ollamaKeys = user.ollamaKeys + encrypted))
                deleteMessage(chatId, messageId)
                sendMessage(chatId, "✅ Ollama key added successfully! Your message was deleted for safety 🔒")
                log("INFO", "Keys", "Added Ollama key for user $userId")
            }
            "waiting_for_elevenlabs_key" -> {
                val key = text.trim()
                if (key.length < 8) {
                    fsmStates[userId] = "waiting_for_elevenlabs_key"
                    sendMessage(chatId, "That doesn't look like a valid key. Try again?")
                    return
                }
                val encrypted = EncryptionService.encrypt(key, config.encryptionKey)
                val user = getOrCreateUser(userId)
                updateUser(userId, user.copy(elevenlabsKeys = user.elevenlabsKeys + encrypted))
                deleteMessage(chatId, messageId)
                sendMessage(chatId, "✅ ElevenLabs key added! Message deleted for safety 🔒")
                log("INFO", "Keys", "Added ElevenLabs key for user $userId")
            }
            "waiting_for_remove_key" -> {
                val parts = text.trim().lowercase().split(" ")
                if (parts.size != 2) {
                    sendMessage(chatId, "Invalid format. Use: `ollama 0` or `elevenlabs 1`")
                    return
                }
                val index = parts[1].toIntOrNull()
                if (index == null) {
                    sendMessage(chatId, "Index must be a number.")
                    return
                }
                val user = getOrCreateUser(userId)
                when (parts[0]) {
                    "ollama" -> {
                        if (index in user.ollamaKeys.indices) {
                            updateUser(userId, user.copy(ollamaKeys = user.ollamaKeys.toMutableList().apply { removeAt(index) }))
                            sendMessage(chatId, "✅ Key removed!")
                        } else sendMessage(chatId, "❌ Invalid index. Check /list\\_keys")
                    }
                    "elevenlabs" -> {
                        if (index in user.elevenlabsKeys.indices) {
                            updateUser(userId, user.copy(elevenlabsKeys = user.elevenlabsKeys.toMutableList().apply { removeAt(index) }))
                            sendMessage(chatId, "✅ Key removed!")
                        } else sendMessage(chatId, "❌ Invalid index. Check /list\\_keys")
                    }
                    else -> sendMessage(chatId, "Use `ollama` or `elevenlabs` as the type.")
                }
            }
            "waiting_for_voice_id" -> {
                val voiceText = text.trim()
                if (voiceText.lowercase() == "reset") {
                    val user = getOrCreateUser(userId)
                    updateUser(userId, user.copy(voiceId = null))
                    sendMessage(chatId, "🎙 Voice reset to default (Rachel)! 🔄")
                    return
                }
                if (voiceText.length < 10 || voiceText.length > 40 || !voiceText.all { it.isLetterOrDigit() }) {
                    sendMessage(chatId, "That doesn't look like a valid voice ID. It should be a 20-character alphanumeric string from ElevenLabs.")
                    return
                }
                val user = getOrCreateUser(userId)
                updateUser(userId, user.copy(voiceId = voiceText))
                sendMessage(chatId, "✅ Voice updated! I'll use voice `$voiceText` from now on 🎤")
            }
            "waiting_for_clear_confirm" -> {
                if (text.trim().lowercase() !in listOf("yes", "y")) {
                    sendMessage(chatId, "Phew! History kept safe 😌")
                    return
                }
                val history = chatHistories[userId]
                val count = history?.size ?: 0
                chatHistories.remove(userId)
                // Clear from Firebase too
                try {
                    val fbCount = firebase.clearChatHistory(userId)
                    if (fbCount > count) log("INFO", "Firebase", "Cleared $fbCount messages from Firebase")
                } catch (e: Exception) {
                    log("WARN", "Firebase", "Failed to clear Firebase history: ${e.message}")
                }
                if (count == 0) {
                    sendMessage(chatId, "Already clean! Nothing to clear 🧹")
                } else {
                    sendMessage(chatId, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n🧹 Memory cleared — $count messages forgotten\nEverything above this line, I don't remember.\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n\nHey! I'm Meera 👋 feels like we're meeting for the first time haha")
                }
            }
        }
    }

    // ─── Command Implementations ───────────────────────────────

    private suspend fun cmdStart(userId: Long, chatId: Long, from: JsonObject) {
        val username = from["first_name"]?.jsonPrimitive?.content ?: "there"
        val user = getOrCreateUser(userId)
        updateUser(userId, user.copy(
            telegramUsername = from["username"]?.jsonPrimitive?.content,
            firstName = from["first_name"]?.jsonPrimitive?.content,
            chatId = chatId,
        ))

        sendMessage(chatId, "Hey $username! 💫 I'm Meera — your AI bestie on Telegram!\n\nLet me help you get set up real quick 👇")

        sendMessage(chatId,
            "🔑 *Step 1: Get your AI key (required)*\n\n" +
            "I use Ollama to think & chat. Here's how to get a key:\n\n" +
            "1️⃣ Go to ollama.com and sign in\n" +
            "2️⃣ Click on your profile → Settings → API Keys\n" +
            "3️⃣ Click Create new key and copy it\n" +
            "4️⃣ Come back here and use /add\\_ollama\\_key\n" +
            "5️⃣ Paste your key — I'll encrypt & store it securely\n\n" +
            "⚡ You can add multiple keys for automatic rotation!"
        )

        sendMessage(chatId,
            "🎙 *Step 2: Get your voice key (optional but fun!)*\n\n" +
            "I use ElevenLabs for both voice replies AND listening to your voice messages.\n\n" +
            "1️⃣ Go to elevenlabs.io and create a free account\n" +
            "2️⃣ Click your profile icon → API Keys\n" +
            "3️⃣ Copy your API key\n" +
            "4️⃣ Come back here and use /add\\_elevenlabs\\_key\n" +
            "5️⃣ Paste your key — done!\n\n" +
            "⚡ Multiple keys supported — auto-rotates if one hits rate limits!"
        )

        sendMessage(chatId,
            "✅ *That's it! Once keys are added, just text me and we're vibing.*\n\n" +
            "📋 Quick commands:\n" +
            "/add\\_ollama\\_key — Add AI key\n" +
            "/add\\_elevenlabs\\_key — Add voice key\n" +
            "/profile — Set your name & bio\n" +
            "/setvoice — Use custom voice\n" +
            "/tone — Set chat style\n" +
            "/talk — Voice-only mode\n" +
            "/clear — Wipe memory & start fresh\n" +
            "/help — All commands\n\n" +
            "🔒 Each user uses their own API keys — your data stays yours!"
        )
    }

    private suspend fun cmdHelp(chatId: Long) {
        sendMessage(chatId,
            "✨ *Meera Commands* ✨\n\n" +
            "💬 Just send me a message to chat!\n" +
            "🎤 Send a voice message — I'll listen & can reply with voice too!\n\n" +
            "*🔧 Setup:*\n" +
            "🔑 /add\\_ollama\\_key — Add Ollama API key\n" +
            "🎙 /add\\_elevenlabs\\_key — Add ElevenLabs key\n" +
            "📋 /list\\_keys — View your saved keys\n" +
            "🗑 /remove\\_key — Remove a key\n\n" +
            "*🎨 Personalization:*\n" +
            "👤 /profile — Set your name & bio\n" +
            "🎭 /tone — Set formal/casual + short/long\n" +
            "🎤 /setvoice — Use your own ElevenLabs voice\n" +
            "🗣 /talk — Toggle voice-only mode\n" +
            "🧹 /clear — Wipe chat history & start fresh\n\n" +
            "*💡 Tips:*\n" +
            "• One ElevenLabs key handles both voice replies AND voice listening\n" +
            "• Add multiple keys for automatic rotation\n" +
            "• Use /start to see the full setup guide again"
        )
    }

    private suspend fun cmdProfile(userId: Long, chatId: Long) {
        val user = getOrCreateUser(userId)
        sendMessage(chatId,
            "👤 *Your Profile*\n\n" +
            "*Name:* ${user.profileName ?: "Not set"}\n" +
            "*Bio:* ${user.profileBio ?: "Not set"}\n" +
            "*Tone:* ${user.tone}\n" +
            "*Reply length:* ${user.replyLength}\n" +
            "*Voice:* ${user.voiceId ?: "Default (Rachel)"}\n\n" +
            "To update:\n" +
            "/setname — Change your name\n" +
            "/setbio — Change your bio\n" +
            "/setvoice — Change your voice"
        )
    }

    private suspend fun cmdTone(chatId: Long) {
        sendMessage(chatId,
            "🎭 *Set your conversation preferences:*\n\n" +
            "/tone\\_casual — Casual, friendly chat\n" +
            "/tone\\_formal — More formal responses\n" +
            "/replies\\_short — Keep it brief\n" +
            "/replies\\_medium — Balanced length\n" +
            "/replies\\_long — Detailed responses"
        )
    }

    private suspend fun cmdListKeys(userId: Long, chatId: Long) {
        val user = getOrCreateUser(userId)
        val ollamaList = if (user.ollamaKeys.isEmpty()) "None" else {
            user.ollamaKeys.mapIndexed { i, enc ->
                try {
                    val plain = EncryptionService.decrypt(enc, config.encryptionKey)
                    val masked = if (plain.length > 8) "${plain.take(4)}****${plain.takeLast(4)}" else "****"
                    "[$i] $masked"
                } catch (_: Exception) {
                    "[$i] <corrupted>"
                }
            }.joinToString("\n")
        }
        val elList = if (user.elevenlabsKeys.isEmpty()) "None" else {
            user.elevenlabsKeys.mapIndexed { i, enc ->
                try {
                    val plain = EncryptionService.decrypt(enc, config.encryptionKey)
                    val masked = if (plain.length > 8) "${plain.take(4)}****${plain.takeLast(4)}" else "****"
                    "[$i] $masked"
                } catch (_: Exception) {
                    "[$i] <corrupted>"
                }
            }.joinToString("\n")
        }
        sendMessage(chatId, "🔑 *Your API Keys*\n\n*Ollama Keys:*\n$ollamaList\n\n*ElevenLabs Keys:*\n$elList\n\nUse /remove\\_key to remove a key.")
    }

    // ─── Text Message Handler — mirrors handlers.py ────────────

    private suspend fun handleTextMessage(userId: Long, chatId: Long, messageId: Long, userText: String, from: JsonObject) {
        val user = getOrCreateUser(userId)
        updateUser(userId, user.copy(lastInteraction = System.currentTimeMillis().toDouble(), chatId = chatId, proactiveSent = false))

        // Check if user has Ollama keys
        if (users[userId]!!.ollamaKeys.isEmpty()) {
            sendMessage(chatId, ErrorMessages.getFriendlyError("no_keys"))
            return
        }

        try {
            sendChatAction(chatId, "typing")

            val chatHistory = getChatHistory(userId)
            val userProfile = getUserProfile(userId)
            val ollamaHistory = chatHistory.map { OllamaMessage(it.role, it.content) }

            // React based on comfort level
            reactToMessage(chatId, messageId, userId, userText, ollamaHistory, chatHistory.size)

            // Decide voice or text
            val userData = users[userId]!!
            val voiceOnly = userData.voiceOnly
            val useVoice = config.debugVoice || voiceOnly || shouldReplyWithVoice(chatHistory.size, "text")

            // Get AI response
            val aiResponse = getAiResponse(userId, userText, ollamaHistory, userProfile)

            // Save messages
            saveChatMessage(userId, "user", userText)
            saveChatMessage(userId, "assistant", aiResponse)

            if (useVoice) {
                sendChatAction(chatId, "record_voice")
                delay(Random.nextLong(1000, 2000))
                val sent = sendVoiceReply(userId, chatId, messageId, aiResponse, userData.voiceId)
                if (!sent) {
                    simulateTyping(chatId, aiResponse.length)
                    sendTextReply(chatId, messageId, aiResponse)
                }
            } else {
                simulateTyping(chatId, aiResponse.length)
                sendTextReply(chatId, messageId, aiResponse)
            }

        } catch (e: Exception) {
            val errorMsg = when {
                "invalid_key" in (e.message ?: "") -> ErrorMessages.getFriendlyError("invalid_key")
                "quota_exceeded" in (e.message ?: "") -> ErrorMessages.getFriendlyError("quota_exceeded")
                "network_error" in (e.message ?: "") -> ErrorMessages.getFriendlyError("network_error")
                "no_keys" in (e.message ?: "") -> ErrorMessages.getFriendlyError("no_keys")
                else -> ErrorMessages.getFriendlyError("unknown_error")
            }
            sendMessage(chatId, errorMsg)
            log("ERROR", "Handler", "Text message error for $userId: ${e.message}")
        }
    }

    // ─── Voice Message Handler — mirrors handlers.py ───────────

    private suspend fun handleVoiceMessage(userId: Long, chatId: Long, messageId: Long, voice: JsonObject, from: JsonObject) {
        val user = getOrCreateUser(userId)
        updateUser(userId, user.copy(lastInteraction = System.currentTimeMillis().toDouble(), chatId = chatId, proactiveSent = false))

        if (users[userId]!!.ollamaKeys.isEmpty()) {
            sendMessage(chatId, ErrorMessages.getFriendlyError("no_keys"))
            return
        }
        if (users[userId]!!.elevenlabsKeys.isEmpty()) {
            sendMessage(chatId, "I need your ElevenLabs API key to listen to voice messages! 🎤\nUse /add\\_elevenlabs\\_key to add one.")
            return
        }

        var oggFile: File? = null
        try {
            sendChatAction(chatId, "record_voice")

            val fileId = voice["file_id"]?.jsonPrimitive?.content ?: return
            tempDir.mkdirs()
            oggFile = File(tempDir, "voice_${userId}_${System.currentTimeMillis()}.ogg")

            if (!downloadFile(fileId, oggFile)) {
                sendMessage(chatId, ErrorMessages.getFriendlyError("stt_error"))
                return
            }

            // Transcribe
            val elKey = getDecryptedElevenlabsKey(userId) ?: run {
                sendMessage(chatId, ErrorMessages.getFriendlyError("stt_error"))
                return
            }

            val transcript = ElevenLabsService.speechToText(elKey, oggFile)
            if (transcript.isNullOrBlank()) {
                sendMessage(chatId, ErrorMessages.getFriendlyError("stt_error"))
                return
            }

            val chatHistory = getChatHistory(userId)
            val ollamaHistory = chatHistory.map { OllamaMessage(it.role, it.content) }
            val userProfile = getUserProfile(userId)

            reactToMessage(chatId, messageId, userId, transcript, ollamaHistory, chatHistory.size)

            val aiResponse = getAiResponse(userId, transcript, ollamaHistory, userProfile)

            saveChatMessage(userId, "user", transcript)
            saveChatMessage(userId, "assistant", aiResponse)

            val useVoice = shouldReplyWithVoice(chatHistory.size, "voice")

            if (useVoice) {
                sendChatAction(chatId, "record_voice")
                delay(Random.nextLong(1000, 2500))
                val sent = sendVoiceReply(userId, chatId, messageId, aiResponse, users[userId]!!.voiceId)
                if (!sent) {
                    simulateTyping(chatId, aiResponse.length)
                    val ttsError = ErrorMessages.getFriendlyError("tts_error")
                    sendTextReply(chatId, messageId, "$ttsError\n\n$aiResponse")
                }
            } else {
                simulateTyping(chatId, aiResponse.length)
                sendTextReply(chatId, messageId, aiResponse)
            }

        } catch (e: Exception) {
            val errorMsg = when {
                "invalid_key" in (e.message ?: "") -> ErrorMessages.getFriendlyError("invalid_key")
                "quota_exceeded" in (e.message ?: "") -> ErrorMessages.getFriendlyError("quota_exceeded")
                else -> ErrorMessages.getFriendlyError("unknown_error")
            }
            sendMessage(chatId, errorMsg)
            log("ERROR", "Handler", "Voice error for $userId: ${e.message}")
        } finally {
            oggFile?.delete()
        }
    }

    // ─── Helper Methods ────────────────────────────────────────

    private suspend fun updateUser(userId: Long, user: UserData) {
        users[userId] = user
        try {
            firebase.createOrUpdateUser(userId, mapOf(
                "telegram_username" to user.telegramUsername,
                "first_name" to user.firstName,
                "profile_name" to user.profileName,
                "profile_bio" to user.profileBio,
                "tone" to user.tone,
                "reply_length" to user.replyLength,
                "voice_id" to user.voiceId,
                "voice_only" to user.voiceOnly,
                "ollama_keys" to user.ollamaKeys,
                "elevenlabs_keys" to user.elevenlabsKeys,
                "last_interaction" to user.lastInteraction,
                "chat_id" to user.chatId,
                "proactive_sent" to user.proactiveSent,
            ))
        } catch (e: Exception) {
            log("WARN", "Firebase", "Failed to sync user $userId: ${e.message}")
        }
    }

    private suspend fun getOrCreateUser(userId: Long): UserData {
        users[userId]?.let { return it }
        // Cache miss — try Firebase
        try {
            val fbUser = firebase.getUser(userId)
            if (fbUser != null) {
                users[userId] = fbUser
                return fbUser
            }
        } catch (e: Exception) {
            log("WARN", "Firebase", "Failed to fetch user $userId: ${e.message}")
        }
        val newUser = UserData()
        users[userId] = newUser
        return newUser
    }

    private suspend fun getChatHistory(userId: Long): List<ChatMessage> {
        chatHistories[userId]?.let { return it.takeLast(config.maxChatHistory) }
        // Cache miss — load from Firebase
        try {
            val fbHistory = firebase.getChatHistory(userId, config.maxChatHistory)
            if (fbHistory.isNotEmpty()) {
                chatHistories[userId] = fbHistory.toMutableList()
                return fbHistory
            }
        } catch (e: Exception) {
            log("WARN", "Firebase", "Failed to fetch chat history for $userId: ${e.message}")
        }
        return emptyList()
    }

    private suspend fun saveChatMessage(userId: Long, role: String, content: String) {
        val history = chatHistories.getOrPut(userId) { mutableListOf() }
        history.add(ChatMessage(role = role, content = content))
        // Trim local cache
        while (history.size > config.maxChatHistory * 2) {
            history.removeAt(0)
        }
        // Persist to Firebase
        try {
            firebase.saveMessage(userId, role, content)
        } catch (e: Exception) {
            log("WARN", "Firebase", "Failed to save message for $userId: ${e.message}")
        }
    }

    private suspend fun getUserProfile(userId: Long): Map<String, String?> {
        val user = getOrCreateUser(userId)
        return mapOf(
            "profile_name" to user.profileName,
            "profile_bio" to user.profileBio,
            "tone" to user.tone,
            "reply_length" to user.replyLength,
            "voice_id" to user.voiceId,
        )
    }

    private fun getDecryptedOllamaKey(userId: Long): String? {
        val user = users[userId] ?: return null
        for (encKey in user.ollamaKeys) {
            try {
                return EncryptionService.decrypt(encKey, config.encryptionKey)
            } catch (_: Exception) { continue }
        }
        return null
    }

    private fun getDecryptedElevenlabsKey(userId: Long): String? {
        val user = users[userId] ?: return null
        for (encKey in user.elevenlabsKeys) {
            try {
                return EncryptionService.decrypt(encKey, config.encryptionKey)
            } catch (_: Exception) { continue }
        }
        return null
    }

    private suspend fun getAiResponse(
        userId: Long,
        userMessage: String,
        chatHistory: List<OllamaMessage>,
        userProfile: Map<String, String?>,
    ): String {
        val user = users[userId] ?: throw RuntimeException("no_keys")
        if (user.ollamaKeys.isEmpty()) throw RuntimeException("no_keys")

        val messages = OllamaService.buildMessages(userMessage, chatHistory, userProfile, config.botName)
        var lastError: Exception? = null

        for (encKey in user.ollamaKeys) {
            try {
                val apiKey = EncryptionService.decrypt(encKey, config.encryptionKey)
                return OllamaService.callOllama(config.ollamaHost, config.ollamaModel, apiKey, messages)
            } catch (e: Exception) {
                lastError = e
                log("WARN", "AI", "Key failed for $userId: ${e.message}")
                continue
            }
        }

        throw lastError ?: RuntimeException("unknown_error")
    }

    private suspend fun reactToMessage(
        chatId: Long, messageId: Long, userId: Long,
        userText: String, chatHistory: List<OllamaMessage>, historyLen: Int
    ) {
        if (!config.debugReactions) {
            val reactChance = when {
                historyLen < 5 -> 0.10
                historyLen < 15 -> 0.25
                historyLen < 30 -> 0.50
                else -> 0.70
            }
            if (Random.nextDouble() > reactChance) return
        }

        val apiKey = getDecryptedOllamaKey(userId) ?: return
        val emoji = OllamaService.pickReactionEmoji(config.ollamaHost, config.ollamaModel, apiKey, userText, chatHistory)
        if (emoji != null) {
            setMessageReaction(chatId, messageId, emoji)
        }
    }

    private fun shouldReplyWithVoice(msgCount: Int, messageType: String): Boolean {
        if (messageType == "voice") {
            return when {
                msgCount < 5 -> Random.nextDouble() < 0.15
                msgCount < 15 -> Random.nextDouble() < 0.35
                msgCount < 30 -> Random.nextDouble() < 0.55
                else -> Random.nextDouble() < 0.70
            }
        }
        // Text message
        return when {
            msgCount < 10 -> false
            msgCount < 25 -> Random.nextDouble() < 0.03
            msgCount < 50 -> Random.nextDouble() < 0.07
            else -> Random.nextDouble() < 0.12
        }
    }

    private suspend fun simulateTyping(chatId: Long, textLength: Int) {
        sendChatAction(chatId, "typing")
        val base = Random.nextDouble(config.typingDelayMin.toDouble(), config.typingDelayMax.toDouble())
        val extra = minOf(textLength / 200.0, 2.0)
        delay(((base + extra) * 1000).toLong())
    }

    private suspend fun sendTextReply(chatId: Long, messageId: Long, text: String) {
        val replyTo = if (Random.nextDouble() < 0.4) messageId else null
        sendMessage(chatId, text, replyTo)
    }

    private suspend fun sendVoiceReply(userId: Long, chatId: Long, messageId: Long, aiText: String, voiceId: String?): Boolean {
        return try {
            sendChatAction(chatId, "record_voice")
            val cleanText = ElevenLabsService.cleanTextForTts(aiText)
            val elKey = getDecryptedElevenlabsKey(userId) ?: return false
            val resolvedVoiceId = voiceId ?: config.elevenlabsDefaultVoiceId
            val audioFile = ElevenLabsService.textToSpeech(elKey, cleanText, tempDir, resolvedVoiceId) ?: return false

            val replyTo = if (Random.nextDouble() < 0.5) messageId else null
            sendVoiceFromFile(chatId, audioFile, replyTo)
            audioFile.delete()
            true
        } catch (e: Exception) {
            log("ERROR", "Voice", "Voice reply failed for $userId: ${e.message}")
            false
        }
    }

    // ─── Proactive Messaging — mirrors proactive.py ────────────

    private suspend fun checkProactiveMessages() {
        val now = System.currentTimeMillis()

        // Load all users from Firebase for proactive checks
        val allUsers = try {
            firebase.getAllUsers()
        } catch (e: Exception) {
            log("WARN", "Firebase", "Failed to load users for proactive: ${e.message}")
            users.toMap()
        }
        // Merge into local cache
        for ((id, u) in allUsers) { users.putIfAbsent(id, u) }

        for ((userId, user) in users) {
            if (user.lastInteraction <= 0 || user.chatId <= 0) continue
            if (user.proactiveSent) continue
            if (user.ollamaKeys.isEmpty()) continue

            val history = getChatHistory(userId)
            val tier = OllamaService.getComfortTier(history.size)
            val threshold = OllamaService.INACTIVITY_THRESHOLDS[tier] ?: continue

            val elapsed = now - user.lastInteraction.toLong()
            if (elapsed < threshold) continue

            try {
                val userProfile = getUserProfile(userId)
                val prompt = OllamaService.INITIATE_PROMPTS[tier] ?: continue
                val ollamaHistory = history.map { OllamaMessage(it.role, it.content) }

                val aiMessage = getAiResponse(userId, prompt, ollamaHistory, userProfile)
                if (aiMessage.isBlank()) continue

                // Decide voice or text for proactive
                var useVoice = false
                if (tier == "close" && user.elevenlabsKeys.isNotEmpty()) {
                    useVoice = Random.nextDouble() < 0.25
                } else if (tier == "comfortable" && user.elevenlabsKeys.isNotEmpty()) {
                    useVoice = Random.nextDouble() < 0.08
                }

                if (useVoice) {
                    val cleanText = ElevenLabsService.cleanTextForTts(aiMessage)
                    val elKey = getDecryptedElevenlabsKey(userId)
                    val voiceId = user.voiceId ?: config.elevenlabsDefaultVoiceId
                    val audioFile = if (elKey != null) ElevenLabsService.textToSpeech(elKey, cleanText, tempDir, voiceId) else null
                    if (audioFile != null) {
                        sendVoiceFromFile(user.chatId, audioFile)
                        audioFile.delete()
                    } else {
                        sendMessage(user.chatId, aiMessage)
                    }
                } else {
                    sendMessage(user.chatId, aiMessage)
                }

                saveChatMessage(userId, "assistant", aiMessage)
                updateUser(userId, user.copy(proactiveSent = true))
                log("INFO", "Proactive", "Sent ${if (useVoice) "voice" else "text"} to user $userId (tier: $tier)")

                delay(Random.nextLong(10000, 30000))
            } catch (e: Exception) {
                log("ERROR", "Proactive", "Failed for $userId: ${e.message}")
            }
        }
    }
}
