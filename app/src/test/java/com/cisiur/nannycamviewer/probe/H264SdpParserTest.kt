package com.cisiur.nannycamviewer.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class H264SdpParserTest {
    @Test
    fun `parses H264 fmtp and parameter sets from video track`() {
        val track = H264SdpParser.findVideoTrack(
            """
            v=0
            m=audio 0 RTP/AVP 8
            a=rtpmap:8 PCMA/8000
            a=control:trackID=1
            m=video 0 RTP/AVP 96
            a=rtpmap:96 H264/90000
            a=fmtp:96 packetization-mode=1; sprop-parameter-sets=Z0IAH5WoFAFuQA==,aM4G4g==
            a=control:trackID=0
            """.trimIndent()
        )!!

        assertEquals(96, track.payloadType)
        assertEquals("H264/90000", track.rtpMap)
        assertNotNull(track.fmtp)
        assertEquals("1", track.packetizationMode)
        assertTrue(track.spropParameterSetsPresent)
        assertEquals(2, track.parameterSets.size)
        assertTrue(track.spsByteCount!! > 0)
        assertTrue(track.ppsByteCount!! > 0)
        assertEquals("trackID=0", track.control)
        assertEquals(
            "rtsp://camera.local:554/streamtype=0/trackID=0",
            H264SdpParser.resolveControlUri(
                "rtsp://camera.local:554/streamtype=0",
                "rtsp://camera.local:554/streamtype=0/",
                track.control
            )
        )
    }

    @Test
    fun `diagnoses H264 video without fmtp rather than rejecting it`() {
        val track = H264SdpParser.findVideoTrack(
            """
            v=0
            m=video 0 RTP/AVP 96
            a=rtpmap:96 H264/90000
            a=control:trackID=0
            """.trimIndent()
        )!!

        assertEquals(96, track.payloadType)
        assertNull(track.fmtp)
        assertFalse(track.spropParameterSetsPresent)
        assertNull(track.spsByteCount)
        assertNull(track.ppsByteCount)
    }

    @Test
    fun `uses absolute control URI unchanged`() {
        val resolved = H264SdpParser.resolveControlUri(
            "rtsp://camera.local:554/streamtype=0",
            null,
            "rtsp://camera.local:554/live/video"
        )

        assertEquals("rtsp://camera.local:554/live/video", resolved)
    }

    @Test
    fun `returns null for missing or non H264 video`() {
        assertNull(H264SdpParser.findVideoTrack("m=audio 0 RTP/AVP 8\na=control:trackID=1"))
        assertNull(
            H264SdpParser.findVideoTrack(
                "m=video 0 RTP/AVP 97\na=rtpmap:97 H265/90000\na=control:trackID=0"
            )
        )
        assertNull(H264SdpParser.findVideoTrack("m=video 0 RTP/AVP 96\na=rtpmap:96 H264/90000"))
    }
}
