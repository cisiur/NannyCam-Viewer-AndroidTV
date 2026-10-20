package com.cisiur.nannycamviewer.ui

import android.app.Activity
import android.os.Bundle
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
import com.cisiur.nannycamviewer.platform.PlatformDiagnostics
import com.cisiur.nannycamviewer.platform.PlatformDiagnosticsFormatter
import com.cisiur.nannycamviewer.playback.ConnectionState
import com.cisiur.nannycamviewer.playback.VideoPlaybackController

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

        endpointStore.load()?.let(endpointInput::setText)
        playbackController = VideoPlaybackController(
            this,
            findViewById<PlayerView>(R.id.video_view),
            object : VideoPlaybackController.Listener {
                override fun onConnectionStateChanged(state: ConnectionState) = updatePlaybackState(state)

                override fun onSelectedVideoDecoder(decoderName: String) {
                    decoderText.text = getString(R.string.selected_decoder, decoderName)
                }

                override fun onPlaybackError(message: String) {
                    statusText.text = getString(R.string.error_status, message)
                }

                override fun onFirstFrameRendered(startupLatencyMs: Long) {
                    configurationMessage.text = getString(R.string.first_frame_rendered, startupLatencyMs)
                }
            }
        )

        saveButton.setOnClickListener { saveEndpoint() }
        connectButton.setOnClickListener { connectSavedEndpoint() }
        disconnectButton.setOnClickListener { playbackController.disconnect() }
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
    }

    override fun onStop() {
        playbackController.releaseForLifecycle()
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

    private fun hasValidSavedEndpoint(): Boolean =
        endpointStore.load()?.let(CameraEndpointNormalizer::normalize) is CameraEndpointNormalizer.Result.Valid
}
