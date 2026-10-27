package com.cisiur.nannycamviewer.probe

import com.cisiur.nannycamviewer.audio.AudioSdpParser

data class H264ParameterSet(val nalType: Int, val byteCount: Int)

data class H264SdpTrack(
    val mediaLine: String,
    val payloadType: Int,
    val rtpMap: String?,
    val fmtp: String?,
    val packetizationMode: String?,
    val spropParameterSetsPresent: Boolean,
    val parameterSets: List<H264ParameterSet>,
    val control: String
) {
    val spsByteCount: Int?
        get() = parameterSets.firstOrNull { it.nalType == NAL_TYPE_SPS }?.byteCount

    val ppsByteCount: Int?
        get() = parameterSets.firstOrNull { it.nalType == NAL_TYPE_PPS }?.byteCount

    companion object {
        const val NAL_TYPE_SPS = 7
        const val NAL_TYPE_PPS = 8
    }
}

/** Parses only the H.264 video SDP fields needed by the diagnostic probe. */
object H264SdpParser {
    /** Raw NALs for the production AVC CSD cache; probe metadata remains unchanged. */
    fun parameterSetNals(track: H264SdpTrack): List<ByteArray> =
        parseFmtpParameters(track.fmtp)["sprop-parameter-sets"]
            ?.split(',')?.mapNotNull(Base64PayloadDecoder::decode).orEmpty()

    fun findVideoTrack(sdp: String): H264SdpTrack? {
        val sections = parseSections(sdp)
        return sections.asSequence()
            .filter { it.mediaType.equals("video", ignoreCase = true) }
            .mapNotNull(::toH264Track)
            .firstOrNull()
    }

    fun resolveControlUri(requestUri: String, contentBase: String?, control: String): String =
        AudioSdpParser.resolveControlUri(requestUri, contentBase, control)

    private fun parseSections(sdp: String): List<MediaSection> {
        val sections = mutableListOf<MediaSection>()
        var current: MediaSection? = null
        for (rawLine in sdp.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("m=", ignoreCase = true)) {
                val parts = line.removePrefix("m=").trim().split(Regex("\\s+"))
                current = MediaSection(
                    mediaLine = line,
                    mediaType = parts.firstOrNull().orEmpty(),
                    payloadTypes = parts.drop(3).mapNotNull(String::toIntOrNull)
                )
                sections += current
            } else if (line.startsWith("a=", ignoreCase = true)) {
                current?.attributes?.add(line.removePrefix("a="))
            }
        }
        return sections
    }

    private fun toH264Track(section: MediaSection): H264SdpTrack? {
        val rtpMaps = section.attributes.mapNotNull { attribute ->
            parsePayloadAttribute(attribute, "rtpmap")
        }.toMap()
        val payloadType = section.payloadTypes.firstOrNull { type ->
            rtpMaps[type]?.substringBefore('/')?.equals("H264", ignoreCase = true) == true
        } ?: return null
        val fmtp = section.attributes.firstNotNullOfOrNull { attribute ->
            parsePayloadAttribute(attribute, "fmtp")?.takeIf { it.first == payloadType }?.second
        }
        val parameters = parseFmtpParameters(fmtp)
        val spropParameterSets = parameters["sprop-parameter-sets"]
        val parameterSets = spropParameterSets
            ?.split(',')
            ?.mapNotNull(::decodeParameterSet)
            .orEmpty()
        val control = section.attributes.firstNotNullOfOrNull { attribute ->
            attribute.takeIf { it.startsWith("control:", ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } ?: return null
        return H264SdpTrack(
            mediaLine = section.mediaLine,
            payloadType = payloadType,
            rtpMap = rtpMaps[payloadType],
            fmtp = fmtp,
            packetizationMode = parameters["packetization-mode"],
            spropParameterSetsPresent = spropParameterSets != null,
            parameterSets = parameterSets,
            control = control
        )
    }

    private fun parsePayloadAttribute(attribute: String, name: String): Pair<Int, String>? {
        if (!attribute.startsWith("$name:", ignoreCase = true)) return null
        val value = attribute.substringAfter(':').trim()
        val separator = value.indexOfFirst(Char::isWhitespace)
        if (separator <= 0) return null
        val payloadType = value.substring(0, separator).toIntOrNull() ?: return null
        return payloadType to value.substring(separator).trim()
    }

    private fun parseFmtpParameters(fmtp: String?): Map<String, String> = fmtp
        ?.split(';')
        ?.mapNotNull { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) null else {
                part.substring(0, separator).trim().lowercase() to
                    part.substring(separator + 1).trim().trim('"')
            }
        }
        ?.toMap()
        .orEmpty()

    private fun decodeParameterSet(encoded: String): H264ParameterSet? {
        val bytes = Base64PayloadDecoder.decode(encoded) ?: return null
        if (bytes.isEmpty()) return null
        return H264ParameterSet(bytes[0].toInt() and 0x1f, bytes.size)
    }

    private data class MediaSection(
        val mediaLine: String,
        val mediaType: String,
        val payloadTypes: List<Int>,
        val attributes: MutableList<String> = mutableListOf()
    )
}

/** Small JVM/Android-safe decoder for SDP's standard Base64 parameter-set values. */
private object Base64PayloadDecoder {
    fun decode(value: String): ByteArray? {
        val compactInput = value.filterNot(Char::isWhitespace)
        if (compactInput.isEmpty()) return null
        val input = when (compactInput.length % 4) {
            0 -> compactInput
            2 -> "$compactInput=="
            3 -> "$compactInput="
            else -> return null
        }
        val output = ArrayList<Byte>(input.length / 4 * 3)
        for (index in input.indices step 4) {
            val first = digit(input[index]) ?: return null
            val second = digit(input[index + 1]) ?: return null
            val third = if (input[index + 2] == '=') -1 else digit(input[index + 2]) ?: return null
            val fourth = if (input[index + 3] == '=') -1 else digit(input[index + 3]) ?: return null
            if ((third == -1 && fourth != -1) || ((third == -1 || fourth == -1) && index + 4 != input.length)) return null
            output += ((first shl 2) or (second ushr 4)).toByte()
            if (third >= 0) output += (((second and 0x0f) shl 4) or (third ushr 2)).toByte()
            if (fourth >= 0) output += (((third and 0x03) shl 6) or fourth).toByte()
        }
        return output.toByteArray()
    }

    private fun digit(character: Char): Int? = when (character) {
        in 'A'..'Z' -> character - 'A'
        in 'a'..'z' -> character - 'a' + 26
        in '0'..'9' -> character - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> null
    }
}
