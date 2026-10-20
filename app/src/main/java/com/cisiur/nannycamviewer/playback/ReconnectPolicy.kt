package com.cisiur.nannycamviewer.playback

/** Bounded retry scheduling state. The session owns the actual Handler callback. */
class ReconnectPolicy {
    private val retryDelaysMs = longArrayOf(1_000, 2_000, 3_000, 5_000)
    private var retryIndex = 0

    var reconnectPending: Boolean = false
        private set

    fun nextDelayMs(): Long? {
        if (reconnectPending) return null
        reconnectPending = true
        val delay = retryDelaysMs[retryIndex.coerceAtMost(retryDelaysMs.lastIndex)]
        retryIndex++
        return delay
    }

    fun retryStarted() {
        reconnectPending = false
    }

    fun reset() {
        retryIndex = 0
        reconnectPending = false
    }

    fun cancel() {
        reconnectPending = false
    }
}
