package com.cisiur.nannycamviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RecoverySessionPolicyTest {
    @Test fun validSavedEndpointCanBeRecoveredBeforeAnyExplicitDisconnect() {
        val policy = RecoverySessionPolicy()
        assertEquals(
            RecoveryDecision.START,
            policy.decide(endpointIsValid = true, videoBusy = false, probeRunning = false)
        )
    }

    @Test fun missingOrInvalidSavedEndpointIsRejected() {
        val policy = RecoverySessionPolicy()
        assertEquals(
            RecoveryDecision.REJECT_INVALID_ENDPOINT,
            policy.decide(endpointIsValid = false, videoBusy = false, probeRunning = false)
        )
    }

    @Test fun disconnectedSessionIsRestoredAfterLifecycleCleanup() {
        val policy = RecoverySessionPolicy()
        policy.onUserConnectAccepted()
        policy.onLifecycleCleanup()
        assertEquals(
            RecoveryDecision.START,
            policy.decide(endpointIsValid = true, videoBusy = false, probeRunning = false)
        )
    }

    @Test fun activeOrConnectingSessionIgnoresRepeatedRecoveryRequests() {
        val policy = RecoverySessionPolicy()
        policy.onUserConnectAccepted()
        assertEquals(
            RecoveryDecision.IGNORE_ACTIVE_SESSION,
            policy.decide(endpointIsValid = true, videoBusy = true, probeRunning = false)
        )
        assertEquals(
            RecoveryDecision.IGNORE_ACTIVE_SESSION,
            policy.decide(endpointIsValid = true, videoBusy = false, probeRunning = true)
        )
    }

    @Test fun explicitDisconnectSuppressesRecoveryButLifecycleCleanupDoesNot() {
        val policy = RecoverySessionPolicy()
        policy.onUserConnectAccepted()
        policy.onExplicitDisconnect()
        policy.onLifecycleCleanup()
        assertEquals(
            RecoveryDecision.IGNORE_EXPLICIT_DISCONNECT,
            policy.decide(endpointIsValid = true, videoBusy = false, probeRunning = false)
        )
    }
}
