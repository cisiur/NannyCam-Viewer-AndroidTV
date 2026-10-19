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

## Source of camera knowledge

The reverse-engineering facts in this repository came from the completed Windows NannyCam Viewer investigation and physical tests against the same camera.

The authoritative handoff is:

`docs/02_CAMERA_REVERSE_ENGINEERING.md`
