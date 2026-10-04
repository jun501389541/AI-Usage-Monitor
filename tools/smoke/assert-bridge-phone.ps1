<#
    Phase 6 phone-side acceptance: an Android account whose data comes from the
    Windows AI Usage Bridge, checked on a running emulator against a Bridge
    running on this machine.

    Covers docs/PHASE-6-PLAN.md rows C1-C9. Every row states how it could fail, and
    a check that no implementation could break is deliberately not written here -
    four review rounds have now caught that pattern (assert-history,
    assert-widget-slots, assert-bridge step 7, and the first draft of this file).

    What it does to the machine:
      - starts the Bridge on 127.0.0.1 only (Phase 5 behaviour, unchanged);
      - runs a stand-in quota server bound to 0.0.0.0 for the length of the run,
        so the emulator's view of the host LAN address can be compared with the
        app's own view of it. It answers 200 only for the header it was started
        with, which is what makes the token path testable at all;
      - clears the emulator's logcat buffer, creates four Codex accounts through
        the real UI, and deletes the ones it made. Typing the token means `adb
        shell input text` passes it through adbd, which logs its own commands, so
        C7 counts that line as the harness and asserts nothing else says it;
      - disables the emulator's soft keyboard for the length of the run and puts it
        back afterwards, because that IME mode eats injected key events for
        suggestion-capable fields (see Disable-SoftKeyboard); the canary check
        "injected text reaches a plain-text field" is what proves the run has a
        working typing channel at all;
      - reads the app database through `run-as` (copy out, never write).

    It never touches the two DeepSeek accounts or their credentials - those keys
    exist only on this device and cannot be typed back in. The run records their
    ids and credential ids first and re-checks them last, so "we did not eat a
    real account" is asserted rather than promised. C5 re-runs the existing
    DeepSeek acceptance scripts, which do their own backup/restore.

    Usage:
        powershell -File tools/smoke/assert-bridge-phone.ps1
        powershell -File tools/smoke/assert-bridge-phone.ps1 -SkipLegacy
#>
param(
    [string]$Serial = "emulator-5554",
    [string]$Package = "com.aiusage.monitor",
    [int]$BridgePort = 38481,
    [int]$FakePort = 38482,
    [string]$Token = "bpToken-6f2a1c9d",
    [switch]$SkipLegacy
)

$ErrorActionPreference = "Stop"

# Gradle and adb both need this machine's toolchain paths; without it the build
# step fails on a missing JAVA_HOME, which looks like a code failure and is not.
. "$PSScriptRoot\..\env.ps1"

$script:checks = 0
$script:failures = 0
$script:procs = @()
$script:dumpSeq = 0
$script:work = ""

function Check {
    param([string]$Name, [bool]$Ok, [string]$Detail = "")
    $script:checks++
    if ($Ok) {
        Write-Host "PASS  $Name"
    } else {
        $script:failures++
        if ($Detail) {
            Write-Host "FAIL  $Name -- $Detail"
        } else {
            Write-Host "FAIL  $Name"
        }
    }
}

# Write-Host rather than Write-Output: a value-returning helper that logs on the way
# out (New-CodexAccount notes why the form was wrong, Get-ReadingWithRetry notes the
# host's transient Codex outage) would otherwise return its log line *and* its value,
# and [bool] refuses the array - measured on 2026-10-03, when one failed UI step
# aborted the run with ParameterArgumentTransformationError and lost the other forty
# checks. Host output still reaches the log file through the shell-level redirect.
function Note {
    param([string]$Text)
    Write-Host "NOTE  $Text"
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$AdbArgs)
    cmd /c "adb -s $Serial $($AdbArgs -join ' ') 2>&1"
}

# Every dump is a separate evidence file. Reusing one name made a failed dump read
# back the previous screen and score the wrong page, and it destroyed the evidence
# for the step that had just failed.
function Dump-Ui {
    param([string]$Tag)
    $script:dumpSeq++
    $local = Join-Path $script:work ("dumps\{0:d2}-{1}.xml" -f $script:dumpSeq, $Tag)
    Invoke-Adb shell rm -f /sdcard/bp.xml | Out-Null
    Invoke-Adb shell uiautomator dump /sdcard/bp.xml | Out-Null
    Invoke-Adb pull /sdcard/bp.xml $local | Out-Null
    if (-not (Test-Path $local)) {
        Note "dump '$Tag' produced nothing; the check below sees an empty screen"
        return ""
    }
    return [System.IO.File]::ReadAllText($local, [System.Text.Encoding]::UTF8)
}

function Node-Center {
    param([string]$Xml, [string]$Needle)
    foreach ($m in [regex]::Matches($Xml, '<node[^>]*>')) {
        $tag = $m.Value
        if ($tag -notlike ("*" + $Needle + "*")) { continue }
        $b = [regex]::Match($tag, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $b.Success) { continue }
        $x1 = [int]$b.Groups[1].Value
        $y1 = [int]$b.Groups[2].Value
        $x2 = [int]$b.Groups[3].Value
        $y2 = [int]$b.Groups[4].Value
        return @([int](($x1 + $x2) / 2), [int](($y1 + $y2) / 2))
    }
    return $null
}

# uiautomator does not emit hint text, so a field that is still empty cannot be
# found by the hint it shows. The account form's fields are located by position
# instead, and every one is confirmed by the text it holds afterwards.
function Edit-Field {
    param([string]$Xml, [int]$Index)
    $matches = @([regex]::Matches($Xml, '<node[^>]*class="[^"]*EditText"[^>]*>'))
    if ($matches.Count -le $Index) { return $null }
    $b = [regex]::Match($matches[$Index].Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $b.Success) { return $null }
    return @([int](([int]$b.Groups[1].Value + [int]$b.Groups[3].Value) / 2),
             [int](([int]$b.Groups[2].Value + [int]$b.Groups[4].Value) / 2))
}

