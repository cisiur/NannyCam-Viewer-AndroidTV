package com.cisiur.nannycamviewer.playback

import java.io.IOException

enum class VideoFailureKind { NETWORK, RTSP, CONFIGURATION, STARTUP_CONFIG, STARTUP_IDR, STARTUP_OUTPUT, RTP_TIMEOUT, KEEPALIVE, DECODER, SURFACE, UNSUPPORTED }
data class VideoFailure(val kind: VideoFailureKind, val message: String, val terminal: Boolean = false)
class VideoPlaybackException(val failure: VideoFailure) : IOException(failure.message)

interface VideoPlaybackEvents {
    fun onConnectionStateChanged(state: ConnectionState)
    fun onSelectedVideoDecoder(decoderName: String, attemptId: Long)
    fun onPlaybackError(message: String, attemptId: Long)
    fun onFirstVideoOutput(startupLatencyMs: Long, attemptId: Long)
}

interface VideoAttemptEvents {
    fun onRtspReady()
    fun onDecoderConfigured(name: String)
    fun onWaitingForVideo()
    fun onOutput(startupLatencyMs: Long)
    fun onFailure(failure: VideoFailure)
    fun onStopped()
}

interface VideoAttempt {
    fun start()
    fun stop()
    /** Wait only for decoder release, never for network I/O; used by SurfaceHolder callbacks. */
    fun awaitSurfaceRelease()
}

fun interface VideoAttemptFactory {
    fun create(attemptId: Long, uri: String, events: VideoAttemptEvents): VideoAttempt
}
fun interface VideoRetryCancellation { fun cancel() }
fun interface VideoRetryScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): VideoRetryCancellation
}

