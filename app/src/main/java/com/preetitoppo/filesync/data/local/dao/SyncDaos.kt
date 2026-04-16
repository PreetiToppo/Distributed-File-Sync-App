package com.preetitoppo.filesync.data.local.dao

import androidx.room.*
import com.preetitoppo.filesync.data.local.entity.SyncFileEntity
import com.preetitoppo.filesync.data.local.entity.SyncOperation
import com.preetitoppo.filesync.data.local.entity.SyncQueueEntity
import com.preetitoppo.filesync.domain.model.SyncStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncFileDao {

    @Query("SELECT * FROM sync_files ORDER BY lastModifiedAt DESC")
    fun observeAll(): Flow<List<SyncFileEntity>>

    @Query("SELECT * FROM sync_files WHERE syncStatus = :status ORDER BY lastModifiedAt DESC")
    fun observeByStatus(status: SyncStatus): Flow<List<SyncFileEntity>>

    @Query("SELECT * FROM sync_files WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SyncFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: SyncFileEntity)

    @Update
    suspend fun update(file: SyncFileEntity)

    @Query("DELETE FROM sync_files WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE sync_files SET syncStatus = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: SyncStatus)

    @Query("UPDATE sync_files SET uploadedChunks = :uploaded WHERE id = :id")
    suspend fun updateUploadedChunks(id: String, uploaded: Int)
}

@Dao
interface SyncQueueDao {

    @Query("SELECT * FROM sync_queue WHERE isCompleted = 0 ORDER BY enqueuedAt ASC")
    suspend fun getPending(): List<SyncQueueEntity>

    @Query("SELECT * FROM sync_queue WHERE fileId = :fileId LIMIT 1")
    suspend fun getByFileId(fileId: String): SyncQueueEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: SyncQueueEntity)

    @Query("UPDATE sync_queue SET isCompleted = 1 WHERE fileId = :fileId")
    suspend fun markComplete(fileId: String)

    @Query("""
        UPDATE sync_queue 
        SET retryCount = retryCount + 1, lastError = :error 
        WHERE fileId = :fileId
    """)
    suspend fun markFailed(fileId: String, error: String)

    @Query("DELETE FROM sync_queue WHERE isCompleted = 1")
    suspend fun clearCompleted()
}
