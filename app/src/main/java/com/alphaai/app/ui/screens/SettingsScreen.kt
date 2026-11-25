package com.alphaai.app.ui.screens

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alphaai.app.data.security.SecureKeyStore
import com.alphaai.app.domain.model.ModelPreference
import com.alphaai.app.domain.model.Provider
import com.alphaai.app.domain.model.SandboxProfile
import com.alphaai.app.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val prior = window?.let { it.attributes.flags.and(WindowManager.LayoutParams.FLAG_SECURE) != 0 } ?: false
        window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (!prior) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    val profile by vm.profile.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val cap by vm.costCap.collectAsStateWithLifecycle()
    val theme by vm.themeMode.collectAsStateWithLifecycle()
    val allowlist by vm.allowlist.collectAsStateWithLifecycle()
    val keyPresence by vm.keyPresence.collectAsStateWithLifecycle()
    val results by vm.testResults.collectAsStateWithLifecycle()
    val sandboxTestResult by vm.sandboxTestResult.collectAsStateWithLifecycle()
    var capText by remember(cap) { mutableStateOf("%.2f".format(cap)) }
    var hostsText by remember(allowlist) { mutableStateOf(allowlist.joinToString(", ")) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Bring your own keys", style = MaterialTheme.typography.headlineSmall)
            Text("Keys are encrypted locally with Android Keystore. They are only attached to provider requests and never synced.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            ApiKeyEntry("Groq", SecureKeyStore.Secret.GROQ, keyPresence[SecureKeyStore.Secret.GROQ] == true, results[Provider.GROQ], "llama-3.3-70b-versatile", onSave = { secret, value -> vm.saveKey(secret, value) }, onTest = { vm.test(Provider.GROQ, "llama-3.3-70b-versatile") })
            ApiKeyEntry("Google Gemini", SecureKeyStore.Secret.GEMINI, keyPresence[SecureKeyStore.Secret.GEMINI] == true, results[Provider.GEMINI], "gemini-2.0-flash", onSave = { secret, value -> vm.saveKey(secret, value) }, onTest = { vm.test(Provider.GEMINI, "gemini-2.0-flash") })
            ApiKeyEntry("Hugging Face", SecureKeyStore.Secret.HF, keyPresence[SecureKeyStore.Secret.HF] == true, results[Provider.HF], "Qwen/Qwen2.5-Coder-32B-Instruct", onSave = { secret, value -> vm.saveKey(secret, value) }, onTest = { vm.test(Provider.HF, "Qwen/Qwen2.5-Coder-32B-Instruct") })
            ApiKeyEntry("E2B sandbox", SecureKeyStore.Secret.E2B, keyPresence[SecureKeyStore.Secret.E2B] == true, sandboxTestResult, "E2B", onSave = { secret, value -> vm.saveKey(secret, value) }, onTest = { vm.testSandbox() })
            ApiKeyEntry("Serper (optional)", SecureKeyStore.Secret.SERPER, keyPresence[SecureKeyStore.Secret.SERPER] == true, null, "", onSave = { secret, value -> vm.saveKey(secret, value) }, onTest = { })

            Text("Defaults", style = MaterialTheme.typography.titleLarge)
            EnumDropdown("Default model", model.name, ModelPreference.entries.map { it.name }) { vm.setModel(ModelPreference.valueOf(it)) }
            EnumDropdown("Sandbox profile", profile.name, SandboxProfile.entries.map { it.name }) { vm.setProfile(SandboxProfile.valueOf(it)) }
            EnumDropdown("Theme", theme, listOf("SYSTEM", "DARK", "LIGHT"), vm::setThemeMode)
            Text("Per-task cost cap · \$${capText}")
            Slider(value = (capText.toFloatOrNull()?.coerceIn(.01f, .5f) ?: .5f), onValueChange = { capText = "%.2f".format(it) }, valueRange = .01f..0.5f, onValueChangeFinished = { vm.setCostCap(capText.toDoubleOrNull() ?: .5) })

            Text("HTTP allowlist", style = MaterialTheme.typography.titleLarge)
            Text("Only these hostnames can be used by the http_request tool in Light mode. SSRF protections apply in all profiles.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(hostsText, { hostsText = it }, Modifier.fillMaxWidth(), label = { Text("example.com, api.example.org") })
            Button(onClick = { vm.setAllowlist(hostsText.split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()) }) { Text("Save allowlist") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ApiKeyEntry(
    title: String,
    secret: SecureKeyStore.Secret,
    isSet: Boolean,
    result: String?,
    testModel: String,
    onSave: (SecureKeyStore.Secret, String) -> Unit,
    onTest: () -> Unit
) {
    var input by remember { mutableStateOf("") } // deliberately not rememberSaveable: never put secrets in saved state
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(if (isSet) "Key saved · ••••" else "No key saved", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (isSet) "Enter a replacement key" else "API key") },
                visualTransformation = PasswordVisualTransformation(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(secret, input); input = "" }, enabled = input.isNotBlank()) { Text("Save") }
                OutlinedButton(onClick = onTest, enabled = testModel.isNotBlank()) { Text("Test") }
                if (isSet) OutlinedButton(onClick = { onSave(secret, "") }) { Text("Clear") }
            }
            if (result != null) Text(result, color = if (result.startsWith("Connected")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnumDropdown(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(value = selected, onValueChange = {}, readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
        androidx.compose.material3.ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { onSelect(value); expanded = false }) }
        }
    }
}
