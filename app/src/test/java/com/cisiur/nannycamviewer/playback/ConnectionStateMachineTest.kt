package com.cisiur.nannycamviewer.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateMachineTest {
    @Test
    fun `moves from not connected through connecting to playing`() {
        val stateMachine = ConnectionStateMachine()

        assertTrue(stateMachine.beginConnect())
        assertEquals(ConnectionState.CONNECTING, stateMachine.state)

        stateMachine.playbackStarted()
        assertEquals(ConnectionState.PLAYING, stateMachine.state)
    }

    @Test
    fun `prevents duplicate connection attempts`() {
        val stateMachine = ConnectionStateMachine()

        assertTrue(stateMachine.beginConnect())
        assertFalse(stateMachine.beginConnect())
    }

    @Test
    fun `failure enters reconnecting and disconnect clears state`() {
        val stateMachine = ConnectionStateMachine()
        stateMachine.beginConnect()

        stateMachine.playbackFailed()
        assertEquals(ConnectionState.RECONNECTING, stateMachine.state)

        stateMachine.disconnect()
        assertEquals(ConnectionState.NOT_CONNECTED, stateMachine.state)
    }

    @Test
    fun `error can be retried`() {
        val stateMachine = ConnectionStateMachine()
        stateMachine.reportError()

        assertTrue(stateMachine.beginConnect())
        assertEquals(ConnectionState.CONNECTING, stateMachine.state)
    }
}
