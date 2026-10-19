package com.cisiur.nannycamviewer.config

import android.content.Context

class CameraEndpointStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): String? = preferences.getString(KEY_ENDPOINT, null)

    fun save(endpoint: String) {
        preferences.edit().putString(KEY_ENDPOINT, endpoint).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "camera_configuration"
        const val KEY_ENDPOINT = "camera_endpoint"
    }
}
