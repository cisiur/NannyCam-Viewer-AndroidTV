package com.cisiur.nannycamviewer.customvideo

import com.cisiur.nannycamviewer.audio.RtpPacketParser
import java.io.ByteArrayOutputStream

data class VideoRtpPacket(val sequence: Int, val timestamp: Long, val marker: Boolean, val payload: ByteArray)

object VideoRtpParser {
    data class Selection(val packet: VideoRtpPacket? = null, val malformed: Boolean = false)

    fun select(channel: Int, videoChannel: Int, expectedPayloadType: Int, bytes: ByteArray): Selection {
        if (channel == 4 || channel != videoChannel) return Selection()
        val parsed = parse(bytes) ?: return Selection(malformed = true)
        return if (parsed.first == expectedPayloadType) Selection(parsed.second) else Selection()
    }

    fun parse(bytes: ByteArray): Pair<Int, VideoRtpPacket>? {
        val packet = RtpPacketParser.parse(bytes) ?: return null
        return packet.payloadType to VideoRtpPacket(
            packet.sequenceNumber, packet.timestamp, bytes[1].toInt() and 0x80 != 0, packet.payload
        )
    }
}

data class DepacketizedVideo(
    val timestamp: Long,
    val marker: Boolean,
    val nals: List<ByteArray>,
    val damaged: Boolean
)

/** Non-interleaved H.264 packetization mode 1, bounded to one fragmented NAL. */
class H264RtpDepacketizer(private val maxNalBytes: Int = MAX_NAL_BYTES) {
    private var lastSequence: Int? = null
    private var fragment: ByteArrayOutputStream? = null
    private var fragmentTimestamp = 0L
    private var fragmentHeader = 0
    var fuAReassembled = 0L
        private set
    var singleNal = 0L
        private set
    var droppedPackets = 0L
        private set
    var sequenceDiscontinuities = 0L
        private set

    fun accept(packet: VideoRtpPacket): DepacketizedVideo {
        var damaged = false
        val previous = lastSequence
        if (previous != null && packet.sequence != ((previous + 1) and 0xffff)) {
            sequenceDiscontinuities++
            fragment = null
            damaged = true
            val delta = (packet.sequence - previous) and 0xffff
            if (delta == 0 || delta >= 0x8000) return rejected(packet)
        }
        lastSequence = packet.sequence
        if (fragment != null && fragmentTimestamp != packet.timestamp) {
            fragment = null
            damaged = true
        }
        val payload = packet.payload
        if (payload.isEmpty() || payload[0].toInt() and 0x80 != 0) return rejected(packet)
        val type = payload[0].toInt() and 0x1f
        val nals: List<ByteArray>
        when (type) {
            in 1..23 -> {
                if (fragment != null) damaged = true
                fragment = null
                if (payload.size > maxNalBytes) return rejected(packet)
                singleNal++
                nals = listOf(payload.copyOf())
            }
            24 -> {
                if (fragment != null) damaged = true
                fragment = null
                nals = parseStapA(payload) ?: return rejected(packet)
            }
            28 -> {
                if (payload.size < 3) return rejected(packet)
                val header = payload[1].toInt() and 0xff
                val start = header and 0x80 != 0
                val end = header and 0x40 != 0
                val nalHeader = (payload[0].toInt() and 0xe0) or (header and 0x1f)
                if (header and 0x20 != 0 || (header and 0x1f) !in 1..23 || start && end) return rejected(packet)
                if (start) {
                    if (fragment != null) damaged = true
                    fragment = ByteArrayOutputStream().apply { write(nalHeader) }
                    fragmentTimestamp = packet.timestamp
                    fragmentHeader = nalHeader
                } else if (fragment == null || fragmentHeader != nalHeader) {
                    return rejected(packet)
                }
                val active = fragment ?: return rejected(packet)
                if (active.size() + payload.size - 2 > maxNalBytes) return rejected(packet)
                active.write(payload, 2, payload.size - 2)
                if (end) {
                    nals = listOf(active.toByteArray())
                    fragment = null
                    fuAReassembled++
                } else {
                    if (packet.marker) return rejected(packet)
                    nals = emptyList()
                }
            }
            else -> return rejected(packet)
        }
        if (damaged) droppedPackets++
        return DepacketizedVideo(packet.timestamp, packet.marker, nals, damaged)
    }

    fun reset() {
        fragment = null
        lastSequence = null
    }

    private fun rejected(packet: VideoRtpPacket): DepacketizedVideo {
        fragment = null
        droppedPackets++
        return DepacketizedVideo(packet.timestamp, packet.marker, emptyList(), damaged = true)
    }

    private fun parseStapA(payload: ByteArray): List<ByteArray>? {
        val result = mutableListOf<ByteArray>()
        var offset = 1
        var total = 0
        while (offset < payload.size) {
            if (offset + 2 > payload.size) return null
            val length = ((payload[offset].toInt() and 0xff) shl 8) or (payload[offset + 1].toInt() and 0xff)
            offset += 2
            if (length == 0 || length > maxNalBytes || offset + length > payload.size) return null
            val type = payload[offset].toInt() and 0x1f
            if (type !in 1..23 || payload[offset].toInt() and 0x80 != 0) return null
            total += length
            if (total > maxNalBytes) return null
            result += payload.copyOfRange(offset, offset + length)
            offset += length
        }
        return result.takeIf { it.isNotEmpty() }
    }

    companion object {
        const val MAX_NAL_BYTES = 1024 * 1024
    }
}
