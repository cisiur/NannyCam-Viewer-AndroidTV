package com.cisiur.nannycamviewer.customvideo

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.IOException
import java.nio.ByteBuffer

/** All codec calls, including configure/release, belong exclusively to the decoder thread. */
class MediaCodecVideoDecoder(
    private val surface: Surface,
    private val pipeline: H264VideoPipeline,
    private val startedAtMs: Long,
    private val onState: (CustomVideoState, String?) -> Unit
) {
    fun run(cancelled: () -> Boolean) {
        var codec: MediaCodec? = null
        var generation = -1L
        var epoch = -1L
        var pending: QueuedVideoUnit? = null
        var firstOutput = false
        var playingForCodec = false
        var inputs = 0L
        var outputs = 0L
        var lastStats = startedAtMs
        val info = MediaCodec.BufferInfo()
        onState(CustomVideoState.WAITING_CONFIG, null)
        Log.i(TAG, "Waiting for SPS/PPS")
        try {
            while (!cancelled()) {
                if (!surface.isValid) throw IOException("POC Surface is no longer valid")
                val configuration = pipeline.configuration.current()
                if (!firstOutput && SystemClock.elapsedRealtime() - startedAtMs > STARTUP_TIMEOUT_MS) {
                    throw IOException(when {
                        configuration == null -> "Missing SPS/PPS after 15 seconds"
                        inputs == 0L -> "No complete IDR access unit after 15 seconds"
                        else -> "No decoded output after 15 seconds"
                    })
                }
                if (configuration == null) {
                    Thread.sleep(5)
                    continue
                }
                val queueEpoch = pipeline.queue.epoch
                if (codec == null || generation != configuration.generation || epoch != queueEpoch) {
                    release(codec)
                    codec = null
                    pending = null
                    codec = createCodec(configuration)
                    playingForCodec = false
                    generation = configuration.generation
                    epoch = queueEpoch
                    onState(CustomVideoState.WAITING_IDR, null)
                    Log.i(TAG, "Decoder configured; waiting for IDR")
                }
                val active = codec
                if (pending == null) pending = pipeline.queue.poll()
                val work = pending
                if (work != null) {
                    if (work.epoch != epoch || work.configuration.generation != generation) {
                        pending = null
                        continue
                    }
                    val index = active.dequeueInputBuffer(1_000)
                    if (index >= 0) {
                        // A network overflow may have invalidated work while dequeue was running.
                        if (pipeline.queue.epoch != epoch || cancelled()) continue
                        val buffer = active.getInputBuffer(index) ?: throw IOException("No codec input buffer")
                        if (buffer.capacity() < work.accessUnit.annexB.size) throw IOException("Access unit exceeds codec input capacity")
                        buffer.clear()
                        buffer.put(work.accessUnit.annexB)
                        active.queueInputBuffer(index, 0, work.accessUnit.annexB.size, work.ptsUs, 0)
                        inputs++
                        pending = null
                    }
                }
                var drained = 0
                while (!cancelled() && drained < 8) {
                    val index = active.dequeueOutputBuffer(info, 0)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Log.i(TAG, "Decoder output format: ${active.outputFormat}")
                        index >= 0 -> {
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                active.releaseOutputBuffer(index, false)
                                throw IOException("AVC decoder reached unexpected end of stream")
                            }
                            val render = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                                pipeline.queue.epoch == epoch
                            active.releaseOutputBuffer(index, render)
                            if (render) {
                                outputs++
                                if (!firstOutput) {
                                    firstOutput = true
                                    val elapsed = SystemClock.elapsedRealtime() - startedAtMs
                                    Log.i(TAG, "First decoded output buffer released to Surface after ${elapsed}ms (visual validation required)")
                                    onState(CustomVideoState.PLAYING, "First output released after ${elapsed}ms")
                                    playingForCodec = true
                                } else if (!playingForCodec) {
                                    playingForCodec = true
                                    onState(CustomVideoState.PLAYING, "Output resumed; verify image visually")
                                }
                            }
                        }
                        else -> break
                    }
                    drained++
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastStats >= 5_000) {
                    lastStats = now
                    Log.i(TAG, "Decoder inputs=$inputs outputs=$outputs queueDrops=${pipeline.queue.drops}")
                }
                if (pending == null) Thread.sleep(5)
            }
        } finally {
            release(codec)
            Log.i(TAG, "Decoder released: inputs=$inputs outputs=$outputs")
        }
    }

    private fun createCodec(configuration: AvcConfiguration): MediaCodec {
        // API 29 exposes a reliable hardware classification. The physical POC target is API 30.
        if (Build.VERSION.SDK_INT < 29) throw IOException("Hardware-verified POC requires Android API 29 or later")
        val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            val info = codec.codecInfo
            Log.i(TAG, "Decoder created: ${codec.name} hardware=${info.isHardwareAccelerated} software=${info.isSoftwareOnly}")
            if (!info.isHardwareAccelerated || info.isSoftwareOnly) throw IOException("Platform selected a non-hardware AVC decoder")
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, 1920, 1080).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(H264AccessUnitAssembler.annexB(configuration.sps)))
                setByteBuffer("csd-1", ByteBuffer.wrap(H264AccessUnitAssembler.annexB(configuration.pps)))
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, H264AccessUnitAssembler.MAX_ACCESS_UNIT_BYTES)
            }
            codec.configure(format, surface, null, 0)
            codec.start()
            return codec
        } catch (error: Exception) {
            runCatching { codec.release() }
            throw error
        }
    }

    private fun release(codec: MediaCodec?) {
        if (codec == null) return
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    companion object {
        private const val TAG = "CustomVideoPoc"
        private const val STARTUP_TIMEOUT_MS = 15_000L
    }
}
