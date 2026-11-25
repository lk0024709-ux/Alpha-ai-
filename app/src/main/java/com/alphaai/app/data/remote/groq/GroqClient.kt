package com.alphaai.app.data.remote.groq

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
import kotlinx.serialization.json.JsonObject
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

/** Groq — OpenAI-compatible chat completions at api.groq.com, streamed over SSE. */
@Singleton
class GroqClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val keyStore: ApiKeyProvider
) : ModelClient {

    internal constructor(okHttpClient: OkHttpClient, keyStore: ApiKeyProvider, baseUrl: String) : this(okHttpClient, keyStore) {
        this.baseUrl = baseUrl.trimEnd('/')
    }

    override val provider: Provider = Provider.GROQ

    private var baseUrl = "https://api.groq.com/openai/v1"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun testConnection(model: String): String? {
        if (!keyStore.has(SecureKeyStore.Secret.GROQ)) return "No Groq API key set"
        return try {
            var got = false
            streamChat(
                ChatRequest(
                    model = model,
                    messages = listOf(ChatMessageDto("user", "Reply with the single word: ok")),
                    maxTokens = 8
                )
            ) { delta -> if (delta.isNotBlank()) got = true }
            if (got) null else "Empty response from Groq"
        } catch (e: Exception) {
            e.message ?: "Groq request failed"
        }
    }

    override suspend fun streamChat(
        request: ChatRequest,
        onDelta: suspend (String) -> Unit
    ): ChatResponse {
        if (!keyStore.has(SecureKeyStore.Secret.GROQ)) throw MissingApiKeyException(Provider.GROQ)

        val body = buildJsonObject {
            put("model", request.model)
            put("temperature", request.temperature)
            put("max_tokens", request.maxTokens)
            put("stream", true)
            putJsonObject("stream_options") { put("include_usage", true) }
            putJsonArray("messages") {
                request.messages.forEach { m ->
                    add(buildJsonObject {
                        put("role", m.role)
                        put("content", m.content)
                    })
                }
            }
        }

        val httpRequest = Request.Builder()
            .url("$baseUrl/chat/completions")
            .tag(SecureAuthTag::class.java, SecureAuthTag(SecureKeyStore.Secret.GROQ, "Authorization", "Bearer "))
            .header("Content-Type", "application/json")
            .post(Json.encodeToString(body).toRequestBody(JSON_MEDIA))
            .build()

        val started = System.currentTimeMillis()
        val content = StringBuilder()
        var promptTokens = 0L
        var completionTokens = 0L
        var finishReason: String? = null

        collectSse(okHttpClient, provider, httpRequest) { data ->
            if (data == "[DONE]") return@collectSse
            val obj = runCatching { json.parseToJsonElement(data).jsonObject }.getOrNull()
                ?: return@collectSse

            // Token usage arrives in the final chunk when stream_options.include_usage is set.
            (obj["usage"] as? JsonObject)?.let { usage ->
                promptTokens = usage["prompt_tokens"]?.jsonPrimitive?.longOrNull ?: promptTokens
                completionTokens = usage["completion_tokens"]?.jsonPrimitive?.longOrNull ?: completionTokens
            }

            val choice = obj["choices"]?.jsonArray?.firstOrNull() as? JsonObject
                ?: return@collectSse
            choice["finish_reason"]?.jsonPrimitive?.content?.takeIf { it != "null" }?.let { finishReason = it }
            val deltaText = (choice["delta"] as? JsonObject)?.get("content")?.jsonPrimitive?.content
            if (!deltaText.isNullOrEmpty()) {
                content.append(deltaText)
                onDelta(deltaText)
            }
        }

        val latency = System.currentTimeMillis() - started
        SafeLog.d(TAG, "groq stream done: ${content.length} chars in ${latency}ms")
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
        private const val TAG = "GroqClient"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
