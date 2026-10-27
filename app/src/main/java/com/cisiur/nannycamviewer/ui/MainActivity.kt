package com.cisiur.nannycamviewer.ui

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.cisiur.nannycamviewer.R
import com.cisiur.nannycamviewer.config.CameraEndpointNormalizer
import com.cisiur.nannycamviewer.config.CameraEndpointStore
import com.cisiur.nannycamviewer.audio.AudioConnectionState
import com.cisiur.nannycamviewer.audio.AudioDiagnostics
import com.cisiur.nannycamviewer.audio.AudioPlaybackController
import com.cisiur.nannycamviewer.platform.PlatformDiagnostics
import com.cisiur.nannycamviewer.platform.PlatformDiagnosticsFormatter
import com.cisiur.nannycamviewer.playback.ConnectionState
import com.cisiur.nannycamviewer.playback.VideoPlaybackController
import com.cisiur.nannycamviewer.playback.ViewerKeepScreenOnPolicy
import com.cisiur.nannycamviewer.probe.H264ProbeOutcome
import com.cisiur.nannycamviewer.probe.H264ProbeResult
import com.cisiur.nannycamviewer.probe.H264RtspProbe

class MainActivity : Activity() {
    private lateinit var endpointInput: EditText
    private lateinit var configurationMessage: TextView
    private lateinit var probeMessage: TextView
    private lateinit var videoDiagnosticsText: TextView
    private lateinit var audioErrorText: TextView
    private lateinit var viewerContainer: ViewGroup
    private lateinit var viewerControls: View
    private lateinit var settingsContainer: View
    private lateinit var settingsButton: Button
    private var showingSettings = false
    private var unregisterSettingsBack: (() -> Unit)? = null
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
    private lateinit var h264ProbeButton: Button
    private lateinit var audioPlaybackController: AudioPlaybackController
    private var h264Probe: H264RtspProbe? = null
    private lateinit var videoSurface: SurfaceView
    private var viewerEndpointUri: String? = null
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
        probeMessage = findViewById(R.id.probe_message)
        videoDiagnosticsText = findViewById(R.id.video_diagnostics_text)
        audioErrorText = findViewById(R.id.audio_error_text)
        viewerContainer = findViewById(R.id.viewer_container)
        viewerControls = findViewById(R.id.viewer_controls)
        settingsContainer = findViewById(R.id.settings_container)
        settingsButton = findViewById(R.id.settings_button)
        saveButton = findViewById(R.id.save_button)
        connectButton = findViewById(R.id.connect_button)
        disconnectButton = findViewById(R.id.disconnect_button)
        statusText = findViewById(R.id.status_text)
        decoderText = findViewById(R.id.decoder_text)
        audioStateText = findViewById(R.id.audio_state_text)
        audioDiagnosticsText = findViewById(R.id.audio_diagnostics_text)
        muteButton = findViewById(R.id.mute_button)
        processingButton = findViewById(R.id.processing_button)
        h264ProbeButton = findViewById(R.id.h264_probe_button)
        videoSurface = findViewById(R.id.video_surface)

