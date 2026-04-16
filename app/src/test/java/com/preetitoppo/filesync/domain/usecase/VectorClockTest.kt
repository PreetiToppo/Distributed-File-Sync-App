package com.preetitoppo.filesync.domain.usecase

import com.preetitoppo.filesync.domain.model.ClockRelation
import com.preetitoppo.filesync.domain.model.VectorClock
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for VectorClock — the most interview-critical logic in this project.
 *
 * These tests cover the four possible clock relationships:
 * BEFORE, AFTER, EQUAL, CONCURRENT (conflict).
 *
 * Run with: ./gradlew test
 */
class VectorClockTest {

    @Test
    fun `equal clocks return EQUAL relation`() {
        val clock = VectorClock(mapOf("A" to 1L, "B" to 2L))
        assertEquals(ClockRelation.EQUAL, clock.compare(clock))
    }

    @Test
    fun `local ahead of remote returns AFTER`() {
        val local = VectorClock(mapOf("A" to 2L))
        val remote = VectorClock(mapOf("A" to 1L))
        assertEquals(ClockRelation.AFTER, local.compare(remote))
    }

    @Test
    fun `remote ahead of local returns BEFORE`() {
        val local = VectorClock(mapOf("A" to 1L))
        val remote = VectorClock(mapOf("A" to 3L))
        assertEquals(ClockRelation.BEFORE, local.compare(remote))
    }

    @Test
    fun `concurrent edits from different devices returns CONCURRENT`() {
        // Device A incremented its own counter; Device B incremented its own counter
        // Neither is ahead of the other — this is a true conflict
        val deviceA = VectorClock(mapOf("A" to 2L, "B" to 1L))
        val deviceB = VectorClock(mapOf("A" to 1L, "B" to 2L))
        assertEquals(ClockRelation.CONCURRENT, deviceA.compare(deviceB))
    }

    @Test
    fun `increment increases only specified device counter`() {
        val clock = VectorClock(mapOf("A" to 1L))
        val incremented = clock.increment("A")
        assertEquals(2L, incremented.clock["A"])
    }

    @Test
    fun `increment adds new device if not present`() {
        val clock = VectorClock(mapOf("A" to 1L))
        val incremented = clock.increment("B")
        assertEquals(1L, incremented.clock["B"])
        assertEquals(1L, incremented.clock["A"]) // A unchanged
    }

    @Test
    fun `merge takes max of each device counter`() {
        val clock1 = VectorClock(mapOf("A" to 3L, "B" to 1L))
        val clock2 = VectorClock(mapOf("A" to 1L, "B" to 5L, "C" to 2L))
        val merged = clock1.merge(clock2)

        assertEquals(3L, merged.clock["A"])  // max(3, 1)
        assertEquals(5L, merged.clock["B"])  // max(1, 5)
        assertEquals(2L, merged.clock["C"])  // max(0, 2)
    }

    @Test
    fun `empty clock is BEFORE any non-empty clock`() {
        val empty = VectorClock()
        val nonEmpty = VectorClock(mapOf("A" to 1L))
        assertEquals(ClockRelation.BEFORE, empty.compare(nonEmpty))
    }

    @Test
    fun `new device in one clock causes CONCURRENT if other device also has changes`() {
        // Simulates: A edited on deviceA, B edited independently on deviceB
        // deviceA doesn't know about deviceB yet and vice versa
        val onlyA = VectorClock(mapOf("deviceA" to 1L))
        val onlyB = VectorClock(mapOf("deviceB" to 1L))
        assertEquals(ClockRelation.CONCURRENT, onlyA.compare(onlyB))
    }
}
