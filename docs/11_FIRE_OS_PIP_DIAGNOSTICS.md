# Fire OS 8 system PiP diagnostics

## Purpose and target

`ATV-04-DIAG-01` is a minimal feasibility probe for system-managed Android
Picture-in-Picture (PiP), not the full ATV-04 experience. The target is the Amazon Fire TV
Stick 4K, model `AFTKRT` / codename `karat`, running Fire OS 8 (Android 11 / API 30).

The device was queried through ADB and declared:

```text
feature:android.software.picture_in_picture
supportsEnterPipOnTaskSwitch: true
PinnedStackController
    pinned:
```

Those facts justify the test. They do not prove that Fire OS will present an arbitrary
third-party app above the launcher, YouTube, or another foreground app.

## What the probe changes

- `MainActivity` declares `android:supportsPictureInPicture="true"`. Its existing landscape
  and relevant `configChanges` handling (`orientation`, `screenLayout`, `screenSize`, and
  `smallestScreenSize`) remain intact.
- The Viewer has a remote-focusable **Test PiP** button. It is enabled only when the platform
  advertises `PackageManager.FEATURE_PICTURE_IN_PICTURE`, the Viewer (not Settings) is shown,
  the current video state is `PLAYING`, the production surface is valid, and no request is
  pending.
- Pressing it requests `PictureInPictureParams` with `Rational(16, 9)` through standard Android
  `enterPictureInPictureMode(params)`. The Boolean response is logged but is explicitly not a
  success verdict.
- PiP presentation removes viewer controls and all settings/diagnostic chrome while leaving the
  existing video container and its `SurfaceView` attached. The same production MediaCodec,
  decoder surface, RTSP sessions, bounded video queue, and AudioTrack path are reused; none is
  recreated for PiP.

No overlay permission, accessibility service, root integration, Media3/ExoPlayer, FFmpeg,
alternative decoder, RTSP change, reconnect tuning, audio-focus policy, or audio DSP/buffering
change is part of this probe.

## Lifecycle policy

Normal `onStop` still releases video and audio using the established cleanup path. A confirmed
platform PiP state deliberately preserves the existing session. The only exception is a short
750 ms confirmation window after a successful PiP API request: if `onStop` arrives before the
platform reports PiP, cleanup waits briefly and then checks
`Activity.isInPictureInPictureMode` again. If PiP is still not confirmed, normal cleanup runs.

The activity does not keep its own permanent “in PiP” flag. `isInPictureInPictureMode` is the
authoritative platform state; the temporary request marker only prevents duplicate entry and
supports the transition window. Returning to full screen restores viewer controls without
reconnecting. PiP dismissal reaches the non-PiP `onStop` cleanup path (and `onDestroy` also
forces cleanup), so no invisible camera connection should remain indefinitely.

## Logs

The consistent diagnostic tag is `NannyCamPiP`. It records:

- PiP request, feature result, playback state, surface validity, API Boolean, and exceptions;
- `onCreate`, `onStart`, `onResume`, `onPause`, `onStop`, `onDestroy`, and
  `onPictureInPictureModeChanged` with platform PiP state, video state, surface validity, and
  cleanup decision;
- whether lifecycle cleanup ran, was deferred for an unresolved request, or was preserved for
  confirmed PiP.

Retrieve the evidence after each physical run with:

```powershell
C:\platform-tools\adb.exe logcat -d -v time | findstr /I "NannyCamPiP"
```

## Physical validation procedure

1. Connect to the camera and wait until Viewer status is **Live**. Verify **Test PiP** is
   enabled and can be selected using only the Fire TV remote.
2. Press **Test PiP** and save the `NannyCamPiP` log output. Record whether Android accepts the
   request and whether a small window is actually shown.
3. Inspect the PiP window for live video continuity, decoder/RTSP reconnection, and camera-audio
   behavior. This task intentionally does not alter audio focus or attempt to force mixing.
4. Open the Fire TV launcher, then YouTube, and record whether the camera window remains visible
   above each foreground surface.
5. Return to full screen. Verify controls return and the existing session continues without an
   intentional reconnect.
6. Dismiss PiP. Confirm the logs show normal cleanup and that no camera video/audio continues in
   the background.

The required observations are: entry success; appearance of the small camera window; video
continuity; launcher and YouTube visibility; any decoder/RTSP reconnect; camera-audio behavior;
return-to-full-screen behavior; and complete cleanup after dismissal.

## Interpretation

Do not treat manifest support, the feature flag, a pinned-task service, a successful API Boolean,
or JVM tests as proof of system PiP feasibility. Only physical Fire TV results can establish
whether this app remains visibly rendered over the desired foreground applications. ATV-04 must
remain incomplete until those results are reported.
