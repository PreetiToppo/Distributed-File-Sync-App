package com.preetitoppo.filesync.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.preetitoppo.filesync.data.local.dao.SyncFileDao
import com.preetitoppo.filesync.data.local.dao.SyncQueueDao
import com.preetitoppo.filesync.data.local.entity.SyncFileEntity
import com.preetitoppo.filesync.data.local.entity.SyncOperation
import com.preetitoppo.filesync.data.local.entity.SyncQueueEntity
import com.preetitoppo.filesync.domain.model.SyncStatus

@Database(
    entities = [SyncFileEntity::class, SyncQueueEntity::class],
    version = 1,
    exportSchema = true
)
@TypeConverters(SyncConverters::class)
abstract class SyncDatabase : RoomDatabase() {
    abstract fun syncFileDao(): SyncFileDao
    abstract fun syncQueueDao(): SyncQueueDao

    companion object {
        const val DATABASE_NAME = "sync_database"
    }
}

class SyncConverters {
    @TypeConverter
    fun fromSyncStatus(status: SyncStatus): String = status.name

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)

    @TypeConverter
    fun fromSyncOperation(op: SyncOperation): String = op.name

    @TypeConverter
    fun toSyncOperation(value: String): SyncOperation = SyncOperation.valueOf(value)
}
