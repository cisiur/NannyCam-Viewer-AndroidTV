package com.cisiur.nannycamviewer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class InterleavedRtspParserTest {
    @Test
    fun `parses a complete interleaved frame`() {
        val events = InterleavedRtspParser().feed(byteArrayOf('$'.code.toByte(), 0, 0, 3, 1, 2, 3))

        val frame = events.single() as InterleavedRtspParser.Event.InterleavedFrame
        assertEquals(0, frame.channel)
        assertTrue(frame.payload.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `buffers fragmented frames and parses multiple events`() {
        val parser = InterleavedRtspParser()
        assertTrue(parser.feed(byteArrayOf('$'.code.toByte(), 0, 0)).isEmpty())
        val events = parser.feed(byteArrayOf(2, 7, 8, '$'.code.toByte(), 1, 0, 1, 9))

        assertEquals(2, events.size)
        assertTrue((events[0] as InterleavedRtspParser.Event.InterleavedFrame).payload.contentEquals(byteArrayOf(7, 8)))
        assertTrue((events[1] as InterleavedRtspParser.Event.InterleavedFrame).payload.contentEquals(byteArrayOf(9)))
    }

    @Test
    fun `parses RTSP text followed by an interleaved frame`() {
        val text = "RTSP/1.0 200 OK\r\nCSeq: 3\r\nContent-Length: 2\r\n\r\nok"
        val bytes = text.toByteArray(StandardCharsets.ISO_8859_1) + byteArrayOf('$'.code.toByte(), 0, 0, 1, 4)

        val events = InterleavedRtspParser().feed(bytes)

        val response = events[0] as InterleavedRtspParser.Event.Message
        assertEquals("RTSP/1.0 200 OK", response.startLine)
        assertEquals("3", response.header("CSeq"))
        assertEquals("ok", response.body.toString(StandardCharsets.ISO_8859_1))
        assertTrue((events[1] as InterleavedRtspParser.Event.InterleavedFrame).payload.contentEquals(byteArrayOf(4)))
    }

    @Test(expected = InterleavedRtspParser.ParseException::class)
    fun `rejects oversized frames`() {
        InterleavedRtspParser(maxInterleavedFrameBytes = 4)
            .feed(byteArrayOf('$'.code.toByte(), 0, 0, 5))
    }
}
