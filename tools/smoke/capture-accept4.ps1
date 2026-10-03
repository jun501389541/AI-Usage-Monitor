#requires -Version 5
<#
Capture the Phase 2 acceptance-4 screenshots: two widgets bound to different
accounts, plus the detail page each widget opens.

Why this exists: the widget layout deliberately does not render the account name
(Spec 27/30 only require the widget to read through
UsageRepository -> UsageResult), so the home screen cannot show which account a
widget is bound to. The binding is stored in widget_config and the observable
contract is behavioural, so the screenshot pair is: the home page carrying both
widgets, then the detail page each widget opens when tapped.

Both taps are driven through the same layout-matched navigation as
widget-open-account.ps1, and every screenshot is gated on verified state: the
first attempt at these shots used a fixed Start-Sleep and produced a black
splash frame and a launcher frame, neither of which is evidence of anything.
So each capture waits for MainActivity to hold focus AND for its title to equal
the expected account, and fails loudly instead of writing a useless image.

Usage:
  .\capture-accept4.ps1
#>
[CmdletBinding()]
param(
  # Absolute coords of the two widgets to photograph, on the censused layout.
  [string]$LayoutKey = "50,164,1030,694;50,722,521,1252;560,722,1030,973;560,1001,1030,1252;305,1280,776,1810",

  # widget centre -> account it is bound to, as established by the sweep.
  [string]$TapA = "540,429",
  [string]$AccountA = "DeepSeek",
  [string]$TapB = "795,1126",
  [string]$AccountB = "DeepSeekWork"
)

$ErrorActionPreference = "Continue"
. "$PSScriptRoot\..\env.ps1"

$out = Join-Path $PSScriptRoot "out"
New-Item -ItemType Directory -Path $out -Force | Out-Null

$pkg = "com.aiusage.monitor"
# Brace-delimited on purpose: inside a double-quoted string PowerShell parses
# "$pkg.ui.MainActivity" as the .ui property of $pkg (silently empty) and
# "$pkg:id" as a scoped variable, so neither would ever match.
$mainFocus = "${pkg}/${pkg}.ui.MainActivity"
$listActivity = "${pkg}/${pkg}.ui.account.AccountListActivity"
$widgetRootId = "${pkg}:id/widget_root"

function Invoke-Adb {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
  # cmd /c keeps stderr-writing adb commands from tripping NativeCommandError.
  $line = "adb " + ($Args -join " ")
  return (cmd /c "$line 2>&1")
}

