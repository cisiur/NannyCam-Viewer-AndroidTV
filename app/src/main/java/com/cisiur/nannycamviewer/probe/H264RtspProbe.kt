package com.cisiur.nannycamviewer.probe

import android.os.SystemClock
import android.util.Log
import com.cisiur.nannycamviewer.audio.InterleavedRtspParser
import com.cisiur.nannycamviewer.audio.RtspRequestBuilder
import com.cisiur.nannycamviewer.audio.RtspSessionHeaderParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
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
        fun onComplete(result: H264ProbeResult)
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
        Log.i(TAG, "H264 probe worker started")
        try {
            val result = runProbe()
            if (!closed) listener.onComplete(result)
        } catch (error: Exception) {
            if (!closed) {
                val message = redactUserInfo(error.message ?: "H264 probe failed")
                Log.w(TAG, "H264 probe failed: $message")
                listener.onFailure(message)
            }
        } finally {
            sendTeardown()
            close()
        }
    }

    @Throws(IOException::class)
    private fun runProbe(): H264ProbeResult {
        val endpoint = URI(endpointUri)
        if (!endpoint.scheme.equals("rtsp", ignoreCase = true) || endpoint.host.isNullOrBlank()) {
            throw IOException("Configured endpoint is not a valid RTSP URI")
        }
        val connectedSocket = Socket()
        socket = connectedSocket
        val port = endpoint.port.takeIf { it > 0 } ?: DEFAULT_RTSP_PORT
        Log.i(TAG, "Connecting RTSP TCP to ${endpoint.host}:$port")
        connectedSocket.connect(
            InetSocketAddress(endpoint.host, port),
            CONNECT_TIMEOUT_MS
        )
        Log.i(TAG, "RTSP TCP connected")
        connectedSocket.soTimeout = READ_TIMEOUT_MS
        val input = BufferedInputStream(connectedSocket.getInputStream())
        output = BufferedOutputStream(connectedSocket.getOutputStream())
        val reader = ProbeRtspReader(input)

        val describe = requestAndAwait(
            reader,
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
            reader,
            method = "SETUP",
            uri = controlUri,
            headers = mapOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=${ProbeTransportParser.REQUESTED_INTERLEAVED}")
        )
        requireSuccess(setup, "SETUP")
        val transport = ProbeTransportParser.parse(setup.header("transport"))
        Log.i(TAG, "SETUP Transport response: ${redactUserInfo(transport.originalHeader ?: "<missing>")}")
        if (transport.negotiationConfirmed) {
            Log.i(TAG, "Negotiated interleaved RTP channel=${transport.rtpChannel} RTCP channel=${transport.rtcpChannel}")
        } else {
            Log.w(TAG, "SETUP has no valid interleaved pair; falling back to requested 0-1 for diagnostics; negotiation unconfirmed")
        }
        sessionId = RtspSessionHeaderParser.parse(setup.header("session"))?.sessionId
            ?: throw IOException("SETUP response did not include a Session header")

        val diagnostics = H264CaptureDiagnostics(track, transport)
        val play = requestAndAwait(
            reader,
            method = "PLAY",
            uri = endpointUri,
            headers = mapOf("Session" to sessionId.orEmpty()),
            onFrame = diagnostics::observe
        )
        requireSuccess(play, "PLAY")
        val result = captureVideoPackets(reader, diagnostics)
        val summary = result.h264
        if (!track.spropParameterSetsPresent && (summary.spsInRtp || summary.ppsInRtp)) {
            Log.i(TAG, "H264 codec parameters are present in-band over RTP despite missing SDP sprop-parameter-sets")
        }
        return result
    }

    private fun requestAndAwait(
        reader: ProbeRtspReader,
        method: String,
        uri: String,
        headers: Map<String, String>,
        onFrame: (InterleavedRtspParser.Event.InterleavedFrame) -> Unit = {}
    ): InterleavedRtspParser.Event.Message {
        val requestCSeq = sendRequest(method, uri, headers)
        while (!closed) {
            when (val event = reader.readEvent()) {
                is InterleavedRtspParser.Event.Message ->
                    if (event.header("cseq")?.toIntOrNull() == requestCSeq) return event
                is InterleavedRtspParser.Event.InterleavedFrame -> onFrame(event)
                null -> Unit
            }
        }
        throw IOException("H264 probe was closed")
    }

    private fun captureVideoPackets(
        reader: ProbeRtspReader,
        diagnostics: H264CaptureDiagnostics
    ): H264ProbeResult {
        Log.i(TAG, "Starting bounded RTP capture")
        val startedAtMs = SystemClock.elapsedRealtime()
        try {
            while (!closed && diagnostics.selectedH264Packets < MAX_VIDEO_RTP_PACKETS) {
                if (SystemClock.elapsedRealtime() - startedAtMs >= CAPTURE_DURATION_MS) break
                val event = try {
                    reader.readEvent()
                } catch (_: SocketTimeoutException) {
                    continue
                }
                if (event is InterleavedRtspParser.Event.InterleavedFrame) diagnostics.observe(event)
            }
        } finally {
            Log.i(TAG, "RTP capture finished")
            // Preserve evidence even if EOF, socket failure, or cancellation ends capture early.
            logSummary(diagnostics.result())
        }
        return diagnostics.result()
    }

    private fun sendRequest(method: String, uri: String, headers: Map<String, String>): Int {
        if (method == "SETUP") {
            Log.i(TAG, "Sending SETUP: requested interleaved=${ProbeTransportParser.REQUESTED_INTERLEAVED}")
        } else {
            Log.i(TAG, "Sending $method")
        }
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
        Log.i(TAG, "$request response: ${statusCode ?: "<invalid>"}")
        if (statusCode !in 200..299) throw IOException("$request failed: status ${statusCode ?: "<invalid>"}")
    }

    private fun logSdp(track: H264SdpTrack, resolvedControlUri: String) {
        Log.i(TAG, "Video SDP selected: payloadType=${track.payloadType} mediaLine=${track.mediaLine}")
        Log.i(TAG, "Video control: ${redactUserInfo(track.control)}")
        Log.i(TAG, "Resolved video control URI: ${uriForLog(resolvedControlUri)}")
        Log.i(TAG, "rtpmap=${track.rtpMap} fmtpPresent=${track.fmtp != null} " +
            "packetizationMode=${track.packetizationMode ?: "<missing>"} " +
            "spropParameterSetsPresent=${track.spropParameterSetsPresent}")
        Log.i(
            TAG,
            "H264 probe SDP:\nmediaLine=${track.mediaLine}\npayloadType=${track.payloadType}\n" +
                "rtpmap=${track.rtpMap ?: "<missing>"}\nfmtpPresent=${track.fmtp != null}\n" +
                "fmtp=${track.fmtp?.let { "a=fmtp:${track.payloadType} $it" } ?: "<missing>"}\n" +
                "packetizationMode=${track.packetizationMode ?: "<missing>"}\n" +
                "spropParameterSetsPresent=${track.spropParameterSetsPresent}\n" +
                "parameterSetCount=${track.parameterSets.size}\n" +
                "spsBytes=${track.spsByteCount ?: 0}\nppsBytes=${track.ppsByteCount ?: 0}\n" +
                "control=${redactUserInfo(track.control)}\nresolvedControl=${uriForLog(resolvedControlUri)}"
        )
    }

    private fun logSummary(result: H264ProbeResult) {
        val summary = result.h264
        result.frameSamples.forEach { sample ->
            Log.i(TAG, "Observed interleaved frame channel=${sample.channel} bytes=${sample.frameBytes} " +
                "payloadType=${sample.payloadType} seq=${sample.sequenceNumber} payloadBytes=${sample.payloadBytes} " +
                "selectedH264=${sample.selectedH264} rtcpChannel=${sample.isRtcpChannel}")
        }
        Log.i(TAG, "H264 probe transport summary: outcome=${result.outcome} " +
            "expectedPayloadType=${result.expectedPayloadType} requestedInterleaved=${ProbeTransportParser.REQUESTED_INTERLEAVED} " +
            "negotiatedRtpChannel=${result.transport.rtpChannel} negotiatedRtcpChannel=${result.transport.rtcpChannel} " +
            "negotiationConfirmed=${result.transport.negotiationConfirmed}")
        Log.i(TAG, "observedInterleavedChannels=${result.observedInterleavedChannels}")
        Log.i(TAG, "observedPayloadTypes=${result.observedPayloadTypes} channelPayloadTypes=${result.channelPayloadTypes}")
        Log.i(TAG, "selectedH264Packets=${summary.rtpPackets} nonRtpOrMalformedFrames=${result.nonRtpOrMalformedFrames} " +
            "rtcpChannelFrames=${result.rtcpChannelFrames}")
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

    private fun redactUserInfo(value: String): String =
        value.replace(Regex("(?i)(rtsp://)[^\\s/]*@"), "$1<redacted>@")

    private companion object {
        const val TAG = "H264RtspProbe"
        const val DEFAULT_RTSP_PORT = 554
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 1_000
        const val MAX_VIDEO_RTP_PACKETS = 50
        const val CAPTURE_DURATION_MS = 5_000L
    }
}
