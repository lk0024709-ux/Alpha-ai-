package com.alphaai.app.data.agent

import com.alphaai.app.domain.model.AgentEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Buffered in-process event bus; durable replay records are written by AgentLoop to Room. */
@Singleton
class AgentEventBus @Inject constructor() {
    private val mutable = MutableSharedFlow<AgentEvent>(replay = 32, extraBufferCapacity = 256)
    val events: SharedFlow<AgentEvent> = mutable
    suspend fun publish(event: AgentEvent) { mutable.emit(event) }
}
