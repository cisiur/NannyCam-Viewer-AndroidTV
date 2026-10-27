package com.cisiur.nannycamviewer.ui

/** UI availability only; connection intent and workers stay in the media controllers. */
data class ViewerControls(val canConnect: Boolean, val canDisconnect: Boolean, val canProbe: Boolean) {
    companion object {
        fun from(
            validEndpoint: Boolean,
            videoBusy: Boolean,
            viewerSession: Boolean,
            audioStopped: Boolean,
            probeRunning: Boolean
        ) = ViewerControls(
            canConnect = validEndpoint && !videoBusy && !probeRunning,
            canDisconnect = viewerSession || videoBusy,
            canProbe = validEndpoint && !viewerSession && !videoBusy && audioStopped && !probeRunning
        )
    }
}

/** Fit the known 16:9 picture without cropping or stretching. */
internal fun fitVideoSize(width: Int, height: Int): Pair<Int, Int> =
    if (width.toLong() * 9 <= height.toLong() * 16) width to width * 9 / 16
    else height * 16 / 9 to height
