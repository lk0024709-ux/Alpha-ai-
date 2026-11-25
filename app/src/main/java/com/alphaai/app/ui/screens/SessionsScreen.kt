package com.alphaai.app.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alphaai.app.ui.viewmodel.SessionsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, vm: SessionsViewModel = hiltViewModel()) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val query by vm.searchQuery.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var deleteId by remember { mutableLongStateOf(-1L) }
    if (deleteId > 0) AlertDialog(
        onDismissRequest = { deleteId = -1 },
        title = { Text("Delete session?") },
        text = { Text("This removes its messages and artifact records from this device.") },
        confirmButton = { TextButton(onClick = { vm.delete(deleteId); deleteId = -1 }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { deleteId = -1 }) { Text("Cancel") } }
    )
    Scaffold(topBar = { TopAppBar(title = { Text("Sessions") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(query, vm::search, Modifier.fillMaxWidth(), label = { Text("Search sessions") }, singleLine = true)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                if (sessions.isEmpty()) item { Text("No matching sessions yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
                items(sessions, key = { it.id }) { item ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(item.id) }) {
                        Row(Modifier.padding(14.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium)
                                Text("${item.status.lowercase()} · ${item.profile.lowercase()} · \$${"%.4f".format(item.totalCostUsd)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    val messages = vm.messages(item.id)
                                    val markdown = buildString {
                                        append("# ${item.title}\n\n${item.task}\n\n")
                                        messages.filter { it.role in setOf("assistant", "user") }.forEach { append("## ${it.role}\n\n${it.content}\n\n") }
                                    }
                                    val send = Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_TEXT, markdown)
                                    context.startActivity(Intent.createChooser(send, "Share session"))
                                }
                            }) { Icon(Icons.Default.Share, "Share as Markdown") }
                            IconButton(onClick = { deleteId = item.id }) { Icon(Icons.Default.Delete, "Delete") }
                        }
                    }
                }
            }
        }
    }
}
