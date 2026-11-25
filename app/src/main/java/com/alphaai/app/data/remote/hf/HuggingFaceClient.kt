package com.alphaai.app.data.remote.hf

import com.alphaai.app.data.remote.ChatRequest
import com.alphaai.app.data.remote.ChatResponse
import com.alphaai.app.data.remote.MissingApiKeyException
import com.alphaai.app.data.remote.ModelClient
import com.alphaai.app.data.remote.ProviderHttpException
import com.alphaai.app.data.remote.collectSse
import com.alphaai.app.data.security.SecureKeyStore
import com.alphaai.app.data.security.ApiKeyProvider
import com.alphaai.app.data.security.SecureAuthTag
import com.alphaai.app.domain.model.ChatMessageDto
import com.alphaai.app.domain.model.Provider
import com.alphaai.app.util.SafeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hugging Face Inference API.
 * Chat/code models are reached through text-generation-inference semantics:
 * POST /models/{model} with an SSE `Accept` header; `token.text` chunks are
 * forwarded as deltas. If streaming is unavailable for the model, we retry
 * non-streamed and emit the full text as a single delta.
 * Embeddings use BAAI/bge-small-en-v1.5 via the feature-extraction pipeline.
 */
@Singleton
class HuggingFaceClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val keyStore: ApiKeyProvider
) : ModelClient {

    override val provider: Provider = Provider.HF

    private val baseUrl = "https://api-inference.huggingface.co"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun testConnection(model: String): String? {
        if (!keyStore.has(SecureKeyStore.Secret.HF)) return "No Hugging Face API key set"
        return try {
            val resp = streamChat(
                ChatRequest(
                    model = model,
                    messages = listOf(ChatMessageDto("user", "Reply with the single word: ok")),
                    maxTokens = 8
                )
            ) { }
            if (resp.content.isNotBlank()) null else "Empty response from Hugging Face"
        } catch (e: Exception) {
            e.message ?: "Hugging Face request failed"
        }
    }

    override suspend fun streamChat(
        request: ChatRequest,
        onDelta: suspend (String) -> Unit
    ): ChatResponse {
        if (!keyStore.has(SecureKeyStore.Secret.HF)) throw MissingApiKeyException(Provider.HF)

        val prompt = renderPrompt(request.messages)
        val body = buildJsonObject {
            put("inputs", prompt)
            putJsonObject("parameters") {
                put("max_new_tokens", request.maxTokens)
                put("temperature", request.temperature)
                put("return_full_text", false)
            }
        }
        val payload = Json.encodeToString(body).toRequestBody(JSON_MEDIA)

        val streamRequest = Request.Builder()
            .url("$baseUrl/models/${request.model}")
            .tag(SecureAuthTag::class.java, SecureAuthTag(SecureKeyStore.Secret.HF, "Authorization", "Bearer "))
            .header("Accept", "text/event-stream")
            .header("Content-Type", "application/json")
            .post(payload)
            .build()

        val started = System.currentTimeMillis()
        val content = StringBuilder()
        var streamed = false

        try {
            collectSse(okHttpClient, provider, streamRequest) { data ->
                val obj = runCatching { json.parseToJsonElement(data) }.getOrNull() ?: return@collectSse
                val tokenText = (obj as? kotlinx.serialization.json.JsonObject)
                    ?.get("token")
                    ?.let { it as? kotlinx.serialization.json.JsonObject }
                    ?.get("text")?.jsonPrimitive?.content
                if (!tokenText.isNullOrEmpty()) {
                    streamed = true
                    content.append(tokenText)
                    onDelta(tokenText)
                }
            }
        } catch (e: ProviderHttpException) {
            if (e.isRateLimited) throw e // let the router handle 429 explicitly
            SafeLog.w(TAG, "HF stream unavailable (${e.message}); falling back to non-streamed call")
            // Non-streamed fallback: single delta with the full text.
            val plainRequest = Request.Builder()
                .url("$baseUrl/models/${request.model}")
                .tag(SecureAuthTag::class.java, SecureAuthTag(SecureKeyStore.Secret.HF, "Authorization", "Bearer "))
                .header("Content-Type", "application/json")
                .post(payload)
                .build()
            val text = withContext(Dispatchers.IO) {
                okHttpClient.newCall(plainRequest).execute().use { resp ->
                    val bodyStr = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw ProviderHttpException(provider, resp.code, bodyStr.take(300))
                    }
                    parseGeneratedText(bodyStr)
                }
            }
            content.setLength(0)
            content.append(text)
            if (text.isNotEmpty()) onDelta(text)
        }

        val latency = System.currentTimeMillis() - started
        // HF serverless inference does not reliably report token counts; estimate from chars.
        val estPrompt = (prompt.length / 4).toLong()
        val estCompletion = (content.length / 4).toLong()
        SafeLog.d(TAG, "hf done: ${content.length} chars in ${latency}ms (streamed=$streamed)")
        return ChatResponse(
            content = content.toString(),
            promptTokens = estPrompt,
            completionTokens = estCompletion,
            finishReason = null,
            model = request.model,
            provider = provider,
            latencyMs = latency
        )
    }

    /** Feature-extraction embeddings (BAAI/bge-small-en-v1.5). Returns null on any failure. */
    suspend fun embed(text: String): List<Float>? {
        if (!keyStore.has(SecureKeyStore.Secret.HF)) return null
        val body = buildJsonObject { put("inputs", text) }
        val request = Request.Builder()
            .url("$baseUrl/pipeline/feature-extraction/BAAI/bge-small-en-v1.5")
            .tag(SecureAuthTag::class.java, SecureAuthTag(SecureKeyStore.Secret.HF, "Authorization", "Bearer "))
            .header("Content-Type", "application/json")
            .post(Json.encodeToString(body).toRequestBody(JSON_MEDIA))
            .build()
        return withContext(Dispatchers.IO) {
            runCatching {
                okHttpClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val root = json.parseToJsonElement(resp.body?.string().orEmpty())
                    flattenFloats(root)
                }
            }.getOrNull()
        }
    }

    private fun flattenFloats(el: kotlinx.serialization.json.JsonElement): List<Float> {
        // Server returns either [[f...]] (tokenized rows) or [f...] (pooled) depending on model.
        val out = mutableListOf<Float>()
        fun walk(node: kotlinx.serialization.json.JsonElement) {
            when (node) {
                is kotlinx.serialization.json.JsonArray -> node.forEach { walk(it) }
                is kotlinx.serialization.json.JsonPrimitive -> node.jsonPrimitive.content.toFloatOrNull()?.let { out.add(it) }
                else -> Unit
            }
        }
        walk(el)
        return out
    }

    private fun renderPrompt(messages: List<ChatMessageDto>): String =
        messages.joinToString("\n\n") { m ->
            when (m.role) {
                "system" -> "System: ${m.content}"
                "assistant" -> "Assistant: ${m.content}"
                else -> "User: ${m.content}"
            }
        } + "\n\nAssistant:"

    private fun parseGeneratedText(body: String): String {
        // Typical shapes: [{"generated_text": "..."}] or {"generated_text": "..."}
        runCatching {
            val el = json.parseToJsonElement(body)
            val obj = when (el) {
                is kotlinx.serialization.json.JsonArray -> el.firstOrNull() as? kotlinx.serialization.json.JsonObject
                else -> el as? kotlinx.serialization.json.JsonObject
            }
            return obj?.get("generated_text")?.jsonPrimitive?.content ?: body.take(2000)
        }
        return body.take(2000)
    }

    companion object {
        private const val TAG = "HuggingFaceClient"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }
}
