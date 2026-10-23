package com.cisiur.nannycamviewer.ui

/**
 * Session-scoped gate for the initial independent audio RTSP connection.
 *
 * It deliberately knows nothing about either player implementation: the Activity starts video
 * first, then consumes the gate from Media3's decoder-initialized callback.
 */
class ViewerAudioStartupGate {
    sealed class Decision {
        data class StartAudio(val uri: String) : Decision()
        object AlreadyStarted : Decision()
        object NoActiveSession : Decision()
    }

    private var viewerUri: String? = null
    private var audioStartedForSession = false

    fun beginSession(uri: String) {
        viewerUri = uri
        audioStartedForSession = false
    }

    fun onVideoDecoderInitialized(): Decision {
        val uri = viewerUri ?: return Decision.NoActiveSession
        if (audioStartedForSession) return Decision.AlreadyStarted

        audioStartedForSession = true
        return Decision.StartAudio(uri)
    }

    fun isAudioStartPending(): Boolean = viewerUri != null && !audioStartedForSession

    /** Video failures do not consume or reset a pending initial audio start. */
    fun remainsGatedAfterVideoFailure(): Boolean = isAudioStartPending()

    fun reset() {
        viewerUri = null
        audioStartedForSession = false
    }
}
