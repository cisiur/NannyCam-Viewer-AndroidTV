# Install once from PowerShell under the Windows user whose ADB is authorized.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$taskName = 'Fire TV - PiP Remote AutoStart'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$targetDir = Join-Path $env:LOCALAPPDATA 'FireTvPipRemote'
New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $root 'Ensure-PipRemote.ps1') -Destination (Join-Path $targetDir 'Ensure-PipRemote.ps1') -Force
$launcher = @'
Option Explicit
Dim ws, command
Set ws = CreateObject("WScript.Shell")
command = "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File """ & ws.ExpandEnvironmentStrings("%LOCALAPPDATA%") & "\FireTvPipRemote\Ensure-PipRemote.ps1" & """"
ws.Run command, 0, True
'@
Set-Content -LiteralPath (Join-Path $targetDir 'Launch-Hidden.vbs') -Value $launcher -Encoding ASCII
$action = New-ScheduledTaskAction -Execute "$env:WINDIR\System32\wscript.exe" -Argument ('"' + (Join-Path $targetDir 'Launch-Hidden.vbs') + '"')
$user = [System.Security.Principal.WindowsIdentity]::GetCurrent().Name
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Minutes 6) -StartWhenAvailable -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
Write-Host "Installed scheduled task: $taskName"
Write-Host "Runs invisibly on Windows sign-in for: $user"
Write-Host "Script: $(Join-Path $targetDir 'Ensure-PipRemote.ps1')"
Write-Host "Log: $(Join-Path $targetDir 'startup.log')"
Write-Host "To test now: Start-ScheduledTask -TaskName '$taskName'"
