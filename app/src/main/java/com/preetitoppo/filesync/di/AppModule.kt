package com.preetitoppo.filesync.di

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.memoryCacheSettings
import com.google.firebase.storage.FirebaseStorage
import com.preetitoppo.filesync.data.local.SyncDatabase
import com.preetitoppo.filesync.data.local.dao.SyncFileDao
import com.preetitoppo.filesync.data.local.dao.SyncQueueDao
import com.preetitoppo.filesync.data.remote.FirebaseDataSource
import com.preetitoppo.filesync.data.repository.FileRepositoryImpl
import com.preetitoppo.filesync.domain.repository.FileRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SyncDatabase {
        return Room.databaseBuilder(
            context,
            SyncDatabase::class.java,
            SyncDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideSyncFileDao(db: SyncDatabase): SyncFileDao = db.syncFileDao()

    @Provides
    fun provideSyncQueueDao(db: SyncDatabase): SyncQueueDao = db.syncQueueDao()
}

@Module
@InstallIn(SingletonComponent::class)
object FirebaseModule {

    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore {
        val firestore = FirebaseFirestore.getInstance()
        // Enable offline persistence for Firestore
        val settings = firestoreSettings {
            setLocalCacheSettings(memoryCacheSettings {})
        }
        firestore.firestoreSettings = settings
        return firestore
    }

    @Provides
    @Singleton
    fun provideFirebaseStorage(): FirebaseStorage = FirebaseStorage.getInstance()

    @Provides
    @Singleton
    fun provideFirebaseDataSource(
        firestore: FirebaseFirestore,
        storage: FirebaseStorage
    ): FirebaseDataSource = FirebaseDataSource(firestore, storage)
}

@Module
@InstallIn(SingletonComponent::class)
object WorkManagerModule {

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindFileRepository(impl: FileRepositoryImpl): FileRepository
}
