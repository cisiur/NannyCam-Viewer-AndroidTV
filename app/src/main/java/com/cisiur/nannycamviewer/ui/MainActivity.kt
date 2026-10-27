package com.cisiur.nannycamviewer.ui

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.media3.common.util.UnstableApi
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.media3.ui.PlayerView
import com.cisiur.nannycamviewer.R
import com.cisiur.nannycamviewer.config.CameraEndpointNormalizer
import com.cisiur.nannycamviewer.config.CameraEndpointStore
import com.cisiur.nannycamviewer.customvideo.CustomVideoPlaybackController
import com.cisiur.nannycamviewer.customvideo.CustomVideoState
import com.cisiur.nannycamviewer.audio.AudioConnectionState
import com.cisiur.nannycamviewer.audio.AudioDiagnostics
import com.cisiur.nannycamviewer.audio.AudioPlaybackController
import com.cisiur.nannycamviewer.platform.PlatformDiagnostics
import com.cisiur.nannycamviewer.platform.PlatformDiagnosticsFormatter
import com.cisiur.nannycamviewer.playback.ConnectionState
import com.cisiur.nannycamviewer.playback.VideoBufferingMode
import com.cisiur.nannycamviewer.playback.VideoPlaybackController
import com.cisiur.nannycamviewer.playback.ViewerKeepScreenOnPolicy
import com.cisiur.nannycamviewer.probe.H264ProbeOutcome
import com.cisiur.nannycamviewer.probe.H264ProbeResult
import com.cisiur.nannycamviewer.probe.H264RtspProbe

@UnstableApi
class MainActivity : Activity() {
    private lateinit var endpointInput: EditText
    private lateinit var configurationMessage: TextView
    private lateinit var endpointStore: CameraEndpointStore
    private lateinit var saveButton: Button
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var statusText: TextView
    private lateinit var decoderText: TextView
    private lateinit var playbackController: VideoPlaybackController
    private lateinit var audioStateText: TextView
    private lateinit var audioDiagnosticsText: TextView
    private lateinit var muteButton: Button
    private lateinit var processingButton: Button
    private lateinit var videoBufferingButton: Button
    private lateinit var h264ProbeButton: Button
    private lateinit var audioPlaybackController: AudioPlaybackController
    private var h264Probe: H264RtspProbe? = null
    private lateinit var customVideo: CustomVideoPlaybackController
    private lateinit var customSurface: SurfaceView
    private lateinit var customVideoButton: Button
    private lateinit var stopCustomVideoButton: Button
    private lateinit var customVideoStatus: TextView
    private var pocWindowActive = true
    private val audioStartupGate = ViewerAudioStartupGate()
    private val startupHandler = Handler(Looper.getMainLooper())
    private var pendingInitialAudioStart: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        endpointStore = CameraEndpointStore(this)
        endpointInput = findViewById(R.id.endpoint_input)
        configurationMessage = findViewById(R.id.configuration_message)
        saveButton = findViewById(R.id.save_button)
        connectButton = findViewById(R.id.connect_button)
        disconnectButton = findViewById(R.id.disconnect_button)
        statusText = findViewById(R.id.status_text)
        decoderText = findViewById(R.id.decoder_text)
        audioStateText = findViewById(R.id.audio_state_text)
        audioDiagnosticsText = findViewById(R.id.audio_diagnostics_text)
        muteButton = findViewById(R.id.mute_button)
        processingButton = findViewById(R.id.processing_button)
        videoBufferingButton = findViewById(R.id.video_buffering_button)
        h264ProbeButton = findViewById(R.id.h264_probe_button)
        customSurface = findViewById(R.id.custom_video_surface)
        customVideoButton = findViewById(R.id.custom_video_button)
        stopCustomVideoButton = findViewById(R.id.stop_custom_video_button)
        customVideoStatus = findViewById(R.id.custom_video_status)