function Scroll-Down {
    Invoke-Adb shell input swipe 540 1700 540 700 400 | Out-Null
    Start-Sleep -Milliseconds 500
}

# A needle that is below the fold does not exist as far as uiautomator is
# concerned: with several accounts in the list the 添加账户 button is off screen,
# and the first draft of this script reported that as "the UI has no add button".
# Every search therefore scrolls as it goes, and gives up after four screens.
function Tap-Needle {
    param([string]$Needle, [string]$Tag, [int]$WaitMs = 900, [int]$Scrolls = 4)
    for ($pass = 0; $pass -le $Scrolls; $pass++) {
        $xml = Dump-Ui ("{0}-p{1}" -f $Tag, $pass)
        $c = Node-Center $xml $Needle
        if ($null -ne $c) {
            Invoke-Adb shell input tap $c[0] $c[1] | Out-Null
            Start-Sleep -Milliseconds $WaitMs
            return $true
        }
        Scroll-Down
    }
    return $false
}

function Long-Press-Needle {
    param([string]$Needle, [string]$Tag, [int]$Scrolls = 4)
    for ($pass = 0; $pass -le $Scrolls; $pass++) {
        $xml = Dump-Ui ("{0}-p{1}" -f $Tag, $pass)
        $c = Node-Center $xml $Needle
        if ($null -ne $c) {
            Invoke-Adb shell input swipe $c[0] $c[1] $c[0] $c[1] 800 | Out-Null
            Start-Sleep -Milliseconds 1200
            return $true
        }
        Scroll-Down
    }
    return $false
}

# The emulator's on-screen keyboard, when it is up, swallows injected text. Measured
# on 2026-10-03: `input text probe` delivered nothing to a plain-text field (and only
# 'p' when typed one character per call), while a password field on the same screen
# received all fourteen characters - the IME mode that ignores key events is the one
# that offers suggestions, so the difference looked like the app dropping input. The
# AVD has a hardware keyboard, so with the soft keyboard disabled every field takes
# injected text the same way, and this stays a test-environment setting rather than a
# claim about the product: a person with a mouse still types into the same fields.
function Get-CurrentIme {
    return ((Invoke-Adb shell settings get secure default_input_method) -join "").Trim()
}

function Keyboard-IsShown {
    $state = Invoke-Adb shell dumpsys input_method
    return ($null -ne ($state | Select-String -Pattern "mDecorViewVisible=true" -SimpleMatch:$false -List))
}

function Disable-SoftKeyboard {
    $script:imeBefore = Get-CurrentIme
    if (-not $script:imeBefore -or $script:imeBefore -eq "null") {
        Note "no soft keyboard selected; nothing to disable"
        return
    }
    Invoke-Adb shell ime disable $script:imeBefore | Out-Null
    Start-Sleep -Milliseconds 500
    Note "soft keyboard was $script:imeBefore; it is disabled for this run and restored in the cleanup"
}

function Restore-SoftKeyboard {
    if (-not $script:imeBefore -or $script:imeBefore -eq "null") { return }
    Invoke-Adb shell ime enable $script:imeBefore | Out-Null
    Invoke-Adb shell ime set $script:imeBefore | Out-Null
    $now = Get-CurrentIme
    if ($now -eq $script:imeBefore) {
        Note "soft keyboard restored to $script:imeBefore"
    } else {
        Note "could not restore the soft keyboard (now $now, was $script:imeBefore)"
    }
}

