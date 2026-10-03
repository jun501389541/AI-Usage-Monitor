<#
    Phase 4 history acceptance: the window read, and retention that cannot take
    the number off the screen with it.

    Covers docs/PHASE-4-PLAN.md rows H1, H2, H3 and H7. Deleting history is the
    one operation in this app that re-querying cannot undo, so the checks are
    written the other way round from most tests: inject readings old enough to
    be eligible, let the app decide, then assert both that they went AND that the
    two rows the UI depends on stayed.

    Everything runs against a full backup restored in finally, and every push is
    size-checked at both ends (review finding R16): the database on this device
    is the only store of two API keys that cannot be typed back in.

    Usage:
        powershell -File tools\smoke\assert-history.ps1
        powershell -File tools\smoke\assert-history.ps1 -SkipPrune
#>
param(
    [string]$Serial = "emulator-5554",
    [string]$Package = "com.aiusage.monitor",
    [switch]$SkipPrune
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\..\env.ps1"

$outDir = Join-Path $PSScriptRoot "out"
if (-not (Test-Path $outDir)) {
    New-Item -ItemType Directory -Path $outDir | Out-Null
}
$sqlite = Join-Path $env:ANDROID_HOME "platform-tools\sqlite3.exe"
if (-not (Test-Path $sqlite)) {
    throw "sqlite3.exe not found at $sqlite"
}

$dbName = "ai_usage_monitor.db"
$live = Join-Path $outDir "history-live.db"
$backup = Join-Path $outDir "history-backup.db"
$day = 86400000L

$script:checks = 0
$script:failures = 0

function Check {
    param([string]$Name, [bool]$Ok, [string]$Detail = "")
    $script:checks++
    if ($Ok) {
        Write-Output "PASS  $Name"
    } else {
        $script:failures++
        if ($Detail) {
            Write-Output "FAIL  $Name -- $Detail"
        } else {
            Write-Output "FAIL  $Name"
        }
    }
}

function Query-Db {
    param([string]$Path, [string]$Sql)
    $temp = Join-Path $env:TEMP ("history-" + [Guid]::NewGuid().ToString("N") + ".sql")
    [IO.File]::WriteAllText($temp, ".mode list`n" + $Sql + "`n", [Text.Encoding]::ASCII)
    $result = cmd /c "`"$sqlite`" -noheader -list `"$Path`" < `"$temp`" 2>&1"
    Remove-Item $temp -Force
    return @($result | Where-Object { "$_" -ne "" } | ForEach-Object { "$_".Trim() })
}

function Pull-Db {
    param([string]$Destination)
    cmd /c "adb -s $Serial exec-out run-as $Package cat databases/$dbName > `"$Destination`"" 2>&1 | Out-Null
    if (-not (Test-Path $Destination)) {
        throw "pull failed: $Destination was not created"
    }
    $bytes = (Get-Item $Destination).Length
    if ($bytes -lt 10240) {
        throw "pull produced only $bytes bytes; that is not the app database"
    }
    return $bytes
}

function Push-Db {
    param([string]$Source)
    # Size-checked at both ends: a truncated write here would destroy the only
    # copy of two live credentials.
    $expected = (Get-Item $Source).Length
    adb -s $Serial push $Source "/sdcard/history.db" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "adb push failed (exit $LASTEXITCODE); the app database was not touched"
    }
    $staged = ((adb -s $Serial shell "stat -c %s /sdcard/history.db") -replace '\D', '')
    if ([long]$staged -ne [long]$expected) {
        throw "staged file is $staged bytes, expected $expected; aborting before overwrite"
    }
    adb -s $Serial shell "cat /sdcard/history.db | run-as $Package sh -c 'cat > databases/$dbName'" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "copy into app data failed (exit $LASTEXITCODE); restore $backup by hand"
    }
    $inside = ((adb -s $Serial shell "run-as $Package stat -c %s databases/$dbName") -replace '\D', '')
    if ([long]$inside -ne [long]$expected) {
        throw "database is $inside bytes after the copy, expected $expected"
    }
    adb -s $Serial shell rm -f /sdcard/history.db | Out-Null
}

function Stop-App {
    adb -s $Serial shell am force-stop $Package | Out-Null
}

function Node-Center {
    param([string]$Xml, [string]$Text)
    $patterns = @(
        ('text="' + [regex]::Escape($Text) + '"[^>]*?bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"'),
        # The account cards carry their display name as a content description on
        # the clickable container, which is the box worth tapping: the text nodes
        # inside it are children, and a tap on the row's own centre is what the
        # card reliably receives.
        ('content-desc="' + [regex]::Escape($Text) + '"[^>]*?bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"')
    )
    foreach ($pattern in $patterns) {
        $match = [regex]::Match($Xml, $pattern)
        if ($match.Success) {
            $x1 = [int]$match.Groups[1].Value
            $y1 = [int]$match.Groups[2].Value
            $x2 = [int]$match.Groups[3].Value
            $y2 = [int]$match.Groups[4].Value
            return , @([int](($x1 + $x2) / 2), [int](($y1 + $y2) / 2))
        }
    }
    return $null
}

function History-Checksum {
    param([string]$Path)
    # Count plus a per-row digest of the columns that matter. A change to any
    # reading, in any order, moves it.
    return @(Query-Db $Path "select count(*) || '/' || ifnull(sum(id), 0) || '/' || " +
        "ifnull(length(group_concat(account_id || ':' || timestamp || ':' || success)), 0) " +
        "from usage_snapshots;")[0]
}

function Dump-Ui {
    param([switch]$Quiet)
    if (-not $Quiet) {
        # A force-stopped app is a stopped app: the launcher shows a grey
        # placeholder instead of the widget until something launches it again.
        adb -s $Serial shell am start -n "$Package/$Package.ui.account.AccountListActivity" | Out-Null
        Start-Sleep -Seconds 3
        adb -s $Serial shell input keyevent KEYCODE_HOME | Out-Null
        Start-Sleep -Seconds 2
    }
    adb -s $Serial shell rm -f /sdcard/u.xml | Out-Null
    adb -s $Serial shell uiautomator dump /sdcard/u.xml | Out-Null
    $path = Join-Path $outDir "history-ui.xml"
    cmd /c "adb -s $Serial exec-out cat /sdcard/u.xml > `"$path`"" 2>&1 | Out-Null
    return [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8)
}

Write-Output "# Phase 4 history acceptance"
Write-Output "# device=$Serial package=$Package"

$bytes = Pull-Db $live
Write-Output "# database=$bytes bytes"
Copy-Item $live $backup -Force

$account = @(Query-Db $live "select id from accounts order by sort_order limit 1;")[0]
$deviceNow = [long]((adb -s $Serial shell date +%s) -replace '\D', '') * 1000L
Write-Output "# account=$account device-now=$deviceNow"

# ------------------------------------------------- H3: the window reads correctly

$all = @(Query-Db $live "select count(*) from usage_snapshots where account_id = '$account';")
$today = @(Query-Db $live "select count(*) from usage_snapshots where account_id = '$account' and timestamp >= $deviceNow - ($deviceNow % $day);")
$sample = @(Query-Db $live "select timestamp from usage_snapshots where account_id = '$account' order by timestamp desc limit 3;")
Check "H3: the account has stored readings to read back" ([int]$all[0] -gt 0) "rows=$($all[0])"
Check "H3: readings are returned newest first" `
    ($sample.Count -lt 2 -or [long]$sample[0] -ge [long]$sample[1]) "first=$($sample[0]) second=$($sample[1])"
Check "H3: a window that has not started yet reads empty" `
    (@(Query-Db $live "select count(*) from usage_snapshots where account_id = '$account' and timestamp >= $deviceNow and timestamp < $([long]$deviceNow + $day);")[0] -eq "0") `
    "future-window rows"

if ($SkipPrune) {
    Write-Output "SKIP  H1/H2/H7 injection (-SkipPrune)"
} else {
    try {
        # --------------------------------------------- H1: retention deletes the old
        # Three successes forty days back. They are older than the window and are
        # neither the account's last success nor its newest row, so they are the
        # rows retention exists to remove -- and they are copies of a real payload,
        # so the decode path is exercised too.
        Stop-App
        $injectedAt = [long]$deviceNow - 40L * $day
        cmd /c "`"$sqlite`" `"$live`" `"insert into usage_snapshots (account_id, timestamp, usage_data, source, success) select '$account', $injectedAt, usage_data, source, 1 from usage_snapshots where account_id = '$account' and success = 1 limit 1;`" 2>&1" | Out-Null
        cmd /c "`"$sqlite`" `"$live`" `"insert into usage_snapshots (account_id, timestamp, usage_data, source, success) select '$account', $($injectedAt + 60000), usage_data, source, 1 from usage_snapshots where account_id = '$account' and success = 1 limit 1;`" 2>&1" | Out-Null
        $beforeInject = @(Query-Db $live "select count(*) from usage_snapshots where account_id = '$account' and timestamp < $injectedAt + 70000;")
        Push-Db $live
        Check "H1: the injected old readings are on the device before the prune" `
            ([int]$beforeInject[0] -ge 2) "rows=$($beforeInject[0])"

        # The widget's refresh is the only retention trigger a person can invoke on
        # demand; the alarm path runs the same call.
        $ui = Dump-Ui
        $refresh = Node-Center -Xml $ui -Text "刷新"
        Check "H1: the refresh affordance is on screen" ($null -ne $refresh) ""
        if ($null -ne $refresh) {
            adb -s $Serial shell input tap $refresh[0] $refresh[1] | Out-Null
            Start-Sleep -Seconds 12
        }
        $after = Join-Path $outDir "history-after-prune.db"
        Pull-Db $after | Out-Null
        $remaining = @(Query-Db $after "select count(*) from usage_snapshots where account_id = '$account' and timestamp >= $injectedAt and timestamp < $($injectedAt + 70000);")
        Check "H1: the expired readings are gone after the refresh" ($remaining[0] -eq "0") "left=$($remaining[0])"

        # ------------------------------------- H2: the number on screen survived it
        $lastSuccess = @(Query-Db $after "select count(*) from usage_snapshots where account_id = '$account' and success = 1;")
        Check "H2: the account still has a last successful reading" ([int]$lastSuccess[0] -ge 1) "rows=$($lastSuccess[0])"
        $uiAfter = Dump-Ui
        Check "H2: the widget still shows a balance rather than an em dash" `
            ($uiAfter -match 'text="¥[0-9]') ""
        Check "H2: the widget still states the reading's age" `
            ($uiAfter -match '(刚刚|分钟前|小时前|数据已过期)') ""

        # ------------------------------------------------------- H4: the index plan
        # The window query is the one the history list runs. Asserting the plan
        # rather than trusting that an index "will be used" is the difference
        # between a bounded read and a full scan that gets slower forever.
        $plan = @(Query-Db $after ("explain query plan select id from usage_snapshots " +
                "where account_id = '$account' and timestamp >= $($deviceNow - 30 * $day) " +
                "and timestamp < $deviceNow order by timestamp desc, id desc limit 10;"))
        $planText = $plan -join " "
        Check "H4: the window query uses the account/time index" `
            ($planText -match 'USING (COVERING )?INDEX idx_snapshots_account_time') ($planText -replace '\s+', ' ')
        Check "H4: the window query does not scan the whole snapshot table" `
            ($planText -notmatch 'SCAN TABLE usage_snapshots') ($planText -replace '\s+', ' ')

        # --------------------------------- H5: reconfiguring a widget keeps history
        # The plan's own risk list: the widget binding tables and the history
        # tables share one database and one store class, so a configuration write
        # that reaches past its own tables would show up here and nowhere else.
        $historyBefore = History-Checksum $after
        $dailyBefore = @(Query-Db $after "select count(*) from daily_usage;")[0]
        $slotsBefore = @(Query-Db $after "select widget_id || '/' || slot_index || '=' || account_id from widget_slots order by widget_id, slot_index;")
        $otherName = @(Query-Db $after "select display_name from accounts where id <> '$account' order by sort_order limit 1;")
        if ($otherName.Count -ge 1) {
            adb -s $Serial shell am start -n "$Package/$Package.widget.WidgetConfigActivity" `
                --ei com.aiusage.monitor.extra.WIDGET_ID 6 | Out-Null
            Start-Sleep -Seconds 3
            $pickerXml = Dump-Ui -Quiet
            $card = Node-Center -Xml $pickerXml -Text $otherName[0]
            Check "H5: the picker shows the other account" ($null -ne $card) "name=$($otherName[0])"
            if ($null -ne $card) {
                adb -s $Serial shell input tap $card[0] $card[1] | Out-Null
                Start-Sleep -Seconds 2
                $confirmXml = Dump-Ui -Quiet
                $confirm = Node-Center -Xml $confirmXml -Text "确定"
                if ($null -ne $confirm) {
                    adb -s $Serial shell input tap $confirm[0] $confirm[1] | Out-Null
                    Start-Sleep -Seconds 3
                }
            }
            $rebound = Join-Path $outDir "history-after-rebind.db"
            Pull-Db $rebound | Out-Null
            $slotsAfter = @(Query-Db $rebound "select widget_id || '/' || slot_index || '=' || account_id from widget_slots order by widget_id, slot_index;")
            Check "H5: the configuration really changed" `
                (@(Compare-Object $slotsBefore $slotsAfter).Count -gt 0) `
                "before=$($slotsBefore -join ',') after=$($slotsAfter -join ',')"
            $historyAfter = History-Checksum $rebound
            $dailyAfter = @(Query-Db $rebound "select count(*) from daily_usage;")[0]
            Check "H5: no reading was added, removed or rewritten by the rebind" `
                ($historyBefore -eq $historyAfter) "before=$historyBefore after=$historyAfter"
            Check "H5: the daily accumulators are untouched" ($dailyBefore -eq $dailyAfter) `
                "before=$dailyBefore after=$dailyAfter"
        }

        # --------------------------------------------------- H7: volume, as a number
        # Reported, not asserted: SQLite does not shrink a file after a DELETE
        # without a VACUUM, so a size comparison here would prove nothing about
        # whether retention works. H1 is the proof; these are the measurements.
        Write-Output ("# H7: database {0} bytes before, {1} after; rows now {2}" -f `
            (Get-Item $backup).Length, (Get-Item $after).Length,
            @(Query-Db $after "select count(*) from usage_snapshots;")[0])
    } finally {
        Stop-App
        Push-Db $backup
        adb -s $Serial shell "run-as $Package sh -c 'rm -f databases/$dbName-wal databases/$dbName-shm'" | Out-Null
        Start-Sleep -Seconds 1
        $restored = Join-Path $outDir "history-restored.db"
        Pull-Db $restored | Out-Null
        $shapeSql = "select (select count(*) from accounts) || '/' || (select count(*) from credentials) || '/' || (select count(*) from usage_snapshots) || '/' || (select count(*) from daily_usage) || '/' || (select count(*) from widget_config) || '/' || (select count(*) from widget_slots);"
        $shapeBefore = @(Query-Db $backup $shapeSql)
        $shapeAfter = @(Query-Db $restored $shapeSql)
        Check "H1: the device database is restored to its pre-test shape" `
            ($shapeBefore[0] -eq $shapeAfter[0]) "backup=$($shapeBefore[0]) restored=$($shapeAfter[0])"
        $stray = @(Query-Db $restored "select count(*) from usage_snapshots where timestamp >= $injectedAt and timestamp < $($injectedAt + 70000);")
        Check "H1: no injected reading survived the restore" ($stray[0] -eq "0") "rows=$($stray[0])"
    }
}

Write-Output ""
Write-Output ("RESULT: {0} checks, {1} failed" -f $script:checks, $script:failures)
if ($script:failures -gt 0) {
    exit 1
}
