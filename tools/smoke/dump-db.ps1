<#
    Pulls the app's SQLite database off the device and prints the tables that
    matter for acceptance checks.

    The device has no sqlite3 binary (run-as: exec failed for sqlite3), so the
    database must be pulled to the host and read with the SDK's sqlite3.exe.
    The pull goes through cmd /c because PowerShell's redirection corrupts
    binary output.

    Usage:
        powershell -File tools\smoke\dump-db.ps1 -Tag before
        powershell -File tools\smoke\dump-db.ps1 -Tag after -Sections widget
#>
param(
    [string]$Tag = "db",
    [string]$Package = "com.aiusage.monitor",
    [ValidateSet("all", "accounts", "credentials", "snapshots", "widget", "meta")]
    [string]$Sections = "all"
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\..\env.ps1"

$outDir = Join-Path $PSScriptRoot "out"
if (-not (Test-Path $outDir)) {
    New-Item -ItemType Directory -Path $outDir | Out-Null
}

$dbPath = Join-Path $outDir "$Tag.db"
$sqlite = Join-Path $env:ANDROID_HOME "platform-tools\sqlite3.exe"
if (-not (Test-Path $sqlite)) {
    throw "sqlite3.exe not found at $sqlite"
}

# Binary-safe pull. run-as reads the app-private database without root.
cmd /c "adb exec-out run-as $Package cat databases/ai_usage_monitor.db > `"$dbPath`""
if (-not (Test-Path $dbPath)) {
    throw "pull failed: $dbPath was not created"
}
$bytes = (Get-Item $dbPath).Length
if ($bytes -lt 1024) {
    throw "pull produced only $bytes bytes; the app is probably not installed"
}
Write-Output "# db=$dbPath bytes=$bytes"

function Invoke-Sql {
    param([string]$Sql)
    $result = cmd /c "`"$sqlite`" `"$dbPath`" `"$Sql`" 2>&1"
    if ($LASTEXITCODE -ne 0) {
        Write-Output "  SQL-ERROR: $result"
    } else {
        $lines = @($result) | Where-Object { $_ -ne "" }
        if ($lines.Count -eq 0) {
            Write-Output "  (no rows)"
        } else {
            foreach ($line in $lines) { Write-Output "  $line" }
        }
    }
}

$want = { param($name) $Sections -eq "all" -or $Sections -eq $name }

if (& $want "accounts") {
    Write-Output "--- accounts ---"
    Invoke-Sql "select id, provider_id, display_name, auth_type, credential_id, enabled, sort_order from accounts order by sort_order;"
}

if (& $want "credentials") {
    Write-Output "--- credentials ---"
    Invoke-Sql "select id, type, protection, length(encrypted_payload) from credentials;"
    # A plaintext key must never appear in the database. Search the raw file for
    # the sk- prefix as a second, independent check beyond the schema.
    $raw = [IO.File]::ReadAllBytes($dbPath)
    $text = [Text.Encoding]::ASCII.GetString($raw)
    $hits = ([regex]::Matches($text, "sk-[A-Za-z0-9_\-]{4,}")).Count
    Write-Output "  plaintext-sk-occurrences=$hits"
}

if (& $want "snapshots") {
    Write-Output "--- usage_snapshots (account, success, count) ---"
    Invoke-Sql "select account_id, success, count(*) from usage_snapshots group by account_id, success order by account_id, success;"
}

if (& $want "widget") {
    Write-Output "--- widget_config ---"
    Invoke-Sql "select widget_id, widget_type, account_id, refresh_interval_ms from widget_config order by widget_id;"
    Write-Output "--- widget_slots ---"
    Invoke-Sql "select widget_id, slot_index, account_id, metric_ids from widget_slots order by widget_id, slot_index;"
}

if (& $want "meta") {
    Write-Output "--- app_meta ---"
    Invoke-Sql "select key, value from app_meta order by key;"
}

Write-Output "# done"
