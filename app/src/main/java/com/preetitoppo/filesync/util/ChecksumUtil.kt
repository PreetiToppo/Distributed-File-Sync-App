package com.preetitoppo.filesync.util

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SHA-256 checksum utilities for delta diffing.
 *
 * Used by AddFileUseCase to fingerprint file content.
 * If checksum matches remote, we skip the upload entirely — zero redundant transfers.
 */
object ChecksumUtil {

    private const val CHUNK_SIZE_BYTES = 4 * 1024 * 1024  // 4MB

    /** Compute SHA-256 hex digest of a byte array */
    fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(data)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /** Compute SHA-256 of a string (used for IDs) */
    fun sha256(input: String): String = sha256(input.toByteArray(Charsets.UTF_8))

    /** Calculate how many 4MB chunks a file of [sizeBytes] requires */
    fun chunkCount(sizeBytes: Long): Int {
        if (sizeBytes == 0L) return 1
        return ((sizeBytes + CHUNK_SIZE_BYTES - 1) / CHUNK_SIZE_BYTES).toInt()
    }

    /**
     * Generate a deterministic file ID from local path + device ID.
     * Same file from the same device always gets the same ID.
     */
    fun generateId(localPath: String, deviceId: String): String {
        val input = "$deviceId:$localPath"
        return sha256(input).take(16) // 16 hex chars = 64-bit ID
    }

    /**
     * Compute per-chunk checksums for a file.
     * Used to verify chunk integrity after upload and enable skip-already-uploaded-chunks.
     */
    fun chunkChecksums(data: ByteArray): List<String> {
        val checksums = mutableListOf<String>()
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + CHUNK_SIZE_BYTES, data.size)
            checksums.add(sha256(data.copyOfRange(offset, end)))
            offset = end
        }
        return checksums
    }
}

/**
 * Provides a stable device identifier used as the vector clock key for this device.
 * Uses Android ANDROID_ID which is stable across reboots for a given app install.
 */
@Singleton
class DeviceIdProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun getDeviceId(): String {
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: UUID.randomUUID().toString()
    }
}
