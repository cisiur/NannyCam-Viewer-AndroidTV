package com.cisiur.nannycamviewer.probe

import android.os.SystemClock
import android.util.Log
import com.cisiur.nannycamviewer.audio.InterleavedRtspParser
import com.cisiur.nannycamviewer.audio.RtpPacketParser
import com.cisiur.nannycamviewer.audio.RtspRequestBuilder
import com.cisiur.nannycamviewer.audio.RtspSessionHeaderParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * Bounded, video-only RTSP/RTP inspection session. It records protocol metadata only and never
 * decodes, renders, or forwards H.264 payloads to the production playback path.
 */
class H264RtspProbe(
    private val endpointUri: String,
    private val listener: Listener
) : Closeable {
    interface Listener {
        fun onComplete(summary: H264ProbeSummary)
        fun onFailure(message: String)
    }

    @Volatile
    private var closed = false
    @Volatile
    private var socket: Socket? = null
    private var output: BufferedOutputStream? = null
    private var cSeq = 1
    private var sessionId: String? = null

    fun start() {
        Thread({ runSafely() }, "NannyCamH264Probe").start()
    }

    override fun close() {
        closed = true
        runCatching { socket?.close() }
        socket = null
        output = null
    }

    private fun runSafely() {
        try {
            val summary = runProbe()
            listener.onComplete(summary)
        } catch (error: Exception) {
            if (!closed) {
                Log.w(TAG, "H264 probe failed: ${error.message}")
                listener.onFailure(error.message ?: "H264 probe failed")
            }
        } finally {
            sendTeardown()
            close()
        }
    }

    @Throws(IOException::class)
    private fun runProbe(): H264ProbeSummary {
        val endpoint = URI(endpointUri)
        if (!endpoint.scheme.equals("rtsp", ignoreCase = true) || endpoint.host.isNullOrBlank()) {
            throw IOException("Configured endpoint is not a valid RTSP URI")
        }
        val connectedSocket = Socket()
        socket = connectedSocket
        connectedSocket.connect(
            InetSocketAddress(endpoint.host, endpoint.port.takeIf { it > 0 } ?: DEFAULT_RTSP_PORT),
            CONNECT_TIMEOUT_MS
        )
        connectedSocket.soTimeout = READ_TIMEOUT_MS
        val input = BufferedInputStream(connectedSocket.getInputStream())
        output = BufferedOutputStream(connectedSocket.getOutputStream())
        val parser = InterleavedRtspParser()

        val describe = requestAndAwait(
            input,
            parser,
            method = "DESCRIBE",
            uri = endpointUri,
            headers = mapOf("Accept" to "application/sdp")
        )
        requireSuccess(describe, "DESCRIBE")
        val sdp = describe.body.toString(StandardCharsets.UTF_8)
        val track = H264SdpParser.findVideoTrack(sdp)
            ?: throw IOException("No controlled H.264 video track in SDP")
        val controlUri = H264SdpParser.resolveControlUri(
            endpointUri,
            describe.header("content-base") ?: describe.header("content-location"),
            track.control
        )
        logSdp(track, controlUri)

        val setup = requestAndAwait(
            input,
            parser,
            method = "SETUP",
            uri = controlUri,
            headers = mapOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1")
        )
        requireSuccess(setup, "SETUP")
        sessionId = RtspSessionHeaderParser.parse(setup.header("session"))?.sessionId
            ?: throw IOException("SETUP response did not include a Session header")

        val play = requestAndAwait(
            input,
            parser,
            method = "PLAY",
            uri = endpointUri,
            headers = mapOf("Session" to sessionId.orEmpty())
        )
        requireSuccess(play, "PLAY")
        val summary = captureVideoPackets(input, parser, track)
        logSummary(summary)
        if (!track.spropParameterSetsPresent && (summary.spsInRtp || summary.ppsInRtp)) {
            Log.i(TAG, "H264 codec parameters are present in-band over RTP despite missing SDP sprop-parameter-sets")
        }
        return summary
    }

    private fun requestAndAwait(
        input: BufferedInputStream,
        parser: InterleavedRtspParser,
        method: String,
        uri: String,
        headers: Map<String, String>
    ): InterleavedRtspParser.Event.Message {
        val requestCSeq = sendRequest(method, uri, headers)
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (!closed) {
            val count = input.read(buffer)
            if (count < 0) throw EOFException("RTSP server closed the connection")
            parser.feed(buffer, count).forEach { event ->
                if (event is InterleavedRtspParser.Event.Message && event.header("cseq")?.toIntOrNull() == requestCSeq) {
                    return event
                }
            }
        }
        throw IOException("H264 probe was closed")
    }

    private fun captureVideoPackets(
        input: BufferedInputStream,
        parser: InterleavedRtspParser,
        track: H264SdpTrack
    ): H264ProbeSummary {
        val statistics = H264ProbeStatistics(track)
        val startedAtMs = SystemClock.elapsedRealtime()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var malformedPayloadLogged = false
        while (!closed && statistics.summary().rtpPackets < MAX_VIDEO_RTP_PACKETS) {
            if (SystemClock.elapsedRealtime() - startedAtMs >= CAPTURE_DURATION_MS) break
            val count = try {
                input.read(buffer)
            } catch (_: SocketTimeoutException) {
                continue
            }
            if (count < 0) throw EOFException("RTSP server closed the connection")
            for (event in parser.feed(buffer, count)) {
                if (statistics.summary().rtpPackets >= MAX_VIDEO_RTP_PACKETS) break
                if (event !is InterleavedRtspParser.Event.InterleavedFrame || event.channel != RTP_CHANNEL) continue
                val packet = RtpPacketParser.parse(event.payload)
                if (packet == null) {
                    statistics.observeMalformedRtpPacket()
                    if (!malformedPayloadLogged) {
                        Log.w(TAG, "Ignoring malformed RTP packet during H264 probe")
                        malformedPayloadLogged = true
                    }
                } else if (packet.payloadType == track.payloadType) {
                    val inspection = statistics.observe(packet.sequenceNumber, packet.payload)
                    if (!malformedPayloadLogged &&
                        (inspection is H264PayloadInspection.Malformed ||
                            inspection is H264PayloadInspection.Unsupported)
                    ) {
                        Log.w(TAG, "Ignoring malformed or unsupported H264 RTP payload during probe")
                        malformedPayloadLogged = true
                    }
                }
            }
        }
        val summary = statistics.summary()
        if (summary.rtpPackets == 0) throw IOException("No H.264 RTP packets received within capture limit")
        return summary
    }

    private fun sendRequest(method: String, uri: String, headers: Map<String, String>): Int {
        val requestCSeq = cSeq++
        val activeOutput = output ?: throw IOException("RTSP output is unavailable")
        activeOutput.write(RtspRequestBuilder.build(method, uri, requestCSeq, headers).toByteArray(StandardCharsets.ISO_8859_1))
        activeOutput.flush()
        return requestCSeq
    }

    private fun sendTeardown() {
        if (closed || sessionId == null) return
        runCatching { sendRequest("TEARDOWN", endpointUri, mapOf("Session" to sessionId.orEmpty())) }
    }

    private fun requireSuccess(message: InterleavedRtspParser.Event.Message, request: String) {
        val statusCode = message.startLine.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()
        if (statusCode !in 200..299) throw IOException("$request failed: ${message.startLine}")
    }

    private fun logSdp(track: H264SdpTrack, resolvedControlUri: String) {
        Log.i(
            TAG,
            "H264 probe SDP:\n${track.mediaLine}\npayloadType=${track.payloadType}\n" +
                "rtpmap=${track.rtpMap ?: "<missing>"}\nfmtpPresent=${track.fmtp != null}\n" +
                "fmtp=${track.fmtp?.let { "a=fmtp:${track.payloadType} $it" } ?: "<missing>"}\n" +
                "packetizationMode=${track.packetizationMode ?: "<missing>"}\n" +
                "spropParameterSetsPresent=${track.spropParameterSetsPresent}\n" +
                "parameterSetCount=${track.parameterSets.size}\n" +
                "spsBytes=${track.spsByteCount ?: 0}\nppsBytes=${track.ppsByteCount ?: 0}\n" +
                "control=${track.control}\nresolvedControl=${uriForLog(resolvedControlUri)}"
        )
    }

    private fun logSummary(summary: H264ProbeSummary) {
        Log.i(
            TAG,
            "H264 probe summary:\nrtpPackets=${summary.rtpPackets}\nsingleNal=${summary.singleNalPackets}\n" +
                "stapA=${summary.stapAPackets}\nfuA=${summary.fuAPackets}\n" +
                "fuAStartFragments=${summary.fuAStartFragments}\nfuAEndFragments=${summary.fuAEndFragments}\n" +
                "nalTypeCounts=${summary.nalTypeCounts}\n" +
                "spsInSdp=${summary.spsInSdp}\nppsInSdp=${summary.ppsInSdp}\n" +
                "spsInRtp=${summary.spsInRtp}\nppsInRtp=${summary.ppsInRtp}\n" +
                "spsSource=${parameterSource(summary.spsInSdp, summary.spsInRtp)}\n" +
                "ppsSource=${parameterSource(summary.ppsInSdp, summary.ppsInRtp)}\n" +
                "idrInRtp=${summary.idrInRtp}\nnonIdrInRtp=${summary.nonIdrInRtp}\n" +
                "malformedOrUnsupported=${summary.malformedOrUnsupportedPayloads}\n" +
                "firstSeq=${summary.firstSequenceNumber ?: "<none>"}\nlastSeq=${summary.lastSequenceNumber ?: "<none>"}"
        )
    }

    private fun parameterSource(inSdp: Boolean, inRtp: Boolean): String = when {
        inSdp && inRtp -> "both"
        inSdp -> "SDP"
        inRtp -> "RTP"
        else -> "neither"
    }

    private fun uriForLog(uri: String): String = runCatching {
        val parsed = URI(uri)
        "${parsed.scheme}://${parsed.host}${parsed.path ?: ""}"
    }.getOrDefault("RTSP URI")

    private companion object {
        const val TAG = "H264RtspProbe"
        const val DEFAULT_RTSP_PORT = 554
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 1_000
        const val READ_BUFFER_BYTES = 4 * 1024
        const val RTP_CHANNEL = 0
        const val MAX_VIDEO_RTP_PACKETS = 50
        const val CAPTURE_DURATION_MS = 5_000L
    }
}
