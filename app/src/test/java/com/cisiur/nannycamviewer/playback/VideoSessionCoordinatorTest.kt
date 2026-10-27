package com.cisiur.nannycamviewer.playback

import com.cisiur.nannycamviewer.ui.ViewerAudioStartupGate
import org.junit.Assert.*
import org.junit.Test

class VideoSessionCoordinatorTest {
    @Test
    fun `successful RTSP config and output are all required and first output is published once`() {
        val h = Harness()
        h.connect()
        val first = h.latest
        first.events.onWaitingForVideo()
        first.events.onDecoderConfigured("hardware-avc")
        assertEquals(ConnectionState.WAITING_VIDEO, h.video.state)
        assertEquals(0, h.firstOutputs)
        first.events.onOutput(120)
        assertNotEquals(ConnectionState.PLAYING, h.video.state) // PLAY response still pending.
        first.events.onRtspReady()
        assertEquals(ConnectionState.PLAYING, h.video.state)
        assertEquals(1, h.firstOutputs)
        first.events.onOutput(130)
        assertEquals(1, h.firstOutputs)
        first.events.onWaitingForVideo()
        assertEquals(ConnectionState.WAITING_VIDEO, h.video.state)
        first.events.onDecoderConfigured("hardware-avc")
        assertEquals(ConnectionState.WAITING_VIDEO, h.video.state)
        first.events.onOutput(140)
        assertEquals(ConnectionState.PLAYING, h.video.state)
        assertEquals(1, h.firstOutputs)
    }

    @Test
    fun `backoff follows one two three five five and resets only after output success`() {
        val h = Harness()
        h.connect()
        listOf(1000L, 2000L, 3000L, 5000L, 5000L).forEach { delay ->
            h.latest.events.onDecoderConfigured("hardware-avc") // Config alone is not success.
            h.failAndClean()
            assertEquals(delay, h.scheduler.latest.delay)
            h.scheduler.latest.fire()
        }
        h.succeed()
        h.failAndClean()
        assertEquals(1000L, h.scheduler.latest.delay)
    }

    @Test
    fun `startup and transport failures clean resources before scheduling fresh attempts`() {
        listOf(VideoFailureKind.NETWORK, VideoFailureKind.RTSP, VideoFailureKind.CONFIGURATION,
            VideoFailureKind.STARTUP_CONFIG, VideoFailureKind.STARTUP_IDR, VideoFailureKind.STARTUP_OUTPUT,
            VideoFailureKind.RTP_TIMEOUT, VideoFailureKind.KEEPALIVE, VideoFailureKind.DECODER
        ).forEach { kind ->
            val h = Harness()
            h.connect()
            val failed = h.latest
            failed.events.onFailure(VideoFailure(kind, kind.name))
            assertEquals(ConnectionState.RECONNECTING, h.video.state)
            assertEquals(1, failed.stops)
            assertTrue(h.scheduler.tasks.isEmpty()) // No overlapping socket or codec.
            failed.events.onFailure(VideoFailure(kind, "duplicate"))
            assertEquals(1, failed.stops)
            failed.events.onStopped()
            failed.events.onStopped()
            assertEquals(1, h.scheduler.tasks.size)
            h.scheduler.latest.fire()
            assertEquals(2, h.attempts.size)
            assertNotSame(failed, h.latest)
            assertEquals(ConnectionState.RECONNECTING, h.video.state)
        }
    }

    @Test
    fun `disconnect cancels pending retry even if its callback is delivered late`() {
        val h = Harness()
        h.connect()
        h.failAndClean()
        val retry = h.scheduler.latest
        h.disconnect()
        assertTrue(retry.cancelled)
        retry.fire(evenIfCancelled = true)
        assertEquals(1, h.attempts.size)
        assertEquals(ConnectionState.NOT_CONNECTED, h.video.state)
        assertFalse(h.video.hasConnectIntent)
    }

    @Test
    fun `disconnect in each startup stage closes attempt and suppresses late milestones`() {
        listOf("connect", "DESCRIBE", "SETUP", "PLAY", "config", "IDR").forEach { stage ->
            val h = Harness()
            h.connect()
            val attempt = h.latest
            if (stage in listOf("config", "IDR")) attempt.events.onDecoderConfigured("hardware-avc")
            if (stage == "IDR") attempt.events.onRtspReady()
            h.disconnect()
            h.video.awaitSurfaceRelease()
            assertEquals(1, attempt.stops)
            assertEquals(1, attempt.awaited)
            attempt.events.onRtspReady()
            attempt.events.onDecoderConfigured("late hardware-avc")
            attempt.events.onOutput(10)
            attempt.events.onStopped()
            assertEquals(ConnectionState.NOT_CONNECTED, h.video.state)
            assertEquals(0, h.firstOutputs)
            assertTrue(h.scheduler.tasks.isEmpty())
        }
    }

