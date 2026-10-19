# NannyCam Viewer Android TV

A standalone Android TV / Fire OS viewer for the TrueLife NannyCam R3 Smart.

This repository is intentionally independent from the Windows NannyCam Viewer project. It does not reuse the Windows WPF/.NET implementation. It carries forward only the physically verified camera/protocol knowledge required to obtain correct low-latency live video and audio.

## Primary goals

- low-latency live H.264 video from the local camera;
- correct playback of the camera's non-standard audio payload;
- TV-remote-friendly UI;
- reliable reconnect behavior;
- optional system Picture-in-Picture on supported Android TV / Fire TV devices;
- preserve live video even if camera audio must yield to another application's audio focus.

PTZ is not part of the first TV version.

## Camera stream baseline

Preferred stream:

`rtsp://<camera-host>:554/streamtype=0`

Transport:

- RTSP over TCP.

Physically observed media:

- H.264 Main profile;
- 1920x1080;
- approximately 12 fps.

Critical audio quirk:

- SDP advertises PCMA / G.711 A-law, 8 kHz mono;
- the physical payload is actually signed PCM16 little-endian, 8 kHz mono;
- treating it as A-law causes severe crackling.

See [docs/02_CAMERA_REVERSE_ENGINEERING.md](docs/02_CAMERA_REVERSE_ENGINEERING.md) before implementing any media path.

## Documentation

- [Project context](docs/01_CONTEXT.md)
- [Camera reverse-engineering handoff](docs/02_CAMERA_REVERSE_ENGINEERING.md)
- [Roadmap](docs/03_ROADMAP.md)
- [Platform constraints: PiP and audio focus](docs/04_PLATFORM_PIP_AUDIO.md)

## Intended implementation direction

Initial direction:

- native Android/Kotlin application;
- one codebase where practical for Android TV and Fire OS;
- no mandatory Google Play Services in the playback core;
- platform-native hardware H.264 decoding preferred;
- custom handling only where the camera's malformed audio metadata makes stock playback incorrect.

The exact media stack is an ATV-00/ATV-01 decision and must be validated on the physical target device before being treated as final.
