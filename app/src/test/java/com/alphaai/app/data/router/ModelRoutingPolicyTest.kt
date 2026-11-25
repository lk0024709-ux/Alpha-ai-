package com.alphaai.app.data.router

import com.alphaai.app.domain.model.ModelPreference
import com.alphaai.app.domain.model.Provider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ModelRoutingPolicyTest {
    @Test fun autoUsesGroqForOrdinaryPrompts() {
        val route = ModelRoutingPolicy.choose(ModelPreference.AUTO, 2_000, false, Provider.entries.toSet(), Provider.entries.toSet())
        assertEquals(Provider.GROQ, route.provider)
        assertEquals(ModelRoutingPolicy.GROQ_DEFAULT, route.modelId)
    }

    @Test fun visionAndLongPromptsForceGeminiPro() {
        assertEquals(ModelRoutingPolicy.GEMINI_PRO, ModelRoutingPolicy.choose(ModelPreference.GROQ, 100, true, Provider.entries.toSet(), Provider.entries.toSet()).modelId)
        assertEquals(Provider.GEMINI, ModelRoutingPolicy.choose(ModelPreference.AUTO, 60_001, false, emptySet(), emptySet()).provider)
    }

    @Test fun fallbackOrderStartsWithSelectedProviderThenGroqGeminiHf() {
        assertEquals(listOf(Provider.GEMINI, Provider.GROQ, Provider.HF), ModelRoutingPolicy.fallbackOrder(Provider.GEMINI))
        assertEquals(listOf(Provider.GROQ, Provider.GEMINI, Provider.HF), ModelRoutingPolicy.fallbackOrder(Provider.GROQ))
    }

    @Test fun degradedGroqCanChooseAvailableHealthyFallback() {
        val route = ModelRoutingPolicy.choose(
            ModelPreference.AUTO, 1_000, false,
            healthyProviders = setOf(Provider.HF), availableProviders = setOf(Provider.HF)
        )
        assertEquals(Provider.HF, route.provider)
    }
}
