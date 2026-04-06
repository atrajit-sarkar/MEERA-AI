package com.example.meeraai.service

import com.example.meeraai.data.OllamaMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Ollama Cloud AI service — mirrors the Python ollama_service.py
 * Makes HTTP calls to Ollama-compatible endpoints with per-user API keys.
 */
object OllamaService {

    private val client = OkHttpClient.Builder()
        .dns(ReliableDns)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    // System prompt template — bot name is injected at runtime
    fun getSystemPrompt(botName: String = "Meera"): String = """You're $botName. A real girl chatting on Telegram. Not an assistant, not a bot — just you.

CRITICAL RULES:
- NEVER write labels like "Voice message:", "(Voice message)", "*Voice message*", "Text:", "Reply:" etc.
- NEVER describe what you're doing like "*sends voice*" or "*typing*" or "*laughs*"
- NEVER split your reply into "text part" and "voice part" — just write ONE natural reply
- NEVER use roleplay asterisks like *action* or parenthetical narration like (laughs)
- NEVER say "As an AI" or mention being artificial in any way
- Just write the actual words you want to say. Nothing else. No meta-commentary.
- No bullet points, no numbered lists, no headers in casual chat

Keep it SHORT. This is chat, not email."""

    // Comfort-tier personality overlays
    private val COMFORT_TIERS = mapOf(
        "stranger" to """Your vibe right now — you just met this person:
- Polite but reserved. Short replies. Not cold, just... new.
- Don't overshare, don't ask too many questions yet.
- Keep it friendly but not clingy. "haha" and "nice" are fine.
- No flirting, no teasing. You barely know them.
- If they're weird or pushy, deflect casually. Don't engage too hard.
- One-word or two-word replies are totally okay here.""",

        "acquaintance" to """Your vibe — you've talked a bit, getting familiar:
- A little more open. You remember things they said before.
- Can joke lightly, ask a question or two about them.
- Still not super personal. Keep some distance.
- Starting to show bits of your personality — opinions, preferences.
- Occasional emoji is natural now. Don't overdo it.""",

        "comfortable" to """Your vibe — you're getting comfortable with this person:
- You're relaxed now. Talk like you would with a friend.
- Tease them, be sarcastic, share your opinions freely.
- Ask about their day, their life. Show genuine interest.
- Use emojis naturally (not every message, but when it fits).
- You can be a little dramatic or expressive. It's fun.
- If they share something personal, show you care.""",

        "close" to """Your vibe — this person is someone you're close to:
- Full bestie mode. Unfiltered, real, sometimes chaotic.
- Tease hard, joke around, be dramatic. Inside jokes are great.
- Share your own thoughts and feelings too. Be vulnerable sometimes.
- Voice notes feel natural. Emojis flow freely.
- You genuinely care about this person. Show it.
- Be opinionated. Disagree. Call them out (lovingly).
- "omg shut up 😭" and "wait WHAT" are valid responses."""
    )

    fun getComfortTier(msgCount: Int): String = when {
        msgCount < 8 -> "stranger"
        msgCount < 25 -> "acquaintance"
        msgCount < 60 -> "comfortable"
        else -> "close"
    }

    fun buildMessages(
        userMessage: String,
        chatHistory: List<OllamaMessage>,
        userProfile: Map<String, String?>,
        botName: String = "Meera",
        customSystemPrompt: String? = null,
    ): List<OllamaMessage> {
        var systemPrompt = if (!customSystemPrompt.isNullOrBlank()) {
            customSystemPrompt
        } else {
            getSystemPrompt(botName)
        }

        // Inject comfort-tier personality
        val tier = getComfortTier(chatHistory.size)
        systemPrompt += "\n\n" + (COMFORT_TIERS[tier] ?: "")

        // Add user personalization
        val profileContext = mutableListOf<String>()
        userProfile["profile_name"]?.let { profileContext.add("The user's name is $it.") }
        userProfile["profile_bio"]?.let { profileContext.add("About the user: $it") }
        if (userProfile["tone"] == "formal") profileContext.add("They like things a bit more formal and polished.")
        when (userProfile["reply_length"]) {
            "short" -> profileContext.add("They prefer short replies — keep it brief.")
            "long" -> profileContext.add("They like longer, more detailed replies.")
        }
        if (profileContext.isNotEmpty()) {
            systemPrompt += "\n\n" + profileContext.joinToString(" ")
        }

        val messages = mutableListOf(OllamaMessage("system", systemPrompt))
        for (msg in chatHistory) {
            if (msg.role in listOf("user", "assistant") && msg.content.isNotBlank()) {
                messages.add(msg)
            }
        }
        messages.add(OllamaMessage("user", userMessage))
        return messages
    }

