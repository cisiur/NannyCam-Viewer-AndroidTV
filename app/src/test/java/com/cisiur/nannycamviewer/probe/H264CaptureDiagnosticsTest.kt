package com.cisiur.nannycamviewer.probe

import com.cisiur.nannycamviewer.audio.InterleavedRtspParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class H264CaptureDiagnosticsTest {
    @Test
    fun `requested channel selects expected H264`() {
        val diagnostics = diagnostics("interleaved=0-1")
        diagnostics.observe(frame(0, 96, 100, byteArrayOf(0x67)))

        val result = diagnostics.result()
        assertEquals(H264ProbeOutcome.H264_RTP_DETECTED, result.outcome)
        assertEquals(1, result.h264.rtpPackets)
        assertTrue(result.h264.spsInRtp)
    }

    @Test
    fun `server override selects channel two and preserves unexpected channel evidence`() {
        val diagnostics = diagnostics("RTP/AVP/TCP;interleaved=2-3")
        diagnostics.observe(frame(0, 96, 10, byteArrayOf(0x67)))
        diagnostics.observe(frame(2, 96, 11, byteArrayOf(0x68)))

        val result = diagnostics.result()
        assertEquals(2, result.transport.rtpChannel)
        assertEquals(3, result.transport.rtcpChannel)
        assertEquals(mapOf(0 to 1L, 2 to 1L), result.observedInterleavedChannels)
        assertEquals(mapOf(96 to 2L), result.observedPayloadTypes)
        assertEquals(1, result.h264.rtpPackets)
        assertEquals(mapOf(8 to 1), result.h264.nalTypeCounts)
        assertEquals(11, result.h264.firstSequenceNumber)
        assertEquals(10, result.frameSamples.first().sequenceNumber)
        assertEquals(1, result.frameSamples.first().payloadBytes)
    }

    @Test
    fun `unexpected PT is counted without reaching H264 inspection`() {
        val diagnostics = diagnostics("interleaved=2-3")
        // Empty wrong-PT payload would be malformed if incorrectly inspected as H.264.
        diagnostics.observe(frame(2, 8, 10, byteArrayOf()))
        diagnostics.observe(frame(2, 96, 11, byteArrayOf(0x65)))

        val result = diagnostics.result()
        assertEquals(mapOf(8 to 1L, 96 to 1L), result.observedPayloadTypes)
        assertEquals(mapOf(2 to mapOf(8 to 1L, 96 to 1L)), result.channelPayloadTypes)
        assertEquals(1, result.h264.rtpPackets)
        assertEquals(0, result.h264.malformedOrUnsupportedPayloads)
        assertTrue(result.h264.idrInRtp)
    }

    @Test
    fun `RTCP channel and rejected RTP frames never pollute H264 malformed counts`() {
        val diagnostics = diagnostics("interleaved=2-3")
        diagnostics.observe(InterleavedRtspParser.Event.InterleavedFrame(3, byteArrayOf(0x80.toByte(), 201.toByte(), 0, 1, 0, 0, 0, 1)))
        // Longer RTCP-like data could pass the generic RTP header parser; channel still wins.
        diagnostics.observe(InterleavedRtspParser.Event.InterleavedFrame(3, ByteArray(28).also {
            it[0] = 0x80.toByte(); it[1] = 200.toByte(); it[3] = 6
        }))
        diagnostics.observe(InterleavedRtspParser.Event.InterleavedFrame(2, byteArrayOf(0)))

        val result = diagnostics.result()
        assertEquals(mapOf(2 to 1L, 3 to 2L), result.observedInterleavedChannels)
        assertEquals(3L, result.nonRtpOrMalformedFrames)
        assertEquals(2L, result.rtcpChannelFrames)
        assertTrue(result.observedPayloadTypes.isEmpty())
        assertEquals(0, result.h264.malformedOrUnsupportedPayloads)
    }

    @Test
    fun `unexpected RTP on RTCP channel is still observed before selection`() {
        val diagnostics = diagnostics("interleaved=2-3")
        diagnostics.observe(frame(3, 96, 90, byteArrayOf(0x65)))

        val result = diagnostics.result()
        assertEquals(mapOf(3 to 1L), result.observedInterleavedChannels)
        assertEquals(mapOf(96 to 1L), result.observedPayloadTypes)
        assertEquals(0, result.h264.rtpPackets)
        assertEquals(0L, result.nonRtpOrMalformedFrames)
    }

    @Test
    fun `zero selected H264 retains channel and payload summary`() {
        val diagnostics = diagnostics("interleaved=2-3")
        diagnostics.observe(frame(2, 8, 50, byteArrayOf(1, 2)))

        val result = diagnostics.result()
        assertEquals(H264ProbeOutcome.NO_H264_RTP, result.outcome)
        assertEquals(96, result.expectedPayloadType)
        assertEquals(0, result.h264.rtpPackets)
        assertEquals(mapOf(2 to 1L), result.observedInterleavedChannels)
        assertEquals(mapOf(8 to 1L), result.observedPayloadTypes)
    }

    @Test
    fun `no frames has explicit no interleaved data outcome`() {
        assertEquals(H264ProbeOutcome.NO_INTERLEAVED_DATA, diagnostics(null).result().outcome)
    }

    @Test
    fun `metadata sampling stays bounded while all packets are counted`() {
        val diagnostics = diagnostics("interleaved=2-3")
        repeat(100) { diagnostics.observe(frame(4, 8, it, byteArrayOf(0))) }

        val result = diagnostics.result()
        assertEquals(H264CaptureDiagnostics.MAX_FRAME_SAMPLES, result.frameSamples.size)
        assertEquals(mapOf(4 to 100L), result.observedInterleavedChannels)
        assertEquals(mapOf(8 to 100L), result.observedPayloadTypes)
    }

    private fun diagnostics(transport: String?): H264CaptureDiagnostics = H264CaptureDiagnostics(
        H264SdpParser.findVideoTrack("m=video 0 RTP/AVP 96\na=rtpmap:96 H264/90000\na=control:trackID=0")!!,
        ProbeTransportParser.parse(transport)
    )

    private fun frame(channel: Int, pt: Int, sequence: Int, payload: ByteArray) =
        InterleavedRtspParser.Event.InterleavedFrame(channel, byteArrayOf(
            0x80.toByte(), pt.toByte(), (sequence ushr 8).toByte(), sequence.toByte(),
            0, 0, 0, 1, 0, 0, 0, 1
        ) + payload)
}
