package com.cisiur.nannycamviewer.playback

/** Developer-facing A/B mode for player creation. It never reconfigures an active player. */
enum class VideoBufferingMode(val label: String) {
    DEFAULT("Default"),
    LOW_LATENCY("Low Latency");

    fun toggled(): VideoBufferingMode = when (this) {
        DEFAULT -> LOW_LATENCY
        LOW_LATENCY -> DEFAULT
    }
}

data class VideoBufferDurations(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int
) {
    fun isValid(): Boolean =
        minBufferMs <= maxBufferMs &&
            bufferForPlaybackMs <= minBufferMs &&
            bufferForPlaybackAfterRebufferMs <= minBufferMs
}

/** Pure representation of the bounded live-stream buffering experiment. */
object VideoBufferingPolicy {
    val lowLatencyDurations = VideoBufferDurations(
        minBufferMs = 500,
        maxBufferMs = 1_500,
        bufferForPlaybackMs = 100,
        bufferForPlaybackAfterRebufferMs = 250
    )

    /** Null deliberately means use Media3's unmodified default LoadControl. */
    fun durationsFor(mode: VideoBufferingMode): VideoBufferDurations? = when (mode) {
        VideoBufferingMode.DEFAULT -> null
        VideoBufferingMode.LOW_LATENCY -> lowLatencyDurations
    }
}
