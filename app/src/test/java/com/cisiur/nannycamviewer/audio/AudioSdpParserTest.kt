package com.cisiur.nannycamviewer.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioSdpParserTest {
    @Test
    fun `selects audio track structurally without trusting PCMA codec text`() {
        val sdp = """
            v=0
            m=video 0 RTP/AVP 96
            a=control:trackID=0
            m=audio 0 RTP/AVP 8
            a=rtpmap:8 PCMA/8000/1
            a=control:trackID=1
        """.trimIndent()

        val track = AudioSdpParser.findAudioTrack(sdp)!!

        assertEquals(8, track.payloadType)
        assertEquals("trackID=1", track.control)
        assertEquals(
            "rtsp://camera.local:554/streamtype=0/trackID=1",
            AudioSdpParser.resolveControlUri("rtsp://camera.local:554/streamtype=0", null, track.control)
        )
    }
}
