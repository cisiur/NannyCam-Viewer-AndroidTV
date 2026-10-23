package com.cisiur.nannycamviewer.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView

/**
 * Owns a single video-only Media3 RTSP session. Media3 selects the platform decoder;
 * this controller only observes and reports that selection.
 */
@UnstableApi
class VideoPlaybackController(
    context: Context,
    private val playerView: PlayerView,
    private val listener: Listener
) {
    interface Listener {
        fun onConnectionStateChanged(state: ConnectionState)
        fun onSelectedVideoDecoder(decoderName: String, attemptId: Long)
        fun onPlaybackError(message: String, attemptId: Long)
        fun onFirstFrameRendered(startupLatencyMs: Long, attemptId: Long)
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val stateMachine = ConnectionStateMachine()
    private val reconnectPolicy = ReconnectPolicy()
    private val firstFrameTimeout = FirstFrameStartupTimeout()

    private var player: ExoPlayer? = null
    private var cameraUri: String? = null
    private var connectIntent = false
    private var attemptStartedAtMs = 0L
    private var firstFrameReported = false
    private var playerAttemptId = 0L
    private var bufferingMode = VideoBufferingMode.LOW_LATENCY

    private val reconnectRunnable = Runnable {
        reconnectPolicy.retryStarted()
        val uri = cameraUri
        if (!connectIntent || uri == null) return@Runnable
        Log.i(TAG, "Reconnect attempt")
        startPlayer(uri, reconnecting = true)
    }

    private val firstFrameTimeoutRunnable = Runnable {
        val attemptId = playerAttemptId
        if (!firstFrameTimeout.expire(attemptId) || !connectIntent) return@Runnable
        Log.w(TAG, "First-frame timeout expired; reconnecting")
        handlePlaybackFailure("First video frame timed out", attemptId)
    }

    val state: ConnectionState
        get() = stateMachine.state

    val currentBufferingMode: VideoBufferingMode
        get() = bufferingMode

    /** Applies only when a subsequent player is created. */
    fun toggleBufferingMode(): VideoBufferingMode {
        bufferingMode = bufferingMode.toggled()
        return bufferingMode
    }

    fun connect(uri: String) {
        if (!stateMachine.beginConnect()) return
        connectIntent = true
        cameraUri = uri
        reconnectPolicy.reset()
        notifyState()
        Log.i(TAG, "Connect requested for ${uriForLog(uri)}")
        startPlayer(uri, reconnecting = false)
    }

    fun disconnect() {
        if (!connectIntent && stateMachine.state == ConnectionState.NOT_CONNECTED) return
        Log.i(TAG, "Disconnect requested")
        connectIntent = false
        cameraUri = null
        reconnectPolicy.cancel()
        handler.removeCallbacks(reconnectRunnable)
        cancelFirstFrameTimeout()
        releasePlayer()
        stateMachine.disconnect()
        notifyState()
    }

    fun releaseForLifecycle() {
        disconnect()
    }

    private fun startPlayer(uri: String, reconnecting: Boolean) {
        handler.removeCallbacks(reconnectRunnable)
        cancelFirstFrameTimeout()
        releasePlayer()

        if (reconnecting) {
            // The state remains RECONNECTING until the player reports actual playback.
            Log.i(TAG, "Creating replacement player")
        } else {
            Log.i(TAG, "Creating player")
        }
        attemptStartedAtMs = SystemClock.elapsedRealtime()
        firstFrameReported = false
        playerAttemptId++
        val attemptId = playerAttemptId
        val attemptBufferingMode = bufferingMode
        Log.i(TAG, "Video attempt #$attemptId started")
        Log.i(TAG, "Video buffering mode: $attemptBufferingMode")

        val trackSelector = DefaultTrackSelector(appContext).apply {
            parameters = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }
        val playerBuilder = ExoPlayer.Builder(appContext)
            .setTrackSelector(trackSelector)
        VideoBufferingPolicy.durationsFor(attemptBufferingMode)?.let { durations ->
            playerBuilder.setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        durations.minBufferMs,
                        durations.maxBufferMs,
                        durations.bufferForPlaybackMs,
                        durations.bufferForPlaybackAfterRebufferMs
                    )
                    .build()
            )
        }
        val newPlayer = playerBuilder.build()
        Log.i(TAG, "Player created after ${elapsedSinceAttemptMs(attemptId)}ms")
        newPlayer.addListener(createPlayerListener(attemptId))
        newPlayer.addAnalyticsListener(createAnalyticsListener(attemptId))
        player = newPlayer
        playerView.player = newPlayer

        val mediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setTimeoutMs(RTSP_TIMEOUT_MS)
            .createMediaSource(MediaItem.fromUri(uri))
        Log.i(TAG, "RTSP preparation started with RTP-over-RTSP/TCP")
        newPlayer.setMediaSource(mediaSource)
        firstFrameTimeout.arm(attemptId)
        handler.postDelayed(firstFrameTimeoutRunnable, VideoStartupPolicy.FIRST_FRAME_TIMEOUT_MS)
        Log.i(TAG, "First-frame timeout armed: ${VideoStartupPolicy.FIRST_FRAME_TIMEOUT_MS}ms")
        Log.i(TAG, "prepare() called after ${elapsedSinceAttemptMs(attemptId)}ms")
        newPlayer.prepare()
        newPlayer.playWhenReady = true
    }

    private fun createPlayerListener(attemptId: Long) = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (attemptId != playerAttemptId) return
            Log.i(TAG, "Playback state changed: $playbackState")
            if (playbackState == Player.STATE_READY && player?.playWhenReady == true) {
                Log.i(TAG, "Playback STATE_READY after ${elapsedSinceAttemptMs(attemptId)}ms")
                stateMachine.playbackStarted()
                reconnectPolicy.reset()
                notifyState()
            }
        }

        override fun onRenderedFirstFrame() {
            if (attemptId != playerAttemptId) return
            if (!firstFrameReported) {
                firstFrameReported = true
                if (firstFrameTimeout.firstFrameRendered(attemptId)) {
                    handler.removeCallbacks(firstFrameTimeoutRunnable)
                    Log.i(TAG, "First video frame rendered; startup timeout cancelled")
                }
                val startupLatencyMs = elapsedSinceAttemptMs(attemptId)
                Log.i(TAG, "First video frame rendered after ${startupLatencyMs}ms")
                listener.onFirstFrameRendered(startupLatencyMs, attemptId)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            if (attemptId != playerAttemptId) return
            Log.e(TAG, "Playback error: ${error.errorCodeName}", error)
            handlePlaybackFailure(error.errorCodeName, attemptId)
        }
    }

    private fun createAnalyticsListener(attemptId: Long) = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            if (attemptId != playerAttemptId) return
            Log.i(
                TAG,
                "Selected video decoder: $decoderName after ${elapsedSinceAttemptMs(attemptId)}ms"
            )
            listener.onSelectedVideoDecoder(decoderName, attemptId)
        }
    }

    private fun elapsedSinceAttemptMs(attemptId: Long): Long =
        if (attemptId == playerAttemptId) SystemClock.elapsedRealtime() - attemptStartedAtMs else 0L

    private fun handlePlaybackFailure(message: String, attemptId: Long = playerAttemptId) {
        if (!connectIntent || attemptId != playerAttemptId) return
        cancelFirstFrameTimeout()
        listener.onPlaybackError(message, attemptId)
        releasePlayer()
        stateMachine.playbackFailed()
        notifyState()
        val delayMs = reconnectPolicy.nextDelayMs() ?: return
        Log.i(TAG, "Reconnect scheduled in ${delayMs}ms")
        handler.postDelayed(reconnectRunnable, delayMs)
    }

    private fun releasePlayer() {
        val releasedPlayer = player ?: return
        Log.i(TAG, "Releasing player")
        playerView.player = null
        player = null
        releasedPlayer.release()
    }

    private fun cancelFirstFrameTimeout() {
        handler.removeCallbacks(firstFrameTimeoutRunnable)
        firstFrameTimeout.cancel()
    }

    private fun notifyState() = listener.onConnectionStateChanged(stateMachine.state)

    private fun uriForLog(uri: String): String = runCatching {
        val parsed = android.net.Uri.parse(uri)
        "${parsed.scheme}://${parsed.host}${parsed.path ?: ""}"
    }.getOrDefault("RTSP URI")

    private companion object {
        const val TAG = "VideoPlayback"
        // This remains an RTSP transport/source timeout, not an application-level frame watchdog.
        const val RTSP_TIMEOUT_MS = 5_000L
    }
}
