package com.cisiur.nannycamviewer.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.cisiur.nannycamviewer.playback.ReconnectPolicy
import java.io.IOException

/**
 * Owns the camera-specific audio RTSP session and AudioTrack independently from Media3 video.
 * No audio-focus policy is requested here; that platform work is intentionally deferred to ATV-05.
 */
class AudioPlaybackController(
    context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onAudioStateChanged(state: AudioConnectionState)
        fun onAudioError(message: String)
        fun onAudioDiagnostics(diagnostics: AudioDiagnostics)
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val reconnectPolicy = ReconnectPolicy()
    private val pcmBuffer = BoundedPcmBuffer()
    private val pcmDecoder = Pcm16LeDecoder()
    private val dspProcessor = PcmDspProcessor()
    private val settings = AudioPlaybackSettings()

    @Volatile
    private var state = AudioConnectionState.STOPPED
    @Volatile
    private var connectIntent = false
    @Volatile
    private var attemptGeneration = 0L
    @Volatile
    private var activeSession: RtspAudioSession? = null
    @Volatile
    private var activeAudioTrack: AudioTrack? = null

    private var cameraUri: String? = null
    private var networkThread: Thread? = null
    private var outputThread: Thread? = null
    private var rtpStatistics = RtpStatistics()
    private var selectedControlUri: String? = null
    private var selectedPayloadType: Int? = null
    private var decodedSamples = 0L
    private var reconnectCount = 0
    private var lastDiagnosticsAtMs = 0L

    private val reconnectRunnable = Runnable {
        val uri: String
        synchronized(lock) {
            reconnectPolicy.retryStarted()
            if (!connectIntent || cameraUri == null) return@Runnable
            uri = cameraUri!!
            reconnectCount++
        }
        Log.i(TAG, "Audio reconnect attempt")
        startAttempt(uri, reconnecting = true)
    }

    val connectionState: AudioConnectionState
        get() = state

    fun connect(uri: String) {
        synchronized(lock) {
            if (connectIntent) return
            connectIntent = true
            cameraUri = uri
            reconnectCount = 0
            reconnectPolicy.reset()
        }
        Log.i(TAG, "Audio connect requested for ${uriForLog(uri)}")
        startAttempt(uri, reconnecting = false)
    }

    fun disconnect() {
        synchronized(lock) {
            connectIntent = false
            cameraUri = null
            reconnectPolicy.cancel()
            mainHandler.removeCallbacks(reconnectRunnable)
            stopActiveAttemptLocked()
            state = AudioConnectionState.STOPPED
        }
        Log.i(TAG, "Audio disconnect")
        emitState()
        emitDiagnostics()
    }

    fun releaseForLifecycle() = disconnect()

    fun toggleMute() {
        settings.muted = !settings.muted
        pcmBuffer.clear() // Never release old buffered audio after unmute.
        Log.i(TAG, "Audio muted=${settings.muted}")
        emitDiagnostics()
    }

    fun toggleProcessing() {
        settings.processingEnabled = !settings.processingEnabled
        pcmBuffer.clear()
        dspProcessor.reset()
        Log.i(TAG, "Audio processing enabled=${settings.processingEnabled}")
        emitDiagnostics()
    }

    fun isMuted(): Boolean = settings.muted

    fun isProcessingEnabled(): Boolean = settings.processingEnabled

    private fun startAttempt(uri: String, reconnecting: Boolean) {
        val generation: Long
        synchronized(lock) {
            mainHandler.removeCallbacks(reconnectRunnable)
            stopActiveAttemptLocked()
            attemptGeneration++
            generation = attemptGeneration
            pcmBuffer.reset()
            pcmDecoder.reset()
            dspProcessor.reset()
            rtpStatistics = RtpStatistics()
            selectedControlUri = null
            selectedPayloadType = null
            decodedSamples = 0
            lastDiagnosticsAtMs = 0
            state = if (reconnecting) AudioConnectionState.RECONNECTING else AudioConnectionState.CONNECTING
        }
        emitState()
        emitDiagnostics()
        outputThread = Thread({ runOutput(generation) }, "NannyCamAudioOutput").also { it.start() }
        networkThread = Thread({ runNetwork(generation, uri) }, "NannyCamAudioRtsp").also { it.start() }
    }

    private fun runNetwork(generation: Long, uri: String) {
        try {
            val session = RtspAudioSession(
                endpointUri = uri,
                onTrackSelected = { controlUri, payloadType ->
                    if (!isCurrent(generation)) return@RtspAudioSession
                    synchronized(lock) {
                        selectedControlUri = controlUri
                        selectedPayloadType = payloadType
                    }
                    Log.i(TAG, "Audio track selected: ${uriForLog(controlUri)} payload type $payloadType")
                    emitDiagnostics()
                },
                onRtpPacket = { packet -> consumeRtpPacket(generation, packet) }
            )
            synchronized(lock) {
                if (!isCurrentLocked(generation)) {
                    session.close()
                    return
                }
                activeSession = session
            }
            session.run()
            if (isCurrent(generation)) failAttempt(generation, "Audio RTSP session ended")
        } catch (error: IOException) {
            if (isCurrent(generation)) failAttempt(generation, error.message ?: "Audio RTSP I/O failure")
        } catch (error: RuntimeException) {
            if (isCurrent(generation)) failAttempt(generation, error.message ?: "Audio RTSP parse failure")
        }
    }

    private fun consumeRtpPacket(generation: Long, packet: RtpPacket) {
        if (!isCurrent(generation)) return
        val expectedPayloadType = synchronized(lock) { selectedPayloadType }
        if (expectedPayloadType != null && packet.payloadType != expectedPayloadType) return
        synchronized(lock) { rtpStatistics.observe(packet) }
        val decoded = pcmDecoder.decode(packet.payload)
        if (decoded.isNotEmpty()) {
            val outputSamples = if (settings.processingEnabled) dspProcessor.process(decoded) else decoded
            pcmBuffer.offer(Pcm16LeDecoder.encode(outputSamples))
            synchronized(lock) { decodedSamples += decoded.size }
            markPlaying(generation)
        }
        emitDiagnosticsPeriodically()
    }

    private fun runOutput(generation: Long) {
        var track: AudioTrack? = null
        try {
            val minimumBufferBytes = AudioTrack.getMinBufferSize(
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val trackBufferBytes = maxOf(TARGET_OUTPUT_CHUNK_BYTES, minimumBufferBytes.coerceAtLeast(0))
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setBufferSizeInBytes(trackBufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (track.state != AudioTrack.STATE_INITIALIZED) throw IOException("AudioTrack was not initialized")
            synchronized(lock) {
                if (!isCurrentLocked(generation)) return
                activeAudioTrack = track
            }
            track.play()
            while (isCurrent(generation)) {
                if (!pcmBuffer.waitForData(OUTPUT_WAIT_MS)) continue
                val bytes = pcmBuffer.take(TARGET_OUTPUT_CHUNK_BYTES)
                if (bytes.isNotEmpty() && !settings.muted) {
                    val written = track.write(bytes, 0, bytes.size, AudioTrack.WRITE_BLOCKING)
                    if (written < 0) throw IOException("AudioTrack write failed: $written")
                }
            }
        } catch (error: IOException) {
            if (isCurrent(generation)) failAttempt(generation, error.message ?: "Audio output failure")
        } catch (error: IllegalStateException) {
            if (isCurrent(generation)) failAttempt(generation, error.message ?: "Audio output failure")
        } finally {
            synchronized(lock) {
                if (activeAudioTrack === track) activeAudioTrack = null
            }
            runCatching { track?.pause() }
            runCatching { track?.flush() }
            runCatching { track?.release() }
        }
    }

    private fun markPlaying(generation: Long) {
        mainHandler.post {
            synchronized(lock) {
                if (!isCurrentLocked(generation) || state == AudioConnectionState.PLAYING) return@post
                state = AudioConnectionState.PLAYING
                reconnectPolicy.reset()
            }
            Log.i(TAG, "Audio playback started")
            emitState()
        }
    }

    private fun failAttempt(generation: Long, message: String) {
        mainHandler.post {
            val delayMs: Long
            synchronized(lock) {
                if (!isCurrentLocked(generation)) return@post
                delayMs = reconnectPolicy.nextDelayMs() ?: return@post
                stopActiveAttemptLocked()
                state = AudioConnectionState.RECONNECTING
            }
            Log.w(TAG, "Audio failure: $message; reconnect in ${delayMs}ms")
            listener.onAudioError(message)
            emitState()
            emitDiagnostics()
            mainHandler.postDelayed(reconnectRunnable, delayMs)
        }
    }

    private fun stopActiveAttemptLocked() {
        activeSession?.close()
        activeSession = null
        networkThread?.interrupt()
        networkThread = null
        activeAudioTrack?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.release() }
        }
        activeAudioTrack = null
        outputThread?.interrupt()
        outputThread = null
        pcmBuffer.clear()
        pcmDecoder.reset()
        dspProcessor.reset()
    }

    private fun isCurrent(generation: Long): Boolean = synchronized(lock) { isCurrentLocked(generation) }

    private fun isCurrentLocked(generation: Long): Boolean = connectIntent && attemptGeneration == generation

    private fun emitState() = mainHandler.post { listener.onAudioStateChanged(state) }

    private fun emitDiagnosticsPeriodically() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDiagnosticsAtMs >= DIAGNOSTICS_INTERVAL_MS) {
            lastDiagnosticsAtMs = now
            emitDiagnostics()
        }
    }

    private fun emitDiagnostics() {
        val diagnostics = synchronized(lock) {
            val rtp = rtpStatistics
            val buffer = pcmBuffer.snapshot()
            AudioDiagnostics(
                controlUri = selectedControlUri,
                payloadType = selectedPayloadType,
                packetsReceived = rtp.packetsReceived,
                payloadBytesReceived = rtp.payloadBytesReceived,
                latestSequenceNumber = rtp.latestSequenceNumber(),
                sequenceDiscontinuities = rtp.sequenceDiscontinuities,
                duplicateOrOutOfOrderPackets = rtp.duplicateOrOutOfOrderPackets,
                decodedSamples = decodedSamples,
                bufferedDurationMs = (buffer.queuedBytes / BoundedPcmBuffer.BYTES_PER_MILLISECOND).toLong(),
                staleDroppedBytes = buffer.staleDroppedBytes,
                reconnectCount = reconnectCount,
                muted = settings.muted,
                processingEnabled = settings.processingEnabled
            )
        }
        mainHandler.post { listener.onAudioDiagnostics(diagnostics) }
    }

    private fun uriForLog(uri: String): String = runCatching {
        val parsed = android.net.Uri.parse(uri)
        "${parsed.scheme}://${parsed.host}${parsed.path ?: ""}"
    }.getOrDefault("RTSP URI")

    private companion object {
        const val TAG = "AudioPlayback"
        const val SAMPLE_RATE_HZ = 8_000
        const val TARGET_OUTPUT_CHUNK_BYTES = 320 // Approximately 20 ms of PCM16 mono.
        const val OUTPUT_WAIT_MS = 100L
        const val DIAGNOSTICS_INTERVAL_MS = 2_000L
    }
}

/** Small testable state used by mute and the raw/processed validation toggle. */
class AudioPlaybackSettings {
    @Volatile
    var muted: Boolean = false

    @Volatile
    var processingEnabled: Boolean = true
}
