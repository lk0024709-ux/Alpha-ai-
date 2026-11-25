package com.alphaai.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.alphaai.app.service.AgentForegroundService
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alphaai.app.domain.model.Examples
import com.alphaai.app.domain.model.ModelPreference
import com.alphaai.app.domain.model.SandboxProfile
import com.alphaai.app.domain.model.SessionStatus
import com.alphaai.app.ui.viewmodel.HomeViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenRun: (Long) -> Unit,
    onSettings: () -> Unit,
    onSessions: () -> Unit,
    onArtifacts: () -> Unit,
    vm: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val defaultProfile by vm.profile.collectAsStateWithLifecycle()
    val defaultModel by vm.model.collectAsStateWithLifecycle()
    var task by remember { mutableStateOf("") }
    var profile by remember(defaultProfile) { mutableStateOf(defaultProfile) }
    var model by remember(defaultModel) { mutableStateOf(defaultModel) }
    var exampleMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var profileMenu by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Alpha AI", fontWeight = FontWeight.Bold) },
            actions = {
                IconButton(onClick = onArtifacts) { Icon(Icons.Default.FolderOpen, "Artifacts") }
                IconButton(onClick = onSessions) { Icon(Icons.Default.History, "Sessions") }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
            }
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Spacer(Modifier.height(6.dp))
                Text("What should I build?", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text("Describe a task. Alpha plans, executes tools, and saves the results privately on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                ElevatedCard(shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = task,
                            onValueChange = { task = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 4,
                            placeholder = { Text("Create a report, analyze data, build a small app…") },
                            shape = RoundedCornerShape(16.dp)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                AssistChip(onClick = { modelMenu = true }, label = { Text("Model · ${model.name}") })
                                DropdownMenu(modelMenu, onDismissRequest = { modelMenu = false }) {
                                    ModelPreference.entries.forEach { choice -> DropdownMenuItem(text = { Text(choice.name) }, onClick = { model = choice; modelMenu = false }) }
                                }
                            }
                            Column {
                                AssistChip(onClick = { profileMenu = true }, label = { Text("${if (profile == SandboxProfile.REMOTE) "Remote" else "Light"} sandbox") })
                                DropdownMenu(profileMenu, onDismissRequest = { profileMenu = false }) {
                                    SandboxProfile.entries.forEach { choice -> DropdownMenuItem(text = { Text(choice.name) }, onClick = { profile = choice; profileMenu = false }) }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Button(
                                onClick = { if (task.isNotBlank()) vm.createTask(task, profile, model) { id -> AgentForegroundService.start(context, id); onOpenRun(id) } },
                                enabled = task.isNotBlank(),
                                shape = RoundedCornerShape(14.dp)
                            ) { Icon(Icons.AutoMirrored.Filled.Send, null); Text(" Run") }
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Text("Try an example", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    AssistChip(onClick = { exampleMenu = true }, label = { Text("Browse") })
                    DropdownMenu(expanded = exampleMenu, onDismissRequest = { exampleMenu = false }) {
                        Examples.all.forEach { ex -> DropdownMenuItem(
                            text = { Column { Text(ex.title, fontWeight = FontWeight.Medium); Text(ex.prompt.take(80), style = MaterialTheme.typography.bodySmall) } },
                            onClick = { task = ex.prompt; exampleMenu = false }
                        ) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Examples.all.take(3).forEach { ex ->
                        Card(Modifier.weight(1f).clickable { task = ex.prompt }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(ex.icon, color = MaterialTheme.colorScheme.primary)
                                Text(ex.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Recent runs", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    AssistChip(onClick = onSessions, label = { Text("All sessions") })
                }
            }
            if (sessions.isEmpty()) {
                item { Text("Your runs will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 22.dp)) }
            } else items(sessions.take(8), key = { it.id }) { session ->
                Card(Modifier.fillMaxWidth().clickable { onOpenRun(session.id) }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(session.title, fontWeight = FontWeight.SemiBold)
                            Text(session.status.lowercase(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(if (session.status == SessionStatus.RUNNING.name) "● Live" else "Open", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SelectorChip(label: String, menu: @Composable (Boolean) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    AssistChip(onClick = { expanded = true }, label = { Text(label) })
    menu(expanded)
}
