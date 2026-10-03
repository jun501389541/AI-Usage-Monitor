#requires -Version 5
<#
Tap AI Usage Monitor widgets on the launcher home screen and report which
account each one opens.

Why this exists: Phase 2 binds one account per widget id, and the widget layout
deliberately does not render the account name (Spec 27/30 only require the
widget to read through UsageRepository -> UsageResult). The home screen
therefore cannot be checked by eye, and neither can a screenshot prove that two
widgets point at different accounts. The observable contract is behavioural:
tapping a widget opens that widget's account.

It is also the regression harness for the onNewIntent bug: MainActivity is
launched with FLAG_ACTIVITY_SINGLE_TOP | FLAG_ACTIVITY_CLEAR_TOP, so a second
tap reuses the first instance. Without onNewIntent the second, third and fourth
taps all keep showing the first widget's account - which is what -ColdMode
exposes by contrast.

Two modes:

  -ColdMode   force-stop the app before every tap, so each tap is a genuine
              cold start and reports the account actually bound to that widget.
              This builds the position -> account map.

  default     no restart between taps: the first tap starts the activity, every
              later tap lands on the same instance through CLEAR_TOP. Comparing
              this run against -ColdMode is what proves the intent is re-read.

Usage:
  .\widget-open-account.ps1 -ColdMode
  .\widget-open-account.ps1
#>
[CmdletBinding()]
param(
  # How many home pages to sweep (the launcher has 3 here).
  [int]$Pages = 3,

  # Force-stop the app before each tap, so every tap is a cold start.
  [switch]$ColdMode,

  # Restrict the sweep to one page (1-based), for a focused re-run.
  [int]$OnlyPage = 0
)

$ErrorActionPreference = "Continue"
. "$PSScriptRoot\..\env.ps1"

$out = Join-Path $PSScriptRoot "out"
New-Item -ItemType Directory -Path $out -Force | Out-Null

$pkg = "com.aiusage.monitor"
# Brace-delimited on purpose: inside a double-quoted string PowerShell parses
# "$pkg.ui.MainActivity" as the .ui property of $pkg (silently empty) and
# "$pkg:id" as a scoped variable, so neither the component name nor the
# resource id would ever match - the sweep reported "0 widget(s) on screen"
# while two widgets were plainly there.
$mainFocus = "${pkg}/${pkg}.ui.MainActivity"
$listActivity = "${pkg}/${pkg}.ui.account.AccountListActivity"
$widgetRootId = "${pkg}:id/widget_root"

