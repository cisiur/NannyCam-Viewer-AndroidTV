package com.cisiur.nannycamviewer.audio

import java.nio.charset.StandardCharsets
import java.util.Locale

/** Streaming parser for RTSP text messages and `$` interleaved RTP/RTCP frames. */
class InterleavedRtspParser(
    private val maxInterleavedFrameBytes: Int = DEFAULT_MAX_INTERLEAVED_FRAME_BYTES,
    private val maxTextMessageBytes: Int = DEFAULT_MAX_TEXT_MESSAGE_BYTES
) {
    sealed class Event {
        data class Message(
            val startLine: String,
            val headers: Map<String, String>,
            val body: ByteArray
        ) : Event() {
            fun header(name: String): String? = headers[name.lowercase(Locale.US)]
        }

        data class InterleavedFrame(val channel: Int, val payload: ByteArray) : Event()
    }

    private var pending = ByteArray(0)

    fun feed(bytes: ByteArray, length: Int = bytes.size): List<Event> {
        require(length in 0..bytes.size)
        if (length == 0) return emptyList()
        if (pending.size + length > maxTextMessageBytes + maxInterleavedFrameBytes) {
            throw ParseException("RTSP receive buffer exceeded limit")
        }
        pending += bytes.copyOf(length)
        val events = mutableListOf<Event>()
        while (pending.isNotEmpty()) {
            if (pending[0] == INTERLEAVED_MARKER) {
                if (pending.size < INTERLEAVED_HEADER_BYTES) break
                val frameLength = unsignedShort(pending[2], pending[3])
                if (frameLength > maxInterleavedFrameBytes) {
                    throw ParseException("Interleaved frame exceeds limit: $frameLength")
                }
                val totalLength = INTERLEAVED_HEADER_BYTES + frameLength
                if (pending.size < totalLength) break
                events += Event.InterleavedFrame(
                    channel = pending[1].toInt() and 0xff,
                    payload = pending.copyOfRange(INTERLEAVED_HEADER_BYTES, totalLength)
                )
                pending = pending.copyOfRange(totalLength, pending.size)
                continue
            }

            val headerEnd = indexOfHeaderEnd(pending)
            if (headerEnd < 0) {
                if (pending.size > maxTextMessageBytes) throw ParseException("RTSP header exceeds limit")
                break
            }
            val headerLength = headerEnd + HEADER_TERMINATOR_BYTES
            val headerText = pending.copyOfRange(0, headerEnd).toString(StandardCharsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            if (lines.isEmpty() || lines.first().isBlank()) throw ParseException("Missing RTSP start line")
            val headers = linkedMapOf<String, String>()
            lines.drop(1).forEach { line ->
                val separator = line.indexOf(':')
                if (separator <= 0) throw ParseException("Malformed RTSP header")
                headers[line.substring(0, separator).trim().lowercase(Locale.US)] =
                    line.substring(separator + 1).trim()
            }
            val contentLengthHeader = headers["content-length"]
            val contentLength = if (contentLengthHeader == null) {
                0
            } else {
                contentLengthHeader.toIntOrNull() ?: throw ParseException("Invalid RTSP content length")
            }
            if (contentLength !in 0..maxTextMessageBytes) throw ParseException("Invalid RTSP content length")
            val messageLength = headerLength + contentLength
            if (messageLength > maxTextMessageBytes || pending.size < messageLength) break
            events += Event.Message(
                startLine = lines.first(),
                headers = headers,
                body = pending.copyOfRange(headerLength, messageLength)
            )
            pending = pending.copyOfRange(messageLength, pending.size)
        }
        return events
    }

    class ParseException(message: String) : IllegalArgumentException(message)

    private fun indexOfHeaderEnd(data: ByteArray): Int {
        for (index in 0..data.size - HEADER_TERMINATOR_BYTES) {
            if (data[index] == '\r'.code.toByte() &&
                data[index + 1] == '\n'.code.toByte() &&
                data[index + 2] == '\r'.code.toByte() &&
                data[index + 3] == '\n'.code.toByte()
            ) return index
        }
        return -1
    }

    private fun unsignedShort(high: Byte, low: Byte): Int =
        ((high.toInt() and 0xff) shl 8) or (low.toInt() and 0xff)

    private companion object {
        val INTERLEAVED_MARKER: Byte = '$'.code.toByte()
        const val INTERLEAVED_HEADER_BYTES = 4
        const val HEADER_TERMINATOR_BYTES = 4
        const val DEFAULT_MAX_INTERLEAVED_FRAME_BYTES = 32 * 1024
        const val DEFAULT_MAX_TEXT_MESSAGE_BYTES = 64 * 1024
    }
}
