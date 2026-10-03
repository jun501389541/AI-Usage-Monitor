#requires -Version 5
<#
Dump the current UI hierarchy from a connected device and print the nodes that
matter, as plain text.

Why a helper: every ad-hoc dump in this project re-implemented the same regex
loop, and two PowerShell traps bit repeatedly —

  1. [regex]::Matches does NOT populate $Matches; only the -match operator does.
     Reading $Matches[1] after a [regex]::Matches loop yields null.
  2. A dump taken while the UI is animating can fail and leave the previous
     /sdcard/u.xml in place, so the pull silently returns a stale screen. The
     file is therefore deleted before every dump.

Usage:
  .\dump-ui.ps1                       # nodes with text or a widget-ish id
  .\dump-ui.ps1 -All                  # every node with text or an id
  .\dump-ui.ps1 -IdFilter 'preview'   # only ids matching a pattern
#>
[CmdletBinding()]
param(
  [string]$Tag = "dump",
  [switch]$All,
  [string]$IdFilter = "widget_preview|widgets_catalog|tab|search|edit",
  [string]$TextFilter = ""
)

$ErrorActionPreference = "Continue"
. "$PSScriptRoot\..\env.ps1"

$out = Join-Path $PSScriptRoot "out"
New-Item -ItemType Directory -Path $out -Force | Out-Null
$local = Join-Path $out "$Tag.xml"

# Delete first: a failed dump leaves the previous file behind, and pulling that
# would describe a screen that is no longer on screen.
cmd /c "adb shell rm -f /sdcard/u.xml 2>&1" | Out-Null
$dump = cmd /c "adb shell uiautomator dump /sdcard/u.xml 2>&1"
if ($dump -notmatch "dumped to") {
  Write-Output "DUMP-FAILED: $dump"
  exit 2
}
cmd /c "adb pull /sdcard/u.xml `"$local`" 2>&1" | Out-Null

if (-not (Test-Path $local)) {
  Write-Output "PULL-FAILED: $local"
  exit 2
}

$xml = [IO.File]::ReadAllText($local, [Text.Encoding]::UTF8)
Write-Output ("# file={0} bytes={1}" -f $local, $xml.Length)

$package = ""
foreach ($m in [regex]::Matches($xml, 'package="([^"]+)"')) {
  # Assign from $m.Groups, never from $Matches: only -match sets $Matches.
  $candidate = $m.Groups[1].Value
  if ($candidate -ne $package) {
    $package = $candidate
    Write-Output "# package=$package"
  }
}

foreach ($m in [regex]::Matches($xml, '<node[^>]*>')) {
  $node = $m.Value

  $text = ""
  $tm = [regex]::Match($node, 'text="([^"]*)"')
  if ($tm.Success) { $text = $tm.Groups[1].Value }

  $id = ""
  $im = [regex]::Match($node, 'resource-id="([^"]*)"')
  if ($im.Success) { $id = $im.Groups[1].Value }

  $bounds = ""
  $bm = [regex]::Match($node, 'bounds="(\[\d+,\d+\]\[\d+,\d+\])"')
  if ($bm.Success) { $bounds = $bm.Groups[1].Value }

  if (-not $text -and -not $id) { continue }
  if ($TextFilter -and $text -notmatch $TextFilter) { continue }
  if (-not $All -and -not $TextFilter -and $id -notmatch $IdFilter -and -not $text) { continue }

  Write-Output ("'{0}' {1} {2}" -f $text, $bounds, $id)
}
