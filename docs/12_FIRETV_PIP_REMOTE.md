# Netflix remote button → NannyCam PiP (Fire TV, native shell helper)

## Verified environment and scope

- Amazon Fire TV Stick 4K, model AFTKRT / karat, Fire OS 8 / Android 11 (API 30), ABI armeabi-v7a.
- NannyCam Android application package: com.cisiur.nannycamviewer.
- An external **native C helper**, `pip_remote`, runs under the **ADB shell identity (uid 2000)**, not inside the Android app.
- Physical trials: Netflix button restored live NannyCam PiP over TiviMate channel changes and YouTube; v5 stayed alive for over 24 hours and re-detected the Bluetooth remote after sleep; Windows scheduled autostart correctly distinguished a running process and started a missing one. This is device-specific evidence, **not a guarantee across other Fire OS models/updates**.

The helper is in [`tools/firetv-pip-remote/pip_remote.c`](../tools/firetv-pip-remote/pip_remote.c), alongside build/install and Windows startup scripts.

## How it works

1. Enumerate `/dev/input/event*` for `Amazon Fire TV Remote`; identify Linux `EV_KEY` 744 (APP_1 / Netflix).
2. Prepare a `/dev/uinput` virtual device **before** grabbing the physical device with `EVIOCGRAB`. Copy original `EVIOCGID` bus/vendor/product/version, which preserves the Fire OS keylayout (`SETTINGS` is key 249). Forward other keys; suppress APP_1 and its frame.
3. On one Netflix press, start a child worker so the main loop continues forwarding keys.
4. Discover the current NannyCam stack using `cmd activity stack list`. If absent, do nothing — **the helper never launches Netflix or a closed NannyCam Activity**.
5. If needed, invoke `cmd activity stack move-top-activity-to-pinned-stack <stack-id> 1424 54 1808 270`. Retry at most four times; require two consecutive observations of `mWindowingMode=pinned` and `visible=true` separated by 500 ms.
6. On confirmed PiP, send the protected broadcast `com.cisiur.nannycamviewer.action.RESTORE_CAMERA` to `com.cisiur.nannycamviewer/.ui.CameraRecoveryReceiver`. This receiver uses only the saved RTSP endpoint and does not start a destroyed Activity. The broadcast is safe to send while video is already playing; the app ignores the duplicate recovery.
7. Single-instance `flock` on `/data/local/tmp/pip_remote.lock`, input re-enumeration after Bluetooth disconnect, SIGTERM cleanup, and log truncation for long-running sessions.

A successful `cmd activity` status or broadcast completion alone is **not proof** of a visible video frame. The system stack checks validate the PiP state, while the Android playback logs validate RTP/video recovery separately.

## Prerequisites

- NannyCam APK built from commit `218017896dbfb7c71d44a4dc2f46f8d89526c06d` or later, with `CameraRecoveryReceiver`.
- Windows PowerShell, authorized `C:\platform-tools\adb.exe`, Android SDK/NDK, and `ANDROID_HOME` (e.g. `D:\Android\Sdk`).
- Fire Stick ADB over LAN (example IP `192.168.50.60:5555`); use a router DHCP reservation so this address does not change.
- A supported and connected Amazon Fire TV Remote; user `shell` must have effective access to `/dev/input`, `/dev/uinput`, and `cmd activity`.
- Do **not** expose TCP 5555 to the internet or an untrusted LAN.

## Build and deploy on Windows

From the repository root:

```powershell
$env:ANDROID_HOME = 'D:\Android\Sdk'
C:\platform-tools\adb.exe connect 192.168.50.60:5555
C:\platform-tools\adb.exe -s 192.168.50.60:5555 shell pkill -TERM pip_remote
cd .\tools\firetv-pip-remote
.\build-and-install.ps1
```

`build-and-install.ps1` selects the latest installed NDK and targets Android API 23 for the detected ABI. It pushes the compiled `pip_remote` to `/data/local/tmp/pip_remote`. The **compiled binary is deliberately not committed**.

If PowerShell blocks a downloaded script, review it and use `Unblock-File` for that script.

### Bounded validation

```powershell
C:\platform-tools\adb.exe -s 192.168.50.60:5555 shell /data/local/tmp/pip_remote 90
```