        endpointStore.load()?.let(endpointInput::setText)
        playbackController = VideoPlaybackController(
            videoSurface,
            object : VideoPlaybackController.Listener {
                override fun onConnectionStateChanged(state: ConnectionState) = updatePlaybackState(state)

                override fun onSelectedVideoDecoder(decoderName: String, attemptId: Long) {
                    decoderText.text = getString(R.string.selected_decoder, decoderName)
                }

                override fun onPlaybackError(message: String, attemptId: Long) {
                    videoDiagnosticsText.text = getString(R.string.video_issue, message)
                    when (audioStartupGate.onVideoFailure(attemptId)) {
                        ViewerAudioStartupGate.VideoFailureDecision.CancelPendingAudioStart -> {
                            cancelPendingInitialAudioStart()
                            Log.i(TAG, "Video failed before initial audio start; pending audio start cancelled")
                        }
                        ViewerAudioStartupGate.VideoFailureDecision.AudioRemainsGated -> {
                            Log.i(TAG, "Video attempt failed before initial audio start; audio remains gated")
                        }
                        ViewerAudioStartupGate.VideoFailureDecision.AudioAlreadyStarted -> Unit
                        ViewerAudioStartupGate.VideoFailureDecision.NoActiveSession -> Unit
                    }
                }

                override fun onFirstVideoOutput(startupLatencyMs: Long, attemptId: Long) {
                    videoDiagnosticsText.text = getString(R.string.first_video_output, startupLatencyMs)
                    when (val decision = audioStartupGate.onVideoFirstFrame(attemptId)) {
                        is ViewerAudioStartupGate.FirstFrameDecision.ScheduleAudio -> {
                            scheduleInitialAudioStart(decision.token)
                        }
                        ViewerAudioStartupGate.FirstFrameDecision.AlreadyScheduled -> {
                            Log.i(TAG, "Initial audio start already scheduled; ignoring duplicate output event")
                        }
                        ViewerAudioStartupGate.FirstFrameDecision.AlreadyStarted -> {
                            Log.i(TAG, "Initial audio already started; video reconnect remains independent")
                        }
                        ViewerAudioStartupGate.FirstFrameDecision.IgnoredFailedAttempt -> {
                            Log.i(TAG, "Ignoring output event from a failed video attempt")
                        }
                        ViewerAudioStartupGate.FirstFrameDecision.NoActiveSession -> {
                            Log.i(TAG, "Ignoring video output without an active viewer session")
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
                    audioErrorText.text = getString(R.string.audio_error, message)
                }

                override fun onAudioDiagnostics(diagnostics: AudioDiagnostics) {
                    audioDiagnosticsText.text = formatAudioDiagnostics(diagnostics)
                }
            }
        )

        saveButton.setOnClickListener { saveEndpoint() }
        connectButton.setOnClickListener { connectSavedEndpoint() }
        disconnectButton.setOnClickListener { disconnectViewer() }
        settingsButton.setOnClickListener { showSettings(true) }
        findViewById<Button>(R.id.settings_back_button).setOnClickListener { showSettings(false) }
        muteButton.setOnClickListener {
            audioPlaybackController.toggleMute()
            updateAudioState(audioPlaybackController.connectionState)
        }
        processingButton.setOnClickListener {
            audioPlaybackController.toggleProcessing()
            updateAudioState(audioPlaybackController.connectionState)
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
        preferredViewerControl().requestFocus()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Uses the Activity Back contract, including the Fire TV remote")
    override fun onBackPressed() {
        if (showingSettings) showSettings(false) else super.onBackPressed()
    }

    private fun showSettings(show: Boolean) {
        showingSettings = show
        unregisterSettingsBack?.invoke()
        unregisterSettingsBack = null
        if (show && Build.VERSION.SDK_INT >= 33) {
            val callback = android.window.OnBackInvokedCallback { showSettings(false) }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback
            )
            unregisterSettingsBack = { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback) }
        }
        // Never hide/reparent the viewer container or SurfaceView here: that would destroy
        // the Surface and restart the active video attempt. An opaque sibling covers it.
        viewerContainer.descendantFocusability = if (show) ViewGroup.FOCUS_BLOCK_DESCENDANTS
            else ViewGroup.FOCUS_AFTER_DESCENDANTS
        viewerControls.visibility = if (show) View.INVISIBLE else View.VISIBLE
        settingsContainer.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            saveButton.requestFocus() // Do not open the IME merely by opening diagnostics.
        } else {
            hideKeyboard()
            endpointInput.clearFocus()
            preferredViewerControl().requestFocus()
        }
    }

    private fun disconnectViewer() {
        resetAudioStartupGate("Viewer session disconnected; audio-start gate reset")
        playbackController.disconnect()
        audioPlaybackController.disconnect()
        setViewerKeepScreenOn(false)
        refreshControls()
    }

    override fun onStop() {
        h264Probe?.close()
        h264Probe = null
        refreshControls()
        resetAudioStartupGate("Viewer session lifecycle release; audio-start gate reset")
        setViewerKeepScreenOn(false)
        playbackController.releaseForLifecycle()
        audioPlaybackController.releaseForLifecycle()
        super.onStop()
    }

    override fun onDestroy() {
        unregisterSettingsBack?.invoke()
        playbackController.destroy()
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
        if (playbackController.isBusy) return
        if (h264Probe != null) {
            statusText.text = getString(R.string.probe_in_progress)
            return
        }
        val configuredEndpoint = viewerEndpointUri ?: endpointStore.load()
        val result = configuredEndpoint?.let(CameraEndpointNormalizer::normalize)
        if (result is CameraEndpointNormalizer.Result.Valid) {
            if (viewerEndpointUri == null) {
                viewerEndpointUri = result.uri
                audioStartupGate.beginSession(result.uri)
            }
            videoSurface.visibility = View.VISIBLE
            videoDiagnosticsText.text = ""
            audioErrorText.text = ""
            Log.i(TAG, "Viewer session connect: hardware video first; initial audio gated until video output")
            playbackController.connect(result.uri)
        } else {
            refreshControls()
        }
    }

