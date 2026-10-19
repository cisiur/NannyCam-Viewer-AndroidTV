package com.cisiur.nannycamviewer.platform

import android.content.Context
import android.media.MediaCodecList
import android.os.Build

data class CodecDiagnostic(
    val name: String,
    val isEncoder: Boolean,
    val isVendor: Boolean?,
    val isSoftwareOnly: Boolean?,
    val isHardwareAccelerated: Boolean?,
    val supportsAvc: Boolean,
    val supports1920x1080: Boolean
)

data class PlatformDiagnosticReport(
    val manufacturer: String,
    val model: String,
    val apiLevel: Int,
    val supportedAbis: List<String>,
    val pictureInPictureSupported: Boolean,
    val avcCodecs: List<CodecDiagnostic>
)

object PlatformDiagnostics {
    fun collect(context: Context): PlatformDiagnosticReport = PlatformDiagnosticReport(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        apiLevel = Build.VERSION.SDK_INT,
        supportedAbis = Build.SUPPORTED_ABIS.toList(),
        pictureInPictureSupported = context.packageManager.hasSystemFeature(
            android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE
        ),
        avcCodecs = inspectAvcDecoders()
    )

    private fun inspectAvcDecoders(): List<CodecDiagnostic> =
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filterNot { it.isEncoder }
            .filter { info -> info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) } }
            .map { info ->
                val avcType = info.supportedTypes.first { it.equals("video/avc", ignoreCase = true) }
                val supportsFullHd = runCatching {
                    info.getCapabilitiesForType(avcType).videoCapabilities?.isSizeSupported(1920, 1080) == true
                }.getOrDefault(false)
                CodecDiagnostic(
                    name = info.name,
                    isEncoder = info.isEncoder,
                    isVendor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isVendor else null,
                    isSoftwareOnly = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isSoftwareOnly else null,
                    isHardwareAccelerated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.isHardwareAccelerated else null,
                    supportsAvc = true,
                    supports1920x1080 = supportsFullHd
                )
            }
            .toList()
}

object PlatformDiagnosticsFormatter {
    fun format(report: PlatformDiagnosticReport): String = buildString {
        appendLine("Manufacturer: ${report.manufacturer}")
        appendLine("Model: ${report.model}")
        appendLine("Android API level: ${report.apiLevel}")
        appendLine("Supported ABIs: ${report.supportedAbis.joinToString()}")
        appendLine("Picture-in-Picture feature: ${if (report.pictureInPictureSupported) "available" else "not available"}")
        appendLine()
        appendLine("H.264 / video/avc decoders:")
        if (report.avcCodecs.isEmpty()) {
            append("None reported by MediaCodecList")
        } else {
            report.avcCodecs.forEachIndexed { index, codec ->
                if (index > 0) appendLine().appendLine()
                appendLine(codec.name)
                appendLine("  Encoder: ${codec.isEncoder}")
                appendLine("  Supports video/avc: ${codec.supportsAvc}")
                appendLine("  Supports 1920x1080: ${codec.supports1920x1080}")
                appendLine("  Vendor: ${codec.isVendor ?: "unavailable"}")
                appendLine("  Software-only: ${codec.isSoftwareOnly ?: "unavailable"}")
                append("  Hardware-accelerated: ${codec.isHardwareAccelerated ?: "unavailable"}")
            }
        }
    }
}
