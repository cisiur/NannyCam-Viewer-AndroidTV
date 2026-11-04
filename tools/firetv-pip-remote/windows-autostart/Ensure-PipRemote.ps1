# Fire TV pip_remote watchdog. Runs under the Windows user with authorized ADB keys.
[CmdletBinding()]
param(
    [string]$Adb = 'C:\platform-tools\adb.exe',
    [string]$Device = '192.168.50.60:5555',
    [int]$StartupDelaySeconds = 30,
    [int]$RetryForSeconds = 180
)
$ErrorActionPreference = 'Stop'
$workDir = Join-Path $env:LOCALAPPDATA 'FireTvPipRemote'
New-Item -ItemType Directory -Path $workDir -Force | Out-Null
$logPath = Join-Path $workDir 'startup.log'
function Write-Log([string]$message) {
    $time = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
    Add-Content -LiteralPath $logPath -Value "[$time] $message" -Encoding UTF8
}
if ((Test-Path -LiteralPath $logPath) -and (Get-Item -LiteralPath $logPath).Length -gt 512KB) {
    $lines = @(Get-Content -LiteralPath $logPath -Tail 200)
    Set-Content -LiteralPath $logPath -Value $lines -Encoding UTF8
}
function Invoke-Adb([string[]]$AdbArgs, [int]$TimeoutSeconds = 15) {
    $startInfo = New-Object System.Diagnostics.ProcessStartInfo
    $startInfo.FileName = $Adb
    $startInfo.Arguments = ($AdbArgs -join ' ')
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $proc = New-Object System.Diagnostics.Process
    $proc.StartInfo = $startInfo
    try {
        [void]$proc.Start()
        if (-not $proc.WaitForExit($TimeoutSeconds * 1000)) {
            try { $proc.Kill() } catch { }
            try { [void]$proc.WaitForExit(2000) } catch { }
            return @{ ExitCode = -1; Output = 'ADB command timed out' }
        }
        $out = $proc.StandardOutput.ReadToEnd()
        $err = $proc.StandardError.ReadToEnd()
        return @{ ExitCode = $proc.ExitCode; Output = (($out + "`n" + $err).Trim()) }
    } catch {
        return @{ ExitCode = -2; Output = $_.Exception.Message }
    } finally {
        $proc.Dispose()
    }
}
function Check-Pid {
    $r = Invoke-Adb @('-s', $Device, 'shell', 'pidof', 'pip_remote')
    if ($r.ExitCode -eq 0 -and $r.Output -match '^\s*\d+(\s+\d+)*\s*$') {
        return $r.Output.Trim()
    }
    return $null
}
try {
    Write-Log 'Startup check initiated.'
    if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) {
        Write-Log "ERROR: adb.exe not found: $Adb"
        exit 2
    }
    if ($StartupDelaySeconds -gt 0) { Start-Sleep -Seconds $StartupDelaySeconds }
    $deadline = (Get-Date).AddSeconds($RetryForSeconds)
    do {
        $connect = Invoke-Adb @('connect', $Device) 15
        $check = Invoke-Adb @('-s', $Device, 'shell', 'id', '-u') 15
        if ($check.ExitCode -eq 0 -and $check.Output.Trim() -eq '2000') { break }
        $detail = ($connect.Output + ' / ' + $check.Output) -replace '[\r\n]+', ' '
        Write-Log "Waiting for Fire Stick/ADB authorization: $detail"
        Start-Sleep -Seconds 10
    } while ((Get-Date) -lt $deadline)
    if ($check.ExitCode -ne 0 -or $check.Output.Trim() -ne '2000') {
        Write-Log 'ERROR: Fire Stick unavailable or ADB unauthorized. No start attempted.'
        exit 3
    }
    $runningPid = Check-Pid
    if ($runningPid) {
        Write-Log "OK: pip_remote already running (PID: $runningPid). Nothing to do."
        exit 0
    }
    $binary = Invoke-Adb @('-s', $Device, 'shell', 'test', '-x', '/data/local/tmp/pip_remote')
    if ($binary.ExitCode -ne 0) {
        Write-Log 'ERROR: /data/local/tmp/pip_remote missing or not executable. Reinstall v5 first.'
        exit 4
    }
    Write-Log 'pip_remote not running. Starting continuous mode.'
    $launch = Invoke-Adb @('-s', $Device, 'shell', '"nohup /data/local/tmp/pip_remote 0 > /data/local/tmp/pip_remote.log 2>&1 < /dev/null &"') 20
    if ($launch.ExitCode -ne 0) {
        Write-Log "ERROR: launch failed: $($launch.Output)"
        exit 5
    }
    Start-Sleep -Seconds 3
    $runningPid = Check-Pid
    if ($runningPid) {
        Write-Log "SUCCESS: pip_remote running (PID: $runningPid)."
        exit 0
    }
    $remoteLog = Invoke-Adb @('-s', $Device, 'shell', 'tail', '-n', '8', '/data/local/tmp/pip_remote.log')
    Write-Log "ERROR: process did not stay running. Fire Stick log: $($remoteLog.Output -replace '[\r\n]+', ' | ')"
    exit 6
} catch {
    Write-Log "ERROR: $($_.Exception.Message)"
    exit 99
}
