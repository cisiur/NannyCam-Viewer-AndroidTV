package com.cisiur.nannycamviewer.audio

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt

/** Reassembles PCM16LE samples across RTP packet boundaries with at most one carry byte. */
class Pcm16LeDecoder {
    private var carryByte: Byte? = null

    fun decode(payload: ByteArray): ShortArray {
        if (payload.isEmpty() && carryByte == null) return ShortArray(0)
        val combined = if (carryByte == null) {
            payload
        } else {
            ByteArray(payload.size + 1).also {
                it[0] = carryByte!!
                payload.copyInto(it, destinationOffset = 1)
            }
        }
        val completeBytes = combined.size and 1.inv()
        carryByte = if (combined.size != completeBytes) combined.last() else null
        return ShortArray(completeBytes / BYTES_PER_SAMPLE) { sampleIndex ->
            val byteIndex = sampleIndex * BYTES_PER_SAMPLE
            (((combined[byteIndex].toInt() and 0xff) or
                ((combined[byteIndex + 1].toInt() and 0xff) shl 8))).toShort()
        }
    }

    fun reset() {
        carryByte = null
    }

    fun hasCarryByte(): Boolean = carryByte != null

    companion object {
        const val BYTES_PER_SAMPLE = 2

        fun encode(samples: ShortArray): ByteArray = ByteArray(samples.size * BYTES_PER_SAMPLE).also { bytes ->
            samples.forEachIndexed { index, sample ->
                bytes[index * BYTES_PER_SAMPLE] = sample.toInt().toByte()
                bytes[index * BYTES_PER_SAMPLE + 1] = (sample.toInt() ushr 8).toByte()
            }
        }
    }
}

/** Streaming first-order high-pass filter followed by the validated fixed gain. */
class PcmDspProcessor(
    sampleRateHz: Int = SAMPLE_RATE_HZ,
    cutoffHz: Double = HIGH_PASS_CUTOFF_HZ,
    private val gain: Double = GAIN_10_DB
) {
    private val alpha = 1.0 / (1.0 + (2.0 * PI * cutoffHz / sampleRateHz))
    private var previousInput = 0.0
    private var previousOutput = 0.0

    fun process(samples: ShortArray): ShortArray = ShortArray(samples.size) { index ->
        val input = samples[index].toDouble()
        val filtered = alpha * (previousOutput + input - previousInput)
        previousInput = input
        previousOutput = filtered
        saturate(filtered * gain)
    }

    fun reset() {
        previousInput = 0.0
        previousOutput = 0.0
    }

    fun highPassCoefficient(): Double = alpha

    private fun saturate(value: Double): Short = value.roundToInt()
        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        .toShort()

    companion object {
        const val SAMPLE_RATE_HZ = 8_000
        const val HIGH_PASS_CUTOFF_HZ = 120.0
        val GAIN_10_DB: Double = 10.0.pow(10.0 / 20.0)
    }
}

data class PcmBufferSnapshot(val queuedBytes: Int, val staleDroppedBytes: Long)

/** A fixed-size PCM ring that favors current audio over an old live-monitor backlog. */
class BoundedPcmBuffer(
    private val maxBytes: Int = MAX_LIVE_BUFFER_BYTES,
    private val recoveryBytes: Int = RECOVERY_BUFFER_BYTES
) {
    private val ring = ByteArray(maxBytes)
    private val monitor = Object()
    private var head = 0
    private var size = 0
    private var staleDroppedBytes = 0L

    init {
        require(maxBytes > 0 && recoveryBytes in 0..maxBytes)
        require(maxBytes % Pcm16LeDecoder.BYTES_PER_SAMPLE == 0)
        require(recoveryBytes % Pcm16LeDecoder.BYTES_PER_SAMPLE == 0)
    }

    fun offer(bytes: ByteArray) {
        synchronized(monitor) {
            if (bytes.isEmpty()) return
            var sourceOffset = 0
            val excess = size + bytes.size - maxBytes
            if (excess > 0) {
                var bytesToDiscard = size + bytes.size - recoveryBytes
                val oldBytesToDiscard = minOf(bytesToDiscard, size)
                removeOldest(oldBytesToDiscard, countAsStale = true)
                bytesToDiscard -= oldBytesToDiscard
                if (bytesToDiscard > 0) {
                    sourceOffset = minOf(bytesToDiscard, bytes.size)
                    staleDroppedBytes += sourceOffset
                }
            }
            append(bytes, sourceOffset, bytes.size - sourceOffset)
            monitor.notifyAll()
        }
    }

    fun take(maximumBytes: Int): ByteArray = synchronized(monitor) {
        require(maximumBytes >= 0)
        val count = minOf(maximumBytes, size)
        val result = ByteArray(count)
        for (index in 0 until count) result[index] = ring[(head + index) % ring.size]
        removeOldest(count, countAsStale = false)
        result
    }

    fun waitForData(timeoutMs: Long): Boolean = synchronized(monitor) {
        if (size == 0) {
            try {
                monitor.wait(timeoutMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        size > 0
    }

    fun clear() {
        synchronized(monitor) {
            head = 0
            size = 0
            monitor.notifyAll()
        }
    }

    fun reset() {
        synchronized(monitor) {
            head = 0
            size = 0
            staleDroppedBytes = 0
            monitor.notifyAll()
        }
    }

    fun snapshot(): PcmBufferSnapshot = synchronized(monitor) {
        PcmBufferSnapshot(size, staleDroppedBytes)
    }

    private fun append(source: ByteArray, sourceOffset: Int, count: Int) {
        for (index in 0 until count) {
            ring[(head + size) % ring.size] = source[sourceOffset + index]
            size++
        }
    }

    private fun removeOldest(count: Int, countAsStale: Boolean) {
        if (count <= 0) return
        head = (head + count) % ring.size
        size -= count
        if (countAsStale) staleDroppedBytes += count
    }

    companion object {
        const val BYTES_PER_MILLISECOND = 16 // 8 kHz, mono, 16-bit PCM.
        const val RECOVERY_BUFFER_BYTES = 1_600 // Approximately 100 ms.
        const val MAX_LIVE_BUFFER_BYTES = 3_200 // Approximately 200 ms.
    }
}
