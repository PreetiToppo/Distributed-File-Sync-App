package com.preetitoppo.filesync.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.preetitoppo.filesync.domain.model.SyncStatus

/**
 * Room entity for persisting file metadata.
 * vectorClockJson stores the serialized VectorClock map.
 */
@Entity(tableName = "sync_files")
data class SyncFileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val localPath: String,
    val remotePath: String,
    val sizeBytes: Long,
    val mimeType: String,
    val checksum: String,
    val vectorClockJson: String,     // JSON: { "deviceA": 1, "deviceB": 2 }
    val syncStatus: SyncStatus,
    val lastModifiedAt: Long,
    val chunkCount: Int,
    val uploadedChunks: Int = 0
)

/**
 * Room entity for the sync queue.
 * Persisted to disk so WorkManager can pick it up after process death or reboot.
 *
 * retryCount + lastError enable exponential backoff and debugging.
 */
@Entity(tableName = "sync_queue")
data class SyncQueueEntity(
    @PrimaryKey val fileId: String,
    val operation: SyncOperation,   // UPLOAD or DOWNLOAD
    val enqueuedAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val lastError: String? = null,
    val isCompleted: Boolean = false
)

enum class SyncOperation { UPLOAD, DOWNLOAD }