Test Netflix, SETTINGS, DPAD, OK, Back, and app transitions. A 90-second test ends automatically and releases the physical remote.

### Continuous mode independent of Windows

```powershell
C:\platform-tools\adb.exe -s 192.168.50.60:5555 shell "nohup /data/local/tmp/pip_remote 0 > /data/local/tmp/pip_remote.log 2>&1 < /dev/null &"
C:\platform-tools\adb.exe -s 192.168.50.60:5555 shell pidof pip_remote
C:\platform-tools\adb.exe -s 192.168.50.60:5555 shell tail -n 40 /data/local/tmp/pip_remote.log
C:\platform-tools\adb.exe disconnect 192.168.50.60:5555
```

Argument `0` means unlimited runtime (the process can still be killed by Fire OS/reboot). Numeric durations from 1 to 604800 seconds are supported for tests. No active PC connection is required after startup.

## Headless startup at Windows user sign-in

The tested, adopted solution is **Windows Task Scheduler**, not Termux or a persistent local ADB client on the Fire Stick.

```powershell
cd .\tools\firetv-pip-remote\windows-autostart
.\Install-Autostart.ps1
Start-ScheduledTask -TaskName 'Fire TV - PiP Remote AutoStart'
Get-Content "$env:LOCALAPPDATA\FireTvPipRemote\startup.log" -Tail 20
```

The installer copies `Ensure-PipRemote.ps1` into `%LOCALAPPDATA%\FireTvPipRemote`, writes a hidden `Launch-Hidden.vbs` wrapper, and registers a logon task running in the **current Windows user's context** (needed for that user's ADB authorization). The check waits 30 seconds for networking, retries ADB up to three minutes, verifies `id -u = 2000`, and calls `pidof pip_remote`. If already running, it exits without disruption. If absent, it starts `pip_remote 0` via detached `nohup` and confirms the PID.

Example verified log lines:

```text
OK: pip_remote already running (PID: 6474). Nothing to do.
pip_remote not running. Starting continuous mode.
SUCCESS: pip_remote running (PID: 18263).
```

The task runs **after logon**, not before. It is a one-shot startup check; it is not a recurring watchdog. If Fire Stick reboots while the PC remains off or Windows stays logged in without a new task invocation, the helper remains off until the next Windows sign-in or manual invocation. If Fire Stick cannot be reached in the retry period, the task exits with a logged error.

To remove automatic checks:

```powershell
Unregister-ScheduledTask -TaskName 'Fire TV - PiP Remote AutoStart' -Confirm:$false
```

## Diagnostics and safe stop

```powershell
C:\platform-tools\adb.exe -s 192.168.50.60:5555 shell pidof pip_remote
C:\platform-tools\adb.exe -s 192.168.50.60:5555 shell tail -n 80 /data/local/tmp/pip_remote.log
C:\platform-tools\adb.exe -s 192.168.50.60:5555 logcat -d -s NannyCamRecovery:I NannyCamPiP:I VideoPlayback:I
C:\platform-tools\adb.exe -s 192.168.50.60:5555 shell pkill -TERM pip_remote
```

Do not run two independent versions: v5's lock prevents duplicate v5 instances but **older helpers may not hold the lock**. If input forwarding fails, the helper releases the EVIOCGRAB and retries. If all remote buttons stop responding, use the ADB SIGTERM command or let a bounded test expire.

## Known limitations

- Rootless **automatic start at Fire Stick boot is not implemented**; the Windows logon task is the working compromise.
- If NannyCam is closed using Back and no task exists, Netflix does nothing (and the Netflix app/store is suppressed while the helper is active). Start NannyCam and connect its camera before using restore.
- The protected receiver is exported with `android.permission.DUMP`; ordinary apps cannot trigger it.
- The PiP region `1424 54 1808 270` and `cmd activity stack` behavior are specific to tested Fire OS 8 firmware and 1920x1080 display override; firmware changes can invalidate them.
- The helper may retain shell privileges for as long as its process survives; treat the source and startup scripts as trusted local administrative tooling.
- The Windows task was successfully tested both when the process was already running and when the process was absent. Cold boot / different user / changed ADB keys / different model remain unverified.
