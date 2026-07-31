package com.example.taskervideoplugin.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FrameTimingTest {
    @Test
    fun fixedFrameCountProducesEvenTimestamps() {
        assertEquals(listOf(0L, 2_500_000L, 5_000_000L, 7_500_000L), FrameTiming.timestampsUs(10_000, null, 4))
    }

    @Test
    fun frameRateProducesRequestedFramesPerSecond() {
        assertEquals(listOf(0L, 500_000L, 1_000_000L, 1_500_000L), FrameTiming.timestampsUs(2_000, 2.0, null))
    }

    @Test
    fun rejectsAmbiguousOrInvalidInputs() {
        assertThrows(IllegalArgumentException::class.java) { FrameTiming.timestampsUs(1_000, 1.0, 1) }
        assertThrows(IllegalArgumentException::class.java) { FrameTiming.timestampsUs(1_000, null, null) }
        assertThrows(IllegalArgumentException::class.java) { FrameTiming.timestampsUs(1_000, 0.0, null) }
        assertThrows(IllegalArgumentException::class.java) { FrameTiming.timestampsUs(0, 1.0, null) }
    }
}
