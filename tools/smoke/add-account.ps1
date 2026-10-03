#requires -Version 5
<#
Adds one DeepSeek account through the Phase 2 UI, on a real device.

Why this is a UI-driven script instead of a DB insert: credentials are sealed
with the Android Keystore (Spec D3), so a credential row cannot be fabricated
from the host. Driving the real add-account form is also the only way to
exercise the Phase 2 acceptance items that depend on accounts existing
("two keys work independently", "widgets bind different accounts").

The flow is: launcher -> add-account -> name -> [remember] -> key -> save.
It re-reads the dump after every step and closes the soft keyboard before
looking for the next field, because the keyboard shifts the form up.

Usage:
  .\add-account.ps1 -Name "DeepSeek" -Key "sk-..."
  .\add-account.ps1 -Name "DeepSeekWork" -Key "sk-..." -NoRemember
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$Name,

  # The API key to store. Pass a syntactically plausible but unauthorized key
  # to seed an account whose refresh is expected to fail.
  [Parameter(Mandatory = $true)][string]$Key,

  # Leave the key out of the credential store. Used to seed an account that is
  # deliberately keyless, which is a legitimate state (Spec §14).
  [switch]$NoRemember,

  [string]$Package = "com.aiusage.monitor",
  [string]$ListActivity = "com.aiusage.monitor.ui.account.AccountListActivity"
)

$ErrorActionPreference = "Continue"
. "$PSScriptRoot\..\env.ps1"

$out = Join-Path $PSScriptRoot "out"
New-Item -ItemType Directory -Path $out -Force | Out-Null

$listFocus = "${Package}/${ListActivity}"

function Invoke-Adb {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
  cmd /c ("adb " + ($Args -join " ") + " 2>&1") | Out-Null
}

