package com.cisiur.nannycamviewer.playback

enum class ConnectionState {
    NOT_CONNECTED,
    CONNECTING,
    PLAYING,
    RECONNECTING,
    ERROR
}

/** Small, platform-independent connection state model for the TV UI and playback session. */
class ConnectionStateMachine {
    var state: ConnectionState = ConnectionState.NOT_CONNECTED
        private set

    fun beginConnect(): Boolean {
        if (state != ConnectionState.NOT_CONNECTED && state != ConnectionState.ERROR) return false
        state = ConnectionState.CONNECTING
        return true
    }

    fun playbackStarted() {
        state = ConnectionState.PLAYING
    }

    fun playbackFailed() {
        state = ConnectionState.RECONNECTING
    }

    fun reportError() {
        state = ConnectionState.ERROR
    }

    fun disconnect() {
        state = ConnectionState.NOT_CONNECTED
    }
}
