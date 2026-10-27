package com.cisiur.nannycamviewer.customvideo

import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.cisiur.nannycamviewer.playback.VideoAttempt
import com.cisiur.nannycamviewer.playback.VideoAttemptEvents
import com.cisiur.nannycamviewer.playback.VideoFailure
import com.cisiur.nannycamviewer.playback.VideoFailureKind
import com.cisiur.nannycamviewer.playback.VideoPlaybackException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One fresh reference state/socket/codec pair. No replacement is allowed until onStopped. */
class VideoPlaybackAttempt(
    uri: String,
    surface: Surface,
    private val mainHandler: Handler,
    private val events: VideoAttemptEvents
) : VideoAttempt {
    private val cancelled = AtomicBoolean(false)
    private val remaining = AtomicInteger(2)
    private val pipeline = H264VideoPipeline { Log.i(TAG, it) }
    private val session = VideoRtspSession(uri, pipeline) { postMilestone { events.onRtspReady() } }
    private val decoder = MediaCodecVideoDecoder(surface, pipeline, SystemClock.elapsedRealtime(),
        object : MediaCodecVideoDecoder.Listener {
            override fun onWaiting() = postMilestone { events.onWaitingForVideo() }
            override fun onConfigured(name: String) = postMilestone { events.onDecoderConfigured(name) }
            override fun onOutput(elapsedMs: Long) = postMilestone { events.onOutput(elapsedMs) }
        })
    private var decoderThread: Thread? = null
    private var networkThread: Thread? = null
    private var started = false

    override fun start() {
        check(!started)
        started = true
        decoderThread = Thread({ worker(VideoFailureKind.DECODER) { decoder.run { cancelled.get() } } }, "VideoDecoder")
        networkThread = Thread({ worker(VideoFailureKind.NETWORK) { session.run() } }, "VideoRtsp")
        var decoderStarted = false
        var networkStarted = false
        try {
            decoderThread!!.start()
            decoderStarted = true
            networkThread!!.start()
            networkStarted = true
        } catch (error: Exception) {
            reportFailure(error, VideoFailureKind.NETWORK)
            if (!decoderStarted) finishedWorker()
            if (!networkStarted) finishedWorker()
        }
    }

    override fun stop() {
        if (cancelled.getAndSet(true)) return
        pipeline.queue.close()
        session.close()
        decoderThread?.interrupt()
        networkThread?.interrupt()
    }

    override fun awaitSurfaceRelease() {
        // The native codec is only touched by its owner thread, including stop/release.
        var interrupted = false
        val thread = decoderThread
        while (thread?.isAlive == true) {
            try { thread.join() } catch (_: InterruptedException) { interrupted = true }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun worker(kind: VideoFailureKind, action: () -> Unit) {
        try {
            if (!cancelled.get()) action()
            if (!cancelled.get()) reportFailure(IllegalStateException("Video worker ended unexpectedly"), kind)
        } catch (error: Exception) {
            if (!cancelled.get()) reportFailure(error, kind)
        } finally {
            finishedWorker()
        }
    }

    private fun reportFailure(error: Exception, kind: VideoFailureKind) {
        val failure = if (error is VideoPlaybackException) error.failure else VideoFailure(
            kind, (error.message ?: error.javaClass.simpleName)
                .replace(Regex("(?i)(rtsp://)[^\\s/]*@"), "$1<redacted>@")
        )
        Log.e(TAG, "Video attempt failure: ${failure.kind}: ${failure.message}")
        // Post failure before this worker decrements the cleanup latch. onStopped therefore
        // cannot be delivered ahead of failure, even if the other worker exits concurrently.
        mainHandler.post { events.onFailure(failure) }
        stop()
    }

    private fun finishedWorker() {
        if (remaining.decrementAndGet() == 0) {
            pipeline.reset()
            mainHandler.post { events.onStopped() }
        }
    }

    private fun postMilestone(action: () -> Unit) {
        mainHandler.post { if (!cancelled.get()) action() }
    }

    private companion object { const val TAG = "VideoPlayback" }
}
