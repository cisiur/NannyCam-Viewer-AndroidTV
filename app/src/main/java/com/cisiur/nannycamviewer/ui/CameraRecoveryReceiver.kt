package com.cisiur.nannycamviewer.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.cisiur.nannycamviewer.config.CameraEndpointNormalizer
import com.cisiur.nannycamviewer.config.CameraEndpointStore
import java.lang.ref.WeakReference

/**
 * Shell-only entry point for an already-existing viewer task. It intentionally does not start an
 * Activity: Android 11 background-launch policy makes that disruptive and unreliable.
 */
class CameraRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESTORE_CAMERA) {
            Log.w(TAG, "Recovery rejected: unexpected action")
            return
        }
        if (intent.data != null || intent.extras != null) {
            Log.w(TAG, "Recovery request contains ignored untrusted data or extras")
        }
        Log.i(TAG, "Recovery request received")

        val result = CameraEndpointStore(context.applicationContext).load()
            ?.let(CameraEndpointNormalizer::normalize)
        if (result !is CameraEndpointNormalizer.Result.Valid) {
            Log.w(TAG, "Recovery rejected: no valid saved camera endpoint")
            return
        }
        Log.i(TAG, "Stored camera endpoint is valid")
        if (!CameraRecoveryRegistry.dispatch(result.uri)) {
            Log.w(TAG, "Recovery rejected: no existing MainActivity session owner")
        }
    }

    companion object {
        const val ACTION_RESTORE_CAMERA = "com.cisiur.nannycamviewer.action.RESTORE_CAMERA"
        private const val TAG = "NannyCamRecovery"
    }
}

/** Keeps no Activity alive; it only lets the protected receiver reach a running task. */
internal object CameraRecoveryRegistry {
    private var owner: WeakReference<RecoverySessionOwner>? = null

    @Synchronized fun register(candidate: RecoverySessionOwner) {
        owner = WeakReference(candidate)
    }

    @Synchronized fun unregister(candidate: RecoverySessionOwner) {
        if (owner?.get() === candidate) owner = null
    }

    @Synchronized fun dispatch(endpoint: String): Boolean {
        val current = owner?.get() ?: return false
        current.onRecoveryCommand(endpoint)
        return true
    }
}

internal fun interface RecoverySessionOwner {
    fun onRecoveryCommand(endpoint: String)
}
