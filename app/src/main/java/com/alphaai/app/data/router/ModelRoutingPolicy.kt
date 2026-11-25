package com.alphaai.app.data.router

import com.alphaai.app.domain.model.ModelPreference
import com.alphaai.app.domain.model.ModelSpec
import com.alphaai.app.domain.model.Provider

/** Pure provider selection rules, isolated so routing/fallback behavior can be unit tested. */
object ModelRoutingPolicy {
    const val GROQ_DEFAULT = "llama-3.3-70b-versatile"
    const val GROQ_CHEAP = "llama-3.1-8b-instant"
    const val GEMINI_FAST = "gemini-2.0-flash"
    const val GEMINI_PRO = "gemini-1.5-pro"
    const val HF_CODE = "Qwen/Qwen2.5-Coder-32B-Instruct"
    const val HF_EMBED = "BAAI/bge-small-en-v1.5"
    const val LONG_CONTEXT_TOKEN_THRESHOLD = 60_000

    fun modelFor(provider: Provider, cheap: Boolean = false): String = when (provider) {
        Provider.GROQ -> if (cheap) GROQ_CHEAP else GROQ_DEFAULT
        Provider.GEMINI -> GEMINI_FAST
        Provider.HF -> HF_CODE
    }

    fun choose(
        preference: ModelPreference,
        estimatedPromptTokens: Int,
        hasImages: Boolean,
        healthyProviders: Set<Provider>,
        availableProviders: Set<Provider>,
        preferCheap: Boolean = false
    ): ModelSpec {
        if (hasImages || estimatedPromptTokens > LONG_CONTEXT_TOKEN_THRESHOLD) {
            return ModelSpec(Provider.GEMINI, GEMINI_PRO)
        }
        val selected = when (preference) {
            ModelPreference.AUTO -> null
            ModelPreference.GROQ -> Provider.GROQ
            ModelPreference.GEMINI -> Provider.GEMINI
            ModelPreference.HF -> Provider.HF
        }
        if (selected != null) return ModelSpec(selected, modelFor(selected, preferCheap))
        if (Provider.GROQ in healthyProviders) return ModelSpec(Provider.GROQ, modelFor(Provider.GROQ, preferCheap))
        val next = fallbackOrder(Provider.GROQ).firstOrNull { it in healthyProviders && it in availableProviders }
        return ModelSpec(next ?: Provider.GROQ, modelFor(next ?: Provider.GROQ, preferCheap))
    }

    /** Ordered retry chain; the preferred provider is first, then Groq → Gemini → HF. */
    fun fallbackOrder(first: Provider): List<Provider> =
        listOf(first) + listOf(Provider.GROQ, Provider.GEMINI, Provider.HF).filter { it != first }
}