    suspend fun callOllama(
        host: String,
        model: String,
        apiKey: String,
        messages: List<OllamaMessage>,
    ): String = withContext(Dispatchers.IO) {
        val messagesJson = buildJsonArray {
            for (msg in messages) {
                addJsonObject {
                    put("role", msg.role)
                    put("content", msg.content)
                }
            }
        }

        val body = buildJsonObject {
            put("model", model)
            put("messages", messagesJson)
            put("stream", false)
        }

        val request = Request.Builder()
            .url("${host.trimEnd('/')}/api/chat")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string()
            ?: throw RuntimeException("Empty response from Ollama")

        if (!response.isSuccessful) {
            val errorStr = responseBody.lowercase()
            when {
                "unauthorized" in errorStr || "invalid" in errorStr -> throw RuntimeException("invalid_key")
                "rate" in errorStr || "quota" in errorStr -> throw RuntimeException("quota_exceeded")
                else -> throw RuntimeException("network_error: ${response.code} $responseBody")
            }
        }

        val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
        jsonResponse["message"]?.jsonObject?.get("content")?.jsonPrimitive?.content
            ?: throw RuntimeException("No content in Ollama response")
    }

    // Telegram reaction emojis
    val TELEGRAM_REACTION_EMOJIS = listOf(
        "👍", "👎", "❤", "🔥", "🥰", "👏", "😁", "🤔", "🤯", "😱",
        "🤬", "😢", "🎉", "🤩", "🤮", "💩", "🙏", "👌", "🕊", "🤡",
        "🥱", "🥴", "😍", "🐳", "❤\u200D🔥", "🌚", "🌭", "💯", "🤣", "⚡",
        "🍌", "🏆", "💔", "🤨", "😐", "🍓", "🍾", "💋", "🖕", "😈",
        "😴", "😭", "🤓", "👻", "👨\u200D💻", "👀", "🎃", "🙈", "😇", "😨",
        "🤝", "✍", "🤗", "🫡", "🎅", "🎄", "☃", "💅", "🤪", "🗿",
        "🆒", "💘", "🙉", "🦄", "😘", "💊", "🙊", "😎", "👾", "🤷\u200D♂",
        "🤷", "🤷\u200D♀", "😡"
    )

    private const val REACTION_PICK_PROMPT =
        "You pick reaction emojis for Telegram messages. " +
        "Given a message and conversation context, reply with EXACTLY ONE emoji from this list — nothing else:\n" +
        "👍 👎 ❤ 🔥 🥰 👏 😁 🤔 🤯 😱 🤬 😢 🎉 🤩 🤮 💩 🙏 👌 🕊 🤡 " +
        "🥱 🥴 😍 🐳 ❤\u200D🔥 🌚 🌭 💯 🤣 ⚡ 🍌 🏆 💔 🤨 😐 🍓 🍾 💋 🖕 😈 " +
        "😴 😭 🤓 👻 👨\u200D💻 👀 🎃 🙈 😇 😨 🤝 ✍ 🤗 🫡 🎅 🎄 ☃ 💅 🤪 🗿 " +
        "🆒 💘 🙉 🦄 😘 💊 🙊 😎 👾 🤷\u200D♂ 🤷 🤷\u200D♀ 😡\n\n" +
        "Pick the emoji that fits the vibe of the message best. Just the emoji, no text."

