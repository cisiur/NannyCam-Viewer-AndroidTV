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

## External camera recovery (ATV-04-RECOVERY-01)

When the existing NannyCam task has been restored to Fire OS PiP but its camera session was
released, a local ADB-shell helper can request one controlled reconnection:

```powershell
C:\platform-tools\adb.exe shell am broadcast -a com.cisiur.nannycamviewer.action.RESTORE_CAMERA -n com.cisiur.nannycamviewer/.ui.CameraRecoveryReceiver
```

The receiver is exported only with the platform `android.permission.DUMP` permission. That
allows the ADB shell / system tooling to invoke it while preventing ordinary installed apps from
sending the command. It accepts no endpoint argument: URI data and extras are ignored, and the
only endpoint considered is the already-saved configuration after `CameraEndpointNormalizer`
validation. Logs deliberately never print its credentials.

The command is intentionally **not** an Activity launcher and cannot bring NannyCam to the
foreground. It can only route to an existing `MainActivity` instance in the restored task:

- In the foreground or in a still-running PiP task, it requests video-first recovery using the
  current `SurfaceView`; audio starts only after the first video frame, as with normal Connect.
- If the task is stopped but remains in memory, the same instance can receive the command. If its
  surface has not returned yet, the existing controller waits for it and makes no decoder/socket
  attempt until it does.
- If the process or `MainActivity` has been destroyed, the receiver logs that no session owner is
  available and does nothing. Starting an Activity from a background broadcast is deliberately
  not attempted because Android/Fire OS background activity-launch rules can create a fullscreen
  interruption over YouTube or TiviMate. A seamless cold-process recovery would require moving
  playback ownership to a foreground Service, which is outside this narrowly-scoped change.

Recovery is idempotent. A playing, connecting, reconnecting, or probe-running session is ignored;
the existing controller owns one video attempt and one bounded retry path. User mute and
raw/processed audio selections remain on the activity's existing audio controller. An explicit
Viewer **Disconnect** suppresses subsequent recovery commands, whereas system lifecycle cleanup
does not mark a user disconnect and remains recoverable.

Use these logs while testing:

```powershell
C:\platform-tools\adb.exe logcat -c
C:\platform-tools\adb.exe logcat -v time NannyCamRecovery:I NannyCamPiP:I ViewerStartup:I VideoPlayback:I *:S
```

For physical validation, first connect normally, enter PiP, provoke the known Fire OS removal by
starting a YouTube video or changing a TiviMate channel, restore the task with `pip_remote`, then
send the broadcast (repeat once only if the platform task restoration itself requires its known
second attempt). Confirm `Recovery accepted`, surface readiness, exactly one video startup and
`Recovery first video frame`; then verify camera audio begins only after the frame. Also test a
second broadcast while live (it must be ignored), missing/invalid saved configuration (it must be
rejected), and an explicit Disconnect followed by the broadcast (it must be ignored). These are
physical Fire TV checks; successful builds and unit tests are not evidence that Fire OS has kept
or restored the PiP surface correctly.

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
