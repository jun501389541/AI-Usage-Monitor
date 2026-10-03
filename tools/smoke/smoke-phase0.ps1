#requires -Version 5
<#
Phase 0 baseline smoke test for AI Usage Monitor.

Verifies the acceptance items that can be checked without a real API key:
  1. the debug APK exists and installs
  2. the app launches and its UI is the expected upstream screen
  3. the balance query performs a real network round trip and maps the
     provider's error response onto the expected user-facing message
  4. no crash is recorded while doing the above
  5. the three app widgets are registered with the launcher

The success path (a real key producing a balance) is checked separately by
tools/smoke/smoke-balance.ps1 once a key is available.

Usage:
  .\smoke-phase0.ps1
  .\smoke-phase0.ps1 -InvalidKey "sk-0000000000000000000000000000000000000000"
#>
[CmdletBinding()]
param(
  # A syntactically plausible but unauthorized key. Used to exercise the
  # request/response/error-mapping path without touching a real account.
  [string]$InvalidKey = "sk-0000000000000000000000000000000000000000",

  # Package and launcher activity. Parameterised because Phase 1 renamed both;
  # the script itself is the regression baseline and should not need editing
  # again when the identity changes. Phase 2 moved the launcher to the account
  # list and demoted MainActivity to the per-account detail page, so the
  # balance-query half of this script now navigates list -> detail first.
  [string]$Package = "com.aiusage.monitor",
  [string]$Activity = "com.aiusage.monitor.ui.account.AccountListActivity",
  [string]$DetailActivity = "com.aiusage.monitor.ui.MainActivity"
)

$ErrorActionPreference = "Continue"
. "$PSScriptRoot\..\env.ps1"

$ws = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$out = Join-Path $PSScriptRoot "out"
New-Item -ItemType Directory -Path $out -Force | Out-Null
$apk = Join-Path $ws "app\build\outputs\apk\debug\app-debug.apk"

$failures = @()

function Write-Result {
  param([string]$Name, [bool]$Ok, [string]$Detail)
  $mark = if ($Ok) { "PASS" } else { "FAIL" }
  Write-Output ("[{0}] {1}" -f $mark, $Name)
  if ($Detail) { Write-Output ("       {0}" -f $Detail) }
  if (-not $Ok) { $script:failures += $Name }
}

function Invoke-Adb {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
  $line = "adb " + ($Args -join " ")
  cmd /c "$line 2>&1" | Out-Null
}

