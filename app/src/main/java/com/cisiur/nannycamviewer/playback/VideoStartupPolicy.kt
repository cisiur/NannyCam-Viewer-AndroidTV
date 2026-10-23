package com.cisiur.nannycamviewer.playback

/** Attempt-scoped startup limits derived from observed successful camera startup times. */
object VideoStartupPolicy {
    const val FIRST_FRAME_TIMEOUT_MS = 6_000L
}
