# Camera Reverse-Engineering Handoff

## Purpose

This document contains the camera facts already established through physical reverse engineering.

The Android TV / Fire OS project should **not** repeat this investigation unless a new physical unit behaves differently.

## Camera

Device:

- TrueLife NannyCam R3 Smart.

Known test-network identity during reverse engineering:

- camera IP: `192.168.50.207`;
- MAC: `90-31-4b-60-66-92`;
- RTSP port: `554`;
- ONVIF/gSOAP port: `8899`.

The IP address is test-environment information only. Never hard-code it as a product assumption.

The TV application must allow the camera host / RTSP URI to be configured.

## RTSP

Preferred physically validated stream:

`rtsp://<camera-host>:554/streamtype=0`

Equivalent form used during tests:

`rtsp://192.168.50.207:554/streamtype=0`

Use RTSP over TCP.

Another camera profile exists:

`rtsp://<camera-host>:554/streamtype=1`

The high-quality `streamtype=0` stream is the initial target.

### Invalid/misleading paths

During probing, paths such as:

- `/live`
- `/live0`
- `/live1`

could return RTSP success but did not provide the required video media.

Do not treat RTSP 200 alone as proof of a usable stream.

## Video

The camera's ONVIF metadata is misleading.

ONVIF profile 000 reported approximately:

- 640x360;
- 12 fps;
- 1024 kbps;
- H.264 Baseline.

The actual physical `streamtype=0` RTSP video observed during playback was:

- H.264;
- Main profile;
- 1920x1080;
- approximately 12 fps.

Implementation should derive actual codec parameters from the stream rather than assuming the ONVIF values are authoritative.

## SDP audio declaration

The RTSP SDP advertises the audio track as:

- payload type 8;
- PCMA / G.711 A-law;
- 8000 Hz;
- mono;
- nominally 64 kbps.

**This declaration is wrong for the tested camera.**

## Actual audio payload

Physical packet-copy analysis proved that the payload carried in the track advertised as PCMA is actually:

- signed PCM16;
- little-endian;
- 8000 Hz;
- mono;
- effectively 128 kbps.

### Evidence

A controlled 10-second RTSP audio copy produced:

- exactly 160,000 payload bytes.

Interpreting this as A-law gives:

- 160,000 8-bit A-law samples over 10 seconds;
- severe crackling / incorrect playback.

Interpreting the same bytes as little-endian 16-bit PCM gives:

- 80,000 samples over 10 seconds;
- exactly 8,000 samples per second;
- clear, understandable physical-camera audio.

Additional observations:

- mu-law interpretation was also wrong;
- big-endian PCM was implausible/noisy;
- PCM16 LE playback was physically confirmed as correct.

Therefore:

> Do not pass this track to a stock PCMA decoder and expect correct sound.

The media implementation must gain access to the raw audio payload and reinterpret it as PCM16 LE 8 kHz mono.

## Packet alignment

PCM16 samples require two bytes.

RTSP/RTP payload packet boundaries must not be assumed to align perfectly to sample boundaries.

Maintain a bounded one-byte carry:

- if a payload ends with one unmatched byte, retain it;
- prepend it to the next payload;
- process complete little-endian 16-bit samples only.

No growing carry/buffer is needed.

## Audio cleanup

The physically preferred cleanup from the Windows project is deliberately minimal.

Apply in this order:

1. first-order high-pass filter at 120 Hz;
2. fixed +10 dB gain;
3. PCM16 saturation/clamping.

Reason:

- the camera is naturally quiet;
- the +10 dB gain materially improves audibility;
- the HPF removes unnecessary low-frequency content;
- heavier processing was intentionally avoided.

Do not introduce by default:

- noise gate;
- FFT/spectral denoise;
- large-window denoise;
- AGC;
- compressor;
- artificial delay/buffering for processing.

Quiet monitoring sounds should not be suppressed by a gate.

If Android output hardware requires a different device sample rate, resampling for output compatibility is acceptable. Keep that conversion conceptually separate from the camera payload interpretation.

## Proven low-latency behavior

Desktop testing established that video can remain effectively live for extended playback without progressive latency when stale media is not allowed to accumulate.

Important policy:

- RTSP over TCP;
- bounded queues;
- latest/fresh frame preferred over backlog;
- stale video may be dropped;
- stale audio may be dropped;
- audio and video must not create backpressure on one another.

## Audio buffering reference

The Windows implementation used a small bounded live-audio buffer:

- approximately 200 ms maximum live PCM buffering;
- stale-audio recovery threshold around 100 ms.

The Android implementation does not have to duplicate those exact internal APIs, but should preserve the same intent:

- absorb small jitter;
- do not accumulate seconds of audio;
- recover toward live when stale.

## Connection resilience reference

The physically validated Windows behavior used:

- persistent Connect intent until Disconnect/window close;
- retry delays: 1 s, 2 s, 3 s, then repeated 5 s;
- a rolling 5-second video liveness deadline;
- disposal of a failed attempt before replacement;
- no overlapping reconnect attempts;
- explicit Disconnect cancels retry.

The Android implementation may use different platform primitives but should preserve equivalent semantics.

## Independence of audio and video

This is important for Android because the malformed audio track may force a custom path even if a stock Android RTSP player handles H.264 video successfully.

Preferred strategy:

- use the simplest reliable Android path for video;
- use an independent audio extraction path if necessary;
- do not abandon a good hardware-decoded video path merely to force both tracks through one abstraction.

## ONVIF information

The camera also exposes ONVIF:

- Device: `http://<camera-host>:8899/onvif/device_service`
- Media: `http://<camera-host>:8899/onvif/Media`
- PTZ: `http://<camera-host>:8899/onvif/PTZ`

Known local ONVIF credential from reverse engineering:

- username: `admin`;
- password: empty.

PTZ was physically validated in the Windows viewer.

This TV project does not need PTZ for its initial scope.

## Tuya

The camera exposes a Tuya service, but Tuya is not required for local RTSP viewing.

Known reverse-engineering metadata included:

- Tuya protocol family: 3.3;
- Product ID: `jolh7zwcg73uhofa`.

The local key was not required for RTSP/ONVIF viewing.

Do not add a Tuya dependency to the TV viewer without a new product requirement.

## Reference manual tests from the investigation

Known-good low-latency desktop video reference used FFmpeg/ffplay with:

`-rtsp_transport tcp`

The important conclusion is not the desktop command itself; it is that direct RTSP/TCP playback of `streamtype=0` is viable and can remain live without progressive lag.

Known-good audio interpretation was direct playback of copied raw bytes as:

- format: signed 16-bit little-endian;
- sample rate: 8000 Hz;
- channels: mono.

This produced clear understandable speech on the physical camera.
