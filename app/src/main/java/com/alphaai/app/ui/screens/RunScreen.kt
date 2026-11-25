package com.alphaai.app.ui.screens

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alphaai.app.data.local.ArtifactEntity
import com.alphaai.app.data.local.MessageEntity
import com.alphaai.app.data.local.ToolCallEntity
import com.alphaai.app.domain.model.AgentEvent
import com.alphaai.app.domain.model.TodoItem
import com.alphaai.app.domain.model.SessionStatus
import com.alphaai.app.util.JsonUtil
import kotlinx.serialization.decodeFromString
import com.alphaai.app.ui.viewmodel.RunViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunScreen(
    onBack: () -> Unit,
    onArtifacts: () -> Unit,
    vm: RunViewModel = hiltViewModel()
) {
    val session by vm.session.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val toolCalls by vm.toolCalls.collectAsStateWithLifecycle()
    val artifacts by vm.artifacts.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val viewModelMessage by vm.message.collectAsStateWithLifecycle()
    var composer by remember { mutableStateOf("") }
    var showSandboxSheet by remember { mutableStateOf(false) }
    var speechError by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val running = session?.status == SessionStatus.RUNNING.name
    val usage = events.filterIsInstance<AgentEvent.UsageUpdated>().lastOrNull()
    val totalTokens = usage?.let { it.totalPromptTokens + it.totalCompletionTokens } ?: ((session?.totalPromptTokens ?: 0) + (session?.totalCompletionTokens ?: 0))
    val totalCost = usage?.estCostUsd ?: session?.totalCostUsd ?: 0.0
    val lastThoughtDone = events.indexOfLast { it is AgentEvent.ThoughtCompleted }
    val liveThought = events.drop(lastThoughtDone + 1).filterIsInstance<AgentEvent.ThoughtDelta>().joinToString("") { it.text }
    val todos = remember(session?.todosJson) {
        runCatching { JsonUtil.json.decodeFromString<List<TodoItem>>(session?.todosJson ?: "[]") }.getOrDefault(emptyList())
    }
    val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::addAttachment) }
    val speechRecognizer = remember { if (SpeechRecognizer.isRecognitionAvailable(context)) SpeechRecognizer.createSpeechRecognizer(context) else null }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            runCatching { speechRecognizer?.startListening(intent) }.onFailure { speechError = it.message ?: "Speech recognition unavailable" }
        } else speechError = "Microphone permission is required for voice input"
    }
    DisposableEffect(speechRecognizer) {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { speechError = null }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) { speechError = "Speech recognition error ($error)" }
            override fun onResults(results: Bundle?) {
                val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!spoken.isNullOrBlank()) composer = listOf(composer, spoken).filter { it.isNotBlank() }.joinToString(" ")
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        onDispose { speechRecognizer?.destroy() }
    }

    if (showSandboxSheet) {
        ModalBottomSheet(onDismissRequest = { showSandboxSheet = false }) {
            SandboxActivitySheet(profile = session?.profile ?: "REMOTE", calls = toolCalls, artifacts = artifacts)
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showSandboxSheet = true }, containerColor = MaterialTheme.colorScheme.tertiaryContainer) {
                Icon(Icons.Default.Terminal, contentDescription = "Sandbox activity")
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(session?.title ?: "Agent run", maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(session?.status?.lowercase() ?: "loading", style = MaterialTheme.typography.labelSmall, color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("$totalTokens tokens · \$${"%.4f".format(totalCost)} est.", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = onArtifacts) { Icon(Icons.Default.FolderOpen, "Artifacts") }
                    if (running) IconButton(onClick = vm::stop) { Icon(Icons.Default.Stop, "Stop") }
                }
            )
        },
        bottomBar = {
            Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                speechError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { attachmentPicker.launch(arrayOf("image/*", "application/pdf")) }, enabled = !running) { Icon(Icons.Default.AttachFile, "Attach image or PDF") }
                    IconButton(onClick = {
                        if (SpeechRecognizer.isRecognitionAvailable(context)) micPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                        else speechError = "Speech recognition is not available on this device"
                    }, enabled = !running) { Icon(Icons.Default.Mic, "Dictate") }
                    OutlinedTextField(
                        value = composer, onValueChange = { composer = it }, modifier = Modifier.weight(1f),
                        placeholder = { Text("Add a follow-up…") }, singleLine = true, shape = RoundedCornerShape(18.dp), enabled = !running
                    )
                    IconButton(onClick = { vm.sendFollowUp(composer); composer = "" }, enabled = composer.isNotBlank() && !running) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            reverseLayout = false
        ) {
            if (viewModelMessage != null) item { Text(viewModelMessage ?: "", color = MaterialTheme.colorScheme.error) }
            if (session?.status in listOf(SessionStatus.INTERRUPTED.name, SessionStatus.STOPPED.name, SessionStatus.FAILED.name)) {
                item { Button(onClick = vm::resume, modifier = Modifier.fillMaxWidth()) { Text("Resume this run") } }
            }
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Task", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(session?.task ?: "", style = MaterialTheme.typography.bodyMedium)
                        if (session?.profile == "LIGHT") AssistChip(onClick = {}, label = { Text("LIGHT MODE · on-device limits apply") })
                    }
                }
            }
            if (todos.isNotEmpty()) item(key = "plan") { TodoPlanCard(todos) }
            val visible = messages.filter {
                it.role in setOf("user", "assistant", "thinking", "tool", "attachment") && it.content.isNotBlank() &&
                    !(it.role in setOf("assistant", "thinking") && it.content.trimStart().startsWith("{"))
            }
            items(visible, key = { "m${it.id}" }) { message -> MessageBubble(message) }
            if (liveThought.isNotBlank() && !liveThought.trimStart().startsWith("{")) item(key = "live-thinking") { LiveThoughtCard(liveThought) }
            items(toolCalls, key = { "t${it.callId}" }) { call -> ToolCallCard(call) }
            val failed = events.filterIsInstance<AgentEvent.Failed>().lastOrNull()
            if (failed != null) item { Text(failed.reason, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
            if (running && messages.none { it.role == "assistant" || it.role == "thinking" }) {
                item { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { CircularProgressIndicator(Modifier.height(20.dp)); Text("Planning your task…") } }
            }
            item { Spacer(Modifier.height(10.dp)) }
        }
    }
}

