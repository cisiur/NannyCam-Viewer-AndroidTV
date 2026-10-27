package com.cisiur.nannycamviewer.customvideo

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

enum class CustomVideoState { NOT_CONNECTED, CONNECTING, WAITING_CONFIG, WAITING_IDR, PLAYING, STOPPING, ERROR }

/** Main-thread orchestration for one opt-in, video-only session. No automatic retries. */
class CustomVideoPlaybackController(
    private val surfaceView: SurfaceView,
    private val listener: (CustomVideoState, String?) -> Unit
) : SurfaceHolder.Callback {
    private val handler = Handler(Looper.getMainLooper())
    private var run: SessionRun? = null
    val isActive: Boolean get() = run != null

    init { surfaceView.holder.addCallback(this) }

    fun start(uri: String) {
        if (run != null) return
        val active = SessionRun(uri)
        run = active
        Log.i(TAG, "POC started")
        listener(CustomVideoState.CONNECTING, null)
        startWorkersIfSurfaceReady(active)
    }

    fun stop() {
        val active = run ?: return
        active.explicitStop = true
        cancel(active)
        listener(CustomVideoState.STOPPING, null)
        if (!active.workersStarted) finish(active)
    }

    fun stopForLifecycle() {
        val active = run
        stop()
        active?.decoderThread?.join(2_000)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        run?.let(::startWorkersIfSurfaceReady)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        val active = run ?: return
        stop()
        // SurfaceHolder requires the renderer to stop using its borrowed Surface before return.
        active.decoderThread?.join(2_000)
    }

    private fun startWorkersIfSurfaceReady(active: SessionRun) {
        if (active.workersStarted || active.cancelled || !surfaceView.holder.surface.isValid) return
        active.workersStarted = true
        val decoder = MediaCodecVideoDecoder(surfaceView.holder.surface, active.pipeline, active.startedAtMs) { state, detail ->
            handler.post { if (run === active && !active.cancelled) listener(state, detail) }
        }
        active.decoderThread = Thread({
            worker(active) { decoder.run { active.cancelled } }
        }, "CustomVideoDecoder")
        active.networkThread = Thread({ worker(active) { active.session.run() } }, "CustomVideoRtsp")
        active.decoderThread!!.start()
        active.networkThread!!.start()
    }

    private fun worker(active: SessionRun, action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            if (!active.cancelled) {
                val message = (error.message ?: error.javaClass.simpleName)
                    .replace(Regex("(?i)(rtsp://)[^\\s/]*@"), "$1<redacted>@")
                active.failure.compareAndSet(null, message)
                Log.e(TAG, "POC failure: $message")
                cancel(active)
            }
        } finally {
            if (active.remaining.decrementAndGet() == 0) {
                active.pipeline.reset()
                handler.post { finish(active) }
            }
        }
    }

    private fun cancel(active: SessionRun) {
        active.cancelled = true
        active.pipeline.queue.close()
        active.session.close()
        active.networkThread?.interrupt()
        active.decoderThread?.interrupt()
    }

    private fun finish(active: SessionRun) {
        if (run !== active) return
        run = null
        Log.i(TAG, "POC stopped")
        val error = active.failure.get().takeUnless { active.explicitStop }
        listener(if (error == null) CustomVideoState.NOT_CONNECTED else CustomVideoState.ERROR, error)
    }

    private class SessionRun(uri: String) {
        val startedAtMs = SystemClock.elapsedRealtime()
        val pipeline = H264VideoPipeline { Log.i(TAG, it) }
        val session = CustomH264RtspSession(uri, pipeline)
        val failure = AtomicReference<String?>(null)
        val remaining = AtomicInteger(2)
        @Volatile var cancelled = false
        var explicitStop = false
        var workersStarted = false
        var networkThread: Thread? = null
        var decoderThread: Thread? = null
    }

    companion object { private const val TAG = "CustomVideoPoc" }
}
