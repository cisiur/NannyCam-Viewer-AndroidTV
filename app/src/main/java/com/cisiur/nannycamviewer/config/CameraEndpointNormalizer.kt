package com.cisiur.nannycamviewer.config

import java.net.URI
import java.net.URISyntaxException

/** Converts a user-entered camera host or RTSP URI into the camera's configured endpoint. */
object CameraEndpointNormalizer {
    private const val DEFAULT_PATH = "/streamtype=0"
    private const val DEFAULT_PORT = 554

    fun normalize(rawInput: String): Result {
        val input = rawInput.trim()
        if (input.isEmpty()) return Result.Invalid("Enter a camera host or RTSP URI.")

        return if (input.contains("://")) normalizeUri(input) else normalizeHost(input)
    }

    private fun normalizeUri(input: String): Result = try {
        val uri = URI(input)
        when {
            !uri.scheme.equals("rtsp", ignoreCase = true) ->
                Result.Invalid("Only RTSP URIs are supported.")
            uri.host.isNullOrBlank() -> Result.Invalid("The RTSP URI needs a host.")
            uri.userInfo?.contains(Regex("\\s")) == true -> Result.Invalid("The RTSP URI is invalid.")
            else -> Result.Valid(input)
        }
    } catch (_: URISyntaxException) {
        Result.Invalid("The RTSP URI is invalid.")
    }

    private fun normalizeHost(input: String): Result {
        if (!isHost(input)) return Result.Invalid("Enter a valid host name, IP address, or RTSP URI.")
        return Result.Valid("rtsp://$input:$DEFAULT_PORT$DEFAULT_PATH")
    }

    private fun isHost(value: String): Boolean {
        if (value.length > 253 || value.contains("..") || value.startsWith('.') || value.endsWith('.')) return false
        if (value.all { it.isDigit() || it == '.' }) {
            val octets = value.split('.')
            return octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 }
        }
        return value.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 &&
                label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                label.all { it.isLetterOrDigit() || it == '-' }
        }
    }

    sealed interface Result {
        data class Valid(val uri: String) : Result
        data class Invalid(val message: String) : Result
    }
}
