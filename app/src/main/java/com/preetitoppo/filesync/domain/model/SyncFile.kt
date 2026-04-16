package com.preetitoppo.filesync.domain.model

import kotlinx.serialization.Serializable

/**
 * Core domain model representing a file tracked by the sync system.
 * Lives in the domain layer — pure Kotlin, zero Android imports.
 */
data class SyncFile(
    val id: String,
    val name: String,
    val localPath: String,
    val remotePath: String,
    val sizeBytes: Long,
    val mimeType: String,
    val checksum: String,           // SHA-256 of full file content
    val vectorClock: VectorClock,
    val syncStatus: SyncStatus,
    val lastModifiedAt: Long,       // epoch millis
    val chunkCount: Int,
    val uploadedChunks: Int = 0
)

/**
 * Vector clock for tracking causal history of edits across devices.
 * Each device has an entry; value increments on every local write.
 *
 * Example:
 *   Device A writes → { "deviceA": 1 }
 *   Device B writes → { "deviceA": 1, "deviceB": 1 }
 *   Concurrent edits → { "deviceA": 2 } vs { "deviceB": 2 }  ← CONFLICT
 */
@Serializable
data class VectorClock(
    val clock: Map<String, Long> = emptyMap()
) {
    /** Increment this device's counter */
    fun increment(deviceId: String): VectorClock {
        val updated = clock.toMutableMap()
        updated[deviceId] = (updated[deviceId] ?: 0L) + 1L
        return VectorClock(updated)
    }

    /** Merge two clocks by taking the max of each device's counter */
    fun merge(other: VectorClock): VectorClock {
        val merged = clock.toMutableMap()
        other.clock.forEach { (device, time) ->
            merged[device] = maxOf(merged[device] ?: 0L, time)
        }
        return VectorClock(merged)
    }

    /**
     * Compare two vector clocks:
     *   BEFORE    → this happened-before other
     *   AFTER     → this happened-after other
     *   EQUAL     → identical
     *   CONCURRENT → neither dominates — CONFLICT detected
     */
    fun compare(other: VectorClock): ClockRelation {
        val allDevices = clock.keys + other.clock.keys
        var thisAhead = false
        var otherAhead = false

        for (device in allDevices) {
            val t = clock[device] ?: 0L
            val o = other.clock[device] ?: 0L
            if (t > o) thisAhead = true
            if (o > t) otherAhead = true
        }

        return when {
            !thisAhead && !otherAhead -> ClockRelation.EQUAL
            thisAhead && !otherAhead -> ClockRelation.AFTER
            !thisAhead && otherAhead -> ClockRelation.BEFORE
            else -> ClockRelation.CONCURRENT  // conflict
        }
    }
}

enum class ClockRelation { BEFORE, AFTER, EQUAL, CONCURRENT }

enum class SyncStatus {
    SYNCED,         // local == remote
    PENDING_UPLOAD, // local change not yet pushed
    PENDING_DOWNLOAD, // remote change not yet pulled
    UPLOADING,      // transfer in progress
    DOWNLOADING,
    CONFLICT,       // concurrent edits detected
    ERROR
}

/** Represents a single 4MB chunk of a file being uploaded */
data class FileChunk(
    val fileId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val data: ByteArray,
    val checksum: String  // SHA-256 of this chunk's bytes
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FileChunk) return false
        return fileId == other.fileId && chunkIndex == other.chunkIndex
    }
    override fun hashCode(): Int = 31 * fileId.hashCode() + chunkIndex
}

/** Conflict resolution result returned from ConflictResolutionUseCase */
sealed class ConflictResolution {
    data class KeepLocal(val file: SyncFile) : ConflictResolution()
    data class KeepRemote(val file: SyncFile) : ConflictResolution()
    data class NeedsUserDecision(val local: SyncFile, val remote: SyncFile) : ConflictResolution()
}
