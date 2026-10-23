package com.cisiur.nannycamviewer.audio

import android.util.Log
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

data class AudioSdpTrack(val payloadType: Int, val control: String)

/** Parses only the SDP structure needed to select this camera's audio RTP track. */
object AudioSdpParser {
    fun findAudioTrack(sdp: String): AudioSdpTrack? {
        var isAudioSection = false
        var payloadType: Int? = null
        var control: String? = null
        for (rawLine in sdp.lineSequence()) {
            val line = rawLine.trim()
            when {
                line.startsWith("m=") -> {
                    if (isAudioSection && payloadType != null && control != null) {
                        return AudioSdpTrack(payloadType!!, control!!)
                    }
                    val parts = line.removePrefix("m=").trim().split(Regex("\\s+"))
                    isAudioSection = parts.firstOrNull()?.equals("audio", ignoreCase = true) == true
                    payloadType = if (isAudioSection) parts.getOrNull(3)?.toIntOrNull() else null
                    control = null
                }
                isAudioSection && line.startsWith("a=control:", ignoreCase = true) -> {
                    control = line.substringAfter(':').trim()
                }
            }
        }
        return payloadType?.let { type -> control?.takeIf { it.isNotBlank() }?.let { AudioSdpTrack(type, it) } }
    }

    fun resolveControlUri(requestUri: String, contentBase: String?, control: String): String {
        if (control.startsWith("rtsp://", ignoreCase = true)) return control
        val request = URI(requestUri)
        if (control.startsWith('/')) {
            return URI(request.scheme, null, request.host, request.port, control, null, null).toString()
        }
        val base = contentBase?.takeIf { it.isNotBlank() } ?: requestUri
        val normalizedBase = if (base.endsWith('/')) base else "$base/"
        return URI(normalizedBase).resolve(control).toString()
    }
}

