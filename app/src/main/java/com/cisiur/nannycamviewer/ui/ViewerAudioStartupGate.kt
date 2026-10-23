package com.cisiur.nannycamviewer.ui

/**
 * Pure, session-scoped decision model for initially starting the independent audio connection.
 *
 * First-frame callbacks are used only as a later sequencing milestone. They do not establish
 * that Media3 has produced a visually valid camera image.
 */
class ViewerAudioStartupGate {
    sealed class DecoderDecision {
        object AudioRemainsGated : DecoderDecision()
        object NoActiveSession : DecoderDecision()
    }

    sealed class FirstFrameDecision {
        data class ScheduleAudio(val token: Long) : FirstFrameDecision()
        object AlreadyScheduled : FirstFrameDecision()
        object AlreadyStarted : FirstFrameDecision()
        object IgnoredFailedAttempt : FirstFrameDecision()
        object NoActiveSession : FirstFrameDecision()
    }

    sealed class VideoFailureDecision {
        object CancelPendingAudioStart : VideoFailureDecision()
        object AudioRemainsGated : VideoFailureDecision()
        object AudioAlreadyStarted : VideoFailureDecision()
        object NoActiveSession : VideoFailureDecision()
    }

    sealed class DelayedStartDecision {
        data class StartAudio(val uri: String) : DelayedStartDecision()
        object IgnoredStaleCallback : DelayedStartDecision()
    }

    private var viewerUri: String? = null
    private var audioStartedForSession = false
    private var scheduledAudioToken: Long? = null
    private var nextAudioToken = 0L
    private var lastFailedVideoAttemptId = Long.MIN_VALUE

    fun beginSession(uri: String) {
        viewerUri = uri
        audioStartedForSession = false
        scheduledAudioToken = null
        lastFailedVideoAttemptId = Long.MIN_VALUE
    }

    fun onVideoDecoderInitialized(): DecoderDecision =
        if (viewerUri == null) DecoderDecision.NoActiveSession else DecoderDecision.AudioRemainsGated

    fun onVideoFirstFrame(attemptId: Long): FirstFrameDecision {
        if (viewerUri == null) return FirstFrameDecision.NoActiveSession
        if (attemptId <= lastFailedVideoAttemptId) return FirstFrameDecision.IgnoredFailedAttempt
        if (audioStartedForSession) return FirstFrameDecision.AlreadyStarted
        if (scheduledAudioToken != null) return FirstFrameDecision.AlreadyScheduled

        val token = ++nextAudioToken
        scheduledAudioToken = token
        return FirstFrameDecision.ScheduleAudio(token)
    }

    fun onVideoFailure(attemptId: Long): VideoFailureDecision {
        if (viewerUri == null) return VideoFailureDecision.NoActiveSession
        lastFailedVideoAttemptId = maxOf(lastFailedVideoAttemptId, attemptId)
        if (audioStartedForSession) return VideoFailureDecision.AudioAlreadyStarted
        if (scheduledAudioToken != null) {
            scheduledAudioToken = null
            return VideoFailureDecision.CancelPendingAudioStart
        }
        return VideoFailureDecision.AudioRemainsGated
    }

    fun consumeDelayedAudioStart(token: Long): DelayedStartDecision {
        val uri = viewerUri
        if (uri == null || scheduledAudioToken != token || audioStartedForSession) {
            return DelayedStartDecision.IgnoredStaleCallback
        }
        scheduledAudioToken = null
        audioStartedForSession = true
        return DelayedStartDecision.StartAudio(uri)
    }

    fun reset() {
        viewerUri = null
        audioStartedForSession = false
        scheduledAudioToken = null
        lastFailedVideoAttemptId = Long.MIN_VALUE
    }

    companion object {
        const val INITIAL_AUDIO_AFTER_FIRST_FRAME_DELAY_MS = 250L
    }
}
