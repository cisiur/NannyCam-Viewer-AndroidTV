package com.cisiur.nannycamviewer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoBufferingModeTest {
    @Test
    fun `default mode leaves Media3 LoadControl unmodified`() {
        assertNull(VideoBufferingPolicy.durationsFor(VideoBufferingMode.DEFAULT))
    }

    @Test
    fun `low latency mode maps to the bounded live stream durations`() {
        assertEquals(
            VideoBufferDurations(500, 1_500, 100, 250),
            VideoBufferingPolicy.durationsFor(VideoBufferingMode.LOW_LATENCY)
        )
    }

    @Test
    fun `low latency durations satisfy LoadControl ordering constraints`() {
        assertTrue(VideoBufferingPolicy.lowLatencyDurations.isValid())
    }

    @Test
    fun `mode toggle selects a future player configuration without mutating the current enum`() {
        val currentAttemptMode = VideoBufferingMode.LOW_LATENCY
        val nextAttemptMode = currentAttemptMode.toggled()

        assertEquals(VideoBufferingMode.LOW_LATENCY, currentAttemptMode)
        assertEquals(VideoBufferingMode.DEFAULT, nextAttemptMode)
        assertFalse(nextAttemptMode == currentAttemptMode)
    }
}
