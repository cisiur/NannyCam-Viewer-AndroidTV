package com.cisiur.nannycamviewer.playback

/** Pure attempt-scoped state for the one-time wait from player creation to its first frame. */
class FirstFrameStartupTimeout {
    private var activeAttemptId: Long? = null

    fun arm(attemptId: Long) {
        activeAttemptId = attemptId
    }

    fun firstFrameRendered(attemptId: Long): Boolean {
        if (activeAttemptId != attemptId) return false
        activeAttemptId = null
        return true
    }

    fun expire(attemptId: Long): Boolean {
        if (activeAttemptId != attemptId) return false
        activeAttemptId = null
        return true
    }

    fun cancel() {
        activeAttemptId = null
    }

    fun isArmedFor(attemptId: Long): Boolean = activeAttemptId == attemptId
}
