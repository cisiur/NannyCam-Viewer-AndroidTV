param([string]$Adb='C:\platform-tools\adb.exe',[string]$Device='192.168.50.60:5555')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $MyInvocation.MyCommand.Path
$sdk=if($env:ANDROID_HOME){$env:ANDROID_HOME}else{Join-Path $env:LOCALAPPDATA 'Android\Sdk'}
$ndkRoot=Join-Path $sdk 'ndk'
if(!(Test-Path $ndkRoot)){throw "Android NDK not found under $ndkRoot. Install NDK using Android Studio SDK Manager."}
$ndk=Get-ChildItem $ndkRoot -Directory | Sort-Object Name -Descending | Select-Object -First 1
$clang=Join-Path $ndk.FullName 'toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe'
if(!(Test-Path $clang)){throw "clang not found: $clang"}
if(!(Test-Path $Adb)){throw "adb not found: $Adb"}
$abi=(& $Adb -s $Device shell getprop ro.product.cpu.abi).Trim()
if($LASTEXITCODE -ne 0){throw 'Connect with adb first.'}
$target=switch -regex ($abi){'armeabi-v7a' {'armv7a-linux-androideabi23';break} 'arm64-v8a' {'aarch64-linux-android23';break} default {throw "Unsupported ABI: $abi"}}
$out=Join-Path $root 'pip_remote'
& $clang --target=$target -O2 -Wall -Wextra -o $out (Join-Path $root 'pip_remote.c')
if($LASTEXITCODE -ne 0){throw 'NDK compilation failed'}
& $Adb -s $Device push $out /data/local/tmp/pip_remote
if($LASTEXITCODE -ne 0){throw 'adb push failed'}
& $Adb -s $Device shell chmod 755 /data/local/tmp/pip_remote
Write-Host "Installed v5. Test: & '$Adb' -s '$Device' shell /data/local/tmp/pip_remote 90"
Write-Host "Continuous: & '$Adb' -s '$Device' shell /data/local/tmp/pip_remote 0"