function Get-UiNodes {
  param([string]$Tag)
  $local = Join-Path $out "w-$Tag.xml"
  Remove-Item $local -Force -ErrorAction SilentlyContinue
  # Delete the on-device file first: uiautomator refuses to dump while the UI
  # animates and leaves the previous file behind, so a pull after a failed dump
  # silently returns the screen before last.
  cmd /c "adb shell rm -f /sdcard/u.xml 2>&1" | Out-Null
  cmd /c "adb shell uiautomator dump /sdcard/u.xml 2>&1" | Out-Null
  cmd /c "adb pull /sdcard/u.xml `"$local`" 2>&1" | Out-Null
  if (-not (Test-Path $local)) { return @() }
  $xml = [System.IO.File]::ReadAllText($local, [System.Text.Encoding]::UTF8)
  $nodes = @()
  foreach ($m in [regex]::Matches($xml, '<node[^>]*>')) {
    $n = $m.Value
    # Attribute values are raw XML text; &amp; is decoded last so an escaped
    # "&amp;lt;" stays "<".
    $text = if ($n -match 'text="([^"]*)"') { $matches[1] } else { "" }
    $text = $text.Replace('&lt;', '<').Replace('&gt;', '>').Replace('&quot;', '"').Replace('&apos;', "'").Replace('&amp;', '&')
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

# HOME does NOT always reset the launcher to its default page: it returns the
# launcher to the page it was last left on, and only goes back to page 1 when the
# launcher is already the foreground app. Pressing HOME twice is the reset: the
# first press brings the launcher forward, the second one is the focused-launcher
# case.
function Reset-ToHome {
  Invoke-Adb shell input keyevent KEYCODE_HOME | Out-Null
  Start-Sleep -Seconds 2
  Invoke-Adb shell input keyevent KEYCODE_HOME | Out-Null
  Start-Sleep -Seconds 2
}

# A force-stopped app's widgets do not stay rendered: the launcher drops them to
# a placeholder (a grey card with the app icon, resource id work_widget_app_icon)
# until the app is started again. Measured on this device: force-stopped ->
# widget_root=0 placeholder=1; after am start -> widget_root=0 placeholder=0;
# after HOME -> widget_root=2. So a run that force-stops the app and then goes
# straight HOME censuses an empty page and can never match a layout - which is
# exactly how this script failed its first two runs.
function Wake-Widgets {
  Invoke-Adb shell am start -n $listActivity | Out-Null
  Start-Sleep -Seconds 3
  Reset-ToHome
  Start-Sleep -Seconds 2
}

# Swipes are dropped, so the page a tap lands on is verified instead of assumed.
function Go-ToLayout {
  param([string]$Key, [int]$MaxSwipes = 5)
  for ($attempt = 0; $attempt -le $MaxSwipes; $attempt++) {
    if ((Get-Census).Key -eq $Key) { return $true }
    Invoke-Adb shell input swipe 900 1400 200 1400 300 | Out-Null
    Start-Sleep -Milliseconds 1400
  }
  return ((Get-Census).Key -eq $Key)
}

function Get-Census {
  $nodes = Get-UiNodes "census"
  $w = @($nodes | Where-Object { $_.Id -eq $widgetRootId -and $_.Bounds })
  $key = (($w | ForEach-Object { "$($_.Bounds.L),$($_.Bounds.T),$($_.Bounds.R),$($_.Bounds.B)" }) -join ";")
  return [pscustomobject]@{ Widgets = $w; Key = $key }
}

function Wait-ForFocus {
  param([string]$Match, [int]$Attempts = 14)
  for ($i = 1; $i -le $Attempts; $i++) {
    $line = (Invoke-Adb shell dumpsys window | Select-String "mCurrentFocus" | Select-Object -First 1)
    if ($line -and $line.Line -match [regex]::Escape($Match)) { return $true }
    Start-Sleep -Milliseconds 800
  }
  return $false
}

# The detail page's title is account.getDisplayName() and its kicker is the
# hardcoded "DEEPSEEK API". A case-sensitive match keeps the kicker out of the
# account name; PowerShell's -match/-eq are case-insensitive by default.
function Get-OpenedAccount {
  param([string]$Tag)
  $nodes = Get-UiNodes $Tag
  $names = @($nodes |
    Where-Object { $_.Text -cmatch '^DeepSeek' } |
    ForEach-Object { $_.Text } |
    Sort-Object -Unique)
  if ($names.Count -eq 0) { return "<none>" }
  return ($names -join "|")
}

# A screenshot is only worth keeping if the screen it captured is the screen the
# acceptance criterion names. The first attempt at these shots used a fixed sleep
# and saved a black splash frame (23 KB) and a launcher frame; both looked like
# findings and neither was. So: wait for focus, then poll the rendered title
# until it equals the expected account, and only then capture.
function Capture {
  param([string]$Expected, [string]$Name)
  if (-not (Wait-ForFocus $mainFocus)) {
    throw "MainActivity never took focus while waiting to capture $Name"
  }
  $seen = "<none>"
  for ($i = 1; $i -le 10; $i++) {
    $seen = Get-OpenedAccount "shot"
    if ($seen -eq $Expected) {
      cmd /c "adb exec-out screencap -p > $out\$Name.png" | Out-Null
      $bytes = (Get-Item "$out\$Name.png").Length
      Write-Output ("        captured {0}.png ({1} bytes) showing {2}" -f $Name, $bytes, $seen)
      return $seen
    }
    Start-Sleep -Milliseconds 900
  }
  throw "refusing to save $Name.png: expected the detail page for '$Expected' but the screen shows '$seen'"
}

Write-Output "[1/3] the home page carrying both widgets"
Wake-Widgets
if (-not (Go-ToLayout $LayoutKey)) {
  throw "could not reach the page that carries both widgets; no screenshot taken"
}
cmd /c "adb exec-out screencap -p > $out\p2-accept4-home.png" | Out-Null
Write-Output ("        captured p2-accept4-home.png ({0} bytes)" -f (Get-Item "$out\p2-accept4-home.png").Length)

Write-Output ("[2/3] tapping {0} -> detail page for {1}" -f $TapA, $AccountA)
$ax, $ay = $TapA -split ","
Invoke-Adb shell input tap $ax $ay | Out-Null
Capture -Expected $AccountA -Name "p2-accept4-detail-deepseek" | Out-Null

Write-Output ("[3/3] tapping {0} -> detail page for {1}" -f $TapB, $AccountB)
Reset-ToHome
if (-not (Go-ToLayout $LayoutKey)) {
  throw "could not reach the widget page for the second tap; no screenshot taken"
}
$bx, $by = $TapB -split ","
Invoke-Adb shell input tap $bx $by | Out-Null
Capture -Expected $AccountB -Name "p2-accept4-detail-deepseekwork" | Out-Null

Write-Output ""
Write-Output "acceptance 4 screenshots:"
foreach ($n in @("p2-accept4-home", "p2-accept4-detail-deepseek", "p2-accept4-detail-deepseekwork")) {
  Write-Output ("  {0}.png  {1} bytes" -f $n, (Get-Item "$out\$n.png").Length)
}
