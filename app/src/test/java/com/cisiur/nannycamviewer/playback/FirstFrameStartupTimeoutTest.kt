package com.cisiur.nannycamviewer.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstFrameStartupTimeoutTest {
    @Test
    fun `timeout is armed for a new attempt and cancelled by first frame`() {
        val timeout = FirstFrameStartupTimeout()

        timeout.arm(1)
        assertTrue(timeout.isArmedFor(1))
        assertTrue(timeout.firstFrameRendered(1))
        assertFalse(timeout.isArmedFor(1))
        assertFalse(timeout.expire(1))
    }

    @Test
    fun `timeout expires only for the active attempt`() {
        val timeout = FirstFrameStartupTimeout()

        timeout.arm(4)
        assertFalse(timeout.expire(3))
        assertTrue(timeout.isArmedFor(4))
        assertTrue(timeout.expire(4))
        assertFalse(timeout.isArmedFor(4))
    }

    @Test
    fun `an expired first-frame timeout maps to the normal reconnecting state`() {
        val timeout = FirstFrameStartupTimeout()
        val connection = ConnectionStateMachine()
        connection.beginConnect()
        timeout.arm(1)

        assertTrue(timeout.expire(1))
        connection.playbackFailed()

        assertEquals(ConnectionState.RECONNECTING, connection.state)
    }

    @Test
    fun `replacement and disconnect cancellation leave no old timeout`() {
        val timeout = FirstFrameStartupTimeout()

        timeout.arm(1)
        timeout.arm(2)
        assertFalse(timeout.expire(1))
        assertTrue(timeout.isArmedFor(2))
        timeout.cancel()
        assertFalse(timeout.expire(2))
    }
}
