#requires -Version 5
<#
Place an AI Usage Monitor widget onto the launcher home screen from the
system widget picker, entirely over adb.

Why this exists: `adb shell input draganddrop` starts moving immediately and
therefore never triggers the long-press that a widget drag requires. The only
gesture that works is an explicit DOWN -> MOVE... -> UP sequence with a real
hold at the start, which is what this script emits.

Two robustness rules are baked in:

1. No hardcoded coordinates. Every tap target is located by dumping the UI
   hierarchy and reading the node's real bounds, so the script survives list
   scroll-position and launcher layout changes.
2. ASCII-only source. The app's labels contain CJK characters; a script file
   read under the wrong codepage turns those literals into something that
   never matches. Matching uses the ASCII prefix plus the size digits.

The picker it drives is the rewritten Launcher3 one, which behaves quite
differently from the older sheet-based picker:

  * its container ids live under com.android.launcher3.widgetpicker, not under
    the launcher package;
  * the first level is a list of *apps*. `widget_preview` nodes do not exist
    until the app's row is tapped to expand it, so "wait for three previews" is
    never true here;
  * because a widget can carry android:configure, the drop does not just place
    the widget: it hands control to the app's configuration activity. Pressing
    BACK at that point would cancel the placement, so the script instead picks
    an account and lets the configuration finish.

Usage:
  .\place-widget.ps1 -WidgetSize 4x2 -AccountName DeepSeek
  .\place-widget.ps1 -WidgetSize 2x1 -AccountName DeepSeekWork -DropY 1600
#>
[CmdletBinding()]
param(
  # Which widget to place: 4x2, 2x2 or 2x1.
  [Parameter(Mandatory = $true)]
  [ValidateSet("4x2", "2x2", "2x1")]
  [string]$WidgetSize,

  # Where to release the drag, in screen pixels.
  [int]$DropX = 540,
  [int]$DropY = 900,

  # Query typed into the picker's search box to isolate the app. Phase 1 renamed
  # the app to "AI Usage Monitor" and its widgets to "AI Usage NxN", so this is
  # parameterised rather than hardcoded to the upstream label.
  [string]$SearchQuery = "AI Usage",

  # Which account the newly placed widget is bound to, matched against the row
  # labels in the app's configuration activity. That activity is reached
  # automatically after the drop, because the widget declares android:configure.
  [string]$AccountName = "DeepSeek"
)

$ErrorActionPreference = "Continue"
. "$PSScriptRoot\..\env.ps1"

$out = Join-Path $PSScriptRoot "out"
New-Item -ItemType Directory -Path $out -Force | Out-Null

# "4x2" matches "<SearchQuery> 4<times>2" without spelling the times sign.
# \D stands in for the multiplication sign: a CJK or symbol literal here would
# silently stop matching if this file's encoding ever regressed to GBK.
$dim = $WidgetSize.Split("x")
$labelPattern = "^{0}\s+{1}\D+{2}$" -f [regex]::Escape($SearchQuery), $dim[0], $dim[1]
# Matches any size label of the target app ("AI Usage 2<times>2"), used to tell
# "the group is above the fold" from "the group is below the fold".
$sectionPattern = "^{0}\s+\d" -f [regex]::Escape($SearchQuery)

function Invoke-Adb {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
  # cmd /c keeps stderr-writing adb commands from tripping PowerShell's
  # NativeCommandError, which would otherwise look like a failure.
  $line = "adb " + ($Args -join " ")
  cmd /c "$line 2>&1" | Out-Null
}

