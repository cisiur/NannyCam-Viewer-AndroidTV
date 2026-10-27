package com.cisiur.nannycamviewer.customvideo

import android.os.SystemClock
import android.util.Log
import com.cisiur.nannycamviewer.audio.InterleavedRtspParser
import com.cisiur.nannycamviewer.audio.RtspKeepaliveState
import com.cisiur.nannycamviewer.audio.RtspRequestBuilder
import com.cisiur.nannycamviewer.audio.RtspSessionHeaderParser
import com.cisiur.nannycamviewer.probe.H264SdpParser
import com.cisiur.nannycamviewer.probe.ProbeRtspReader
import com.cisiur.nannycamviewer.probe.ProbeTransportParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.StandardCharsets

/** Own POC-only video TCP session; never opens an audio track or calls an audio controller. */
class CustomH264RtspSession(private val endpointUri: String, private val pipeline: H264VideoPipeline) : Closeable {
    private val lifecycleLock = Any()
    @Volatile private var closed = false
    private var socket: Socket? = null
    private var output: BufferedOutputStream? = null
    private var sessionId: String? = null
    private var cSeq = 1
    private val keepalive = RtspKeepaliveState()
    private var rtpChannel = -1
    private var payloadType = -1
    private var lastVideoPacketAtMs = 0L
    private var packets = 0L
    private var lastStatsAtMs = 0L

