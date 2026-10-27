package com.cisiur.nannycamviewer.ui

import com.cisiur.nannycamviewer.playback.ConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipDiagnosticPolicyTest {
    @Test fun entryRequiresFeaturePlayingSurfaceAndViewer() {
        assertTrue(PipEntryGate.canRequest(true, ConnectionState.PLAYING, true, false, false))
        assertFalse(PipEntryGate.canRequest(false, ConnectionState.PLAYING, true, false, false))
        assertFalse(PipEntryGate.canRequest(true, ConnectionState.CONNECTING, true, false, false))
        assertFalse(PipEntryGate.canRequest(true, ConnectionState.PLAYING, false, false, false))
        assertFalse(PipEntryGate.canRequest(true, ConnectionState.PLAYING, true, true, false))
    }

    @Test fun duplicateRequestIsNotAllowed() {
        val policy = PipLifecyclePolicy()
        assertTrue(policy.beginEntryRequest())
        assertFalse(policy.beginEntryRequest())
        assertFalse(PipEntryGate.canRequest(true, ConnectionState.PLAYING, true, false, policy.requestPending))
    }

    @Test fun ordinaryStopCleansUpAndConfirmedPipPreservesPlayback() {
        val policy = PipLifecyclePolicy()
        assertEquals(PipStopDecision.CLEANUP_NOW, policy.onStop(platformReportsPip = false))
        assertEquals(PipStopDecision.PRESERVE_PLAYBACK, policy.onStop(platformReportsPip = true))
    }

    @Test fun requestedPipDefersOnlyUntilPlatformOutcomeIsKnown() {
        val policy = PipLifecyclePolicy()
        policy.beginEntryRequest()
        assertEquals(PipStopDecision.DEFER_CLEANUP, policy.onStop(platformReportsPip = false))
        assertEquals(PipStopDecision.PRESERVE_PLAYBACK, policy.afterDeferredStop(platformReportsPip = true))

        policy.beginEntryRequest()
        assertEquals(PipStopDecision.DEFER_CLEANUP, policy.onStop(platformReportsPip = false))
        assertEquals(PipStopDecision.CLEANUP_NOW, policy.afterDeferredStop(platformReportsPip = false))
    }

    @Test fun rejectedOrDismissedRequestRestoresNormalCleanup() {
        val policy = PipLifecyclePolicy()
        policy.beginEntryRequest()
        policy.entryRequestRejected()
        assertEquals(PipStopDecision.CLEANUP_NOW, policy.onStop(platformReportsPip = false))

        policy.beginEntryRequest()
        policy.onPipModeChanged()
        assertEquals(PipStopDecision.CLEANUP_NOW, policy.onDestroy())
    }
}