function Get-UiNodes {
  param([string]$Tag)
  $local = Join-Path $out "u-$Tag.xml"
  Remove-Item $local -Force -ErrorAction SilentlyContinue
  # Delete the on-device file first. uiautomator refuses to dump while the UI is
  # animating and leaves the previous file in place, so a pull after a failed
  # dump silently returns the screen before last. Removing it turns that failure
  # into a missing file, which is a visible failure instead of a wrong answer.
  cmd /c "adb shell rm -f /sdcard/u.xml 2>&1" | Out-Null
  cmd /c "adb shell uiautomator dump /sdcard/u.xml 2>&1" | Out-Null
  cmd /c "adb pull /sdcard/u.xml `"$local`" 2>&1" | Out-Null
  if (-not (Test-Path $local)) { return @() }
  $xml = [System.IO.File]::ReadAllText($local, [System.Text.Encoding]::UTF8)
  $nodes = @()
  foreach ($m in [regex]::Matches($xml, '<node[^>]*>')) {
    $n = $m.Value
    $text = if ($n -match 'text="([^"]*)"') { $matches[1] } else { "" }
    # Attribute values are raw XML text, so the home menu's "Wallpaper & style"
    # arrives as "Wallpaper &amp; style". Comparing the encoded form against a
    # decoded literal can never match, which reads a correct screen as a wrong
    # one. &amp; is decoded last so an escaped "&amp;lt;" stays "<".
    $text = $text.Replace('&lt;', '<')
    $text = $text.Replace('&gt;', '>')
    $text = $text.Replace('&quot;', '"')
    $text = $text.Replace('&apos;', "'")
    $text = $text.Replace('&amp;', '&')
    $bounds = if ($n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
      [pscustomobject]@{
        L = [int]$matches[1]; T = [int]$matches[2]
        R = [int]$matches[3]; B = [int]$matches[4]
      }
    } else { $null }
    $rid = if ($n -match 'resource-id="([^"]*)"') { $matches[1] } else { "" }
    $cls = if ($n -match 'class="([^"]*)"') { $matches[1] } else { "" }
    # clickable matters because a launcher dump is mostly full-screen layout
    # containers: 7 of the 80 nodes on this home screen are [0,0][1080,2400]
    # wrappers that cover every point yet are not icons and cannot be pressed.
    # The lookbehind is required: 'long-clickable="true"' ends with the literal
    # 'clickable="true"', so a plain match would treat a long-press-only node as
    # a pressable one.
    $click = ($n -match '(?<!long-)clickable="true"')
    $nodes += [pscustomobject]@{
      Text = $text; Bounds = $bounds; Id = $rid; Class = $cls; Clickable = $click
    }
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

function Tap-Node {
  param($Node)
  $c = Get-Centre $Node
  Invoke-Adb shell input tap $c[0] $c[1]
  Start-Sleep -Milliseconds 1500
}

function Find-Node {
  param($Nodes, [scriptblock]$Predicate)
  return $Nodes | Where-Object $Predicate | Select-Object -First 1
}

function Show-Nodes {
  param($Nodes)
  Write-Output "      nodes currently on screen:"
  $Nodes |
    Where-Object { $_.Text -ne "" -or $_.Id -match "widget_preview|search" } |
    ForEach-Object {
      $b = if ($_.Bounds) { "[{0},{1}][{2},{3}]" -f $_.Bounds.L, $_.Bounds.T, $_.Bounds.R, $_.Bounds.B } else { "?" }
      Write-Output ("        '{0}' {1} {2}" -f $_.Text, $b, $_.Id)
    }
}

function Wait-ForNode {
  param(
    # Named Test, not Predicate: a parameter called $Predicate would shadow
    # Find-Node's own $Predicate inside the wrapper scriptblock and recurse
    # forever.
    [scriptblock]$Test,
    [int]$Attempts = 10,
    [string]$TagPrefix = "wait",
    [int]$DelayMs = 1000
  )
  for ($i = 1; $i -le $Attempts; $i++) {
    $nodes = Get-UiNodes "$TagPrefix$i"
    $hit = $nodes | Where-Object $Test | Select-Object -First 1
    if ($hit) { return [pscustomobject]@{ Node = $hit; Nodes = $nodes } }
    Start-Sleep -Milliseconds $DelayMs
  }
  return $null
}

# Long-pressing a home-screen icon opens that app's own shortcut menu, whose
# "Widgets" entry leads to a single-app catalog. That looks almost exactly like
# success until the app row can never be found, so the press point has to be
# chosen from the real layout rather than assumed.
#
# The wallpaper menu can only be opened by pressing empty space, so this reads
# the current page and returns a point that is clear of every icon and widget on
# it. Candidates are tried in order and pairs are dropped when they fall inside
# any node's bounds (a home-screen icon is a node with text but no id).
function Get-EmptyHomePoint {
  param([int]$Margin = 40)

  $nodes = Get-UiNodes "home"
  # Only clickable nodes block a press. A launcher dump also contains the
  # full-screen wrappers listed above, and counting those made every candidate
  # look occupied, so the function never returned a point and the script failed
  # with "picker never opened" while it had never actually long-pressed.
  $blocked = @($nodes | Where-Object {
    $_.Clickable -and $_.Bounds -and
    -not ($_.Bounds.L -le 0 -and $_.Bounds.T -le 0 -and
          $_.Bounds.R -ge 1080 -and $_.Bounds.B -ge 2300)
  })

  $candidates = @(
    @(140, 620), @(940, 620), @(140, 1150), @(940, 1150),
    @(540, 700), @(540, 1150), @(140, 1750), @(940, 1750)
  )
  foreach ($c in $candidates) {
    $x = $c[0]; $y = $c[1]
    # The search bar lives at the bottom and swallows presses by opening the
    # Google app, and the status bar is not part of the wallpaper either.
    if ($y -gt 2000 -or $y -lt 340) { continue }
    $clash = $blocked | Where-Object {
      $x -ge ($_.Bounds.L - $Margin) -and $x -le ($_.Bounds.R + $Margin) -and
      $y -ge ($_.Bounds.T - $Margin) -and $y -le ($_.Bounds.B + $Margin)
    } | Select-Object -First 1
    if (-not $clash) { return @($x, $y) }
  }
  return $null
}

# ---------------------------------------------------------------- open picker
Write-Output "[1/7] resetting to home"

# Opening the picker and landing on its Browse tab is the flaky part: the tab
# sits at the bottom of a bottom sheet, so a tap that lands a few pixels off
# dismisses the sheet instead of switching tabs, and the screen behind it then
# gets dumped as if it were the picker. The whole open sequence is therefore
# retried and validated, rather than trusted once.
function Open-PickerOnBrowseTab {
  param([int]$Attempts = 5)

  # Progress lines here use Write-Host, not Write-Output, and that is load
  # bearing: Write-Output would append the message to this function's *return*
  # value, so the caller would receive an array like
  # @("      long-pressing ...", $true). Every non-empty array is truthy, so
  # `if (-not (Open-PickerOnBrowseTab))` would then be false and the failure
  # path would never run - the script would go on to search for the app row in
  # whatever screen happened to be showing.
  for ($attempt = 1; $attempt -le $Attempts; $attempt++) {
    # The launcher's search box is provided by the Google app, and once that app
    # has been foregrounded a long press on the home screen reopens it instead of
    # showing the launcher menu. Stopping it makes the gesture deterministic.
    Invoke-Adb shell am force-stop com.google.android.googlequicksearchbox
    Invoke-Adb shell input keyevent KEYCODE_BACK
    Start-Sleep -Milliseconds 800
    Invoke-Adb shell input keyevent KEYCODE_HOME
    Start-Sleep -Seconds 3

    # Read the page first and press a point that is verifiably clear of every
    # icon and widget on it. Guessing here is what makes this step flaky: an
    # app icon's own menu also offers "Widgets", and that leads to a catalog
    # containing exactly one app.
    $point = Get-EmptyHomePoint
    if (-not $point) {
      Write-Host "      no empty spot on this home page; scrolling to another page"
      Invoke-Adb shell input swipe 900 1400 200 1400 300
      Start-Sleep -Seconds 2
      $point = Get-EmptyHomePoint
    }
    if (-not $point) { continue }

    Write-Host ("      long-pressing empty wallpaper at {0},{1}" -f $point[0], $point[1])
    Invoke-Adb shell input swipe $point[0] $point[1] $point[0] $point[1] 1200
    Start-Sleep -Seconds 3

    $menu = Wait-ForNode { $_.Text -eq "Widgets" } -TagPrefix "menu$attempt" -Attempts 4
    if (-not $menu) { continue }

    # Accept the menu only if it is the wallpaper/home menu. "Wallpaper & style"
    # is the marker: an app's own shortcut menu offers App info and Pause app,
    # never that entry.
    $style = Find-Node $menu.Nodes { $_.Text -eq "Wallpaper & style" }
    if (-not $style) {
      Write-Host ("      press at {0},{1} landed on an app icon, not the wallpaper; retrying" -f $point[0], $point[1])
      Invoke-Adb shell input keyevent KEYCODE_BACK
      Start-Sleep -Seconds 2
      continue
    }

    Tap-Node $menu.Node
    Start-Sleep -Seconds 4

    $tab = Wait-ForNode { $_.Text -eq "Browse" } -TagPrefix "picker$attempt" -Attempts 5
    if (-not $tab) { continue }
    Tap-Node $tab.Node
    Start-Sleep -Seconds 4

    # Accept the state once the catalog itself is up. Counting widget_preview
    # nodes would never succeed here: the first level of this picker lists apps,
    # and previews only appear after one of them is expanded.
    $nodes = Get-UiNodes "browse$attempt"
    $catalog = Find-Node $nodes { $_.Id -match "widgetpicker:id/(widgets_catalog|personal_widgets_list)" }
    if ($catalog) {
      Write-Host ("      picker open on Browse (attempt {0})" -f $attempt)
      return $true
    }
  }
  return $false
}

if (-not (Open-PickerOnBrowseTab)) {
  Show-Nodes (Get-UiNodes "picker-fail")
  throw "could not open the widget picker on its Browse tab"
}

Write-Output "[2/7] scrolling the '$SearchQuery' section into the middle of the list"

# The first level of this picker lists apps, not widgets. Tapping the app's row
# expands it in place, and only then do its widget previews exist at all. The
# list is alphabetical, so a name that sorts early ("AI Usage") is already near
# the top and the first dump usually finds it with no scrolling.
#
# Only ever scroll DOWN (finger moves up). A downward swipe inside the list drags
# the sheet itself closed, which silently loses the picker and leaves the next
# dump describing whatever is behind it.
function Scroll-SectionIntoView {
  param([int]$Attempts = 10)

  $header = $null
  for ($i = 1; $i -le $Attempts; $i++) {
    $nodes = Get-UiNodes "scan$i"
    # A stray scroll gesture can drag the sheet shut. Without this check the
    # loop would keep scrolling the home screen and eventually report a missing
    # app row, which points at the wrong problem.
    #
    # Note this only fires on dumps taken *after* the sheet closed: the first
    # dump is taken immediately after the picker was confirmed open, but the
    # launcher can still be animating, and uiautomator then describes the
    # screen underneath. Retry a couple of times before declaring it gone.
    if (-not (Find-Node $nodes { $_.Id -match "widgetpicker:id/widgets_catalog" })) {
      Start-Sleep -Seconds 2
      $nodes = Get-UiNodes "scan${i}b"
      if (-not (Find-Node $nodes { $_.Id -match "widgetpicker:id/widgets_catalog" })) {
        throw "the widget picker was dismissed while looking for the '$SearchQuery' app row"
      }
    }
    # The app row reads "AI Usage Monitor" and its widget labels read
    # "AI Usage 4x2". Excluding the size pattern is what tells them apart.
    $header = Find-Node $nodes { $_.Text -match "^$SearchQuery" -and $_.Text -notmatch $sectionPattern -and $_.Bounds }
    if ($header -and $header.Bounds.T -ge 300 -and $header.Bounds.T -le 1400) {
      return $header
    }
    if (-not $header -or $header.Bounds.T -gt 1400) {
      # Not visible yet, or still below the fold: scroll the list down.
      Invoke-Adb shell input swipe 540 1900 540 900 400
    }
    Start-Sleep -Seconds 2
  }
  return $header
}

$section = Scroll-SectionIntoView
if (-not $section) {
  Show-Nodes (Get-UiNodes "section-fail")
  throw "could not find the '$SearchQuery' app row in the picker"
}
Write-Output ("      app row at y={0}" -f $section.Bounds.T)

Write-Output "[3/7] expanding the app row and locating the $WidgetSize preview"

# Expanding is a one-shot action. Tapping the app row repeatedly toggles it open
# and shut, which makes the search below oscillate forever, so expansion is
# tracked as state instead of being retried inside the loop.
$appRow = $section
$expanded = $false
$label = $null
$candidate = $null
$scrolled = $false
for ($attempt = 1; $attempt -le 12; $attempt++) {
  $nodes = Get-UiNodes "find$attempt"

  # The picker can be dismissed by a stray gesture; every dump is checked so
  # that loss of the catalog fails loudly instead of being read as "not expanded
  # yet" and retried forever.
  if (-not (Find-Node $nodes { $_.Id -match "widgetpicker:id/widgets_catalog" })) {
    throw "the widget picker was dismissed while looking for the $WidgetSize preview"
  }

  $row = Find-Node $nodes { $_.Text -match "^$SearchQuery" -and $_.Text -notmatch $sectionPattern -and $_.Bounds }
  if ($row) { $appRow = $row }

  # Only previews that sit below the app's own row belong to it; a preview left
  # over from anything else would be dragged instead of the intended widget.
  $own = @($nodes | Where-Object {
    $_.Id -match "widget_preview" -and $_.Bounds -and $appRow -and $_.Bounds.T -ge $appRow.Bounds.B
  })

  if (-not $expanded) {
    if ($own.Count -eq 0) {
      if ($appRow) {
        $c = Get-Centre $appRow
        Write-Output ("      expanding the app row at {0},{1}" -f $c[0], $c[1])
        Invoke-Adb shell input tap $c[0] $c[1]
        Start-Sleep -Seconds 3
      }
      continue
    }
    $expanded = $true
  }

  $label = Find-Node $nodes { $_.Text -match $labelPattern }

  if ($label) {
    # The preview region sits directly above its label. Take the closest
    # preview whose bottom edge is at or above the label's top edge.
    $candidate = $nodes |
      Where-Object { $_.Id -match "widget_preview" -and $_.Bounds.B -le $label.Bounds.T + 10 } |
      Sort-Object { [Math]::Abs($_.Bounds.B - $label.Bounds.T) } |
      Select-Object -First 1

    # Keep clear of the catalog's own header and of the tab bar along the
    # bottom: a drop that starts on either would not begin a drag at all.
    if ($candidate -and $candidate.Bounds.T -gt 150 -and $candidate.Bounds.B -lt 2100) { break }
  }

  # Expanded but the target size is not on screen yet: scroll toward it. The
  # picker lists sizes in descending order (4x2, 2x2, 2x1), so a missing 2x1
  # means the target is below the fold until proven otherwise.
  if ($label -and $label.Bounds.T -lt 400 -and $scrolled) {
    # Overshot: the label is above the fold, so its preview is off-screen above.
    Invoke-Adb shell input swipe 540 1200 540 1700 400
  } else {
    Invoke-Adb shell input swipe 540 1900 540 900 400
    $scrolled = $true
  }
  Start-Sleep -Seconds 2
}

if (-not $label) {
  Show-Nodes (Get-UiNodes "final")
  throw "widget label matching '$labelPattern' was never visible"
}
if (-not $candidate) {
  throw "no preview region found above the $WidgetSize label"
}

Write-Output "[4/7] dragging the preview onto the home screen"
$src = Get-Centre $candidate
Write-Output ("      dragging from {0},{1} to {2},{3}" -f $src[0], $src[1], $DropX, $DropY)

# Emit the whole gesture inside one shell invocation so the input stream stays
# coherent; the hold at the start is what arms the drag.
$midY = [int](($src[1] + $DropY) / 2)
$script = @"
input motionevent DOWN $($src[0]) $($src[1])
sleep 1
input motionevent MOVE $($src[0]) $($src[1] - 20)
sleep 1
input motionevent MOVE $($src[0]) $midY
sleep 1
input motionevent MOVE $DropX $([int]($midY / 2))
sleep 1
input motionevent MOVE $DropX $DropY
sleep 1
input motionevent UP $DropX $DropY
sleep 4
echo dragged
"@
$tmp = Join-Path $env:TEMP "aiusage-drag.sh"
Set-Content -Path $tmp -Value $script -Encoding ASCII
cmd /c "adb push `"$tmp`" /data/local/tmp/aiusage-drag.sh 2>&1" | Out-Null
cmd /c "adb shell sh /data/local/tmp/aiusage-drag.sh 2>&1"
Start-Sleep -Seconds 3

# The drop does not finish the placement on its own. Every widget here declares
# android:configure, so the launcher hands off to the app's configuration
# activity (through android.appwidget.AppWidgetConfigActivityProxy) and the
# widget stays unconfigured until that activity returns RESULT_OK. Pressing BACK
# here, as this script used to, cancels the placement outright -- which is why
# the picker could be driven successfully by hand and still leave no widget
# behind. The account has to be chosen for the placement to complete.
Write-Output "[5/7] choosing the account in the widget configuration activity"

# The activity is recognised by its ASCII kicker, "WIDGET". A case-sensitive
# comparison is required: PowerShell's -eq is case-insensitive, so matching
# "WIDGET" loosely would also accept the picker menu entry "Widgets".
$config = Wait-ForNode { $_.Text -ceq "WIDGET" } -TagPrefix "config" -Attempts 8 -DelayMs 1000
if (-not $config) {
  Show-Nodes (Get-UiNodes "config-fail")
  throw "the widget configuration activity did not appear after the drop"
}

# Exact match, not prefix: the accounts here are named "DeepSeek" and
# "DeepSeekWork", and a prefix match would sometimes take the wrong one.
$row = Find-Node $config.Nodes { $_.Text -eq $AccountName -and $_.Bounds }
if (-not $row) {
  Show-Nodes $config.Nodes
  throw "no '$AccountName' row in the widget configuration activity"
}

$c = Get-Centre $row
Write-Output ("      selecting '$AccountName' at {0},{1}" -f $c[0], $c[1])
Invoke-Adb shell input tap $c[0] $c[1]
Start-Sleep -Seconds 3

# The activity finishing is what commits the placement: until the launcher is
# frontmost again, the widget is bound but its configuration has not returned.
Start-Sleep -Seconds 2
$focus = cmd /c "adb shell dumpsys window 2>&1" | Select-String "mCurrentFocus"
Write-Output ("      " + ($focus | Select-Object -First 1).Line.Trim())

Write-Output "[6/7] counting the bound widget instances"
$dump = cmd /c "adb shell dumpsys appwidget 2>&1"
$bound = @($dump | Select-String -Pattern "provider=ProviderId.*com\.aiusage\.monitor" -AllMatches)
Write-Output ("      bound widget instances for com.aiusage.monitor: {0}" -f $bound.Count)
foreach ($b in $bound) { Write-Output ("        " + $b.Line.Trim()) }

# Which account each instance is bound to. Phase 2 acceptance depends on two
# widgets pointing at different accounts, and the widget does not render the
# account name, so the row is the only place that difference is visible.
Write-Output "[7/7] reading the widget -> account bindings from the database"
$db = Join-Path $out "widget-bindings.db"
Remove-Item $db -Force -ErrorAction SilentlyContinue
# exec-out is binary-safe; a plain `adb shell cat` would rewrite CRLF and
# corrupt the SQLite header.
cmd /c "adb exec-out run-as com.aiusage.monitor cat databases/ai_usage_monitor.db > `"$db`" 2>&1" | Out-Null
if (-not (Test-Path $db)) { throw "could not pull the database from the device" }

$sqlite = Join-Path $env:ANDROID_HOME "platform-tools\sqlite3.exe"
if (-not (Test-Path $sqlite)) { $sqlite = "sqlite3" }
if (-not (Get-Command $sqlite -ErrorAction SilentlyContinue)) {
  Write-Output "      sqlite3 not found; skipping the binding read"
  return
}

Write-Output ("      database pulled: {0} bytes" -f (Get-Item $db).Length)
Write-Output "      select c.widget_id, c.widget_type, ifnull(group_concat(s.slot_index || ':' || s.account_id, ','), '-') from widget_config c left join widget_slots s on s.widget_id = c.widget_id group by c.widget_id order by c.widget_id;"
# The statement goes in over stdin: cmd mangles the double quotes inside a
# -separator argument list, which breaks any SQL that quotes a literal.
'select c.widget_id || "|" || c.widget_type || "|" || ifnull(group_concat(s.slot_index || ":" || s.account_id, ","), "-") from widget_config c left join widget_slots s on s.widget_id = c.widget_id group by c.widget_id order by c.widget_id;' |
  cmd /c "`"$sqlite`" `"$db`""
