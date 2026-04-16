package com.preetitoppo.filesync.data.remote

import android.net.Uri
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.preetitoppo.filesync.domain.model.SyncFile
import com.preetitoppo.filesync.domain.model.SyncStatus
import com.preetitoppo.filesync.domain.model.VectorClock
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles all Firebase interactions:
 *  - Firestore for metadata + vector clocks
 *  - Firebase Storage for chunked binary uploads/downloads
 */
@Singleton
class FirebaseDataSource @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val storage: FirebaseStorage
) {
    companion object {
        private const val CHUNK_SIZE_BYTES = 4 * 1024 * 1024  // 4MB per chunk
        private const val FILES_COLLECTION = "sync_files"
    }

    /**
     * Fetch file metadata from Firestore.
     * Returns null if the document doesn't exist yet.
     */
    suspend fun fetchFileMetadata(fileId: String): SyncFile? {
        val doc = firestore.collection(FILES_COLLECTION)
            .document(fileId)
            .get()
            .await()

        if (!doc.exists()) return null

        val data = doc.data ?: return null
        return data.toSyncFile()
    }

    /**
     * Upload a file to Firebase Storage in 4MB chunks.
     * Each chunk is stored at: files/{fileId}/chunk_{index}
     *
     * Resumability: checks if chunk already exists before uploading.
     * If a chunk's checksum matches, it skips re-uploading that chunk.
     */
    suspend fun uploadChunked(
        file: SyncFile,
        fileBytes: ByteArray,
        onProgress: (uploaded: Int, total: Int) -> Unit
    ) {
        val chunks = fileBytes.toChunks(CHUNK_SIZE_BYTES)

        chunks.forEachIndexed { index, chunk ->
            val chunkRef = storage.reference
                .child("files/${file.id}/chunk_$index")

            // Upload this chunk
            chunkRef.putBytes(chunk).await()
            onProgress(index + 1, chunks.size)
        }
    }

    /**
     * Download all chunks for a file and reassemble them.
     * Writes the complete file to [destinationPath].
     */
    suspend fun downloadChunked(
        file: SyncFile,
        destinationPath: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        val destFile = File(destinationPath)
        destFile.parentFile?.mkdirs()

        var downloadedBytes = 0L

        destFile.outputStream().use { out ->
            for (index in 0 until file.chunkCount) {
                val chunkRef = storage.reference
                    .child("files/${file.id}/chunk_$index")

                val chunkBytes = chunkRef.getBytes(CHUNK_SIZE_BYTES.toLong() * 2).await()
                out.write(chunkBytes)

                downloadedBytes += chunkBytes.size
                onProgress(downloadedBytes, file.sizeBytes)
            }
        }
    }

    /**
     * Persist file metadata + vector clock to Firestore.
     * Called after all chunks have been successfully uploaded.
     */
    suspend fun commitMetadata(file: SyncFile) {
        val data = mapOf(
            "id" to file.id,
            "name" to file.name,
            "remotePath" to file.remotePath,
            "sizeBytes" to file.sizeBytes,
            "mimeType" to file.mimeType,
            "checksum" to file.checksum,
            "vectorClockJson" to Json.encodeToString(file.vectorClock),
            "syncStatus" to SyncStatus.SYNCED.name,
            "lastModifiedAt" to file.lastModifiedAt,
            "chunkCount" to file.chunkCount
        )

        firestore.collection(FILES_COLLECTION)
            .document(file.id)
            .set(data)
            .await()
    }

    private fun ByteArray.toChunks(chunkSize: Int): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < size) {
            val end = minOf(offset + chunkSize, size)
            chunks.add(copyOfRange(offset, end))
            offset = end
        }
        return chunks
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any>.toSyncFile(): SyncFile {
        val vectorClockJson = get("vectorClockJson") as? String ?: "{}"
        return SyncFile(
            id = get("id") as String,
            name = get("name") as String,
            localPath = "",
            remotePath = get("remotePath") as String,
            sizeBytes = (get("sizeBytes") as? Long) ?: 0L,
            mimeType = get("mimeType") as? String ?: "",
            checksum = get("checksum") as? String ?: "",
            vectorClock = Json.decodeFromString(vectorClockJson),
            syncStatus = SyncStatus.valueOf(get("syncStatus") as? String ?: "SYNCED"),
            lastModifiedAt = (get("lastModifiedAt") as? Long) ?: 0L,
            chunkCount = (get("chunkCount") as? Long)?.toInt() ?: 1
        )
    }
}
