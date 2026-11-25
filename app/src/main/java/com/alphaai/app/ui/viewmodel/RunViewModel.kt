package com.alphaai.app.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alphaai.app.data.agent.AgentEventBus
import com.alphaai.app.data.local.ArtifactEntity
import com.alphaai.app.data.local.MessageEntity
import com.alphaai.app.data.local.SessionEntity
import com.alphaai.app.data.local.ToolCallEntity
import com.alphaai.app.data.repository.ArtifactRepository
import com.alphaai.app.data.repository.SessionRepository
import com.alphaai.app.domain.model.AgentEvent
import com.alphaai.app.domain.model.SessionStatus
import com.alphaai.app.domain.model.TodoItem
import com.alphaai.app.domain.model.TodoStatus
import com.alphaai.app.service.AgentForegroundService
import com.alphaai.app.util.JsonUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@HiltViewModel
class RunViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val application: Application,
    private val repository: SessionRepository,
    private val artifactRepository: ArtifactRepository,
    bus: AgentEventBus
) : ViewModel() {
    val sessionId: Long = checkNotNull(savedStateHandle["sessionId"])
    val session: StateFlow<SessionEntity?> = repository.observeSession(sessionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val messages: StateFlow<List<MessageEntity>> = repository.observeMessages(sessionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val toolCalls: StateFlow<List<ToolCallEntity>> = repository.observeToolCalls(sessionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val artifacts: StateFlow<List<ArtifactEntity>> = artifactRepository.forSession(sessionId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _events = MutableStateFlow<List<AgentEvent>>(emptyList())
    val events: StateFlow<List<AgentEvent>> = _events.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        viewModelScope.launch {
            bus.events.filter { it.sessionId == sessionId }.collect { event ->
                _events.value = (_events.value + event).takeLast(300)
            }
        }
    }

    fun stop() {
        application.startService(Intent(application, AgentForegroundService::class.java).setAction(AgentForegroundService.ACTION_STOP))
    }

    fun sendFollowUp(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            try {
                val current = repository.get(sessionId) ?: return@launch
                val todos = runCatching { JsonUtil.json.decodeFromString<List<TodoItem>>(current.todosJson) }
                    .getOrDefault(emptyList()).map { it.copy(status = TodoStatus.PENDING) }
                repository.addMessage(sessionId, "user", clean)
                repository.prepareFollowUp(sessionId, clean, JsonUtil.json.encodeToString(todos))
                AgentForegroundService.start(application, sessionId)
            } catch (e: Exception) {
                _message.value = e.message ?: "Unable to continue this run"
            }
        }
    }

    fun addAttachment(uri: Uri) {
        viewModelScope.launch {
            try {
                val item = artifactRepository.storeAttachment(sessionId, uri)
                repository.addMessage(sessionId, "attachment", "attachment:${item.id}")
                val current = repository.get(sessionId) ?: return@launch
                val todos = runCatching { JsonUtil.json.decodeFromString<List<TodoItem>>(current.todosJson) }
                    .getOrDefault(emptyList()).map { it.copy(status = TodoStatus.PENDING) }
                val followUp = "Please inspect the attached file ${item.name} and incorporate its contents into the task result."
                repository.addMessage(sessionId, "user", followUp)
                repository.prepareFollowUp(sessionId, followUp, JsonUtil.json.encodeToString(todos))
                AgentForegroundService.start(application, sessionId)
            } catch (e: Exception) {
                _message.value = e.message ?: "Unable to attach the selected file"
            }
        }
    }

    fun resume() {
        viewModelScope.launch {
            val current = repository.get(sessionId) ?: return@launch
            val todos = runCatching { JsonUtil.json.decodeFromString<List<TodoItem>>(current.todosJson) }
                .getOrDefault(emptyList()).map { if (it.status == TodoStatus.IN_PROGRESS) it.copy(status = TodoStatus.PENDING) else it }
            repository.resume(sessionId, JsonUtil.json.encodeToString(todos))
            AgentForegroundService.start(application, sessionId)
        }
    }

    fun clearMessage() { _message.value = null }
}
