package com.example.meeraai.service

import java.util.concurrent.ConcurrentHashMap

/**
 * Humanized error messages — mirrors error_messages.py
 * Stored in-memory with fallbacks (no Firebase dependency for the app version).
 */
object ErrorMessages {

    private val messages = ConcurrentHashMap<String, List<String>>()

    init {
        messages["quota_exceeded"] = listOf(
            "Hey… I think I'm a bit tired right now 😅 Try again soon?",
            "Oops, looks like I've been talking too much today 😄 Give me a moment!",
            "I need a tiny break 💤 My quota ran out, but I'll be back!",
        )
        messages["invalid_key"] = listOf(
            "Something feels off on my side… maybe check your API settings?",
            "Hmm, I can't seem to connect properly. Could you check your API key? 🔑",
            "I'm having trouble with authentication… mind double-checking your key?",
        )
        messages["network_error"] = listOf(
            "I think the internet is playing tricks on us 😕 Try again?",
            "Connection hiccup! Let's try that again in a sec 🌐",
            "Oops, lost my train of thought there… network issue! Try once more?",
        )
        messages["unknown_error"] = listOf(
            "Something unexpected happened 😅 But don't worry, I'll figure it out!",
            "Hmm, that's weird… Let me try again if you send your message once more?",
            "I got a bit confused there 🤔 Could you try again?",
        )
        messages["stt_error"] = listOf(
            "I couldn't quite hear that clearly 🎤 Could you try sending the voice message again?",
            "Hmm, the audio was a bit tricky for me. Mind trying once more?",
            "Sorry, I had trouble understanding that voice message. Try again? 🎧",
        )
        messages["tts_error"] = listOf(
            "I wanted to talk back but my voice is being shy today 😅 Here's text instead!",
            "My voice module hiccupped! Sending you text for now 📝",
        )
        messages["no_keys"] = listOf(
            "You haven't added any API keys yet! Use /add_ollama_key to get started 🔑",
        )
    }

    fun getFriendlyError(category: String): String {
        val msgs = messages[category] ?: messages["unknown_error"]!!
        return msgs.random()
    }
}
