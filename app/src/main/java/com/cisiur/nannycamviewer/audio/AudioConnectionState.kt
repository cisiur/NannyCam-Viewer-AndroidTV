package com.cisiur.nannycamviewer.audio

/** State owned by the custom audio session only; it never changes the video state. */
enum class AudioConnectionState {
    STOPPED,
    CONNECTING,
    PLAYING,
    RECONNECTING,
    ERROR
}

data class AudioDiagnostics(
    val controlUri: String? = null,
    val payloadType: Int? = null,
    val packetsReceived: Long = 0,
    val payloadBytesReceived: Long = 0,
    val latestSequenceNumber: Int? = null,
    val sequenceDiscontinuities: Long = 0,
    val duplicateOrOutOfOrderPackets: Long = 0,
    val decodedSamples: Long = 0,
    val bufferedDurationMs: Long = 0,
    val staleDroppedBytes: Long = 0,
    val reconnectCount: Int = 0,
    val muted: Boolean = false,
    val processingEnabled: Boolean = true
)
