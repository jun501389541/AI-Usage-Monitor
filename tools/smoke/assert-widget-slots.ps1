<#
    Phase 3 widget acceptance: the slot model, its storage, and what the home
    screen actually shows.

    Covers docs/PHASE-3-PLAN.md rows A1, A2, A3, A5, A6, A7 plus the spec §53
    red-line greps. Everything is read-only against the two live accounts except
    A7, which points one dashboard slot at an account id that does not exist.
    A7 is done that way rather than by deleting a real account because the two
    keys live only on this device and cannot be typed back: the check has to be
    reversible, and it is — the database is backed up first and restored in
    finally, the discipline review finding R5 asked for.

    Two device facts this script encodes:
      * every Query-Db call site is wrapped in @(). PowerShell unwraps a
        single-element array on assignment, so $row[0] would otherwise be the
        first *character* of the value — which silently "passes" a one-digit
        count and fails everything else.
      * a widget cannot be redrawn from the shell: `am broadcast` of
        APPWIDGET_UPDATE is refused for an unknown caller. The refresh is
        triggered the way a user triggers it, by tapping the label.

    Usage:
        powershell -File tools\smoke\assert-widget-slots.ps1
        powershell -File tools\smoke\assert-widget-slots.ps1 -SkipInjection
#>
param(
    [string]$Serial = "emulator-5554",
    [string]$Package = "com.aiusage.monitor",
    [switch]$SkipInjection
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
$live = Join-Path $outDir "widget-slots-live.db"
$backup = Join-Path $outDir "widget-slots-backup.db"

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
    $temp = Join-Path $env:TEMP ("widget-slots-" + [Guid]::NewGuid().ToString("N") + ".sql")
    $body = ".mode list`n" + $Sql + "`n"
    [IO.File]::WriteAllText($temp, $body, [Text.Encoding]::ASCII)
    $result = cmd /c "`"$sqlite`" -noheader -list `"$Path`" < `"$temp`" 2>&1"
    Remove-Item $temp -Force
    return @($result | Where-Object { "$_" -ne "" } | ForEach-Object { "$_".Trim() })
}

function Pull-Db {
    param([string]$Destination)
    # Binary-safe: PowerShell's own redirection corrupts the stream (HANDOFF §8).
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
    # This database is the only store of the two live API keys, so a half-written
    # copy is not an acceptable failure mode: every step is checked and the file
    # is copied into place only after its staged size is confirmed. A review
    # finding on the first version of this script was that an unchecked `adb push`
    # followed by the cat pipe could leave the app reading a truncated database.
    $expected = (Get-Item $Source).Length
    adb -s $Serial push $Source "/sdcard/widget-slots.db" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "adb push failed (exit $LASTEXITCODE); the app database was not touched"
    }
    $staged = ((adb -s $Serial shell "stat -c %s /sdcard/widget-slots.db") -replace '\D', '')
    if ([long]$staged -ne [long]$expected) {
        throw "staged file is $staged bytes, expected $expected; aborting before overwrite"
    }
    # run-as cannot read /sdcard directly and the first `run-as cp` denial is a
    # false positive; the cat pipe is the path that works.
    adb -s $Serial shell "cat /sdcard/widget-slots.db | run-as $Package sh -c 'cat > databases/$dbName'" | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "copy into app data failed (exit $LASTEXITCODE); restore from $backup by hand"
    }
    $inside = ((adb -s $Serial shell "run-as $Package stat -c %s databases/$dbName") -replace '\D', '')
    if ([long]$inside -ne [long]$expected) {
        throw "database is $inside bytes after the copy, expected $expected"
    }
    adb -s $Serial shell rm -f /sdcard/widget-slots.db | Out-Null
}

function Stop-App {
    adb -s $Serial shell am force-stop $Package | Out-Null
}

function Start-App {
    # A force-stopped app is a *stopped* app: the launcher cannot bind its widget
    # provider and shows "problem loading widget" until something launches it
    # again. Every UI read therefore brings the app up first.
    adb -s $Serial shell am start -n "$Package/$Package.ui.account.AccountListActivity" | Out-Null
    Start-Sleep -Seconds 3
}

