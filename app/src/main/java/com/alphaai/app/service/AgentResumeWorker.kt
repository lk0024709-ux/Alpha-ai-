package com.alphaai.app.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.alphaai.app.data.agent.AgentLoop
import com.alphaai.app.data.repository.SessionRepository
import com.alphaai.app.domain.model.SessionStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException

@HiltWorker
class AgentResumeWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val sessions: SessionRepository,
    private val agentLoop: AgentLoop
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        return try {
            sessions.interrupted().forEach { session ->
                if (isStopped) throw CancellationException("resume worker stopped")
                agentLoop.run(session.id)
            }
            Result.success()
        } catch (_: CancellationException) {
            Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object { const val UNIQUE_NAME = "alphaai-resume-interrupted" }
}
