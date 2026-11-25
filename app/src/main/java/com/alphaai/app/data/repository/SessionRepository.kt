package com.alphaai.app.data.repository

import com.alphaai.app.data.local.MessageDao
import com.alphaai.app.data.local.MessageEntity
import com.alphaai.app.data.local.SessionDao
import com.alphaai.app.data.local.SessionEntity
import com.alphaai.app.data.local.ToolCallDao
import com.alphaai.app.data.local.ToolCallEntity
import com.alphaai.app.domain.model.ModelPreference
import com.alphaai.app.domain.model.SandboxProfile
import com.alphaai.app.domain.model.SessionStatus
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SessionRepository @Inject constructor(
    private val sessions: SessionDao,
    private val messages: MessageDao,
    private val toolCalls: ToolCallDao,
    private val artifacts: ArtifactRepository
) {
    val allSessions: Flow<List<SessionEntity>> = sessions.observeAll()

    fun observeSearch(query: String): Flow<List<SessionEntity>> = sessions.search(query)
    fun observeSession(id: Long): Flow<SessionEntity?> = sessions.observeById(id)
    fun observeMessages(id: Long): Flow<List<MessageEntity>> = messages.observeForSession(id)
    fun observeToolCalls(id: Long): Flow<List<ToolCallEntity>> = toolCalls.observeForSession(id)

    suspend fun create(
        task: String,
        profile: SandboxProfile,
        modelPreference: ModelPreference
    ): Long {
        val now = System.currentTimeMillis()
        val title = task.trim().replace(Regex("\\s+"), " ").take(72).ifBlank { "New task" }
        return sessions.upsert(
            SessionEntity(
                title = title,
                task = task.trim(),
                status = SessionStatus.RUNNING.name,
                profile = profile.name,
                modelPreference = modelPreference.name,
                createdAt = now,
                updatedAt = now
            )
        )
    }

    suspend fun get(id: Long): SessionEntity? = sessions.getById(id)
    suspend fun interrupted(): List<SessionEntity> = sessions.getInterruptedRunning()

    suspend fun addMessage(sessionId: Long, role: String, content: String, toolCallId: String? = null) {
        messages.insert(MessageEntity(sessionId = sessionId, role = role, content = content, toolCallId = toolCallId, createdAt = System.currentTimeMillis()))
    }

    suspend fun messagesFor(sessionId: Long): List<MessageEntity> = messages.getForSession(sessionId)

    suspend fun saveTodos(id: Long, todosJson: String) = sessions.setTodos(id, todosJson)
    suspend fun prepareFollowUp(id: Long, text: String, todosJson: String) = sessions.prepareFollowUp(id, text, todosJson)
    suspend fun resume(id: Long, todosJson: String) {
        sessions.setTodos(id, todosJson)
        sessions.setStatus(id, SessionStatus.RUNNING)
    }
    suspend fun setStatus(id: Long, status: SessionStatus) = sessions.setStatus(id, status.name)
    suspend fun complete(id: Long, summary: String?, status: SessionStatus) = sessions.complete(id, summary, status.name)
    suspend fun addUsage(id: Long, prompt: Long, completion: Long, cost: Double, step: Int) =
        sessions.addUsage(id, prompt, completion, cost, step)
    suspend fun addTokens(id: Long, prompt: Long, completion: Long, cost: Double) =
        sessions.addTokens(id, prompt, completion, cost)
    suspend fun setMemorySummary(id: Long, summary: String) = sessions.setMemorySummary(id, summary)

    suspend fun upsertToolCall(call: ToolCallEntity) = toolCalls.upsert(call)
    suspend fun finishToolCall(id: String, status: String, result: String) = toolCalls.finish(id, status, result)

    suspend fun delete(id: Long) {
        messages.deleteForSession(id)
        toolCalls.deleteForSession(id)
        artifacts.deleteForSession(id)
        sessions.delete(id)
    }
}
