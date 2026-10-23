package com.cisiur.nannycamviewer.ui

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
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
import com.cisiur.nannycamviewer.audio.AudioConnectionState
import com.cisiur.nannycamviewer.audio.AudioDiagnostics
import com.cisiur.nannycamviewer.audio.AudioPlaybackController
import com.cisiur.nannycamviewer.platform.PlatformDiagnostics
import com.cisiur.nannycamviewer.platform.PlatformDiagnosticsFormatter
import com.cisiur.nannycamviewer.playback.ConnectionState
import com.cisiur.nannycamviewer.playback.VideoBufferingMode
import com.cisiur.nannycamviewer.playback.VideoPlaybackController
import com.cisiur.nannycamviewer.playback.ViewerKeepScreenOnPolicy

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
    private lateinit var audioPlaybackController: AudioPlaybackController
    private val audioStartupGate = ViewerAudioStartupGate()

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

        endpointStore.load()?.let(endpointInput::setText)
        playbackController = VideoPlaybackController(
            this,
            findViewById<PlayerView>(R.id.video_view),
            object : VideoPlaybackController.Listener {
                override fun onConnectionStateChanged(state: ConnectionState) = updatePlaybackState(state)

                override fun onSelectedVideoDecoder(decoderName: String) {
                    decoderText.text = getString(R.string.selected_decoder, decoderName)
                    when (val decision = audioStartupGate.onVideoDecoderInitialized()) {
                        is ViewerAudioStartupGate.Decision.StartAudio -> {
                            Log.i(TAG, "Video decoder initialized; starting initial audio session")
                            audioPlaybackController.connect(decision.uri)
                        }
                        ViewerAudioStartupGate.Decision.AlreadyStarted -> {
                            Log.i(TAG, "Initial audio already started for viewer session; ignoring duplicate decoder callback")
                        }
                        ViewerAudioStartupGate.Decision.NoActiveSession -> {
                            Log.i(TAG, "Ignoring decoder callback without an active viewer session")
                        }
                    }
                }

                override fun onPlaybackError(message: String) {
                    statusText.text = getString(R.string.error_status, message)
                    if (audioStartupGate.remainsGatedAfterVideoFailure()) {
                        Log.i(TAG, "Video attempt failed before audio start; audio remains gated")
                    }
                }

                override fun onFirstFrameRendered(startupLatencyMs: Long) {
                    configurationMessage.text = getString(R.string.first_frame_rendered, startupLatencyMs)
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

        saveButton.setOnClickListener { saveEndpoint() }
        connectButton.setOnClickListener { connectSavedEndpoint() }
        disconnectButton.setOnClickListener {
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
        resetAudioStartupGate("Viewer session lifecycle release; audio-start gate reset")
        setViewerKeepScreenOn(false)
        playbackController.releaseForLifecycle()
        audioPlaybackController.releaseForLifecycle()
        super.onStop()
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
        val configuredEndpoint = endpointStore.load()
        val result = configuredEndpoint?.let(CameraEndpointNormalizer::normalize)
        if (result is CameraEndpointNormalizer.Result.Valid) {
            audioStartupGate.beginSession(result.uri)
            Log.i(TAG, "Viewer session connect: video starts first; audio start gated")
            playbackController.connect(result.uri)
        } else {
            statusText.text = getString(R.string.error_status, getString(R.string.save_endpoint_first))
            connectButton.isEnabled = false
            disconnectButton.isEnabled = false
        }
    }

    private fun hideKeyboardAndCompleteInput() {
        val inputMethodManager = getSystemService(InputMethodManager::class.java)
        inputMethodManager.hideSoftInputFromWindow(endpointInput.windowToken, 0)
        endpointInput.clearFocus()
        saveButton.requestFocus()
    }

    private fun updatePlaybackState(state: ConnectionState) {
        setViewerKeepScreenOn(ViewerKeepScreenOnPolicy.shouldKeepScreenOn(state))
        if (state != ConnectionState.ERROR) {
            statusText.text = when (state) {
                ConnectionState.NOT_CONNECTED -> getString(R.string.not_connected)
                ConnectionState.CONNECTING -> getString(R.string.connecting)
                ConnectionState.PLAYING -> getString(R.string.playing)
                ConnectionState.RECONNECTING -> getString(R.string.reconnecting)
                ConnectionState.ERROR -> getString(R.string.error)
            }
        }
        connectButton.isEnabled = state == ConnectionState.NOT_CONNECTED && hasValidSavedEndpoint()
        disconnectButton.isEnabled = state != ConnectionState.NOT_CONNECTED
    }

    private fun setViewerKeepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun resetAudioStartupGate(message: String) {
        audioStartupGate.reset()
        Log.i(TAG, message)
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
