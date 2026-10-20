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
        fun onSelectedVideoDecoder(decoderName: String)
        fun onPlaybackError(message: String)
        fun onFirstFrameRendered(startupLatencyMs: Long)
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val stateMachine = ConnectionStateMachine()
    private val reconnectPolicy = ReconnectPolicy()

    private var player: ExoPlayer? = null
    private var cameraUri: String? = null
    private var connectIntent = false
    private var connectStartedAtMs = 0L
    private var lastVideoProgressAtMs = 0L
    private var firstFrameReported = false

    private val reconnectRunnable = Runnable {
        reconnectPolicy.retryStarted()
        val uri = cameraUri
        if (!connectIntent || uri == null) return@Runnable
        Log.i(TAG, "Reconnect attempt")
        startPlayer(uri, reconnecting = true)
    }

    private val livenessRunnable = object : Runnable {
        override fun run() {
            if (!connectIntent || stateMachine.state == ConnectionState.NOT_CONNECTED) return
            val elapsedSinceProgress = SystemClock.elapsedRealtime() - lastVideoProgressAtMs
            if (elapsedSinceProgress >= LIVENESS_TIMEOUT_MS) {
                Log.w(TAG, "Video liveness timeout after ${elapsedSinceProgress}ms")
                handlePlaybackFailure("Video progress timed out")
                return
            }
            handler.postDelayed(this, LIVENESS_CHECK_INTERVAL_MS)
        }
    }

    val state: ConnectionState
        get() = stateMachine.state

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
        handler.removeCallbacks(livenessRunnable)
        releasePlayer()
        stateMachine.disconnect()
        notifyState()
    }

    fun releaseForLifecycle() {
        disconnect()
    }

    private fun startPlayer(uri: String, reconnecting: Boolean) {
        handler.removeCallbacks(reconnectRunnable)
        handler.removeCallbacks(livenessRunnable)
        releasePlayer()

        if (reconnecting) {
            // The state remains RECONNECTING until the player reports actual playback.
            Log.i(TAG, "Creating replacement player")
        } else {
            Log.i(TAG, "Creating player")
        }
        connectStartedAtMs = SystemClock.elapsedRealtime()
        lastVideoProgressAtMs = connectStartedAtMs
        firstFrameReported = false

        val trackSelector = DefaultTrackSelector(appContext).apply {
            parameters = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }
        val newPlayer = ExoPlayer.Builder(appContext)
            .setTrackSelector(trackSelector)
            .build()
        newPlayer.addListener(playerListener)
        newPlayer.addAnalyticsListener(analyticsListener)
        player = newPlayer
        playerView.player = newPlayer

        val mediaSource = RtspMediaSource.Factory()
            .setForceUseRtpTcp(true)
            .setTimeoutMs(LIVENESS_TIMEOUT_MS)
            .createMediaSource(MediaItem.fromUri(uri))
        Log.i(TAG, "RTSP preparation started with RTP-over-RTSP/TCP")
        newPlayer.setMediaSource(mediaSource)
        newPlayer.prepare()
        newPlayer.playWhenReady = true
        handler.postDelayed(livenessRunnable, LIVENESS_CHECK_INTERVAL_MS)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            Log.i(TAG, "Playback state changed: $playbackState")
            if (playbackState == Player.STATE_READY && player?.playWhenReady == true) {
                lastVideoProgressAtMs = SystemClock.elapsedRealtime()
                stateMachine.playbackStarted()
                reconnectPolicy.reset()
                notifyState()
            }
        }

        override fun onRenderedFirstFrame() {
            lastVideoProgressAtMs = SystemClock.elapsedRealtime()
            if (!firstFrameReported) {
                firstFrameReported = true
                val startupLatencyMs = lastVideoProgressAtMs - connectStartedAtMs
                Log.i(TAG, "First video frame rendered; startup latency ${startupLatencyMs}ms")
                listener.onFirstFrameRendered(startupLatencyMs)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback error: ${error.errorCodeName}", error)
            handlePlaybackFailure(error.errorCodeName)
        }
    }

    private val analyticsListener = object : AnalyticsListener {
        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            Log.i(TAG, "Selected video decoder: $decoderName")
            listener.onSelectedVideoDecoder(decoderName)
        }

        override fun onVideoFrameProcessingOffset(
            eventTime: AnalyticsListener.EventTime,
            totalProcessingOffsetUs: Long,
            frameCount: Int
        ) {
            if (frameCount > 0) lastVideoProgressAtMs = SystemClock.elapsedRealtime()
        }
    }

    private fun handlePlaybackFailure(message: String) {
        if (!connectIntent) return
        handler.removeCallbacks(livenessRunnable)
        listener.onPlaybackError(message)
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

    private fun notifyState() = listener.onConnectionStateChanged(stateMachine.state)

    private fun uriForLog(uri: String): String = runCatching {
        val parsed = android.net.Uri.parse(uri)
        "${parsed.scheme}://${parsed.host}${parsed.path ?: ""}"
    }.getOrDefault("RTSP URI")

    private companion object {
        const val TAG = "VideoPlayback"
        const val LIVENESS_TIMEOUT_MS = 5_000L
        const val LIVENESS_CHECK_INTERVAL_MS = 1_000L
    }
}
