package com.alphaai.app.data.agent

import com.alphaai.app.data.datastore.AppPreferences
import com.alphaai.app.data.local.ToolCallEntity
import com.alphaai.app.data.repository.ArtifactRepository
import com.alphaai.app.data.repository.SessionRepository
import com.alphaai.app.data.repository.UsageRepository
import com.alphaai.app.data.router.CostCalculator
import com.alphaai.app.data.router.ModelRouter
import com.alphaai.app.data.sandbox.SandboxFactory
import com.alphaai.app.data.sandbox.SandboxClient
import com.alphaai.app.domain.agent.AgentToolCall
import com.alphaai.app.domain.agent.ToolOutput
import com.alphaai.app.domain.model.AgentEvent
import com.alphaai.app.domain.model.AgentState
import com.alphaai.app.domain.model.ChatMessageDto
import com.alphaai.app.domain.model.ModelPreference
import com.alphaai.app.domain.model.InlineImage
import com.alphaai.app.domain.model.SandboxProfile
import com.alphaai.app.domain.model.SessionStatus
import com.alphaai.app.domain.model.TodoItem
import com.alphaai.app.domain.model.TodoStatus
import com.alphaai.app.util.JsonUtil
import com.alphaai.app.util.SafeLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Autonomous planner/tool/observer/verifier loop with persisted checkpoints and hard limits. */
@Singleton
class AgentLoop @Inject constructor(
    private val router: ModelRouter,
    private val sessions: SessionRepository,
    private val usageRepository: UsageRepository,
    private val sandboxFactory: SandboxFactory,
    private val tools: ToolExecutor,
    private val events: AgentEventBus,
    private val artifactRepository: ArtifactRepository,
    private val preferences: AppPreferences
) {
    suspend fun run(sessionId: Long) {
        val session = sessions.get(sessionId) ?: return
        val profile = runCatching { SandboxProfile.valueOf(session.profile) }.getOrDefault(SandboxProfile.REMOTE)
        val preference = runCatching { ModelPreference.valueOf(session.modelPreference) }.getOrDefault(ModelPreference.AUTO)
        val sandbox = sandboxFactory.create(profile)
        val startedAt = session.createdAt
        var step = session.stepCount
        var todos = decodeTodos(session.todosJson)
        val repeated = mutableSetOf<String>()

        try {
            withTimeout(MAX_WALL_MS) {
                events.publish(AgentEvent.StateChanged(sessionId, AgentState.PLANNING))
                persistEvent(sessionId, AgentEvent.StateChanged(sessionId, AgentState.PLANNING))
                if (todos.isEmpty()) {
                    val planningPrompt = listOf(
                        ChatMessageDto("system", planningSystem(sandbox)),
                        ChatMessageDto("user", session.task)
                    )
                    val planAnswer = ask(sessionId, preference, planningPrompt, AgentState.PLANNING)
                    val parsed = JsonUtil.parseJsonObjectOrNull(planAnswer)
                    val rawTodos = (parsed?.get("todos") as? JsonArray) ?: JsonArray(emptyList())
                    todos = rawTodos.mapIndexedNotNull { index, el ->
                        val o = el as? JsonObject ?: return@mapIndexedNotNull null
                        val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapIndexedNotNull null
                        TodoItem(
                            id = o["id"]?.jsonPrimitive?.intOrNull ?: index + 1,
                            title = title.take(240), status = TodoStatus.PENDING
                        )
                    }.take(MAX_STEPS)
                    if (todos.isEmpty()) throw IllegalStateException("Planner returned no valid todos")
                    sessions.saveTodos(sessionId, JsonUtil.json.encodeToString(todos))
                    sessions.addMessage(sessionId, "assistant", "Plan created with ${todos.size} steps.")
                    val created = AgentEvent.PlanCreated(sessionId, todos)
                    persistEvent(sessionId, created)
                    events.publish(created)
                } else {
                    // Recovered run — any in-progress item becomes pending so it can be retried.
                    todos = todos.map { if (it.status == TodoStatus.IN_PROGRESS) it.copy(status = TodoStatus.PENDING) else it }
                    sessions.saveTodos(sessionId, JsonUtil.json.encodeToString(todos))
                    val created = AgentEvent.PlanCreated(sessionId, todos)
                    persistEvent(sessionId, created)
                    events.publish(created)
                }

                val system = executionSystem(session.task, sandbox, todos)
                while (step < MAX_STEPS) {
                    ensureBudget(sessionId)
                    AgentRunGuardrails.ensureStepAllowed(step)
                    if (System.currentTimeMillis() - startedAt >= MAX_WALL_MS) throw IllegalStateException("10-minute task time limit reached")

                    val allStepsReportedDone = todos.all { it.status == TodoStatus.DONE }
                    val todoIndex = todos.indexOfFirst { it.status != TodoStatus.DONE }.let { if (it >= 0) it else (todos.lastIndex).coerceAtLeast(0) }
                    if (!allStepsReportedDone) {
                        todos = todos.mapIndexed { i, item -> if (i == todoIndex) item.copy(status = TodoStatus.IN_PROGRESS) else item }
                        sessions.saveTodos(sessionId, JsonUtil.json.encodeToString(todos))
                    }
                    val state = if (allStepsReportedDone) AgentState.VERIFYING else AgentState.EXECUTING
                    val stateEvent = AgentEvent.StateChanged(sessionId, state)
                    persistEvent(sessionId, stateEvent)
                    events.publish(stateEvent)

                    maybeCompactMemory(sessionId, preference)
                    val verifierInstruction = if (allStepsReportedDone) "All checklist items were marked done. Verify the task's actual result against the user's request. If complete, call finish with a useful summary and artifact paths. If anything remains, use tools to fix it before finishing." else ""
                    val messages = listOf(ChatMessageDto("system", "$system\n$verifierInstruction")) + conversation(sessionId)
                    var streamedThinking = StringBuilder()
                    val raw = ask(sessionId, preference, messages, state) { delta ->
                        streamedThinking.append(delta)
                        events.publish(AgentEvent.ThoughtDelta(sessionId, delta))
                    }
                    val thought = streamedThinking.toString().ifBlank { raw }
                    if (thought.isNotBlank()) {
                        sessions.addMessage(sessionId, "thinking", thought.take(20_000))
                        val completed = AgentEvent.ThoughtCompleted(sessionId, thought.take(20_000))
                        persistEvent(sessionId, completed)
                        events.publish(completed)
                    }
                    sessions.addMessage(sessionId, "assistant", raw.take(20_000))

                    val decision = parseDecision(raw)
                    val call = decision?.call
                    if (call == null || decision == null) {
                        // Model may give plain-language observation. Feed it back and allow a retry.
                        sessions.addMessage(sessionId, "user", "Your last response was not a valid tool call. Return exactly one JSON object with a tool_call, or use finish.")
                        step++
                        sessions.addUsage(sessionId, 0, 0, 0.0, step)
                        continue
                    }
                    val signature = call.name + ":" + JsonUtil.canonicalize(JsonUtil.pretty(call.arguments))
                    AgentRunGuardrails.ensureNotRepeated(signature, repeated)

                    tools.validate(call)
                    val callJson = JsonUtil.pretty(call.arguments)
                    val stepLabel = "${todoIndex + 1}/${todos.size} ${todos[todoIndex].title}"
                    val started = AgentEvent.ToolStarted(sessionId, call.id, call.name, callJson, stepLabel)
                    persistEvent(sessionId, started)
                    events.publish(started)
                    sessions.upsertToolCall(
                        ToolCallEntity(
                            callId = call.id, sessionId = sessionId, tool = call.name,
                            argsJson = callJson, status = "RUNNING", startedAt = System.currentTimeMillis()
                        )
                    )
                    val observing = AgentEvent.StateChanged(sessionId, AgentState.OBSERVING)
                    persistEvent(sessionId, observing)
                    events.publish(observing)

                    val execution = tools.execute(call, sandbox)
                    val outputText = execution.output.text
                    sessions.finishToolCall(call.id, if (execution.output.isError) "ERROR" else "SUCCESS", outputText.take(12_000))
                    val finished = AgentEvent.ToolFinished(
                        sessionId, call.id, call.name, outputText.take(12_000), execution.output.isError,
                        artifactPath = execution.output.artifactPath
                    )
                    persistEvent(sessionId, finished)
                    events.publish(finished)
                    sessions.addMessage(sessionId, "tool", outputText.take(12_000), call.id)
                    step++
                    sessions.addUsage(sessionId, 0, 0, 0.0, step)

                    if (execution.shouldFinish) {
                        val payload = runCatching {
                            JsonUtil.json.decodeFromString(ToolExecutor.FinishPayload.serializer(), outputText)
                        }.getOrNull()
                        val summary = payload?.summary ?: outputText.take(2_000)
                        payload?.artifacts.orEmpty().take(20).forEach { path ->
                            runCatching { artifactRepository.importFromSandbox(sessionId, path, sandbox) }
                        }
                        todos = todos.map { it.copy(status = TodoStatus.DONE) }
                        sessions.saveTodos(sessionId, JsonUtil.json.encodeToString(todos))
                        sessions.complete(sessionId, summary, SessionStatus.DONE)
                        events.publish(AgentEvent.StateChanged(sessionId, AgentState.DONE))
                        val done = AgentEvent.Done(sessionId, summary)
                        persistEvent(sessionId, done)
                        events.publish(done)
                        return@withTimeout
                    }

                    // Tool errors are observable feedback; planner can recover or replan.
                    sessions.addMessage(sessionId, "user", "Tool result (${call.name}): ${outputText.take(8_000)}")
                    // The model explicitly marks a checklist item complete only after verifying the work.
                    if (execution.output.isError) {
                        todos = todos.mapIndexed { i, item -> if (i == todoIndex) item.copy(status = TodoStatus.PENDING) else item }
                        sessions.saveTodos(sessionId, JsonUtil.json.encodeToString(todos))
                    } else if (decision.stepDone && !allStepsReportedDone) {
                        todos = todos.mapIndexed { i, item -> if (i == todoIndex) item.copy(status = TodoStatus.DONE) else item }
                        sessions.saveTodos(sessionId, JsonUtil.json.encodeToString(todos))
                        val stepEvent = AgentEvent.StepFinished(sessionId, step, todos.getOrNull(todoIndex)?.id)
                        persistEvent(sessionId, stepEvent)
                        events.publish(stepEvent)
                    }
                    val currentCost = usageRepository.totalCostForSession(sessionId)
                    val totals = sessions.get(sessionId)
                    events.publish(AgentEvent.UsageUpdated(sessionId, totals?.totalPromptTokens ?: 0, totals?.totalCompletionTokens ?: 0, currentCost))
                }

                throw IllegalStateException("25-step safety limit reached before the agent verified completion")
            }
        } catch (timeout: TimeoutCancellationException) {
            val reason = "10-minute task time limit reached"
            sessions.complete(sessionId, reason, SessionStatus.FAILED)
            val failed = AgentEvent.Failed(sessionId, reason)
            persistEvent(sessionId, failed)
            events.publish(failed)
        } catch (cancel: CancellationException) {
            sessions.setStatus(sessionId, SessionStatus.INTERRUPTED)
            throw cancel
        } catch (e: Exception) {
            SafeLog.e(TAG, "agent failed: ${e.message}", e)
            sessions.complete(sessionId, e.message?.take(500), SessionStatus.FAILED)
            val failed = AgentEvent.Failed(sessionId, e.message ?: "Agent failed")
            persistEvent(sessionId, failed)
            events.publish(failed)
        } finally {
            runCatching { sandbox.close() }
        }
    }

    private suspend fun ask(
        sessionId: Long,
        preference: ModelPreference,
        messages: List<ChatMessageDto>,
        state: AgentState,
        onDelta: suspend (String) -> Unit = {}
    ): String {
        val response = router.streamChat(
            ModelRouter.RouteRequest(
                sessionId = sessionId, preference = preference, messages = messages,
                temperature = 0.2, maxTokens = if (state == AgentState.PLANNING) 2_000 else 4_000
            ), onDelta
        )
        val cost = CostCalculator.estimate(response.model, response.promptTokens, response.completionTokens)
        sessions.addTokens(sessionId, response.promptTokens, response.completionTokens, cost)
        val row = sessions.get(sessionId)
        val usageEvent = AgentEvent.UsageUpdated(sessionId, row?.totalPromptTokens ?: 0, row?.totalCompletionTokens ?: 0, usageRepository.totalCostForSession(sessionId))
        persistEvent(sessionId, usageEvent)
        events.publish(usageEvent)
        return response.content
    }

    private suspend fun ensureBudget(sessionId: Long) {
        val cost = usageRepository.totalCostForSession(sessionId)
        val cap = preferences.costCapUsd.first().coerceAtMost(MAX_COST_USD)
        try {
            AgentRunGuardrails.ensureCostAllowed(cost, cap)
        } catch (_: IllegalArgumentException) {
            throw IllegalStateException("Task cost cap (\$${"%.2f".format(cap)}) reached")
        }
    }

    private suspend fun conversation(sessionId: Long): List<ChatMessageDto> {
        val session = sessions.get(sessionId)
        val allRows = sessions.messagesFor(sessionId).filter { it.role in setOf("user", "assistant", "tool", "attachment") }
        val rows = allRows.takeLast(if (session?.memorySummary != null) 20 else 80)
        val result = mutableListOf<ChatMessageDto>()
        session?.memorySummary?.let { result += ChatMessageDto("system", "Earlier task context summary: $it") }
        for (row in rows) {
            if (row.role == "attachment") {
                val artifactId = row.content.removePrefix("attachment:").toLongOrNull() ?: continue
                val artifact = artifactRepository.get(artifactId) ?: continue
                val bytes = withContext(Dispatchers.IO) { artifactRepository.fileFor(artifact).readBytes() }
                val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                result += ChatMessageDto("user", "Attached file: ${artifact.name}", listOf(InlineImage(artifact.mimeType, encoded)))
            } else {
                val role = if (row.role == "assistant") "assistant" else "user"
                result += ChatMessageDto(role, if (row.role == "tool") "Tool output: ${row.content}" else row.content)
            }
        }
        return result
    }

    private suspend fun maybeCompactMemory(sessionId: Long, preference: ModelPreference) {
        val current = conversation(sessionId)
        val chars = current.sumOf { it.content.length }
        if (chars < 120_000) return // approximate 80% of a conservative context window
        val old = current.dropLast(20)
        val summaryPrompt = listOf(
            ChatMessageDto("system", "Summarize the following agent history in under 1500 tokens. Preserve completed work, important files/values, errors, and next actions. Output only the summary."),
            ChatMessageDto("user", old.joinToString("\n") { "${it.role}: ${it.content}" }.take(80_000))
        )
        val summary = ask(sessionId, preference, summaryPrompt, AgentState.OBSERVING).take(8_000)
        sessions.setMemorySummary(sessionId, summary)
        sessions.addMessage(sessionId, "system", "Context summary: $summary")
    }

    private suspend fun persistEvent(sessionId: Long, event: AgentEvent) {
        // Durable event stream for replay after process death. Never contains provider secrets.
        val json = when (event) {
            is AgentEvent.StateChanged -> "state:${event.state}"
            is AgentEvent.PlanCreated -> "plan:" + JsonUtil.json.encodeToString(event.todos)
            is AgentEvent.ThoughtDelta -> "thought_delta:${event.text}"
            is AgentEvent.ThoughtCompleted -> "thought:${event.text}"
            is AgentEvent.ToolStarted -> "tool_started:${event.callId}:${event.tool}:${event.argsJson}"
            is AgentEvent.ToolDelta -> "tool_delta:${event.callId}:${event.text}"
            is AgentEvent.ToolFinished -> "tool_finished:${event.callId}:${event.tool}:${event.result}"
            is AgentEvent.StepFinished -> "step:${event.step}:${event.todoId}"
            is AgentEvent.UsageUpdated -> "usage:${event.totalPromptTokens}:${event.totalCompletionTokens}:${event.estCostUsd}"
            is AgentEvent.Done -> "done:${event.summary}"
            is AgentEvent.Failed -> "failed:${event.reason}"
        }
        sessions.addMessage(sessionId, "system", "[agent-event] $json".take(20_000))
    }

    private fun decodeTodos(raw: String): List<TodoItem> = runCatching {
        JsonUtil.json.decodeFromString<List<TodoItem>>(raw)
    }.getOrDefault(emptyList())

    private data class Decision(val call: AgentToolCall, val stepDone: Boolean)

    private fun parseDecision(raw: String): Decision? {
        val root = JsonUtil.parseJsonObjectOrNull(raw) ?: return null
        val callObj = (root["tool_call"] as? JsonObject) ?: root
        val name = callObj["name"]?.jsonPrimitive?.contentOrNull ?: return null
        val args = (callObj["arguments"] as? JsonObject) ?: (callObj["args"] as? JsonObject) ?: return null
        val id = callObj["id"]?.jsonPrimitive?.contentOrNull ?: UUID.randomUUID().toString()
        val stepDone = root["step_done"]?.jsonPrimitive?.booleanOrNull ?: false
        return Decision(AgentToolCall(id = id.take(100), name = name, arguments = args), stepDone)
    }

    private fun planningSystem(sandbox: SandboxClient): String = """
You are the Planner for Alpha AI, a mobile autonomous coding agent. Create a concise, ordered plan for the user's task.
Return ONLY JSON: {"todos":[{"id":1,"title":"..."}, ...]}. Use 1 to 8 actionable steps. No markdown.
Sandbox capability: ${sandbox.capabilitySummary()}
""".trimIndent()

    private fun executionSystem(task: String, sandbox: SandboxClient, todos: List<TodoItem>): String = """
You are Alpha AI, an autonomous coding and research agent. Complete the user's task by choosing exactly ONE tool call per response.
Return ONLY JSON in this schema: {"step_done":false,"tool_call":{"id":"unique-string","name":"tool_name","arguments":{...}}}. Set step_done=true only when the current checklist item is genuinely complete; use false when you need more tool calls for it.
Call finish only after verifying the task is complete: {"step_done":true,"tool_call":{"id":"...","name":"finish","arguments":{"summary":"...","artifacts":["relative/path"]}}}.
Allowed tools and arguments:
- shell {"command":"..."}; python {"code":"..."}; node {"code":"..."}
- read_file {"path":"..."}; write_file {"path":"...","content":"..."}; list_dir {"path":"."}; patch_file {"path":"...","search":"...","replace":"..."}
- browser {"url":"https://...","actions":["click selector","fill selector :: text","wait 1000"]}
- web_search {"query":"..."}; http_request {"url":"https://...","method":"GET|POST|HEAD","body":"..."}
- finish {"summary":"...","artifacts":["...", ...]}
Never claim a tool succeeded without observing its output. Validate assumptions, create useful files as artifacts, and keep outputs concise. Use relative paths in artifacts/ where possible.
Task: $task
Sandbox: ${sandbox.capabilitySummary()}
Working directory: ${sandbox.workingDirectory()}
Plan: ${todos.joinToString("; ") { "${it.id}. ${it.title} [${it.status}]" }}
Safety: never access credentials, environment secrets, or files outside the sandbox. Do not attempt destructive system commands.
""".trimIndent()

    companion object {
        const val MAX_STEPS = AgentRunGuardrails.MAX_STEPS
        const val MAX_COST_USD = AgentRunGuardrails.MAX_COST_USD
        const val MAX_WALL_MS = AgentRunGuardrails.MAX_WALL_MS
        private const val TAG = "AgentLoop"
    }
}