    @Test
    fun `fresh Connect during prior cleanup waits and old callbacks cannot affect it`() {
        val h = Harness()
        h.connect()
        val old = h.latest
        h.disconnect()
        h.gate.beginSession("rtsp://second/streamtype=0")
        assertTrue(h.video.connect("rtsp://second/streamtype=0"))
        assertEquals(1, h.attempts.size)
        old.events.onFailure(VideoFailure(VideoFailureKind.NETWORK, "stale failure"))
        old.events.onOutput(1)
        old.events.onStopped()
        assertEquals(2, h.attempts.size)
        assertEquals("rtsp://second/streamtype=0", h.latest.uri)
        h.succeed()
        assertEquals(ConnectionState.PLAYING, h.video.state)
        old.events.onWaitingForVideo()
        old.events.onStopped()
        assertEquals(ConnectionState.PLAYING, h.video.state)
    }

    @Test
    fun `surface loss releases ownership and retries only after surface recreation`() {
        val h = Harness()
        h.connect()
        h.succeed()
        val old = h.latest
        h.video.setSurfaceAvailable(false)
        h.video.awaitSurfaceRelease()
        assertEquals(1, old.stops)
        assertEquals(1, old.awaited)
        old.events.onStopped()
        assertEquals(ConnectionState.RECONNECTING, h.video.state)
        assertTrue(h.scheduler.tasks.isEmpty())
        h.video.setSurfaceAvailable(true)
        assertEquals(1000L, h.scheduler.latest.delay)
        h.scheduler.latest.fire()
        assertEquals(2, h.attempts.size)
    }

    @Test
    fun `surface interruption of retry does not advance backoff without an attempt`() {
        val h = Harness()
        h.connect()
        h.failAndClean()
        val oldRetry = h.scheduler.latest
        h.video.setSurfaceAvailable(false)
        oldRetry.fire(evenIfCancelled = true)
        assertEquals(1, h.attempts.size)
        h.video.setSurfaceAvailable(true)
        assertEquals(1000L, h.scheduler.latest.delay)
        h.scheduler.latest.fire()
        assertEquals(2, h.attempts.size)
    }

    @Test
    fun `Connect while surface unavailable waits and lifecycle stop cancels intent`() {
        val h = Harness()
        h.gate.beginSession("rtsp://camera/streamtype=0")
        assertTrue(h.video.connect("rtsp://camera/streamtype=0"))
        assertEquals(ConnectionState.WAITING_VIDEO, h.video.state)
        assertTrue(h.attempts.isEmpty())
        h.disconnect() // Same coordinator operation used by Activity stop.
        h.video.setSurfaceAvailable(true)
        assertTrue(h.attempts.isEmpty())
    }

    @Test
    fun `unsupported terminal failure stops without retries but may be explicitly retried`() {
        val h = Harness()
        h.connect()
        h.latest.events.onFailure(VideoFailure(VideoFailureKind.UNSUPPORTED, "hardware policy", terminal = true))
        h.latest.events.onStopped()
        assertEquals(ConnectionState.ERROR, h.video.state)
        assertFalse(h.video.hasConnectIntent)
        assertFalse(h.video.isBusy)
        assertTrue(h.scheduler.tasks.isEmpty())
        assertTrue(h.video.connect("rtsp://camera/streamtype=0"))
        assertEquals(2, h.attempts.size)
    }

    @Test
    fun `first production output schedules audio once and later video retries do not restart it`() {
        val h = Harness()
        h.connect()
        h.succeed()
        assertEquals(250L, ViewerAudioStartupGate.INITIAL_AUDIO_AFTER_FIRST_FRAME_DELAY_MS)
        h.consumeAudioDelay()
        assertEquals(1, h.audioConnects)
        h.failAndClean()
        assertEquals(1, h.audioConnects)
        h.scheduler.latest.fire()
        h.succeed()
        h.consumeAudioDelay()
        assertEquals(1, h.audioConnects)
        assertEquals(2, h.firstOutputs)
    }

    @Test
    fun `failure or Disconnect during audio delay invalidates stale delayed start`() {
        val h = Harness()
        h.connect()
        h.succeed()
        val stale = h.pendingAudioToken!!
        h.failAndClean()
        assertTrue(h.gate.consumeDelayedAudioStart(stale) is ViewerAudioStartupGate.DelayedStartDecision.IgnoredStaleCallback)
        h.scheduler.latest.fire()
        h.succeed()
        val second = h.pendingAudioToken!!
        h.disconnect()
        assertTrue(h.gate.consumeDelayedAudioStart(second) is ViewerAudioStartupGate.DelayedStartDecision.IgnoredStaleCallback)
        assertEquals(0, h.audioConnects)
    }

