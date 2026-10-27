package com.cisiur.nannycamviewer.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeTransportParserTest {
    @Test
    fun `parses requested and server overridden interleaved pairs`() {
        listOf(0 to 1, 2 to 3, 4 to 5, 10 to 11).forEach { (rtp, rtcp) ->
            val header = "RTP/AVP/TCP;unicast;interleaved=$rtp-$rtcp"
            val transport = ProbeTransportParser.parse(header)

            assertEquals(header, transport.originalHeader)
            assertEquals(rtp, transport.rtpChannel)
            assertEquals(rtcp, transport.rtcpChannel)
            assertTrue(transport.negotiationConfirmed)
        }
    }

    @Test
    fun `supports case spacing and extra parameters`() {
        val transport = ProbeTransportParser.parse(
            "rtp/avp/tcp;unicast; SSRC=1234; InTeRlEaVeD = 10 - 11 ;mode=PLAY"
        )

        assertEquals(10, transport.rtpChannel)
        assertEquals(11, transport.rtcpChannel)
        assertTrue(transport.negotiationConfirmed)
    }

    @Test
    fun `missing or malformed interleaved pair uses unconfirmed fallback`() {
        listOf(null, "RTP/AVP/TCP;unicast", "interleaved=bad", "interleaved=2",
            "interleaved=2-3junk", "interleaved=-1-2", "interleaved=256-257", "interleaved=2-2"
        ).forEach { header ->
            val transport = ProbeTransportParser.parse(header)

            assertEquals(header, transport.originalHeader)
            assertEquals(0, transport.rtpChannel)
            assertEquals(1, transport.rtcpChannel)
            assertFalse(transport.negotiationConfirmed)
        }
    }
}
