package com.preetitoppo.filesync.domain.repository

import com.preetitoppo.filesync.domain.model.SyncFile
import com.preetitoppo.filesync.domain.model.SyncStatus
import kotlinx.coroutines.flow.Flow

/**
 * Pure Kotlin repository contract — defines what data operations are needed
 * without caring about Room, Firebase, or any Android framework.
 * Implemented in the :data layer (FileRepositoryImpl).
 */
interface FileRepository {

    /** Observe all files tracked by the sync engine */
    fun observeAllFiles(): Flow<List<SyncFile>>

    /** Observe files with a specific sync status */
    fun observeFilesByStatus(status: SyncStatus): Flow<List<SyncFile>>

    /** Get a single file by its ID */
    suspend fun getFileById(id: String): SyncFile?

    /** Persist a new file record locally */
    suspend fun insertFile(file: SyncFile)

    /** Update an existing file record */
    suspend fun updateFile(file: SyncFile)

    /** Remove a file record (does NOT delete remote copy) */
    suspend fun deleteFile(fileId: String)

    /** Enqueue a file for upload — persisted to the sync queue in Room */
    suspend fun enqueueUpload(fileId: String)

    /** Enqueue a file for download */
    suspend fun enqueueDownload(fileId: String)

    /**
     * Fetch the remote version of a file from Firestore.
     * Returns null if the file doesn't exist remotely yet.
     */
    suspend fun fetchRemoteFile(fileId: String): SyncFile?

    /**
     * Upload a file to Firebase Storage in 4MB chunks.
     * Calls [onProgress] after each successful chunk.
     */
    suspend fun uploadFileChunked(
        file: SyncFile,
        onProgress: (uploadedChunks: Int, totalChunks: Int) -> Unit
    )

    /**
     * Download a file from Firebase Storage, writing chunks to local disk.
     */
    suspend fun downloadFile(
        file: SyncFile,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    )

    /** Commit the final vector clock + metadata to Firestore after upload */
    suspend fun commitRemoteMetadata(file: SyncFile)

    /** Get pending items from the Room-persisted sync queue */
    suspend fun getPendingSyncItems(): List<String>

    /** Mark a sync queue item as completed */
    suspend fun markSyncComplete(fileId: String)

    /** Mark a sync queue item as failed with an error message */
    suspend fun markSyncFailed(fileId: String, error: String)
}
