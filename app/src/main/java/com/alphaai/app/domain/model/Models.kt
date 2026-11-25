package com.alphaai.app.domain.model

/** High-level lifecycle of an Agent run, persisted on the Session row. */
enum class SessionStatus { IDLE, RUNNING, DONE, FAILED, STOPPED, INTERRUPTED }

/** Fine-grained state machine inside a run (Manus-style loop). */
enum class AgentState { IDLE, PLANNING, EXECUTING, OBSERVING, VERIFYING, DONE, FAILED }

/** Per-task sandbox profile (Settings → per task, chosen on Home). */
enum class SandboxProfile { REMOTE, LIGHT }

/** Model preference selected by the user; AUTO applies router rules. */
enum class ModelPreference { AUTO, GROQ, GEMINI, HF }

enum class Provider { GROQ, GEMINI, HF }

enum class TodoStatus { PENDING, IN_PROGRESS, DONE, FAILED }

@kotlinx.serialization.Serializable
data class TodoItem(
    val id: Int,
    val title: String,
    val status: TodoStatus = TodoStatus.PENDING
)

@kotlinx.serialization.Serializable
data class InlineImage(
    val mimeType: String,
    /** Base64 payload held in memory only; never persisted in Room or logged. */
    val base64Data: String
)

@kotlinx.serialization.Serializable
data class ChatMessageDto(
    val role: String, // "system" | "user" | "assistant"
    val content: String,
    val images: List<InlineImage> = emptyList()
)

data class TokenUsage(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val estCostUsd: Double = 0.0
) {
    operator fun plus(other: TokenUsage) = TokenUsage(
        promptTokens + other.promptTokens,
        completionTokens + other.completionTokens,
        estCostUsd + other.estCostUsd
    )
}

data class ModelSpec(
    val provider: Provider,
    val modelId: String
) {
    override fun toString(): String = "$provider:$modelId"
}