function Invoke-Adb {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
  # cmd /c keeps stderr-writing adb commands from tripping PowerShell's
  # NativeCommandError, which would otherwise look like a failure.
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

function Get-Centre {
  param($Node)
  return @(
    [int](($Node.Bounds.L + $Node.Bounds.R) / 2),
    [int](($Node.Bounds.T + $Node.Bounds.B) / 2)
  )
}

# HOME does NOT always reset the launcher to its default page: it returns the
# launcher to the page it was last left on, and only goes back to page 1 when the
# launcher is already the foreground app. Measured on this device: HOME while the
# launcher is focused -> page 1; HOME while an app is focused -> whichever page
# the launcher was left on. Swiping on top of that lands anywhere, which is how
# page 2's widgets came to be tapped at page 3's coordinates. Pressing HOME twice
# is therefore the reset: the first press brings the launcher forward, the second
# one is the focused-launcher case.
function Reset-ToHome {
  param([int]$PageIndex)
  Invoke-Adb shell input keyevent KEYCODE_HOME | Out-Null
  Start-Sleep -Seconds 2
  Invoke-Adb shell input keyevent KEYCODE_HOME | Out-Null
  Start-Sleep -Seconds 2
  for ($i = 0; $i -lt $PageIndex; $i++) {
    Invoke-Adb shell input swipe 900 1400 200 1400 300 | Out-Null
    Start-Sleep -Milliseconds 1200
  }
  Start-Sleep -Milliseconds 800
}

# Swipes are dropped and the last page does not advance, so the page a tap will
# land on is verified instead of assumed: swipe until the on-screen layout
# matches the one the tap plan was built from.
function Go-ToLayout {
  param([string]$Key, [int]$MaxSwipes = 5)
  for ($attempt = 0; $attempt -le $MaxSwipes; $attempt++) {
    if ((Get-Census).Key -eq $Key) { return $true }
    Invoke-Adb shell input swipe 900 1400 200 1400 300 | Out-Null
    Start-Sleep -Milliseconds 1400
  }
  return ((Get-Census).Key -eq $Key)
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

function Get-WidgetNodes {
  $nodes = Get-UiNodes "census"
  return @($nodes | Where-Object { $_.Id -eq $widgetRootId -and $_.Bounds })
}

# A page is identified by the geometry of the widgets on it. Swipes get dropped
# and HOME does not always land where it is expected, so the census of each page
# is compared with the previous one: an identical census means the swipe never
# advanced, and continuing would report page 3's widgets as an empty page (which
# is exactly how "page 3, 0 widget(s)" appeared).
function Get-Census {
  $nodes = Get-UiNodes "census"
  $w = @($nodes | Where-Object { $_.Id -eq $widgetRootId -and $_.Bounds })
  $key = (($w | ForEach-Object { "$($_.Bounds.L),$($_.Bounds.T),$($_.Bounds.R),$($_.Bounds.B)" }) -join ";")
  return [pscustomobject]@{
    Widgets = $w
    Key = $key
    Centre = @($w | ForEach-Object { , (Get-Centre $_) })
  }
}

# ------------------------------------------------------------- pass 1: census
# The whole sweep is planned before anything is tapped. Tapping changes the
# foreground and a force-stop can re-lay-out the launcher, so a per-tap re-read
# used to abort the sweep when the node order shifted. Coordinates captured from
# a settled page are stable across the restart, so they are what gets tapped.
Write-Output "[pass 1] census of the home pages"

$tapPlan = @()
$layouts = @()
Reset-ToHome 0
for ($page = 1; $page -le $Pages; $page++) {
  $census = Get-Census
  if ($census.Key -eq "") {
    Write-Output ("[page {0}] empty (past the last page)" -f $page)
    break
  }
  if ($layouts.Count -gt 0 -and $census.Key -eq $layouts[$layouts.Count - 1].Key) {
    Write-Output ("[page {0}] the layout did not change; stopping at page {1}" -f $page, ($page - 1))
    break
  }
  if ($OnlyPage -gt 0 -and $page -ne $OnlyPage) {
    $layouts += [pscustomobject]@{ Page = $page; Key = $census.Key }
    Invoke-Adb shell input swipe 900 1400 200 1400 300 | Out-Null
    Start-Sleep -Milliseconds 1400
    continue
  }

  $layouts += [pscustomobject]@{ Page = $page; Key = $census.Key }
  Write-Output ("[page {0}] {1} widget(s) on screen" -f $page, $census.Widgets.Count)
  for ($i = 0; $i -lt $census.Widgets.Count; $i++) {
    $c = $census.Centre[$i]
    $w = $census.Widgets[$i]
    Write-Output ("        p{0}w{1} [{2},{3}][{4},{5}] centre {6},{7}" -f `
        $page, ($i + 1), $w.Bounds.L, $w.Bounds.T, $w.Bounds.R, $w.Bounds.B, $c[0], $c[1])
    $tapPlan += [pscustomobject]@{
      Page = $page; Index = $i + 1; X = $c[0]; Y = $c[1]; Key = $census.Key
    }
  }
  if ($page -lt $Pages) {
    Invoke-Adb shell input swipe 900 1400 200 1400 300 | Out-Null
    Start-Sleep -Milliseconds 1400
  }
}

# ------------------------------------------------------------- pass 2: taps
Write-Output ""
Write-Output "[pass 2] tapping each widget and reading the account it opens"

$results = @()
foreach ($t in $tapPlan) {
  $tag = "p$($t.Page)w$($t.Index)"

  if ($ColdMode) {
    # A force-stopped app is left in the stopped state; starting its launcher
    # activity clears that without reinstalling anything. Cancelled alarms and a
    # discarded process are what make the next tap a genuine cold start.
    Invoke-Adb shell am force-stop $pkg | Out-Null
    Start-Sleep -Seconds 2
    Invoke-Adb shell am start -n $listActivity | Out-Null
    Start-Sleep -Seconds 2
  }

  # The page is reached by matching the layout it was censused with. Swiping a
  # fixed number of times from HOME does not work: HOME leaves the launcher on
  # the page it was left on when the foreground app was something else, so the
  # swipes started from an unknown page and the taps landed on page 3's
  # coordinates. Eleven taps at two different widgets' centres are what that
  # looked like: one hit, four misses.
  Reset-ToHome 0
  if (-not (Go-ToLayout $t.Key)) {
    Write-Output ("        {0} could not reach the censused layout; skipped" -f $tag)
    $results += [pscustomobject]@{ Page = $t.Page; Tap = "$($t.X),$($t.Y)"; Account = "<unreachable>" }
    continue
  }

  Write-Output ("        {0} tapping {1},{2}" -f $tag, $t.X, $t.Y)
  Invoke-Adb shell input tap $t.X $t.Y | Out-Null

  $focused = Wait-ForFocus $mainFocus
  if (-not $focused) {
    $focus = (Invoke-Adb shell dumpsys window | Select-String "mCurrentFocus" | Select-Object -First 1)
    Write-Output ("        {0} -> no MainActivity; focus={1}" -f $tag, $(if ($focus) { $focus.Line.Trim() } else { "?" }))
    $results += [pscustomobject]@{ Page = $t.Page; Tap = "$($t.X),$($t.Y)"; Account = "<no-nav>" }
    continue
  }

  # The account title is rendered by buildInterface(); give the activity a
  # moment so the dump cannot catch the previous screen mid-transition.
  Start-Sleep -Seconds 3
  $account = Get-OpenedAccount $tag
  Write-Output ("        {0} -> {1}" -f $tag, $account)
  $results += [pscustomobject]@{ Page = $t.Page; Tap = "$($t.X),$($t.Y)"; Account = $account }
}

Write-Output ""
Write-Output ("mode = {0}" -f $(if ($ColdMode) { "cold (force-stop before every tap)" } else { "warm (activity reused through CLEAR_TOP)" }))
Write-Output ("taps = {0}" -f $results.Count)
foreach ($r in $results) {
  Write-Output ("  page {0}  tap {1,-9} -> {2}" -f $r.Page, $r.Tap, $r.Account)
}

$distinct = @($results | Where-Object { $_.Account -ne "<no-nav>" } | ForEach-Object { $_.Account } | Sort-Object -Unique)
Write-Output ("distinct accounts opened = {0}: {1}" -f $distinct.Count, ($distinct -join ", "))
