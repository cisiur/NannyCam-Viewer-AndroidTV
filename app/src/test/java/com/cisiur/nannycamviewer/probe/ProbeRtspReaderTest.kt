package com.cisiur.nannycamviewer.probe

import com.cisiur.nannycamviewer.audio.InterleavedRtspParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream

class ProbeRtspReaderTest {
    @Test
    fun `retains frame following PLAY response in the same TCP read`() {
        val reader = ProbeRtspReader(ByteArrayInputStream(
            "RTSP/1.0 200 OK\r\nCSeq: 3\r\n\r\n".toByteArray() +
                byteArrayOf('$'.code.toByte(), 2, 0, 1, 0x67)
        ))

        assertEquals("3", (reader.readEvent() as InterleavedRtspParser.Event.Message).header("cseq"))
        val frame = reader.readEvent() as InterleavedRtspParser.Event.InterleavedFrame
        assertEquals(2, frame.channel)
        assertEquals(0x67.toByte(), frame.payload.single())
    }

    @Test
    fun `partial data returns control so caller can check capture deadline`() {
        val reader = ProbeRtspReader(object : ByteArrayInputStream(byteArrayOf('$'.code.toByte(), 2, 0, 1, 0x67)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = super.read(bytes, offset, 1)
        })

        repeat(4) { assertNull(reader.readEvent()) }
        assertEquals(2, (reader.readEvent() as InterleavedRtspParser.Event.InterleavedFrame).channel)
    }
}