/** Camera-specific RTSP/TCP client for one interleaved RTP audio track, not a generic RTSP stack. */
class RtspAudioSession(
    private val endpointUri: String,
    private val onTrackSelected: (controlUri: String, payloadType: Int) -> Unit,
    private val onRtpPacket: (RtpPacket) -> Unit
) : Closeable {
    @Volatile
    private var closed = false
    @Volatile
    private var socket: Socket? = null
    private var output: BufferedOutputStream? = null
    private var cSeq = 1
    private var sessionId: String? = null
    private var sessionTimeoutSeconds: Int? = null
    private val keepaliveState = RtspKeepaliveState()

    @Throws(IOException::class)
    fun run() {
        val endpoint = URI(endpointUri)
        if (!endpoint.scheme.equals("rtsp", ignoreCase = true) || endpoint.host.isNullOrBlank()) {
            throw IOException("Configured endpoint is not a valid RTSP URI")
        }
        val connectedSocket = Socket()
        socket = connectedSocket
        connectedSocket.connect(InetSocketAddress(endpoint.host, endpoint.port.takeIf { it > 0 } ?: DEFAULT_RTSP_PORT), CONNECT_TIMEOUT_MS)
        connectedSocket.soTimeout = READ_TIMEOUT_MS
        val input = BufferedInputStream(connectedSocket.getInputStream())
        output = BufferedOutputStream(connectedSocket.getOutputStream())
        val parser = InterleavedRtspParser()
        try {
            val describe = requestAndAwait(
                input,
                parser,
                method = "DESCRIBE",
                uri = endpointUri,
                headers = mapOf("Accept" to "application/sdp")
            )
            requireSuccess(describe, "DESCRIBE")
            val sdp = describe.body.toString(StandardCharsets.UTF_8)
            val audioTrack = AudioSdpParser.findAudioTrack(sdp)
                ?: throw IOException("No controlled audio track in SDP")
            val controlUri = AudioSdpParser.resolveControlUri(
                endpointUri,
                describe.header("content-base") ?: describe.header("content-location"),
                audioTrack.control
            )
            onTrackSelected(controlUri, audioTrack.payloadType)

            val setup = requestAndAwait(
                input,
                parser,
                method = "SETUP",
                uri = controlUri,
                headers = mapOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1")
            )
            requireSuccess(setup, "SETUP")
            val sessionInfo = RtspSessionHeaderParser.parse(setup.header("session"))
                ?: throw IOException("SETUP response did not include a Session header")
            sessionId = sessionInfo.sessionId
            sessionTimeoutSeconds = sessionInfo.timeoutSeconds
            if (sessionTimeoutSeconds != null) {
                Log.i(TAG, "RTSP Session timeout: ${sessionTimeoutSeconds}s")
            }

            val play = requestAndAwait(
                input,
                parser,
                method = "PLAY",
                uri = endpointUri,
                headers = mapOf("Session" to sessionId.orEmpty())
            )
            requireSuccess(play, "PLAY")
            val keepaliveInterval = keepaliveState.start(System.currentTimeMillis(), sessionTimeoutSeconds)
            Log.i(TAG, "Audio keepalive scheduled every ${keepaliveInterval / 1_000}s")
            readInterleavedFrames(input, parser)
        } finally {
            sendTeardown()
            close()
        }
    }

    override fun close() {
        closed = true
        runCatching { socket?.close() }
        socket = null
        output = null
        keepaliveState.reset()
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
                when (event) {
                    is InterleavedRtspParser.Event.InterleavedFrame -> processInterleavedFrame(event)
                    is InterleavedRtspParser.Event.Message -> {
                        if (event.header("cseq")?.toIntOrNull() == requestCSeq) return event
                    }
                }
            }
        }
        throw IOException("RTSP session was closed")
    }

    private fun readInterleavedFrames(input: BufferedInputStream, parser: InterleavedRtspParser) {
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (!closed) {
            serviceKeepaliveIfDue()
            val count = try {
                input.read(buffer)
            } catch (_: SocketTimeoutException) {
                // RTP may be temporarily quiet; use this wake-up to service/check RTSP keepalive.
                serviceKeepaliveIfDue()
                continue
            }
            if (count < 0) throw EOFException("RTSP server closed the connection")
            parser.feed(buffer, count).forEach { event ->
                when (event) {
                    is InterleavedRtspParser.Event.InterleavedFrame -> processInterleavedFrame(event)
                    is InterleavedRtspParser.Event.Message -> processStreamingRtspMessage(event)
                }
            }
            serviceKeepaliveIfDue()
        }
    }

    private fun processInterleavedFrame(frame: InterleavedRtspParser.Event.InterleavedFrame) {
        if (frame.channel != RTP_CHANNEL) return // Channel 1 is RTCP for this SETUP request.
        RtpPacketParser.parse(frame.payload)?.let(onRtpPacket)
    }

    private fun processStreamingRtspMessage(message: InterleavedRtspParser.Event.Message) {
        val responseCSeq = message.header("cseq")?.toIntOrNull() ?: return
        if (!keepaliveState.acknowledge(responseCSeq)) return
        requireSuccess(message, "GET_PARAMETER")
        Log.i(TAG, "RTSP keepalive acknowledged")
    }

    private fun serviceKeepaliveIfDue() {
        val nowMs = System.currentTimeMillis()
        if (keepaliveState.responseTimedOut(nowMs)) {
            throw IOException("RTSP GET_PARAMETER keepalive response timed out")
        }
        if (!keepaliveState.isDue(nowMs)) return
        val activeSessionId = sessionId ?: throw IOException("RTSP Session is unavailable for keepalive")
        Log.i(TAG, "Sending RTSP GET_PARAMETER keepalive")
        val requestCSeq = sendRequest(
            method = "GET_PARAMETER",
            uri = endpointUri,
            headers = mapOf("Session" to activeSessionId)
        )
        keepaliveState.requestSent(requestCSeq, nowMs)
    }

    private fun sendRequest(method: String, uri: String, headers: Map<String, String>): Int {
        val requestCSeq = cSeq++
        val request = RtspRequestBuilder.build(method, uri, requestCSeq, headers)
        val activeOutput = output ?: throw IOException("RTSP output is unavailable")
        activeOutput.write(request.toByteArray(StandardCharsets.ISO_8859_1))
        activeOutput.flush()
        return requestCSeq
    }

    private fun sendTeardown() {
        if (closed || sessionId == null) return
        runCatching { sendRequest("TEARDOWN", endpointUri, mapOf("Session" to sessionId.orEmpty())) }
    }

    private fun requireSuccess(message: InterleavedRtspParser.Event.Message, request: String) {
        val parts = message.startLine.split(Regex("\\s+"))
        if (parts.getOrNull(1)?.toIntOrNull() !in 200..299) {
            throw IOException("$request failed: ${message.startLine}")
        }
    }

    private companion object {
        const val TAG = "RtspAudioSession"
        const val DEFAULT_RTSP_PORT = 554
        const val CONNECT_TIMEOUT_MS = 5_000
        const val READ_TIMEOUT_MS = 5_000
        const val READ_BUFFER_BYTES = 4 * 1024
        const val RTP_CHANNEL = 0
    }
}