/** Main-thread policy with injectable attempts/timers. All callback validity checks live here. */
class VideoSessionCoordinator(
    private val factory: VideoAttemptFactory,
    private val scheduler: VideoRetryScheduler,
    private val listener: VideoPlaybackEvents,
    private val log: (String) -> Unit = {}
) {
    private val backoff = ReconnectPolicy()
    private var uri: String? = null
    private var generation = 0L
    private var nextAttemptId = 0L
    private var active: Attempt? = null
    private var surfaceAvailable = false
    private var needsRetry = false
    private var retryDelayMs: Long? = null
    private var retry: Retry? = null
    var state = ConnectionState.NOT_CONNECTED
        private set
    val hasConnectIntent: Boolean get() = uri != null
    val isBusy: Boolean get() = hasConnectIntent || active != null || retry != null

    fun connect(endpoint: String): Boolean {
        if (hasConnectIntent) return false
        generation++
        uri = endpoint
        needsRetry = false
        cancelRetry(clearDelay = true)
        backoff.reset()
        setState(if (surfaceAvailable) ConnectionState.CONNECTING else ConnectionState.WAITING_VIDEO)
        log("Viewer video connect requested")
        continueIntent()
        return true
    }

    fun disconnect() {
        generation++
        uri = null
        needsRetry = false
        cancelRetry(clearDelay = true)
        backoff.cancel()
        active?.let { attempt ->
            attempt.stopping = true
            stopHandle(attempt)
        }
        setState(ConnectionState.NOT_CONNECTED)
        log("Video disconnected")
    }

    fun setSurfaceAvailable(available: Boolean) {
        surfaceAvailable = available
        if (!available) {
            cancelRetry(clearDelay = false)
            val attempt = active
            if (attempt != null && isCurrent(attempt)) {
                fail(attempt, VideoFailure(VideoFailureKind.SURFACE, "Video surface unavailable"))
            } else if (hasConnectIntent) {
                setState(if (needsRetry) ConnectionState.RECONNECTING else ConnectionState.WAITING_VIDEO)
            }
        } else continueIntent()
    }

    fun awaitSurfaceRelease() { active?.handle?.awaitSurfaceRelease() }

    private fun continueIntent() {
        if (!hasConnectIntent || active != null || !surfaceAvailable || retry != null) return
        if (!needsRetry) {
            startAttempt(reconnecting = false)
            return
        }
        val delay = retryDelayMs ?: backoff.nextDelayMs()?.also { retryDelayMs = it } ?: return
        val ticket = Retry(generation)
        retry = ticket
        log("Reconnect scheduled in ${delay}ms")
        ticket.cancellation = scheduler.schedule(delay) {
            if (retry !== ticket || generation != ticket.generation || !hasConnectIntent) return@schedule
            retry = null
            if (!surfaceAvailable || active != null) return@schedule
            backoff.retryStarted()
            retryDelayMs = null
            needsRetry = false
            log("Reconnect attempt")
            startAttempt(reconnecting = true)
        }
    }

    private fun startAttempt(reconnecting: Boolean) {
        val endpoint = uri ?: return
        val attempt = Attempt(++nextAttemptId, generation)
        active = attempt
        setState(if (reconnecting) ConnectionState.RECONNECTING else ConnectionState.CONNECTING)
        if (!isCurrent(attempt)) {
            stopped(attempt) // A listener may have disconnected during state publication.
            return
        }
        log("Video attempt #${attempt.id}")
        val events = object : VideoAttemptEvents {
            override fun onRtspReady() {
                if (!isCurrent(attempt)) return
                attempt.rtspReady = true
                publishOutput(attempt)
            }
            override fun onDecoderConfigured(name: String) {
                if (!isCurrent(attempt)) return
                attempt.decoderConfigured = true
                listener.onSelectedVideoDecoder(name, attempt.id)
                publishOutput(attempt)
            }
            override fun onWaitingForVideo() {
                if (!isCurrent(attempt)) return
                attempt.outputLatencyMs = null
                if (state != ConnectionState.RECONNECTING) setState(ConnectionState.WAITING_VIDEO)
            }
            override fun onOutput(startupLatencyMs: Long) {
                if (!isCurrent(attempt)) return
                attempt.outputLatencyMs = startupLatencyMs
                publishOutput(attempt)
            }
            override fun onFailure(failure: VideoFailure) = fail(attempt, failure)
            override fun onStopped() = stopped(attempt)
        }
        try {
            attempt.handle = factory.create(attempt.id, endpoint, events)
            attempt.handle!!.start()
        } catch (error: Exception) {
            fail(attempt, if (error is VideoPlaybackException) error.failure else
                VideoFailure(VideoFailureKind.NETWORK, error.message ?: "Video attempt could not start"))
            if (attempt.handle == null) stopped(attempt)
        }
    }

    private fun publishOutput(attempt: Attempt) {
        if (!isCurrent(attempt)) return
        val latency = attempt.outputLatencyMs ?: return
        if (!attempt.rtspReady || !attempt.decoderConfigured) return
        attempt.outputLatencyMs = null
        setState(ConnectionState.PLAYING)
        if (!isCurrent(attempt)) return
        if (!attempt.firstOutputPublished) {
            attempt.firstOutputPublished = true
            backoff.reset()
            log("First video output after ${latency}ms; video Playing")
            listener.onFirstVideoOutput(latency, attempt.id)
        }
    }

    private fun fail(attempt: Attempt, failure: VideoFailure) {
        if (!isCurrent(attempt)) return
        attempt.stopping = true
        needsRetry = !failure.terminal
        if (failure.terminal) uri = null
        log("Video failure: ${failure.kind}: ${failure.message}")
        listener.onPlaybackError(failure.message, attempt.id)
        if (active === attempt && generation == attempt.generation) {
            setState(if (failure.terminal) ConnectionState.ERROR else ConnectionState.RECONNECTING)
        }
        stopHandle(attempt)
    }

    private fun stopHandle(attempt: Attempt) {
        val handle = attempt.handle ?: return
        if (attempt.stopRequested) return
        attempt.stopRequested = true
        handle.stop()
    }

    private fun stopped(attempt: Attempt) {
        if (active !== attempt) return
        if (isCurrent(attempt)) fail(attempt, VideoFailure(VideoFailureKind.NETWORK, "Video attempt ended unexpectedly"))
        active = null
        // Also refresh UI busy/cleanup controls when the visible enum did not change.
        listener.onConnectionStateChanged(state)
        continueIntent()
    }

    private fun isCurrent(attempt: Attempt): Boolean = active === attempt &&
        attempt.generation == generation && hasConnectIntent && !attempt.stopping

    private fun cancelRetry(clearDelay: Boolean) {
        retry?.cancellation?.cancel()
        retry = null
        if (clearDelay) retryDelayMs = null
    }

    private fun setState(next: ConnectionState) {
        state = next
        listener.onConnectionStateChanged(next)
    }

    private class Attempt(val id: Long, val generation: Long) {
        var handle: VideoAttempt? = null
        var stopping = false
        var stopRequested = false
        var rtspReady = false
        var decoderConfigured = false
        var outputLatencyMs: Long? = null
        var firstOutputPublished = false
    }
    private class Retry(val generation: Long) { var cancellation: VideoRetryCancellation? = null }
}
