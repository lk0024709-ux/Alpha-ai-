package com.alphaai.app.data.remote

import com.alphaai.app.domain.model.ChatMessageDto
import com.alphaai.app.domain.model.Provider

/** Thrown when the required API key for a provider is absent from the SecureKeyStore. */
class MissingApiKeyException(val provider: Provider) :
    Exception("Missing API key for $provider — add it in Settings")

/** Thrown for non-2xx provider responses; carries the HTTP code when known. */
class ProviderHttpException(
    val provider: Provider,
    val httpCode: Int?,
    message: String
) : Exception("[$provider ${httpCode ?: "?"}] $message") {
    val isRateLimited: Boolean get() = httpCode == 429
}

data class ChatRequest(
    val model: String,
    val messages: List<ChatMessageDto>,
    val temperature: Double = 0.2,
    val maxTokens: Int = 4096
)

data class ChatResponse(
    val content: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val finishReason: String?,
    val model: String,
    val provider: Provider,
    val latencyMs: Long
)

/**
 * One streaming chat call against a provider. Implementations:
 *  - build auth headers at request time from SecureKeyStore (never from Room/state),
 *  - emit raw content deltas via [onDelta],
 *  - return the fully assembled response including token usage.
 */
interface ModelClient {
    val provider: Provider

    /** Cheap connectivity check used by Settings → "Test". Returns null on success, error message otherwise. */
    suspend fun testConnection(model: String): String?

    suspend fun streamChat(
        request: ChatRequest,
        onDelta: suspend (String) -> Unit
    ): ChatResponse
}
