package com.alphaai.app.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sessions", indices = [Index("updatedAt"), Index("status")])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val task: String,
    /** SessionStatus name */
    val status: String,
    /** SandboxProfile name */
    val profile: String,
    /** ModelPreference name */
    val modelPreference: String,
    /** Serialized List<TodoItem> — single source of truth for resume. */
    val todosJson: String = "[]",
    /** Rolling summary of older context once memory compaction kicks in. */
    val memorySummary: String? = null,
    val summary: String? = null,
    val totalPromptTokens: Long = 0,
    val totalCompletionTokens: Long = 0,
    val totalCostUsd: Double = 0.0,
    val stepCount: Int = 0,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(tableName = "messages", indices = [Index("sessionId"), Index("createdAt")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    /** "user" | "assistant" | "thinking" | "system" | "tool" */
    val role: String,
    val content: String,
    /** For role == "tool", the originating callId. */
    val toolCallId: String? = null,
    val createdAt: Long
)

@Entity(tableName = "tool_calls", indices = [Index("sessionId"), Index("createdAt")])
data class ToolCallEntity(
    @PrimaryKey val callId: String,
    val sessionId: Long,
    val tool: String,
    val argsJson: String,
    val resultJson: String? = null,
    /** RUNNING | SUCCESS | ERROR */
    val status: String,
    val startedAt: Long,
    val finishedAt: Long? = null
)

@Entity(tableName = "artifacts", indices = [Index("sessionId"), Index("createdAt")])
data class ArtifactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val name: String,
    val mimeType: String,
    /** Path relative to the app-private artifacts/ root. */
    val relativePath: String,
    val sizeBytes: Long,
    val createdAt: Long
)

@Entity(tableName = "usage_logs", indices = [Index("sessionId"), Index("createdAt")])
data class UsageLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long?,
    /** Provider name, e.g. GROQ / GEMINI / HF */
    val provider: String,
    val model: String,
    val promptTokens: Long,
    val completionTokens: Long,
    val latencyMs: Long,
    val estCostUsd: Double,
    /** SUCCESS | ERROR_429 | ERROR_OTHER | FALLBACK_SKIPPED */
    val result: String,
    val createdAt: Long
)
