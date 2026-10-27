package com.cisiur.nannycamviewer.probe

data class ProbeTransport(
    val originalHeader: String?,
    val rtpChannel: Int,
    val rtcpChannel: Int,
    val negotiationConfirmed: Boolean
)

/** Diagnostic-only Transport parsing; absent/invalid negotiation retains the requested pair. */
object ProbeTransportParser {
    const val REQUESTED_INTERLEAVED = "0-1"
    private val channelPair = Regex("(\\d+)\\s*-\\s*(\\d+)")

    fun parse(header: String?): ProbeTransport {
        val parameter = header?.split(';')?.firstOrNull {
            it.substringBefore('=').trim().equals("interleaved", ignoreCase = true)
        }
        val match = parameter?.substringAfter('=', "")?.trim()?.let(channelPair::matchEntire)
        val rtp = match?.groupValues?.get(1)?.toIntOrNull()
        val rtcp = match?.groupValues?.get(2)?.toIntOrNull()
        return if (rtp != null && rtcp != null && rtp in 0..255 && rtcp in 0..255 && rtp != rtcp) {
            ProbeTransport(header, rtp, rtcp, negotiationConfirmed = true)
        } else {
            ProbeTransport(header, 0, 1, negotiationConfirmed = false)
        }
    }
}