    private fun runH264Probe() {
        if (h264Probe != null) {
            probeMessage.text = getString(R.string.h264_probe_running)
            return
        }
        val configuredEndpoint = endpointStore.load()
        val result = configuredEndpoint?.let(CameraEndpointNormalizer::normalize)
        if (result !is CameraEndpointNormalizer.Result.Valid) {
            probeMessage.text = getString(R.string.save_endpoint_first)
            return
        }
        if (!controlAvailability().canProbe) {
            probeMessage.text = getString(R.string.disconnect_before_h264_probe)
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
                            probeMessage.text = if (result.outcome == H264ProbeOutcome.H264_RTP_DETECTED) {
                                getString(R.string.h264_probe_complete, result.h264.rtpPackets)
                            } else {
                                getString(R.string.h264_probe_no_matching_rtp)
                            }
                            refreshControls()
                        }
                    }
                }

                override fun onFailure(message: String) {
                    runOnUiThread {
                        if (h264Probe === probe) {
                            h264Probe = null
                            probeMessage.text = getString(R.string.h264_probe_failed, message)
                            refreshControls()
                        }
                    }
                }
            }
        )
        h264Probe = probe
        probeMessage.text = getString(R.string.h264_probe_running)
        refreshControls()
        Log.i(TAG, "Starting bounded H.264 video RTSP probe")
        probe.start()
    }

    private fun hideKeyboardAndCompleteInput() {
        hideKeyboard()
        endpointInput.clearFocus()
        saveButton.requestFocus()
    }

    private fun hideKeyboard() {
        val inputMethodManager = getSystemService(InputMethodManager::class.java)
        inputMethodManager.hideSoftInputFromWindow(endpointInput.windowToken, 0)
    }

    private fun updatePlaybackState(state: ConnectionState) {
        setViewerKeepScreenOn(ViewerKeepScreenOnPolicy.shouldKeepScreenOn(state))
        refreshControls()
        if (!playbackController.isBusy) videoSurface.visibility = View.INVISIBLE
    }

    private fun updateStatus(state: ConnectionState) {
        statusText.text = when (state) {
            ConnectionState.NOT_CONNECTED -> getString(when {
                h264Probe != null -> R.string.probe_in_progress
                !hasValidSavedEndpoint() -> R.string.configuration_required
                else -> R.string.not_connected
            })
            ConnectionState.CONNECTING -> getString(R.string.connecting)
            ConnectionState.WAITING_VIDEO -> getString(R.string.waiting_for_video)
            ConnectionState.PLAYING -> getString(R.string.live)
            ConnectionState.RECONNECTING -> getString(R.string.reconnecting)
            ConnectionState.ERROR -> getString(R.string.error)
        }
    }

    private fun controlAvailability() = ViewerControls.from(
        validEndpoint = hasValidSavedEndpoint(),
        videoBusy = playbackController.isBusy,
        viewerSession = viewerEndpointUri != null,
        audioStopped = audioPlaybackController.connectionState == AudioConnectionState.STOPPED,
        probeRunning = h264Probe != null
    )

    private fun refreshControls() {
        val oldFocus = currentFocus
        val controls = controlAvailability()
        connectButton.isEnabled = controls.canConnect
        connectButton.visibility = if (playbackController.isBusy) View.GONE else View.VISIBLE
        disconnectButton.isEnabled = controls.canDisconnect
        disconnectButton.visibility = if (controls.canDisconnect) View.VISIBLE else View.GONE
        muteButton.visibility = if (controls.canDisconnect) View.VISIBLE else View.GONE
        muteButton.isEnabled = audioPlaybackController.connectionState != AudioConnectionState.STOPPED
        // Keep the probe reachable so its guard can explain why Disconnect is required.
        h264ProbeButton.isEnabled = h264Probe == null
        updateStatus(playbackController.state)
        val buttons = listOf(connectButton, disconnectButton, muteButton, settingsButton)
        val focusOrder = buttons.filter { it.visibility == View.VISIBLE && it.isEnabled }
        focusOrder.forEachIndexed { index, button ->
            button.nextFocusLeftId = focusOrder.getOrElse(index - 1) { button }.id
            button.nextFocusRightId = focusOrder.getOrElse(index + 1) { button }.id
            button.nextFocusUpId = button.id
            button.nextFocusDownId = button.id
            button.nextFocusForwardId = focusOrder.getOrElse(index + 1) { button }.id
        }
        if (!showingSettings && oldFocus in buttons && oldFocus !in focusOrder) {
            preferredViewerControl().requestFocus()
        } else if (showingSettings && oldFocus === h264ProbeButton && !h264ProbeButton.isEnabled) {
            processingButton.requestFocus()
        }
    }

    private fun preferredViewerControl(): Button = when {
        connectButton.isEnabled && connectButton.visibility == View.VISIBLE -> connectButton
        disconnectButton.isEnabled && disconnectButton.visibility == View.VISIBLE -> disconnectButton
        else -> settingsButton
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
        viewerEndpointUri = null
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
            "First hardware video output received; scheduling initial audio in " +
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
        // The existing controller allows choosing Raw/Processed before connecting too.
        processingButton.isEnabled = true
        muteButton.text = getString(
            if (audioPlaybackController.isMuted()) R.string.unmute else R.string.mute
        )
        processingButton.text = getString(
            if (audioPlaybackController.isProcessingEnabled()) R.string.processed_pcm else R.string.raw_pcm
        )
        if (state == AudioConnectionState.PLAYING) audioErrorText.text = ""
        refreshControls()
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
