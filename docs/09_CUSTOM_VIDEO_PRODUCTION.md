# ATV-VID-01 — Production custom video

## Decision and physical evidence

Normal Connect now uses custom RTSP/TCP H.264 processing and hardware MediaCodec. The user
physically validated the preceding POC with a correct image, no green rectangle, very fast
startup, nearly unnoticeable perceived latency, and excellent playback quality. That result
established the architecture and triggered this production migration.

Media3 was retired after intermittent SDP `missing attribute fmtp` errors, startup stalls,
and persistent green output despite first-frame/READY callbacks. Its rationale and earlier
measurements remain historical in `docs/05_ATV01_MEDIA3_VIDEO.md`. ExoPlayer, RTSP/UI
dependencies, PlayerView, Media3 first-frame timeout, and DEFAULT/LOW_LATENCY A/B controls
are removed. There is one production video implementation and no competing POC controller.

**Production normal-Connect Fire TV validation is still pending.** The POC's physical result
does not prove the new reconnect, audio integration, lifecycle, or endurance behavior.

## Architecture and ownership

```text
video: own RTSP/TCP session -> interleaved RTP -> H.264 depacketizer
       -> access-unit assembler -> bounded live queue -> hardware MediaCodec -> SurfaceView

audio: separate RTSP/TCP session -> RTP -> actual PCM16LE 8 kHz mono
       -> existing DSP/bounded PCM buffering -> AudioTrack
```

`VideoPlaybackController` binds the SurfaceHolder and a platform-independent
`VideoSessionCoordinator`. The coordinator owns connect intent, attempt/generation tokens,
one cancellable retry, external state, and success/failure publication. `VideoPlaybackAttempt`
owns fresh network and decoder workers, pipeline, socket, and codec resources for one attempt.
Callbacks are marshalled to the main thread and validated against the current generation.

All MediaCodec operations, including configure/stop/release, belong to the decoder thread.
Network parsing never waits for decoder input/output. The camera endpoint is restored from
existing local configuration; no camera IP or decoder name is forced.

## RTSP, H.264, and codec setup

`VideoRtspSession` performs TCP connect, DESCRIBE, H.264 media/control selection, video SETUP,
and PLAY. The returned Transport channel pair is authoritative. Only negotiated video RTP
with the matching SDP PT enters the video pipeline. Unrelated channel 4 is explicitly ignored,
including the physically observed PT8/640-byte extra stream. No video socket carries output
to the audio controller.

The tested single-NAL, FU-A reconstruction, STAP-A, CSRC/extension/padding validation,
sequence-gap handling, marker-closed AUs, and 90 kHz/wrapping RTP timestamp conversion are
retained. Incomplete/corrupt work is discarded, not fed as arbitrary RTP fragments. A NAL is
bounded to 1 MiB and an AU to 2 MiB. SPS/PPS can come from SDP or in-band even when fmtp is
missing. Identical parameter sets do not reset the codec; changed config invalidates references.

The decoder uses `MediaCodec.createDecoderByType("video/avc")`, verifies hardware acceleration
and rejects software decoding, then logs the selected name. Hardware classification retains
the POC's API 29+ policy; unsupported versions/selection enter terminal Error. App minSdk is
unchanged. The known camera-specific **1920×1080** format assumption is retained; no new SPS
dimension parser is added. Annex-B-prefixed SPS/PPS populate `csd-0`/`csd-1` once through
configure/start, rather than being duplicated as picture inputs.

## Freshness, startup, and transport liveness

The application queue remains capped at **three complete AUs**, with no added prebuffer.
Overflow discards stale queued work, invalidates the decoder reference chain, and waits for
a fresh IDR. The newest available IDR can recover immediately. Queue epochs reject already-
polled stale work. There is no large jitter buffer or A/V timestamp synchronization engine.

The decoder retains the 15-second startup ceiling for missing SPS/PPS, no complete IDR,
or no output. Separately, five seconds without **selected video RTP** fails the transport,
even if unrelated channel 4 traffic continues. This is measured directly in the single
network reader; no Media3 callback or speculative continuous frame watchdog is involved.

Video keepalive uses GET_PARAMETER on the same socket with one outstanding request and
monotonic CSeq. The existing helper chooses half a valid Session timeout, bounded to 1–30
seconds, with a 25-second fallback and five-second response deadline. Thus timeout=60 yields
30 seconds. RTP continues while the matching RTSP response is pending. EOF, socket/read
failure, RTP timeout, keepalive failure, and decoder/config/startup failure recover as below.

