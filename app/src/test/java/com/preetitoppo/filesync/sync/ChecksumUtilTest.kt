package com.preetitoppo.filesync.sync

import com.preetitoppo.filesync.util.ChecksumUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChecksumUtilTest {

    @Test
    fun `sha256 returns consistent hash for same input`() {
        val data = "hello world".toByteArray()
        assertEquals(ChecksumUtil.sha256(data), ChecksumUtil.sha256(data))
    }

    @Test
    fun `sha256 returns different hashes for different inputs`() {
        assertNotEquals(
            ChecksumUtil.sha256("file_v1".toByteArray()),
            ChecksumUtil.sha256("file_v2".toByteArray())
        )
    }

    @Test
    fun `sha256 returns 64 character hex string`() {
        val hash = ChecksumUtil.sha256("test".toByteArray())
        assertEquals(64, hash.length)
        assertTrue(hash.all { it.isDigit() || it in 'a'..'f' })
    }

    @Test
    fun `chunkCount returns 1 for empty file`() {
        assertEquals(1, ChecksumUtil.chunkCount(0L))
    }

    @Test
    fun `chunkCount returns 1 for file smaller than 4MB`() {
        assertEquals(1, ChecksumUtil.chunkCount(1024L * 1024))  // 1MB
    }

    @Test
    fun `chunkCount returns 2 for file slightly over 4MB`() {
        val justOver4MB = (4L * 1024 * 1024) + 1
        assertEquals(2, ChecksumUtil.chunkCount(justOver4MB))
    }

    @Test
    fun `chunkCount returns correct value for exact multiple`() {
        val exactly8MB = 8L * 1024 * 1024
        assertEquals(2, ChecksumUtil.chunkCount(exactly8MB))
    }

    @Test
    fun `generateId is deterministic for same inputs`() {
        val id1 = ChecksumUtil.generateId("/sdcard/doc.pdf", "device-abc")
        val id2 = ChecksumUtil.generateId("/sdcard/doc.pdf", "device-abc")
        assertEquals(id1, id2)
    }

    @Test
    fun `generateId differs for different devices`() {
        val id1 = ChecksumUtil.generateId("/sdcard/doc.pdf", "device-A")
        val id2 = ChecksumUtil.generateId("/sdcard/doc.pdf", "device-B")
        assertNotEquals(id1, id2)
    }

    @Test
    fun `chunkChecksums returns one hash per 4MB chunk`() {
        val fourMB = 4 * 1024 * 1024
        val data = ByteArray(fourMB + 100) { it.toByte() }
        val checksums = ChecksumUtil.chunkChecksums(data)
        assertEquals(2, checksums.size)
    }
}
