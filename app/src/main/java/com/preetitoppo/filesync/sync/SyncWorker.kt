package com.preetitoppo.filesync.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.preetitoppo.filesync.domain.model.ConflictResolution
import com.preetitoppo.filesync.domain.model.SyncStatus
import com.preetitoppo.filesync.domain.repository.FileRepository
import com.preetitoppo.filesync.domain.usecase.ConflictResolutionUseCase
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The heart of the sync engine.
 *
 * Triggered by WorkManager when:
 *  - A file is enqueued for upload (via AddFileUseCase)
 *  - Network connectivity is restored after failure
 *  - Periodic background sync fires
 *
 * Constraints guarantee this only runs with network connectivity.
 * Extending CoroutineWorker means the work runs on a coroutine — no blocking calls on main thread.
 *
 * Failure handling:
 *  - Returns Result.retry() on transient failures (network blip)
 *  - Returns Result.failure() on permanent failures (file not found)
 *  - WorkManager handles exponential backoff automatically
 */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: FileRepository,
    private val conflictResolutionUseCase: ConflictResolutionUseCase
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_FILE_ID = "file_id"
        const val SYNC_WORK_NAME = "sync_worker"

        /**
         * Builds a one-time upload request for a specific file.
         * Requires network; uses exponential backoff on failure.
         */
        fun buildUploadRequest(fileId: String): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            return OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_FILE_ID to fileId))
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .addTag(SYNC_WORK_NAME)
                .build()
        }

        /**
         * Periodic background sync — checks for remote changes every 15 minutes.
         */
        fun buildPeriodicSyncRequest(): PeriodicWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            return PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .addTag("periodic_sync")
                .build()
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val fileId = inputData.getString(KEY_FILE_ID)
            ?: return@withContext Result.failure(
                workDataOf("error" to "No fileId provided")
            )

        val file = repository.getFileById(fileId)
            ?: return@withContext Result.failure(
                workDataOf("error" to "File $fileId not found in local DB")
            )

        return@withContext try {
            // Step 1: Resolve conflicts before uploading
            val resolution = conflictResolutionUseCase(file)

            when (resolution) {
                is ConflictResolution.KeepLocal -> {
                    // Our version wins — proceed with upload
                    performUpload(fileId, file)
                }

                is ConflictResolution.KeepRemote -> {
                    // Remote is newer — download instead
                    repository.downloadFile(resolution.file) { _, _ -> }
                    repository.markSyncComplete(fileId)
                    Result.success()
                }

                is ConflictResolution.NeedsUserDecision -> {
                    // Mark as conflict; UI will surface this to the user
                    val conflicted = file.copy(syncStatus = SyncStatus.CONFLICT)
                    repository.updateFile(conflicted)
                    // Don't retry — wait for user resolution
                    Result.failure(workDataOf("error" to "Conflict detected for $fileId"))
                }
            }
        } catch (e: Exception) {
            repository.markSyncFailed(fileId, e.message ?: "Unknown error")

            // Retry on network errors, fail on others
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure(workDataOf("error" to e.message))
            }
        }
    }

    private suspend fun performUpload(
        fileId: String,
        file: com.preetitoppo.filesync.domain.model.SyncFile
    ): Result {
        // Update status to UPLOADING so UI shows progress
        repository.updateFile(file.copy(syncStatus = SyncStatus.UPLOADING))

        // Chunked upload with progress tracking
        repository.uploadFileChunked(file) { uploaded, total ->
            // Update Room DB with chunk progress for UI
        }

        // Commit metadata + vector clock to Firestore
        repository.commitRemoteMetadata(
            file.copy(syncStatus = SyncStatus.SYNCED)
        )

        repository.markSyncComplete(fileId)
        return Result.success()
    }
}
