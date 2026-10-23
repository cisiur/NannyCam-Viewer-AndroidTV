package com.cisiur.nannycamviewer.audio

import java.util.Locale

data class RtspSessionInfo(val sessionId: String, val timeoutSeconds: Int?)

object RtspSessionHeaderParser {
    fun parse(value: String?): RtspSessionInfo? {
        if (value.isNullOrBlank()) return null
        val parts = value.split(';')
        val sessionId = parts.first().trim().takeIf { it.isNotEmpty() } ?: return null
        val timeoutSeconds = parts.drop(1)
            .mapNotNull { parameter ->
                val key = parameter.substringBefore('=').trim().lowercase(Locale.US)
                if (key != "timeout") return@mapNotNull null
                parameter.substringAfter('=', "").trim().toIntOrNull()?.takeIf { it > 0 }
            }
            .firstOrNull()
        return RtspSessionInfo(sessionId, timeoutSeconds)
    }
}

/** Policy for sending a session keepalive safely before the advertised RTSP timeout. */
object RtspKeepalivePolicy {
    const val FALLBACK_INTERVAL_MS = 25_000L
    const val MIN_VALID_TIMEOUT_SECONDS = 2
    const val MIN_INTERVAL_MS = 1_000L
    const val MAX_INTERVAL_MS = 30_000L
    const val RESPONSE_TIMEOUT_MS = 5_000L

    fun intervalMs(advertisedTimeoutSeconds: Int?): Long {
        if (advertisedTimeoutSeconds == null || advertisedTimeoutSeconds < MIN_VALID_TIMEOUT_SECONDS) {
            return FALLBACK_INTERVAL_MS
        }
        return (advertisedTimeoutSeconds * 1_000L / 2).coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
    }
}

/** Pure state machine ensuring one GET_PARAMETER request is outstanding at a time. */
class RtspKeepaliveState {
    private var intervalMs = RtspKeepalivePolicy.FALLBACK_INTERVAL_MS
    private var nextDueAtMs = Long.MAX_VALUE
    private var outstandingCSeq: Int? = null
    private var outstandingSinceMs = 0L

    fun start(nowMs: Long, advertisedTimeoutSeconds: Int?): Long {
        intervalMs = RtspKeepalivePolicy.intervalMs(advertisedTimeoutSeconds)
        nextDueAtMs = nowMs + intervalMs
        outstandingCSeq = null
        return intervalMs
    }

    fun isDue(nowMs: Long): Boolean = outstandingCSeq == null && nowMs >= nextDueAtMs

    fun requestSent(cSeq: Int, nowMs: Long) {
        check(outstandingCSeq == null) { "A keepalive request is already outstanding" }
        outstandingCSeq = cSeq
        outstandingSinceMs = nowMs
        nextDueAtMs = nowMs + intervalMs
    }

    fun acknowledge(cSeq: Int): Boolean {
        if (outstandingCSeq != cSeq) return false
        outstandingCSeq = null
        return true
    }

    fun responseTimedOut(nowMs: Long): Boolean =
        outstandingCSeq != null && nowMs - outstandingSinceMs >= RtspKeepalivePolicy.RESPONSE_TIMEOUT_MS

    fun outstandingRequestCSeq(): Int? = outstandingCSeq

    fun reset() {
        nextDueAtMs = Long.MAX_VALUE
        outstandingCSeq = null
        outstandingSinceMs = 0L
    }
}

object RtspRequestBuilder {
    fun build(method: String, uri: String, cSeq: Int, headers: Map<String, String>): String = buildString {
        append(method).append(' ').append(uri).append(" RTSP/1.0\r\n")
        append("CSeq: ").append(cSeq).append("\r\n")
        append("User-Agent: NannyCamViewerAndroidTV/1.0\r\n")
        headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
        append("\r\n")
    }
}
