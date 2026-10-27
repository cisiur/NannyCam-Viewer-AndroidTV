package com.cisiur.nannycamviewer.ui

import com.cisiur.nannycamviewer.playback.ConnectionState

/**
 * Small, Android-free policy for the Fire OS PiP feasibility probe.
 *
 * This only gates PiP entry and decides whether normal Activity-stop cleanup may be
 * deferred briefly while Android confirms a PiP transition. It never owns media state.
 */
internal object PipEntryGate {
    fun canRequest(
        deviceSupportsPip: Boolean,
        playbackState: ConnectionState,
        surfaceValid: Boolean,
        showingSettings: Boolean,
        requestPending: Boolean
    ): Boolean = deviceSupportsPip &&
        playbackState == ConnectionState.PLAYING &&
        surfaceValid &&
        !showingSettings &&
        !requestPending
}

internal enum class PipStopDecision { PRESERVE_PLAYBACK, DEFER_CLEANUP, CLEANUP_NOW }

/**
 * Tracks only the short-lived request window. Actual PiP membership always comes from
 * Activity.isInPictureInPictureMode; this class deliberately does not mirror it.
 */
internal class PipLifecyclePolicy {
    var requestPending: Boolean = false
        private set

    fun beginEntryRequest(): Boolean {
        if (requestPending) return false
        requestPending = true
        return true
    }

    fun entryRequestRejected() {
        requestPending = false
    }

    fun onPipModeChanged() {
        requestPending = false
    }

    fun onStop(platformReportsPip: Boolean): PipStopDecision = when {
        platformReportsPip -> PipStopDecision.PRESERVE_PLAYBACK
        requestPending -> PipStopDecision.DEFER_CLEANUP
        else -> PipStopDecision.CLEANUP_NOW
    }

    fun afterDeferredStop(platformReportsPip: Boolean): PipStopDecision {
        requestPending = false
        return if (platformReportsPip) PipStopDecision.PRESERVE_PLAYBACK else PipStopDecision.CLEANUP_NOW
    }

    fun onDestroy(): PipStopDecision {
        requestPending = false
        return PipStopDecision.CLEANUP_NOW
    }
}