function Get-UiNodes {
  param([string]$Tag)
  $local = Join-Path $out "smoke-$Tag.xml"
  Remove-Item $local -Force -ErrorAction SilentlyContinue
  cmd /c "adb shell rm -f /sdcard/u.xml 2>&1" | Out-Null
  cmd /c "adb shell uiautomator dump /sdcard/u.xml 2>&1" | Out-Null
  cmd /c "adb pull /sdcard/u.xml `"$local`" 2>&1" | Out-Null
  if (-not (Test-Path $local)) { return @() }
  $xml = [System.IO.File]::ReadAllText($local, [System.Text.Encoding]::UTF8)
  $nodes = @()
  foreach ($m in [regex]::Matches($xml, '<node[^>]*>')) {
    $n = $m.Value
    $text = if ($n -match 'text="([^"]*)"') { $matches[1] } else { "" }
    $bounds = if ($n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
      [pscustomobject]@{
        L = [int]$matches[1]; T = [int]$matches[2]
        R = [int]$matches[3]; B = [int]$matches[4]
      }
    } else { $null }
    $rid = if ($n -match 'resource-id="([^"]*)"') { $matches[1] } else { "" }
    $nodes += [pscustomobject]@{ Text = $text; Bounds = $bounds; Id = $rid }
  }
  return $nodes
}

function Get-Centre {
  param($Node)
  return @(
    [int](($Node.Bounds.L + $Node.Bounds.R) / 2),
    [int](($Node.Bounds.T + $Node.Bounds.B) / 2)
  )
}

function Find-Node {
  param($Nodes, [scriptblock]$Predicate)
  return $Nodes | Where-Object $Predicate | Select-Object -First 1
}

function Get-Texts {
  param($Nodes)
  return @($Nodes | Where-Object { $_.Text -ne "" } | ForEach-Object { $_.Text })
}

Write-Output "=== Phase 0 baseline smoke test ==="
Write-Output ""

# ------------------------------------------------------------------ device
$devices = cmd /c "adb devices 2>&1"
$online = @($devices | Select-String -Pattern "\sdevice$")
Write-Result "an adb device is online" ($online.Count -gt 0) ($online -join "; ")
if ($online.Count -eq 0) {
  Write-Output ""
  Write-Output "No device: start one with"
  Write-Output '  $env:ANDROID_AVD_HOME="D:\Android\.android\avd"'
  Write-Output '  & "D:\Android\sdk\emulator\emulator.exe" -avd Pixel_7_API_37'
  exit 1
}

# --------------------------------------------------------------------- apk
Write-Result "debug APK exists" (Test-Path $apk) $apk
if (-not (Test-Path $apk)) { exit 1 }

Invoke-Adb install -r $apk
$installed = cmd /c "adb shell pm list packages $Package 2>&1"
Write-Result "package installs" ($installed -match [regex]::Escape($Package)) ($installed -join "; ")

# ------------------------------------------------------------------ launch
Invoke-Adb shell am force-stop $Package
Start-Sleep -Milliseconds 800
Invoke-Adb shell am start -n "$Package/$Activity"
Start-Sleep -Seconds 4

$focus = cmd /c "adb shell dumpsys window 2>&1" | Select-String -Pattern "mCurrentFocus"
Write-Result "the launcher activity holds focus" ($focus -match [regex]::Escape($Package)) ($focus -join "; ")

$nodes = Get-UiNodes "launch"
$texts = Get-Texts $nodes
Write-Result "the account list renders" (($texts -join "|") -match "账户" -and ($texts -join "|") -match "添加账户") ($texts -join " | ")

# ----------------------------------------------------- list -> detail nav
Write-Output ""
Write-Output "--- opening the first account card ---"

# Tap the status line of the first account card. The card itself owns the click
# listener, but its text nodes are what the dump exposes; tapping the text hits
# the card behind it. Falling back to a direct start keeps the script usable on
# a device with no accounts yet.
$cardStatus = Find-Node $nodes { $_.Text -match "^(尚未查询|账户可用|数据已过期|网络连接失败|API Key 无效或已失效|正在刷新|Bridge 未连接)$" }
if ($cardStatus) {
  $c = Get-Centre $cardStatus
  Invoke-Adb shell input tap $c[0] $c[1]
  Start-Sleep -Seconds 3
} else {
  Write-Output "       (no account card found; opening the detail page directly)"
  Invoke-Adb shell am start -n "$Package/$DetailActivity"
  Start-Sleep -Seconds 3
}

$detailFocus = cmd /c "adb shell dumpsys window 2>&1" | Select-String -Pattern "mCurrentFocus"
Write-Result "the detail page opened" ($detailFocus -match [regex]::Escape($DetailActivity)) ($detailFocus -join "; ")

$nodes = Get-UiNodes "detail"
$texts = Get-Texts $nodes
Write-Result "the upstream UI renders" (($texts -join "|") -match "API KEY") ($texts -join " | ")

# ------------------------------------------------- query path (invalid key)
Write-Output ""
Write-Output "--- exercising the balance query path with an unauthorized key ---"

$keyField = Find-Node $nodes { $_.Text -match "^sk-" }
if ($keyField) {
  $c = Get-Centre $keyField
  Invoke-Adb shell input tap $c[0] $c[1]
  Start-Sleep -Milliseconds 800
  Invoke-Adb shell input text $InvalidKey
  Start-Sleep -Milliseconds 800
  Invoke-Adb shell input keyevent KEYCODE_BACK   # close the soft keyboard
  Start-Sleep -Milliseconds 800
}

$nodes = Get-UiNodes "typed"
$queryBtn = Find-Node $nodes { $_.Text -eq "查询余额" }
if ($queryBtn) {
  $c = Get-Centre $queryBtn
  Invoke-Adb shell input tap $c[0] $c[1]
  Start-Sleep -Seconds 6
}

$nodes = Get-UiNodes "queried"
$texts = Get-Texts $nodes

# An unauthorized key must produce the provider-mapped message, not a crash
# and not a generic failure. This proves the request left the device and the
# response was parsed.
$mapped = ($texts -join "|") -match "API Key 无效或已失效"
$generic = ($texts -join "|") -match "网络连接失败|数据解析失败"
Write-Result "unauthorized key maps to the 401 message" $mapped ($texts -join " | ")
if (-not $mapped) {
  Write-Result "  (a generic network error instead suggests no connectivity)" (-not $generic) ""
}

# ------------------------------------------------------------ no crashes
Write-Output ""
$logcat = cmd /c "adb logcat -d 2>&1"
$crashes = @($logcat | Select-String -Pattern "FATAL EXCEPTION|AndroidRuntime.*$([regex]::Escape($Package))")
Write-Result "no crash during the run" ($crashes.Count -eq 0) ("$($crashes.Count) matching lines")

# ---------------------------------------------------------------- widgets
$appwidget = cmd /c "adb shell dumpsys appwidget 2>&1"
$providers = @($appwidget | Select-String -Pattern "cmp:ComponentInfo\{$([regex]::Escape($Package))/")
foreach ($size in @("4x2", "2x2", "2x1")) {
  $hit = @($providers | Select-String -Pattern "BalanceWidget$size" -AllMatches)
  Write-Result "widget provider $size is registered" ($hit.Count -gt 0) ""
}

$bound = @($appwidget | Select-String -Pattern "provider=ProviderId.*$([regex]::Escape($Package))" -AllMatches)
Write-Result "at least one widget instance is bound" ($bound.Count -gt 0) ("$($bound.Count) instance(s)")

# ---------------------------------------------------------------- summary
Write-Output ""
if ($failures.Count -eq 0) {
  Write-Output "RESULT: all Phase 0 smoke checks passed"
  exit 0
}
Write-Output ("RESULT: {0} check(s) failed: {1}" -f $failures.Count, ($failures -join ", "))
exit 1
