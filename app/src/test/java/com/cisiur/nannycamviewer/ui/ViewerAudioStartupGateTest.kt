package com.cisiur.nannycamviewer.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerAudioStartupGateTest {
    @Test
    fun `first decoder output is the initial audio gate milestone`() {
        val gate = ViewerAudioStartupGate()
        gate.beginSession("rtsp://camera/streamtype=0")

        assertTrue(gate.onVideoFirstFrame(1) is ViewerAudioStartupGate.FirstFrameDecision.ScheduleAudio)
    }

    @Test
    fun `first frame schedules audio which starts only when delayed token is consumed`() {
        val gate = ViewerAudioStartupGate()
        gate.beginSession("rtsp://camera/streamtype=0")
        val scheduled = gate.onVideoFirstFrame(1) as ViewerAudioStartupGate.FirstFrameDecision.ScheduleAudio

        assertEquals(250L, ViewerAudioStartupGate.INITIAL_AUDIO_AFTER_FIRST_FRAME_DELAY_MS)
        assertTrue(gate.consumeDelayedAudioStart(scheduled.token) is ViewerAudioStartupGate.DelayedStartDecision.StartAudio)
    }

    @Test
    fun `duplicate video output events do not duplicate audio startup`() {
        val gate = ViewerAudioStartupGate()
        gate.beginSession("rtsp://camera/streamtype=0")

        val scheduled = gate.onVideoFirstFrame(1) as ViewerAudioStartupGate.FirstFrameDecision.ScheduleAudio
        assertTrue(gate.onVideoFirstFrame(1) is ViewerAudioStartupGate.FirstFrameDecision.AlreadyScheduled)
        gate.consumeDelayedAudioStart(scheduled.token)

        assertTrue(gate.onVideoFirstFrame(2) is ViewerAudioStartupGate.FirstFrameDecision.AlreadyStarted)
    }

    @Test
    fun `failure before first frame leaves audio gated for a retry`() {
        val gate = ViewerAudioStartupGate()
        gate.beginSession("rtsp://camera/streamtype=0")

        assertTrue(gate.onVideoFailure(1) is ViewerAudioStartupGate.VideoFailureDecision.AudioRemainsGated)
        assertTrue(gate.onVideoFirstFrame(1) is ViewerAudioStartupGate.FirstFrameDecision.IgnoredFailedAttempt)
        assertTrue(gate.onVideoFirstFrame(2) is ViewerAudioStartupGate.FirstFrameDecision.ScheduleAudio)
    }

    @Test
    fun `failure during delay invalidates stale delayed callback and later retry can schedule`() {
        val gate = ViewerAudioStartupGate()
        gate.beginSession("rtsp://camera/streamtype=0")
        val first = gate.onVideoFirstFrame(1) as ViewerAudioStartupGate.FirstFrameDecision.ScheduleAudio

        assertTrue(gate.onVideoFailure(1) is ViewerAudioStartupGate.VideoFailureDecision.CancelPendingAudioStart)
        assertTrue(gate.consumeDelayedAudioStart(first.token) is ViewerAudioStartupGate.DelayedStartDecision.IgnoredStaleCallback)
        assertTrue(gate.onVideoFirstFrame(2) is ViewerAudioStartupGate.FirstFrameDecision.ScheduleAudio)
    }

    @Test
    fun `disconnect or lifecycle reset invalidates delayed callback and fresh session works`() {
        val gate = ViewerAudioStartupGate()
        gate.beginSession("rtsp://first/streamtype=0")
        val first = gate.onVideoFirstFrame(1) as ViewerAudioStartupGate.FirstFrameDecision.ScheduleAudio
        gate.reset()

        assertTrue(gate.consumeDelayedAudioStart(first.token) is ViewerAudioStartupGate.DelayedStartDecision.IgnoredStaleCallback)
        gate.beginSession("rtsp://second/streamtype=0")
        val second = gate.onVideoFirstFrame(2) as ViewerAudioStartupGate.FirstFrameDecision.ScheduleAudio
        val start = gate.consumeDelayedAudioStart(second.token) as ViewerAudioStartupGate.DelayedStartDecision.StartAudio
        assertTrue(start.uri.contains("second"))
    }

    @Test
    fun `later video failure after audio start leaves independent audio start consumed`() {
        val gate = ViewerAudioStartupGate()
        gate.beginSession("rtsp://camera/streamtype=0")
        val scheduled = gate.onVideoFirstFrame(1) as ViewerAudioStartupGate.FirstFrameDecision.ScheduleAudio
        gate.consumeDelayedAudioStart(scheduled.token)

        assertTrue(gate.onVideoFailure(2) is ViewerAudioStartupGate.VideoFailureDecision.AudioAlreadyStarted)
        assertTrue(gate.onVideoFirstFrame(3) is ViewerAudioStartupGate.FirstFrameDecision.AlreadyStarted)
    }

    @Test
    fun `begin session without explicit reset cannot rearm running audio`() {
        val gate = ViewerAudioStartupGate()
        gate.beginSession("rtsp://camera/streamtype=0")
        val initial = gate.onVideoFirstFrame(1) as ViewerAudioStartupGate.FirstFrameDecision.ScheduleAudio
        gate.consumeDelayedAudioStart(initial.token)
        gate.beginSession("rtsp://camera/streamtype=0")
        assertTrue(gate.onVideoFirstFrame(2) is ViewerAudioStartupGate.FirstFrameDecision.AlreadyStarted)
    }
}
