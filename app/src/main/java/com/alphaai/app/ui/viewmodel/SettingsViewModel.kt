package com.alphaai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alphaai.app.data.datastore.AppPreferences
import com.alphaai.app.data.remote.gemini.GeminiClient
import com.alphaai.app.data.remote.groq.GroqClient
import com.alphaai.app.data.remote.hf.HuggingFaceClient
import com.alphaai.app.data.security.SecureKeyStore
import com.alphaai.app.data.sandbox.RemoteSandbox
import com.alphaai.app.domain.model.ModelPreference
import com.alphaai.app.domain.model.SandboxProfile
import com.alphaai.app.domain.model.Provider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val keys: SecureKeyStore,
    private val preferences: AppPreferences,
    private val groq: GroqClient,
    private val gemini: GeminiClient,
    private val hf: HuggingFaceClient,
    private val remoteSandboxFactory: RemoteSandbox.Factory
) : ViewModel() {
    val profile = preferences.defaultProfile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SandboxProfile.REMOTE)
    val model = preferences.defaultModel.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelPreference.AUTO)
    val costCap = preferences.costCapUsd.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0.50)
    val themeMode = preferences.themeMode.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "DARK")
    val allowlist = preferences.httpAllowlist.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    private val _keyPresence = MutableStateFlow<Map<SecureKeyStore.Secret, Boolean>>(emptyMap())
    val keyPresence: StateFlow<Map<SecureKeyStore.Secret, Boolean>> = _keyPresence
    private val _testResults = MutableStateFlow<Map<Provider, String>>(emptyMap())
    val testResults: StateFlow<Map<Provider, String>> = _testResults
    private val _sandboxTestResult = MutableStateFlow<String?>(null)
    val sandboxTestResult: StateFlow<String?> = _sandboxTestResult

    init { refreshKeyPresence() }

    fun refreshKeyPresence() {
        _keyPresence.value = SecureKeyStore.Secret.entries.associateWith(keys::has)
    }

    fun saveKey(secret: SecureKeyStore.Secret, value: String) {
        if (value.isBlank()) keys.clear(secret) else keys.set(secret, value)
        refreshKeyPresence()
    }

    fun test(provider: Provider, modelId: String) {
        viewModelScope.launch {
            _testResults.value = _testResults.value + (provider to "Testing…")
            val error = runCatching {
                when (provider) {
                    Provider.GROQ -> groq.testConnection(modelId)
                    Provider.GEMINI -> gemini.testConnection(modelId)
                    Provider.HF -> hf.testConnection(modelId)
                }
            }.getOrElse { it.message ?: "Connection failed" }
            _testResults.value = _testResults.value + (provider to (error ?: "Connected"))
        }
    }

    fun testSandbox() {
        viewModelScope.launch {
            _sandboxTestResult.value = "Testing E2B sandbox…"
            val sandbox = remoteSandboxFactory.create()
            try {
                val result = sandbox.runShell("echo alphaai-e2b-ok", timeoutMs = 30_000)
                _sandboxTestResult.value = if (!result.isError && result.output.contains("alphaai-e2b-ok")) "Connected · ${result.output.trim()}" else result.output.take(300)
            } catch (e: Exception) {
                _sandboxTestResult.value = e.message?.take(300) ?: "E2B sandbox test failed"
            } finally {
                runCatching { sandbox.close() }
            }
        }
    }

    fun setProfile(value: SandboxProfile) = viewModelScope.launch { preferences.setDefaultProfile(value) }
    fun setModel(value: ModelPreference) = viewModelScope.launch { preferences.setDefaultModel(value) }
    fun setCostCap(value: Double) = viewModelScope.launch { preferences.setCostCap(value) }
    fun setThemeMode(value: String) = viewModelScope.launch { preferences.setThemeMode(value) }
    fun setAllowlist(value: Set<String>) = viewModelScope.launch { preferences.setHttpAllowlist(value) }
}
