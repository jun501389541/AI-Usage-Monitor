<#
    Phase 7 pairing acceptance (docs/PHASE-7-PLAN.md §5.3, rows P-1…P-11).

    What this proves and what it does not

    A pairing that only works in unit tests has never met a certificate. This run
    starts the real Go Bridge on a real socket, hands a real offer to the app on the
    emulator, and then reads the app's own database and the Bridge's own log to check
    what both sides actually did. Every row states how it could fail, and rows that
    cannot run here are reported as SKIPPED with the reason rather than passed: nine
    green ticks that were never attempted are worse than one honest gap, because they
    teach the next reader to trust the list.

    What it does to the machine

      - starts the Bridge with --pair and the address given by -BridgeHost (default
        10.0.0.0-form LAN bind is NOT chosen for you; pass it explicitly) on
        -BridgePort, with a temporary --data-dir under this repo's build directory;
      - installs app/build/outputs/apk/debug/app-debug.apk over the existing app, so
        the accounts, keys and widgets already on the device stay there;
      - creates Codex accounts through pairing and deletes the ones it made. It never
        touches the two real DeepSeek accounts: their ids and credential ids are
        recorded at the start and re-checked at the end;
      - reads the app database through `run-as` (copy out, never write), including the
        -wal sidecar, because a committed row can still be sitting there;
      - disables the emulator's soft keyboard for the duration (that IME mode eats
        injected keystrokes; see assert-bridge-phone.ps1) and restores it after.

    Usage:
        powershell -File tools/smoke/assert-bridge-pair.ps1 -BridgeHost 28.0.0.1
        powershell -File tools/smoke/assert-bridge-pair.ps1 -SkipBridgeBuild

    Without -BridgeHost the phone-side rows cannot be judged: the emulator reaches the
    host's loopback as 10.0.2.2, but a Bridge bound to 127.0.0.1 does not answer
    there, so the run says so and stops rather than inventing a pass.
#>
param(
    [string]$Serial = "emulator-5554",
    [string]$Package = "com.aiusage.monitor",
    [int]$BridgePort = 38491,
    [string]$BridgeHost = "",
    [string]$PairTtl = "120s",
    [string]$BridgeExePath = "",
    [int]$LegacyTimeoutSec = 3600,
    [int]$BindTimeoutSec = 1800,
    [switch]$SkipBridgeBuild,
    [switch]$SkipLegacy,
    [switch]$SelfTest
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\..\env.ps1"
# Row names stay ASCII: a redirected console renders Chinese under the machine's code
# page, and a mangled PASS line in an evidence file reads like a broken assertion.
# Measured, not assumed: run 12 wrote `the revoked account offers  on its own row` to
# its evidence file even with the two lines below in place, because PowerShell 5.1's
# console encoding only governs a real console - with stdout redirected to a file the
# write goes out in the machine code page. So the rows are ASCII; the needles are not.
try {
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }

$script:checks = 0
$script:failures = 0
$script:skips = 0
$script:skipReasons = @{}
$script:procs = @()
$script:dumpSeq = 0
$script:uiBroken = $false
$script:uiBrokenReason = ""
$script:work = ""
$script:deviceReady = $false
$script:bridgeExe = ""
$script:dataDir = ""
$script:shortDir = ""
$script:shortPort = 0
$script:guardDir = ""
$script:guardPort = 0
$script:decoyDir = ""
$script:decoyPort = 0
$script:stdoutLog = ""
$script:dbName = "ai_usage_monitor.db"
$script:sqlite = ""
$script:dbPath = ""
$script:pairAccounts = @{}
$script:pairBridges = @{}
$script:manifestPath = ""
$script:bridgesScreen = $false
$script:deepseekBaseline = ""
$script:imeBefore = ""
$script:imeEnabled = @()

function Check {
    param([string]$Name, [bool]$Ok, [string]$Detail = "")
    if ($script:uiBroken -and (-not $Ok) -and ($Name -match '^P-\d')) {
        # Only a red phone row can be re-attributed, and only once the dead-guest signature
        # has actually been seen. A green row is still reported green - it happened.
        Skip "$Name" $script:uiBrokenReason
        return
    }
    $script:checks++
    if ($Ok) {
        Write-Host "PASS  $Name"
    } else {
        $script:failures++
        if ($Detail) { Write-Host "FAIL  $Name -- $Detail" } else { Write-Host "FAIL  $Name" }
    }
}

# A row that could not be attempted. Not a pass, and deliberately not a failure
# either: it is counted, named and reported, so the summary cannot read as clean.
function Skip {
    param([string]$Name, [string]$Why)
    $script:skips++
    $script:skipReasons[$Name] = $Why
    Write-Host "SKIP  $Name -- $Why"
}

function Note {
    param([string]$Text)
    Write-Host "NOTE  $Text"
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$AdbArgs)
    cmd /c "adb -s $Serial $($AdbArgs -join ' ') 2>&1"
}

function Note-GuestUiHealth {
    param([string]$Dump, [string]$Tag)
    # Run 22 lost twelve rows to the guest, not to the app. `logcat -b events` shows
    # 10-04 01:30:31.926 `am_anr ... com.google.android.apps.nexuslauncher ... Input
    # dispatching timed out` and two seconds later the same for com.android.systemui, and
    # every dump from then on is a 4464-byte window whose only package is "android" - the
    # ANR sheet, with no app node anywhere in it. Reading that as twelve product failures is
    # precisely the misleading log this project refuses, so the signature is named once and
    # later red phone rows become NOT JUDGED.
    # Deliberately narrow: an app that crashed while the system is healthy still shows its
    # own package in other windows and must stay FAIL, which is what the second condition
    # and the -SelfTest rows exist to keep honest.
    if ($script:uiBroken) { return }
    if (-not $Dump -or $Dump.Length -lt 400) { return }
    if ($Dump.Contains('package="' + $Package + '"')) { return }
    if ($Dump -notmatch 'package="android"' -and $Dump -notmatch 'Application Not Responding') { return }
    $script:uiBroken = $true
    $script:uiBrokenReason = ("the guest UI showed no window of $Package from dump '$Tag' on; that " +
        "dump is system-owned only (ANR sheet or dead SystemUI/launcher), so the device is not " +
        "answering rather than the app misbehaving")
    Note "GUEST UI UNRESPONSIVE: $script:uiBrokenReason - remaining failing phone rows report NOT JUDGED"
}

function Dump-Ui {
    param([string]$Tag)
    $script:dumpSeq++
    $local = Join-Path $script:work ("dumps\{0:d2}-{1}.xml" -f $script:dumpSeq, $Tag)
    # `uiautomator dump` fails outright while a window is still animating ("could not
    # get idle state"), and it prints the reason rather than an empty file. Retrying
    # three times with the reason on screen is what makes "the needle was not there"
    # mean the app did not show it, instead of meaning the harness blinked (measured
    # 2026-10-03, when one such failure aborted a whole run at its own canary).
    for ($try = 1; $try -le 3; $try++) {
        Invoke-Adb shell rm -f /sdcard/bp.xml | Out-Null
        $reason = ((Invoke-Adb shell uiautomator dump /sdcard/bp.xml) -join " ").Trim()
        Invoke-Adb pull /sdcard/bp.xml $local 2>&1 | Out-Null
        if ((Test-Path $local) -and ((Get-Item $local).Length -gt 400)) {
            $text = [System.IO.File]::ReadAllText($local, [System.Text.Encoding]::UTF8)
            Note-GuestUiHealth -Dump $text -Tag $Tag
            return $text
        }
        Note "dump '$Tag' attempt $try got no hierarchy ($reason)"
        Start-Sleep -Milliseconds 800
    }
    Note "dump '$Tag' produced nothing; the check below sees an empty screen"
    return ""
}

