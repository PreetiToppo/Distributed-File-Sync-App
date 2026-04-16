package com.preetitoppo.filesync.ui.filebrowser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import com.preetitoppo.filesync.domain.model.ConflictResolution
import com.preetitoppo.filesync.domain.model.SyncFile
import com.preetitoppo.filesync.domain.model.SyncStatus
import com.preetitoppo.filesync.domain.usecase.AddFileUseCase
import com.preetitoppo.filesync.domain.usecase.ObserveFilesUseCase
import com.preetitoppo.filesync.domain.usecase.ResolveConflictUseCase
import com.preetitoppo.filesync.sync.SyncWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FileBrowserUiState(
    val files: List<SyncFile> = emptyList(),
    val isLoading: Boolean = false,
    val conflict: ConflictUiState? = null,
    val error: String? = null,
    val uploadProgress: Map<String, Float> = emptyMap() // fileId → 0..1
)

data class ConflictUiState(
    val localFile: SyncFile,
    val remoteFile: SyncFile
)

sealed class FileBrowserEvent {
    data class AddFile(
        val localPath: String,
        val name: String,
        val sizeBytes: Long,
        val mimeType: String,
        val fileBytes: ByteArray
    ) : FileBrowserEvent()

    data class ResolveConflict(
        val chosen: SyncFile,
        val discarded: SyncFile
    ) : FileBrowserEvent()

    data object DismissError : FileBrowserEvent()
    data object TriggerSync : FileBrowserEvent()
}

@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    private val observeFilesUseCase: ObserveFilesUseCase,
    private val addFileUseCase: AddFileUseCase,
    private val resolveConflictUseCase: ResolveConflictUseCase,
    private val workManager: WorkManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileBrowserUiState(isLoading = true))
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    init {
        observeFiles()
    }

    private fun observeFiles() {
        viewModelScope.launch {
            observeFilesUseCase()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
                .collect { files ->
                    _uiState.update { state ->
                        state.copy(
                            files = files,
                            isLoading = false,
                            // Surface first conflict to UI
                            conflict = files
                                .firstOrNull { it.syncStatus == SyncStatus.CONFLICT }
                                ?.let { conflictFile ->
                                    // In real app, fetch remote from repository
                                    // For now just surface local
                                    null
                                }
                        )
                    }
                }
        }
    }

    fun onEvent(event: FileBrowserEvent) {
        when (event) {
            is FileBrowserEvent.AddFile -> addFile(event)
            is FileBrowserEvent.ResolveConflict -> resolveConflict(event.chosen, event.discarded)
            is FileBrowserEvent.DismissError -> _uiState.update { it.copy(error = null) }
            is FileBrowserEvent.TriggerSync -> triggerSync()
        }
    }

    private fun addFile(event: FileBrowserEvent.AddFile) {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(isLoading = true) }
                addFileUseCase(
                    localPath = event.localPath,
                    name = event.name,
                    sizeBytes = event.sizeBytes,
                    mimeType = event.mimeType,
                    fileBytes = event.fileBytes
                )
                // Dispatch upload to WorkManager
                val request = SyncWorker.buildUploadRequest(event.name)
                workManager.enqueue(request)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    private fun resolveConflict(chosen: SyncFile, discarded: SyncFile) {
        viewModelScope.launch {
            try {
                resolveConflictUseCase(chosen, discarded)
                _uiState.update { it.copy(conflict = null) }

                // Re-enqueue upload with resolved version
                val request = SyncWorker.buildUploadRequest(chosen.id)
                workManager.enqueue(request)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    private fun triggerSync() {
        val request = SyncWorker.buildUploadRequest("periodic")
        workManager.enqueue(request)
    }
}