@Composable
private fun SandboxActivitySheet(profile: String, calls: List<ToolCallEntity>, artifacts: List<ArtifactEntity>) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Sandbox activity", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(if (profile == "LIGHT") "LIGHT MODE · scoped on-device files, no shell/browser" else "REMOTE · ephemeral E2B environment", color = MaterialTheme.colorScheme.primary)
        Text("Files and tool results", style = MaterialTheme.typography.titleMedium)
        if (artifacts.isEmpty()) Text("No imported artifacts yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        artifacts.forEach { Text("📄 ${it.name} · ${it.sizeBytes} bytes", style = MaterialTheme.typography.bodySmall) }
        if (calls.isEmpty()) Text("No tool calls yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        calls.takeLast(12).forEach { call ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text("${call.status} · ${call.tool}", fontWeight = FontWeight.Medium)
                    Text(call.argsJson.take(300), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    call.resultJson?.let { Text(it.take(600), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun TodoPlanCard(todos: List<TodoItem>) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Plan · ${todos.count { it.status.name == "DONE" }}/${todos.size}", fontWeight = FontWeight.SemiBold)
            todos.forEach { item ->
                val icon = when (item.status.name) { "DONE" -> "✓"; "IN_PROGRESS" -> "◉"; "FAILED" -> "!"; else -> "○" }
                Text("$icon  ${item.id}. ${item.title}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun LiveThoughtCard(text: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Thinking · live", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                CircularProgressIndicator(Modifier.height(16.dp), strokeWidth = 2.dp)
            }
            Text(text.take(12_000), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MessageBubble(message: MessageEntity) {
    var expanded by remember(message.id) { mutableStateOf(message.role != "thinking") }
    val isUser = message.role == "user"
    val title = when (message.role) {
        "user" -> "You"
        "assistant" -> "Alpha AI"
        "thinking" -> "Thinking"
        "attachment" -> "Attachment"
        else -> "Tool output"
    }
    Card(
        Modifier.fillMaxWidth(if (isUser) .88f else 1f),
        colors = CardDefaults.cardColors(containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(if (isUser) 22.dp else 16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                if (message.role == "thinking") TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide" else "Show") }
            }
            if (expanded) Text(
                if (message.role == "attachment") "Image/PDF attached. The agent can send it to Gemini for analysis." else message.content,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun ToolCallCard(call: ToolCallEntity) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(call.tool, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                when (call.status) {
                    "RUNNING" -> CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                    "ERROR" -> Text("Error", color = MaterialTheme.colorScheme.error)
                    else -> Text("Done", color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(call.argsJson.take(700), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            call.resultJson?.let { Text(it.take(2_000), style = MaterialTheme.typography.bodySmall) }
        }
    }
}
