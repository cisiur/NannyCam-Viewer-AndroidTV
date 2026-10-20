package com.cisiur.nannycamviewer.playback

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {
    @Test
    fun `uses the required retry schedule and repeats five seconds`() {
        val policy = ReconnectPolicy()
        val delays = LongArray(5) {
            policy.nextDelayMs()!!.also { policy.retryStarted() }
        }

        assertArrayEquals(longArrayOf(1_000, 2_000, 3_000, 5_000, 5_000), delays)
    }

    @Test
    fun `does not permit duplicate pending retries`() {
        val policy = ReconnectPolicy()

        policy.nextDelayMs()
        assertTrue(policy.reconnectPending)
        assertNull(policy.nextDelayMs())
    }

    @Test
    fun `cancel and reset clear pending retry intent`() {
        val policy = ReconnectPolicy()
        policy.nextDelayMs()

        policy.cancel()
        assertFalse(policy.reconnectPending)

        policy.nextDelayMs()
        policy.reset()
        assertFalse(policy.reconnectPending)
        assertTrue(policy.nextDelayMs() == 1_000L)
    }
}
