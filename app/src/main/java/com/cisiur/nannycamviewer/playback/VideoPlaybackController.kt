package com.cisiur.nannycamviewer.playback

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.cisiur.nannycamviewer.customvideo.VideoPlaybackAttempt

/** Production SurfaceView owner. The custom H.264 worker path is the only video runtime. */
class VideoPlaybackController(private val surfaceView: SurfaceView, listener: Listener) : SurfaceHolder.Callback {
    interface Listener : VideoPlaybackEvents
    private val handler = Handler(Looper.getMainLooper())
    private val coordinator = VideoSessionCoordinator(
        factory = VideoAttemptFactory { _, uri, events ->
            VideoPlaybackAttempt(uri, surfaceView.holder.surface, handler, events)
        },
        scheduler = VideoRetryScheduler { delay, action ->
            val runnable = Runnable(action)
            handler.postDelayed(runnable, delay)
            VideoRetryCancellation { handler.removeCallbacks(runnable) }
        },
        listener = listener,
        log = { Log.i(TAG, it) }
    )

    init {
        surfaceView.holder.addCallback(this)
        coordinator.setSurfaceAvailable(surfaceView.holder.surface.isValid)
    }

    val state: ConnectionState get() = coordinator.state
    val isBusy: Boolean get() = coordinator.isBusy
    val hasConnectIntent: Boolean get() = coordinator.hasConnectIntent

    fun connect(uri: String): Boolean = coordinator.connect(uri)
    fun disconnect() = coordinator.disconnect()

    fun releaseForLifecycle() {
        coordinator.disconnect()
        coordinator.awaitSurfaceRelease()
    }

    fun destroy() {
        releaseForLifecycle()
        surfaceView.holder.removeCallback(this)
    }

    override fun surfaceCreated(holder: SurfaceHolder) = coordinator.setSurfaceAvailable(true)
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        coordinator.setSurfaceAvailable(false)
        // Do not return the borrowed Surface to Android while MediaCodec still uses it.
        // Only decoder release is awaited here. Socket reads never run on the UI thread.
        coordinator.awaitSurfaceRelease()
    }

    private companion object { const val TAG = "VideoPlayback" }
}
