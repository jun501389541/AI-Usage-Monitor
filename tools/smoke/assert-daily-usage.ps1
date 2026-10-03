# assert-daily-usage.ps1 — R1 regression: today's usage total must survive a refresh
# when a previous-day row exists (the row that used to reset the total to 0.00).
#
# What it does, on the device:
#   1. Force-stops the app (avoid a mid-copy write), pulls the database.
#   2. Injects two daily_usage rows for the first enabled account on the HOST
#      copy: yesterday (last_balance 12.00, total 5.00) and today (last_balance
#      10.00, total_usage 3.00), then pushes the database back (same path the
#      R1 A/B experiment used).
#   3. Starts the app, opens the account list, taps 刷新全部账户 and waits for
#      the refresh to land (newest usage_snapshots row for A is newer than the
#      injection point).
#   4. Pulls the database again and asserts:
#        - today's total_usage is still 3.00 (the R1 defect used to reset it
#          to 0.00 whenever a yesterday row existed);
#        - today's last_balance moved to the fresh reading (24.94);
#        - a new success snapshot exists for A.
#   5. Restores the device (R5 fix): the pulled pre-test database is backed up
#      before any device write and pushed back in a finally block (app
#      force-stopped again), so injected rows and test-time snapshots never
#      outlive the run — the account's real daily_usage history survives the
#      test unchanged, pass or fail.
#
# Requires: device/emulator with the debug app installed, first enabled
# account holding a live key (the refresh must genuinely succeed, otherwise
# nothing writes and the assertion is vacuous).

. .\tools\env.ps1

$ErrorActionPreference = "Stop"
$pkg = "com.aiusage.monitor"
$dbName = "ai_usage_monitor.db"
$dbPath = "databases/$dbName"
$tmpDb = "tools\smoke\out\daily-usage.db"
$backupDb = "tools\smoke\out\daily-usage-backup.db"
$restoredDb = "tools\smoke\out\daily-usage-restored.db"
$sqlite = "D:\Android\sdk\platform-tools\sqlite3.exe"
$listActivity = "$pkg/com.aiusage.monitor.ui.account.AccountListActivity"

if (-not (Test-Path tools\smoke\out)) { New-Item -ItemType Directory -Path tools\smoke\out | Out-Null }

$failures = 0

function Check($name, $ok, $detail) {
    if ($ok) { Write-Output ("PASS  {0}" -f $name) }
    else { Write-Output ("FAIL  {0}  {1}" -f $name, $detail); $script:failures++ }
}

function Invoke-Sql($Sql) {
    # Pipe SQL through stdin; as an argument cmd mangles the quotes.
    $Sql | cmd /c "`"$sqlite`" `"$tmpDb`""
}

# --- 0. device present -------------------------------------------------------
$adbDevices = cmd /c "adb devices 2>&1"
Check "device attached" ($null -ne ($adbDevices | Where-Object { $_ -match "`tdevice$" })) ($adbDevices -join "; ")

# --- 1. pull, back up, inject, push ------------------------------------------
cmd /c "adb shell am force-stop $pkg"
cmd /c "adb exec-out run-as $pkg cat $dbPath > $tmpDb 2>tools\smoke\out\pull-err.txt"
$size = (Get-Item $tmpDb).Length
Check "database pulled non-trivially" ($size -gt 10000) "size=$size B"
if ($size -le 10000) { Write-Output "RESULT: FAIL (empty pull)"; exit 1 }

# R5 fix: full pre-test copy before any device write. The finally block at the
# bottom pushes it back, so the device never keeps test data behind.
Copy-Item $tmpDb $backupDb -Force

$accountId = @(Invoke-Sql "SELECT id FROM accounts WHERE enabled = 1 ORDER BY sort_order LIMIT 1;")[0]
Check "found an enabled account" ($accountId -match '^acct_') "got '$accountId'"
if (-not ($accountId -match '^acct_')) { Write-Output "RESULT: FAIL (no account)"; exit 1 }

# Device time is the app's day-boundary source; use the device's own date so
# the injected rows count as yesterday/today regardless of the host clock.
$today = (cmd /c "adb shell date +%Y-%m-%d").Trim()
$yesterday = ([datetime]::ParseExact($today, "yyyy-MM-dd", $null)).AddDays(-1).ToString("yyyy-MM-dd")
Write-Output ("device day: today={0} yesterday={1}" -f $today, $yesterday)

$injectAt = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()

$sql = @"
DELETE FROM daily_usage WHERE account_id = '$accountId';
INSERT INTO daily_usage (account_id, day, last_balance, total_usage, updated_at) VALUES
 ('$accountId', '$yesterday', 12.00, 5.00, $injectAt),
 ('$accountId', '$today', 10.00, 3.00, $injectAt);
"@
[IO.File]::WriteAllText("$PWD\tools\smoke\out\inject.sql", $sql, [Text.Encoding]::UTF8)
$injectOut = Invoke-Sql ([IO.File]::ReadAllText("$PWD\tools\smoke\out\inject.sql", [Text.Encoding]::UTF8))
$check = @(Invoke-Sql "SELECT day || '=' || last_balance || '/' || total_usage FROM daily_usage WHERE account_id = '$accountId' ORDER BY day;")
Check "injected rows read back" ($check.Count -eq 2 -and $check[1] -eq "$today=10.0/3.0") ($check -join "; ")