        endpointStore.load()?.let(endpointInput::setText)
        playbackController = VideoPlaybackController(
            this,
            findViewById<PlayerView>(R.id.video_view),
            object : VideoPlaybackController.Listener {
                override fun onConnectionStateChanged(state: ConnectionState) = updatePlaybackState(state)

                override fun onSelectedVideoDecoder(decoderName: String, attemptId: Long) {
                    decoderText.text = getString(R.string.selected_decoder, decoderName)
                    when (audioStartupGate.onVideoDecoderInitialized()) {
                        ViewerAudioStartupGate.DecoderDecision.AudioRemainsGated -> {
                            Log.i(TAG, "Video decoder initialized for attempt #$attemptId; audio remains gated")
                        }
                        ViewerAudioStartupGate.DecoderDecision.NoActiveSession -> {
                            Log.i(TAG, "Ignoring decoder callback without an active viewer session")
                        }
                    }
                }

                override fun onPlaybackError(message: String, attemptId: Long) {
                    statusText.text = getString(R.string.error_status, message)
                    when (audioStartupGate.onVideoFailure(attemptId)) {
                        ViewerAudioStartupGate.VideoFailureDecision.CancelPendingAudioStart -> {
                            cancelPendingInitialAudioStart()
                            Log.i(TAG, "Video failed before initial audio start; pending audio start cancelled")
                        }
                        ViewerAudioStartupGate.VideoFailureDecision.AudioRemainsGated -> {
                            Log.i(TAG, "Video attempt failed before first frame; audio remains gated")
                        }
                        ViewerAudioStartupGate.VideoFailureDecision.AudioAlreadyStarted -> Unit
                        ViewerAudioStartupGate.VideoFailureDecision.NoActiveSession -> Unit
                    }
                }

                override fun onFirstFrameRendered(startupLatencyMs: Long, attemptId: Long) {
                    configurationMessage.text = getString(R.string.first_frame_rendered, startupLatencyMs)
                    when (val decision = audioStartupGate.onVideoFirstFrame(attemptId)) {
                        is ViewerAudioStartupGate.FirstFrameDecision.ScheduleAudio -> {
                            scheduleInitialAudioStart(decision.token)
                        }
                        ViewerAudioStartupGate.FirstFrameDecision.AlreadyScheduled -> {
                            Log.i(TAG, "Initial audio start already scheduled; ignoring duplicate first-frame callback")
                        }
                        ViewerAudioStartupGate.FirstFrameDecision.AlreadyStarted -> {
                            Log.i(TAG, "Initial audio already started; ignoring later first-frame callback")
                        }
                        ViewerAudioStartupGate.FirstFrameDecision.IgnoredFailedAttempt -> {
                            Log.i(TAG, "Ignoring first-frame callback from a failed video attempt")
                        }
                        ViewerAudioStartupGate.FirstFrameDecision.NoActiveSession -> {
                            Log.i(TAG, "Ignoring first-frame callback without an active viewer session")
                        }
                    }
                }
            }
        )
        audioPlaybackController = AudioPlaybackController(
            this,
            object : AudioPlaybackController.Listener {
                override fun onAudioStateChanged(state: AudioConnectionState) = updateAudioState(state)

                override fun onAudioError(message: String) {
                    configurationMessage.text = getString(R.string.audio_error, message)
                }

                override fun onAudioDiagnostics(diagnostics: AudioDiagnostics) {
                    audioDiagnosticsText.text = formatAudioDiagnostics(diagnostics)
                }
            }
        )

