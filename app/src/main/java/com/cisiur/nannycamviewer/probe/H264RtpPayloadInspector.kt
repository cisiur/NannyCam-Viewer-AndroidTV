package com.cisiur.nannycamviewer.probe

sealed class H264PayloadInspection {
    abstract val nalTypes: List<Int>

    data class SingleNal(override val nalTypes: List<Int>) : H264PayloadInspection()
    data class StapA(override val nalTypes: List<Int>) : H264PayloadInspection()
    data class FuA(
        override val nalTypes: List<Int>,
        val start: Boolean,
        val end: Boolean
    ) : H264PayloadInspection()
    data class Unsupported(val nalType: Int) : H264PayloadInspection() {
        override val nalTypes: List<Int> = emptyList()
    }
    object Malformed : H264PayloadInspection() {
        override val nalTypes: List<Int> = emptyList()
    }
}

/** Bounded structural inspection only; it never reconstructs or decodes H.264 frames. */
object H264RtpPayloadInspector {
    fun inspect(payload: ByteArray): H264PayloadInspection {
        if (payload.isEmpty()) return H264PayloadInspection.Malformed
        return when (val nalType = payload[0].toInt() and NAL_TYPE_MASK) {
            in 1..23 -> H264PayloadInspection.SingleNal(listOf(nalType))
            NAL_TYPE_STAP_A -> inspectStapA(payload)
            NAL_TYPE_FU_A -> inspectFuA(payload)
            else -> H264PayloadInspection.Unsupported(nalType)
        }
    }

    private fun inspectStapA(payload: ByteArray): H264PayloadInspection {
        var offset = 1
        val nalTypes = mutableListOf<Int>()
        while (offset < payload.size) {
            if (offset + 2 > payload.size) return H264PayloadInspection.Malformed
            val length = ((payload[offset].toInt() and 0xff) shl 8) or (payload[offset + 1].toInt() and 0xff)
            offset += 2
            if (length <= 0 || offset + length > payload.size) return H264PayloadInspection.Malformed
            nalTypes += payload[offset].toInt() and NAL_TYPE_MASK
            offset += length
        }
        return if (nalTypes.isEmpty()) H264PayloadInspection.Malformed else H264PayloadInspection.StapA(nalTypes)
    }

    private fun inspectFuA(payload: ByteArray): H264PayloadInspection {
        if (payload.size < 2) return H264PayloadInspection.Malformed
        val fuHeader = payload[1].toInt() and 0xff
        val originalNalType = fuHeader and NAL_TYPE_MASK
        if (originalNalType !in 1..23) return H264PayloadInspection.Malformed
        return H264PayloadInspection.FuA(
            nalTypes = listOf(originalNalType),
            start = fuHeader and FU_START_BIT != 0,
            end = fuHeader and FU_END_BIT != 0
        )
    }

    private const val NAL_TYPE_MASK = 0x1f
    private const val NAL_TYPE_STAP_A = 24
    private const val NAL_TYPE_FU_A = 28
    private const val FU_START_BIT = 0x80
    private const val FU_END_BIT = 0x40
}

data class H264ProbeSummary(
    val rtpPackets: Int,
    val singleNalPackets: Int,
    val stapAPackets: Int,
    val fuAPackets: Int,
    val fuAStartFragments: Int,
    val fuAEndFragments: Int,
    val nalTypeCounts: Map<Int, Int>,
    val spsInSdp: Boolean,
    val ppsInSdp: Boolean,
    val spsInRtp: Boolean,
    val ppsInRtp: Boolean,
    val idrInRtp: Boolean,
    val nonIdrInRtp: Boolean,
    val malformedOrUnsupportedPayloads: Int,
    val firstSequenceNumber: Int?,
    val lastSequenceNumber: Int?
)

class H264ProbeStatistics(track: H264SdpTrack) {
    private var rtpPackets = 0
    private var singleNalPackets = 0
    private var stapAPackets = 0
    private var fuAPackets = 0
    private var fuAStartFragments = 0
    private var fuAEndFragments = 0
    private val nalTypeCounts = sortedMapOf<Int, Int>()
    private var spsInRtp = false
    private var ppsInRtp = false
    private var idrInRtp = false
    private var nonIdrInRtp = false
    private var malformedOrUnsupportedPayloads = 0
    private var firstSequenceNumber: Int? = null
    private var lastSequenceNumber: Int? = null
    private val spsInSdp = track.parameterSets.any { it.nalType == H264SdpTrack.NAL_TYPE_SPS }
    private val ppsInSdp = track.parameterSets.any { it.nalType == H264SdpTrack.NAL_TYPE_PPS }

    fun observe(sequenceNumber: Int, payload: ByteArray): H264PayloadInspection {
        rtpPackets++
        if (firstSequenceNumber == null) firstSequenceNumber = sequenceNumber
        lastSequenceNumber = sequenceNumber
        val inspection = H264RtpPayloadInspector.inspect(payload)
        when (inspection) {
            is H264PayloadInspection.SingleNal -> {
                singleNalPackets++
                observeNalTypes(inspection.nalTypes)
            }
            is H264PayloadInspection.StapA -> {
                stapAPackets++
                observeNalTypes(inspection.nalTypes)
            }
            is H264PayloadInspection.FuA -> {
                fuAPackets++
                if (inspection.start) fuAStartFragments++
                if (inspection.end) fuAEndFragments++
                observeNalTypes(inspection.nalTypes)
            }
            H264PayloadInspection.Malformed,
            is H264PayloadInspection.Unsupported -> malformedOrUnsupportedPayloads++
        }
        return inspection
    }

    fun observeMalformedRtpPacket() {
        malformedOrUnsupportedPayloads++
    }

    fun summary(): H264ProbeSummary = H264ProbeSummary(
        rtpPackets = rtpPackets,
        singleNalPackets = singleNalPackets,
        stapAPackets = stapAPackets,
        fuAPackets = fuAPackets,
        fuAStartFragments = fuAStartFragments,
        fuAEndFragments = fuAEndFragments,
        nalTypeCounts = nalTypeCounts.toMap(),
        spsInSdp = spsInSdp,
        ppsInSdp = ppsInSdp,
        spsInRtp = spsInRtp,
        ppsInRtp = ppsInRtp,
        idrInRtp = idrInRtp,
        nonIdrInRtp = nonIdrInRtp,
        malformedOrUnsupportedPayloads = malformedOrUnsupportedPayloads,
        firstSequenceNumber = firstSequenceNumber,
        lastSequenceNumber = lastSequenceNumber
    )

    private fun observeNalTypes(nalTypes: List<Int>) {
        nalTypes.forEach { nalType ->
            nalTypeCounts[nalType] = (nalTypeCounts[nalType] ?: 0) + 1
            when (nalType) {
                1 -> nonIdrInRtp = true
                5 -> idrInRtp = true
                7 -> spsInRtp = true
                8 -> ppsInRtp = true
            }
        }
    }
}