function Node-Center {
    param([string]$Xml, [string]$Needle)
    # A clickable node first, and only then any node that carries the text. Without
    # the preference a dialog's title steals the tap from the button that shares its
    # wording, which looks exactly like "the button did nothing" (assert-bridge-phone
    #.ps1 documents the same trap for 删除账户).
    $fallback = $null
    foreach ($m in [regex]::Matches($Xml, '<node[^>]*>')) {
        $tag = $m.Value
        if ($tag -notlike ("*" + $Needle + "*")) { continue }
        $b = [regex]::Match($tag, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
        if (-not $b.Success) { continue }
        $center = @([int]((([int]$b.Groups[1].Value + [int]$b.Groups[3].Value) / 2)),
                    [int]((([int]$b.Groups[2].Value + [int]$b.Groups[4].Value) / 2)))
        if ($tag -match 'clickable="true"') { return $center }
        if ($null -eq $fallback) { $fallback = $center }
    }
    return $fallback
}

function Tap-Needle {
    param([string]$Needle, [string]$Tag, [int]$WaitMs = 900, [int]$Scrolls = 3)
    for ($pass = 0; $pass -le $Scrolls; $pass++) {
        $xml = Dump-Ui ("{0}-p{1}" -f $Tag, $pass)
        $c = Node-Center $xml $Needle
        if ($null -ne $c) {
            Invoke-Adb shell input tap $c[0] $c[1] | Out-Null
            Start-Sleep -Milliseconds $WaitMs
            return $true
        }
        Invoke-Adb shell input swipe 540 1700 540 700 400 | Out-Null
        Start-Sleep -Milliseconds 400
    }
    return $false
}

function Edit-Field {
    param([string]$Xml, [int]$Index)
    $ms = @([regex]::Matches($Xml, '<node[^>]*class="[^"]*EditText"[^>]*>'))
    if ($ms.Count -le $Index) { return $null }
    $b = [regex]::Match($ms[$Index].Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    if (-not $b.Success) { return $null }
    return @([int](([int]$b.Groups[1].Value + [int]$b.Groups[3].Value) / 2),
             [int](([int]$b.Groups[2].Value + [int]$b.Groups[4].Value) / 2))
}

function Type-Text {
    param([string]$Text)
    # input text takes no spaces unescaped; the payloads here are base64url plus a
    # scheme, so only '#' needs quoting (it starts a shell comment).
    $safe = $Text.Replace("#", "\#").Replace(" ", "%s")
    Invoke-Adb shell input text $safe | Out-Null
}

function Get-CurrentIme {
    return ((Invoke-Adb shell settings get secure default_input_method) -join "").Trim()
}

function Get-EnabledImes {
    $lines = Invoke-Adb shell ime list -s
    $ids = @()
    foreach ($line in $lines) {
        $t = "$line".Trim()
        if ($t -ne "" -and $t -match "/") { $ids += $t }
    }
    return $ids
}

function Disable-SoftKeyboard {
    # Disabling only the *default* IME is not enough: any still-enabled one can take
    # focus when a field is tapped, and the keyboard it raises covers the widgets
    # below it. Measured 2026-10-03: an earlier run's restore had made the TTS voice
    # input the default, so this disabled that, the real keyboard stayed enabled, and
    # a tap meant for 「连接并显示指纹」 landed on the keyboard instead.
    $script:imeBefore = Get-CurrentIme
    $script:imeEnabled = @(Get-EnabledImes)
    if ($script:imeEnabled.Count -eq 0) {
        Note "no enabled input method reported; nothing to disable"
        return
    }
    foreach ($id in $script:imeEnabled) {
        Invoke-Adb shell ime disable $id | Out-Null
    }
    Start-Sleep -Milliseconds 400
    Note ("disabled " + $script:imeEnabled.Count + " enabled input method(s): " + ($script:imeEnabled -join ", "))
}

function Restore-SoftKeyboard {
    if (-not $script:imeEnabled -or $script:imeEnabled.Count -eq 0) { return }
    foreach ($id in $script:imeEnabled) {
        Invoke-Adb shell ime enable $id | Out-Null
    }
    if ($script:imeBefore -and $script:imeBefore -ne "null") {
        Invoke-Adb shell ime set $script:imeBefore | Out-Null
    }
}

function Hide-Keyboard {
    # BACK only when a keyboard is actually up: with none shown the same keyevent pops
    # the activity, which is how a cleanup step once deleted the wrong screen.
    $state = ((Invoke-Adb shell dumpsys input_method) -join "`n")
    if ($state -match "mInputShown=true") {
        Invoke-Adb shell input keyevent KEYCODE_BACK | Out-Null
        Start-Sleep -Milliseconds 400
        return $true
    }
    return $false
}

# Args with spaces must be quoted by hand: Start-Process -ArgumentList does not, and
# Go's flag package stops parsing at the first stray word, so a spaced --data-dir
# silently becomes a loopback bind (measured 2026-10-03).
function Format-Arguments {
    # Not $Args: that is PowerShell's automatic array of unbound arguments, and a
    # parameter named Args shadows it into something the caller cannot see.
    param([string[]]$Values)
    foreach ($a in $Values) {
        if ($a -match '[\s"]') { '"' + $a.Replace('"', '""') + '"' } else { $a }
    }
}

function Start-Bridge {
    param([string[]]$BridgeArgs, [string]$Label)
    $out = Join-Path $script:work ("bridge-{0}.log" -f $Label)
    $err = Join-Path $script:work ("bridge-{0}.err.log" -f $Label)
    # FileShare ReadWrite: a redirect that excludes reading leaves the next run of the
    # same label unable to inspect a failure it is about to diagnose.
    $start = Start-Process -FilePath $script:bridgeExe -ArgumentList (Format-Arguments $BridgeArgs) `
        -RedirectStandardOutput $out -RedirectStandardError $err -PassThru -WindowStyle Hidden
    # No -PassThru here: Add-Member would then emit the object a second time, the
    # function would return an array of two, and $Proc.OutFile would collapse to a
    # two-word path that exists nowhere - "the Bridge never came up" for a Bridge
    # that was listening (measured 2026-10-03).
    $start | Add-Member -NotePropertyName OutFile -NotePropertyValue $out | Out-Null
    $start | Add-Member -NotePropertyName ErrFile -NotePropertyValue $err | Out-Null
    $script:procs += $start
    return $start
}

function Read-Log {
    param([string]$Path)
    if (-not (Test-Path $Path)) { return "" }
    # Shared read, because the Bridge is still holding the handle.
    $fs = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read,
        [System.IO.FileShare]::ReadWrite)
    try {
        $reader = New-Object System.IO.StreamReader($fs)
        return $reader.ReadToEnd()
    } finally {
        $reader.Close()
        $fs.Dispose()
    }
}

function Wait-BridgeUp {
    param($Proc, [int]$Seconds = 15)
    for ($i = 0; $i -lt ($Seconds * 4); $i++) {
        Start-Sleep -Milliseconds 250
        $text = (Read-Log $Proc.OutFile) + (Read-Log $Proc.ErrFile)
        if ($text -match "listening on") { return $true }
        if ($Proc.HasExited) { return $false }
    }
    return $false
}

# A refusal is only a refusal if the exit code says so, and Start-Process without
# -Wait leaves ExitCode null - which then compares -ne 0 as true, vacuously.
function Invoke-BridgeExpectingFailure {
    param([string[]]$BridgeArgs, [string]$Label)
    $out = Join-Path $script:work ("refuse-{0}.log" -f $Label)
    $err = Join-Path $script:work ("refuse-{0}.err.log" -f $Label)
    $p = Start-Process -FilePath $script:bridgeExe -ArgumentList (Format-Arguments $BridgeArgs) `
        -RedirectStandardOutput $out -RedirectStandardError $err -PassThru -Wait -WindowStyle Hidden
    if ($null -eq $p.ExitCode) { throw "no exit code for $Label; the check would be vacuous" }
    return @{ Code = $p.ExitCode; Out = (Read-Log $out); Err = (Read-Log $err) }
}

# A regression child is started and then waited on BY DEADLINE, never with Start-Process
# -Wait. Measured on this machine (2026-10-03, scratch probe under %TEMP%\waittest): with
# -RedirectStandardOutput, `-Wait` returned 2.1 s after a child that left nothing behind
# but 22.3 s after the same child when it left one grandchild holding the handle - the
# child itself had exited at ~2 s both times. run 12 paid for that: its phone child wrote
# "== summary: 58 checks, 31 failed ==" at 22:04:46 and the parent printed nothing more
# for 25 minutes, parked on a Gradle daemon that inherits the log handle and never exits.
#
# A bounded wait costs the exit code, though: Start-Process -PassThru without -Wait leaves
# ExitCode null here, and it did so in every shape tried (plain, hidden window, stderr
# redirected, with and without a second WaitForExit - all read code=[]). So each child runs
# through a wrapper that echoes its own $LASTEXITCODE into a sidecar file, which is also
# what makes "no exit code at all" a reportable state instead of a silent one.
function Start-ChildScript {
    param([string]$Path, [string]$LogPath)
    $wrapper = Join-Path $script:work "run-child.ps1"
    if (-not (Test-Path $wrapper)) {
        Set-Content -Path $wrapper -Value @'
param([string]$Target, [string]$CodeFile)
& $Target
$code = $LASTEXITCODE
Set-Content -LiteralPath $CodeFile -Value "$code"
exit $code
'@
    }
    $codeFile = "$LogPath.exit"
    if (Test-Path $codeFile) { Remove-Item $codeFile -Force }
    $proc = Start-Process -FilePath "powershell.exe" `
        -ArgumentList (Format-Arguments @("-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                                          $wrapper, "-Target", $Path, "-CodeFile", $codeFile)) `
        -RedirectStandardOutput $LogPath -PassThru -WindowStyle Hidden
    return @{ Proc = $proc; CodeFile = $codeFile }
}

function Wait-ChildScript {
    param($Child, [int]$TimeoutSec)
    # WaitForExit(int) is the documented form that does not wait for the redirected
    # streams to drain, which is exactly the behaviour -Wait gets wrong here.
    $exited = $Child.Proc.WaitForExit($TimeoutSec * 1000)
    if (-not $exited) {
        $Child.Proc.Kill()
        $Child.Proc.WaitForExit()
        return @{ Exited = $false; Code = $null; TimedOutAfter = $TimeoutSec }
    }
    $code = $null
    if (Test-Path $Child.CodeFile) {
        $raw = ([System.IO.File]::ReadAllText($Child.CodeFile)).Trim()
        if ($raw -match '^-?\d+$') { $code = [int]$raw }
    }
    return @{ Exited = $true; Code = $code; TimedOutAfter = 0 }
}

function Invoke-Admin {
    param([string]$Method, [string]$Path, [string]$Body = $null, [int]$Port = 0)
    if ($Port -eq 0) { $Port = $BridgePort }
    $url = "https://127.0.0.1:$Port$Path"
    $params = @{ Uri = $url; Method = $Method; UseBasicParsing = $true;
                 DisableKeepAlive = $true }
    if ($Body) {
        $params.Body = $Body
        $params.ContentType = "application/json"
    }
    # -DisableKeepAlive is required: a pooled connection would skip the certificate
    # callback entirely and let "wrong pin refused" pass for the wrong reason.
    return (Invoke-WebRequest @params).Content
}

function Install-App {
    $apk = Join-Path $PSScriptRoot "..\..\app\build\outputs\apk\debug\app-debug.apk"
    if (-not (Test-Path $apk)) { throw "no debug APK at $apk; run :app:assembleDebug first" }
    Invoke-Adb install -r $apk | Out-Null
}

function Open-PairScreen {
    Invoke-Adb shell am force-stop $Package | Out-Null
    Start-Sleep -Milliseconds 600
    Invoke-Adb shell am start -n "$Package/.ui.pair.PairActivity" | Out-Null
    Start-Sleep -Seconds 3
}

function Send-DeepLink {
    param([string]$Payload)
    Invoke-Adb shell am force-stop $Package | Out-Null
    Start-Sleep -Milliseconds 600
    # The '#' is quoted from the shell's point of view; the payload itself is
    # base64url, which carries no characters needing more than that.
    Invoke-Adb shell "am start -a android.intent.action.VIEW -d '$Payload'" | Out-Null
    Start-Sleep -Seconds 6
}

# Reading the app's own rows is the difference between "the screen looked right" and
# "the pairing was recorded". Same three helpers assert-bridge-phone.ps1 uses,
# because they were written by a run that had already been fooled once by a copy that
# missed the -wal, and once by a `cat` of the live file mid-write.
function Pull-Db {
    param([string]$Destination)
    $name = $script:dbName
    Invoke-Adb shell am force-stop $Package | Out-Null
    Start-Sleep -Milliseconds 800
    foreach ($side in @("", "-wal", "-shm")) {
        cmd /c "adb -s $Serial exec-out run-as $Package cat databases/$name$side > `"$Destination$side`"" 2>&1 | Out-Null
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

function Query-Db {
    param([string]$Path, [string]$Sql)
    if (-not $script:sqlite) { throw "no sqlite3 reader on this machine" }
    $temp = Join-Path $env:TEMP ("bp-" + [Guid]::NewGuid().ToString("N") + ".sql")
    [IO.File]::WriteAllText($temp, ".mode list`n$Sql`n", [System.Text.Encoding]::ASCII)
    $out = cmd /c "`"$script:sqlite`" -noheader -list `"$Path`" < `"$temp`" 2>&1"
    Remove-Item $temp -Force
    return @($out | Where-Object { "$_" -ne "" } | ForEach-Object { "$_".Trim() })
}

function Scalar-Db {
    param([string]$Path, [string]$Sql)
    $rows = @(Query-Db $Path $Sql)
    if ($rows.Count -eq 0) { return "" }
    return "$($rows[0])"
}

# Pull, then read. Called at every point where the row a phone write just created is
# the evidence, because a query against an older copy would happily confirm the past.
function Refresh-Db {
    $null = Pull-Db $script:dbPath
    return $script:dbPath
}

# Pull, then read. Called at every point where the row a phone write just created is
# the evidence, because a query against an older copy would happily confirm the past.
function Refresh-Db {
    $null = Pull-Db $script:dbPath
    return $script:dbPath
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
        Invoke-Adb shell input swipe 540 1700 540 700 400 | Out-Null
        Start-Sleep -Milliseconds 400
    }
    return $false
}

function Get-FocusedActivity {
    # Measured shape on API 35: mCurrentFocus=Window{3518c46 u0
    # com.aiusage.monitor/com.aiusage.monitor.ui.pair.PairActivity}
    $dump = (Invoke-Adb shell dumpsys window) -join "`n"
    $m = [regex]::Match($dump, 'mCurrentFocus=Window\{\w+ \w+ ([^ }]+)/([^ }]+)\}')
    if ($m.Success) { return $m.Groups[2].Value }
    return ""
}

function Scroll-Down {
    Invoke-Adb shell input swipe 540 1700 540 700 400 | Out-Null
    Start-Sleep -Milliseconds 400
}

# "指纹被改一位" means one hex digit of the digest, not six characters of the base64
# tail: the latter usually breaks the JSON and the app then refuses for a reason that
# has nothing to do with pinning, which is how a P-2 row passes while the pin is
# nobody's business.
function Tamper-Digest {
    param([string]$Payload)
    $prefix = "aiusage://pair#"
    if (-not $Payload.StartsWith($prefix)) { throw "not a pairing offer: $Payload" }
    $b64 = $Payload.Substring($prefix.Length)
    $padded = $b64.PadRight($b64.Length + ((4 - ($b64.Length % 4)) % 4), "=")
    $json = [System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($padded))
    $obj = $json | ConvertFrom-Json
    $last = $obj.fingerprint.Substring($obj.fingerprint.Length - 1)
    $flipped = if ($last -eq "0") { "1" } else { "0" }
    $obj.fingerprint = $obj.fingerprint.Substring(0, $obj.fingerprint.Length - 1) + $flipped
    $bytes = [System.Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Compress))
    return $prefix + ([Convert]::ToBase64String($bytes).TrimEnd("=").Replace("+", "-").Replace("/", "_"))
}

function Pair-ByLink {
    param([string]$Payload, [int]$WaitSeconds = 8)
    Open-PairScreen
    Send-DeepLink $Payload
    Start-Sleep -Seconds $WaitSeconds
    return (Wait-For-Pairing-Verdict)
}

# A pairing outcome is asynchronous, and reading `mCurrentFocus` once is not a way to
# observe an async outcome: one run's P-3 dump clearly held 「配对码无效或已过期」 while the
# focus probe on the same row came back empty, and the row went red for that reason
# (measured 2026-10-03; the same code passed the day before). Poll instead, and stop at
# whichever end state appears first - the screen closing (success) or a failure message
# appearing on it.
$script:failureWordings = @(
    "证书指纹与配对时记录的不一致", "配对码无效或已过期", "请先在手机上是这台电脑的指纹",
    "电脑端暂时无法保存配对结果", "连不上这台电脑", "电脑端的应答不是预期格式",
    "请先勾选确认，配对码不会发出")

function Wait-For-Pairing-Verdict {
    param([int]$TimeoutSec = 30)
    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    $dump = ""
    while ((Get-Date) -lt $deadline) {
        $focus = Get-FocusedActivity
        # "Not PairActivity" is not proof a pairing worked, and run 15 measured why the
        # stricter version I tried first is wrong: this script starts PairActivity with
        # `am start`, so when succeeded() finishes it the task is empty and the LAUNCHER
        # is what has focus - a real, successful pairing (device created, live Codex
        # numbers read) reads as "the app left the foreground". So leaving PairActivity is
        # still the observable, but it never carries the row alone: the send tap has to
        # have landed, and the Bridge's own device count is the thing that decides.
        if ($focus -and $focus -notmatch "PairActivity") {
            return @{ Closed = $true; Focus = $focus; Dump = ""; Left = $true; Said = "" }
        }
        $dump = Scroll-And-Dump "verdict"
        foreach ($needle in $script:failureWordings) {
            if ($dump -like ("*" + $needle + "*")) {
                return @{ Closed = $false; Focus = (Get-FocusedActivity); Dump = $dump; Said = $needle; Left = $false }
            }
        }
        Start-Sleep -Milliseconds 1500
    }
    return @{ Closed = $false; Focus = (Get-FocusedActivity); Dump = $dump; Said = ""; Left = $false }
}

function Refresh-All {
    param([int]$WaitSeconds = 14)
    Invoke-Adb shell am force-stop $Package | Out-Null
    Start-Sleep -Milliseconds 600
    Invoke-Adb shell am start -n "$Package/.ui.account.AccountListActivity" | Out-Null
    Start-Sleep -Seconds 3
    $null = Tap-Needle "刷新全部账户" "refresh-all" 1500
    Start-Sleep -Seconds $WaitSeconds
    return (Dump-Ui "refresh-all")
}

# Deleting through the app's own menu rather than by writing to its database: the
# device holds the only copy of two real DeepSeek keys, and a hand-run SQL delete
# would be an argument about which rows are safe to touch.
function Remove-OneAccount {
    param([string]$Name, [string]$Tag)
    Invoke-Adb shell am force-stop $Package | Out-Null
    Start-Sleep -Milliseconds 600
    Invoke-Adb shell am start -n "$Package/.ui.account.AccountListActivity" | Out-Null
    Start-Sleep -Seconds 3
    if (-not (Long-Press-Needle ('content-desc="' + $Name + '"') "press-$Tag")) { return $false }
    if (-not (Tap-Needle 'text="删除账户"' "menu-$Tag" 1200)) {
        Invoke-Adb shell input keyevent KEYCODE_BACK | Out-Null
        return $false
    }
    # Exact text: the dialog's title is also 删除账户, and tapping the title dismisses
    # nothing while still looking like a delete that worked.
    $null = Tap-Needle 'text="删除"' "confirm-$Tag" 2500
    return $true
}

function Scroll-To-Bottom {
    param([int]$Times = 3)
    for ($i = 0; $i -lt $Times; $i++) {
        Invoke-Adb shell input swipe 540 1600 540 500 400 | Out-Null
        Start-Sleep -Milliseconds 400
    }
}

# uiautomator prunes the children of a ScrollView that sit below the viewport, so a
# field or a status line can be laid out, visible to a person who scrolls, and absent
# from the dump. Measured 2026-10-03 on the pairing screen: everything under the
# discover button (the tail, the tick, the send button, the status line) is out of the
# first dump.
function Scroll-And-Dump {
    param([string]$Tag, [int]$Times = 3)
    Scroll-To-Bottom -Times $Times
    return (Dump-Ui $Tag)
}

function Open-BridgeList {
    # Reached through the account list's own 「已配对的电脑」 button: BridgeListActivity is
    # android:exported="false", so `am start` from the shell uid is refused and the run
    # would otherwise tap a launcher home screen while believing it was forgetting
    # computers (measured 2026-10-03: three bridge rows left behind that way).
    if ((Get-FocusedActivity) -match "BridgeListActivity") { return $true }
    Invoke-Adb shell am force-stop $Package | Out-Null
    Start-Sleep -Milliseconds 600
    Invoke-Adb shell am start -n "$Package/.ui.account.AccountListActivity" | Out-Null
    Start-Sleep -Seconds 3
    if (-not (Tap-Needle "已配对的电脑" "enter-bridges" 2000)) {
        Note "could not reach the paired-computer list from the account screen"
        return $false
    }
    Start-Sleep -Milliseconds 800
    return $true
}

# Typing is not done when the command returned; it is done when the field holds what
# was sent. The Phase 6 lesson was that a dump shows a hint as text - the Phase 7 one
# is that an IME can hand back one character more than was typed, and a 9-character
# code makes the app refuse the form for a reason the screen then prints below the
# fold. Read the field back, and clear and retry rather than proceed on a guess.
function Type-Into-Field {
    param([int[]]$Center, [string]$Text, [int]$Index, [string]$Tag)
    for ($attempt = 1; $attempt -le 3; $attempt++) {
        Invoke-Adb shell input tap $Center[0] $Center[1] | Out-Null
        Start-Sleep -Milliseconds 500
        Type-Text $Text
        Start-Sleep -Milliseconds 500
        $xml = Dump-Ui ("typed-$Tag-$attempt")
        $nodes = @([regex]::Matches($xml, '<node[^>]*class="[^"]*EditText"[^>]*>'))
        $value = ""
        if ($nodes.Count -gt $Index) {
            $value = [regex]::Match($nodes[$Index].Value, 'text="([^"]*)"').Groups[1].Value
        }
        if ($value -eq $Text) { $null = Hide-Keyboard; return $true }
        # A password field masks its contents, so length is the only read-back there.
        $masked = [regex]::Matches($value, '[•●*\u2022]').Count
        if ($masked -eq $Text.Length -and $value -notmatch "[^•●*\u2022]") { $null = Hide-Keyboard; return $true }
        Note ("field $Index holds '" + $value + "' instead of '" + $Text + "', retrying")
        $null = Hide-Keyboard
        Invoke-Adb shell input keyevent KEYCODE_MOVE_END | Out-Null
        for ($k = 0; $k -lt 16; $k++) { Invoke-Adb shell input keyevent KEYCODE_DEL | Out-Null }
    }
    return $false
}


# The manifest lives outside the per-run work directory, because "what this run
# created" is not enough: a run that died halfway leaves its pairings behind, and the
# next one would then be forbidden from cleaning up rows that only it ever made - while
# still never touching a pairing its owner made. So the ledger is by script, not by run.
function Load-Manifest {
    if (-not (Test-Path $script:manifestPath)) { return }
    foreach ($line in ([System.IO.File]::ReadAllLines($script:manifestPath))) {
        $t = "$line".Trim()
        if ($t -like "account:*") { $script:pairAccounts[$t.Substring(8)] = @{ Name = ""; Bridge = "" } }
        elseif ($t -like "bridge:*") { $script:pairBridges[$t.Substring(7)] = "" }
    }
    # Ids that no longer exist are dropped, so the ledger cannot grow forever.
    $snap = Snapshot-Pairings
    foreach ($id in @($script:pairAccounts.Keys)) {
        if (-not $snap.Accounts.ContainsKey($id)) { $script:pairAccounts.Remove($id) }
        else { $script:pairAccounts[$id] = $snap.Accounts[$id] }
    }
    foreach ($id in @($script:pairBridges.Keys)) {
        if (-not $snap.Bridges.ContainsKey($id)) { $script:pairBridges.Remove($id) }
        else { $script:pairBridges[$id] = $snap.Bridges[$id] }
    }
    Note ("manifest: " + $script:pairAccounts.Count + " account row(s) and " `
        + $script:pairBridges.Count + " computer row(s) left by earlier runs of this script")
}

function Save-Manifest {
    $lines = @()
    foreach ($id in $script:pairAccounts.Keys) { $lines += "account:$id" }
    foreach ($id in $script:pairBridges.Keys) { $lines += "bridge:$id" }
    [System.IO.File]::WriteAllLines($script:manifestPath, $lines, (New-Object System.Text.UTF8Encoding($false)))
}
# What this script created, tracked by id, and the only thing it is allowed to remove.
# The version that went before swept `accounts where bridge_id != ''` and every row of
# `bridges` at three points in the run, which on a device holding a pairing its owner
# made for themselves would have destroyed that pairing (review P1, 2026-10-03).
function Snapshot-Pairings {
    $db = Refresh-Db
    $accounts = @{}
    foreach ($row in @(Query-Db $db `
            "select id || '|' || display_name || '|' || bridge_id from accounts where bridge_id != '';")) {
        $p = $row -split '\|', 3
        $accounts[$p[0]] = @{ Name = $p[1]; Bridge = $p[2] }
    }
    $bridges = @{}
    foreach ($row in @(Query-Db $db "select id || '|' || fingerprint from bridges;")) {
        $p = $row -split '\|', 2
        $bridges[$p[0]] = $p[1]
    }
    return @{ Accounts = $accounts; Bridges = $bridges }
}

# Anything that appeared since the snapshot taken before the pairing, and was not
# already in the manifest, is this run's. Anything that was on the device before the
# run is not, and stays.
function Update-Manifest {
    param($Before)
    $now = Snapshot-Pairings
    foreach ($id in @($now.Accounts.Keys)) {
        if (-not $script:pairAccounts.ContainsKey($id) -and -not $Before.Accounts.ContainsKey($id)) {
            $script:pairAccounts[$id] = $now.Accounts[$id]
        }
    }
    foreach ($id in @($now.Bridges.Keys)) {
        if (-not $script:pairBridges.ContainsKey($id) -and -not $Before.Bridges.ContainsKey($id)) {
            $script:pairBridges[$id] = $now.Bridges[$id]
        }
    }
    Save-Manifest
    return $now
}

function Forget-BridgeByTail {
    param([string]$Tail, [string]$Tag)
    $script:bridgesScreen = $false
    for ($scroll = 0; $scroll -le 4; $scroll++) {
        if (-not $script:bridgesScreen) {
            if (-not (Open-BridgeList)) { return $false }
            $script:bridgesScreen = $true
        }
        $xml = Dump-Ui "forget-$Tag-$scroll"
        $tailNode = $null
        foreach ($m in [regex]::Matches($xml, '<node[^>]*>')) {
            if ($m.Value -match ("指纹尾号 " + $Tail)) { $tailNode = $m.Value; break }
        }
        if ($null -eq $tailNode) {
            if ($xml -notlike "*忘记这台电脑*") { return $false }
            Scroll-Down
            continue
        }
        $tailBottom = [int][regex]::Match($tailNode, 'bounds="\[\d+,\d+\]\[\d+,(\d+)\]"').Groups[1].Value
        $best = $null; $bestGap = [int]::MaxValue
        foreach ($m in [regex]::Matches($xml, '<node[^>]*content-desc="忘记这台电脑"[^>]*>')) {
            $b = [regex]::Match($m.Value, 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
            if (-not $b.Success) { continue }
            $top = [int]$b.Groups[2].Value
            $gap = $top - $tailBottom
            if ($gap -ge 0 -and $gap -lt $bestGap) {
                $bestGap = $gap
                $best = @([int](([int]$b.Groups[1].Value + [int]$b.Groups[3].Value) / 2),
                          [int](([int]$b.Groups[2].Value + [int]$b.Groups[4].Value) / 2))
            }
        }
        if ($null -eq $best) { return $false }
        Invoke-Adb shell input tap $best[0] $best[1] | Out-Null
        Start-Sleep -Milliseconds 1200
        $null = Tap-Needle 'text="忘记"' "forgetyes-$Tag" 2000
        $script:bridgesScreen = $false
        return $true
    }
    return $false
}

function Delete-RunAccounts {
    param([string]$Tag)
    $failed = @()
    foreach ($id in @($script:pairAccounts.Keys)) {
        $name = $script:pairAccounts[$id].Name
        $round = 0
        while ($round -lt 8) {
            $snap = Snapshot-Pairings
            if (-not $snap.Accounts.ContainsKey($id)) { break }
            # The row is chosen in the app's list by its visible name. If a pairing that
            # is NOT ours carries the same name, no tap can tell them apart, so stop and
            # say so rather than delete somebody else's account.
            $lookalikes = @($snap.Accounts.Keys | Where-Object {
                -not $script:pairAccounts.ContainsKey($_) -and $snap.Accounts[$_].Name -eq $name })
            if ($lookalikes.Count -gt 0) {
                Note ("leaving '" + $name + "' alone: " + $lookalikes.Count `
                    + " row(s) with the same name are not this run's")
                $failed += $id
                break
            }
            if (-not (Remove-OneAccount -Name $name -Tag "$Tag-$round")) { $failed += $id; break }
            $round++
        }
    }
    return $failed
}

# Remove exactly the manifest, then report what is still on the device: the two
# numbers the caller asserts on are "mine left" and "not mine, still there".
function Clear-RunPairings {
    param([string]$Tag)
    $null = Delete-RunAccounts -Tag "acc-$Tag"
    foreach ($id in @($script:pairBridges.Keys)) {
        $fp = $script:pairBridges[$id]
        if ($fp.Length -lt 8) { continue }
        $null = Forget-BridgeByTail -Tail $fp.Substring(0, 8).ToUpper() -Tag "$Tag-$id"
    }
    $snap = Snapshot-Pairings
    foreach ($id in @($script:pairAccounts.Keys)) {
        if (-not $snap.Accounts.ContainsKey($id)) { $script:pairAccounts.Remove($id) }
    }
    foreach ($id in @($script:pairBridges.Keys)) {
        if (-not $snap.Bridges.ContainsKey($id)) { $script:pairBridges.Remove($id) }
    }
    Save-Manifest
    $mineLeft = @($script:pairAccounts.Keys | Where-Object { $snap.Accounts.ContainsKey($_) }).Count
    $myBridgesLeft = @($script:pairBridges.Keys | Where-Object { $snap.Bridges.ContainsKey($_) }).Count
    $foreignAccounts = @($snap.Accounts.Keys | Where-Object { -not $script:pairAccounts.ContainsKey($_) }).Count
    $foreignBridges = @($snap.Bridges.Keys | Where-Object { -not $script:pairBridges.ContainsKey($_) }).Count
    return @{ MineLeft = $mineLeft; MyBridgesLeft = $myBridgesLeft
              ForeignAccounts = $foreignAccounts; ForeignBridges = $foreignBridges }
}


function Stop-Everything {
    foreach ($p in $script:procs) {
        try { if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force } } catch { }
    }
    [System.Net.ServicePointManager]::ServerCertificateValidationCallback = $null
    Restore-SoftKeyboard
}

$script:TlsPolicy = $null

# ============================================================ the run itself

$sqlite3 = "C:\msys64\ucrt64\bin\sqlite3.exe"
# The SDK ships one next to adb, and that is the one the other device scripts already
# trust; the msys64 build is the fallback for a machine without the SDK package.
foreach ($candidate in @((Join-Path $env:ANDROID_HOME "platform-tools\sqlite3.exe"), $sqlite3)) {
    if ($candidate -and (Test-Path $candidate)) { $script:sqlite = $candidate; break }
}
$onPath = Get-Command "sqlite3.exe" -ErrorAction SilentlyContinue
if (-not $script:sqlite -and $onPath) { $script:sqlite = $onPath.Source }
if ($script:sqlite) {
    Note "reading the app database with $script:sqlite"
} else {
    Note "no host sqlite3 found: the row-level checks (P-1, P-8) will report SKIP, not PASS"
}

function Setup-Work {
    $root = Join-Path $PSScriptRoot "out\pair-run"
    if (-not (Test-Path $root)) { $null = New-Item -ItemType Directory -Path $root }
    # One directory per run, and the previous run's directory is left alone. The old shape
    # deleted `out\pair-run` here, which cost two runs: it destroyed the evidence of the
    # run before it, and a Gradle daemon still holding the previous child's log made the
    # delete fatal - run 13 aborted at 22:35 on "文件正由另一进程使用".
    $script:work = Join-Path $root ("run-" + (Get-Date -Format "yyyyMMdd-HHmmss"))
    $null = New-Item -ItemType Directory -Path (Join-Path $script:work "dumps")
    # A spaced path on purpose: it is what exposed the Start-Process quoting trap.
    $script:dataDir = Join-Path $script:work "bridge data"
    New-Item -ItemType Directory -Path $script:dataDir | Out-Null
    $script:shortDir = Join-Path $script:work "bridge ttl"
    New-Item -ItemType Directory -Path $script:shortDir | Out-Null
    $script:shortPort = $BridgePort + 1
    $script:guardDir = Join-Path $script:work "bridge guard"
    New-Item -ItemType Directory -Path $script:guardDir | Out-Null
    $script:guardPort = $BridgePort + 2
    $script:decoyDir = Join-Path $script:work "bridge decoy"
    New-Item -ItemType Directory -Path $script:decoyDir | Out-Null
    $script:decoyPort = $BridgePort + 3
    $script:dbPath = Join-Path $script:work "phone.db"
    # Deliberately outside the per-run directory: it is the ledger of what this script owns
    # on the device, and a run that dies must not hand its leftovers to the next one as
    # "not mine".
    $script:manifestPath = Join-Path $PSScriptRoot "out\pair-manifest.txt"
}

function Build-Bridge {
    $dst = Join-Path $script:work "aiusage-bridge.exe"
    if ($SkipBridgeBuild) {
        # Honoured rather than ignored: -SkipBridgeBuild means "the binary I am about to
        # test is already built somewhere", so take that path and prove it exists. The
        # switch used to be accepted and silently dropped, which meant the flag's name
        # promised a faster run and delivered the same build.
        $existing = if ($BridgeExePath) { $BridgeExePath } else {
            Join-Path $PSScriptRoot "..\..\bridge\bin\aiusage-bridge.exe"
        }
        if (-not $existing -or -not (Test-Path $existing)) {
            throw "-SkipBridgeBuild given but no Bridge binary at '$existing'; drop the switch or pass -BridgeExePath"
        }
        Copy-Item $existing $dst -Force
        $script:bridgeExe = $dst
        Note "skipped the Go build; testing the Bridge binary at $existing"
        return
    }
    Push-Location (Join-Path $PSScriptRoot "..\..\bridge")
    try {
        & go build -o $dst ./cmd/aiusage-bridge
        if ($LASTEXITCODE -ne 0) { throw "go build failed with $LASTEXITCODE" }
    } finally { Pop-Location }
    $script:bridgeExe = $dst
}

function Setup-TlsCallback {
    # A scriptblock in the certificate callback runs on a thread with no PowerShell
    # runspace and fails the handshake, so the callback is a compiled static method.
    if (-not ("PairAcceptAny" -as [type])) {
        Add-Type -TypeDefinition @'
using System;
using System.Net;
using System.Net.Security;
using System.Security.Cryptography.X509Certificates;
public static class PairAcceptAny {
    public static bool Callback(object sender, X509Certificate certificate,
                                X509Chain chain, SslPolicyErrors errors) {
        return true;
    }
}
'@
    }
    $method = [PairAcceptAny].GetMethod("Callback")
    $propType = ([System.Net.ServicePointManager].GetProperty("ServerCertificateValidationCallback")).PropertyType
    $delegate = [Delegate]::CreateDelegate($propType, $method)
    # Invoke-WebRequest has no per-request certificate parameter (that one belongs to
    # WinRM), so the harness installs its shortcut process-wide. Scope it honestly:
    # every request this affects goes to 127.0.0.1 admin routes the Bridge itself
    # restricts to loopback peers. The phone's pinning is not exercised here - rows
    # P-1/P-2 read what the app stored and what the app refused.
    [System.Net.ServicePointManager]::ServerCertificateValidationCallback = $delegate
    # .NET Framework 4.x on this machine still defaults to a protocol set that excludes
    # what a Go 1.x server offers by default, and a failed handshake would look like
    # "the Bridge is down".
    [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.SecurityProtocolType]::Tls12
    return $delegate
}

function Assert-Canary {
    # Everything that taps or types depends on this. Without it a dead UI channel
    # reads as "the pairing screen is broken".
    Invoke-Adb shell am force-stop $Package | Out-Null
    Start-Sleep -Milliseconds 500
    Invoke-Adb shell am start -n "$Package/.ui.account.AccountListActivity" | Out-Null
    Start-Sleep -Seconds 3
    if (-not (Tap-Needle "添加账户" "canary-open" 2000)) {
        throw "the canary could not open the account form; there is no working UI channel"
    }
    $xml = Dump-Ui "canary-form"
    $field = Edit-Field $xml 0
    if ($null -eq $field) { throw "the canary found no text field on the account form" }
    Invoke-Adb shell input tap $field[0] $field[1] | Out-Null
    Start-Sleep -Milliseconds 800
    Type-Text "PairCanaryText"
    Start-Sleep -Milliseconds 400
    $after = Dump-Ui "canary-typed"
    Check "injected text reaches a plain-text field" ($after -like '*text="PairCanaryText"*') `
        "without this, every screen assertion below is noise"
    Invoke-Adb shell input keyevent 4 | Out-Null
    Start-Sleep -Milliseconds 600
}

function New-Offer {
    return (Invoke-Admin "Post" "/v1/admin/pair" | ConvertFrom-Json)
}

function Get-Devices {
    # PowerShell 5.1's ConvertFrom-Json writes a deserialized JSON array onto the pipe as
    # ONE object, so `@(… | ConvertFrom-Json).Count` is 1 for a list of one or more, and
    # `-not $_.revoked` against that inner array is false - which is how a store holding
    # two live devices read as "no paired device to revoke" (measured 2026-10-03).
    # foreach enumerates the array, so counting happens after that.
    $parsed = Invoke-Admin "Get" "/v1/admin/devices" | ConvertFrom-Json
    $list = @()
    foreach ($d in $parsed) { $list += $d }
    return $list
}

function Active-Count {
    $n = 0
    foreach ($d in Get-Devices) { if (-not $d.revoked) { $n++ } }
    return $n
}

function Device-Ids {
    param([switch]$ActiveOnly)
    $ids = @()
    foreach ($d in Get-Devices) {
        if ($ActiveOnly -and $d.revoked) { continue }
        $ids += $d.id
    }
    return $ids
}

function Summary {
    Write-Host ""
    Write-Host "CHECKS=$script:checks FAILURES=$script:failures SKIPPED=$script:skips"
    if ($script:skips -gt 0) {
        foreach ($key in ($script:skipReasons.Keys | Sort-Object)) {
            Write-Host ("  SKIP {0}: {1}" -f $key, $script:skipReasons[$key])
        }
    }
    if ($script:failures -gt 0) {
        Write-Host "RESULT: FAIL"
        exit 1
    }
    if ($script:skips -gt 0) {
        Write-Host "RESULT: *PASS* with $script:skips row(s) NOT JUDGED - see the SKIP list above"
    } else {
        Write-Host "RESULT: *PASS*"
    }
    # Terminating, in the passing branch too: `exit` used to live only in the failure
    # branch, so the "no -BridgeHost" path printed its summary and fell through to
    # starting a Bridge with an empty host (review P2, 2026-10-03).
    exit 0
}

# ----------------------------------------------------------------- prerequisites

Setup-Work

if ($SelfTest) {
    # The bounded child wait is the thing that let run 12 park for 25 minutes with its
    # child already finished, so it carries rows of its own. It needs no device, no Bridge
    # and no app: the failure being guarded against lives in this harness, and a guard that
    # only runs inside a 60-minute round never gets checked. Each row below says which of
    # the two wrong shapes it catches - an unbounded wait, or a wait so bounded that a
    # finished child is reported as a timeout.
    $holder = Join-Path $script:work "selftest-holder.ps1"
    Set-Content -Path $holder -Value @'
Write-Output "selftest: the child is done"
$null = Start-Process -FilePath "cmd.exe" -NoNewWindow -ArgumentList "/c", "ping -n 25 127.0.0.1 >nul"
exit 0
'@
    $logHolder = Join-Path $script:work "selftest-holder.log"
    $clock = Get-Date
    $r = Wait-ChildScript (Start-ChildScript -Path $holder -LogPath $logHolder) -TimeoutSec 20
    $took = [math]::Round(((Get-Date) - $clock).TotalSeconds, 1)
    Check "the child wait is not held by a process that inherited its log handle" `
        ($r.Exited -and ($null -ne $r.Code) -and ($r.Code -eq 0) -and $took -lt 12) `
            ("exited={0} code=[{1}] after {2} s; the child finished in about 2 s and left a 25 s holder behind it, " -f $r.Exited, $r.Code, $took) +
            "which is the shape that parked run 12"
    Check "and what that child wrote is readable as its own evidence" `
        ((Read-Log $logHolder) -match "selftest: the child is done") `
            "log held: $(((Read-Log $logHolder) -replace '\s+', ' '))"

    $sleeper = Join-Path $script:work "selftest-sleep.ps1"
    Set-Content -Path $sleeper -Value 'Write-Output "selftest: still working"; Start-Sleep -Seconds 25; exit 0'
    $logSleep = Join-Path $script:work "selftest-sleep.log"
    $clock2 = Get-Date
    $r2 = Wait-ChildScript (Start-ChildScript -Path $sleeper -LogPath $logSleep) -TimeoutSec 4
    $took2 = [math]::Round(((Get-Date) - $clock2).TotalSeconds, 1)
    Check "and a child that really does overrun its deadline is reported, not waited for" `
        ((-not $r2.Exited) -and ($null -eq $r2.Code) -and $took2 -lt 10) `
            ("exited={0} code=[{1}] after {2} s with a 4 s deadline; a run that ignored the deadline would still be waiting" -f $r2.Exited, $r2.Code, $took2)

    $clean = Join-Path $script:work "selftest-clean.ps1"
    Set-Content -Path $clean -Value 'Write-Output "selftest: clean exit"; exit 3'
    $logClean = Join-Path $script:work "selftest-clean.log"
    $clock3 = Get-Date
    $r3 = Wait-ChildScript (Start-ChildScript -Path $clean -LogPath $logClean) -TimeoutSec 30
    $took3 = [math]::Round(((Get-Date) - $clock3).TotalSeconds, 1)
    Check "and a child that finishes reports its exit code instead of a timeout" `
        ($r3.Exited -and ($r3.Code -eq 3) -and $took3 -lt 15) `
            "exited=$($r3.Exited) code=[$($r3.Code)] after $took3 s"

    # The dead-guest detector, judged against the real artefacts that motivated it rather
    # than a synthetic string: run 22's own dumps, one from after the SystemUI ANR and one
    # from before it, in the same directory. Both directions matter - flagging a healthy
    # screen would turn every genuine failure into a SKIP, and missing a dead guest is what
    # produced twelve misleading FAILs.
    $deadDump = Join-Path $PSScriptRoot "out\pair-run\run-20261004-092753\dumps\72-enter-bridges-p3.xml"
    $liveDump = Join-Path $PSScriptRoot "out\pair-run\run-20261004-092753\dumps\15-p1m-ticked.xml"
    if (-not (Test-Path $deadDump) -or -not (Test-Path $liveDump)) {
        Skip "the dead-guest detector reads run 22's real dumps" "those artefacts are no longer under tools/smoke/out/"
    } else {
        $script:uiBroken = $false; $script:uiBrokenReason = ""
        Note-GuestUiHealth -Dump ([System.IO.File]::ReadAllText($liveDump, [System.Text.Encoding]::UTF8)) -Tag "selftest-live"
        Check "a healthy app dump is not mistaken for a dead guest" (-not $script:uiBroken) `
            "it flagged a screen that contains the app's own package; the rows after it would have been silently excused"
        $script:uiBroken = $false; $script:uiBrokenReason = ""
        Note-GuestUiHealth -Dump ([System.IO.File]::ReadAllText($deadDump, [System.Text.Encoding]::UTF8)) -Tag "selftest-dead"
        Check "and run 22's ANR-sheet dump is recognised as the guest, not the app" ($script:uiBroken) `
            "the 4464-byte package=`"android`" dump that cost twelve FAILs in run 22 did not raise the flag"
        $script:uiBroken = $false; $script:uiBrokenReason = ""
    }
    Summary
}

$script:TlsPolicy = Setup-TlsCallback
# Say which device and which build this evidence belongs to. A log without that is not
# attributable: an earlier migration rehearsal recorded M1-M7 passing with no serial,
# and the reader cannot tell whether it ran on the emulator the plan talks about.
Note ("host: " + $env:COMPUTERNAME + " / Windows " + [Environment]::OSVersion.Version + `
      " / PowerShell " + $PSVersionTable.PSVersion)
if ($script:sqlite) { Note "host sqlite3: $script:sqlite" }
$state = (Invoke-Adb get-state) -join ""
$script:deviceReady = ($state -match "device")
if ($script:deviceReady) {
    $props = @{}
    foreach ($p in @("ro.product.model", "ro.build.version.release", "ro.build.version.sdk")) {
        $props[$p] = ((Invoke-Adb shell getprop $p) -join "").Trim()
    }
    # `ro.kernel.qemu.avd_name` is not readable from the guest on this image - it came
    # back empty in run 12's header - while the emulator console answers `adb emu avd
    # name` with the name plus a trailing OK. Which AVD this is belongs in the evidence
    # because the two real DeepSeek accounts and the widgets live on that one image.
    # ro.debuggable is dropped rather than reported: the run's DB reads depend on the
    # app being debug-built and installed here, not on the image flag, so printing it
    # explained nothing about the rows below it.
    $avd = ((Invoke-Adb emu avd name) | Where-Object { "$_".Trim() -ne "" -and "$_".Trim() -ne "OK" } |
        Select-Object -First 1)
    Note ("device " + $Serial + ": " + $props["ro.product.model"] + " / Android " `
        + $props["ro.build.version.release"] + " (SDK " + $props["ro.build.version.sdk"] + ")" `
        + " / avd=" + ("$avd").Trim())
} else {
    Note "no device registered on $Serial (adb get-state: $($state.Trim())); the phone-side rows cannot be judged"
}

try {
    # Everything that touches the device lives inside the try, because the keyboard is
    # disabled here and only Stop-Everything puts it back: a canary that threw before
    # the try began left the emulator without an IME for the rest of the day (review
    # P2, 2026-10-03).
    if ($script:deviceReady) {
        Install-App
        Disable-SoftKeyboard
        Assert-Canary
        # P-7 scans this run's log, not the device's whole uptime.
        Invoke-Adb logcat -c | Out-Null
    }

    Build-Bridge

    # ---------------------------------------- P-5's guard: needs no phone, always runs
    # This used to live inside the "no -BridgeHost" branch, which meant the row that
    # proves a LAN bind cannot start unencrypted simply disappeared once a bind was
    # requested - an absent row is worse than a skipped one, because the summary still
    # reads clean. It now runs either way, in its own directory so it cannot touch the
    # identity the paired Bridge is holding.
    #
    # It also drops `--require-token`, which is not a flag this binary defines: with it
    # present the process exited 2 on "flag provided but not defined" and printed its
    # usage, and since the usage line contains the words "--pair" both rows passed while
    # the guard was never reached (measured 2026-10-03). The needle is now the guard's own
    # sentence, and a control excludes the flag parser.
    $refusal = Invoke-BridgeExpectingFailure @(
        "--host", "28.0.0.1", "--data-dir", $script:guardDir,
        "--port", "$($script:guardPort)") "no-pair"
    $refusalText = ($refusal.Err + "`n" + $refusal.Out)
    Check "P-5 a non-loopback bind without --pair refuses to start" ($refusal.Code -ne 0) `
        "exit was $($refusal.Code); said: $($refusalText.Trim())"
    Check "P-5 and the refusal came from the bind guard, not the flag parser" `
        ($refusalText -match "refuses to bind a non-loopback" -and $refusalText -notmatch "flag provided but not defined") `
        "said: $($refusalText.Trim())"
    Check "P-5 and the refusal names --pair as the thing to add" ($refusalText -match "--pair") `
        "said: $($refusalText.Trim())"
    $guardIdentity = @(Get-ChildItem $script:guardDir -Filter "identity.*" -ErrorAction SilentlyContinue).Count
    Check "P-5 and the refusal minted no identity while doing it" ($guardIdentity -eq 0) `
        "identity files in the guard directory: $guardIdentity"

    if (-not $BridgeHost) {
        foreach ($row in @("P-1", "P-2", "P-3", "P-4", "P-6", "P-7", "P-8")) {
            Skip $row "no -BridgeHost: nothing is bound where the emulator can reach it"
        }
        Summary
    }

    $bridge = Start-Bridge @("--pair", "--host", $BridgeHost, "--port", "$BridgePort",
                             "--data-dir", $script:dataDir, "--pair-ttl", $PairTtl) "main"
    $up = Wait-BridgeUp $bridge
    Check "the Bridge came up with --pair on ${BridgeHost}:$BridgePort" $up (Read-Log $bridge.OutFile)
    if (-not $up) { throw "the Bridge never reported listening on; nothing below can be judged" }

    $identity = Join-Path $script:dataDir "identity.crt.pem"
    $identityBefore = $null
    if (Test-Path $identity) {
        $identityBefore = [Convert]::ToBase64String((Get-Content -Encoding Byte -ReadCount 0 $identity))
    }

    # Their keys exist nowhere else on this machine, so the rows pairing is about to
    # create are checked against a baseline taken before any of it happened.
    if ($script:deviceReady -and $script:sqlite) {
        $null = Pull-Db $script:dbPath
        $script:deepseekBaseline = (Query-Db $script:dbPath `
            "select id || '=' || credential_id from accounts where provider_id='deepseek' order by id;") -join ","
        Check "the DeepSeek accounts this run must not disturb are present to begin with" `
            ((($script:deepseekBaseline -split ",").Count -ge 2) -and ($script:deepseekBaseline -notmatch "^$")) `
            ("found: " + $script:deepseekBaseline + " - add the two DeepSeek accounts in the app first; " +
                "this run must not start on a device whose real accounts are missing")
    } else {
        Skip "the DeepSeek baseline" $(if ($script:deviceReady) { "no host sqlite3" } else { "no device registered" })
    }

    # Pairings this *script* made on an earlier run are cleaned before it adds, for the
    # same reason assert-bridge-phone.ps1 does: they are indistinguishable on screen
    # from the ones this run is about to make. Pairings made by anybody else are
    # reported and left alone - see Clear-RunPairings.
    if ($script:deviceReady -and $script:sqlite) {
        Load-Manifest
        $start = Clear-RunPairings -Tag "pre"
        Check "no pairing this script made earlier is left to confuse this run" `
            ($start.MineLeft -eq 0 -and $start.MyBridgesLeft -eq 0) `
                "still present after cleaning: $($start.MineLeft) account row(s), $($start.MyBridgesLeft) computer row(s)"
        if ($start.ForeignAccounts -gt 0 -or $start.ForeignBridges -gt 0) {
            Note ("the device holds " + $start.ForeignAccounts + " account(s) and " `
                + $start.ForeignBridges + " computer(s) this script did not create; left untouched")
        }
    } else {
        Skip "the pre-run clean" $(if ($script:deviceReady) { "no host sqlite3" } else { "no device registered" })
    }

    # --------------------------------------------------------------- P-1 and P-8
    $offer = New-Offer
    $health = Invoke-Admin "Get" "/v1/health" | ConvertFrom-Json
    Check "P-1 the offer's fingerprint is the one /v1/health advertises" `
        ($offer.fingerprint -eq $health.fingerprint) "offer=$($offer.fingerprint) health=$($health.fingerprint)"

    if (-not $script:deviceReady) {
        Skip "P-1 the pairing itself" "no device registered"
        Skip "P-1m the typed channel" "no device registered"
        Skip "P-2" "no device registered"
        Skip "P-3" "no device registered"
        Skip "P-8" "no device registered"
    } elseif (-not $script:sqlite) {
        Skip "P-1 the pairing itself" "no host sqlite3 to read what the phone stored"
        Skip "P-8" "no host sqlite3"
    } else {
        $beforePair = Snapshot-Pairings
        $verdict = Pair-ByLink $offer.payload
        Check "P-1 the pairing screen closed by itself once the offer worked" `
            ($verdict.Closed) "still on the pairing screen; it said: '$($verdict.Said)' focused: $($verdict.Focus)"
        $null = Update-Manifest $beforePair

        $null = Pull-Db $script:dbPath
        $rows = (Query-Db $script:dbPath `
            "select id || '|' || base_url || '|' || fingerprint from bridges;") -join "`n"
        Check "P-8 the paired computer was stored by id, address and digest" `
            ($rows -like "*$($offer.bridgeId)*") "bridges: $rows"
        $pairedAccountId = (Scalar-Db $script:dbPath `
            "select id from accounts where bridge_id = '$($offer.bridgeId)' order by created_at desc limit 1;")
        Check "P-8 an account points at that row rather than repeating an address" `
            ($pairedAccountId -ne "") "account id read back: '$pairedAccountId'; rows: $rows"
        $stored = (Scalar-Db $script:dbPath `
            "select fingerprint from bridges where id = '$($offer.bridgeId)';")
        Check "P-1 the digest the phone kept is the one the computer advertised" `
            ($stored -eq $offer.fingerprint) "stored: $stored"
        Check "P-1 the computer records exactly one active device" ((Active-Count) -eq 1) `
            "devices: $((Get-Devices | ForEach-Object { $_.id }) -join ',')"

        # A10's positive evidence: the certificate has no SAN for 10.0.2.2 or for the
        # LAN address, so a handshake that completes and then reads numbers can only
        # have come from the per-connection verifier. A snapshot row is that read.
        $null = Refresh-All
        $null = Refresh-Db
        $snap = (Scalar-Db $script:dbPath `
            "select count(*) from usage_snapshots where account_id = '$pairedAccountId' and success = 1;")
        Check "P-1 the paired account read numbers over the pinned connection" `
            ($snap -match "^[1-9]") "successful snapshots: $snap; health stateReadable=$($health.stateReadable)"
        $payload = (Scalar-Db $script:dbPath `
            "select usage_data from usage_snapshots where account_id = '$pairedAccountId' and success = 1 order by timestamp desc limit 1;")
        # Counted as text, not parsed as JSON: the row comes back through cmd's console
        # codepage, and a window label like 「5 小时」 is exactly the kind of byte that
        # makes a JSON parser throw - which would then read as "the Bridge sent one
        # window" when what happened is that the harness could not read its own file.
        $windows = [regex]::Matches($payload, '"usedPercent"').Count
        Check "P-1 and the read carried both quota windows the plan names" `
            ($windows -eq 2) "windows: $windows; payload: $($payload.Substring(0, [Math]::Min(220, $payload.Length)))"

        # ------------------------------------------------------- P-1m the typed channel
        # A11/R10: trust-on-first-use plus a human comparing the tail. The gate is the
        # point - a screen that sends the code before the tick is a screen that hands
        # the code to whoever happens to answer that address.
        $offer3 = New-Offer
        # Taken before the screen opens: Snapshot-Pairings reads the device database, and
        # reading it goes through Pull-Db, which force-stops the app. Taking it mid-form is
        # what closed the pairing page under the harness's own fingers in runs 12 and 14.
        $beforeTyped = Snapshot-Pairings
        Open-PairScreen
        $xml = Dump-Ui "p1m-form"
        # 1, 2, 3 rather than 0, 1, 2: the first text field on this screen is the paste
        # box of the offer card, and the typed channel's three come after it.
        $hostField = Edit-Field $xml 1
        $portField = Edit-Field $xml 2
        $codeField = Edit-Field $xml 3
        if ($null -eq $hostField -or $null -eq $portField -or $null -eq $codeField) {
            Skip "P-1m the typed channel" "the pairing screen did not show four fields, the paste box plus three (see dumps/p*-p1m-form*.xml)"
        } else {
            $okHost = Type-Into-Field -Center $hostField -Text "127.0.0.1" -Index 1 -Tag "host"
            $okPort = Type-Into-Field -Center $portField -Text "$BridgePort" -Index 2 -Tag "port"
            $okCode = Type-Into-Field -Center $codeField -Text $offer3.code -Index 3 -Tag "code"
            Check "P-1m the typed channel's three boxes hold what was sent into them" `
                ($okHost -and $okPort -and $okCode) "host=$okHost port=$okPort code=$okCode"
            $before = Active-Count
            $tappedDiscover = Tap-Needle "连接并显示指纹" "p1m-discover" 1500
            # The probe is a real TLS round trip; read the screen until the tail appears
            # or the app says why it did not, rather than trusting one fixed sleep.
            $shown = ""
            $tailDeadline = (Get-Date).AddSeconds(25)
            while ((Get-Date) -lt $tailDeadline) {
                $shown = Scroll-And-Dump "p1m-tail"
                if ($shown -match 'text="指纹尾号 \w{8}' -or
                    ($script:failureWordings | Where-Object { $shown -like "*$_*" } | Select-Object -First 1)) { break }
                Start-Sleep -Milliseconds 1200
            }
            $match = [regex]::Match($shown, 'text="指纹尾号 (\w{8})')
            $shownTail = if ($match.Success) { $match.Groups[1].Value.ToUpper() } else { "" }
            $wantTail = $offer3.fingerprint.Substring(0, 8).ToUpper()
            Check "P-1m the typed channel shows the tail before it will send anything" `
                ($tappedDiscover -and $shownTail -eq $wantTail) "the discover button was reached: $tappedDiscover; shown: '$shownTail' expected: '$wantTail'; screen said: $((([regex]::Matches($shown, 'text=""([^""]{6,})""') | ForEach-Object { $_.Groups[1].Value }) -join ' / '))"
            $tappedUngated = Tap-Needle "确认无误，发送配对码" "p1m-ungated" 2500
            $dump = Scroll-And-Dump "p1m-no-send"
            Check "P-1m and the code stays home until the tick is on" `
                ($tappedUngated -and ($dump -like "*请先勾选确认，配对码不会发出*") -and ((Active-Count) -eq $before)) `
                "the send button was reached: $tappedUngated; devices: $before -> $(Active-Count)"
            $tappedTick = Tap-Needle "上面这串指纹尾号" "p1m-tick" 1200
            $ticked = Scroll-And-Dump "p1m-ticked"
            Check "P-1m the tick is a real checkbox, now checked" `
                ($tappedTick -and ($ticked -match 'text="上面这串指纹尾号[^>]*checked="true"' -or $ticked -match 'checked="true"[^>]*text="上面这串指纹尾号')) `
                "the tick row was reached: $tappedTick; see dumps/p*-p1m-ticked*.xml"
            $tappedSend = Tap-Needle "确认无误，发送配对码" "p1m-send" 2000
            $typedVerdict = Wait-For-Pairing-Verdict -TimeoutSec 30
            Check "P-1m a confirmed typed pairing completes and closes the screen" `
                ($tappedSend -and $typedVerdict.Closed) ("the send button was reached: $tappedSend; it said: '" + $typedVerdict.Said + "'; focused: " + $typedVerdict.Focus)
            $null = Update-Manifest $beforeTyped
            Check "P-1m and the computer recorded a second device" ((Active-Count) -eq ($before + 1)) `
                "devices: $before -> $(Active-Count)"
        }

        # ------------------------------------------------------------- P-2 tampering
        $offer2 = New-Offer
        $tampered = Tamper-Digest $offer2.payload
        $before = Active-Count
        $tamperedVerdict = Pair-ByLink $tampered
        Check "P-2 a digest changed by one hex digit is refused by name" `
            ($tamperedVerdict.Said -eq "证书指纹与配对时记录的不一致" -and -not $tamperedVerdict.Closed) `
                "it said: '$($tamperedVerdict.Said)'; closed: $($tamperedVerdict.Closed)"
        Check "P-2 and the refusal happened before the Bridge was asked anything" `
            ((Active-Count) -eq $before) "devices went $before -> $(Active-Count)"
        $beforeClean = Snapshot-Pairings
        # The server-side half of "no request was sent": a one-time token that the
        # tampered attempt had really spent cannot then pair the untouched offer.
        $cleanVerdict = Pair-ByLink $offer2.payload
        Check "P-2 and the untouched offer still works, which means the token was never spent" `
            ($cleanVerdict.Closed) "it said: '$($cleanVerdict.Said)'; devices: $(Active-Count)"
        $null = Update-Manifest $beforeClean

        # --------------------------------------------------------- P-3 one-time token
        $before = Active-Count
        $replayVerdict = Pair-ByLink $offer2.payload
        Check "P-3 a used pair token is refused, not re-honoured" `
            ($replayVerdict.Said -eq "配对码无效或已过期" -and -not $replayVerdict.Closed) `
                "it said: '$($replayVerdict.Said)'; closed: $($replayVerdict.Closed)"
        Check "P-3 and the replay created no device" ((Active-Count) -eq $before) `
            "devices went $before -> $(Active-Count)"

        # ------------------------------------------------------------- P-3b the TTL
        $short = Start-Bridge @("--pair", "--host", $BridgeHost, "--port", "$shortPort",
                                "--data-dir", $script:shortDir, "--pair-ttl", "3s") "ttl"
        if (-not (Wait-BridgeUp $short)) {
            Skip "P-3b an expired code" "the short-TTL Bridge never came up: $((Read-Log $short.OutFile).Trim())"
        } else {
            $shortHealth = Invoke-Admin "Get" "/v1/health" -Port $shortPort | ConvertFrom-Json
            $expired = Invoke-Admin "Post" "/v1/admin/pair" -Port $shortPort | ConvertFrom-Json
            Start-Sleep -Seconds 5
            $expiredVerdict = Pair-ByLink $expired.payload
            Check "P-3b a code past its TTL is refused on the phone too" `
                ($expiredVerdict.Said -eq "配对码无效或已过期" -and -not $expiredVerdict.Closed) `
                "it said: '$($expiredVerdict.Said)'; closed: $($expiredVerdict.Closed); fingerprint=$($shortHealth.fingerprint) vs $($expired.fingerprint)"
            Stop-Process -Id $short.Id -Force -ErrorAction SilentlyContinue
            Start-Sleep -Seconds 1
        }
    }

    # ------------------------------------------------------------------ P-4 revoke
    $devices = @()
    foreach ($d in Get-Devices) { if (-not $d.revoked) { $devices += $d } }
    if ($devices.Count -lt 1) {
        Skip "P-4" "no paired device to revoke"
    } else {
        # Every device this run created: each paired account holds its own device
        # token, and revoking only the first would leave another account reading
        # numbers, which the screen-level check below would then read as "the
        # revoke did not propagate" - a failure that is the script's, not the app's.
        foreach ($d in $devices) {
            Invoke-Admin "Post" "/v1/admin/devices/revoke" `
                (@{ deviceId = $d.id } | ConvertTo-Json -Compress) | Out-Null
        }
        Check "P-4 revoking on the computer deactivates it" ((Active-Count) -eq 0) `
            "still active: $(Active-Count)"
        if ($script:deviceReady -and $script:sqlite) {
            $dump = Refresh-All
            Check "P-4 the phone says revoked, not offline" `
                (($dump -like "*电脑端授权已失效*") -and ($dump -notlike "*电脑离线*")) `
                    "list wording after a revoke; see dumps/p4-after-revoke*.xml"
            # "重新配对后恢复" is the second half of the same plan row, and review gave it a
            # stronger meaning: the recovery has to land on the SAME account, because that
            # account's history and widget slots are the whole reason 「重新配对」 exists.
            # A new account would also read as a pass here, so the row counts the accounts
            # as well as the snapshots.
            $dbNow = Refresh-Db
            # Every account this script pairs is named after the Bridge, so the list shows
            # three identical rows (measured in run 15: dumps/22-p4-menu-p0.xml holds three
            # content-desc="https://10.0.2.2:38491"). Pressing a name therefore cannot
            # identify which account 修复 landed on, and picking one by created_at - what
            # this row used to do - counted snapshots of an account nobody repaired, which
            # is how "0 -> 0" looked like a product failure. Identify it by what the repair
            # actually touches: its credential row gets a new updated_at.
            $credSql = "select a.id || '=' || c.updated_at from accounts a join credentials c on c.id = a.credential_id where a.bridge_id != '';"
            $credBefore = (Query-Db $dbNow $credSql)
            $orphanId = (Scalar-Db $dbNow `
                "select id from accounts where bridge_id != '' order by created_at desc limit 1;")
            $orphanName = (Scalar-Db $dbNow "select display_name from accounts where id = '$orphanId';")
            $accountsBefore = (Scalar-Db $dbNow "select count(*) from accounts where bridge_id != '';")
            $snapsBefore = (Scalar-Db $dbNow `
                "select count(*) from usage_snapshots where account_id = '$orphanId' and success = 1;")

            $recovery = New-Offer
            Invoke-Adb shell am force-stop $Package | Out-Null
            Start-Sleep -Milliseconds 600
            Invoke-Adb shell am start -n "$Package/.ui.account.AccountListActivity" | Out-Null
            Start-Sleep -Seconds 3
            $pressed = Long-Press-Needle ('content-desc="' + $orphanName + '"') "p4-menu"
            $openedRepair = $pressed -and (Tap-Needle 'text="重新配对"' "p4-repair-item" 3000)
            Check "P-4 the revoked account offers its repair entry on its own row" `
                ($openedRepair -and (Get-FocusedActivity) -match "PairActivity") `
                    "pressed: $pressed; focused after the menu: $(Get-FocusedActivity)"

            $repairVerdict = @{ Closed = $false; Said = "the repair screen never opened" }
            if ($openedRepair) {
                $xml = Dump-Ui "p4-repair-form"
                $hf = Edit-Field $xml 1
                $pf = Edit-Field $xml 2
                $cf = Edit-Field $xml 3
                if ($null -eq $hf -or $null -eq $pf -or $null -eq $cf) {
                    # Not a crash to be had here: the screen was open but its boxes were
                    # not in the dump, which is a row to report, not a run to lose.
                    Skip "P-4 the repair path reads the certificate before it will send" `
                        "the pairing screen opened but its three boxes were not in the dump"
                    $repairVerdict = @{ Closed = $false; Said = "no fields on the repair screen" }
                } else {
                    $okH = Type-Into-Field -Center $hf -Text "127.0.0.1" -Index 1 -Tag "p4h"
                    $okP = Type-Into-Field -Center $pf -Text "$BridgePort" -Index 2 -Tag "p4p"
                    $okC = Type-Into-Field -Center $cf -Text $recovery.code -Index 3 -Tag "p4c"
                    $tappedRepairDiscover = Tap-Needle "连接并显示指纹" "p4-repair-discover" 4000
                    $tailShown = Scroll-And-Dump "p4-repair-tail"
                    $tailMatch = [regex]::Match($tailShown, 'text="指纹尾号 (\w{8})')
                    Check "P-4 the repair path reads the certificate before it will send" `
                        ($okH -and $okP -and $okC -and $tappedRepairDiscover -and $tailMatch.Success) `
                        "typed: $okH/$okP/$okC; discover reached: $tappedRepairDiscover; tail shown: '$($tailMatch.Groups[1].Value)'"
                    $tappedRepairTick = Tap-Needle "上面这串指纹尾号" "p4-repair-tick" 1200
                    $tappedRepairSend = Tap-Needle "确认无误，发送配对码" "p4-repair-send" 2000
                    $repairVerdict = Wait-For-Pairing-Verdict -TimeoutSec 30
                    $repairVerdict.Said = ("ticked: $tappedRepairTick; send tapped: $tappedRepairSend; " + $repairVerdict.Said)
                }
            }
            Check "P-4 the repair path finishes and closes the screen" `
                ($repairVerdict.Closed) "it said: '$($repairVerdict.Said)'"

            $dbAfter = Refresh-Db
            $accountsAfter = (Scalar-Db $dbAfter "select count(*) from accounts where bridge_id != '';")
            Check "P-4 and it recovered that account instead of adding another" `
                ($accountsAfter -eq $accountsBefore) `
                    "bridge-linked accounts: $accountsBefore -> $accountsAfter"
            $null = Refresh-All
            $null = Refresh-Db
            # Whose credential moved? Exactly one account should answer to the repair. Zero
            # means the repair wrote nothing it could be traced to; more than one means the
            # press landed somewhere unpredictable. Both say so instead of counting a
            # bystander's snapshots.
            $credAfter = (Query-Db $script:dbPath $credSql)
            $repaired = @()
            foreach ($row in $credAfter) {
                $id = ("" + $row) -split "=", 2
                if ($credBefore -notcontains $row -and $id.Count -eq 2 -and $id[0]) { $repaired += $id[0] }
            }
            $readTarget = if ($repaired.Count -eq 1) { $repaired[0] } else { $orphanId }
            $snapsAfter = (Scalar-Db $script:dbPath `
                "select count(*) from usage_snapshots where account_id = '$readTarget' and success = 1;")
            # 0 -> 0 on its own does not say why. The newest rows for that account say
            # whether the refresh wrote failures, wrote nothing, or wrote for a different
            # account than the one whose row was long-pressed.
            $lastSql = "select success || '/' || source || '/' || substr(replace(usage_data, char(10), ' '), 1, 70)"
            $lastSql = $lastSql + " from usage_snapshots where account_id = '$readTarget' order by timestamp desc limit 3;"
            $lastRows = ((Query-Db $script:dbPath $lastSql) -join " ;; ")
            $rowsForAccount = (Scalar-Db $script:dbPath "select count(*) from usage_snapshots where account_id = '$readTarget';")
            Check "P-4 the same account reads numbers again, history and all" `
                (($repaired.Count -eq 1) -and ([int64]$snapsAfter -gt [int64]$snapsBefore)) `
                ("the repair landed on " + $repaired.Count + " credential row(s) [" + ($repaired -join ",") + "]" +
                 "; successful snapshots for $readTarget : $snapsBefore -> $snapsAfter" +
                 "; rows for it: $rowsForAccount; newest: $lastRows")
        } else {
            Skip "P-4 the phone's wording" $(if ($script:deviceReady) { "no host sqlite3" } else { "no device registered" })
        }
    }

    # ------------------------------------------------------------- P-6 restart
    Stop-Process -Id $bridge.Id -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 2
    $again = Start-Bridge @("--pair", "--host", $BridgeHost, "--port", "$BridgePort",
                            "--data-dir", $script:dataDir) "restart"
    Check "P-6 the Bridge restarted on the same data dir" (Wait-BridgeUp $again) (Read-Log $again.OutFile)
    $identityAfter = $null
    if (Test-Path $identity) {
        $identityAfter = [Convert]::ToBase64String((Get-Content -Encoding Byte -ReadCount 0 $identity))
    }
    Check "P-6 the identity is byte-for-byte the same" `
        ($null -ne $identityBefore -and $identityBefore -eq $identityAfter) `
            "a changed key would force every phone to pair again"
    Check "P-6 and a paired device survived the restart" ((Active-Count) -ge 1) `
        "active devices after restart: $(Active-Count)"
    if ($script:deviceReady -and $script:sqlite) {
        $liveId = (Scalar-Db (Refresh-Db) `
            "select id from accounts where bridge_id != '' order by updated_at desc limit 1;")
        $beforeStamp = (Scalar-Db $script:dbPath `
            "select timestamp from usage_snapshots where account_id = '$liveId' order by timestamp desc limit 1;")
        if ($beforeStamp -eq "") { $beforeStamp = "0" }
        $null = Refresh-All
        $null = Refresh-Db
        $newest = (Scalar-Db $script:dbPath `
            "select success || '|' || timestamp from usage_snapshots where account_id = '$liveId' order by timestamp desc limit 1;")
        $newestSuccess = ($newest -split "\|")[0]
        $afterStamp = ($newest -split "\|")[1]
        # No re-pairing happened between the two reads, so a newer row can only mean the
        # device token the phone already had still works after the computer restarted -
        # A2's promise, restated as a row. Success is read from the same newest row,
        # because an older success row would let a failing read pass as "it advanced".
        Check "P-6 the phone reads through the same pairing, without pairing again" `
            ($afterStamp -ne "" -and [int64]$afterStamp -gt [int64]$beforeStamp) `
                "last read $beforeStamp -> $afterStamp for account $liveId"
        Check "P-6 and that newest row is a success, not an error" ($newestSuccess -eq "1") `
            "newest row reads: $newest"
    } else {
        Skip "P-6 the phone reads after the restart" "no device or no sqlite3"
    }

    # ------------------------------------------------------------- P-5 guards
    $code = 0
    try {
        Invoke-WebRequest -Uri "http://${BridgeHost}:$BridgePort/v1/accounts/codex/usage" `
            -UseBasicParsing -TimeoutSec 6 -DisableKeepAlive | Out-Null
        $code = 200
    } catch {
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode } else { $code = -1 }
    }
    Check "P-5 a plaintext read of the quota route is not served" `
        ($code -eq 400 -or $code -eq 401 -or $code -eq 426 -or $code -eq -1) "got HTTP $code"
    $code = 0
    try {
        Invoke-Admin "Get" "/v1/accounts/codex/usage" | Out-Null
        $code = 200
    } catch {
        if ($_.Exception.Response) { $code = [int]$_.Exception.Response.StatusCode } else { $code = -1 }
    }
    Check "P-5 and an unauthenticated TLS read answers 401, not data" ($code -eq 401) "got HTTP $code"

    # ------------------------------------------------------------- P-7 plaintext scan
    $secrets = @($offer.pairToken, $offer.code, $offer3.pairToken, $offer3.code,
                 $offer2.pairToken, $offer2.code, $recovery.pairToken, $recovery.code)
    $leak = @()
    foreach ($name in @("pairing.json", "state.json")) {
        foreach ($dir in @($script:dataDir, $script:shortDir)) {
            $path = Join-Path $dir $name
            if (-not (Test-Path $path)) { continue }
            $text = Get-Content -Raw $path
            foreach ($secret in $secrets) {
                if ($secret -and $text -like "*$secret*") { $leak += "$dir\$name" }
            }
        }
    }
    Check "P-7 no plaintext pair token or code on the computer's disk" ($leak.Count -eq 0) ($leak -join ",")
    # A scan that found nothing is only evidence if the file it scanned was the real
    # store: assert the digests are there, otherwise "no plaintext" is just an empty file.
    $scanned = 0
    foreach ($dir in @($script:dataDir, $script:shortDir)) {
        $pp = Join-Path $dir "pairing.json"
        if (-not (Test-Path $pp)) { continue }
        if ((Get-Content -Raw $pp) -match '"TokenHash"') { $scanned++ }
    }
    Check "P-7 and the pairing stores it scanned really held digests" ($scanned -ge 1) `
        "pairing.json files carrying TokenHash: $scanned"
    if ($script:deviceReady -and $script:sqlite) {
        # The pulled copy is the phone's own bytes, so scanning it asks the same
        # question of the device side: an 8-char code is not secret for long, but a
        # pair token in plaintext next to the account row is.
        $bytes = [System.IO.File]::ReadAllBytes($script:dbPath)
        $text = [System.Text.Encoding]::ASCII.GetString($bytes)
        $wal = "$script:dbPath-wal"
        if (Test-Path $wal) {
            $text += [System.Text.Encoding]::ASCII.GetString([System.IO.File]::ReadAllBytes($wal))
        }
        $inDb = @()
        foreach ($secret in $secrets) {
            if ($secret -and $text.Contains($secret)) { $inDb += $secret.Substring(0, 6) }
        }
        Check "P-7 and the app database holds no plaintext pairing secret" ($inDb.Count -eq 0) `
            "found prefixes: $($inDb -join ',')"
        # The envelope SecureStorage writes is Base64(iv || ciphertext) with no prefix,
        # so "it has the right type" alone would also be true of a plaintext JSON payload
        # - which is the one thing Spec §7 forbids. Both halves are asserted.
        $cred = (Scalar-Db $script:dbPath `
            "select c.type || '|' || substr(c.encrypted_payload, 1, 24) || '|' || c.protection from credentials c join accounts a on a.credential_id = c.id where a.bridge_id != '' limit 1;")
        Check "P-8 the paired account's credential is a BRIDGE_TOKEN row with an opaque payload" `
            ($cred -match "^BRIDGE_TOKEN\|[A-Za-z0-9+/]{20,}" -and $cred -notmatch '[{"=]') "read back: $cred"
    } else {
        Skip "P-7 the phone's database" "no device or no sqlite3"
    }
    if ($script:deviceReady) {
        $logPath = Join-Path $script:work "logcat.txt"
        # Into a file, then regex: `-join`ing a 20 MB log and looping every line per
        # secret spent nine minutes of the run that tried it (measured 2026-10-03).
        cmd /c "adb -s $Serial logcat -d -v brief > `"$logPath`"" 2>&1 | Out-Null
        $log = [System.IO.File]::ReadAllText($logPath, [System.Text.Encoding]::UTF8)
        # Attribution by process, which is what the plan's P-7 asks for: this harness
        # types the code with `adb shell input text`, and adbd logs the command line it
        # was given - including the secret. That echo is the test's own output, not the
        # app writing a credential somewhere, and treating it as a leak would fail a
        # correct build while treating every hit as an echo would pass a leaking one.
        $found = @()
        $echoed = 0
        foreach ($secret in ($secrets | Where-Object { $_ })) {
            foreach ($m in [regex]::Matches($log, [regex]::Escape($secret))) {
                $from = $log.LastIndexOf("`n", $m.Index) + 1
                $to = $log.IndexOf("`n", $m.Index)
                if ($to -lt 0) { $to = $log.Length }
                $line = $log.Substring($from, $to - $from).Trim()
                # -v brief pads the tag: "I/adbd    (  529): ...", so the space before
                # the parenthesis is part of the format, not a detail to get lucky with.
                if ($line -match "/adbd\s*\(" -and $line -match "input text") { $echoed++; continue }
                $found += $line.Substring(0, [Math]::Min(120, $line.Length))
            }
        }
        Check "P-7 logcat carries no pairing secret from any process but the harness's own typing" `
            ($found.Count -eq 0) ($found -join " | ")
        if ($echoed -gt 0) {
            Note "$echoed logcat line(s) were adbd echoing this harness's own input text command; excluded by process attribution, not by silence"
        }
        # The device name is what a successful pairing must leave behind, so an empty
        # log would make the row above a pass over nothing.
        $lines = @([regex]::Matches($log, "`n")).Count
        Check "P-7 and there was a log to scan" ($lines -gt 200) "logcat lines: $lines"
    } else {
        Skip "P-7 logcat" "no device registered"
    }

    # ------------------------------------------- hand the device back clean, then recurse
    # The C-series adds its own accounts by name and reads "the first row that matches".
    # Running it while this run's paired accounts are still on the device made its add
    # flow miss the button under the extra rows, so `BridgePhone` was never created
    # (measured 2026-10-03: "rows named BridgePhone: 0") and 30 of its 58 rows then
    # reported on whichever account was actually on screen - one of them a DeepSeek
    # balance. Not a product regression; a harness that handed over a dirty device.
    if ($script:deviceReady -and $script:sqlite) {
        $cleared = Clear-RunPairings -Tag "mid"
        Check "P-9's device is handed over clean: no account this run paired is left" `
            ($cleared.MineLeft -eq 0) "rows this run made and still present: $($cleared.MineLeft)"
        Check "P-9's device is handed over clean: no paired computer left either" `
            ($cleared.MyBridgesLeft -eq 0) "computer rows this run made and still present: $($cleared.MyBridgesLeft)"
    } else {
        Skip "P-9's clean handover" "no device or no sqlite3"
    }

    # ------------------------------------------------------------- P-9 regressions
    if (-not $SkipLegacy) {
        foreach ($name in @("assert-bridge.ps1", "assert-bridge-phone.ps1")) {
            $path = Join-Path $PSScriptRoot $name
            if (-not (Test-Path $path)) { Skip "P-9 $name" "not present"; continue }
            $logPath = Join-Path $script:work "legacy-$name.log"
            $r = Wait-ChildScript (Start-ChildScript -Path $path -LogPath $logPath) `
                -TimeoutSec $LegacyTimeoutSec
            $tail = ((Read-Log $logPath) -split "`n" | Select-String "RESULT|FAILURES" |
                Select-Object -Last 2) -join " / "
            if (-not $r.Exited) {
                Check "P-9 $name still passes" $false `
                    "it had not returned after $LegacyTimeoutSec s so it was killed; its log ends: $tail"
                continue
            }
            if ($null -eq $r.Code) { throw "no exit code from $name; the regression row would be vacuous" }
            Check "P-9 $name still passes" ($r.Code -eq 0) "exit $($r.Code); $tail"
        }
    } else {
        Skip "P-9" "-SkipLegacy was given"
    }

    # ------------------------------------------------------- P-11 the bind acceptance
    # A second paired Bridge on its own ports, while this run's is still holding the
    # LAN: P-11 is the row that says the advertised addresses match the real bind,
    # and it must keep saying that while something else is listening.
    $bind = Join-Path $PSScriptRoot "assert-bridge-bind.ps1"
    if (-not (Test-Path $bind)) {
        Skip "P-11 assert-bridge-bind.ps1" "not present"
    } else {
        $logPath = Join-Path $script:work "legacy-assert-bridge-bind.log"
        $r = Wait-ChildScript (Start-ChildScript -Path $bind -LogPath $logPath) `
            -TimeoutSec $BindTimeoutSec
        $tail = ((Read-Log $logPath) -split "`n" | Select-String "RESULT|FAILURES" |
            Select-Object -Last 2) -join " / "
        if (-not $r.Exited) {
            Check "P-11 assert-bridge-bind.ps1 still passes" $false `
                "it had not returned after $BindTimeoutSec s so it was killed; its log ends: $tail"
        } else {
            if ($null -eq $r.Code) { throw "no exit code from assert-bridge-bind.ps1" }
            Check "P-11 assert-bridge-bind.ps1 still passes" ($r.Code -eq 0) "exit $($r.Code); $tail"
        }
    }

    # ------------------------------------------------------------ cleanup: the machine
    # Everything above writes to a device whose only DeepSeek keys are these two
    # accounts. The pairing rows are deleted through the app's own UI; the DeepSeek
    # rows are then compared to the baseline taken before any of this ran.
    if ($script:deviceReady -and $script:sqlite) {
        # Control for the scoped cleanup, and the only way to prove it: pair a second
        # Bridge that this run deliberately does NOT put in its ledger, clean up, and
        # show that pairing survived. Without this row "it only deletes what it
        # recorded" is a sentence about code nobody tested - the earlier version of this
        # script swept every bridge row on the device and every assertion still passed,
        # because the device only ever held this script's own pairings.
        $decoyBridge = Start-Bridge @("--pair", "--host", $BridgeHost, "--port", "$($script:decoyPort)",
                                       "--data-dir", $script:decoyDir) "decoy"
        $decoyId = ""
        $decoyAccountIds = @()
        if (-not (Wait-BridgeUp $decoyBridge)) {
            Skip "cleanup leaves an unrecorded pairing alone" "the decoy Bridge never came up"
        } else {
            $decoyOffer = Invoke-Admin "Post" "/v1/admin/pair" -Port $script:decoyPort | ConvertFrom-Json
            $beforeDecoy = @(Query-Db (Refresh-Db) "select id from accounts where bridge_id != '';")
            $decoyVerdict = Pair-ByLink $decoyOffer.payload
            $afterDecoy = Snapshot-Pairings
            foreach ($id in @($afterDecoy.Accounts.Keys)) {
                if ($beforeDecoy -notcontains $id) { $decoyAccountIds += $id }
            }
            $decoyId = $decoyOffer.bridgeId
            Check "the decoy paired, and is not in this script's ledger" `
                ($decoyVerdict.Closed -and $decoyAccountIds.Count -ge 1 -and `
                 -not $script:pairBridges.ContainsKey($decoyId)) `
                    "closed: $($decoyVerdict.Closed); new accounts: $($decoyAccountIds -join ','); ledger has it: $($script:pairBridges.ContainsKey($decoyId))"
        }

        $cleared = Clear-RunPairings -Tag "end"
        Check "cleanup removes the accounts this run paired" ($cleared.MineLeft -eq 0) `
            "rows this run made and still present: $($cleared.MineLeft)"
        Check "cleanup removes the computers this run paired" ($cleared.MyBridgesLeft -eq 0) `
            "computer rows this run made and still present: $($cleared.MyBridgesLeft)"
        if ($decoyId) {
            $still = Snapshot-Pairings
            Check "cleanup leaves a pairing this script did not record alone" `
                ($still.Bridges.ContainsKey($decoyId) -and `
                 (@($decoyAccountIds | Where-Object { $still.Accounts.ContainsKey($_) }).Count -eq $decoyAccountIds.Count)) `
                    "decoy computer present: $($still.Bridges.ContainsKey($decoyId)); its accounts left: $(@($decoyAccountIds | Where-Object { $still.Accounts.ContainsKey($_) }).Count) of $($decoyAccountIds.Count)"
            # This run made it, so this run may remove it - explicitly, and verified.
            foreach ($id in $decoyAccountIds) {
                $name = $still.Accounts[$id].Name
                $null = Remove-OneAccount -Name $name -Tag "decoy-$id"
            }
            $decoyTail = $decoyOffer.fingerprint.Substring(0, 8).ToUpper()
            $null = Forget-BridgeByTail -Tail $decoyTail -Tag "decoy"
            $gone = Snapshot-Pairings
            Check "cleanup the decoy is removed when the script chooses to remove it" `
                (-not $gone.Bridges.ContainsKey($decoyId)) `
                    "decoy still present: $($gone.Bridges.ContainsKey($decoyId))"
        }
        $after = (Query-Db (Refresh-Db) `
            "select id || '=' || credential_id from accounts where provider_id='deepseek' order by id;") -join ","
        Check "cleanup the DeepSeek accounts and their credentials are exactly what they were" `
            ($after -eq $script:deepseekBaseline) "before: $script:deepseekBaseline / after: $after"
    } else {
        Skip "cleanup" "no device or no sqlite3"
    }

    Note "P-10 stays unscripted on purpose: Wi-Fi QR pairing awaits D1, and a pairing that survives Codex signing out cannot be tested without logging the real account out. The latter's shape is covered by the Go side's stand-in app server."

    Summary
} finally {
    Stop-Everything
}