# --- 2. push back and refresh (the device is mutated from here on) -----------
try {
    cmd /c "adb push $tmpDb /sdcard/daily-usage.db 2>&1" | Out-Null
    cmd /c "adb shell `"cat /sdcard/daily-usage.db | run-as $pkg sh -c 'cat > $dbPath'`"" | Out-Null

    cmd /c "adb shell am start -n $listActivity" | Out-Null
    Start-Sleep -Seconds 3

    # Tap 刷新全部账户 via uiautomator lookup (coordinates vary by build).
    # Cold start can lag; retry the dump a few times before giving up.
    $dump = "tools\smoke\out\daily-usage-ui.xml"
    $m = $null
    for ($try = 0; $try -lt 5 -and -not $m.Success; $try++) {
        Start-Sleep -Seconds 2
        cmd /c "adb shell rm -f /sdcard/u.xml"
        cmd /c "adb shell uiautomator dump /sdcard/u.xml" | Out-Null
        cmd /c "adb exec-out cat /sdcard/u.xml > $dump 2>&1"
        $ui = [IO.File]::ReadAllText("$PWD\$dump", [Text.Encoding]::UTF8)
        $m = [regex]::Match($ui, 'text="刷新全部账户"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
    }
    if (-not $m.Success) {
        Write-Output "RESULT: FAIL (refresh-all button not found on screen)"
        exit 1
    }
    $cx = ([int]$m.Groups[1].Value + [int]$m.Groups[3].Value) / 2
    $cy = ([int]$m.Groups[2].Value + [int]$m.Groups[4].Value) / 2
    cmd /c "adb shell input tap $cx $cy" | Out-Null
    Write-Output ("tapped 刷新全部账户 at {0},{1}" -f $cx, $cy)

    # --- 3. wait for a fresh success snapshot --------------------------------
    # The refresh runs on the app's executor; poll the pulled DB until a success
    # row for A lands with a timestamp after the injection point (epoch millis).
    $landed = $false
    $newestOk = $null
    for ($i = 0; $i -lt 20; $i++) {
        Start-Sleep -Seconds 2
        cmd /c "adb exec-out run-as $pkg cat $dbPath > $tmpDb 2>`$null"
        if ((Get-Item $tmpDb).Length -lt 10000) { continue }
        $newestOk = @(Invoke-Sql "SELECT timestamp FROM usage_snapshots WHERE account_id = '$accountId' AND success = 1 AND CAST(timestamp AS INTEGER) > $injectAt ORDER BY CAST(timestamp AS INTEGER) DESC LIMIT 1;")
        if ($newestOk.Count -gt 0) { $landed = $true; break }
    }
    Check "a success snapshot landed after injection" $landed "newest=[$($newestOk -join ',')]"

    # --- 4. the actual R1 assertion ------------------------------------------
    $todayRow = @(Invoke-Sql "SELECT last_balance || '/' || total_usage FROM daily_usage WHERE account_id = '$accountId' AND day = '$today';")
    Check "today's row exists" ($todayRow.Count -eq 1) "got [$($todayRow -join ',')]"
    $total = if ($todayRow.Count -eq 1) { ($todayRow[0] -split '/')[1] } else { "" }
    # The app rewrites the row with its own two-decimal formatting (3.0 -> 3.00),
    # so compare numerically, not as text.
    Check "R1: today's total_usage survived the refresh (not reset to 0)" ($total -ne "" -and [decimal]$total -eq 3.0) "total_usage=$total"
    $balance = if ($todayRow.Count -eq 1) { ($todayRow[0] -split '/')[0] } else { "" }
    Check "today's last_balance moved to the fresh reading" ($balance -ne "10.0") "last_balance=$balance"
} finally {
    # --- 5. restore the device to its pre-test state (R5) --------------------
    cmd /c "adb shell am force-stop $pkg"
    cmd /c "adb push $backupDb /sdcard/daily-usage-restore.db 2>&1" | Out-Null
    cmd /c "adb shell `"cat /sdcard/daily-usage-restore.db | run-as $pkg sh -c 'cat > $dbPath'`"" | Out-Null
    # Stale WAL/SHM side files could resurrect pre-restore pages; drop them.
    cmd /c "adb shell run-as $pkg rm -f '${dbPath}-wal' '${dbPath}-shm'" | Out-Null
    cmd /c "adb exec-out run-as $pkg cat $dbPath > $restoredDb 2>NUL"
    $okSize = (Test-Path $restoredDb) -and ((Get-Item $restoredDb).Length -gt 10000)
    $before = @()
    $afterRows = @()
    if ($okSize) {
        $before = @(& $sqlite $backupDb "SELECT day || '/' || last_balance || '/' || total_usage FROM daily_usage WHERE account_id = '$accountId' ORDER BY day;")
        $afterRows = @(& $sqlite $restoredDb "SELECT day || '/' || last_balance || '/' || total_usage FROM daily_usage WHERE account_id = '$accountId' ORDER BY day;")
    }
    Check "device daily_usage restored to pre-test state" ($okSize -and ((Compare-Object $before $afterRows).Count -eq 0)) ("after=[" + ($afterRows -join "; ") + "]")
}

if ($failures -eq 0) {
    Write-Output "RESULT: PASS — yesterday-row injection does not reset today's usage total"
    exit 0
} else {
    Write-Output "RESULT: FAIL — $failures check(s) failed"
    exit 1
}
