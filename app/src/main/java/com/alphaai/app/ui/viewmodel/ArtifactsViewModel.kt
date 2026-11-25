package com.alphaai.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alphaai.app.data.local.ArtifactEntity
import com.alphaai.app.data.repository.ArtifactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class ArtifactsViewModel @Inject constructor(private val repository: ArtifactRepository) : ViewModel() {
    val artifacts: StateFlow<List<ArtifactEntity>> = repository.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun read(item: ArtifactEntity, onLoaded: (String) -> Unit) {
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                runCatching { repository.fileFor(item).readText(Charsets.UTF_8).take(200_000) }.getOrElse { "Unable to preview: ${it.message}" }
            }
            onLoaded(content)
        }
    }
    fun file(item: ArtifactEntity) = repository.fileFor(item)
}
