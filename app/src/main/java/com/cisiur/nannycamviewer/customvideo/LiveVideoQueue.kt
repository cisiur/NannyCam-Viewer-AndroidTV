package com.cisiur.nannycamviewer.customvideo

import java.util.ArrayDeque

data class AvcConfiguration(val sps: ByteArray, val pps: ByteArray, val generation: Long)

/** Caches small raw NALs; repeated identical in-band parameter sets do not recreate the codec. */
class AvcConfigurationCache {
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var generation = 0L

    @Synchronized
    fun update(nal: ByteArray): Boolean {
        if (nal.isEmpty() || nal.size > 4096 || nal[0].toInt() and 0x80 != 0) return false
        when (nal[0].toInt() and 0x1f) {
            7 -> {
                if (nal.size < 4 || sps?.contentEquals(nal) == true) return false
                sps = nal.copyOf()
            }
            8 -> {
                if (nal.size < 2 || pps?.contentEquals(nal) == true) return false
                pps = nal.copyOf()
            }
            else -> return false
        }
        generation++
        return true
    }

    @Synchronized
    fun current(): AvcConfiguration? {
        val currentSps = sps ?: return null
        val currentPps = pps ?: return null
        return AvcConfiguration(currentSps, currentPps, generation)
    }
}

data class QueuedVideoUnit(
    val accessUnit: H264AccessUnit,
    val configuration: AvcConfiguration,
    val ptsUs: Long,
    val epoch: Long
)

/** Nonblocking producer, at most three AUs. Any lost reference chain requires a fresh IDR. */
class LiveVideoQueue(private val capacity: Int = 3) {
    private val queue = ArrayDeque<QueuedVideoUnit>()
    private var closed = false
    private var waitingForIdr = true
    var epoch = 0L
        @Synchronized get
        private set
    var drops = 0L
        @Synchronized get
        private set

    init { require(capacity in 1..4) }

    @Synchronized
    fun offer(unit: H264AccessUnit, configuration: AvcConfiguration, ptsUs: Long): Boolean {
        if (closed) return false
        if (queue.size >= capacity) invalidate()
        if (waitingForIdr && !unit.isIdr) {
            drops++
            return false
        }
        waitingForIdr = false
        queue.addLast(QueuedVideoUnit(unit, configuration, ptsUs, epoch))
        return true
    }

    @Synchronized
    fun invalidate() {
        drops += queue.size
        queue.clear()
        epoch++
        waitingForIdr = true
    }

    @Synchronized
    fun poll(): QueuedVideoUnit? = queue.pollFirst()

    @Synchronized
    fun size(): Int = queue.size

    @Synchronized
    fun close() {
        closed = true
        invalidate()
    }
}
