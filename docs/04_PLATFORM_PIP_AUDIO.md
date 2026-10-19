# Platform Constraints — Picture-in-Picture and Audio

## Why this document exists

The desired TV experience is:

- NannyCam video in a small corner window;
- another application such as YouTube or television full-screen;
- audio from both applications when possible.

Video PiP and cross-application audio are separate platform concerns.

## System Picture-in-Picture

Use Android's system Picture-in-Picture API.

Do not build a custom floating overlay using `SYSTEM_ALERT_WINDOW`.

At runtime, detect PiP support with the platform capability rather than assuming all TV devices support it.

Android's official PiP guidance explicitly recommends checking:

`PackageManager.FEATURE_PICTURE_IN_PICTURE`

The system can disable PiP on some devices, including constrained/low-RAM hardware.

In PiP, the application should show primarily the video surface and remove unnecessary controls.

## Android audio interaction in PiP

Android's official PiP guidance warns that PiP video playback can interfere with audio from another application.

Applications are expected to use Android audio focus and react to focus loss.

Therefore the TV viewer cannot assume that entering PiP grants the right to keep playing camera audio while another foreground application plays its own audio.

## Fire TV audio focus

Amazon's Fire TV requirements are stricter.

For Fire TV multimedia applications:

- request audio focus immediately before playback;
- verify that focus was granted;
- handle focus-change callbacks;
- stop/release audio appropriately on permanent focus loss;
- abandon audio focus when playback ends.

Amazon explicitly describes audio focus as the mechanism used to coordinate multiple multimedia applications.

This means simultaneous NannyCam + YouTube/TV audio is **device/application behavior to test**, not a guaranteed feature.

## Product policy

The project should aim for simultaneous audio, but remain platform-compliant.

Rules:

1. Never bypass or falsify audio focus.
2. Never let loss of audio focus kill the camera video pipeline.
3. Camera audio must be independently muteable.
4. If the physical target permits simultaneous playback, keep it.
5. If the system requires camera audio to stop/duck, obey it and keep PiP video running.
6. Record behavior per tested device/OS.

## Required physical tests

For each primary target device:

- app full-screen + camera audio;
- enter PiP;
- PiP over launcher;
- PiP + YouTube with YouTube audio playing;
- PiP + intended live-TV application;
- camera mute/unmute while PiP is active;
- voice/Alexa interruption on Fire TV;
- return from foreground app to NannyCam;
- reconnect while PiP is active where supported.

Record:

- device model;
- Fire OS / Android version;
- API level;
- PiP capability;
- foreground app tested;
- whether both audio sources were audible;
- focus events observed;
- whether camera audio stopped, paused, ducked, or resumed.
