package com.alphaai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alphaai.app.data.datastore.AppPreferences
import com.alphaai.app.data.local.SessionEntity
import com.alphaai.app.data.repository.SessionRepository
import com.alphaai.app.domain.model.ModelPreference
import com.alphaai.app.domain.model.SandboxProfile
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: SessionRepository,
    private val preferences: AppPreferences
) : ViewModel() {
    val sessions: StateFlow<List<SessionEntity>> = repository.allSessions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val profile: StateFlow<SandboxProfile> = preferences.defaultProfile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SandboxProfile.REMOTE)
    val model: StateFlow<ModelPreference> = preferences.defaultModel.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelPreference.AUTO)

    fun createTask(task: String, profile: SandboxProfile, model: ModelPreference, onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = repository.create(task, profile, model)
            repository.addMessage(id, "user", task.trim())
            onCreated(id)
        }
    }
}
