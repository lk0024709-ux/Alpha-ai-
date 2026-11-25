package com.alphaai.app.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.alphaai.app.data.local.ArtifactEntity
import com.alphaai.app.ui.viewmodel.ArtifactsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtifactsScreen(onBack: () -> Unit, vm: ArtifactsViewModel = hiltViewModel()) {
    val artifacts by vm.artifacts.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selected by remember { mutableStateOf<ArtifactEntity?>(null) }
    var preview by remember { mutableStateOf("") }
    var exportTarget by remember { mutableStateOf<ArtifactEntity?>(null) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val target = exportTarget
        if (uri != null && target != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out -> vm.file(target).inputStream().use { it.copyTo(out) } }
            }
        }
        exportTarget = null
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Artifacts") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (artifacts.isEmpty()) Text("Artifacts created by your agent will appear here. They stay in app-private storage until you share or export them.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(if (selected != null) 0.42f else 1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(artifacts, key = { it.id }) { item ->
                    Card(Modifier.fillMaxWidth().clickable {
                        selected = item
                        if (item.mimeType.startsWith("image/")) preview = ""
                        else vm.read(item) { preview = it }
                    }) {
                        Row(Modifier.padding(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.titleMedium)
                                Text("${item.mimeType} · ${item.sizeBytes} bytes", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { exportTarget = item; exporter.launch(item.name) }) { Icon(Icons.Default.Download, "Export via system file picker") }
                            IconButton(onClick = {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", vm.file(item))
                                val send = Intent(Intent.ACTION_SEND).setType(item.mimeType).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                context.startActivity(Intent.createChooser(send, "Share artifact"))
                            }) { Icon(Icons.Default.Share, "Share") }
                        }
                    }
                }
            }
            selected?.let { item ->
                Text("Preview · ${item.name}", style = MaterialTheme.typography.titleMedium)
                Card(Modifier.fillMaxWidth().weight(0.58f)) {
                    when {
                        item.mimeType.startsWith("image/") -> AsyncImage(model = vm.file(item), contentDescription = item.name, modifier = Modifier.fillMaxSize().padding(8.dp))
                        item.mimeType == "text/html" -> SafeHtmlPreview(preview)
                        item.mimeType == "text/csv" -> CsvPreview(preview)
                        else -> PreviewText(preview)
                    }
                }
            }
        }
    }
}

@Composable
private fun CsvPreview(csv: String) {
    val rows = remember(csv) { parseCsv(csv).take(80) }
    Column(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState()).padding(8.dp)) {
        rows.forEachIndexed { rowIndex, row ->
            Row {
                row.forEach { cell ->
                    Text(
                        cell.take(120),
                        modifier = Modifier.width(132.dp).padding(6.dp),
                        style = if (rowIndex == 0) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                        color = if (rowIndex == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private fun parseCsv(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0
    while (i < text.length && rows.size < 500) {
        val ch = text[i]
        when {
            ch == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
            ch == '"' -> quoted = !quoted
            ch == ',' && !quoted -> { row += cell.toString(); cell.setLength(0) }
            (ch == '\n' || ch == '\r') && !quoted -> {
                if (ch == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                row += cell.toString(); cell.setLength(0)
                rows += row; row = mutableListOf()
            }
            else -> cell.append(ch)
        }
        i++
    }
    if (cell.isNotEmpty() || row.isNotEmpty()) { row += cell.toString(); rows += row }
    return rows
}

@Composable
private fun SafeHtmlPreview(html: String) {
    // Render as escaped text by default; executing agent HTML is never run in a WebView.
    PreviewText(html)
}

@Composable
private fun PreviewText(text: String) {
    // Isolated helper keeps HTML/script content inert.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(text.take(20_000), style = MaterialTheme.typography.bodySmall)
    }
}
