package com.preetitoppo.filesync.data.repository

import com.preetitoppo.filesync.data.local.dao.SyncFileDao
import com.preetitoppo.filesync.data.local.dao.SyncQueueDao
import com.preetitoppo.filesync.data.local.entity.SyncFileEntity
import com.preetitoppo.filesync.data.local.entity.SyncOperation
import com.preetitoppo.filesync.data.local.entity.SyncQueueEntity
import com.preetitoppo.filesync.data.remote.FirebaseDataSource
import com.preetitoppo.filesync.domain.model.SyncFile
import com.preetitoppo.filesync.domain.model.SyncStatus
import com.preetitoppo.filesync.domain.model.VectorClock
import com.preetitoppo.filesync.domain.repository.FileRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileRepositoryImpl @Inject constructor(
    private val fileDao: SyncFileDao,
    private val queueDao: SyncQueueDao,
    private val remoteDataSource: FirebaseDataSource
) : FileRepository {

    override fun observeAllFiles(): Flow<List<SyncFile>> =
        fileDao.observeAll().map { entities -> entities.map { it.toDomain() } }

    override fun observeFilesByStatus(status: SyncStatus): Flow<List<SyncFile>> =
        fileDao.observeByStatus(status).map { entities -> entities.map { it.toDomain() } }

    override suspend fun getFileById(id: String): SyncFile? =
        fileDao.getById(id)?.toDomain()

    override suspend fun insertFile(file: SyncFile) =
        fileDao.insert(file.toEntity())

    override suspend fun updateFile(file: SyncFile) =
        fileDao.update(file.toEntity())

    override suspend fun deleteFile(fileId: String) =
        fileDao.deleteById(fileId)

    override suspend fun enqueueUpload(fileId: String) {
        queueDao.insert(
            SyncQueueEntity(fileId = fileId, operation = SyncOperation.UPLOAD)
        )
    }

    override suspend fun enqueueDownload(fileId: String) {
        queueDao.insert(
            SyncQueueEntity(fileId = fileId, operation = SyncOperation.DOWNLOAD)
        )
    }

    override suspend fun fetchRemoteFile(fileId: String): SyncFile? =
        remoteDataSource.fetchFileMetadata(fileId)

    override suspend fun uploadFileChunked(
        file: SyncFile,
        onProgress: (Int, Int) -> Unit
    ) {
        // In production, read actual bytes from file.localPath
        // Here we pass through to the data source
        val fileBytes = java.io.File(file.localPath).readBytes()
        remoteDataSource.uploadChunked(file, fileBytes, onProgress)
    }

    override suspend fun downloadFile(
        file: SyncFile,
        onProgress: (Long, Long) -> Unit
    ) {
        remoteDataSource.downloadChunked(file, file.localPath, onProgress)
    }

    override suspend fun commitRemoteMetadata(file: SyncFile) =
        remoteDataSource.commitMetadata(file)

    override suspend fun getPendingSyncItems(): List<String> =
        queueDao.getPending().map { it.fileId }

    override suspend fun markSyncComplete(fileId: String) {
        queueDao.markComplete(fileId)
        fileDao.updateStatus(fileId, SyncStatus.SYNCED)
    }

    override suspend fun markSyncFailed(fileId: String, error: String) {
        queueDao.markFailed(fileId, error)
        fileDao.updateStatus(fileId, SyncStatus.ERROR)
    }

    // ---- Mappers ----

    private fun SyncFileEntity.toDomain(): SyncFile = SyncFile(
        id = id,
        name = name,
        localPath = localPath,
        remotePath = remotePath,
        sizeBytes = sizeBytes,
        mimeType = mimeType,
        checksum = checksum,
        vectorClock = Json.decodeFromString(vectorClockJson),
        syncStatus = syncStatus,
        lastModifiedAt = lastModifiedAt,
        chunkCount = chunkCount,
        uploadedChunks = uploadedChunks
    )

    private fun SyncFile.toEntity(): SyncFileEntity = SyncFileEntity(
        id = id,
        name = name,
        localPath = localPath,
        remotePath = remotePath,
        sizeBytes = sizeBytes,
        mimeType = mimeType,
        checksum = checksum,
        vectorClockJson = Json.encodeToString(vectorClock),
        syncStatus = syncStatus,
        lastModifiedAt = lastModifiedAt,
        chunkCount = chunkCount,
        uploadedChunks = uploadedChunks
    )
}
