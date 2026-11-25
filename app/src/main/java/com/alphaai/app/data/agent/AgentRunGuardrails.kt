package com.alphaai.app.data.agent

/** Pure hard-stop rules for the agent's bounded execution loop. */
object AgentRunGuardrails {
    const val MAX_STEPS = 25
    const val MAX_COST_USD = 0.50
    const val MAX_WALL_MS = 10 * 60 * 1000L

    fun ensureStepAllowed(completedOrAttemptedSteps: Int) {
        require(completedOrAttemptedSteps < MAX_STEPS) { "25-step safety limit reached" }
    }

    fun ensureCostAllowed(estimatedCostUsd: Double, capUsd: Double) {
        require(estimatedCostUsd < capUsd) { "Task cost cap reached" }
    }

    fun ensureNotRepeated(signature: String, seen: MutableSet<String>) {
        require(seen.add(signature)) { "Repeated identical tool call blocked" }
    }
}