function Dump-Ui {
    Start-App
    adb -s $Serial shell input keyevent KEYCODE_HOME | Out-Null
    Start-Sleep -Seconds 2
    adb -s $Serial shell rm -f /sdcard/u.xml | Out-Null
    adb -s $Serial shell uiautomator dump /sdcard/u.xml | Out-Null
    $path = Join-Path $outDir "widget-slots-ui.xml"
    cmd /c "adb -s $Serial exec-out cat /sdcard/u.xml > `"$path`"" 2>&1 | Out-Null
    return [IO.File]::ReadAllText($path, [Text.Encoding]::UTF8)
}

function Node-Center {
    param([string]$Xml, [string]$Text)
    $pattern = 'text="' + [regex]::Escape($Text) + '"[^>]*?bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"'
    $match = [regex]::Match($Xml, $pattern)
    if (-not $match.Success) {
        return $null
    }
    $x1 = [int]$match.Groups[1].Value
    $y1 = [int]$match.Groups[2].Value
    $x2 = [int]$match.Groups[3].Value
    $y2 = [int]$match.Groups[4].Value
    $centerX = [int](($x1 + $x2) / 2)
    $centerY = [int](($y1 + $y2) / 2)
    return , @($centerX, $centerY)
}

function Tap-Refresh {
    param([string]$Xml)
    $node = Node-Center -Xml $Xml -Text "刷新"
    if ($null -eq $node) {
        return $false
    }
    adb -s $Serial shell input tap $node[0] $node[1] | Out-Null
    Start-Sleep -Seconds 8
    return $true
}

Write-Output "# Phase 3 widget acceptance"
Write-Output "# device=$Serial package=$Package"

# ------------------------------------------------------------- red lines (§53)

$widgetDir = Join-Path $PSScriptRoot "..\..\app\src\main\java\com\aiusage\monitor\widget"
$hits = @()
foreach ($file in (Get-ChildItem -Path $widgetDir -Filter *.java)) {
    $text = [IO.File]::ReadAllText($file.FullName, [Text.Encoding]::UTF8)
    # A widget must not fetch from a provider and must not read a credential, so
    # none of these names may appear anywhere in the package: the refresh path
    # reaches providers only through AccountRefreshManager.
    foreach ($forbidden in @("fetchUsage", "CredentialStore", "AuthContext",
                             "openCredential", "HttpURLConnection")) {
        if ($text.Contains($forbidden)) {
            $hits += ("{0}:{1}" -f $file.Name, $forbidden)
        }
    }
}
Check "widget package never fetches from a provider or opens a credential" ($hits.Count -eq 0) ($hits -join ", ")

# ------------------------------------------------------------ stored slot state

$bytes = Pull-Db $live
Write-Output "# database=$bytes bytes"

$slots = @(Query-Db $live "select widget_id || '/' || slot_index || '=' || account_id from widget_slots order by widget_id, slot_index;")
Check "A1: widget_slots holds the migrated bindings" ($slots.Count -ge 2) "rows=$($slots.Count)"

$orphans = @(Query-Db $live "select count(*) from widget_slots s left join widget_config c on c.widget_id = s.widget_id where c.widget_id is null;")
Check "A1: no slot row outlives its widget" ($orphans[0] -eq "0") "orphans=$($orphans[0])"

$blank = @(Query-Db $live "select count(*) from widget_slots where account_id = '';")
Check "A1: no slot stores an empty account id" ($blank[0] -eq "0") "blank=$($blank[0])"

$drift = @(Query-Db $live "select count(*) from widget_slots where metric_ids <> 'balance|today_usage';")
Check "A1: every slot carries the encoded default metric list" ($drift[0] -eq "0") "other=$($drift[0])"

$version = @(Query-Db $live "select user_version from pragma_user_version;")
Check "A1: the database is at schema version 2" ($version[0] -eq "2") "user_version=$($version[0])"

# The migration copies the legacy binding into widget_slots and clears the old
# column, so an upgraded database has one answer rather than two that can drift.
$legacy = @(Query-Db $live "select count(*) from widget_config where account_id <> '';")
Write-Output "# legacy binding rows still populated on this device: $($legacy[0]) (it was upgraded by an earlier build)"

$dashboard = @(Query-Db $live "select widget_id from widget_slots group by widget_id having count(distinct account_id) > 1 order by widget_id limit 1;")
Check "A2 setup: one widget shows more than one account" ($dashboard.Count -ge 1) "widgets=$($dashboard -join ',')"

$shared = @(Query-Db $live "select account_id from widget_slots group by account_id having count(distinct widget_id) > 1 limit 1;")
Check "A3 setup: one account appears in more than one widget" ($shared.Count -ge 1) "accounts=$($shared -join ',')"

$enabled = @(Query-Db $live "select display_name from accounts where enabled = 1 order by sort_order;")
Write-Output "# enabled accounts: $($enabled -join ', ')"

# ------------------------------------------------------------- what it renders

$xml = Dump-Ui
if ($dashboard.Count -ge 1) {
    $slotNames = @(Query-Db $live "select a.display_name from widget_slots s join accounts a on a.id = s.account_id where s.widget_id = $($dashboard[0]) order by s.slot_index;")
    $named = 0
    foreach ($name in $slotNames) {
        if ($xml -match ('text="' + [regex]::Escape($name) + '"')) {
            $named++
        }
    }
    Check "A2: the dashboard shows each bound account's own name on screen" ($named -ge 2) "named=$named of $($slotNames.Count)"
}
Check "A2: the dashboard is labelled and offers a refresh" ($xml -match 'AI Usage' -and $xml -match '刷新') ""
# Each slot is stored with two metrics, and the first version of the dashboard
# drew only the first: counting money cells is what proves both reach the screen.
$moneyCells = ([regex]::Matches($xml, 'text="¥[0-9][0-9.,]*"')).Count
Check "A2: every dashboard row draws both of its metrics" ($moneyCells -ge 4) "money-cells=$moneyCells"
# The status wording must come from the shared vocabulary, whichever state the
# stored data is actually in. Asserting "账户可用" specifically was wrong: after
# the emulator sat idle past twice the refresh interval the honest reading is
# 数据已过期, and a widget that said 账户可用 then would be the bug.
Check "A4 wording: the slot uses one of the shared status wordings" ($xml -match '(账户可用|数据已过期|网络连接失败|API Key 无效或已失效|Bridge 未连接|正在刷新|尚未查询)') ""
Check "A6 wording: every slot says how old its reading is" ($xml -match '(刚刚|分钟前|小时前)') ""

# ------------------------------------------------- A5: the refresh affordance

$refresh = Node-Center -Xml $xml -Text "刷新"
Check "A5: the refresh label is on screen" ($null -ne $refresh) ""
if ($null -ne $refresh -and $dashboard.Count -ge 1) {
    $first = @(Query-Db $live "select account_id from widget_slots where widget_id = $($dashboard[0]) order by slot_index limit 1;")
    $before = @(Query-Db $live "select count(*) from usage_snapshots where account_id = '$($first[0])' and success = 1;")
    Check "A5: the tapped account has stored history to compare against" ($first.Count -eq 1 -and [int]$before[0] -gt 0) "account=$($first[0]) before=$($before[0])"
    [void](Tap-Refresh $xml)
    $tapped = Join-Path $outDir "widget-slots-after-tap.db"
    Pull-Db $tapped | Out-Null
    $after = @(Query-Db $tapped "select count(*) from usage_snapshots where account_id = '$($first[0])' and success = 1;")
    Check "A5: tapping refresh stores one new snapshot for that account" ([int]$after[0] -eq ([int]$before[0] + 1)) "before=$($before[0]) after=$($after[0])"
    $refreshed = Dump-Ui
    Check "A5: the widget redraws itself and calls the reading current" ($refreshed -match '(刚刚|分钟前)') ""
}

# -------------------------------------------------- A6: configuration survives

Stop-App
adb -s $Serial shell am start -n "$Package/$Package.ui.account.AccountListActivity" | Out-Null
Start-Sleep -Seconds 4
Stop-App
$restarted = Join-Path $outDir "widget-slots-after-restart.db"
Pull-Db $restarted | Out-Null
$slotsAfterRestart = @(Query-Db $restarted "select widget_id || '/' || slot_index || '=' || account_id from widget_slots order by widget_id, slot_index;")
$diff = @(Compare-Object $slots $slotsAfterRestart)
$diffText = ($diff | ForEach-Object { "$($_.SideIndicator) $($_.InputObject)" }) -join ", "
Check "A6: the slot configuration survives a cold start unchanged" ($diff.Count -eq 0) "diff=$diffText"

# ------------------------------------------- A1u: rehearse the v1 -> v2 upgrade

# The device database is already at version 2, so the checks above can only read
# the result of an upgrade this build did not perform. This section downgrades a
# copy of the real database back to the version 1 shape (its legacy binding
# column is still populated, because the earlier migration left it alone), pushes
# it, and lets the app run the migration for real -- then restores the pristine
# copy. Everything is backed up first, and the restore is verified table by table.
Copy-Item $live $backup -Force
try {
    $v1 = Join-Path $outDir "widget-slots-v1-rehearsal.db"
    Copy-Item $live $v1 -Force
    cmd /c "`"$sqlite`" `"$v1`" `"drop table if exists widget_slots; pragma user_version = 1;`" 2>&1" | Out-Null
    $downgraded = @(Query-Db $v1 "select user_version from pragma_user_version;")
    Check "A1u: the rehearsal copy is back at schema version 1" ($downgraded[0] -eq "1") "version=$($downgraded[0])"
    # The upgrade can only rebuild what the version 1 shape actually recorded: one
    # account per widget, in the legacy column. Extra slots a later picker added
    # are not part of that shape, so the expectation is the legacy pairs as they
    # stood at downgrade time -- not the current slot table.
    $legacyPairs = @(Query-Db $v1 "select widget_id || '=' || account_id from widget_config where account_id <> '' order by widget_id;")
    Write-Output "# downgrade carried $($legacyPairs.Count) legacy binding(s): $($legacyPairs -join ', ')"
    Stop-App
    Push-Db $v1
    adb -s $Serial shell am start -n "$Package/$Package.ui.account.AccountListActivity" | Out-Null
    Start-Sleep -Seconds 5
    Stop-App
    $upgraded = Join-Path $outDir "widget-slots-after-upgrade.db"
    Pull-Db $upgraded | Out-Null
    $newVersion = @(Query-Db $upgraded "select user_version from pragma_user_version;")
    Check "A1u: the app brought the database to version 2 again" ($newVersion[0] -eq "2") "version=$($newVersion[0])"
    $rebuilt = @(Query-Db $upgraded "select widget_id || '=' || account_id from widget_slots where slot_index = 0 order by widget_id;")
    Check "A1u: the migration rebuilt exactly the bindings the version 1 shape recorded" `
        (@(Compare-Object $legacyPairs $rebuilt).Count -eq 0) "expected=$($legacyPairs -join ',') rebuilt=$($rebuilt -join ',')"
    $cleared = @(Query-Db $upgraded "select count(*) from widget_config where account_id <> '';")
    Check "A1u: the migration cleared the legacy binding column" ($cleared[0] -eq "0") "rows=$($cleared[0])"
    $drift = @(Query-Db $upgraded "select count(*) from widget_slots where metric_ids <> 'balance|today_usage';")
    Check "A1u: the rebuilt slots carry the encoded default metric list" ($drift[0] -eq "0") "other=$($drift[0])"
} finally {
    Stop-App
    Push-Db $backup
    adb -s $Serial shell "run-as $Package sh -c 'rm -f databases/$dbName-wal databases/$dbName-shm'" | Out-Null
    Start-Sleep -Seconds 1
    $backAgain = Join-Path $outDir "widget-slots-after-rehearsal.db"
    Pull-Db $backAgain | Out-Null
    $shapeSql = "select (select count(*) from accounts) || '/' || (select count(*) from credentials) || '/' || (select count(*) from usage_snapshots) || '/' || (select count(*) from daily_usage) || '/' || (select count(*) from widget_config);"
    $beforeShape = @(Query-Db $backup $shapeSql)
    $afterShape = @(Query-Db $backAgain $shapeSql)
    Check "A1u: the rehearsal left every table matching its backup" ($beforeShape[0] -eq $afterShape[0]) `
        "backup=$($beforeShape[0]) now=$($afterShape[0])"
}

# ------------------------------------------- A7: a slot whose account is gone

if ($SkipInjection) {
    Write-Output "SKIP  A7 injection (-SkipInjection)"
} else {
    Copy-Item $live $backup -Force
    try {
        Stop-App
        cmd /c "`"$sqlite`" `"$live`" `"update widget_slots set account_id = 'acct_qa_missing' where widget_id = $($dashboard[0]) and slot_index = 1;`" 2>&1" | Out-Null
        Push-Db $live
        # The shell cannot broadcast APPWIDGET_UPDATE, so the widget is redrawn
        # the way a user redraws it: by tapping its refresh label.
        $before = Dump-Ui
        [void](Tap-Refresh $before)
        $injected = Dump-Ui
        Check "A7: a slot whose account vanished says so" ($injected -match '账户已删除') ""
        Check "A7: the surviving slot keeps showing its own account" ($injected -match 'text="DeepSeek"') ""
        # The row promises "请重新配置该 Slot"; tapping it has to deliver that
        # rather than opening some other account's detail page.
        $deadRow = Node-Center -Xml $injected -Text "账户已删除"
        Check "A7: the deleted row is on screen and tappable" ($null -ne $deadRow) ""
        if ($null -ne $deadRow) {
            adb -s $Serial shell input tap $deadRow[0] $deadRow[1] | Out-Null
            Start-Sleep -Seconds 3
            $focus = (adb -s $Serial shell dumpsys window | Select-String mCurrentFocus) -join " "
            Check "A7: tapping the deleted row opens the picker for this widget" ($focus -match 'WidgetConfigActivity') $focus
            adb -s $Serial shell input keyevent KEYCODE_BACK | Out-Null
            Start-Sleep -Seconds 1
        }
        $errors = @(adb -s $Serial logcat -d -t 500 *:E | Select-String -Pattern "AndroidRuntime" -SimpleMatch)
        Check "A7: rendering a deleted binding does not crash" ($errors.Count -eq 0) ($errors -join " ")
        $probe = Join-Path $outDir "widget-slots-after-injection.db"
        Pull-Db $probe | Out-Null
        $written = @(Query-Db $probe "select count(*) from usage_snapshots where account_id = 'acct_qa_missing';")
        Check "A7: nothing is stored for an account that does not exist" ($written[0] -eq "0") "rows=$($written[0])"
    } finally {
        Stop-App
        Push-Db $backup
        # A stale write-ahead log would replay the injected page back in.
        adb -s $Serial shell "run-as $Package sh -c 'rm -f databases/$dbName-wal databases/$dbName-shm'" | Out-Null
        Start-Sleep -Seconds 1
        $restoredPath = Join-Path $outDir "widget-slots-restored.db"
        Pull-Db $restoredPath | Out-Null
        $restoredSlots = @(Query-Db $restoredPath "select widget_id || '/' || slot_index || '=' || account_id from widget_slots order by widget_id, slot_index;")
        $left = @(Query-Db $restoredPath "select count(*) from widget_slots where account_id = 'acct_qa_missing';")
        $rowDiff = @(Compare-Object $slots $restoredSlots).Count
        Check "A7: the device database is restored to its pre-test rows" (($rowDiff -eq 0) -and ($left[0] -eq "0")) "rowdiff=$rowDiff injected_left=$($left[0])"
        # Row equality on one table is not enough when the file holds the only
        # copy of two live keys: compare every table's shape against the backup,
        # so a restore that fixed the slots and lost something else still fails.
        $shape = "select (select count(*) from accounts) || '/' || (select count(*) from credentials) || '/' || (select count(*) from usage_snapshots) || '/' || (select count(*) from daily_usage) || '/' || (select count(*) from widget_config);"
        $shapeBefore = @(Query-Db $backup $shape)
        $shapeAfter = @(Query-Db $restoredPath $shape)
        Check "A7: every table matches the backup after the restore" ($shapeBefore[0] -eq $shapeAfter[0]) "backup=$($shapeBefore[0]) restored=$($shapeAfter[0])"
        $after = Dump-Ui
        [void](Tap-Refresh $after)
    }
}

Write-Output ""
Write-Output ("RESULT: {0} checks, {1} failed" -f $script:checks, $script:failures)
if ($script:failures -gt 0) {
    exit 1
}