        customVideo = CustomVideoPlaybackController(customSurface, ::updateCustomVideoState)
        customVideoButton.setOnClickListener { startCustomVideo() }
        stopCustomVideoButton.setOnClickListener { customVideo.stop() }
        saveButton.setOnClickListener { saveEndpoint() }
        connectButton.setOnClickListener { connectSavedEndpoint() }
        disconnectButton.setOnClickListener {
            customVideo.stop()
            resetAudioStartupGate("Viewer session disconnected; audio-start gate reset")
            playbackController.disconnect()
            audioPlaybackController.disconnect()
            setViewerKeepScreenOn(false)
        }
        muteButton.setOnClickListener {
            audioPlaybackController.toggleMute()
            updateAudioState(audioPlaybackController.connectionState)
        }
        processingButton.setOnClickListener {
            audioPlaybackController.toggleProcessing()
            updateAudioState(audioPlaybackController.connectionState)
        }
        videoBufferingButton.setOnClickListener {
            updateVideoBufferingMode(playbackController.toggleBufferingMode())
            configurationMessage.text = getString(R.string.video_buffering_applies_on_next_connection)
        }
        h264ProbeButton.setOnClickListener { runH264Probe() }
        endpointInput.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP)
            ) {
                saveEndpoint()
                true
            } else {
                false
            }
        }
        findViewById<TextView>(R.id.diagnostics_text).text =
            PlatformDiagnosticsFormatter.format(PlatformDiagnostics.collect(this))
        updatePlaybackState(ConnectionState.NOT_CONNECTED)
        updateAudioState(AudioConnectionState.STOPPED)
        updateVideoBufferingMode(playbackController.currentBufferingMode)
    }

    override fun onStop() {
        pocWindowActive = false
        customVideo.stopForLifecycle()
        h264Probe?.close()
        h264Probe = null
        resetAudioStartupGate("Viewer session lifecycle release; audio-start gate reset")
        setViewerKeepScreenOn(false)
        playbackController.releaseForLifecycle()
        audioPlaybackController.releaseForLifecycle()
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        pocWindowActive = true
    }

    override fun onDestroy() {
        customVideo.stopForLifecycle()
        super.onDestroy()
    }

    private fun saveEndpoint(): Boolean {
        when (val result = CameraEndpointNormalizer.normalize(endpointInput.text.toString())) {
            is CameraEndpointNormalizer.Result.Valid -> {
                endpointStore.save(result.uri)
                endpointInput.setText(result.uri)
                configurationMessage.text = getString(R.string.configuration_saved)
                hideKeyboardAndCompleteInput()
                updatePlaybackState(playbackController.state)
                return true
            }
            is CameraEndpointNormalizer.Result.Invalid -> {
                configurationMessage.text = result.message
                return false
            }
        }
    }

    private fun connectSavedEndpoint() {
        if (customVideo.isActive) {
            configurationMessage.text = getString(R.string.stop_custom_video_first)
            return
        }
        if (h264Probe != null) {
            configurationMessage.text = getString(R.string.h264_probe_running)
            return
        }
        val configuredEndpoint = endpointStore.load()
        val result = configuredEndpoint?.let(CameraEndpointNormalizer::normalize)
        if (result is CameraEndpointNormalizer.Result.Valid) {
            cancelPendingInitialAudioStart()
            audioStartupGate.beginSession(result.uri)
            Log.i(TAG, "Viewer session connect: video starts first; audio gated until first frame")
            playbackController.connect(result.uri)
        } else {
            statusText.text = getString(R.string.error_status, getString(R.string.save_endpoint_first))
            connectButton.isEnabled = false
            disconnectButton.isEnabled = false
        }
    }

    private fun runH264Probe() {
        if (customVideo.isActive) {
            configurationMessage.text = getString(R.string.stop_custom_video_first)
            return
        }
        if (h264Probe != null) {
            configurationMessage.text = getString(R.string.h264_probe_running)
            return
        }
        if (playbackController.state != ConnectionState.NOT_CONNECTED ||
            audioPlaybackController.connectionState != AudioConnectionState.STOPPED
        ) {
            configurationMessage.text = getString(R.string.disconnect_before_h264_probe)
            return
        }
        val configuredEndpoint = endpointStore.load()
        val result = configuredEndpoint?.let(CameraEndpointNormalizer::normalize)
        if (result !is CameraEndpointNormalizer.Result.Valid) {
            configurationMessage.text = getString(R.string.save_endpoint_first)
            return
        }

        lateinit var probe: H264RtspProbe
        probe = H264RtspProbe(
            endpointUri = result.uri,
            listener = object : H264RtspProbe.Listener {
                override fun onComplete(result: H264ProbeResult) {
                    runOnUiThread {
                        if (h264Probe === probe) {
                            h264Probe = null
                            configurationMessage.text = if (result.outcome == H264ProbeOutcome.H264_RTP_DETECTED) {
                                getString(R.string.h264_probe_complete, result.h264.rtpPackets)
                            } else {
                                getString(R.string.h264_probe_no_matching_rtp)
                            }
                        }
                    }
                }

                override fun onFailure(message: String) {
                    runOnUiThread {
                        if (h264Probe === probe) {
                            h264Probe = null
                            configurationMessage.text = getString(R.string.h264_probe_failed, message)
                        }
                    }
                }
            }
        )
        h264Probe = probe
        configurationMessage.text = getString(R.string.h264_probe_running)
        Log.i(TAG, "Starting bounded H.264 video RTSP probe")
        probe.start()
    }

    private fun startCustomVideo() {
        if (customVideo.isActive) return
        if (h264Probe != null || playbackController.state != ConnectionState.NOT_CONNECTED ||
            audioPlaybackController.connectionState != AudioConnectionState.STOPPED
        ) {
            configurationMessage.text = getString(R.string.disconnect_before_custom_video)
            return
        }
        val endpoint = endpointStore.load()?.let(CameraEndpointNormalizer::normalize)
        if (endpoint !is CameraEndpointNormalizer.Result.Valid) {
            configurationMessage.text = getString(R.string.save_endpoint_first)
            return
        }
        findViewById<PlayerView>(R.id.video_view).visibility = View.INVISIBLE
        customSurface.visibility = View.VISIBLE
        customVideo.start(endpoint.uri)
    }

    private fun updateCustomVideoState(state: CustomVideoState, detail: String?) {
        val label = getString(when (state) {
            CustomVideoState.NOT_CONNECTED -> R.string.custom_video_not_connected
            CustomVideoState.CONNECTING -> R.string.custom_video_connecting
            CustomVideoState.WAITING_CONFIG -> R.string.custom_video_waiting_config
            CustomVideoState.WAITING_IDR -> R.string.custom_video_waiting_idr
            CustomVideoState.PLAYING -> R.string.custom_video_playing
            CustomVideoState.STOPPING -> R.string.custom_video_stopping
            CustomVideoState.ERROR -> R.string.custom_video_error
        })
        customVideoStatus.text = if (detail == null) label else getString(R.string.custom_video_detail, label, detail)
        customVideoButton.isEnabled = !customVideo.isActive
        stopCustomVideoButton.isEnabled = customVideo.isActive && state != CustomVideoState.STOPPING
        if (!customVideo.isActive) {
            customSurface.visibility = View.GONE
            findViewById<PlayerView>(R.id.video_view).visibility = View.VISIBLE
        }
        updatePlaybackState(playbackController.state)
    }

    private fun hideKeyboardAndCompleteInput() {
        val inputMethodManager = getSystemService(InputMethodManager::class.java)
        inputMethodManager.hideSoftInputFromWindow(endpointInput.windowToken, 0)
        endpointInput.clearFocus()
        saveButton.requestFocus()
    }

    private fun updatePlaybackState(state: ConnectionState) {
        val customActive = ::customVideo.isInitialized && customVideo.isActive
        setViewerKeepScreenOn(ViewerKeepScreenOnPolicy.shouldKeepScreenOn(state) || (customActive && pocWindowActive))
        if (state != ConnectionState.ERROR) {
            statusText.text = when (state) {
                ConnectionState.NOT_CONNECTED -> getString(R.string.not_connected)
                ConnectionState.CONNECTING -> getString(R.string.connecting)
                ConnectionState.PLAYING -> getString(R.string.playing)
                ConnectionState.RECONNECTING -> getString(R.string.reconnecting)
                ConnectionState.ERROR -> getString(R.string.error)
            }
        }
        connectButton.isEnabled = state == ConnectionState.NOT_CONNECTED && hasValidSavedEndpoint() && !customActive
        disconnectButton.isEnabled = state != ConnectionState.NOT_CONNECTED || customActive
    }

    private fun setViewerKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun resetAudioStartupGate(message: String) {
        cancelPendingInitialAudioStart()
        audioStartupGate.reset()
        Log.i(TAG, message)
    }

    private fun scheduleInitialAudioStart(token: Long) {
        cancelPendingInitialAudioStart()
        val runnable = Runnable {
            pendingInitialAudioStart = null
            when (val decision = audioStartupGate.consumeDelayedAudioStart(token)) {
                is ViewerAudioStartupGate.DelayedStartDecision.StartAudio -> {
                    Log.i(TAG, "Initial audio stabilization delay elapsed; starting audio")
                    audioPlaybackController.connect(decision.uri)
                }
                ViewerAudioStartupGate.DelayedStartDecision.IgnoredStaleCallback -> {
                    Log.i(TAG, "Ignoring stale delayed initial audio start")
                }
            }
        }
        pendingInitialAudioStart = runnable
        startupHandler.postDelayed(runnable, ViewerAudioStartupGate.INITIAL_AUDIO_AFTER_FIRST_FRAME_DELAY_MS)
        Log.i(
            TAG,
            "First video-frame callback received; scheduling initial audio in " +
                "${ViewerAudioStartupGate.INITIAL_AUDIO_AFTER_FIRST_FRAME_DELAY_MS}ms"
        )
    }

    private fun cancelPendingInitialAudioStart() {
        pendingInitialAudioStart?.let(startupHandler::removeCallbacks)
        pendingInitialAudioStart = null
    }

    private fun updateAudioState(state: AudioConnectionState) {
        audioStateText.text = when (state) {
            AudioConnectionState.STOPPED -> getString(R.string.audio_stopped)
            AudioConnectionState.CONNECTING -> getString(R.string.audio_connecting)
            AudioConnectionState.PLAYING -> getString(R.string.audio_playing)
            AudioConnectionState.RECONNECTING -> getString(R.string.audio_reconnecting)
            AudioConnectionState.ERROR -> getString(R.string.audio_error_state)
        }
        val active = state != AudioConnectionState.STOPPED
        muteButton.isEnabled = active
        processingButton.isEnabled = active
        muteButton.text = getString(
            if (audioPlaybackController.isMuted()) R.string.unmute else R.string.mute
        )
        processingButton.text = getString(
            if (audioPlaybackController.isProcessingEnabled()) R.string.processed_pcm else R.string.raw_pcm
        )
    }

    private fun updateVideoBufferingMode(mode: VideoBufferingMode) {
        videoBufferingButton.text = getString(R.string.video_buffering_mode, mode.label)
    }

    private fun formatAudioDiagnostics(diagnostics: AudioDiagnostics): String = getString(
        R.string.audio_diagnostics,
        diagnostics.controlUri ?: getString(R.string.not_available),
        diagnostics.payloadType?.toString() ?: getString(R.string.not_available),
        diagnostics.packetsReceived,
        diagnostics.payloadBytesReceived,
        diagnostics.latestSequenceNumber?.toString() ?: getString(R.string.not_available),
        diagnostics.sequenceDiscontinuities,
        diagnostics.duplicateOrOutOfOrderPackets,
        diagnostics.decodedSamples,
        diagnostics.bufferedDurationMs,
        diagnostics.staleDroppedBytes,
        diagnostics.reconnectCount,
        if (diagnostics.muted) getString(R.string.muted) else getString(R.string.unmuted),
        if (diagnostics.processingEnabled) getString(R.string.processed) else getString(R.string.raw)
    )

    private fun hasValidSavedEndpoint(): Boolean =
        endpointStore.load()?.let(CameraEndpointNormalizer::normalize) is CameraEndpointNormalizer.Result.Valid

    private companion object {
        const val TAG = "ViewerStartup"
    }
}
