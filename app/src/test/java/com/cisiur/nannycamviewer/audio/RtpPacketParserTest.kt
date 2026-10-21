package com.cisiur.nannycamviewer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpPacketParserTest {
    @Test
    fun `parses fixed RTP header and payload`() {
        val packet = RtpPacketParser.parse(packet(sequence = 12, timestamp = 99, payload = byteArrayOf(1, 2)))!!

        assertEquals(8, packet.payloadType)
        assertEquals(12, packet.sequenceNumber)
        assertEquals(99, packet.timestamp)
        assertTrue(packet.payload.contentEquals(byteArrayOf(1, 2)))
    }

    @Test
    fun `skips CSRC and extension headers`() {
        val bytes = byteArrayOf(
            0x91.toByte(), 8, 0, 1, 0, 0, 0, 2, 0, 0, 0, 3,
            0, 0, 0, 4, // One CSRC.
            0x10, 0, 0, 1, 9, 8, 7, 6, // Extension header plus one 32-bit word.
            5, 6
        )
        val parsed = RtpPacketParser.parse(bytes)!!

        assertEquals(1, parsed.sequenceNumber)
        assertTrue(parsed.payload.contentEquals(byteArrayOf(5, 6)))
    }

    @Test
    fun `rejects invalid RTP versions and truncated packets`() {
        assertNull(RtpPacketParser.parse(ByteArray(11)))
        assertNull(RtpPacketParser.parse(byteArrayOf(0x40, 8) + ByteArray(10)))
        assertNull(RtpPacketParser.parse(byteArrayOf(0x90.toByte(), 8) + ByteArray(10)))
    }

    private fun packet(sequence: Int, timestamp: Int, payload: ByteArray): ByteArray = byteArrayOf(
        0x80.toByte(), 8,
        (sequence ushr 8).toByte(), sequence.toByte(),
        (timestamp ushr 24).toByte(), (timestamp ushr 16).toByte(), (timestamp ushr 8).toByte(), timestamp.toByte(),
        0, 0, 0, 1
    ) + payload
}