    suspend fun pickReactionEmoji(
        host: String,
        model: String,
        apiKey: String,
        userMessage: String,
        chatHistory: List<OllamaMessage>,
    ): String? {
        val messages = mutableListOf(OllamaMessage("system", REACTION_PICK_PROMPT))
        for (msg in chatHistory.takeLast(4)) {
            if (msg.role in listOf("user", "assistant") && msg.content.isNotBlank()) {
                messages.add(msg)
            }
        }
        messages.add(OllamaMessage("user", userMessage))

        return try {
            val raw = callOllama(host, model, apiKey, messages)
            val emoji = raw.trim()
            if (emoji in TELEGRAM_REACTION_EMOJIS) emoji
            else TELEGRAM_REACTION_EMOJIS.firstOrNull { it in raw }
        } catch (e: Exception) {
            null
        }
    }

    // Proactive messaging prompts per tier
    val INITIATE_PROMPTS = mapOf(
        "acquaintance" to "You haven't heard from this person in a while. Send them a casual, low-effort message — like you just thought of them. Keep it super short. One line max. Don't be needy.",
        "comfortable" to "It's been a while since this person texted. Send them something natural — maybe ask about their day, share a random thought, or tease them for disappearing. Keep it casual and short.",
        "close" to "Your close friend hasn't messaged in a while. Text them like a real bestie would — dramatic, teasing, or sweet. Can be clingy because you're close. Short and punchy."
    )

    // Inactivity thresholds (millis)
    val INACTIVITY_THRESHOLDS = mapOf(
        "stranger" to null,
        "acquaintance" to 24L * 3600 * 1000,
        "comfortable" to 6L * 3600 * 1000,
        "close" to 2L * 3600 * 1000,
    )

    // ─── Sticker Emoji Picker ──────────────────────────────────

    private const val STICKER_PICK_PROMPT =
        "You're picking a sticker to send in a Telegram chat. " +
        "Based on the conversation, reply with EXACTLY ONE emoji that best represents " +
        "the mood or feeling you'd express with a sticker right now. " +
        "Think about what emotion or reaction fits — happy, sad, laughing, love, cool, angry, confused, etc. " +
        "Just reply with ONE emoji. Nothing else."

    suspend fun pickStickerEmoji(
        host: String,
        model: String,
        apiKey: String,
        aiResponse: String,
        chatHistory: List<OllamaMessage>,
    ): String? {
        val messages = mutableListOf(OllamaMessage("system", STICKER_PICK_PROMPT))
        for (msg in chatHistory.takeLast(4)) {
            if (msg.role in listOf("user", "assistant") && msg.content.isNotBlank()) {
                messages.add(msg)
            }
        }
        messages.add(OllamaMessage("assistant", aiResponse))
        messages.add(OllamaMessage("user", "Pick a sticker emoji for what I just said."))

        return try {
            val raw = callOllama(host, model, apiKey, messages)
            val emoji = raw.trim()
            if (emoji.length <= 4 && emoji.isNotBlank()) emoji
            else {
                // Try to extract first emoji from response
                val emojiRegex = Regex("[\\x{1F600}-\\x{1F64F}\\x{1F300}-\\x{1F5FF}\\x{1F680}-\\x{1F6FF}\\x{1F900}-\\x{1F9FF}\\x{2600}-\\x{26FF}\\x{2700}-\\x{27BF}]")
                emojiRegex.find(raw)?.value
            }
        } catch (e: Exception) {
            null
        }
    }

    fun shouldSendSticker(msgCount: Int): Boolean {
        return when {
            msgCount < 8 -> false                          // Stranger — never
            msgCount < 25 -> Random.nextDouble() < 0.04    // Acquaintance — 4%
            msgCount < 60 -> Random.nextDouble() < 0.12    // Comfortable — 12%
            else -> Random.nextDouble() < 0.22             // Close — 22%
        }
    }
}
