# ATV-03-UI-01 — Private-use TV viewer

## Main Viewer

The existing production SurfaceView fits the largest centered 16:9 rectangle above an
80 dp control bar. It is neither cropped nor stretched. The viewer has no vertical scroll
or developer counters. Its compact state reads Not connected, Connecting, Waiting for video,
Live, Reconnecting, or Error. Detailed errors and startup timing live in Settings.

With a saved valid endpoint, launch offers explicit Connect (no auto-connect). Without one,
Connect is disabled and the state asks the user to configure the camera in Settings.
Active sessions offer Disconnect and Mute/Unmute. A terminal video error can also offer
explicit retry while retaining full viewer Disconnect. Probe activity temporarily blocks Connect.

## Settings / Diagnostics

Settings contains endpoint editing/Save, Raw/Processed audio selection, selected decoder,
video startup/errors, audio state/errors and counters, platform diagnostics, and the H.264
probe. Endpoint normalization and SharedPreferences storage are unchanged. Saving while
connected applies to the next fully disconnected viewer session. Processing can be chosen
before connecting or changed during playback using the existing controller toggle.

Configuration, probe results, and media diagnostics have separate text fields so ongoing
playback events no longer overwrite a Save result. Probe restrictions are unchanged: it
requires a saved valid endpoint, no viewer intent or video cleanup, and stopped audio.
Pressing the probe while connected explains that Disconnect is required.

## Remote navigation and lifecycle

- Main bar: Connect/Disconnect, Mute/Unmute when enabled, then Settings, left to right.
  Focus skips hidden/disabled controls and transfers when connection state replaces a button.
- Settings opens with Save focused to avoid automatically opening the keyboard. Up reaches
  the endpoint; Down proceeds through Save, audio processing, and the probe. Continued Down
  scrolls through diagnostics toward the Back button. Diagnostic TextViews are not selectable
  or focusable. Android's ScrollView provides normal remote scrolling.
- IME Done/Enter and Save normalize/persist, close the keyboard, clear endpoint focus, and
  focus Save, retaining the previous TV input completion fix.
- The Back button or remote Android Back returns to the viewer and focuses an available
  connection control (or Settings). While the IME is open Android may dismiss it first.
  Back from the viewer retains normal Activity behavior.

API 33+ registers the platform [OnBackInvokedCallback](https://developer.android.com/reference/android/window/OnBackInvokedDispatcher)
only while Settings is open; earlier versions retain Activity.onBackPressed for the TV remote.
No navigation library or second Activity is added.

Both modes remain inside MainActivity because opening a separate Activity would trigger
the existing onStop media release. The opaque Settings sibling covers the viewer; only its
control bar is hidden and its descendant focus is blocked. The viewer container and Surface
are never hidden/reparented by this navigation. Opening/closing Settings neither disconnects
nor recreates video/audio. Active-session keep-screen-on remains governed by the existing
ViewerKeepScreenOnPolicy. Leaving the Activity still releases playback as before.

No media architecture, RTP/H.264/MediaCodec behavior, audio DSP, retry policy, audio startup
gate (first output + 250 ms), or PiP behavior changes in this task. Unit tests cover control
availability/probe exclusion and 16:9 fit calculations; existing media tests remain required.

**Fire TV UI/D-pad physical validation is still pending.** Check the new layout, readable
controls, focus order, diagnostics scrolling, endpoint IME completion, Back behavior,
Settings during playback, and return to the viewer with the actual Fire TV remote.
