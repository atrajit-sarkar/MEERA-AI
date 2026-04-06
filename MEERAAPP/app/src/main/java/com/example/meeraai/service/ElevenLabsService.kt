package com.example.meeraai.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.*
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ElevenLabs TTS + STT service — mirrors elevenlabs_service.py and stt_service.py
 */
object ElevenLabsService {

    private val client = OkHttpClient.Builder()
        .dns(ReliableDns)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Text-to-Speech: Convert text to speech using ElevenLabs API.
     * Returns path to the generated audio file, or null on failure.
     */
    suspend fun textToSpeech(
        apiKey: String,
        text: String,
        outputDir: File,
        voiceId: String = "21m00Tcm4TlvDq8ikWAM",
    ): File? = withContext(Dispatchers.IO) {
        try {
            outputDir.mkdirs()
            val outputFile = File(outputDir, "tts_${System.currentTimeMillis()}.mp3")

            val body = buildJsonObject {
                put("text", text)
                put("model_id", "eleven_multilingual_v2")
                put("voice_settings", buildJsonObject {
                    put("stability", 0.5)
                    put("similarity_boost", 0.75)
                })
            }

            val request = Request.Builder()
                .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId")
                .addHeader("xi-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "audio/mpeg")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw RuntimeException("ElevenLabs TTS failed: ${response.code}")
            }

            response.body?.byteStream()?.use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }

            if (outputFile.exists() && outputFile.length() > 0) outputFile else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Speech-to-Text: Transcribe audio file using ElevenLabs Scribe v2.
     */
    suspend fun speechToText(
        apiKey: String,
        audioFile: File,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val audioBody = audioFile.readBytes()
                .toRequestBody("audio/ogg".toMediaType())

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", audioFile.name, audioBody)
                .addFormDataPart("model_id", "scribe_v2")
                .build()

            val request = Request.Builder()
                .url("https://api.elevenlabs.io/v1/speech-to-text")
                .addHeader("xi-api-key", apiKey)
                .post(multipartBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext null

            if (!response.isSuccessful) {
                throw RuntimeException("ElevenLabs STT failed: ${response.code}")
            }

            val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
            val text = jsonResponse["text"]?.jsonPrimitive?.content
            text?.trim()?.ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Clean text for TTS — strip emojis, markdown, labels that sound robotic.
     * Mirrors _clean_text_for_tts from handlers.py
     */
    fun cleanTextForTts(text: String): String {
        var cleaned = text

        // Remove label/prefix patterns
        cleaned = cleaned.replace(
            Regex(
                "[*_]*\\(?\\s*(voice\\s*message|text\\s*message|voice|response|reply|answer|meera|audio)\\s*\\)?[*_]*\\s*[:：\\-—]\\s*",
                RegexOption.IGNORE_CASE
            ), ""
        )

        // Remove "Here's my voice/response" openers
        cleaned = cleaned.replace(
            Regex(
                "^\\s*here['']?s?\\s*(my|a|the)?\\s*(voice|response|reply|answer|message)[:：\\-—\\s]*",
                RegexOption.IGNORE_CASE
            ), ""
        )

        // Remove roleplay asterisks
        cleaned = cleaned.replace(Regex("\\*[^*]+\\*"), "")

        // Remove parenthetical stage directions
        cleaned = cleaned.replace(Regex("\\([^)]*\\)"), "")

        // Remove emojis (common Unicode ranges)
        cleaned = cleaned.replace(
            Regex("[\\uD83C-\\uDBFF\\uDC00-\\uDFFF\\u2600-\\u27BF\\u2702-\\u27B0\\u24C2-\\uF251]+"),
            ""
        )

        // Remove markdown formatting
        cleaned = cleaned.replace(Regex("[`~]"), "")

        // Remove leading/trailing quotes
        cleaned = cleaned.trim().replace(Regex("""^["'“”]+|["'“”]+$"""), "")

        // Collapse whitespace
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()

        return cleaned
    }
}
