#requires -Version 5
<#
One-command inner loop for this project: build -> install -> relaunch -> read the
app's logcat.

Why this exists: adb is not on PATH, and two of the launch paths are easy to get
wrong, both of which make a working app look broken.

  1. `am start -n .../AccountListActivity` does NOT put that screen on top when
     a detail page is already in the same task. Android brings the existing task
     forward and the detail page stays focused, so the command looks like a
     no-op. The MAIN/LAUNCHER intent (what the home icon sends) does land on the
     launcher screen.

  2. `gradlew :app:installDebug` force-stops the app. Placed widgets then render
     as the launcher's grey placeholder cards until something starts the app
     again, which reads as "my widgets disappeared".

Usage:
  .\tools\dev.ps1                 # build, install, relaunch, print recent logcat
  .\tools\dev.ps1 -NoBuild        # relaunch installed version + logcat only
  .\tools\dev.ps1 -Follow         # relaunch, then stream logcat (Ctrl+C to stop)
  .\tools\dev.ps1 -ErrorsOnly     # crashes and errors only
  .\tools\dev.ps1 -AccountId ID   # open the detail page for one account
#>
[CmdletBinding()]
param(
  [switch]$NoBuild,
  [switch]$Follow,
  [switch]$ErrorsOnly,
  [string]$AccountId = ""
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\env.ps1"

$pkg      = "com.aiusage.monitor"
$listComp = "$pkg/com.aiusage.monitor.ui.account.AccountListActivity"
$detailComp = "$pkg/com.aiusage.monitor.ui.MainActivity"
$repoRoot = Split-Path -Parent $PSScriptRoot
$apk      = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"

# MainActivity is exported=false, so it cannot be started by component name from
# adb. The list screen is the only way in.
$crashFilter = "FATAL EXCEPTION|AndroidRuntime|E AndroidRuntime|\bE \w+.*Exception"

function Get-AppPid {
  # $pid is a read-only automatic variable in PowerShell; never assign it.
  $raw = cmd /c "adb shell pidof $pkg 2>&1"
  if ($null -eq $raw) { return "" }
  return (($raw | Out-String).Trim())
}

function Invoke-Cmd {
  param([string]$Text, [switch]$Quiet)
  $out = cmd /c "$Text 2>&1"
  $code = $LASTEXITCODE
  if (-not $Quiet -and $out) { $out | ForEach-Object { Write-Host $_ } }
  if ($code -ne 0) { throw "'$Text' failed with exit code $code" }
  return $out
}

if (-not $NoBuild) {
  Write-Host "[1/4] building and installing" -ForegroundColor Cyan
  $sw = [Diagnostics.Stopwatch]::StartNew()
  Invoke-Cmd ".\gradlew.bat :app:installDebug" -Quiet
  Write-Host ("      installed in {0}s" -f [int]$sw.Elapsed.TotalSeconds)
} else {
  Write-Host "[1/4] skipped the build (-NoBuild)" -ForegroundColor DarkGray
}

if (-not (Test-Path $apk)) { throw "debug APK missing at $apk - run without -NoBuild" }
Write-Host ("[2/4] APK {0} bytes" -f (Get-Item $apk).Length)

# Clear first so the tail below only shows this run.
cmd /c "adb logcat -c 2>&1" | Out-Null

Write-Host "[3/4] launching the account list" -ForegroundColor Cyan
# The explicit MAIN/LAUNCHER intent, not -n alone; see the header note.
Invoke-Cmd "adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $listComp" -Quiet
Start-Sleep -Seconds 3

if ($AccountId) {
  Write-Host "      the detail page for $AccountId has to be opened by tapping its row"
  Write-Host "      (MainActivity is exported=false, adb cannot start it directly)"
}

$appPid = Get-AppPid
if ($appPid) {
  Write-Host "      running as pid $appPid"
} else {
  Write-Host "      WARNING: the process is not running - the app crashed on launch" -ForegroundColor Yellow
}

Write-Host "[4/4] logcat" -ForegroundColor Cyan
if ($Follow) {
  if ($appPid) { cmd /c "adb logcat -v time --pid=$appPid" }
  else         { cmd /c "adb logcat -v time" }
  return
}

# The app ships no Log calls, so an empty result here is normal, not a fault.
# What is worth reading is framework-level: crashes, ANRs and start records.
$lines = cmd /c "adb logcat -d -v time 2>&1"
if ($ErrorsOnly) {
  $shown = $lines | Select-String -Pattern $crashFilter
} else {
  $shown = $lines | Select-String -Pattern "$crashFilter|ActivityTaskManager: START|ActivityManager: Start proc|aiusage"
}
if ($shown) { $shown | Select-Object -Last 40 } else { Write-Host "      (nothing notable)" }

$crashCount = ($lines | Select-String -Pattern "FATAL EXCEPTION").Count
Write-Host ""
if ($crashCount -gt 0) {
  Write-Host "FATAL EXCEPTION lines: $crashCount" -ForegroundColor Red
  exit 1
}
Write-Host "no crash during this run" -ForegroundColor Green
