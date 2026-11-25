package com.alphaai.app.data.router

import com.alphaai.app.data.remote.ChatRequest
import com.alphaai.app.data.remote.ChatResponse
import com.alphaai.app.data.remote.MissingApiKeyException
import com.alphaai.app.data.remote.ModelClient
import com.alphaai.app.data.remote.ProviderHttpException
import com.alphaai.app.data.remote.gemini.GeminiClient
import com.alphaai.app.data.remote.groq.GroqClient
import com.alphaai.app.data.remote.hf.HuggingFaceClient
import com.alphaai.app.data.repository.UsageRepository
import com.alphaai.app.data.security.SecureKeyStore
import com.alphaai.app.domain.model.ChatMessageDto
import com.alphaai.app.domain.model.ModelPreference
import com.alphaai.app.domain.model.ModelSpec
import com.alphaai.app.domain.model.Provider
import com.alphaai.app.util.SafeLog
import kotlinx.coroutines.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Multi-model router with retries and provider fallback.
 *
 * Routing rules (AUTO preference):
 *  1. prompt > 60k estimated tokens, or the task carries images/PDF → Gemini 1.5 Pro
 *  2. otherwise → Groq llama-3.3-70b-versatile
 *
 * Fallback chain on 429 / errors: GROQ → GEMINI → HF.
 * A provider with ≥2 consecutive errors is skipped until it succeeds again.
 * Every attempt is logged to Room UsageLog with tokens, latency and estimated cost.
 */
