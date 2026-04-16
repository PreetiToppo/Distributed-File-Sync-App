package com.preetitoppo.filesync.domain.usecase

import com.preetitoppo.filesync.domain.model.ConflictResolution
import com.preetitoppo.filesync.domain.model.SyncFile
import com.preetitoppo.filesync.domain.model.SyncStatus
import com.preetitoppo.filesync.domain.model.VectorClock
import com.preetitoppo.filesync.domain.repository.FileRepository
import com.preetitoppo.filesync.util.DeviceIdProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for ConflictResolutionUseCase.
 * Uses MockK to fake the repository — no Firebase, no Room, no Android runtime needed.
 */
class ConflictResolutionUseCaseTest {

    private lateinit var repository: FileRepository
    private lateinit var deviceIdProvider: DeviceIdProvider
    private lateinit var useCase: ConflictResolutionUseCase

    private val deviceId = "test-device-123"

    @Before
    fun setup() {
        repository = mockk()
        deviceIdProvider = mockk()
        coEvery { deviceIdProvider.getDeviceId() } returns deviceId
        useCase = ConflictResolutionUseCase(repository, deviceIdProvider)
    }

    @Test
    fun `when no remote file exists, returns KeepLocal`() = runTest {
        val localFile = buildFile(clock = VectorClock(mapOf(deviceId to 1L)))
        coEvery { repository.fetchRemoteFile(localFile.id) } returns null

        val result = useCase(localFile)
        assertTrue(result is ConflictResolution.KeepLocal)
    }

    @Test
    fun `when local is ahead of remote, returns KeepLocal`() = runTest {
        val localFile = buildFile(clock = VectorClock(mapOf(deviceId to 3L)))
        val remoteFile = buildFile(clock = VectorClock(mapOf(deviceId to 1L)))
        coEvery { repository.fetchRemoteFile(localFile.id) } returns remoteFile

        val result = useCase(localFile)
        assertTrue(result is ConflictResolution.KeepLocal)
    }

    @Test
    fun `when remote is ahead of local, returns KeepRemote`() = runTest {
        val localFile = buildFile(clock = VectorClock(mapOf(deviceId to 1L)))
        val remoteFile = buildFile(clock = VectorClock(mapOf(deviceId to 5L)))
        coEvery { repository.fetchRemoteFile(localFile.id) } returns remoteFile

        val result = useCase(localFile)
        assertTrue(result is ConflictResolution.KeepRemote)
    }

    @Test
    fun `when clocks are concurrent, returns NeedsUserDecision`() = runTest {
        // Two devices edited concurrently — true conflict
        val localFile = buildFile(clock = VectorClock(mapOf(deviceId to 2L, "other-device" to 1L)))
        val remoteFile = buildFile(clock = VectorClock(mapOf(deviceId to 1L, "other-device" to 2L)))
        coEvery { repository.fetchRemoteFile(localFile.id) } returns remoteFile

        val result = useCase(localFile)
        assertTrue(result is ConflictResolution.NeedsUserDecision)
    }

    @Test
    fun `when clocks are equal, returns KeepRemote (no-op)`() = runTest {
        val clock = VectorClock(mapOf(deviceId to 2L))
        val localFile = buildFile(clock = clock)
        val remoteFile = buildFile(clock = clock)
        coEvery { repository.fetchRemoteFile(localFile.id) } returns remoteFile

        val result = useCase(localFile)
        assertTrue(result is ConflictResolution.KeepRemote)
    }

    private fun buildFile(clock: VectorClock) = SyncFile(
        id = "file-001",
        name = "test.txt",
        localPath = "/data/test.txt",
        remotePath = "files/test.txt",
        sizeBytes = 1024L,
        mimeType = "text/plain",
        checksum = "abc123",
        vectorClock = clock,
        syncStatus = SyncStatus.PENDING_UPLOAD,
        lastModifiedAt = System.currentTimeMillis(),
        chunkCount = 1
    )
}
