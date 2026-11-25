package com.alphaai.app.data.router

/**
 * Estimated per-1M-token prices (USD), used only for the in-app cost meter.
 * Prices are estimates and are labeled as such in the UI; they are not billing data.
 */
object CostCalculator {
    private data class Price(val inputPer1M: Double, val outputPer1M: Double)

    private val PRICES: Map<String, Price> = mapOf(
        "llama-3.3-70b-versatile" to Price(0.59, 0.79),
        "llama-3.1-8b-instant" to Price(0.05, 0.08),
        "gemini-2.0-flash" to Price(0.10, 0.40),
        "gemini-1.5-pro" to Price(1.25, 5.00),
        // Hugging Face hosted models vary; this conservative estimate is displayed as approximate.
        "Qwen/Qwen2.5-Coder-32B-Instruct" to Price(0.20, 0.20)
    )

    private val HF_DEFAULT = Price(0.20, 0.20)

    fun estimate(model: String, promptTokens: Long, completionTokens: Long): Double {
        val p = PRICES[model] ?: if (model.contains('/')) HF_DEFAULT else Price(0.0, 0.0)
        return (promptTokens / 1_000_000.0) * p.inputPer1M +
            (completionTokens / 1_000_000.0) * p.outputPer1M
    }
}