    fun run() {
        val endpoint = URI(endpointUri)
        require(endpoint.scheme.equals("rtsp", true) && !endpoint.host.isNullOrBlank()) { "Invalid RTSP endpoint" }
        val connection = synchronized(lifecycleLock) {
            if (closed) return
            Socket().also { socket = it }
        }
        try {
            connection.connect(InetSocketAddress(endpoint.host, endpoint.port.takeIf { it > 0 } ?: 554), 5_000)
            connection.soTimeout = 1_000
            val reader = ProbeRtspReader(BufferedInputStream(connection.getInputStream()))
            output = BufferedOutputStream(connection.getOutputStream())
            Log.i(TAG, "Custom video RTSP connected")
            val describe = request(reader, "DESCRIBE", endpointUri, mapOf("Accept" to "application/sdp"))
            val track = H264SdpParser.findVideoTrack(describe.body.toString(StandardCharsets.UTF_8))
                ?: throw IOException("No controlled H.264 video track in SDP")
            if (track.packetizationMode != null && track.packetizationMode !in listOf("0", "1")) {
                throw IOException("Unsupported H.264 packetization mode")
            }
            Log.i(TAG, "Video PT=${track.payloadType} fmtpPresent=${track.fmtp != null}")
            payloadType = track.payloadType
            pipeline.seedParameterSets(H264SdpParser.parameterSetNals(track))
            val control = H264SdpParser.resolveControlUri(
                endpointUri, describe.header("content-base") ?: describe.header("content-location"), track.control
            )
            val setup = request(reader, "SETUP", control, mapOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1"))
            val transport = ProbeTransportParser.parse(setup.header("transport"))
            if (!transport.negotiationConfirmed) throw IOException("SETUP did not confirm an interleaved channel pair")
            if (transport.rtpChannel == 4) throw IOException("POC excludes camera channel 4; unexpected video channel mapping")
            rtpChannel = transport.rtpChannel
            Log.i(TAG, "Video SETUP complete: RTP channel=$rtpChannel RTCP channel=${transport.rtcpChannel}")
            val session = RtspSessionHeaderParser.parse(setup.header("session"))
                ?: throw IOException("SETUP response has no RTSP Session")
            sessionId = session.sessionId
            lastVideoPacketAtMs = SystemClock.elapsedRealtime()
            request(reader, "PLAY", endpointUri, mapOf("Session" to session.sessionId), ::processFrame)
            lastVideoPacketAtMs = SystemClock.elapsedRealtime()
            keepalive.start(SystemClock.elapsedRealtime(), session.timeoutSeconds)
            while (!closed) {
                serviceKeepalive()
                val event = try { reader.readEvent() } catch (_: SocketTimeoutException) { null }
                when (event) {
                    is InterleavedRtspParser.Event.InterleavedFrame -> processFrame(event)
                    is InterleavedRtspParser.Event.Message -> {
                        val responseCSeq = event.header("cseq")?.toIntOrNull()
                        if (responseCSeq != null && keepalive.acknowledge(responseCSeq)) {
                            requireSuccess(event, "GET_PARAMETER")
                        }
                    }
                    null -> Unit
                }
                if (SystemClock.elapsedRealtime() - lastVideoPacketAtMs >= 5_000) {
                    throw IOException("Selected video RTP transport timed out (5 seconds)")
                }
            }
        } finally {
            // UI cancellation shuts down only input on an established connection. This worker
            // can still attempt TEARDOWN before it closes the socket; there is only one writer.
            if (sessionId != null && !connection.isClosed) {
                runCatching { send("TEARDOWN", endpointUri, mapOf("Session" to sessionId.orEmpty())) }
            }
            runCatching { connection.close() }
            synchronized(lifecycleLock) { socket = null }
            output = null
            keepalive.reset()
        }
    }

    override fun close() {
        synchronized(lifecycleLock) {
            closed = true
            socket?.let { connection ->
                if (connection.isConnected) runCatching { connection.shutdownInput() }
                else runCatching { connection.close() }
            }
        }
    }

    private fun processFrame(frame: InterleavedRtspParser.Event.InterleavedFrame) {
        // The physical camera also sends channel 4 / PT8 even during video-only SETUP.
        if (closed) return
        val selected = VideoRtpParser.select(frame.channel, rtpChannel, payloadType, frame.payload)
        if (selected.malformed) {
            pipeline.queue.invalidate()
            return
        }
        val packet = selected.packet ?: return
        lastVideoPacketAtMs = SystemClock.elapsedRealtime()
        if (packets++ == 0L) Log.i(TAG, "First video RTP packet")
        pipeline.accept(packet)
        if (lastVideoPacketAtMs - lastStatsAtMs >= 5_000) {
            lastStatsAtMs = lastVideoPacketAtMs
            val depacketizer = pipeline.depacketizer
            Log.i(TAG, "RTP packets=$packets accessUnits=${pipeline.accessUnits} " +
                "fuAReassembled=${depacketizer.fuAReassembled} singleNal=${depacketizer.singleNal} " +
                "droppedPackets=${depacketizer.droppedPackets} sequenceDiscontinuities=${depacketizer.sequenceDiscontinuities} " +
                "queueDrops=${pipeline.queue.drops} queued=${pipeline.queue.size()}")
        }
    }

    private fun request(
        reader: ProbeRtspReader,
        method: String,
        uri: String,
        headers: Map<String, String>,
        onFrame: (InterleavedRtspParser.Event.InterleavedFrame) -> Unit = {}
    ): InterleavedRtspParser.Event.Message {
        val sequence = send(method, uri, headers)
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!closed && SystemClock.elapsedRealtime() < deadline) {
            val event = try { reader.readEvent() } catch (_: SocketTimeoutException) { null }
            when (event) {
                is InterleavedRtspParser.Event.Message -> if (event.header("cseq")?.toIntOrNull() == sequence) {
                    requireSuccess(event, method)
                    return event
                }
                is InterleavedRtspParser.Event.InterleavedFrame -> onFrame(event)
                null -> Unit
            }
        }
        throw IOException("$method stopped or timed out")
    }

    private fun serviceKeepalive() {
        val now = SystemClock.elapsedRealtime()
        if (keepalive.responseTimedOut(now)) throw IOException("Video RTSP keepalive timed out")
        if (keepalive.isDue(now)) {
            val sequence = send("GET_PARAMETER", endpointUri, mapOf("Session" to sessionId.orEmpty()))
            keepalive.requestSent(sequence, now)
        }
    }

    private fun send(method: String, uri: String, headers: Map<String, String>): Int {
        val sequence = cSeq++
        val target = output ?: throw IOException("RTSP output unavailable")
        target.write(RtspRequestBuilder.build(method, uri, sequence, headers).toByteArray(StandardCharsets.ISO_8859_1))
        target.flush()
        return sequence
    }

    private fun requireSuccess(response: InterleavedRtspParser.Event.Message, method: String) {
        val code = response.startLine.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull()
        Log.i(TAG, "$method ${code ?: "invalid response"}")
        if (code !in 200..299) throw IOException("$method failed: $code")
    }

    companion object { const val TAG = "CustomVideoPoc" }
}