    @Test
    fun `duplicate production Connect while Playing cannot create extra video or audio sessions`() {
        val h = Harness()
        h.connect()
        h.succeed()
        h.consumeAudioDelay()
        assertEquals(ConnectionState.PLAYING, h.video.state)
        assertFalse(h.video.connect("rtsp://duplicate/streamtype=0"))
        assertEquals(1, h.attempts.size)
        assertEquals(1, h.audioConnects)
    }

    @Test
    fun `Disconnect during attempt state publication cannot start a late worker`() {
        val h = Harness()
        var connectingEvents = 0
        h.stateHook = { state ->
            if (state == ConnectionState.CONNECTING && ++connectingEvents == 2) h.disconnect()
        }
        h.connect()
        assertTrue(h.attempts.isEmpty())
        assertEquals(ConnectionState.NOT_CONNECTED, h.video.state)
        assertFalse(h.video.isBusy)
    }

    @Test
    fun `Disconnect during Playing publication suppresses the late audio milestone`() {
        val h = Harness()
        h.connect()
        h.stateHook = { if (it == ConnectionState.PLAYING) h.disconnect() }
        h.succeed()
        assertEquals(0, h.firstOutputs)
        assertNull(h.pendingAudioToken)
        assertEquals(1, h.latest.stops)
        h.latest.events.onStopped()
        assertEquals(ConnectionState.NOT_CONNECTED, h.video.state)
        assertTrue(h.scheduler.tasks.isEmpty())
    }

    private class Harness {
        val scheduler = Scheduler()
        val attempts = mutableListOf<Attempt>()
        val gate = ViewerAudioStartupGate()
        var firstOutputs = 0
        var audioConnects = 0
        var pendingAudioToken: Long? = null
        var stateHook: ((ConnectionState) -> Unit)? = null
        val latest: Attempt get() = attempts.last()
        val video = VideoSessionCoordinator(
            VideoAttemptFactory { _, uri, events -> Attempt(uri, events).also { attempts += it } },
            scheduler,
            object : VideoPlaybackEvents {
                override fun onConnectionStateChanged(state: ConnectionState) { stateHook?.invoke(state) }
                override fun onSelectedVideoDecoder(decoderName: String, attemptId: Long) = Unit
                override fun onPlaybackError(message: String, attemptId: Long) {
                    if (gate.onVideoFailure(attemptId) is ViewerAudioStartupGate.VideoFailureDecision.CancelPendingAudioStart) {
                        pendingAudioToken = null
                    }
                }
                override fun onFirstVideoOutput(startupLatencyMs: Long, attemptId: Long) {
                    firstOutputs++
                    val decision = gate.onVideoFirstFrame(attemptId)
                    if (decision is ViewerAudioStartupGate.FirstFrameDecision.ScheduleAudio) pendingAudioToken = decision.token
                }
            }
        )
        fun connect() {
            gate.beginSession("rtsp://camera/streamtype=0")
            video.setSurfaceAvailable(true)
            assertTrue(video.connect("rtsp://camera/streamtype=0"))
        }
        fun succeed() {
            latest.events.onRtspReady()
            latest.events.onDecoderConfigured("hardware-avc")
            latest.events.onOutput(100)
        }
        fun failAndClean() {
            latest.events.onFailure(VideoFailure(VideoFailureKind.NETWORK, "network interrupted"))
            latest.events.onStopped()
        }
        fun disconnect() {
            gate.reset()
            pendingAudioToken = null
            video.disconnect()
        }
        fun consumeAudioDelay() {
            val token = pendingAudioToken ?: return
            pendingAudioToken = null
            if (gate.consumeDelayedAudioStart(token) is ViewerAudioStartupGate.DelayedStartDecision.StartAudio) audioConnects++
        }
    }

    private class Attempt(val uri: String, val events: VideoAttemptEvents) : VideoAttempt {
        var stops = 0
        var awaited = 0
        override fun start() = Unit
        override fun stop() { stops++ }
        override fun awaitSurfaceRelease() { awaited++ }
    }
    private class Scheduler : VideoRetryScheduler {
        val tasks = mutableListOf<Task>()
        val latest: Task get() = tasks.last()
        override fun schedule(delayMs: Long, action: () -> Unit): VideoRetryCancellation =
            Task(delayMs, action).also { tasks += it }
    }
    private class Task(val delay: Long, val action: () -> Unit) : VideoRetryCancellation {
        var cancelled = false
        override fun cancel() { cancelled = true }
        fun fire(evenIfCancelled: Boolean = false) { if (!cancelled || evenIfCancelled) action() }
    }
}
