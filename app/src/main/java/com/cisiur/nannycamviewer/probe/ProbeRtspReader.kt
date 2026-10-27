package com.cisiur.nannycamviewer.probe

import com.cisiur.nannycamviewer.audio.InterleavedRtspParser
import java.io.EOFException
import java.io.InputStream
import java.util.ArrayDeque

/** One socket reader; retains events following an RTSP response in the same bounded read. */
internal class ProbeRtspReader(private val input: InputStream) {
    private val parser = InterleavedRtspParser()
    private val events = ArrayDeque<InterleavedRtspParser.Event>()
    private val buffer = ByteArray(4 * 1024)

    fun readEvent(): InterleavedRtspParser.Event? {
        if (events.isEmpty()) {
            val count = input.read(buffer)
            if (count < 0) throw EOFException("RTSP server closed the connection")
            events.addAll(parser.feed(buffer, count))
        }
        // Returning after one read lets the capture loop check its deadline even when
        // only a partial frame/text message arrived. The parser retains incomplete data.
        return events.pollFirst()
    }
}
