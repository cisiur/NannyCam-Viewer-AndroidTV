package com.cisiur.nannycamviewer.customvideo

import java.io.ByteArrayOutputStream

data class H264AccessUnit(val annexB: ByteArray, val timestamp: Long, val isIdr: Boolean)

/** Only marker-closed, undamaged timestamps containing VCL slices become decoder inputs. */
class H264AccessUnitAssembler(private val maxBytes: Int = MAX_ACCESS_UNIT_BYTES) {
    private var timestamp: Long? = null
    private var data = ByteArrayOutputStream()
    private var damaged = false
    private var hasSlice = false
    private var hasIdr = false
    var discardedUnits = 0L
        private set

    fun accept(packet: DepacketizedVideo): H264AccessUnit? {
        if (timestamp != packet.timestamp) {
            if (timestamp != null) discardedUnits++ // Never flush an AU lacking its marker.
            reset()
            timestamp = packet.timestamp
        }
        damaged = damaged || packet.damaged
        for (nal in packet.nals) {
            if (nal.isEmpty() || data.size() + START_CODE.size + nal.size > maxBytes) {
                damaged = true
                data.reset()
                break
            }
            if (!damaged) {
                data.write(START_CODE)
                data.write(nal)
                val type = nal[0].toInt() and 0x1f
                hasSlice = hasSlice || type in 1..5
                hasIdr = hasIdr || type == 5
            }
        }
        if (!packet.marker) return null
        val result = if (!damaged && hasSlice) H264AccessUnit(data.toByteArray(), packet.timestamp, hasIdr) else null
        if (result == null && damaged) discardedUnits++
        reset()
        return result
    }

    fun reset() {
        timestamp = null
        data = ByteArrayOutputStream()
        damaged = false
        hasSlice = false
        hasIdr = false
    }

    companion object {
        const val MAX_ACCESS_UNIT_BYTES = 2 * 1024 * 1024
        val START_CODE: ByteArray get() = byteArrayOf(0, 0, 0, 1)
        fun annexB(nal: ByteArray): ByteArray = START_CODE + nal
    }
}

/** Unsigned RTP wrap handling, with monotonic PTS even if timestamps arrive out of order. */
class RtpVideoTimestampConverter {
    private var lastTimestamp: Long? = null
    private var ticks = 0L
    private var lastPtsUs = -1L

    fun toPresentationTimeUs(timestamp: Long): Long {
        lastTimestamp?.let { previous ->
            val delta = ((timestamp - previous + 0x80000000L) and 0xffffffffL) - 0x80000000L
            ticks += delta.coerceAtLeast(0)
        }
        lastTimestamp = timestamp
        val pts = (ticks * 1_000_000L / 90_000L).coerceAtLeast(lastPtsUs + 1)
        lastPtsUs = pts
        return pts
    }
}
