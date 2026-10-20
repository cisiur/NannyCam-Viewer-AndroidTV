# ATV-01 Media3 Video Implementation Notes

## Scope

ATV-01 uses Media3 `1.9.4` as a video-only RTSP candidate. This is the latest stable
Media3 line compatible with the existing AGP 8.6.1 / compileSdk 35 baseline; newer
Media3 1.11.x requires compileSdk 36. It must be tested on the physical Fire TV before
it is considered a proven media stack.

The player uses `RtspMediaSource.Factory.setForceUseRtpTcp()` so RTP is interleaved in
the RTSP TCP connection from the first attempt; it does not silently begin with UDP.

## Video-only handling

The camera SDP's PCMA declaration is known to be malformed for this camera. ATV-01 does
not decode, reinterpret, render, route, or otherwise handle camera audio. The Media3
track selector disables `TRACK_TYPE_AUDIO`, so this session intentionally renders video
only. This is not an audio workaround or a conclusion about ATV-02.

No physical test has yet established whether the malformed advertised audio affects
Media3 source setup before track selection. If it does, record the observation and make
only the smallest change necessary to preserve video for ATV-01.

## Decoder diagnostics

Media3 is allowed to select its normal platform decoder. The app listens for Media3's
video-decoder-initialized analytics event, logs the decoder name, and shows it next to
the video surface. It does not force `OMX.MTK.VIDEO.DECODER.AVC` by name. On the
physical target, compare the selected decoder with the tested platform baseline and
confirm that it is hardware accelerated through the existing codec diagnostics.

## Reconnect and liveness

One `VideoPlaybackController` owns at most one player and one scheduled retry. It uses
the retry sequence 1 s, 2 s, 3 s, then 5 s repeatedly. Explicit Disconnect and Activity
stop cancel retry intent, remove callbacks, release the player, and clear the attached
surface.

The initial liveness signal is Media3's first-frame and video-frame-processing callbacks.
A five-second watchdog with no observed video progress treats the session as failed. The
RTSP source timeout is also five seconds, allowing Media3 to surface an end-of-stream
condition when no RTP data arrives. These values are an initial reference, not a claim
that physical latency or recovery is already validated.

## Latency support

The controller logs the Connect request and first rendered frame, and displays an
approximate startup latency. The physical test must compare latency immediately after
connect and after 15–30 minutes; no latency conclusion can be made from automated tests.
