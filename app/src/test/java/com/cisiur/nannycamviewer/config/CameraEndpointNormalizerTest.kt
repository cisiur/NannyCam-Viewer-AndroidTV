package com.cisiur.nannycamviewer.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraEndpointNormalizerTest {
    @Test
    fun `normalizes an IPv4 host to the preferred camera stream`() {
        assertEquals(
            "rtsp://192.168.50.207:554/streamtype=0",
            validUri("192.168.50.207")
        )
    }

    @Test
    fun `normalizes a DNS host to the preferred camera stream`() {
        assertEquals("rtsp://camera.local:554/streamtype=0", validUri("camera.local"))
    }

    @Test
    fun `preserves a valid complete RTSP URI`() {
        val uri = "rtsp://192.168.50.207:554/streamtype=0"
        assertEquals(uri, validUri(uri))
    }

    @Test
    fun `rejects empty and malformed inputs`() {
        assertInvalid("")
        assertInvalid("camera local")
        assertInvalid("999.168.50.207")
        assertInvalid("http://camera.local/live")
        assertInvalid("rtsp://")
    }

    private fun validUri(input: String): String =
        (CameraEndpointNormalizer.normalize(input) as CameraEndpointNormalizer.Result.Valid).uri

    private fun assertInvalid(input: String) {
        assertTrue(CameraEndpointNormalizer.normalize(input) is CameraEndpointNormalizer.Result.Invalid)
    }
}