## Success, retries, and terminal errors

External states are Not connected, Connecting, Waiting for video, Playing, Reconnecting,
and Error. SPS/PPS/IDR/config details remain diagnostic logs. Playing requires successful
RTSP PLAY, decoder configuration, and an output released to the Surface. That first-output
event resets retry backoff and reports attempt startup time. Duplicate/resumed output cannot
duplicate the attempt's initial milestone. The event is a runtime milestone, not an automated
test of image correctness.

Transient failures move immediately to Reconnecting. The failed attempt closes its socket,
stops both workers, releases the codec, clears the pipeline, and reports cleanup completion.
Only then is a retry scheduled at **1, 2, 3, 5, 5… seconds**. A replacement constructs entirely
new FU-A/AU state, timestamp converter, SPS/PPS cache, queue, RTSP Session/CSeq/keepalive, and
codec, and waits for fresh config and IDR. No attempts overlap.

Unsupported hardware/API policy, missing controlled H.264 track, or unsupported packetization/
channel mapping can enter terminal Error without automatic retries. Decoder/configuration
and startup failures remain retryable because camera SDP/config can vary between sessions.
A user can explicitly retry terminal video Error; only full viewer Disconnect resets initial
audio ownership. Saving a changed endpoint applies to the next fully disconnected viewer session.

## Initial audio gate

Connect starts video first. Successful RTSP/config/first output schedules the initial audio
Connect after **250 ms**. Failure during the delay cancels its token; a later video attempt
may schedule a new one. Disconnect/lifecycle stop invalidates pending delayed callbacks.
After initial audio Connect is requested, video failures/retries do not stop or restart audio.
Audio retains its own keepalive/reconnect and mute/raw/processed controls. No PCM16LE, carry,
HPF 120 Hz, +10 dB, saturation, AudioTrack, or PCM buffer policy changed.

## Disconnect, lifecycle, and Surface

Disconnect clears video intent and retry tickets/generation, stops the socket/queue/workers,
stops independent audio, cancels the audio delay, and clears keep-screen-on. Socket close
interrupts reads and writes during connect/DESCRIBE/SETUP/PLAY or streaming. The worker makes
best-effort TEARDOWN if its connection is still usable on exit; cancellation prioritizes
immediate close. Stale timer, failure, output, and cleanup callbacks cannot create a zombie
attempt or affect a newer viewer session.

Surface destruction cancels the attempt and waits for its decoder thread to finish native
release before returning the borrowed Surface to Android. New attempts require an available
Surface and completed prior cleanup. If connect intent remains, surface recreation schedules
a fresh retry. Temporarily unavailable surfaces pause retry scheduling without advancing
backoff or starting headless decoding. Activity stop clears intent and retries and awaits
decoder release; return does not automatically reconnect. No background or PiP mode exists.

The single 16:9 production SurfaceView replaces PlayerView. ATV-03-UI-01 now sizes it to the
available viewer area above a compact control bar; see `docs/10_TV_UI.md` for same-Activity
settings navigation, which leaves the Surface attached and playback running.
Separate POC start/stop and Media3 buffering controls are removed. Normal Connect/Disconnect,
configuration, audio controls, and the explicitly triggered H.264 probe remain. The probe
requires a fully disconnected viewer and does not participate in normal playback.

`FLAG_KEEP_SCREEN_ON` follows Connecting/Waiting for video/Playing/Reconnecting, including
retry delays and temporary surface loss. It clears for Disconnect, terminal Error, and
Activity stop. The Surface is hidden after disconnected/terminal cleanup to clear stale output.

## Validation boundary

Unit tests exercise the production coordinator with controlled workers/timers, failure and
Disconnect races, surface availability, first-output gating, audio independence, retry/reset,
and the retained H.264/queue parsers. They do not emulate MediaCodec rendering. Required build
validation is `./gradlew test lint assembleDebug` and the actual push-triggered GitHub Actions run.

Physical normal-Connect checks still required: correct picture plus audio, startup speed,
live latency, Disconnect/reconnect, camera/network interruption, multi-minute stability, and
longer progressive-lag behavior. ATV-VID-01 is not physically complete until those are confirmed.
