package com.alphaai.app.data.remote.gemini

import com.alphaai.app.data.remote.ChatRequest
import com.alphaai.app.data.remote.ChatResponse
import com.alphaai.app.data.remote.MissingApiKeyException
import com.alphaai.app.data.remote.ModelClient
import com.alphaai.app.data.remote.collectSse
import com.alphaai.app.data.security.SecureKeyStore
import com.alphaai.app.data.security.ApiKeyProvider
import com.alphaai.app.data.security.SecureAuthTag
import com.alphaai.app.domain.model.ChatMessageDto
import com.alphaai.app.domain.model.Provider
import com.alphaai.app.util.SafeLog
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google AI Studio (Gemini) via the v1beta REST API over SSE.
 * Auth uses the `x-goog-api-key` header so keys never appear in URLs or logs.
 * Images are passed inline as base64 via the `inlineData` part type (vision models).
 */
@Singleton
class GeminiClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val keyStore: ApiKeyProvider
) : ModelClient {

    override val provider: Provider = Provider.GEMINI

    private val baseUrl = "https://generativelanguage.googleapis.com/v1beta"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun testConnection(model: String): String? {
        if (!keyStore.has(SecureKeyStore.Secret.GEMINI)) return "No Gemini API key set"
        return try {
            var got = false
            streamChat(
                ChatRequest(
                    model = model,
                    messages = listOf(ChatMessageDto("user", "Reply with the single word: ok")),
                    maxTokens = 8
                )
            ) { delta -> if (delta.isNotBlank()) got = true }
            if (got) null else "Empty response from Gemini"
        } catch (e: Exception) {
            e.message ?: "Gemini request failed"
        }
    }

    override suspend fun streamChat(
        request: ChatRequest,
        onDelta: suspend (String) -> Unit
    ): ChatResponse {
        if (!keyStore.has(SecureKeyStore.Secret.GEMINI)) throw MissingApiKeyException(Provider.GEMINI)

        val systemText = request.messages.filter { it.role == "system" }
            .joinToString("\n") { it.content }
            .ifBlank { null }
        val turns = request.messages.filter { it.role != "system" }

        val body = buildJsonObject {
            systemText?.let { sys ->
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { add(buildJsonObject { put("text", sys) }) }
                }
            }
            putJsonArray("contents") {
                turns.forEachIndexed { index, m ->
                    add(buildJsonObject {
                        put("role", if (m.role == "assistant") "model" else "user")
                        putJsonArray("parts") {
                            add(buildJsonObject { put("text", m.content) })
                            m.images.forEach { image ->
                                add(buildJsonObject {
                                    putJsonObject("inlineData") {
                                        put("mimeType", image.mimeType)
                                        put("data", image.base64Data)
                                    }
                                })
                            }
                        }
                    })
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", request.temperature)
                put("maxOutputTokens", request.maxTokens)
            }
        }

        val httpRequest = Request.Builder()
            .url("$baseUrl/models/${request.model}:streamGenerateContent?alt=sse")
            .tag(SecureAuthTag::class.java, SecureAuthTag(SecureKeyStore.Secret.GEMINI, "x-goog-api-key"))
            .header("Content-Type", "application/json")
            .post(Json.encodeToString(body).toRequestBody(JSON_MEDIA))
            .build()

        val started = System.currentTimeMillis()
        val content = StringBuilder()
        var promptTokens = 0L
        var completionTokens = 0L
        var finishReason: String? = null

        collectSse(okHttpClient, provider, httpRequest) { data ->
            val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
                ?: return@collectSse

            (obj["usageMetadata"] as? kotlinx.serialization.json.JsonObject)?.let { usage ->
                promptTokens = usage["promptTokenCount"]?.jsonPrimitive?.longOrNull ?: promptTokens
                completionTokens = usage["candidatesTokenCount"]?.jsonPrimitive?.longOrNull ?: completionTokens
            }

            val candidate = obj["candidates"]?.jsonArray?.firstOrNull() as? kotlinx.serialization.json.JsonObject
            candidate?.get("finishReason")?.jsonPrimitive?.content?.let { finishReason = it }
            val parts = (candidate?.get("content") as? kotlinx.serialization.json.JsonObject)
                ?.get("parts")?.jsonArray ?: return@collectSse
            parts.forEach { part ->
                val text = (part as? kotlinx.serialization.json.JsonObject)?.get("text")?.jsonPrimitive?.content
                if (!text.isNullOrEmpty()) {
                    content.append(text)
                    onDelta(text)
                }
            }
        }

        val latency = System.currentTimeMillis() - started
        SafeLog.d(TAG, "gemini stream done: ${content.length} chars in ${latency}ms")
        return ChatResponse(
            content = content.toString(),
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            finishReason = finishReason,
            model = request.model,
            provider = provider,
            latencyMs = latency
        )
    }

    companion object {
        private const val TAG = "GeminiClient"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
