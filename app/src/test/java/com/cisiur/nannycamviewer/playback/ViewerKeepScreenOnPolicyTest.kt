package com.cisiur.nannycamviewer.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerKeepScreenOnPolicyTest {
    @Test
    fun `inactive and terminal states allow normal display idle`() {
        assertFalse(ViewerKeepScreenOnPolicy.shouldKeepScreenOn(ConnectionState.NOT_CONNECTED))
        assertFalse(ViewerKeepScreenOnPolicy.shouldKeepScreenOn(ConnectionState.ERROR))
    }

    @Test
    fun `active viewer states keep the display awake`() {
        assertTrue(ViewerKeepScreenOnPolicy.shouldKeepScreenOn(ConnectionState.CONNECTING))
        assertTrue(ViewerKeepScreenOnPolicy.shouldKeepScreenOn(ConnectionState.WAITING_VIDEO))
        assertTrue(ViewerKeepScreenOnPolicy.shouldKeepScreenOn(ConnectionState.PLAYING))
        assertTrue(ViewerKeepScreenOnPolicy.shouldKeepScreenOn(ConnectionState.RECONNECTING))
    }

    @Test
    fun `automatic reconnect remains active but explicit disconnect does not`() {
        val sequence = listOf(ConnectionState.CONNECTING, ConnectionState.WAITING_VIDEO,
            ConnectionState.PLAYING, ConnectionState.RECONNECTING)
        sequence.forEach { assertTrue(ViewerKeepScreenOnPolicy.shouldKeepScreenOn(it)) }
        assertFalse(ViewerKeepScreenOnPolicy.shouldKeepScreenOn(ConnectionState.NOT_CONNECTED))
    }
}
