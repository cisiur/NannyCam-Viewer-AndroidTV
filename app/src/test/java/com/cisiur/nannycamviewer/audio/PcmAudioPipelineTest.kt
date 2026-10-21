package com.cisiur.nannycamviewer.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmAudioPipelineTest {
    @Test
    fun `decodes known PCM16 little endian values`() {
        val values = Pcm16LeDecoder().decode(byteArrayOf(0, 0, 0xff.toByte(), 0x7f, 0, 0x80.toByte(), 0xff.toByte(), 0xff.toByte()))

        assertTrue(values.contentEquals(shortArrayOf(0, 32767, -32768, -1)))
    }

    @Test
    fun `keeps one byte carry between packets and resets it`() {
        val decoder = Pcm16LeDecoder()
        assertTrue(decoder.decode(byteArrayOf(0x34)).isEmpty())
        assertTrue(decoder.hasCarryByte())
        assertTrue(decoder.decode(byteArrayOf(0x12, 0x78, 0x56)).contentEquals(shortArrayOf(0x1234, 0x5678)))
        assertFalse(decoder.hasCarryByte())
        decoder.decode(byteArrayOf(1))
        decoder.reset()
        assertFalse(decoder.hasCarryByte())
    }

    @Test
    fun `DSP attenuates DC and applies gain with saturation`() {
        val filter = PcmDspProcessor(gain = 1.0)
        assertTrue(filter.process(shortArrayOf(1_000, 1_000)).contentEquals(shortArrayOf(914, 835)))
        filter.reset()
        val output = filter.process(ShortArray(20) { 1_000 })
        assertTrue(output.last() < output.first())

        val gainOnly = PcmDspProcessor(cutoffHz = 0.0)
        assertEquals(3162, gainOnly.process(shortArrayOf(1_000)).single().toInt())
        assertEquals(32767, PcmDspProcessor(cutoffHz = 0.0).process(shortArrayOf(20_000)).single().toInt())
        assertEquals(-32768, PcmDspProcessor(cutoffHz = 0.0).process(shortArrayOf(-20_000)).single().toInt())
    }

    @Test
    fun `bounded buffer recovers toward the configured live target`() {
        val buffer = BoundedPcmBuffer(maxBytes = 10, recoveryBytes = 4)
        buffer.offer(byteArrayOf(1, 2, 3, 4, 5, 6))
        assertEquals(6, buffer.snapshot().queuedBytes)
        buffer.offer(byteArrayOf(7, 8, 9, 10, 11, 12))

        assertEquals(4, buffer.snapshot().queuedBytes)
        assertEquals(8, buffer.snapshot().staleDroppedBytes)
        assertTrue(buffer.take(10).contentEquals(byteArrayOf(9, 10, 11, 12)))
        assertEquals(8, buffer.snapshot().staleDroppedBytes)
    }

    @Test
    fun `mute clears pending audio so unmute cannot replay a backlog`() {
        val settings = AudioPlaybackSettings()
        val buffer = BoundedPcmBuffer(maxBytes = 10, recoveryBytes = 4)
        buffer.offer(byteArrayOf(1, 2, 3, 4))
        assertFalse(settings.muted)
        assertTrue(settings.processingEnabled)
        settings.muted = true
        buffer.clear()
        settings.processingEnabled = false
        assertTrue(settings.muted)
        assertEquals(0, buffer.snapshot().queuedBytes)
        assertFalse(settings.processingEnabled)
    }
}
