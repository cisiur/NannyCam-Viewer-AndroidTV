package com.cisiur.nannycamviewer.ui

/** Android-free policy which preserves an explicit user Disconnect across lifecycle cleanup. */
internal class RecoverySessionPolicy {
    // No explicit user action has happened in a freshly created activity, so a saved endpoint may
    // still be recovered. Only the Disconnect control suppresses the command.
    private var explicitlyDisconnected = false

    fun onUserConnectAccepted() {
        explicitlyDisconnected = false
    }

    fun onExplicitDisconnect() {
        explicitlyDisconnected = true
    }

    /** Lifecycle release is deliberately not a user Disconnect. */
    fun onLifecycleCleanup() = Unit

    fun decide(
        endpointIsValid: Boolean,
        videoBusy: Boolean,
        probeRunning: Boolean
    ): RecoveryDecision = when {
        !endpointIsValid -> RecoveryDecision.REJECT_INVALID_ENDPOINT
        explicitlyDisconnected -> RecoveryDecision.IGNORE_EXPLICIT_DISCONNECT
        videoBusy || probeRunning -> RecoveryDecision.IGNORE_ACTIVE_SESSION
        else -> RecoveryDecision.START
    }
}

internal enum class RecoveryDecision {
    START,
    REJECT_INVALID_ENDPOINT,
    IGNORE_EXPLICIT_DISCONNECT,
    IGNORE_ACTIVE_SESSION
}
