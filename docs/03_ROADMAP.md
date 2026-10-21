# Roadmap

## Status legend

- [ ] planned
- [~] implemented but awaiting physical-device validation
- [x] complete

## ATV-00 — Platform baseline and scaffold

Goal: establish a standalone native TV application and identify the real capabilities of the target Android TV / Fire TV device before making media-stack assumptions.

- [x] identify the primary physical target device
- [x] record Fire OS / Android version and API level
- [x] record CPU ABI
- [ ] verify ADB debugging and APK sideload workflow with this application
- [x] verify available H.264 hardware decoder capabilities
- [x] verify whether `FEATURE_PICTURE_IN_PICTURE` is exposed
- [ ] record observed platform behavior when another app owns audio focus (deferred until a real audio path exists)
- [~] create native Kotlin/Android project
- [~] configure TV launcher activity
- [~] support D-pad navigation from the start
- [x] avoid mandatory Google Play Services
- [~] add configurable camera host / RTSP URI
- [~] add basic CI for the Android project
- [~] complete endpoint save/IME focus handoff for TV remote use

Exit: minimal APK builds, installs, launches on the physical TV device, and can be operated with its remote.

ATV-00 is not complete until the APK is installed and launched on the physical Fire TV,
launcher visibility and remote operation are verified, and saved configuration is checked
after relaunch. The project has no audio path yet, so audio-focus behavior remains deferred.

## ATV-01 — Low-latency live video

Goal: show the physically verified `streamtype=0` H.264 stream with stable live latency.

Requirements:

- RTSP over TCP;
- target physical stream is H.264 1920x1080 at approximately 12 fps;
- freshness over completeness;
- no unbounded video queue;
- stale frames may be dropped;
- network/decode work off the main/UI thread.

Plan:

- [x] spike Android Media3/ExoPlayer RTSP for **video only**
- [x] verify the physical camera rather than relying on emulator/sample streams
- [x] verify the selected hardware H.264 decoder and expose selected-decoder diagnostics
- [x] record physical Connect-to-first-frame startup timing
- [ ] measure latency after extended playback
- [ ] confirm no progressive lag
- [~] implement explicit Disconnect/resource release
- [~] add reconnect behavior after basic stream validation
- [ ] establish a reliable frozen-video liveness signal (the false-positive custom watchdog was removed)
- [ ] physically verify reconnect after actual camera or network loss
- [ ] if Media3 is unsuitable, replace only the minimum required RTSP/demux layer

Do not make the malformed audio track a reason to discard a working hardware video path.

Exit: physical TV device displays stable live video with no progressive delay.

Physical validation has confirmed Media3 RTSP/TCP playback from the primary camera,
selection of `OMX.MTK.VIDEO.DECODER.AVC`, and approximately 3.3–4.7 second startup
timing. It also observed approximately one second live-view delay. ATV-01 remains
incomplete: extended playback, progressive-lag testing, a reliable liveness signal, and
recovery after actual camera/network loss are still pending physical validation.

## ATV-02 — Correct camera audio

Goal: play the camera's real PCM16 LE audio despite the incorrect PCMA SDP declaration.

- [ ] obtain raw payload bytes before PCMA decoding
- [ ] reinterpret payload as PCM16 LE 8 kHz mono
- [ ] implement one-byte packet carry
- [ ] add 120 Hz first-order high-pass filter
- [ ] add fixed +10 dB gain with saturation
- [ ] play through an appropriate Android audio output path
- [ ] keep the audio buffer bounded
- [ ] recover toward live by dropping stale audio when needed
- [ ] keep audio lifecycle independent from video
- [ ] add mute/unmute
- [ ] physically validate speech clarity
- [ ] physically validate long-running audio latency
- [ ] physically validate audio recovery after connection loss

Likely architecture decision:

If Media3 gives good video but cannot expose this malformed raw audio payload correctly, retain Media3 for video and implement a separate lightweight RTSP/RTP audio path.

Exit: clear live camera audio matching the previously validated PCM16 interpretation.

## ATV-03 — TV UX and connection resilience

Goal: make the application usable as a simple TV monitor without keyboard or mouse.

- [ ] full-screen live camera view
- [ ] D-pad focus/navigation
- [ ] minimal remote-friendly controls
- [ ] connecting state
- [ ] reconnecting state
- [ ] disconnected/error state
- [ ] automatic reconnect with cancellable bounded attempts
- [ ] explicit Disconnect stops reconnect intent
- [ ] mute/unmute available from remote
- [ ] handle Activity pause/resume/stop appropriately
- [ ] test TV sleep/wake
- [ ] test app switch and return
- [ ] test network/camera power loss and recovery
- [ ] test close/relaunch

Exit: normal full-screen viewer is reliable on the physical TV device.

## ATV-04 — System Picture-in-Picture

Goal: keep the camera visible in a system-managed corner while another TV application is foregrounded.

- [ ] gate the feature on actual platform PiP capability
- [ ] use system Android Picture-in-Picture APIs
- [ ] do not use `SYSTEM_ALERT_WINDOW`
- [ ] hide unnecessary viewer UI in PiP
- [ ] preserve live camera video in PiP
- [ ] avoid unnecessary stream recreation on PiP transitions
- [ ] preserve reconnect behavior where lifecycle rules allow
- [ ] add useful PiP action(s), especially mute/unmute if practical
- [ ] test PiP over launcher
- [ ] test PiP over YouTube
- [ ] test PiP over the intended television application
- [ ] document unsupported device/OS combinations

Exit: on supported hardware, the camera remains visible while another application occupies the main TV surface.

## ATV-05 — PiP audio coexistence

Goal: determine and implement the best platform-compliant audio behavior while NannyCam is in PiP.

Desired behavior:

- foreground app audio remains audible;
- camera audio also remains audible.

This is not guaranteed by Android/Fire OS.

- [ ] implement compliant AudioManager/audio-focus handling
- [ ] verify audio-focus requests/results on the physical target
- [ ] test PiP camera audio + YouTube
- [ ] test PiP camera audio + intended live-TV app
- [ ] test mute/unmute during PiP
- [ ] record whether the device mixes, ducks, pauses, or removes camera audio
- [ ] ensure camera audio-focus loss never destroys PiP video
- [ ] if simultaneous audio is allowed naturally, preserve it
- [ ] if simultaneous audio is not allowed, follow platform focus requirements and keep video live
- [ ] document target-device behavior

Do not bypass audio focus or fight the operating system.

Exit: PiP audio behavior is physically characterized, compliant, and predictable on the target device.

## ATV-06 — Personal-use release package

Goal: produce a reproducible sideloadable build.

- [ ] release APK for target API/ABI
- [ ] app icon
- [ ] Android TV / Fire TV banner as needed
- [ ] no developer-machine paths
- [ ] no secrets or camera-specific credentials embedded unnecessarily
- [ ] clean-install instructions
- [ ] update-install instructions
- [ ] final validation from release APK
- [ ] document tested device and OS version

Store publication is not required.

## Later / optional

- PTZ
- snapshots
- recording
- motion indication
- sound-level indication
- camera discovery
- settings persistence beyond minimum useful preferences
- additional Android TV / Fire TV compatibility targets
- store distribution
