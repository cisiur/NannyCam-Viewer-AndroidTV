package com.cisiur.nannycamviewer.customvideo

import org.junit.Assert.*
import org.junit.Test

class H264AccessUnitAssemblerTest {
    @Test
    fun `same timestamp NALs produce one marker closed Annex-B IDR access unit`() {
        val assembler = H264AccessUnitAssembler()
        assertNull(assembler.accept(part(90, false, 0x09)))
        assertNull(assembler.accept(part(90, false, 0x06)))
        val unit = assembler.accept(part(90, true, 0x65))!!

        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 9, 0, 0, 0, 1, 6, 0, 0, 0, 1, 0x65), unit.annexB)
        assertEquals(90L, unit.timestamp)
        assertTrue(unit.isIdr)
    }

    @Test
    fun `timestamp change discards incomplete unit rather than joining pictures`() {
        val assembler = H264AccessUnitAssembler()
        assembler.accept(part(0, false, 0x65))
        val unit = assembler.accept(part(90, true, 0x61))!!
        assertArrayEquals(H264AccessUnitAssembler.annexB(byteArrayOf(0x61)), unit.annexB)
        assertFalse(unit.isIdr)
        assertEquals(1L, assembler.discardedUnits)
    }

    @Test
    fun `damaged units config only and oversized data are not queued`() {
        val assembler = H264AccessUnitAssembler(maxBytes = 8)
        assertNull(assembler.accept(part(0, true, 0x65).copy(damaged = true)))
        assertNull(assembler.accept(part(90, true, 0x67)))
        assertNull(assembler.accept(DepacketizedVideo(180, true, listOf(ByteArray(9) { 0x65 }), false)))
    }

    @Test
    fun `timestamp conversion uses 90kHz and handles wrap monotonically`() {
        val converter = RtpVideoTimestampConverter()
        assertEquals(0L, converter.toPresentationTimeUs(0xfffffff0L))
        assertEquals(100_000L, converter.toPresentationTimeUs((0xfffffff0L + 9000) and 0xffffffffL))
        assertEquals(200_000L, converter.toPresentationTimeUs((0xfffffff0L + 18000) and 0xffffffffL))
        assertEquals(200_001L, converter.toPresentationTimeUs((0xfffffff0L + 18000) and 0xffffffffL))
    }

    private fun part(timestamp: Long, marker: Boolean, type: Int) =
        DepacketizedVideo(timestamp, marker, listOf(byteArrayOf(type.toByte())), false)
}
