package com.cisiur.nannycamviewer.customvideo

/** Network-thread-only bitstream state; its cache and queue expose synchronized decoder access. */
class H264VideoPipeline(private val log: (String) -> Unit) {
    val configuration = AvcConfigurationCache()
    val queue = LiveVideoQueue()
    val depacketizer = H264RtpDepacketizer()
    private val assembler = H264AccessUnitAssembler()
    private val timestamps = RtpVideoTimestampConverter()
    private var seenSps = false
    private var seenPps = false
    private var seenInBandSps = false
    private var seenInBandPps = false
    private var seenIdr = false
    var accessUnits = 0L
        private set
    private var firstQueued = false

    fun seedParameterSets(nals: List<ByteArray>) {
        nals.forEach { cacheParameter(it, "SDP") }
    }

    fun accept(packet: VideoRtpPacket) {
        val result = depacketizer.accept(packet)
        if (result.damaged) queue.invalidate()
        var configChanged = false
        result.nals.forEach { if (cacheParameter(it, "RTP")) configChanged = true }
        if (configChanged) {
            queue.invalidate()
            assembler.reset()
        }
        // CSD supplies SPS/PPS to the codec. Retain the other NALs in their access unit.
        val pictureNals = result.nals.filter { (it[0].toInt() and 0x1f) !in 7..8 }
        val beforeDiscard = assembler.discardedUnits
        val unit = assembler.accept(result.copy(nals = pictureNals))
        if (assembler.discardedUnits != beforeDiscard) queue.invalidate()
        if (unit == null) return
        accessUnits++
        val config = configuration.current() ?: return
        if (unit.isIdr && !seenIdr) {
            seenIdr = true
            log("First IDR received")
        }
        if (queue.offer(unit, config, timestamps.toPresentationTimeUs(unit.timestamp)) && !firstQueued) {
            firstQueued = true
            log("First access unit queued")
        }
    }

    fun reset() {
        depacketizer.reset()
        assembler.reset()
        queue.close()
    }

    private fun cacheParameter(nal: ByteArray, source: String): Boolean {
        val changed = configuration.update(nal)
        if (source == "RTP" && nal.isNotEmpty()) {
            when (nal[0].toInt() and 0x1f) {
                7 -> if (!seenInBandSps) {
                    seenInBandSps = true
                    log("First in-band SPS observed: bytes=${nal.size} cachedChange=$changed")
                }
                8 -> if (!seenInBandPps) {
                    seenInBandPps = true
                    log("First in-band PPS observed: bytes=${nal.size} cachedChange=$changed")
                }
            }
        }
        if (!changed) return false
        when (nal[0].toInt() and 0x1f) {
            7 -> {
                log("${if (seenSps) "Updated" else "First"} SPS: source=$source bytes=${nal.size}")
                seenSps = true
            }
            8 -> {
                log("${if (seenPps) "Updated" else "First"} PPS: source=$source bytes=${nal.size}")
                seenPps = true
            }
        }
        return true
    }
}
