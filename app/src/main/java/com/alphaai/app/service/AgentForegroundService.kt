package com.alphaai.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.alphaai.app.MainActivity
import com.alphaai.app.R
import com.alphaai.app.data.agent.AgentEventBus
import com.alphaai.app.data.agent.AgentLoop
import com.alphaai.app.data.repository.SessionRepository
import com.alphaai.app.domain.model.AgentEvent
import com.alphaai.app.domain.model.SessionStatus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AgentForegroundService : Service() {
    @Inject lateinit var agentLoop: AgentLoop
    @Inject lateinit var sessions: SessionRepository
    @Inject lateinit var eventBus: AgentEventBus

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runJob: Job? = null
    private var eventJob: Job? = null
    private var sessionId: Long = -1
    private var liveStep: String = "Starting agent…"

    override fun onCreate() {
        super.onCreate()
        createChannel()
        eventJob = serviceScope.launch {
            eventBus.events.collect { event ->
                if (event.sessionId != sessionId) return@collect
                when (event) {
                    is AgentEvent.ToolStarted -> liveStep = event.stepLabel
                    is AgentEvent.StateChanged -> liveStep = event.state.name.lowercase().replaceFirstChar { it.uppercase() }
                    is AgentEvent.Done -> liveStep = "Completed"
                    is AgentEvent.Failed -> liveStep = "Failed"
                    else -> Unit
                }
                updateNotification()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                runJob?.cancel(CancellationException("Stopped by user"))
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_OPEN -> {
                openApp()
                return START_NOT_STICKY
            }
        }
        val id = intent?.getLongExtra(EXTRA_SESSION_ID, -1L) ?: -1L
        if (id <= 0L) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (runJob?.isActive == true && sessionId == id) return START_STICKY
        sessionId = id
        liveStep = "Starting agent…"
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        runJob?.cancel()
        runJob = serviceScope.launch {
            try {
                agentLoop.run(id)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("Alpha AI is working")
        .setContentText(liveStep)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .addAction(0, "Stop", pendingIntent(ACTION_STOP, 1))
        .addAction(0, "Open", pendingIntent(ACTION_OPEN, 2))
        .build()

    private fun updateNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification())
    }

    private fun pendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, AgentForegroundService::class.java).setAction(action)
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(EXTRA_SESSION_ID, sessionId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Agent run", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Progress notifications for long-running Alpha AI tasks"
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        eventJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        const val ACTION_STOP = "com.alphaai.app.action.STOP_AGENT"
        const val ACTION_OPEN = "com.alphaai.app.action.OPEN_AGENT"
        private const val CHANNEL_ID = "alphaai_agent"
        private const val NOTIFICATION_ID = 3001

        fun start(context: Context, sessionId: Long) {
            val intent = Intent(context, AgentForegroundService::class.java).putExtra(EXTRA_SESSION_ID, sessionId)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }
    }
}
