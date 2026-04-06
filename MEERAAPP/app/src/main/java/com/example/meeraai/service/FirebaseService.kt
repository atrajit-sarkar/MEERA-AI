package com.example.meeraai.service

import android.content.Context
import android.util.Base64
import com.example.meeraai.data.ChatMessage
import com.example.meeraai.data.UserData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyFactory
import java.security.Signature 
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.TimeUnit

/**
 * Firestore REST API service — mirrors Python's firebase_service.py.
 * Uses the service account JSON (same one the Python backend uses) for JWT-based auth.
 * Talks directly to Firestore REST API so data stays consistent across both backends.
 */
class FirebaseService(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .dns(ReliableDns)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    // Service account credentials parsed from JSON
    private var projectId: String = ""
    private var clientEmail: String = ""
    private var privateKeyPem: String = ""
    private var databaseId: String = "(default)"

    // OAuth2 token cache
    private var accessToken: String? = null
    private var tokenExpiry: Long = 0
    private val tokenMutex = Mutex()

    private val firestoreBaseUrl: String
        get() = "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents"

    // ─── Initialization ────────────────────────────────────────

    fun init(dbId: String = "(default)") {
        databaseId = dbId
        val credentialsJson = context.assets.open("firebase-credentials.json")
            .bufferedReader().use { it.readText() }
        val creds = json.parseToJsonElement(credentialsJson).jsonObject
        projectId = creds["project_id"]?.jsonPrimitive?.content
            ?: throw RuntimeException("Missing project_id in firebase-credentials.json")
        clientEmail = creds["client_email"]?.jsonPrimitive?.content
            ?: throw RuntimeException("Missing client_email in firebase-credentials.json")
        privateKeyPem = creds["private_key"]?.jsonPrimitive?.content
            ?: throw RuntimeException("Missing private_key in firebase-credentials.json")
    }

    // ─── JWT / OAuth2 Token ────────────────────────────────────

    private suspend fun getAccessToken(): String = tokenMutex.withLock {
        val now = System.currentTimeMillis() / 1000
        if (accessToken != null && now < tokenExpiry - 60) {
            return@withLock accessToken!!
        }

        val token = withContext(Dispatchers.IO) {
            val header = buildJsonObject {
                put("alg", "RS256")
                put("typ", "JWT")
            }

            val claims = buildJsonObject {
                put("iss", clientEmail)
                put("scope", "https://www.googleapis.com/auth/datastore")
                put("aud", "https://oauth2.googleapis.com/token")
                put("iat", now)
                put("exp", now + 3600)
            }

            val headerB64 = base64UrlEncode(header.toString().toByteArray())
            val claimsB64 = base64UrlEncode(claims.toString().toByteArray())
            val signInput = "$headerB64.$claimsB64"

            // Parse PEM private key
            val keyContent = privateKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\n", "")
                .replace("\n", "")
                .replace(" ", "")
            val keyBytes = Base64.decode(keyContent, Base64.DEFAULT)
            val keySpec = PKCS8EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("RSA")
            val privateKey = keyFactory.generatePrivate(keySpec)

            // Sign with RS256
            val signature = Signature.getInstance("SHA256withRSA")
            signature.initSign(privateKey)
            signature.update(signInput.toByteArray())
            val signatureB64 = base64UrlEncode(signature.sign())

            val jwt = "$signInput.$signatureB64"

            // Exchange JWT for access token
            val tokenRequest = Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(
                    "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=$jwt"
                        .toRequestBody("application/x-www-form-urlencoded".toMediaType())
                )
                .build()

            val response = client.newCall(tokenRequest).execute()
            val body = response.body?.string()
                ?: throw RuntimeException("Empty token response")

            if (!response.isSuccessful) {
                throw RuntimeException("Token exchange failed: ${response.code} $body")
            }

            val tokenJson = json.parseToJsonElement(body).jsonObject
            val newToken = tokenJson["access_token"]?.jsonPrimitive?.content
                ?: throw RuntimeException("No access_token in response")
            val expiresIn = tokenJson["expires_in"]?.jsonPrimitive?.long ?: 3600

            tokenExpiry = now + expiresIn
            newToken
        }

        accessToken = token
        token
    }

    private fun base64UrlEncode(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    // ─── Firestore Helpers ─────────────────────────────────────

    private suspend fun firestoreGet(path: String): JsonObject? = withContext(Dispatchers.IO) {
        val token = getAccessToken()
        val request = Request.Builder()
            .url("$firestoreBaseUrl/$path")
            .addHeader("Authorization", "Bearer $token")
            .build()

        val response = client.newCall(request).execute()
        if (response.code == 404) return@withContext null
        val body = response.body?.string() ?: return@withContext null
        if (!response.isSuccessful) return@withContext null
        json.parseToJsonElement(body).jsonObject
    }

    private suspend fun firestoreSet(path: String, fields: JsonObject) = withContext(Dispatchers.IO) {
        val token = getAccessToken()
        val doc = buildJsonObject { put("fields", fields) }

        val request = Request.Builder()
            .url("$firestoreBaseUrl/$path")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .patch(doc.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute()
    }

    private suspend fun firestoreCreate(collectionPath: String, fields: JsonObject): String? =
        withContext(Dispatchers.IO) {
            val token = getAccessToken()
            val doc = buildJsonObject { put("fields", fields) }

            val request = Request.Builder()
                .url("$firestoreBaseUrl/$collectionPath")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(doc.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            val result = json.parseToJsonElement(body).jsonObject
            result["name"]?.jsonPrimitive?.content
        }

    private suspend fun firestoreQuery(
        collectionId: String,
        parentPath: String,
        orderBy: String? = null,
        descending: Boolean = false,
        limit: Int? = null,
    ): List<JsonObject> = withContext(Dispatchers.IO) {
        val token = getAccessToken()

        val structuredQuery = buildJsonObject {
            put("structuredQuery", buildJsonObject {
                put("from", buildJsonArray {
                    addJsonObject { put("collectionId", collectionId) }
                })
                orderBy?.let { field ->
                    put("orderBy", buildJsonArray {
                        addJsonObject {
                            put("field", buildJsonObject { put("fieldPath", field) })
                            put("direction", if (descending) "DESCENDING" else "ASCENDING")
                        }
                    })
                }
                limit?.let { put("limit", it) }
            })
        }

        val url = "https://firestore.googleapis.com/v1/projects/$projectId/databases/$databaseId/documents/$parentPath:runQuery"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
            .post(structuredQuery.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = client.newCall(request).execute()
        val body = response.body?.string() ?: return@withContext emptyList()
        if (!response.isSuccessful) return@withContext emptyList()

        val results = json.parseToJsonElement(body).jsonArray
        results.mapNotNull { element ->
            element.jsonObject["document"]?.jsonObject
        }
    }

    private suspend fun firestoreDelete(path: String) = withContext(Dispatchers.IO) {
        val token = getAccessToken()
        val request = Request.Builder()
            .url("$firestoreBaseUrl/$path")
            .addHeader("Authorization", "Bearer $token")
            .delete()
            .build()
        client.newCall(request).execute()
    }

    // ─── Field Conversion Helpers ──────────────────────────────

    private fun toFirestoreFields(data: Map<String, Any?>): JsonObject = buildJsonObject {
        for ((key, value) in data) {
            put(key, toFirestoreValue(value))
        }
    }

    private fun toFirestoreValue(value: Any?): JsonObject = buildJsonObject {
        when (value) {
            null -> put("nullValue", JsonNull)
            is String -> put("stringValue", value)
            is Boolean -> put("booleanValue", value)
            is Int -> put("integerValue", value.toString())
            is Long -> put("integerValue", value.toString())
            is Double -> put("doubleValue", value)
            is Float -> put("doubleValue", value.toDouble())
            is List<*> -> put("arrayValue", buildJsonObject {
                put("values", buildJsonArray {
                    for (item in value) {
                        add(toFirestoreValue(item))
                    }
                })
            })
            is Map<*, *> -> put("mapValue", buildJsonObject {
                put("fields", buildJsonObject {
                    for ((k, v) in value) {
                        put(k.toString(), toFirestoreValue(v))
                    }
                })
            })
            else -> put("stringValue", value.toString())
        }
    }

    private fun fromFirestoreFields(fields: JsonObject): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        for ((key, valueObj) in fields) {
            result[key] = fromFirestoreValue(valueObj.jsonObject)
        }
        return result
    }

    private fun fromFirestoreValue(value: JsonObject): Any? {
        return when {
            "stringValue" in value -> value["stringValue"]?.jsonPrimitive?.content
            "integerValue" in value -> value["integerValue"]?.jsonPrimitive?.content?.toLongOrNull()
            "doubleValue" in value -> value["doubleValue"]?.jsonPrimitive?.double
            "booleanValue" in value -> value["booleanValue"]?.jsonPrimitive?.boolean
            "nullValue" in value -> null
            "arrayValue" in value -> {
                val values = value["arrayValue"]?.jsonObject?.get("values")?.jsonArray ?: return emptyList<Any>()
                values.map { fromFirestoreValue(it.jsonObject) }
            }
            "mapValue" in value -> {
                val fields = value["mapValue"]?.jsonObject?.get("fields")?.jsonObject ?: return emptyMap<String, Any>()
                fromFirestoreFields(fields)
            }
            "timestampValue" in value -> value["timestampValue"]?.jsonPrimitive?.content
            else -> null
        }
    }

    // ─── User Operations (mirrors Python firebase_service.py) ──

    suspend fun getUser(userId: Long): UserData? {
        val doc = firestoreGet("users/$userId") ?: return null
        val fields = doc["fields"]?.jsonObject ?: return null
        return userDataFromFields(fields)
    }

    suspend fun createOrUpdateUser(userId: Long, data: Map<String, Any?>) {
        // Merge: fetch existing, overlay new fields
        val existing = getUser(userId)
        val merged = mutableMapOf<String, Any?>()

        if (existing != null) {
            merged["telegram_username"] = existing.telegramUsername
            merged["first_name"] = existing.firstName
            merged["profile_name"] = existing.profileName
            merged["profile_bio"] = existing.profileBio
            merged["tone"] = existing.tone
            merged["reply_length"] = existing.replyLength
            merged["voice_id"] = existing.voiceId
            merged["voice_only"] = existing.voiceOnly
            merged["ollama_keys"] = existing.ollamaKeys
            merged["elevenlabs_keys"] = existing.elevenlabsKeys
            merged["last_interaction"] = existing.lastInteraction
            merged["chat_id"] = existing.chatId
            merged["proactive_sent"] = existing.proactiveSent
        }

        merged.putAll(data)
        firestoreSet("users/$userId", toFirestoreFields(merged))
    }

    suspend fun getAllUsers(): Map<Long, UserData> {
        val docs = firestoreQuery("users", "", limit = 500)
        val result = mutableMapOf<Long, UserData>()
        for (doc in docs) {
            val name = doc["name"]?.jsonPrimitive?.content ?: continue
            val userId = name.substringAfterLast("/").toLongOrNull() ?: continue
            val fields = doc["fields"]?.jsonObject ?: continue
            result[userId] = userDataFromFields(fields)
        }
        return result
    }

    private fun userDataFromFields(fields: JsonObject): UserData {
        val data = fromFirestoreFields(fields)
        @Suppress("UNCHECKED_CAST")
        return UserData(
            telegramUsername = data["telegram_username"] as? String,
            firstName = data["first_name"] as? String,
            profileName = data["profile_name"] as? String,
            profileBio = data["profile_bio"] as? String,
            tone = (data["tone"] as? String) ?: "casual",
            replyLength = (data["reply_length"] as? String) ?: "medium",
            voiceId = data["voice_id"] as? String,
            voiceOnly = (data["voice_only"] as? Boolean) ?: false,
            ollamaKeys = (data["ollama_keys"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            elevenlabsKeys = (data["elevenlabs_keys"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList(),
            lastInteraction = (data["last_interaction"] as? Number)?.toDouble() ?: 0.0,
            chatId = (data["chat_id"] as? Number)?.toLong() ?: 0L,
            proactiveSent = (data["proactive_sent"] as? Boolean) ?: false,
        )
    }

    // ─── Chat History ──────────────────────────────────────────

    suspend fun getChatHistory(userId: Long, limit: Int = 20): List<ChatMessage> {
        val docs = firestoreQuery(
            collectionId = "messages",
            parentPath = "chats/$userId",
            orderBy = "timestamp",
            descending = true,
            limit = limit,
        )

        val messages = docs.mapNotNull { doc ->
            val fields = doc["fields"]?.jsonObject ?: return@mapNotNull null
            val data = fromFirestoreFields(fields)
            ChatMessage(
                role = (data["role"] as? String) ?: return@mapNotNull null,
                content = (data["content"] as? String) ?: return@mapNotNull null,
                timestamp = (data["timestamp"] as? String)?.let { parseTimestamp(it) }
                    ?: (data["timestamp"] as? Number)?.toLong()
                    ?: System.currentTimeMillis(),
            )
        }

        return messages.reversed() // oldest first
    }

    suspend fun saveMessage(userId: Long, role: String, content: String) {
        val fields = toFirestoreFields(
            mapOf(
                "role" to role,
                "content" to content,
                "timestamp" to System.currentTimeMillis().toDouble() / 1000.0,
            )
        )
        firestoreCreate("chats/$userId/messages", fields)
    }

    suspend fun clearChatHistory(userId: Long): Int {
        val docs = firestoreQuery(
            collectionId = "messages",
            parentPath = "chats/$userId",
        )
        var count = 0
        for (doc in docs) {
            val name = doc["name"]?.jsonPrimitive?.content ?: continue
            // Extract the relative path from the full resource name
            val relativePath = name.substringAfter("/documents/")
            firestoreDelete(relativePath)
            count++
        }
        return count
    }

    // ─── Error Messages ────────────────────────────────────────

    suspend fun getErrorMessages(category: String): List<String> {
        val doc = firestoreGet("error_messages/$category") ?: return emptyList()
        val fields = doc["fields"]?.jsonObject ?: return emptyList()
        val data = fromFirestoreFields(fields)
        @Suppress("UNCHECKED_CAST")
        return (data["messages"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
    }

    suspend fun seedErrorMessages() {
        val defaults = mapOf(
            "quota_exceeded" to listOf(
                "Hey… I think I'm a bit tired right now 😅 Try again soon?",
                "Oops, looks like I've been talking too much today 😄 Give me a moment!",
                "I need a tiny break 💤 My quota ran out, but I'll be back!",
            ),
            "invalid_key" to listOf(
                "Something feels off on my side… maybe check your API settings?",
                "Hmm, I can't seem to connect properly. Could you check your API key? 🔑",
                "I'm having trouble with authentication… mind double-checking your key?",
            ),
            "network_error" to listOf(
                "I think the internet is playing tricks on us 😕 Try again?",
                "Connection hiccup! Let's try that again in a sec 🌐",
                "Oops, lost my train of thought there… network issue! Try once more?",
            ),
            "unknown_error" to listOf(
                "Something unexpected happened 😅 But don't worry, I'll figure it out!",
                "Hmm, that's weird… Let me try again if you send your message once more?",
                "I got a bit confused there 🤔 Could you try again?",
            ),
            "stt_error" to listOf(
                "I couldn't quite hear that clearly 🎤 Could you try sending the voice message again?",
                "Hmm, the audio was a bit tricky for me. Mind trying once more?",
                "Sorry, I had trouble understanding that voice message. Try again? 🎧",
            ),
            "tts_error" to listOf(
                "I wanted to talk back but my voice is being shy today 😅 Here's text instead!",
                "My voice module hiccupped! Sending you text for now 📝",
            ),
        )

        for ((category, messages) in defaults) {
            val existing = getErrorMessages(category)
            if (existing.isEmpty()) {
                firestoreSet(
                    "error_messages/$category",
                    toFirestoreFields(mapOf("messages" to messages))
                )
            }
        }
    }

    // ─── Helpers ───────────────────────────────────────────────

    private fun parseTimestamp(ts: String): Long {
        return try {
            // Firestore timestamp format: "2024-01-01T00:00:00.000Z"
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
                .parse(ts.take(19))?.time ?: System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }
}
