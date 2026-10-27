package com.cisiur.nannycamviewer.probe

import com.cisiur.nannycamviewer.audio.InterleavedRtspParser
import com.cisiur.nannycamviewer.audio.RtpPacketParser

enum class H264ProbeOutcome { H264_RTP_DETECTED, NO_H264_RTP, NO_INTERLEAVED_DATA }

/** Metadata only. At most eight samples are retained; no RTP payload is stored. */
data class ProbeFrameMetadata(
    val channel: Int,
    val frameBytes: Int,
    val payloadType: Int?,
    val sequenceNumber: Int?,
    val payloadBytes: Int?,
    val selectedH264: Boolean,
    val isRtcpChannel: Boolean
)

data class H264ProbeResult(
    val expectedPayloadType: Int,
    val transport: ProbeTransport,
    val observedInterleavedChannels: Map<Int, Long>,
    val observedPayloadTypes: Map<Int, Long>,
    val channelPayloadTypes: Map<Int, Map<Int, Long>>,
    val nonRtpOrMalformedFrames: Long,
    val rtcpChannelFrames: Long,
    val frameSamples: List<ProbeFrameMetadata>,
    val h264: H264ProbeSummary
) {
    val outcome: H264ProbeOutcome
        get() = when {
            h264.rtpPackets > 0 -> H264ProbeOutcome.H264_RTP_DETECTED
            observedInterleavedChannels.isEmpty() -> H264ProbeOutcome.NO_INTERLEAVED_DATA
            else -> H264ProbeOutcome.NO_H264_RTP
        }
}

/** Counts transport evidence before applying the negotiated channel and H.264 PT filters. */
class H264CaptureDiagnostics(private val track: H264SdpTrack, private val transport: ProbeTransport) {
    private val h264 = H264ProbeStatistics(track)
    private val channels = sortedMapOf<Int, Long>()
    private val payloadTypes = sortedMapOf<Int, Long>()
    private val channelPayloadTypes = sortedMapOf<Int, MutableMap<Int, Long>>()
    private val samples = mutableListOf<ProbeFrameMetadata>()
    private var nonRtpOrMalformedFrames = 0L
    private var rtcpChannelFrames = 0L
    var selectedH264Packets = 0
        private set

    fun observe(frame: InterleavedRtspParser.Event.InterleavedFrame) {
        channels[frame.channel] = (channels[frame.channel] ?: 0) + 1
        // The generic RTP header parser can accept RTCP bytes. Recognize standard RTCP
        // headers on its negotiated channel, but still observe unexpected RTP on that channel.
        val isRtcpChannel = frame.channel == transport.rtcpChannel
        val isRtcp = isRtcpChannel && hasRtcpHeader(frame.payload)
        val packet = if (isRtcp) null else RtpPacketParser.parse(frame.payload)
        if (isRtcpChannel) rtcpChannelFrames++
        if (packet == null) {
            nonRtpOrMalformedFrames++
        } else {
            payloadTypes[packet.payloadType] = (payloadTypes[packet.payloadType] ?: 0) + 1
            val perChannel = channelPayloadTypes.getOrPut(frame.channel) { sortedMapOf() }
            perChannel[packet.payloadType] = (perChannel[packet.payloadType] ?: 0) + 1
        }
        val selected = packet != null && frame.channel == transport.rtpChannel &&
            packet.payloadType == track.payloadType
        if (selected) {
            selectedH264Packets++
            h264.observe(packet!!.sequenceNumber, packet.payload)
        }
        if (samples.size < MAX_FRAME_SAMPLES) {
            samples += ProbeFrameMetadata(
                frame.channel, frame.payload.size, packet?.payloadType, packet?.sequenceNumber,
                packet?.payload?.size, selected, isRtcpChannel
            )
        }
    }

    fun result(): H264ProbeResult = H264ProbeResult(
        expectedPayloadType = track.payloadType,
        transport = transport,
        observedInterleavedChannels = channels.toMap(),
        observedPayloadTypes = payloadTypes.toMap(),
        channelPayloadTypes = channelPayloadTypes.mapValues { it.value.toMap() },
        nonRtpOrMalformedFrames = nonRtpOrMalformedFrames,
        rtcpChannelFrames = rtcpChannelFrames,
        frameSamples = samples.toList(),
        h264 = h264.summary()
    )

    private fun hasRtcpHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 4 || (bytes[0].toInt() and 0xc0) != 0x80) return false
        // RFC 3550 SR/RR/SDES/BYE/APP use the full second octet, not RTP's seven-bit PT.
        if ((bytes[1].toInt() and 0xff) !in 200..204) return false
        val wordsMinusOne = ((bytes[2].toInt() and 0xff) shl 8) or (bytes[3].toInt() and 0xff)
        return (wordsMinusOne + 1) * 4 <= bytes.size
    }

    companion object {
        const val MAX_FRAME_SAMPLES = 8
    }
}
