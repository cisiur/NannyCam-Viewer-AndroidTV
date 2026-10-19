package com.cisiur.nannycamviewer.platform

import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformDiagnosticsFormatterTest {
    @Test
    fun `formats the key AVC capabilities`() {
        val text = PlatformDiagnosticsFormatter.format(
            PlatformDiagnosticReport(
                manufacturer = "Amazon",
                model = "AFTKRT",
                apiLevel = 30,
                supportedAbis = listOf("armeabi-v7a", "armeabi"),
                pictureInPictureSupported = true,
                avcCodecs = listOf(
                    CodecDiagnostic(
                        name = "OMX.MTK.VIDEO.DECODER.AVC",
                        isEncoder = false,
                        isVendor = true,
                        isSoftwareOnly = false,
                        isHardwareAccelerated = true,
                        supportsAvc = true,
                        supports1920x1080 = true
                    )
                )
            )
        )

        assertTrue(text.contains("Picture-in-Picture feature: available"))
        assertTrue(text.contains("OMX.MTK.VIDEO.DECODER.AVC"))
        assertTrue(text.contains("Supports 1920x1080: true"))
        assertTrue(text.contains("Hardware-accelerated: true"))
    }
}
