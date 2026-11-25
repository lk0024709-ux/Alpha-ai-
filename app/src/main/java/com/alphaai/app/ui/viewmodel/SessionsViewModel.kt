package com.alphaai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alphaai.app.data.local.SessionEntity
import com.alphaai.app.data.repository.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class SessionsViewModel @Inject constructor(private val repository: SessionRepository) : ViewModel() {
    private val query = MutableStateFlow("")
    val searchQuery: StateFlow<String> = query.asStateFlow()
    val sessions: StateFlow<List<SessionEntity>> = query.flatMapLatest(repository::observeSearch)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun search(value: String) { query.value = value }
    fun delete(id: Long) = viewModelScope.launch { repository.delete(id) }
    suspend fun messages(id: Long) = repository.messagesFor(id)
}
