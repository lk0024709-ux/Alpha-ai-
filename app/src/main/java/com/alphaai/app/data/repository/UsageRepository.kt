package com.alphaai.app.data.repository

import com.alphaai.app.data.local.UsageLogDao
import com.alphaai.app.data.local.UsageLogEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsageRepository @Inject constructor(
    private val dao: UsageLogDao
) {
    val recentLogs: Flow<List<UsageLogEntity>> = dao.observeRecent(200)

    suspend fun log(
        sessionId: Long?,
        provider: String,
        model: String,
        promptTokens: Long,
        completionTokens: Long,
        latencyMs: Long,
        estCostUsd: Double,
        result: String
    ) {
        dao.insert(
            UsageLogEntity(
                sessionId = sessionId,
                provider = provider,
                model = model,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                latencyMs = latencyMs,
                estCostUsd = estCostUsd,
                result = result,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun totalCostForSession(sessionId: Long): Double = dao.totalCostForSession(sessionId)
}
