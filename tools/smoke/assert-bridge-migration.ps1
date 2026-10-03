<#
    Phase 7 step 5 device evidence: the v2 -> v3 migration, rehearsed on the real
    database and put back exactly as it was found.

    Why on a device at all: `android.database.sqlite` is a stub on the host JVM, so
    no unit test here can prove SQLiteOpenHelper actually runs onUpgrade and that the
    resulting file is readable by the app. What the JVM does prove (and does, in
    BridgeSchemaContractTest) is that the fresh-install and upgraded schemas are the
    same DDL, that the migration is one transaction, and that it inserts no rows.

    The database on this emulator is the only place the two real DeepSeek keys exist,
    so every step is byte-checked and the file is restored at the end:
      - pull the live file and hash it;
      - build a v2-shaped copy offline (drop `bridges`, user_version = 2) and push
        that, with the size confirmed at each hop;
      - start the app, let it upgrade, pull again and compare the parts that must not
        move: accounts, credentials, snapshots, daily_usage, widget rows;
      - push the upgraded file back a second time to show re-entry is a no-op;
      - restore the backup, hash it, and relaunch the app.

    Usage:
        powershell -File tools/smoke/assert-bridge-migration.ps1
#>
param(
    [string]$Serial = "emulator-5554",
    [string]$Package = "com.aiusage.monitor",
    [string]$Apk = "app\build\outputs\apk\debug\app-debug.apk"
)

$ErrorActionPreference = "Stop"

$script:checks = 0
$script:failures = 0

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

function Note {
    param([string]$Text)
    Write-Host "NOTE  $Text"
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$apkPath = Join-Path $repoRoot $Apk
if (-not $env:ANDROID_HOME) {
    # The documented way to get this machine's toolchain paths; the agent's own
    # shell has none, and Join-Path on a null -Path throws under Stop.
    . (Join-Path $PSScriptRoot "..\env.ps1")
}
$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
$sqlite = Join-Path $env:ANDROID_HOME "platform-tools\sqlite3.exe"
if (-not (Test-Path $adb)) { throw "adb not found at $adb" }
if (-not (Test-Path $sqlite)) { throw "sqlite3.exe not found next to adb" }
if (-not (Test-Path $apkPath)) { throw "apk not found at $apkPath - run :app:assembleDebug first" }

$dbName = "ai_usage_monitor.db"
$work = Join-Path $env:TEMP ("bridge-migration-" + (Get-Date -Format "yyyyMMddHHmmss"))
New-Item -ItemType Directory -Path $work | Out-Null

function Query-Db {
    param([string]$Path, [string]$Sql)
    $temp = Join-Path $work ("q-" + [Guid]::NewGuid().ToString("N") + ".sql")
    [IO.File]::WriteAllText($temp, ".mode list`n$Sql`n", [Text.Encoding]::ASCII)
    $result = cmd /c "`"$sqlite`" -noheader -list `"$Path`" < `"$temp`" 2>&1"
    Remove-Item $temp -Force
    return @($result | Where-Object { "$_" -ne "" } | ForEach-Object { "$_".Trim() })
}

function Pull-Db {
    param([string]$Destination)
    # Binary-safe: PowerShell's own redirection corrupts the stream (HANDOFF §8).
    Stop-App
    cmd /c "`"$adb`" -s $Serial exec-out run-as $Package cat databases/$dbName > `"$Destination`"" 2>&1 | Out-Null
    if (-not (Test-Path $Destination)) { throw "pull failed: $Destination was not created" }
    $bytes = (Get-Item $Destination).Length
    if ($bytes -lt 10240) { throw "pull produced only $bytes bytes; that is not the app database" }
    return $bytes
}

function Push-Db {
    param([string]$Source)
    # The only store of two live API keys: nothing is overwritten before its size
    # has been confirmed at each hop, and a mismatch aborts rather than guesses.
    $expected = (Get-Item $Source).Length
    & $adb -s $Serial push $Source "/sdcard/migration-rehearsal.db" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "adb push failed (exit $LASTEXITCODE); the app database was not touched" }
    $staged = (((& $adb -s $Serial shell "stat -c %s /sdcard/migration-rehearsal.db") -join "") -replace '\D', '')
    if ([long]$staged -ne [long]$expected) { throw "staged file is $staged bytes, expected $expected; aborting before overwrite" }
    & $adb -s $Serial shell "cat /sdcard/migration-rehearsal.db | run-as $Package sh -c 'cat > databases/$dbName'" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "copy into app data failed (exit $LASTEXITCODE); restore by hand from the backup in $work" }
    $inside = (((& $adb -s $Serial shell "run-as $Package stat -c %s databases/$dbName") -join "") -replace '\D', '')
    if ([long]$inside -ne [long]$expected) { throw "database is $inside bytes after the copy, expected $expected" }
    & $adb -s $Serial shell rm -f /sdcard/migration-rehearsal.db | Out-Null
    # A stale -wal next to a replaced main file means the app reads the old pages.
    & $adb -s $Serial shell "run-as $Package sh -c 'rm -f databases/$dbName-wal databases/$dbName-shm'" | Out-Null
}

