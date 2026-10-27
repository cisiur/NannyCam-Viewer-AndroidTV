package com.cisiur.nannycamviewer.ui

import org.junit.Assert.*
import org.junit.Test

class ViewerControlsTest {
    @Test fun savedEndpointEnablesExplicitConnectAndProbe() {
        val controls = controls()
        assertTrue(controls.canConnect)
        assertTrue(controls.canProbe)
        assertFalse(controls.canDisconnect)
    }

    @Test fun missingOrInvalidEndpointCannotStartAnything() {
        val controls = controls(valid = false)
        assertFalse(controls.canConnect)
        assertFalse(controls.canProbe)
    }

    @Test fun activeOrCleaningVideoBlocksConnectAndProbeButAllowsDisconnect() {
        // Busy includes connecting, waiting, playing, reconnecting and worker cleanup.
        for (session in listOf(false, true)) {
            val controls = controls(busy = true, session = session)
            assertFalse(controls.canConnect)
            assertFalse(controls.canProbe)
            assertTrue(controls.canDisconnect)
        }
    }

    @Test fun terminalVideoErrorStillOffersFullDisconnectAndExplicitRetry() {
        val controls = controls(session = true, audioStopped = false)
        assertTrue(controls.canConnect)
        assertTrue(controls.canDisconnect)
        assertFalse(controls.canProbe)
    }

    @Test fun audioOrViewerIntentBlocksProbeEvenIfVideoIsIdle() {
        assertFalse(controls(session = true).canProbe)
        assertFalse(controls(audioStopped = false).canProbe)
    }

    @Test fun runningProbeBlocksDuplicateProbeAndConnect() {
        val controls = controls(probe = true)
        assertFalse(controls.canConnect)
        assertFalse(controls.canProbe)
        assertFalse(controls.canDisconnect)
        assertTrue(controls().canConnect) // Completion restores normal disconnected controls.
    }

    @Test fun pictureFitsWideTallAndTvSpacesWithoutCropping() {
        assertEquals(1920 to 1080, fitVideoSize(1920, 1080))
        assertEquals(1600 to 900, fitVideoSize(1920, 900))
        assertEquals(960 to 540, fitVideoSize(960, 1000))
        assertEquals(0 to 0, fitVideoSize(0, 0))
        for ((width, height) in listOf(960 to 460, 1920 to 920, 1080 to 1920)) {
            val (pictureWidth, pictureHeight) = fitVideoSize(width, height)
            assertTrue(pictureWidth <= width && pictureHeight <= height)
            assertTrue(kotlin.math.abs(pictureWidth * 9 - pictureHeight * 16) < 16)
        }
    }

    private fun controls(
        valid: Boolean = true,
        busy: Boolean = false,
        session: Boolean = false,
        audioStopped: Boolean = true,
        probe: Boolean = false
    ) = ViewerControls.from(valid, busy, session, audioStopped, probe)
}
