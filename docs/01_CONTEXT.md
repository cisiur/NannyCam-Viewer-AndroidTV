# Project Context

## Product

NannyCam Viewer Android TV is a small personal-use TV application for direct local viewing of a TrueLife NannyCam R3 Smart.

It is a separate project from the existing Windows viewer.

The Windows project proved the camera protocol and media behavior on physical hardware. This project starts cleanly for Android TV / Fire OS and reuses only those verified findings.

## Scope

The first useful TV version needs:

1. live camera video;
2. live camera audio;
3. low and stable latency;
4. automatic recovery after connection loss;
5. operation using a normal TV remote;
6. optional system Picture-in-Picture where supported.

The first version does **not** need:

- ONVIF PTZ;
- snapshots;
- recording;
- motion detection;
- cloud services;
- Tuya integration;
- user accounts;
- store publication.

## Main user workflow

Normal mode:

1. launch the TV application;
2. connect to the configured camera;
3. display live video full-screen;
4. hear live camera audio;
5. leave the viewer running as a simple monitor.

Desired PiP workflow:

1. camera is already playing;
2. enter system Picture-in-Picture;
3. open another TV application such as YouTube or live television;
4. keep the camera visible in a corner;
5. keep camera video live;
6. hear camera audio together with foreground-app audio **if the target OS/device permits compliant simultaneous playback**.

Simultaneous cross-app audio is a desired behavior, not a guaranteed Android/Fire OS capability.

## Architecture principles

### Native TV project

Do not port WPF, FFmpeg.AutoGen, NAudio, Windows packaging, or other Windows-specific implementation code.

Prefer a native Android/Kotlin architecture unless a concrete technical spike demonstrates a better option.

### Freshness over completeness

This is a live monitor.

A stale frame is less valuable than a dropped frame.

The implementation must avoid:

- unbounded decoded-frame queues;
- unbounded audio queues;
- progressive latency accumulation;
- network/decode work on the UI thread.

### Audio and video independence

The camera audio path is unusual and may require a separate transport/demux path.

Therefore:

- video must remain live if audio fails;
- audio restart must not require restarting working video unless the chosen transport technically requires it;
- PiP video must survive loss of camera audio focus.

### Physical validation

Desktop/emulator success is not sufficient for media milestones.

Relevant milestones are only complete after validation on the intended physical Android TV / Fire TV device.

## Target compatibility philosophy

Aim for one codebase for Android TV and Fire OS where APIs permit.

Do not make Google Play Services a requirement for the core viewer.

Device capabilities must be detected rather than assumed, particularly:

- system Picture-in-Picture;
- codec/hardware decoder support;
- Android/Fire OS API level;
- audio-focus behavior;
- background/PiP lifecycle behavior.

## Physically verified Fire TV baseline

The following facts were collected from the primary physical target via ADB. They are a
tested-device baseline, not application requirements and must never be hard-coded into
product logic.

| Property | Physically verified value |
| --- | --- |
| Manufacturer / model | Amazon / `AFTKRT` |
| Device / product codename | `karat` |
| SoC / platform | `mt8696` |
| Fire OS / Android base | Fire OS `8.1.8.5` / Android 11 |
| API level | 30 |
| App ABIs | `armeabi-v7a`, `armeabi` |
| TV features | `android.hardware.type.television`, `android.software.leanback`, `android.software.leanback_only` |
| PiP feature | `android.software.picture_in_picture` |
| Physical display / Android logical override | 3840x2160 / 1920x1080 |
| Density | 320 dpi |
| H.264 decoder | `OMX.MTK.VIDEO.DECODER.AVC` |
| Decoder classification | vendor, not software-only, hardware-accelerated |
| Known video capability | H.264 Main; 1920x1080 at approximately 12 fps is comfortably within the reported decoder performance |

ATV-00 displays the corresponding runtime-derived manufacturer, model, API level, ABI,
PiP-feature and AVC-decoder diagnostics using Android APIs (`Build`, `PackageManager`,
and `MediaCodecList`). The values above are the expected physical-device comparison
point only; runtime diagnostics are the source of truth on any installed device.

### Audio-focus status

No camera audio path, audio-focus request, or PiP entry exists in ATV-00. The target's
audio-focus behavior with another application is intentionally deferred until real camera
audio exists. It must not be considered physically completed from this baseline.

## Current production media architecture

ATV-VID-01 promotes the physically validated custom video POC into normal Connect:
RTSP/TCP → H.264 RTP reconstruction → bounded access units → hardware MediaCodec → SurfaceView.
Audio retains its independent RTSP/TCP → actual PCM16LE → DSP → AudioTrack path.
The production controller, reconnect/lifecycle policy, and initial audio gate are documented
in `docs/09_CUSTOM_VIDEO_PRODUCTION.md`. Normal-Connect physical validation remains pending.
Media3 is no longer a runtime dependency; `docs/05_ATV01_MEDIA3_VIDEO.md` preserves the
historical SDP, startup-stall, and green-output findings explaining the migration.

## Source of camera knowledge

The reverse-engineering facts in this repository came from the completed Windows NannyCam Viewer investigation and physical tests against the same camera.

The authoritative handoff is:

`docs/02_CAMERA_REVERSE_ENGINEERING.md`