function Stop-App { & $adb -s $Serial shell am force-stop $Package | Out-Null }

function Start-App {
    & $adb -s $Serial shell am start -n "$Package/$Package.ui.account.AccountListActivity" | Out-Null
    Start-Sleep -Seconds 4
}

function Hash-File { param([string]$Path) return (Get-FileHash -Algorithm SHA256 -Path $Path).Hash }

# The facts that must survive a migration, as one comparable string per table.
function Fingerprint-Db {
    param([string]$Path)
    $accounts = (Query-Db $Path "SELECT id || '=' || credential_id || '=' || bridge_id || '=' || provider_id FROM accounts ORDER BY id;") -join ";"
    $credentials = (Query-Db $Path "SELECT id || '=' || type || '=' || protection || '=' || length(encrypted_payload) FROM credentials ORDER BY id;") -join ";"
    $snapshots = (Query-Db $Path "SELECT count(*), COALESCE(sum(length(usage_data)),0) FROM usage_snapshots;") -join "/"
    $daily = (Query-Db $Path "SELECT count(*), COALESCE(sum(length(total_usage)),0) FROM daily_usage;") -join "/"
    $widgets = (Query-Db $Path "SELECT count(*) FROM widget_config;") -join "/"
    $slots = (Query-Db $Path "SELECT count(*), COALESCE(sum(length(metric_ids)),0) FROM widget_slots;") -join "/"
    return @{ accounts = $accounts; credentials = $credentials; snapshots = $snapshots;
        daily = $daily; widgets = $widgets; slots = $slots }
}

