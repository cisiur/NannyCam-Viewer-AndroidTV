package com.cisiur.nannycamviewer.customvideo

import org.junit.Assert.*
import org.junit.Test

class H264RtpDepacketizerTest {
    @Test
    fun `reassembles IDR FU-A start middle end and reconstructs NAL header`() {
        val parser = H264RtpDepacketizer()
        assertTrue(parser.accept(packet(1, 90, false, 0x7c, 0x85, 1, 2)).nals.isEmpty())
        assertTrue(parser.accept(packet(2, 90, false, 0x7c, 0x05, 3)).nals.isEmpty())
        val end = parser.accept(packet(3, 90, true, 0x7c, 0x45, 4))

        assertArrayEquals(byteArrayOf(0x65, 1, 2, 3, 4), end.nals.single())
        assertTrue(end.marker)
        assertFalse(end.damaged)
        assertEquals(1L, parser.fuAReassembled)
    }

    @Test
    fun `reassembles non IDR and preserves NRI bits`() {
        val parser = H264RtpDepacketizer()
        parser.accept(packet(1, 0, false, 0x5c, 0x81, 1))
        val result = parser.accept(packet(2, 0, true, 0x5c, 0x41, 2))
        assertArrayEquals(byteArrayOf(0x41, 1, 2), result.nals.single())
    }

    @Test
    fun `single SPS PPS IDR and non IDR remain complete NALs`() {
        val parser = H264RtpDepacketizer()
        listOf(0x67, 0x68, 0x65, 0x61).forEachIndexed { index, type ->
            val result = parser.accept(packet(index, index.toLong(), true, type, 1))
            assertArrayEquals(byteArrayOf(type.toByte(), 1), result.nals.single())
            assertFalse(result.damaged)
        }
    }

    @Test
    fun `middle or end without start is discarded`() {
        val parser = H264RtpDepacketizer()
        assertTrue(parser.accept(packet(1, 0, false, 0x7c, 0x05, 1)).damaged)
        val result = parser.accept(packet(2, 0, true, 0x7c, 0x45, 2))
        assertTrue(result.damaged)
        assertTrue(result.nals.isEmpty())
    }

    @Test
    fun `duplicate start marks the access unit damaged`() {
        val parser = H264RtpDepacketizer()
        parser.accept(packet(1, 0, false, 0x7c, 0x85, 1))
        assertTrue(parser.accept(packet(2, 0, false, 0x7c, 0x85, 2)).damaged)
    }

    @Test
    fun `sequence discontinuity discards fragmented NAL and a new start can recover`() {
        val parser = H264RtpDepacketizer()
        parser.accept(packet(1, 0, false, 0x7c, 0x85, 1))
        assertTrue(parser.accept(packet(3, 0, true, 0x7c, 0x45, 2)).nals.isEmpty())
        parser.accept(packet(4, 90, false, 0x7c, 0x85, 3))
        assertArrayEquals(byteArrayOf(0x65, 3, 4), parser.accept(packet(5, 90, true, 0x7c, 0x45, 4)).nals.single())
        assertEquals(1L, parser.sequenceDiscontinuities)
    }

    @Test
    fun `timestamp change in a fragmented NAL discards it`() {
        val parser = H264RtpDepacketizer()
        parser.accept(packet(1, 0, false, 0x7c, 0x85, 1))
        val result = parser.accept(packet(2, 90, true, 0x7c, 0x45, 2))
        assertTrue(result.damaged)
        assertTrue(result.nals.isEmpty())
    }

    @Test
    fun `sequence wrap is accepted`() {
        val parser = H264RtpDepacketizer()
        parser.accept(packet(65535, 0, false, 0x7c, 0x85, 1))
        assertFalse(parser.accept(packet(0, 0, true, 0x7c, 0x45, 2)).damaged)
    }

    @Test
    fun `rejects empty short reserved invalid start-end and oversized FU-A`() {
        listOf(intArrayOf(), intArrayOf(0x7c), intArrayOf(0x7c, 0x85),
            intArrayOf(0x7c, 0xc5, 1), intArrayOf(0x7c, 0xa5, 1), intArrayOf(0xfc, 0x85, 1)
        ).forEach { bytes ->
            assertTrue(H264RtpDepacketizer().accept(packet(1, 0, true, *bytes)).damaged)
        }
        val bounded = H264RtpDepacketizer(maxNalBytes = 3)
        bounded.accept(packet(1, 0, false, 0x7c, 0x85, 1, 2))
        assertTrue(bounded.accept(packet(2, 0, true, 0x7c, 0x45, 3)).damaged)
    }

    @Test
    fun `extracts STAP-A parameter sets and multiple slices safely`() {
        val parser = H264RtpDepacketizer()
        val config = parser.accept(packet(1, 0, false, 0x78, 0, 2, 0x67, 1, 0, 2, 0x68, 2))
        assertEquals(2, config.nals.size)
        assertArrayEquals(byteArrayOf(0x67, 1), config.nals[0])
        assertArrayEquals(byteArrayOf(0x68, 2), config.nals[1])
        val slices = parser.accept(packet(2, 0, true, 0x78, 0, 2, 0x65, 1, 0, 2, 0x65, 2))
        assertEquals(2, slices.nals.size)
        assertTrue(parser.accept(packet(3, 90, true, 0x78, 0, 4, 0x65)).damaged)
    }

    @Test
    fun `video parser retains timestamp sequence marker and validated RTP payload`() {
        val raw = byteArrayOf(0x80.toByte(), 0xe0.toByte(), 0x12, 0x34, 0, 0, 0x23, 0x28, 0, 0, 0, 1, 0x65)
        val (pt, packet) = VideoRtpParser.parse(raw)!!
        assertEquals(96, pt)
        assertEquals(0x1234, packet.sequence)
        assertEquals(9000L, packet.timestamp)
        assertTrue(packet.marker)
        assertArrayEquals(byteArrayOf(0x65), packet.payload)
        assertNull(VideoRtpParser.parse(byteArrayOf(1, 2)))
    }

    @Test
    fun `selects negotiated video channel and PT while ignoring channel four completely`() {
        val video = byteArrayOf(0x80.toByte(), 96, 0, 1, 0, 0, 0, 1, 0, 0, 0, 1, 0x65)
        assertNotNull(VideoRtpParser.select(2, 2, 96, video).packet)
        assertNull(VideoRtpParser.select(0, 2, 96, video).packet)
        assertNull(VideoRtpParser.select(2, 2, 96, video.copyOf().also { it[1] = 8 }).packet)
        val ignoredAudio = VideoRtpParser.select(4, 4, 96, byteArrayOf(1))
        assertNull(ignoredAudio.packet)
        assertFalse(ignoredAudio.malformed)
        assertTrue(VideoRtpParser.select(2, 2, 96, byteArrayOf(1)).malformed)
    }

    private fun packet(seq: Int, ts: Long, marker: Boolean, vararg bytes: Int) =
        VideoRtpPacket(seq, ts, marker, bytes.map(Int::toByte).toByteArray())
}
