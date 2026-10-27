package com.cisiur.nannycamviewer.playback

enum class ConnectionState {
    NOT_CONNECTED,
    CONNECTING,
    WAITING_VIDEO,
    PLAYING,
    RECONNECTING,
    ERROR
}
