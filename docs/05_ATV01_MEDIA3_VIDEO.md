# ATV-01 Media3 Video Implementation Notes

## Scope

ATV-01 uses Media3 `1.9.4` as a video-only RTSP path. This is the latest stable
Media3 line compatible with the existing AGP 8.6.1 / compileSdk 35 baseline; newer
Media3 1.11.x requires compileSdk 36. Physical validation on the target Fire TV has
confirmed that this path renders the camera's live H.264 video.

The player uses `RtspMediaSource.Factory.setForceUseRtpTcp()` so RTP is interleaved in
the RTSP TCP connection from the first attempt; it does not silently begin with UDP.

## Video-only handling

The camera SDP's PCMA declaration is known to be malformed for this camera. ATV-01 does
not decode, reinterpret, render, route, or otherwise handle camera audio. The Media3
track selector disables `TRACK_TYPE_AUDIO`, so this session intentionally renders video
only. This is not an audio workaround or a conclusion about ATV-02.

The first physical connection attempt failed during Media3 RTSP SDP parsing with
`ERROR_CODE_IO_UNSPECIFIED`, caused by `IllegalArgumentException: missing attribute fmtp`
from `RtspMediaTrack.generatePayloadFormat` / `RtspClient.buildTrackList`. This confirms
that Media3 parses the SDP before disabled audio tracks are fully irrelevant. A subsequent
reconnect succeeded and rendered video, so ATV-01 has no custom parser, Media3 patch, or
audio workaround for this finding.

## Decoder diagnostics

Media3 is allowed to select its normal platform decoder. The app listens for Media3's
video-decoder-initialized analytics event, logs the decoder name, and shows it next to
the video surface. It does not force `OMX.MTK.VIDEO.DECODER.AVC` by name. Physical
validation selected `OMX.MTK.VIDEO.DECODER.AVC`, confirming use of the expected
hardware-accelerated vendor AVC decoder.

## Reconnect and liveness

One `VideoPlaybackController` owns at most one player and one scheduled retry. It uses
the retry sequence 1 s, 2 s, 3 s, then 5 s repeatedly. Explicit Disconnect and Activity
stop cancel retry intent, remove callbacks, release the player, and clear the attached
surface. Successful playback resets the retry schedule. Reconnect is currently triggered
by Media3's `onPlayerError` callback, including errors surfaced by the RTSP transport.

The previous application-level five-second frame-progress watchdog used
`onVideoFrameProcessingOffset`. On the target Fire TV / Media3 combination that callback
did not provide a reliable continuous progress signal and caused false reconnects after
working playback. It has therefore been removed rather than replaced with another
speculative watchdog. The five-second `RtspMediaSource` timeout remains a distinct RTSP
transport/source timeout; it is not an application-level video-frame liveness detector.

Physical testing also exposed a separate startup hang: Media3 initialized the expected
decoder but remained in `BUFFERING`, with neither a rendered first frame nor
`onPlayerError`. `VideoPlaybackController` now arms a one-time first-frame
startup timeout for each player attempt. A rendered first frame cancels it immediately;
expiry releases that attempt and enters the existing reconnect sequence. This is not a
continuous playback/frame-progress watchdog: after the first frame, the application keeps
no video liveness timer active. Physical validation confirmed this corrective: an occasional
first attempt stalled before first frame, the timeout released it, the existing
one-second reconnect ran, and the replacement attempt rendered its first frame in
approximately 1.47 seconds.

Recovery after actual camera or network loss still requires physical validation. The
initial SDP failure above did recover through the existing reconnect policy.

## Latency support

The controller logs the Connect request and first rendered frame, and displays an
approximate startup latency. Physical tests observed successful Connect-to-first-frame
times of approximately 2.7–4.7 seconds; the later replacement attempt above reached its
first frame in approximately 1.47 seconds. The latest observed video delay was
approximately 1–2 seconds behind live. No continuous frame-progress watchdog is active.
Long-run latency and progressive-lag testing over 15–30 minutes remain pending; no long-run
stability conclusion can be made from automated tests or short runs.

## Startup and latency experiment

The physically observed successful first-frame range is approximately 1.47–4.7 seconds,
while the known stalled attempt produced no frame. The attempt-scoped first-frame timeout is
therefore reduced from 10 seconds to 6 seconds: it remains above the slowest successful
observation while reducing recovery time for a clearly stalled attempt. It is still cancelled
by the first rendered frame, explicit disconnect, lifecycle release, or replacement player;
there is no continuous post-first-frame watchdog.

Each player attempt records monotonic `SystemClock.elapsedRealtime()` timing for attempt
start, player creation, `prepare()`, decoder initialization, first rendered frame, and
`STATE_READY`. The resulting log deltas are intended for repeated physical measurements,
not continuous telemetry.

This build defaults its developer-only `Video Buffer` toggle to **Low Latency**. That mode
uses Media3 `DefaultLoadControl` with a 500 ms minimum buffer, 1500 ms maximum buffer,
100 ms playback threshold, and 250 ms rebuffer threshold. **Default** creates the player
without a custom `LoadControl`, preserving Media3's normal behavior. Changing the toggle
does not alter a current player; Disconnect/Connect provides a clean A/B attempt. The RTSP
transport/source timeout remains 5 seconds and is not a startup-buffer control. Media3,
RTSP-over-TCP, decoder selection, and the video architecture are unchanged. Physical A/B
testing found **Low Latency** materially reduced perceived live video lag and is the current
preferred mode. It did not, however, explain the long cold-start path described below.

## Cold-start sequencing experiment

Subsequent Disconnect/Connect attempts were generally fast in the latest physical A/B test,
but the first connection after app launch remained slower. That cold start logged repeated
Media3 `IllegalArgumentException: missing attribute fmtp` SDP failures: three video attempts
failed before a fourth succeeded. The retries, rather than `LoadControl`, were the main cause
of the long first startup.

The decoder-gated experiment had a mixed first physical result. On a fresh app launch, video
succeeded on attempt #1 and did not reproduce `missing attribute fmtp`; the hardware decoder
initialized at approximately 560 ms, Media3 reported its first-frame callback at approximately
2.19 seconds, and `STATE_READY` at approximately 2.24 seconds. Audio also started and its
keepalive continued normally. This is one encouraging run, not proof that the SDP issue is
fixed.

Critically, the visible output in that entire session was a solid/mostly solid green rectangle.
No valid camera image appeared during more than two minutes of playback despite the decoder,
first-frame, and READY callbacks. Consequently, `onRenderedFirstFrame()` and `STATE_READY`
must not be treated as evidence that the decoded camera picture is visually valid on this
camera/device combination.

This experiment still retains two independent RTSP/TCP sessions. The previous version started
the one initial custom-audio connection immediately after `onVideoDecoderInitialized`. The
next version instead uses first-frame only as a later *sequencing milestone*, then waits 250 ms
before starting audio. Decoder initialization alone now leaves audio gated. If the video attempt
fails before first frame, or fails during that stabilization delay, pending audio startup is
cancelled and a later replacement attempt may schedule it again. Once audio starts, its lifecycle
and recovery remain independent through later video reconnects. Physical validation is required;
this change does not claim to fix either the SDP failures or persistent green video.

## Display idle behavior

The viewer now applies `FLAG_KEEP_SCREEN_ON` to this Activity's window only while its video
session intent is connecting, playing, or reconnecting. It is cleared on explicit disconnect
and Activity stop, so this is not a permanent Fire OS setting and does not claim to suppress
every possible long-duration system prompt. This corrective awaits physical idle/screensaver
validation on the target Fire TV.
