package com.cisiur.nannycamviewer.customvideo

import com.cisiur.nannycamviewer.probe.H264SdpParser
import org.junit.Assert.*
import org.junit.Test

class LiveVideoQueueTest {
    private val config = AvcConfiguration(byteArrayOf(0x67, 1, 2, 3), byteArrayOf(0x68, 1), 1)

    @Test
    fun `startup waits for IDR and queue never exceeds three units`() {
        val queue = LiveVideoQueue()
        assertFalse(queue.offer(unit(false), config, 0))
        assertTrue(queue.offer(unit(true), config, 1))
        assertTrue(queue.offer(unit(false), config, 2))
        assertTrue(queue.offer(unit(false), config, 3))
        assertEquals(3, queue.size())
        assertFalse(queue.offer(unit(false), config, 4))
        assertEquals(0, queue.size())
        assertTrue(queue.offer(unit(true), config, 5))
        assertEquals(5L, queue.poll()!!.ptsUs)
    }

    @Test
    fun `newest IDR replaces a stale full queue and invalidates decoder epoch`() {
        val queue = LiveVideoQueue(2)
        queue.offer(unit(true), config, 0)
        queue.offer(unit(false), config, 1)
        val oldEpoch = queue.epoch
        queue.offer(unit(true), config, 2)
        assertTrue(queue.epoch > oldEpoch)
        assertEquals(1, queue.size())
        assertEquals(2L, queue.poll()!!.ptsUs)
        assertEquals(2L, queue.drops)
    }

    @Test
    fun `explicit invalidation requires IDR and closed queues reject all work`() {
        val queue = LiveVideoQueue()
        queue.offer(unit(true), config, 0)
        queue.invalidate()
        assertNull(queue.poll())
        assertFalse(queue.offer(unit(false), config, 1))
        queue.close()
        assertFalse(queue.offer(unit(true), config, 2))
    }

    @Test
    fun `configuration cache validates parameter sets and ignores unchanged bytes`() {
        val cache = AvcConfigurationCache()
        assertFalse(cache.update(byteArrayOf(0x67)))
        assertTrue(cache.update(config.sps))
        assertNull(cache.current())
        assertTrue(cache.update(config.pps))
        val first = cache.current()!!
        assertFalse(cache.update(config.sps.copyOf()))
        assertEquals(first.generation, cache.current()!!.generation)
        assertTrue(cache.update(byteArrayOf(0x68, 2)))
        assertTrue(cache.current()!!.generation > first.generation)
    }

    @Test
    fun `SDP NAL bytes are available for CSD without requiring in band config`() {
        val track = H264SdpParser.findVideoTrack("""
            m=video 0 RTP/AVP 96
            a=rtpmap:96 H264/90000
            a=fmtp:96 packetization-mode=1;sprop-parameter-sets=Z0IAH5WoFAFuQA==,aM4G4g==
            a=control:realvideo
        """.trimIndent())!!
        val pipeline = H264VideoPipeline { }
        pipeline.seedParameterSets(H264SdpParser.parameterSetNals(track))
        assertNotNull(pipeline.configuration.current())
        pipeline.accept(VideoRtpPacket(1, 90, true, byteArrayOf(0x65, 1)))
        assertTrue(pipeline.queue.poll()!!.accessUnit.isIdr)
    }

    @Test
    fun `pipeline obtains config in band and suppresses predicted frames after loss`() {
        val pipeline = H264VideoPipeline { }
        pipeline.accept(VideoRtpPacket(1, 0, false, config.sps))
        pipeline.accept(VideoRtpPacket(2, 0, false, config.pps))
        pipeline.accept(VideoRtpPacket(3, 0, true, byteArrayOf(0x61, 1)))
        assertNull(pipeline.queue.poll())
        pipeline.accept(VideoRtpPacket(4, 90, true, byteArrayOf(0x65, 1)))
        assertTrue(pipeline.queue.poll()!!.accessUnit.isIdr)
        pipeline.accept(VideoRtpPacket(6, 180, true, byteArrayOf(0x61, 1)))
        assertNull(pipeline.queue.poll())
        pipeline.accept(VideoRtpPacket(7, 270, true, byteArrayOf(0x65, 1)))
        assertTrue(pipeline.queue.poll()!!.accessUnit.isIdr)
    }

    @Test
    fun `pipeline queues one complete Annex-B AU only after FU-A end and marker`() {
        val pipeline = H264VideoPipeline { }
        pipeline.seedParameterSets(listOf(config.sps, config.pps))
        pipeline.accept(VideoRtpPacket(1, 90, false, byteArrayOf(0x7c, 0x85.toByte(), 1)))
        assertNull(pipeline.queue.poll())
        pipeline.accept(VideoRtpPacket(2, 90, true, byteArrayOf(0x7c, 0x45, 2)))
        val work = pipeline.queue.poll()!!
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2), work.accessUnit.annexB)
        assertTrue(work.accessUnit.isIdr)
        assertNull(pipeline.queue.poll())
    }

    @Test
    fun `duplicate FU-A start cannot leak a corrupt IDR through assembler`() {
        val pipeline = H264VideoPipeline { }
        pipeline.seedParameterSets(listOf(config.sps, config.pps))
        pipeline.accept(VideoRtpPacket(1, 90, false, byteArrayOf(0x7c, 0x85.toByte(), 1)))
        pipeline.accept(VideoRtpPacket(2, 90, false, byteArrayOf(0x7c, 0x85.toByte(), 2)))
        pipeline.accept(VideoRtpPacket(3, 90, true, byteArrayOf(0x7c, 0x45, 3)))
        assertNull(pipeline.queue.poll())
    }

    @Test
    fun `repeated identical in-band config neither changes epoch nor duplicates CSD in picture`() {
        val pipeline = H264VideoPipeline { }
        pipeline.seedParameterSets(listOf(config.sps, config.pps))
        pipeline.accept(VideoRtpPacket(1, 0, true, byteArrayOf(0x65, 1)))
        pipeline.queue.poll()
        val epoch = pipeline.queue.epoch
        val generation = pipeline.configuration.current()!!.generation
        pipeline.accept(VideoRtpPacket(2, 90, false, config.sps))
        pipeline.accept(VideoRtpPacket(3, 90, false, config.pps))
        pipeline.accept(VideoRtpPacket(4, 90, true, byteArrayOf(0x61, 1)))
        assertEquals(epoch, pipeline.queue.epoch)
        assertEquals(generation, pipeline.configuration.current()!!.generation)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x61, 1), pipeline.queue.poll()!!.accessUnit.annexB)
    }

    private fun unit(idr: Boolean) = H264AccessUnit(byteArrayOf(0, 0, 0, 1, if (idr) 0x65 else 0x61), 0, idr)
}
