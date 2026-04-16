package com.preetitoppo.filesync.domain.usecase

import com.preetitoppo.filesync.domain.model.ClockRelation
import com.preetitoppo.filesync.domain.model.ConflictResolution
import com.preetitoppo.filesync.domain.model.SyncFile
import com.preetitoppo.filesync.domain.model.SyncStatus
import com.preetitoppo.filesync.domain.model.VectorClock
import com.preetitoppo.filesync.domain.repository.FileRepository
import com.preetitoppo.filesync.util.ChecksumUtil
import com.preetitoppo.filesync.util.DeviceIdProvider
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

/**
 * Observes all tracked files. Used by FileBrowserViewModel to drive UI.
 */
class ObserveFilesUseCase @Inject constructor(
    private val repository: FileRepository
) {
    operator fun invoke(): Flow<List<SyncFile>> = repository.observeAllFiles()
}

/**
 * Registers a new local file into the sync system and enqueues its upload.
 * Computes SHA-256 checksum and initialises the vector clock for this device.
 */
class AddFileUseCase @Inject constructor(
    private val repository: FileRepository,
    private val deviceIdProvider: DeviceIdProvider
) {
    suspend operator fun invoke(
        localPath: String,
        name: String,
        sizeBytes: Long,
        mimeType: String,
        fileBytes: ByteArray
    ) {
        val deviceId = deviceIdProvider.getDeviceId()
        val checksum = ChecksumUtil.sha256(fileBytes)
        val chunkCount = ChecksumUtil.chunkCount(sizeBytes)

        val file = SyncFile(
            id = ChecksumUtil.generateId(localPath, deviceId),
            name = name,
            localPath = localPath,
            remotePath = "files/$deviceId/$name",
            sizeBytes = sizeBytes,
            mimeType = mimeType,
            checksum = checksum,
            vectorClock = VectorClock().increment(deviceId),
            syncStatus = SyncStatus.PENDING_UPLOAD,
            lastModifiedAt = System.currentTimeMillis(),
            chunkCount = chunkCount
        )

        repository.insertFile(file)
        repository.enqueueUpload(file.id)
        // WorkManager picks up the queue and calls SyncWorker
    }
}

/**
 * Core conflict resolution logic.
 *
 * Strategy:
 *  - EQUAL / BEFORE  → remote is newer, keep remote
 *  - AFTER           → local is newer, keep local (overwrite remote)
 *  - CONCURRENT      → true conflict, surface to user for manual decision
 *
 * This is what makes this project interview-worthy vs a naive last-write-wins.
 */
class ConflictResolutionUseCase @Inject constructor(
    private val repository: FileRepository,
    private val deviceIdProvider: DeviceIdProvider
) {
    suspend operator fun invoke(localFile: SyncFile): ConflictResolution {
        val remoteFile = repository.fetchRemoteFile(localFile.id)
            ?: return ConflictResolution.KeepLocal(localFile) // no remote yet, safe to upload

        val relation = localFile.vectorClock.compare(remoteFile.vectorClock)

        return when (relation) {
            ClockRelation.AFTER -> {
                // Local is ahead — our changes are newer
                ConflictResolution.KeepLocal(localFile)
            }
            ClockRelation.BEFORE, ClockRelation.EQUAL -> {
                // Remote is ahead or identical — pull remote
                ConflictResolution.KeepRemote(remoteFile)
            }
            ClockRelation.CONCURRENT -> {
                // Neither dominates — true conflict, ask the user
                ConflictResolution.NeedsUserDecision(localFile, remoteFile)
            }
        }
    }
}

/**
 * Handles the result of a user resolving a conflict manually.
 * Updates the local record with a merged vector clock regardless of which version was chosen.
 */
class ResolveConflictUseCase @Inject constructor(
    private val repository: FileRepository,
    private val deviceIdProvider: DeviceIdProvider
) {
    suspend operator fun invoke(chosenFile: SyncFile, discardedFile: SyncFile) {
        val deviceId = deviceIdProvider.getDeviceId()

        // Merge both clocks so future comparisons know about all past events
        val mergedClock = chosenFile.vectorClock
            .merge(discardedFile.vectorClock)
            .increment(deviceId)

        val resolved = chosenFile.copy(
            vectorClock = mergedClock,
            syncStatus = SyncStatus.PENDING_UPLOAD,
            lastModifiedAt = System.currentTimeMillis()
        )

        repository.updateFile(resolved)
        repository.enqueueUpload(resolved.id)
    }
}

/**
 * Triggers a full sync pass: checks all pending items and dispatches them.
 */
class TriggerSyncUseCase @Inject constructor(
    private val repository: FileRepository
) {
    suspend operator fun invoke() {
        val pending = repository.getPendingSyncItems()
        pending.forEach { fileId ->
            val file = repository.getFileById(fileId) ?: return@forEach
            repository.enqueueUpload(fileId)
        }
    }
}
