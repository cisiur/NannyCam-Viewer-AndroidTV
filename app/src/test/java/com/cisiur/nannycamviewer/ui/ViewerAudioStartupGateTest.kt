package com.cisiur.nannycamviewer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ViewerAudioStartupGateTest {
    @Test
    fun `new viewer session keeps audio gated until decoder initialization`() {
        val gate = ViewerAudioStartupGate()

        gate.beginSession("rtsp://camera/streamtype=0")

        assertTrue(gate.isAudioStartPending())
        assertTrue(gate.onVideoDecoderInitialized() is ViewerAudioStartupGate.Decision.StartAudio)
    }

    @Test
    fun `decoder initialization starts audio exactly once`() {
        val gate = ViewerAudioStartupGate()
        gate.beginSession("rtsp://camera/streamtype=0")

        val first = gate.onVideoDecoderInitialized()
        val duplicate = gate.onVideoDecoderInitialized()

        assertTrue(first is ViewerAudioStartupGate.Decision.StartAudio)
        assertFalse(gate.isAudioStartPending())
        assertTrue(duplicate is ViewerAudioStartupGate.Decision.AlreadyStarted)
    }

    @Test
    fun `failed video attempts before decoder leave audio pending for a later attempt`() {
        val gate = ViewerAudioStartupGate()
        gate.beginSession("rtsp://camera/streamtype=0")

        assertTrue(gate.remainsGatedAfterVideoFailure())
        assertTrue(gate.remainsGatedAfterVideoFailure())
        assertTrue(gate.onVideoDecoderInitialized() is ViewerAudioStartupGate.Decision.StartAudio)
    }

    @Test
    fun `disconnect prevents stale decoder callback and fresh session gets a new gate`() {
        val gate = ViewerAudioStartupGate()
        gate.beginSession("rtsp://first/streamtype=0")
        gate.reset()

        assertTrue(gate.onVideoDecoderInitialized() is ViewerAudioStartupGate.Decision.NoActiveSession)

        gate.beginSession("rtsp://second/streamtype=0")
        val decision = gate.onVideoDecoderInitialized()
        assertTrue(decision is ViewerAudioStartupGate.Decision.StartAudio)
        assertTrue((decision as ViewerAudioStartupGate.Decision.StartAudio).uri.contains("second"))
    }

    @Test
    fun `later video reconnect events cannot restart already started audio`() {
        val gate = ViewerAudioStartupGate()
        gate.beginSession("rtsp://camera/streamtype=0")
        gate.onVideoDecoderInitialized()

        assertFalse(gate.remainsGatedAfterVideoFailure())
        assertTrue(gate.onVideoDecoderInitialized() is ViewerAudioStartupGate.Decision.AlreadyStarted)
    }
}