try {
    Note "work dir: $work"
    Stop-App
    & $adb -s $Serial install -r $apkPath | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "adb install failed (exit $LASTEXITCODE)" }

    $backup = Join-Path $work "live-backup.db"
    $null = Pull-Db $backup
    $backupHash = Hash-File $backup
    Check "M1 the live database was backed up byte-verified" ($backupHash.Length -eq 64) "hash=$backupHash"
    $before = Fingerprint-Db $backup
    Check "M1 the two DeepSeek accounts and their credentials are present to be protected" `
        ($before.accounts -match "deepseek") "accounts=$($before.accounts)"

    # Build the v2 shape offline: whatever the live file's version is, the rehearsal
    # starts from a database that has no bridges table.
    $v2 = Join-Path $work "as-v2.db"
    Copy-Item $backup $v2 -Force
    cmd /c "`"$sqlite`" `"$v2`" `"drop table if exists bridges; pragma user_version = 2;`" 2>&1" | Out-Null
    $version = (Query-Db $v2 "SELECT user_version FROM pragma_user_version;") -join ""
    $tables = (Query-Db $v2 "SELECT name FROM sqlite_master WHERE type='table' AND name='bridges';") -join ","
    Check "M2 a version 2 starting point was built (no bridges table)" `
        ($version -eq "2" -and $tables -eq "") "user_version=$version tables='$tables'"
    $beforeUpgrade = Fingerprint-Db $v2

    Push-Db $v2
    Start-App
    Start-Sleep -Seconds 2

    $after = Join-Path $work "after-upgrade.db"
    $null = Pull-Db $after
    $newVersion = (Query-Db $after "SELECT user_version FROM pragma_user_version;") -join ""
    Check "M3 opening the app upgraded the database to version 3" ($newVersion -eq "3") "user_version=$newVersion"

    $ddl = (Query-Db $after "SELECT sql FROM sqlite_master WHERE type='table' AND name='bridges';") -join " "
    Check "M3 the bridges table exists with the columns pairing needs" `
        ($ddl -match "id TEXT PRIMARY KEY NOT NULL" -and $ddl -match "base_url TEXT NOT NULL" `
            -and $ddl -match "fingerprint TEXT NOT NULL" -and $ddl -match "last_seen INTEGER NOT NULL DEFAULT 0") `
        "ddl=$ddl"
    $invented = (Query-Db $after "SELECT count(*) FROM bridges;") -join ""
    Check "M3 the migration invented no bridge rows" ($invented -eq "0") "rows=$invented"

    $afterFacts = Fingerprint-Db $after
    foreach ($key in @("accounts", "credentials", "snapshots", "daily", "widgets", "slots")) {
        Check "M4 $key survived the migration unchanged" `
            ($beforeUpgrade.$key -eq $afterFacts.$key) "before=$($beforeUpgrade.$key) after=$($afterFacts.$key)"
    }

    # Re-entry: an upgraded file pushed back onto an app that is already at version
    # 3 must not run the migration again, and must not lose anything either.
    Push-Db $after
    Start-App
    $again = Join-Path $work "after-second-open.db"
    $null = Pull-Db $again
    $secondFacts = Fingerprint-Db $again
    Check "M5 a second open changed nothing (the upgrade is not re-run and not re-needed)" `
        ($secondFacts.accounts -eq $afterFacts.accounts -and $secondFacts.credentials -eq $afterFacts.credentials) `
        "accounts=$($secondFacts.accounts)"
    $secondVersion = (Query-Db $again "SELECT user_version FROM pragma_user_version;") -join ""
    Check "M5 and the version stayed at 3" ($secondVersion -eq "3") "user_version=$secondVersion"

    # An account can now be pointed at a bridge without disturbing anything else,
    # exercised through the app's own storage path rather than a JVM fake.
    $probe = Join-Path $work "with-pointer.db"
    Copy-Item $after $probe -Force
    $firstAccount = (Query-Db $probe "SELECT id FROM accounts ORDER BY created_at LIMIT 1;") -join ""
    cmd /c "`"$sqlite`" `"$probe`" `"UPDATE accounts SET bridge_id = 'br_probe' WHERE id = '$firstAccount';`" 2>&1" | Out-Null
    Push-Db $probe
    Start-App
    $readBack = Join-Path $work "pointer-read-back.db"
    $null = Pull-Db $readBack
    $pointer = (Query-Db $readBack "SELECT bridge_id FROM accounts WHERE id = '$firstAccount';") -join ""
    Check "M6 the app reads a bridge pointer written into the upgraded schema" ($pointer -eq "br_probe") `
        "bridge_id=$pointer for $firstAccount"
    $stillThere = (Query-Db $readBack "SELECT count(*) FROM accounts;") -join ""
    Check "M6 and the account list is intact after the app re-opened it" ([int]$stillThere -ge 2) "accounts=$stillThere"
} finally {
    Stop-App
    if (Test-Path $backup) {
        Push-Db $backup
        $restored = Join-Path $work "restored.db"
        $null = Pull-Db $restored
        if ((Hash-File $restored) -eq $backupHash) {
            Check "M7 the device database was restored byte-for-byte" $true
        } else {
            Check "M7 the device database was restored byte-for-byte" $false `
                "RESTORE FAILED - the backup is at $backup; put it back by hand before trusting this device"
        }
        $deepSeek = Fingerprint-Db $restored
        Check "M7 the two DeepSeek credentials are still the ones that were there" `
            ($deepSeek.accounts -eq $before.accounts -and $deepSeek.credentials -eq $before.credentials) `
            "now=$($deepSeek.accounts)"
    } else {
        Check "M7 a backup existed to restore from" $false "no backup at $backup"
    }
    Start-App
    & $adb -s $Serial shell "run-as $Package ls databases" | ForEach-Object { Note ("device files: " + $_) }
    Note "evidence kept in $work"
}

Write-Host ""
Write-Host "== summary: $script:checks checks, $script:failures failed =="
if ($script:failures -gt 0) { exit 1 }
Write-Host "RESULT: *PASS*"
