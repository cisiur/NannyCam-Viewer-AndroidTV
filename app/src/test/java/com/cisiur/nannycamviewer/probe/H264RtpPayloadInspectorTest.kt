package com.cisiur.nannycamviewer.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class H264RtpPayloadInspectorTest {
    @Test
    fun `classifies single SPS PPS IDR and non IDR NAL units`() {
        assertSingleNal(0x67, 7)
        assertSingleNal(0x68, 8)
        assertSingleNal(0x65, 5)
        assertSingleNal(0x61, 1)
    }

    @Test
    fun `classifies FU-A start middle and end fragments`() {
        val start = H264RtpPayloadInspector.inspect(byteArrayOf(0x7c, 0x85.toByte(), 1)) as H264PayloadInspection.FuA
        val middle = H264RtpPayloadInspector.inspect(byteArrayOf(0x7c, 0x05, 2)) as H264PayloadInspection.FuA
        val end = H264RtpPayloadInspector.inspect(byteArrayOf(0x7c, 0x45, 3)) as H264PayloadInspection.FuA

        assertEquals(listOf(5), start.nalTypes)
        assertTrue(start.start)
        assertTrue(!start.end)
        assertTrue(!middle.start)
        assertTrue(!middle.end)
        assertTrue(end.end)
    }

    @Test
    fun `classifies STAP-A contained SPS and PPS`() {
        val inspection = H264RtpPayloadInspector.inspect(
            byteArrayOf(0x78, 0, 1, 0x67, 0, 1, 0x68)
        ) as H264PayloadInspection.StapA

        assertEquals(listOf(7, 8), inspection.nalTypes)
    }

    @Test
    fun `rejects malformed FU-A STAP-A and empty payload`() {
        assertTrue(H264RtpPayloadInspector.inspect(byteArrayOf(0x7c)) is H264PayloadInspection.Malformed)
        assertTrue(H264RtpPayloadInspector.inspect(byteArrayOf(0x78, 0, 2, 0x67)) is H264PayloadInspection.Malformed)
        assertTrue(H264RtpPayloadInspector.inspect(ByteArray(0)) is H264PayloadInspection.Malformed)
    }

    @Test
    fun `statistics reports RTP codec parameters and picture slices`() {
        val track = H264SdpTrack(
            mediaLine = "m=video 0 RTP/AVP 96",
            payloadType = 96,
            rtpMap = "H264/90000",
            fmtp = null,
            packetizationMode = null,
            spropParameterSetsPresent = false,
            parameterSets = emptyList(),
            control = "trackID=0"
        )
        val statistics = H264ProbeStatistics(track)

        statistics.observe(10, byteArrayOf(0x67))
        statistics.observe(11, byteArrayOf(0x68))
        statistics.observe(12, byteArrayOf(0x65))
        statistics.observe(13, byteArrayOf(0x61))
        val summary = statistics.summary()

        assertEquals(4, summary.rtpPackets)
        assertTrue(summary.spsInRtp)
        assertTrue(summary.ppsInRtp)
        assertTrue(summary.idrInRtp)
        assertTrue(summary.nonIdrInRtp)
        assertEquals(mapOf(1 to 1, 5 to 1, 7 to 1, 8 to 1), summary.nalTypeCounts)
        assertEquals(10, summary.firstSequenceNumber)
        assertEquals(13, summary.lastSequenceNumber)
    }

    private fun assertSingleNal(firstByte: Int, expectedType: Int) {
        val inspection = H264RtpPayloadInspector.inspect(byteArrayOf(firstByte.toByte(), 0)) as H264PayloadInspection.SingleNal
        assertEquals(listOf(expectedType), inspection.nalTypes)
    }
}
