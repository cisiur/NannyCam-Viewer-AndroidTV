package com.cisiur.nannycamviewer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class RtspKeepaliveTest {
    @Test
    fun `parses session IDs with optional timeout and case spacing variations`() {
        assertEquals(RtspSessionInfo("abc123", null), RtspSessionHeaderParser.parse("abc123"))
        assertEquals(RtspSessionInfo("abc123", 60), RtspSessionHeaderParser.parse("abc123;timeout=60"))
        assertEquals(RtspSessionInfo("abc123", 60), RtspSessionHeaderParser.parse("abc123; Timeout = 60"))
        assertEquals(RtspSessionInfo("abc123", null), RtspSessionHeaderParser.parse("abc123;timeout=bad"))
        assertNull(RtspSessionHeaderParser.parse(" ;timeout=60"))
    }

    @Test
    fun `keepalive interval uses half advertised timeout with safe bounds and fallback`() {
        assertEquals(30_000L, RtspKeepalivePolicy.intervalMs(60))
        assertEquals(5_000L, RtspKeepalivePolicy.intervalMs(10))
        assertEquals(25_000L, RtspKeepalivePolicy.intervalMs(null))
        assertEquals(25_000L, RtspKeepalivePolicy.intervalMs(1))
    }

    @Test
    fun `only one keepalive can be outstanding and matching response clears it`() {
        val state = RtspKeepaliveState()
        assertEquals(30_000L, state.start(nowMs = 1_000, advertisedTimeoutSeconds = 60))
        assertFalse(state.isDue(30_999))
        assertTrue(state.isDue(31_000))
        state.requestSent(cSeq = 7, nowMs = 31_000)
        assertEquals(7, state.outstandingRequestCSeq())
        assertFalse(state.isDue(90_000))
        assertFalse(state.acknowledge(8))
        assertTrue(state.acknowledge(7))
        assertNull(state.outstandingRequestCSeq())
        assertTrue(state.isDue(61_000))
    }

    @Test
    fun `response timeout and reset clear pending keepalive state`() {
        val state = RtspKeepaliveState()
        state.start(nowMs = 0, advertisedTimeoutSeconds = null)
        state.requestSent(cSeq = 9, nowMs = 25_000)
        assertFalse(state.responseTimedOut(29_999))
        assertTrue(state.responseTimedOut(30_000))
        state.reset()
        assertNull(state.outstandingRequestCSeq())
        assertFalse(state.isDue(Long.MAX_VALUE - 1))
    }

    @Test
    fun `GET_PARAMETER request contains the expected CSeq Session and URI`() {
        val request = RtspRequestBuilder.build(
            method = "GET_PARAMETER",
            uri = "rtsp://camera.local:554/streamtype=0",
            cSeq = 12,
            headers = mapOf("Session" to "abc123")
        )

        assertTrue(request.startsWith("GET_PARAMETER rtsp://camera.local:554/streamtype=0 RTSP/1.0\r\n"))
        assertTrue(request.contains("CSeq: 12\r\n"))
        assertTrue(request.contains("Session: abc123\r\n"))
        assertTrue(request.endsWith("\r\n\r\n"))
    }

    @Test
    fun `interleaved RTP remains parseable while keepalive response is pending`() {
        val state = RtspKeepaliveState()
        state.start(nowMs = 0, advertisedTimeoutSeconds = 60)
        state.requestSent(cSeq = 4, nowMs = 30_000)
        val response = "RTSP/1.0 200 OK\r\nCSeq: 4\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)
        val frame = byteArrayOf('$'.code.toByte(), 0, 0, 2, 1, 2)

        val events = InterleavedRtspParser().feed(frame + response)

        assertTrue(events[0] is InterleavedRtspParser.Event.InterleavedFrame)
        val message = events[1] as InterleavedRtspParser.Event.Message
        assertTrue(state.acknowledge(message.header("CSeq")!!.toInt()))
        assertNull(state.outstandingRequestCSeq())
    }
}
