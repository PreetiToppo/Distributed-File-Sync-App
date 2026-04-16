package com.preetitoppo.filesync.ui.filebrowser

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.preetitoppo.filesync.domain.model.SyncFile
import com.preetitoppo.filesync.domain.model.SyncStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    viewModel: FileBrowserViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use { c ->
                val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                c.moveToFirst()
                val name = c.getString(nameIdx) ?: "unknown"
                val size = c.getLong(sizeIdx)
                val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val bytes = context.contentResolver.openInputStream(uri)?.readBytes() ?: return@use

                viewModel.onEvent(
                    FileBrowserEvent.AddFile(
                        localPath = uri.toString(),
                        name = name,
                        sizeBytes = size,
                        mimeType = mimeType,
                        fileBytes = bytes
                    )
                )
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("File Sync", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { viewModel.onEvent(FileBrowserEvent.TriggerSync) }) {
                        Icon(Icons.Default.Sync, contentDescription = "Sync now")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { filePickerLauncher.launch("*/*") }
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add file")
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                uiState.isLoading && uiState.files.isEmpty() -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                uiState.files.isEmpty() -> {
                    EmptyState(modifier = Modifier.align(Alignment.Center))
                }
                else -> {
                    FileList(
                        files = uiState.files,
                        uploadProgress = uiState.uploadProgress
                    )
                }
            }

            // Conflict dialog
            uiState.conflict?.let { conflict ->
                ConflictResolutionDialog(
                    localFile = conflict.localFile,
                    remoteFile = conflict.remoteFile,
                    onKeepLocal = {
                        viewModel.onEvent(
                            FileBrowserEvent.ResolveConflict(
                                chosen = conflict.localFile,
                                discarded = conflict.remoteFile
                            )
                        )
                    },
                    onKeepRemote = {
                        viewModel.onEvent(
                            FileBrowserEvent.ResolveConflict(
                                chosen = conflict.remoteFile,
                                discarded = conflict.localFile
                            )
                        )
                    }
                )
            }

            // Error snackbar
            uiState.error?.let { error ->
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.onEvent(FileBrowserEvent.DismissError) }) {
                            Text("Dismiss")
                        }
                    }
                ) {
                    Text(error)
                }
            }
        }
    }
}

@Composable
private fun FileList(
    files: List<SyncFile>,
    uploadProgress: Map<String, Float>
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(files, key = { it.id }) { file ->
            FileCard(
                file = file,
                uploadProgress = uploadProgress[file.id]
            )
        }
    }
}

@Composable
private fun FileCard(
    file: SyncFile,
    uploadProgress: Float?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = file.mimeType.toFileIcon(),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = file.sizeBytes.toReadableSize(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                SyncStatusChip(status = file.syncStatus)
            }

            // Upload progress bar
            AnimatedVisibility(
                visible = file.syncStatus == SyncStatus.UPLOADING && uploadProgress != null,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column {
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { uploadProgress ?: 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = "Uploading chunk ${file.uploadedChunks}/${file.chunkCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncStatusChip(status: SyncStatus) {
    val (label, color) = when (status) {
        SyncStatus.SYNCED -> "Synced" to MaterialTheme.colorScheme.tertiary
        SyncStatus.PENDING_UPLOAD -> "Pending" to MaterialTheme.colorScheme.secondary
        SyncStatus.PENDING_DOWNLOAD -> "Downloading" to MaterialTheme.colorScheme.secondary
        SyncStatus.UPLOADING -> "Uploading" to MaterialTheme.colorScheme.primary
        SyncStatus.DOWNLOADING -> "Downloading" to MaterialTheme.colorScheme.primary
        SyncStatus.CONFLICT -> "Conflict" to MaterialTheme.colorScheme.error
        SyncStatus.ERROR -> "Error" to MaterialTheme.colorScheme.error
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.12f)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ConflictResolutionDialog(
    localFile: SyncFile,
    remoteFile: SyncFile,
    onKeepLocal: () -> Unit,
    onKeepRemote: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Default.Warning, contentDescription = null) },
        title = { Text("Sync Conflict Detected") },
        text = {
            Column {
                Text(
                    "Both local and remote versions of \"${localFile.name}\" were edited concurrently. " +
                    "Vector clock comparison shows neither version dominates — please choose which to keep.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                ConflictVersionRow(label = "Local", file = localFile)
                Spacer(modifier = Modifier.height(6.dp))
                ConflictVersionRow(label = "Remote", file = remoteFile)
            }
        },
        confirmButton = {
            TextButton(onClick = onKeepLocal) { Text("Keep Local") }
        },
        dismissButton = {
            TextButton(onClick = onKeepRemote) { Text("Keep Remote") }
        }
    )
}

@Composable
private fun ConflictVersionRow(label: String, file: SyncFile) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp)
            )
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
        Text(
            file.sizeBytes.toReadableSize(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.CloudUpload,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            "No files synced yet",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            "Tap + to add your first file",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

// ---- Extension helpers ----

private fun Long.toReadableSize(): String = when {
    this < 1024 -> "$this B"
    this < 1024 * 1024 -> "${this / 1024} KB"
    this < 1024 * 1024 * 1024 -> "${this / (1024 * 1024)} MB"
    else -> "${this / (1024 * 1024 * 1024)} GB"
}

private fun String.toFileIcon() = when {
    startsWith("image/") -> Icons.Default.Image
    startsWith("video/") -> Icons.Default.VideoFile
    startsWith("audio/") -> Icons.Default.AudioFile
    contains("pdf") -> Icons.Default.PictureAsPdf
    else -> Icons.Default.InsertDriveFile
}