function Get-UiNodes {
  param([string]$Tag)
  $local = Join-Path $out "add-$Tag.xml"
  Remove-Item $local -Force -ErrorAction SilentlyContinue
  # The dump must be deleted first: a failed dump leaves the previous screen in
  # place, and reading that back would silently validate the wrong screen.
  Invoke-Adb shell rm -f /sdcard/u.xml
  Invoke-Adb shell uiautomator dump /sdcard/u.xml
  Invoke-Adb pull /sdcard/u.xml $local
  if (-not (Test-Path $local)) { return @() }
  $xml = [System.IO.File]::ReadAllText($local, [System.Text.Encoding]::UTF8)
  $nodes = @()
  foreach ($m in [regex]::Matches($xml, '<node[^>]*>')) {
    $n = $m.Value
    $decode = {
      param([string]$s)
      # &amp; must be decoded last, otherwise an escaped entity in the source
      # ("&amp;lt;") would be turned into "<" by the earlier passes.
      $s = $s -replace '&lt;', '<' -replace '&gt;', '>' -replace '&quot;', '"' -replace '&apos;', "'"
      return ($s -replace '&amp;', '&')
    }
    $text = if ($n -match 'text="([^"]*)"') { & $decode $matches[1] } else { "" }
    $bounds = $null
    if ($n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
      $bounds = [pscustomobject]@{
        L = [int]$matches[1]; T = [int]$matches[2]
        R = [int]$matches[3]; B = [int]$matches[4]
      }
    }
    $rid = if ($n -match 'resource-id="([^"]*)"') { $matches[1] } else { "" }
    $cls = if ($n -match 'class="([^"]*)"') { $matches[1] } else { "" }
    $clk = $n -match '(?<!long-)clickable="true"'
    $chk = $n -match '(?<!long-)checked="true"'
    $nodes += [pscustomobject]@{
      Text = $text; Bounds = $bounds; Id = $rid; Cls = $cls
      Clickable = $clk; Checked = $chk
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

function Get-Focus {
  $f = cmd /c "adb shell dumpsys window 2>&1" | Select-String -Pattern "mCurrentFocus"
  return ($f -join "; ")
}

function Wait-ForFocus {
  param([string]$Match, [int]$Tries = 12)
  for ($i = 0; $i -lt $Tries; $i++) {
    if ((Get-Focus) -match [regex]::Escape($Match)) { return $true }
    Start-Sleep -Milliseconds 800
  }
  return $false
}

Write-Output "=== adding the account '$Name' ==="

# The widget census is unreliable while the app is stopped (the launcher shows
# a placeholder until the app runs again), so wake it and land on the list.
Invoke-Adb shell am force-stop $Package
Start-Sleep -Seconds 2
Invoke-Adb shell am start -n "${Package}/${ListActivity}"
if (-not (Wait-ForFocus $listFocus)) {
  throw "the account list did not come up: $(Get-Focus)"
}
Start-Sleep -Seconds 3
Write-Output "  [1/6] the account list is up"

# ------------------------------------------------------------------ add card
$nodes = Get-UiNodes "list"
$add = $nodes | Where-Object { $_.Text -eq "添加账户" } | Select-Object -First 1
if (-not $add) { throw "the '添加账户' button was not found on the account list" }
$c = Get-Centre $add
Invoke-Adb shell input tap $c[0] $c[1]
Start-Sleep -Seconds 3
$nodes = Get-UiNodes "edit"
if (-not ($nodes | Where-Object { $_.Text -eq "API KEY" })) {
  throw "the add-account form did not open"
}
Write-Output "  [2/6] the add-account form is up"

function Set-Field {
  param([string]$MatchText, [string]$Value, [string]$Tag)
  $nodes = Get-UiNodes $Tag
  $field = $nodes |
    Where-Object { $_.Cls -eq "android.widget.EditText" -and $_.Text -eq $MatchText } |
    Select-Object -First 1
  if (-not $field) {
    # An EditText shows its hint as text while it is empty.
    $field = $nodes |
      Where-Object { $_.Cls -eq "android.widget.EditText" -and $_.Text -match $MatchText } |
      Select-Object -First 1
  }
  if (-not $field) { throw "no EditText matching '$MatchText'" }
  $p = Get-Centre $field
  Invoke-Adb shell input tap $p[0] $p[1]
  Start-Sleep -Milliseconds 900
  Invoke-Adb shell input text $Value
  Start-Sleep -Milliseconds 900
  # The soft keyboard covers the lower half of the form, so it has to be
  # dismissed before the next control can be located by its bounds.
  Invoke-Adb shell input keyevent KEYCODE_BACK
  Start-Sleep -Milliseconds 900
}

Set-Field "例如 DeepSeek 个人" $Name "name"
Write-Output "  [3/6] the name is filled in"

if (-not $NoRemember) {
  $nodes = Get-UiNodes "remember"
  $box = $nodes |
    Where-Object { $_.Cls -eq "android.widget.CheckBox" -and $_.Text -eq "记住密钥" } |
    Select-Object -First 1
  if (-not $box) { throw "the '记住密钥' checkbox was not found" }
  if ($box.Checked) {
    # Never assume the initial state: clicking an already-checked box would
    # silently *uncheck* it and drop the credential.
    Write-Output "  [4/6] '记住密钥' is already checked"
  } else {
    $c = Get-Centre $box
    Invoke-Adb shell input tap $c[0] $c[1]
    Start-Sleep -Milliseconds 900
    $nodes = Get-UiNodes "remember2"
    $box2 = $nodes |
      Where-Object { $_.Cls -eq "android.widget.CheckBox" -and $_.Text -eq "记住密钥" } |
      Select-Object -First 1
    if (-not $box2.Checked) { throw "clicking '记住密钥' did not check it" }
    Write-Output "  [4/6] '记住密钥' is now checked"
  }

  Set-Field "sk-\.\.\." $Key "key"
  Write-Output "  [5/6] the key is filled in"
} else {
  Write-Output "  [4/6] the key is deliberately not remembered"
  Write-Output "  [5/6] no key entered"
}

# --------------------------------------------------------------------- save
$nodes = Get-UiNodes "presave"
$save = $nodes |
  Where-Object { $_.Cls -eq "android.widget.TextView" -and $_.Text -eq "添加账户" -and $_.Clickable } |
  Select-Object -First 1
if (-not $save) { $save = $nodes | Where-Object { $_.Text -eq "添加账户" -and $_.Clickable } | Select-Object -First 1 }
if (-not $save) { throw "the save button was not found" }
$c = Get-Centre $save
Invoke-Adb shell input tap $c[0] $c[1]
Start-Sleep -Seconds 4

if (-not (Wait-ForFocus $listFocus)) {
  throw "saving did not return to the account list: $(Get-Focus)"
}
$nodes = Get-UiNodes "saved"
$texts = @($nodes | Where-Object { $_.Text -ne "" } | ForEach-Object { $_.Text })
Write-Output "  [6/6] saved; the list now reads:"
Write-Output ("        " + ($texts -join " | "))

if (-not (($texts -join "|") -match [regex]::Escape($Name))) {
  throw "the account list does not mention '$Name' after saving"
}

Write-Output ""
Write-Output "RESULT: account '$Name' added"
exit 0