# The one assertion that tells the rest of the run whether it means anything: if
# typing does not land, every screen-driven check below would fail for a reason that
# has nothing to do with the app.
function Assert-TextInjectionWorks {
    Invoke-Adb shell am force-stop $Package | Out-Null
    Start-Sleep -Seconds 1
    Invoke-Adb shell am start -n $listActivity | Out-Null
    Start-Sleep -Seconds 3
    if (-not (Tap-Needle "添加账户" "canary-open" 2000)) {
        throw "the canary could not open the account form; the run has no working UI channel"
    }
    $xml = Dump-Ui "canary-form"
    $field = Edit-Field $xml 0
    if ($null -eq $field) { throw "the canary found no text field on the account form" }
    Invoke-Adb shell input tap $field[0] $field[1] | Out-Null
    Start-Sleep -Milliseconds 900
    Type-Text "CanaryText"
    $after = Dump-Ui "canary-typed"
    $shown = ($after -like '*text="CanaryText"*')
    Check "injected text reaches a plain-text field on this emulator" $shown `
        "typed CanaryText and the field still shows a hint; the soft keyboard is eating key events (see Disable-SoftKeyboard)"
    Invoke-Adb shell am force-stop $Package | Out-Null
    if (-not $shown) {
        throw "text injection does not work on this emulator; the remaining checks would all fail for that one reason"
    }
}

function Type-Text {
    param([string]$Value)
    Invoke-Adb shell input text $Value | Out-Null
    Start-Sleep -Milliseconds 600
    # The soft keyboard covers the controls below the focused field - but only when
    # there is one. Pressing BACK with no keyboard up pops the activity instead, which
    # is how a run with the keyboard disabled would lose the form it is filling.
    if (Keyboard-IsShown) {
        Invoke-Adb shell input keyevent KEYCODE_BACK | Out-Null
        Start-Sleep -Milliseconds 600
    }
}

function Wait-For-Quota-Line {
    param([string]$Tag, [int]$TimeoutSec = 90, [int]$PollSec = 3)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $xml = ""
    while ($true) {
        $xml = Dump-Ui $Tag
        # 已用 <n>% only exists in a window line the app built from a stored
        # QuotaWindow. Matching the phrase 额度窗口 instead would also match the
        # page's own subtitle, and a screen with no reading at all would pass.
        if ($xml -match "已用 [0-9]+%") { return $xml }
        if ((Get-Date) -gt $deadline) { return $xml }
        Start-Sleep -Seconds $PollSec
    }
}

function Stop-Procs {
    param([bool]$KillApps = $true)
    foreach ($p in $script:procs) {
        try {
            Stop-Process -Id $p.Id -Force -ErrorAction Stop
        } catch {
            # Already exited; that is the expected state by now.
        }
    }
    $script:procs = @()
    if ($KillApps) {
        Invoke-Adb shell am force-stop $Package | Out-Null
    }
}

# Java renders a window as Math.round(usedPercent); PowerShell's [Math]::Round is
# banker's rounding, so 12.5 would compare against 12 here and 13 on screen.
function Screen-Percent {
    param($Value)
    $rounded = [int][Math]::Floor([double]$Value + 0.5)
    if ($rounded -lt 0) { $rounded = 0 }
    if ($rounded -gt 100) { $rounded = 100 }
    return $rounded
}

# ------------------------------------------------------------------- fixtures

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$apk = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"
$bridgeExe = Join-Path $repoRoot "bridge\bin\aiusage-bridge.exe"
$listActivity = "$Package/.ui.account.AccountListActivity"
$emuBase = "http://10.0.2.2"
$wrongToken = "bpToken-wrong-0000"

# The stand-in's own numbers, chosen so they cannot coincide with the live
# Bridge's: a screen that shows 13% and 40% was parsed, not passed through.
$fakeFive = 12.5
$fakeWeek = 40.0

$dbName = "ai_usage_monitor.db"
$sqlite = Join-Path $env:ANDROID_HOME "platform-tools\sqlite3.exe"

function Query-Db {
    param([string]$Path, [string]$Sql)
    $temp = Join-Path $env:TEMP ("bp-" + [Guid]::NewGuid().ToString("N") + ".sql")
    [IO.File]::WriteAllText($temp, ".mode list`n$Sql`n", [System.Text.Encoding]::ASCII)
    $out = cmd /c "`"$sqlite`" -noheader -list `"$Path`" < `"$temp`" 2>&1"
    Remove-Item $temp -Force
    return @($out | Where-Object { "$_" -ne "" } | ForEach-Object { "$_".Trim() })
}

function Pull-Db {
    param([string]$Destination)
    # The app refreshes on a timer, so `cat` of the live file can copy a database
    # mid-write. One run did exactly that and then read numbers that no screen ever
    # showed. Stopping first costs three seconds and makes the snapshot the screen
    # actually produced.
    Invoke-Adb shell am force-stop $Package | Out-Null
    Start-Sleep -Milliseconds 800
    # -wal has to come along: committed rows live there until SQLite checkpoints,
    # and a main file without it is a copy of the past. Frames are checksummed, so
    # a torn trailing frame is ignored rather than trusted.
    foreach ($side in @("", "-wal", "-shm")) {
        cmd /c "adb -s $Serial exec-out run-as $Package cat databases/$dbName$side > `"$Destination$side`"" 2>&1 | Out-Null
        if ($side -ne "" -and (Test-Path "$Destination$side") `
                -and (Get-Item "$Destination$side").Length -eq 0) {
            Remove-Item "$Destination$side" -Force
        }
    }
    if (-not (Test-Path $Destination)) { throw "pull failed: $Destination" }
    $bytes = (Get-Item $Destination).Length
    if ($bytes -lt 10240) { throw "pulled only $bytes bytes; that is not the app database" }
    return $bytes
}

function Account-Id {
    param([string]$DbPath, [string]$Name)
    return (Scalar-Db $DbPath "SELECT id FROM accounts WHERE display_name='$Name';")
}

# Empty results used to index [0] and abort the run halfway, which threw away every
# check after it. An empty string fails the Check that reads it and the run goes on.
function Scalar-Db {
    param([string]$Path, [string]$Sql)
    $rows = @(Query-Db $Path $Sql)
    if ($rows.Count -eq 0) { return "" }
    return "$($rows[0])"
}

$fakeServer = ""

function New-QuotaStandIn {
    param([string]$Path, [string]$Port, [string]$Bearer, [double]$Five, [double]$Week)
    $py = @"
import json, sys, time
from http.server import BaseHTTPRequestHandler, HTTPServer

PORT = int(sys.argv[1])
BEARER = sys.argv[2]
FIVE = float(sys.argv[3])
WEEK = float(sys.argv[4])
now_ms = int(time.time() * 1000)
BODY = json.dumps({
    "account_id": "stand-in",
    "plan_type": "plus",
    "generated_at_millis": now_ms,
    "state": {
        "windows": [
            {"id": "primary", "label": "5 \u5c0f\u65f6", "usedPercent": FIVE,
             "remainingPercent": 100 - FIVE, "windowMinutes": 300,
             "resetAtMillis": now_ms + 2 * 3600 * 1000},
            {"id": "secondary", "label": "7 \u5929", "usedPercent": WEEK,
             "remainingPercent": 100 - WEEK, "windowMinutes": 10080,
             "resetAtMillis": now_ms + 3 * 86400 * 1000},
        ]
    },
}, ensure_ascii=False).encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/v1/health":
            self._send(200, b'{"ok":true}')
            return
        if self.headers.get("Authorization") == "Bearer " + BEARER:
            self._send(200, BODY)
        else:
            self._send(401, b'{"error":"unauthorized"}')

    def _send(self, code, payload):
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, fmt, *args):
        pass


HTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
"@
    [IO.File]::WriteAllText($Path, $py.Replace("`r`n", "`n"), (New-Object System.Text.UTF8Encoding($false)))
}

try {
    Write-Output "== building the app and the Bridge =="
    Push-Location $repoRoot
    try {
        cmd /c "gradlew.bat :app:assembleDebug --offline -q" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "gradle assembleDebug failed (exit $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
    Push-Location (Join-Path $repoRoot "bridge")
    try {
        & "C:\Program Files\Go\bin\go.exe" build -o bin\aiusage-bridge.exe .\cmd\aiusage-bridge
        if ($LASTEXITCODE -ne 0) { throw "go build failed (exit $LASTEXITCODE)" }
    } finally {
        Pop-Location
    }
    if (-not (Test-Path $bridgeExe)) { throw "bridge exe missing at $bridgeExe" }
    if (-not (Test-Path $sqlite)) { throw "sqlite3.exe not found at $sqlite" }

    $state = (Invoke-Adb get-state) -join " "
    if ($state -notlike "*device*") { throw "no emulator on $Serial (adb get-state said: $state)" }
    Invoke-Adb install -r $apk | Out-Null
    Disable-SoftKeyboard

    $script:work = Join-Path $env:TEMP ("bridge-phone-" + (Get-Date -Format "yyyyMMddHHmmss"))
    New-Item -ItemType Directory -Path $script:work | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $script:work "dumps") | Out-Null
    $bridgeData = Join-Path $script:work "bridge"
    New-Item -ItemType Directory -Path $bridgeData | Out-Null
    $dbPath = Join-Path $script:work "phone.db"

    # Recorded before anything runs, checked after: the two DeepSeek accounts and
    # their credentials must survive this script byte-identical, because their
    # keys exist nowhere else.
    $before = Join-Path $script:work "before.db"
    $null = Pull-Db $before
    $deepSeekBaseline = (Query-Db $before "SELECT id || '=' || credential_id FROM accounts WHERE provider_id='deepseek' ORDER BY id;") -join ","
    Check "the DeepSeek accounts this script must not touch are present" `
        (($deepSeekBaseline -split ",").Count -ge 2) "found: $deepSeekBaseline"

    $fakeServer = Join-Path $script:work "quota_stand_in.py"
    New-QuotaStandIn -Path $fakeServer -Port $FakePort -Bearer $Token -Five $fakeFive -Week $fakeWeek

    Note "== starting the Bridge (loopback only) and the quota stand-in =="
    $bridge = Start-Process -FilePath $bridgeExe `
        -ArgumentList @("--port", "$BridgePort", "--data-dir", "$bridgeData") `
        -PassThru -WindowStyle Hidden
    $script:procs += $bridge
    $fake = Start-Process -FilePath "python" `
        -ArgumentList @("`"$fakeServer`"", "$FakePort", "`"$Token`"", "$fakeFive", "$fakeWeek") `
        -PassThru -WindowStyle Hidden
    $script:procs += $fake

    function Wait-Url {
        param([string]$Url, [int]$Tries = 40)
        for ($i = 0; $i -lt $Tries; $i++) {
            try {
                $r = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 2
                if ([int]$r.StatusCode -eq 200) { return $true }
            } catch {
                Start-Sleep -Milliseconds 400
            }
        }
        return $false
    }

    Check "the Bridge answers on the host loopback" (Wait-Url "http://127.0.0.1:$BridgePort/v1/health")
    Check "the stand-in answers on the host loopback" (Wait-Url "http://127.0.0.1:$FakePort/v1/health")

    # The host's own Codex needs the internet the phone never touches, and it
    # fails from time to time: measured during this phase, three 503s reading
    # "failed to fetch codex rate limits" and then a normal answer about twenty
    # seconds later. Retrying is the honest response; blaming the app for the
    # host's network is not. A run that never gets a number still cannot judge
    # C1/C2, so it stops rather than passing on nothing.
    $doc = $null
    for ($attempt = 1; $attempt -le 6; $attempt++) {
        try {
            $usage = Invoke-WebRequest -UseBasicParsing `
                -Uri "http://127.0.0.1:$BridgePort/v1/accounts/codex/usage" -TimeoutSec 90
            $doc = $usage.Content | ConvertFrom-Json
            break
        } catch {
            $detail = "$($_.ErrorDetails.Message)" -replace "\s+", " "
            if ($detail.Length -gt 150) { $detail = $detail.Substring(0, 150) }
            Note "the Bridge has no reading yet (attempt $attempt): $detail"
            Start-Sleep -Seconds 6
        }
    }
    if ($null -eq $doc) { throw "the Bridge never produced a reading, so C1/C2 cannot be judged" }
    $five = $null
    $week = $null
    foreach ($w in $doc.state.windows) {
        if ([int]$w.windowMinutes -eq 300) { $five = $w }
        if ([int]$w.windowMinutes -eq 10080) { $week = $w }
    }
    Check "the Bridge reports both windows to any client" ($null -ne $five -and $null -ne $week) `
        "windows=$($doc.state.windows.Count)"

    # ------------------------------------------------------------- form driver

    function New-CodexAccount {
        param([string]$Name, [string]$Address, [string]$WithToken)
        Invoke-Adb shell am force-stop $Package | Out-Null
        Start-Sleep -Seconds 1
        Invoke-Adb shell am start -n $listActivity | Out-Null
        Start-Sleep -Seconds 3
        if (-not (Tap-Needle "添加账户" "open-list" 1800)) { return $false }
        $xml = Dump-Ui "form-open"
        $field = Edit-Field $xml 0
        if ($null -eq $field) { return $false }
        Invoke-Adb shell input tap $field[0] $field[1] | Out-Null
        Start-Sleep -Milliseconds 900
        Type-Text $Name
        if (-not (Tap-Needle 'content-desc="服务商 OpenAI Codex"' "chip-codex" 1400)) { return $false }
        $xml = Dump-Ui "form-bridge"
        # After the Codex chip the key box is gone, so field 1 is the address and
        # field 2 the token. Confirmed below by the text the address holds.
        $urlField = Edit-Field $xml 1
        if ($null -eq $urlField) { return $false }
        Invoke-Adb shell input tap $urlField[0] $urlField[1] | Out-Null
        Start-Sleep -Milliseconds 900
        Type-Text $Address
        $tokenField = Edit-Field (Dump-Ui "form-token") 2
        if ($null -eq $tokenField) { return $false }
        Invoke-Adb shell input tap $tokenField[0] $tokenField[1] | Out-Null
        Start-Sleep -Milliseconds 900
        Type-Text $WithToken
        $xml = Dump-Ui "form-filled"
        if ($xml -notlike ('*' + 'text="' + $Address + '"*')) {
            Note "$Name : the address field never held $Address"
            return $false
        }
        # Scroll until the save button is on screen before looking for it. Step 9 put
        # 「与电脑配对（推荐）」 and the 「或直接手输地址（调试通道）」 label into the same card,
        # so on this device the 保存 button sits below the fold - and uiautomator does not
        # report the children of a ScrollView that are outside the viewport at all. Measured
        # in run 20's dumps/29-form-filled.xml: the name, the address (http://10.0.2.2:38481)
        # and the token (bullets) are all filled in, and `content-desc="保存"` is simply not
        # in the file, so the old single dump looked up a node that was never going to be
        # there and every C row after it died with "no such account". A human scrolls; so must
        # the harness, and if scrolling cannot find it that is said out loud.
        $save = Node-Center $xml 'content-desc="保存"'
        $scrolls = 0
        while ($null -eq $save -and $scrolls -lt 4) {
            Scroll-Down; Start-Sleep -Milliseconds 500
            $scrolls++
            $xml = Dump-Ui ("save-scroll-{0}" -f $scrolls)
            $save = Node-Center $xml 'content-desc="保存"'
        }
        if ($null -eq $save) {
            Note "$Name : the form is filled but no 保存 button appeared after $scrolls scrolls"
            return $false
        }
        Invoke-Adb shell input tap $save[0] $save[1] | Out-Null
        Start-Sleep -Seconds 2
        return $true
    }

    function Open-Account {
        param([string]$Name, [string]$Tag)
        Invoke-Adb shell am force-stop $Package | Out-Null
        Start-Sleep -Seconds 1
        Invoke-Adb shell am start -n $listActivity | Out-Null
        Start-Sleep -Seconds 3
        if (-not (Tap-Needle ('content-desc="' + $Name + '"') "row-$Tag" 1500)) { return "" }
        Start-Sleep -Seconds 3
        # The detail page refreshes on its own; tapping the button as well costs
        # nothing (a running query ignores it) and covers the case where the gate
        # decides not to fire, which is exactly the defect this run exists to pin.
        Tap-Needle "查询额度" "tap-query-$Tag" 1500 | Out-Null
        return (Dump-Ui "detail-$Tag")
    }

    function Show-List {
        param([string]$Tag)
        Invoke-Adb shell am force-stop $Package | Out-Null
        Start-Sleep -Seconds 1
        Invoke-Adb shell am start -n $listActivity | Out-Null
        Start-Sleep -Seconds 4
        return (Dump-Ui "list-$Tag")
    }

    function Wait-For-Quota {
        param([string]$Name, [string]$Tag, [int]$TimeoutSec = 75)
        $deadline = (Get-Date).AddSeconds($TimeoutSec)
        $xml = Open-Account -Name $Name -Tag $Tag
        while ($true) {
            if ($xml -match "已用 [0-9]+%") { return $xml }
            if ((Get-Date) -gt $deadline) { return $xml }
            Start-Sleep -Seconds 4
            $xml = Wait-For-Quota-Line -Tag "wait-$Tag" -TimeoutSec 20
        }
    }

    # -------------------------------------------------- C1 / C2 / C6 / C9 with
    # -------------------------------------------------- the live Bridge and the
    # -------------------------------------------------- stand-in's own numbers

    $accountName = "BridgePhone"
    $okName = "BridgePhoneOk"
    $authName = "BridgePhone401"
    $lanName = "BridgePhoneLan"

    function Remove-Account {
        param([string]$Name, [string]$Tag)
        Invoke-Adb shell am force-stop $Package | Out-Null
        Start-Sleep -Seconds 1
        Invoke-Adb shell am start -n $listActivity | Out-Null
        Start-Sleep -Seconds 3
        if (-not (Long-Press-Needle ('content-desc="' + $Name + '"') "menu-$Tag")) { return $false }
        if (-not (Tap-Needle 'text="删除账户"' "menuitem-$Tag" 1200)) {
            Invoke-Adb shell input keyevent KEYCODE_BACK | Out-Null
            return $false
        }
        # Exact text, quoted: the dialog's title is 删除账户, which is not
        # clickable, and a substring match on 删除 would tap the title and delete
        # nothing while still reporting success.
        Tap-Needle 'text="删除"' "confirm-$Tag" 2500 | Out-Null
        return $true
    }

    # A run that died before its cleanup leaves accounts behind, and those leftovers
    # both push 添加账户 below the fold and let a name resolve to two rows - after
    # which "the quota on screen matches this account" proves nothing. Delete every
    # duplicate first, then let the database say whether that worked.
    Note "== removing Codex accounts left by earlier runs =="
    foreach ($name in @($accountName, $okName, $authName, $lanName)) {
        $rounds = 0
        while ($rounds -lt 6 -and (Remove-Account -Name $name -Tag "pre-$rounds")) {
            $rounds++
        }
    }
    $null = Pull-Db $dbPath
    $mineLeft = Scalar-Db $dbPath ("SELECT count(*) FROM accounts WHERE display_name IN ('" `
        + $accountName + "','" + $okName + "','" + $authName + "','" + $lanName + "');")
    Check "no Codex account from an earlier run is left to confuse this one" `
        ($mineLeft -eq "0") "rows still named after this script's accounts: $mineLeft"
    # Counted after the pre-clean, which is allowed to remove rows; "before" from
    # the very start of the run would blame this script for the last one's mess.
    $accountCountBaseline = (Query-Db $dbPath "SELECT count(*) FROM accounts;") -join ""

    Assert-TextInjectionWorks

    Note "== C1 / C2 / C9 against the live Bridge =="
    Check "a Codex account can be added through the UI" `
        (New-CodexAccount -Name $accountName -Address "$emuBase`:$BridgePort" -WithToken $Token)
    $detail = Wait-For-Quota -Name $accountName -Tag "c1"
    Check "C1 the detail screen shows the quota block" ($detail -match "已用 [0-9]+%") `
        "no rendered window line on the screen after opening $accountName"
    Check "C1 the reading replaced the placeholder rather than sitting beside it" `
        (-not ($detail -like "*等待读取额度窗口*"))
    Check "C1 both windows are named from the source, not hard-coded" `
        (($detail -like "*5 小时*") -and ($detail -like "*7 天*"))
    Check "C1 the account reads as available" ($detail -like "*账户可用*") `
        "MainActivity defaults to 账户不可用 when a result omits is_available"
    Check "C1 the button offers to read quota, not a balance" ($detail -like "*查询额度*")
    Check "C1 the screen shows the Bridge address it is calling" `
        ($detail -like ("*text=`"" + $emuBase + ":" + $BridgePort + "`"*"))
    Check "C9 a Codex screen never mentions api.deepseek.com" (-not ($detail -match "api\.deepseek\.com"))
    Check "C9 the DeepSeek platform button is hidden for Codex" (-not ($detail -like "*DeepSeek 开放平台*"))
    Check "C9 no API KEY box, so no DeepSeek key can be typed here" `
        (($detail -notlike "*API KEY*") -and ($detail -notlike "*记住密钥*"))
    Check "C9 the caption stopped promising a CNY reading" (-not ($detail -like "*CNY 余额*"))
    # A6/R5: an account with no money must not read as an account that spent
    # nothing today. "0.00" on this screen is wrong information, not missing it.
    Check "A6 the quota account shows a dash rather than 0.00" `
        (($detail -like '*text="—"*') -and (-not ($detail -like "*0.00*"))) `
        "the balance area showed a number the Bridge never reported"

    $fivePercent = Screen-Percent $five.usedPercent
    $weekPercent = Screen-Percent $week.usedPercent
    Check "C2 the screen shows the same 5-hour percentage as the Bridge" `
        ($detail -like ("*已用 " + $fivePercent + "%*")) "expected 已用 $fivePercent%"
    Check "C2 the screen shows the same weekly percentage as the Bridge" `
        ($detail -like ("*已用 " + $weekPercent + "%*")) "expected 已用 $weekPercent%"
    Check "C2 reset times are phrased, not left as raw numbers" `
        (($detail -like "*重置*") -and ($detail -notlike "*resetAtMillis*"))

    Note "== C-A4 / C7: the token has to reach the header, and nothing else =="
    $null = Invoke-Adb logcat -c
    Check "an account can be pointed at the stand-in" `
        (New-CodexAccount -Name $okName -Address "$emuBase`:$FakePort" -WithToken $Token)
    $detailOk = Wait-For-Quota -Name $okName -Tag "c-accept"
    # The stand-in answers 200 only for the header it was started with, so seeing
    # its own numbers proves the token left the phone inside Authorization. If the
    # token were dropped, the same request would read 401 and say so.
    Check "A4 the right token is accepted by a server that asks for it" `
        (($detailOk -like ("*已用 " + (Screen-Percent $fakeFive) + "%*")) -and
         ($detailOk -like ("*已用 " + (Screen-Percent $fakeWeek) + "%*"))) `
        "expected the stand-in's $(Screen-Percent $fakeFive)% / $(Screen-Percent $fakeWeek)% numbers"
    Check "A4 the parsed number is rounded the way the app rounds it" `
        ($detailOk -like ("*已用 " + (Screen-Percent $fakeFive) + "%*")) "12.5 must render as 13"

    # One quoted argument on purpose: as separate tokens, "-v" binds to
    # Invoke-Adb itself and logcat silently falls back to threadtime.
    $logLines = @(Invoke-Adb logcat "-v brief -d -t 6000")
    $hits = @($logLines | Where-Object { $_ -like "*$Token*" })
    # adbd logs the shell command it was handed, and this script hands it
    # `input text <token>` - so the token appears exactly once, in a line the
    # harness itself caused. Everything that is not adbd is the product talking,
    # and the product must never say it. Split rather than filtered: a grep that
    # simply dropped adbd would also drop a real leak that happened to share a
    # prefix with one.
    $adbd = @($hits | Where-Object { $_ -match "^\w/adbd\s" })
    $others = @($hits | Where-Object { $_ -notmatch "^\w/adbd\s" })
    $leakSample = ""
    if ($others.Count -gt 0) { $leakSample = "$($others[0])" }
    Check "C7 no process other than this script's own typing logs the token" `
        ($others.Count -eq 0) $leakSample
    Check "C7 the harness's own typing is the only appearance" `
        ($adbd.Count -eq $hits.Count -and $hits.Count -le 4) "hits=$($hits.Count) adbd=$($adbd.Count)"
    $dumpLeak = $false
    foreach ($dump in @(Get-ChildItem (Join-Path $script:work "dumps") -Filter *.xml)) {
        if (([IO.File]::ReadAllText($dump.FullName, [System.Text.Encoding]::UTF8)) -like "*$Token*") {
            $dumpLeak = $true
            Note "C7 the token appeared in $($dump.Name)"
        }
    }
    Check "C7 the token never reaches the rendered UI (uiautomator dumps)" (-not $dumpLeak)
    $logSites = 0
    foreach ($f in @(
        "app\src\main\java\com\aiusage\monitor\util\Http.java",
        "app\src\main\java\com\aiusage\monitor\util\HttpBridgeTransport.java",
        "app\src\main\java\com\aiusage\monitor\util\BridgeTransport.java",
        "app\src\main\java\com\aiusage\monitor\provider\codex\BridgeCodexDataSource.java",
        "app\src\main\java\com\aiusage\monitor\auth\BridgeAuthAdapter.java")) {
        $text = Get-Content (Join-Path $repoRoot $f) -Raw
        $logSites += ([regex]::Matches($text, "Log\.|System\.out|println")).Count
    }
    Check "C7 the credential path contains no logging call to leak through" ($logSites -eq 0) "$logSites site(s)"

    Note "== C6: the reading must survive the app being killed =="
    $again = Open-Account -Name $accountName -Tag "c6"
    Check "C6 quota comes back from storage after a restart" `
        (($again -match "已用 [0-9]+%") -and ($again -like "*7 天*"))

    # ------------------------------------------------------------- C3 / C4b
    Note "== C3: a failed refresh must not erase the last good reading =="
    Stop-Process -Id $bridge.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 1
    $detail3 = Open-Account -Name $accountName -Tag "c3-offline"
    if ($detail3 -notlike "*无法连接电脑端 Bridge*") {
        # One more chance: the first attempt may have landed while the process was
        # still dying, and a cached page would hide the failure rather than prove
        # the reading survived.
        Start-Sleep -Seconds 6
        $detail3 = Dump-Ui "c3-offline-2"
    }
    Check "C3 the failure names the computer side as unreachable" `
        ($detail3 -like "*无法连接电脑端 Bridge*") "screen held no offline wording"
    Check "C3 rule 19: offline is not described as an authorisation problem" `
        (-not ($detail3 -like "*拒绝了这个令牌*"))

    $null = Pull-Db $dbPath
    $codexId = Account-Id $dbPath $accountName
    Check "C3 the Codex account can be found by name in the database" ($codexId -ne "")
    # Two rows with one name would let every scoped query below read the wrong
    # account's history, which is the mistake the first draft of this file made by
    # not scoping at all.
    $sameName = Scalar-Db $dbPath "SELECT count(*) FROM accounts WHERE display_name='$accountName';"
    Check "exactly one account carries the name this run queries" ($sameName -eq "1") "rows named ${accountName}: $sameName"
    $okRows = Scalar-Db $dbPath "SELECT count(*) FROM usage_snapshots WHERE account_id='$codexId' AND success=1;"
    Check "C3 rule 18: successful readings for THIS account are still stored" `
        ([int]$okRows -ge 1) "success rows for $codexId = $okRows (a global count would always pass)"
    $lastGood = (Query-Db $dbPath "SELECT usage_data FROM usage_snapshots WHERE account_id='$codexId' AND success=1 ORDER BY timestamp DESC, id DESC LIMIT 1;") -join ""
    Check "C3 the last good reading still holds its quota windows" ($lastGood -like "*quotaWindows*") `
        "the newest success row had no windows"
    $latestStatus = Scalar-Db $dbPath "SELECT json_extract(usage_data,'`$.status') FROM usage_snapshots WHERE account_id='$codexId' ORDER BY timestamp DESC, id DESC LIMIT 1;"
    Check "C3 the newest row for this account is the failed attempt" `
        ($latestStatus -like "*BRIDGE_OFFLINE*") "status=$latestStatus"
    $failSource = Scalar-Db $dbPath "SELECT source FROM usage_snapshots WHERE account_id='$codexId' ORDER BY timestamp DESC, id DESC LIMIT 1;"
    Check "C4b a Codex failure is filed as BRIDGE, not DIRECT_API" ($failSource -eq "BRIDGE") "source=$failSource"
    # Rule 18's visible half: the row says it is showing retained data. A name
    # label that is always on screen ("最近读数") would pass under any
    # implementation, so the assertion is on the suffix StatusWords only adds when
    # a failure followed a success.
    $listC3 = Show-List "c3"
    Check "C3 rule 18: the list says the computer side is unreachable" `
        ($listC3 -like "*Bridge 未连接*") "no offline wording on the list"
    Check "C3 rule 18: the list marks the row as retained last-success data" `
        ($listC3 -like "*最后成功数据*") "the row did not claim to retain anything"

    # ------------------------------------------------------------- C4 / rule 19
    Note "== C4: a refused token is not an offline computer =="
    $null = Invoke-Adb shell am force-stop $Package
    Check "an account can be pointed at the stand-in with the wrong token" `
        (New-CodexAccount -Name $authName -Address "$emuBase`:$FakePort" -WithToken $wrongToken)
    $detail4 = Open-Account -Name $authName -Tag "c4-auth"
    if ($detail4 -notlike "*拒绝了这个令牌*") {
        Start-Sleep -Seconds 6
        $detail4 = Dump-Ui "c4-auth-2"
    }
    Check "C4 a refused token says the computer side refused this token" `
        ($detail4 -like "*拒绝了这个令牌*") "screen held no token-refusal wording"
    Check "C4 rule 19: it is worded differently from the offline case" `
        (($detail4 -like "*拒绝了这个令牌*") -and ($detail3 -like "*无法连接电脑端 Bridge*") -and
         -not ($detail4 -like "*无法连接电脑端 Bridge*"))
    Check "C4 and rule 19: it never says API Key" (-not ($detail4 -like "*API Key*"))

    $null = Pull-Db $dbPath
    $authId = Account-Id $dbPath $authName
    $authStatus = Scalar-Db $dbPath "SELECT json_extract(usage_data,'`$.status') FROM usage_snapshots WHERE account_id='$authId' ORDER BY timestamp DESC, id DESC LIMIT 1;"
    Check "C4 rule 19: the two failures are stored as two different statuses" `
        (($authStatus -eq "BRIDGE_AUTH_REQUIRED") -and ($latestStatus -eq "BRIDGE_OFFLINE")) `
        "auth=$authStatus offline=$latestStatus"
    $listXml = Show-List "c4"
    Check "C4 rule 19: one list, two different wordings for the two failures" `
        (($listXml -like "*Bridge 未连接*") -and ($listXml -like "*电脑端授权已失效*")) `
        "the two failure kinds are not distinguishable on the list"
    Check "C4 the list never reaches for DeepSeek's key wording" (-not ($listXml -like "*API Key 无效*"))

    # ---------------------------------------------------------------- C8 policy
    $lanIp = $null
    try {
        $lanIp = (Get-NetIPAddress -AddressFamily IPv4 |
            Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } |
            Select-Object -First 1).IPAddress
    } catch {
        Note "Get-NetIPAddress unavailable; C8 cannot be judged"
    }
    if ($lanIp) {
        # The TCP-level probe is what keeps C8 honest: if the emulator cannot open
        # a socket to the host LAN address at all, then "the request failed" would
        # prove nothing about the cleartext allowlist and must not be scored.
        $probe = (Invoke-Adb shell "nc -z -w 2 $lanIp $FakePort; echo rc=`$?") -join " "
        if ($probe -notlike "*rc=*") {
            Note "C8 not scored: the device has no nc to prove ${lanIp}:$FakePort is reachable"
        } elseif ($probe -like "*rc=0*") {
            Check "an account can be pointed at the host LAN address" `
                (New-CodexAccount -Name $lanName -Address "http://$lanIp`:$FakePort" -WithToken $Token)
            $detail8 = Open-Account -Name $lanName -Tag "c8-lan"
            if ($detail8 -notlike "*额度窗口*") {
                Start-Sleep -Seconds 6
                $detail8 = Dump-Ui "c8-lan-2"
            }
            # Same server, same token that C-A4 just proved works - only the host
            # differs. Reaching it would mean the allowlist scopes nothing.
            Check "C8 the same server is refused off the allowlist, though the port is open and the token right" `
                (-not ($detail8 -match "已用 [0-9]+%")) `
                "the device read quota from a non-allowlisted cleartext host"
            Check "C8 the refusal is named by the platform, not inferred from a blank screen" `
                ($detail8 -like "*Cleartext HTTP traffic*not permitted*") `
                "no cleartext-policy message on screen"
        } else {
            Note "C8 not scored: the emulator cannot open a TCP socket to ${lanIp}:$FakePort, so a failure there would prove nothing about the policy"
        }
    }

    # ------------------------------------------------------------------- C5
    if (-not $SkipLegacy) {
        Note "== C5: the DeepSeek half must not have regressed =="
        foreach ($legacy in @("assert-two-live-accounts.ps1", "assert-last-success.ps1", "assert-daily-usage.ps1")) {
            $path = Join-Path $PSScriptRoot $legacy
            $log = Join-Path $script:work $legacy
            cmd /c "powershell -NoProfile -ExecutionPolicy Bypass -File `"$path`" > `"$log`" 2>&1" | Out-Null
            $exit = $LASTEXITCODE
            $child = ""
            if (Test-Path $log) { $child = Get-Content $log -Raw }
            $verdict = ""
            foreach ($line in @($child -split "`n")) {
                if ($line -like "RESULT:*") { $verdict = $line.Trim() }
            }
            Check "C5 $legacy still passes" `
                (($exit -eq 0) -and ($verdict -like "*PASS*")) `
                "exit=$exit verdict='$verdict'; see $log"
        }
    } else {
        Note "C5 skipped by -SkipLegacy"
    }

    # ---------------------------------------------------------------- cleanup
    Note "== removing the accounts this script created =="
    foreach ($name in @($accountName, $okName, $authName, $lanName)) {
        $rounds = 0
        while ($rounds -lt 3 -and (Remove-Account -Name $name -Tag "del-$rounds")) {
            $rounds++
        }
        $null = Pull-Db $dbPath
        $left = Scalar-Db $dbPath "SELECT count(*) FROM accounts WHERE display_name='$name';"
        Check "the script's own account $name is gone from the database" ($left -eq "0") "rows left=$left"
    }

    $after = Join-Path $script:work "after.db"
    $null = Pull-Db $after
    $deepSeekAfter = (Query-Db $after "SELECT id || '=' || credential_id FROM accounts WHERE provider_id='deepseek' ORDER BY id;") -join ","
    Check "the DeepSeek accounts and their credential ids are untouched" `
        ($deepSeekAfter -eq $deepSeekBaseline) "before=$deepSeekBaseline after=$deepSeekAfter"
    $accountCountAfter = Scalar-Db $after "SELECT count(*) FROM accounts;"
    Check "no other account disappeared" `
        ($accountCountAfter -eq $accountCountBaseline) `
        "after the pre-clean there were $accountCountBaseline accounts, now $accountCountAfter"
} finally {
    Restore-SoftKeyboard
    Stop-Procs
    if (Test-Path $script:work) {
        Note ("evidence kept in " + $script:work)
    }
}

Write-Output ""
Write-Output "== summary: $script:checks checks, $script:failures failed =="
if ($script:failures -gt 0) { exit 1 }
