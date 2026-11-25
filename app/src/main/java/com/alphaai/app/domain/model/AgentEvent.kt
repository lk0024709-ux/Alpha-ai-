package com.alphaai.app.domain.model

/**
 * Single event stream emitted by the agent loop. The same stream feeds the UI,
 * the foreground-service notification, and is checkpointed to Room after every event.
 */
sealed interface AgentEvent {
    val sessionId: Long

    data class StateChanged(override val sessionId: Long, val state: AgentState) : AgentEvent

    data class PlanCreated(override val sessionId: Long, val todos: List<TodoItem>) : AgentEvent

    /** Character-level streaming deltas for the collapsible "Thinking" block. */
    data class ThoughtDelta(override val sessionId: Long, val text: String) : AgentEvent

    /** The full thinking text for the step, persisted when the step closes. */
    data class ThoughtCompleted(override val sessionId: Long, val text: String) : AgentEvent

    data class ToolStarted(
        override val sessionId: Long,
        val callId: String,
        val tool: String,
        val argsJson: String,
        /** Human description of the target step, e.g. "3/6 Deploy" — shown in the notification. */
        val stepLabel: String
    ) : AgentEvent

    data class ToolDelta(override val sessionId: Long, val callId: String, val text: String) : AgentEvent

    data class ToolFinished(
        override val sessionId: Long,
        val callId: String,
        val tool: String,
        val result: String,
        val isError: Boolean,
        /** Relative path (under artifacts/) when the tool produced a file artifact. */
        val artifactPath: String? = null
    ) : AgentEvent

    data class StepFinished(override val sessionId: Long, val step: Int, val todoId: Int?) : AgentEvent

    data class UsageUpdated(
        override val sessionId: Long,
        val totalPromptTokens: Long,
        val totalCompletionTokens: Long,
        val estCostUsd: Double
    ) : AgentEvent

    data class Done(override val sessionId: Long, val summary: String) : AgentEvent

    data class Failed(override val sessionId: Long, val reason: String) : AgentEvent
}
