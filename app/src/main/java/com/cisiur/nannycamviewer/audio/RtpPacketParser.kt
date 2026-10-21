package com.cisiur.nannycamviewer.audio

data class RtpPacket(
    val payloadType: Int,
    val sequenceNumber: Int,
    val timestamp: Long,
    val ssrc: Long,
    val payload: ByteArray
)

/** Validates variable RTP headers before exposing only the media payload. */
object RtpPacketParser {
    fun parse(data: ByteArray): RtpPacket? {
        if (data.size < FIXED_HEADER_BYTES) return null
        val first = data[0].toInt() and 0xff
        if ((first ushr 6) != RTP_VERSION) return null
        val hasPadding = first and 0x20 != 0
        val hasExtension = first and 0x10 != 0
        val csrcCount = first and 0x0f
        var payloadOffset = FIXED_HEADER_BYTES + csrcCount * CSRC_BYTES
        if (payloadOffset > data.size) return null
        if (hasExtension) {
            if (payloadOffset + EXTENSION_HEADER_BYTES > data.size) return null
            val extensionWords = unsignedShort(data[payloadOffset + 2], data[payloadOffset + 3])
            payloadOffset += EXTENSION_HEADER_BYTES + extensionWords * WORD_BYTES
            if (payloadOffset > data.size) return null
        }
        var payloadEnd = data.size
        if (hasPadding) {
            val paddingBytes = data.last().toInt() and 0xff
            if (paddingBytes == 0 || paddingBytes > payloadEnd - payloadOffset) return null
            payloadEnd -= paddingBytes
        }
        val second = data[1].toInt() and 0xff
        return RtpPacket(
            payloadType = second and 0x7f,
            sequenceNumber = unsignedShort(data[2], data[3]),
            timestamp = unsignedInt(data, 4),
            ssrc = unsignedInt(data, 8),
            payload = data.copyOfRange(payloadOffset, payloadEnd)
        )
    }

    private fun unsignedShort(high: Byte, low: Byte): Int =
        ((high.toInt() and 0xff) shl 8) or (low.toInt() and 0xff)

    private fun unsignedInt(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xff) shl 24) or
            ((data[offset + 1].toLong() and 0xff) shl 16) or
            ((data[offset + 2].toLong() and 0xff) shl 8) or
            (data[offset + 3].toLong() and 0xff)

    private const val RTP_VERSION = 2
    private const val FIXED_HEADER_BYTES = 12
    private const val CSRC_BYTES = 4
    private const val EXTENSION_HEADER_BYTES = 4
    private const val WORD_BYTES = 4
}

class RtpStatistics {
    private var lastSequence: Int? = null
    var packetsReceived: Long = 0
        private set
    var payloadBytesReceived: Long = 0
        private set
    var sequenceDiscontinuities: Long = 0
        private set
    var duplicateOrOutOfOrderPackets: Long = 0
        private set

    fun observe(packet: RtpPacket) {
        val previous = lastSequence
        if (previous != null) {
            val delta = (packet.sequenceNumber - previous + SEQUENCE_MODULUS) % SEQUENCE_MODULUS
            when {
                delta == 0 -> duplicateOrOutOfOrderPackets++
                delta in 2..FORWARD_SEQUENCE_WINDOW -> sequenceDiscontinuities++
                delta > FORWARD_SEQUENCE_WINDOW -> duplicateOrOutOfOrderPackets++
            }
            if (delta in 1..FORWARD_SEQUENCE_WINDOW) lastSequence = packet.sequenceNumber
        } else {
            lastSequence = packet.sequenceNumber
        }
        packetsReceived++
        payloadBytesReceived += packet.payload.size
    }

    fun latestSequenceNumber(): Int? = lastSequence

    private companion object {
        const val SEQUENCE_MODULUS = 65_536
        const val FORWARD_SEQUENCE_WINDOW = 32_768
    }
}
