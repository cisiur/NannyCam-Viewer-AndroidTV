package com.cisiur.nannycamviewer.playback

/** Maps the viewer's video-session intent to the window idle-display policy. */
object ViewerKeepScreenOnPolicy {
    fun shouldKeepScreenOn(state: ConnectionState): Boolean = when (state) {
        ConnectionState.CONNECTING,
        ConnectionState.WAITING_VIDEO,
        ConnectionState.PLAYING,
        ConnectionState.RECONNECTING -> true

        ConnectionState.NOT_CONNECTED,
        ConnectionState.ERROR -> false
    }
}