@Singleton
class ModelRouter @Inject constructor(
    private val groq: GroqClient,
    private val gemini: GeminiClient,
    private val hf: HuggingFaceClient,
    private val usageRepository: UsageRepository,
    private val keyStore: SecureKeyStore
) {
    companion object {
        const val GROQ_DEFAULT = ModelRoutingPolicy.GROQ_DEFAULT
        const val GROQ_CHEAP = ModelRoutingPolicy.GROQ_CHEAP
        const val GEMINI_FAST = ModelRoutingPolicy.GEMINI_FAST
        const val GEMINI_PRO = ModelRoutingPolicy.GEMINI_PRO
        const val HF_CODE = ModelRoutingPolicy.HF_CODE
        const val HF_EMBED = ModelRoutingPolicy.HF_EMBED
        const val LONG_CONTEXT_TOKEN_THRESHOLD = ModelRoutingPolicy.LONG_CONTEXT_TOKEN_THRESHOLD
        private const val TAG = "ModelRouter"

        fun estimateTokens(messages: List<ChatMessageDto>): Int =
            messages.sumOf { it.content.length } / 4 + messages.size * 4
    }

    data class RouteRequest(
        val sessionId: Long?,
        val preference: ModelPreference,
        val messages: List<ChatMessageDto>,
        val hasImages: Boolean = false,
        /** Cheap/fast model for summarization and internal helper calls. */
        val preferCheap: Boolean = false,
        val temperature: Double = 0.2,
        val maxTokens: Int = 4096
    )

    private val consecutiveErrors = ConcurrentHashMap<Provider, AtomicInteger>()

    private val clients: Map<Provider, ModelClient> = mapOf(
        Provider.GROQ to groq,
        Provider.GEMINI to gemini,
        Provider.HF to hf
    )

    private fun defaultModel(provider: Provider, preferCheap: Boolean): String =
        ModelRoutingPolicy.modelFor(provider, preferCheap)

    private fun hasKey(provider: Provider): Boolean = when (provider) {
        Provider.GROQ -> keyStore.has(SecureKeyStore.Secret.GROQ)
        Provider.GEMINI -> keyStore.has(SecureKeyStore.Secret.GEMINI)
        Provider.HF -> keyStore.has(SecureKeyStore.Secret.HF)
    }

    private fun isHealthy(provider: Provider): Boolean =
        (consecutiveErrors[provider]?.get() ?: 0) < 2

    private fun recordFailure(provider: Provider) {
        consecutiveErrors.getOrPut(provider) { AtomicInteger(0) }.incrementAndGet()
    }

    private fun recordSuccess(provider: Provider) {
        consecutiveErrors[provider]?.set(0)
    }

    /** Pure routing decision — exposed for unit tests. */
    fun route(
        preference: ModelPreference,
        estimatedPromptTokens: Int,
        hasImages: Boolean,
        preferCheap: Boolean = false
    ): ModelSpec = ModelRoutingPolicy.choose(
        preference = preference,
        estimatedPromptTokens = estimatedPromptTokens,
        hasImages = hasImages,
        healthyProviders = Provider.entries.filter(::isHealthy).toSet(),
        availableProviders = Provider.entries.filter(::hasKey).toSet(),
        preferCheap = preferCheap
    )

    /**
     * Streams a chat completion applying the route + fallback chain.
     * Returns the successful response. Throws the last error when the whole chain fails.
     */
    suspend fun streamChat(
        request: RouteRequest,
        onDelta: suspend (String) -> Unit
    ): ChatResponse {
        val estimated = estimateTokens(request.messages)
        val containsImages = request.hasImages || request.messages.any { it.images.isNotEmpty() }
        val first = route(request.preference, estimated, containsImages, request.preferCheap)
        val chain = ModelRoutingPolicy.fallbackOrder(first.provider)
            .map { provider -> ModelSpec(provider, if (provider == first.provider) first.modelId else defaultModel(provider, request.preferCheap)) }

        var lastError: Exception? = null
        for (spec in chain) {
            val client = clients.getValue(spec.provider)
            if (!hasKey(spec.provider)) {
                SafeLog.d(TAG, "skip ${spec.provider}: no API key")
                continue
            }
            if (!isHealthy(spec.provider) && spec != first) continue
            val attemptStarted = System.currentTimeMillis()
            try {
                val response = client.streamChat(
                    ChatRequest(
                        model = spec.modelId,
                        messages = request.messages,
                        temperature = request.temperature,
                        maxTokens = request.maxTokens
                    ),
                    onDelta
                )
                recordSuccess(spec.provider)
                logUsage(request.sessionId, spec, response, "SUCCESS")
                return response
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                lastError = e
                recordFailure(spec.provider)
                val result = when ((e as? ProviderHttpException)?.httpCode) {
                    429 -> "ERROR_429"
                    else -> "ERROR_OTHER"
                }
                SafeLog.w(TAG, "${spec.provider} failed (${e.message}); trying next provider")
                logUsage(
                    sessionId = request.sessionId,
                    spec = spec,
                    response = null,
                    result = result,
                    errorMessage = e.message,
                    latencyMsOverride = System.currentTimeMillis() - attemptStarted
                )
                // Missing key is not retryable elsewhere in a meaningful way; move down the chain.
                if (e is MissingApiKeyException) continue
            }
        }
        throw lastError ?: IllegalStateException(
            "No model provider available — add at least one API key in Settings"
        )
    }

    private suspend fun logUsage(
        sessionId: Long?,
        spec: ModelSpec,
        response: ChatResponse?,
        result: String,
        errorMessage: String? = null,
        latencyMsOverride: Long? = null
    ) {
        val prompt = response?.promptTokens ?: 0L
        val completion = response?.completionTokens ?: 0L
        val latency = latencyMsOverride ?: response?.latencyMs ?: 0L
        val cost = if (response != null) CostCalculator.estimate(spec.modelId, prompt, completion) else 0.0
        runCatching {
            usageRepository.log(
                sessionId = sessionId,
                provider = spec.provider.name,
                model = spec.modelId,
                promptTokens = prompt,
                completionTokens = completion,
                latencyMs = latency,
                estCostUsd = cost,
                result = result
            )
        }.onFailure {
            SafeLog.w(TAG, "usage log failed: ${it.message}")
        }
    }

    /** In-memory consecutive error count — exposed for tests and the settings screen. */
    fun consecutiveErrorCount(provider: Provider): Int = consecutiveErrors[provider]?.get() ?: 0

    fun recordErrorForTest(provider: Provider) {
        recordFailure(provider)
    }

    fun resetErrorsForTest() {
        consecutiveErrors.clear()
    }

}
