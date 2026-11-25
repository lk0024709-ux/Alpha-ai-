package com.alphaai.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeById(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE status = 'RUNNING' ORDER BY updatedAt DESC")
    suspend fun getInterruptedRunning(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE title LIKE '%' || :query || '%' OR task LIKE '%' || :query || '%' ORDER BY updatedAt DESC")
    fun search(query: String): Flow<List<SessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: SessionEntity): Long

    @Query("UPDATE sessions SET status = :status, updatedAt = :now WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET todosJson = :todosJson, updatedAt = :now WHERE id = :id")
    suspend fun setTodos(id: Long, todosJson: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET task = task || '\\n\\nFollow-up: ' || :followUp, todosJson = :todosJson, status = 'RUNNING', stepCount = 0, updatedAt = :now WHERE id = :id")
    suspend fun prepareFollowUp(id: Long, followUp: String, todosJson: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET memorySummary = :summary, updatedAt = :now WHERE id = :id")
    suspend fun setMemorySummary(id: Long, summary: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET summary = :summary, status = :status, updatedAt = :now WHERE id = :id")
    suspend fun complete(id: Long, summary: String?, status: String, now: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET totalPromptTokens = totalPromptTokens + :p, totalCompletionTokens = totalCompletionTokens + :c, totalCostUsd = totalCostUsd + :cost, stepCount = :stepCount, updatedAt = :now WHERE id = :id")
    suspend fun addUsage(id: Long, p: Long, c: Long, cost: Double, stepCount: Int, now: Long = System.currentTimeMillis())

    @Query("UPDATE sessions SET totalPromptTokens = totalPromptTokens + :p, totalCompletionTokens = totalCompletionTokens + :c, totalCostUsd = totalCostUsd + :cost, updatedAt = :now WHERE id = :id")
    suspend fun addTokens(id: Long, p: Long, c: Long, cost: Double, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun count(): Int
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    fun observeForSession(sessionId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun getForSession(sessionId: Long): List<MessageEntity>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun count(sessionId: Long): Int

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)
}

@Dao
interface ToolCallDao {
    @Query("SELECT * FROM tool_calls WHERE sessionId = :sessionId ORDER BY startedAt ASC")
    fun observeForSession(sessionId: Long): Flow<List<ToolCallEntity>>

    @Query("SELECT * FROM tool_calls WHERE sessionId = :sessionId ORDER BY startedAt ASC")
    suspend fun getForSession(sessionId: Long): List<ToolCallEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(call: ToolCallEntity)

    @Query("UPDATE tool_calls SET status = :status, resultJson = :result, finishedAt = :finishedAt WHERE callId = :callId")
    suspend fun finish(callId: String, status: String, result: String, finishedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM tool_calls WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)
}

@Dao
interface ArtifactDao {
    @Query("SELECT * FROM artifacts ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<ArtifactEntity>>

    @Query("SELECT * FROM artifacts WHERE sessionId = :sessionId ORDER BY createdAt DESC")
    fun observeForSession(sessionId: Long): Flow<List<ArtifactEntity>>

    @Query("SELECT * FROM artifacts WHERE id = :id")
    suspend fun getById(id: Long): ArtifactEntity?

    @Query("SELECT * FROM artifacts WHERE sessionId = :sessionId")
    suspend fun getForSession(sessionId: Long): List<ArtifactEntity>

    @Insert
    suspend fun insert(artifact: ArtifactEntity): Long

    @Query("DELETE FROM artifacts WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM artifacts WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)
}

@Dao
interface UsageLogDao {
    @Query("SELECT * FROM usage_logs ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<UsageLogEntity>>

    @Query("SELECT COALESCE(SUM(estCostUsd), 0.0) FROM usage_logs WHERE sessionId = :sessionId")
    suspend fun totalCostForSession(sessionId: Long): Double

    @Insert
    suspend fun insert(log: UsageLogEntity): Long
}
