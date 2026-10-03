<#
    Phase 1 acceptance 8, second half: prove the SQL predicate itself.

    The host-JVM test (refresh/FailurePreservesLastSuccessTest) proves the
    refresh manager appends rather than clears. It cannot prove the SQL,
    because SqliteUsageRepository needs an Android Context. This script closes
    that gap by replaying the EXACT predicate and the EXACT schema against a
    real sqlite3 engine, so "a failed refresh never clears the last successful
    data" (Spec §39 rule 18) is evidenced end to end.

    The statements below are copied verbatim from
    app/src/main/java/com/aiusage/monitor/storage/Database.java:77-85 and
    app/src/main/java/com/aiusage/monitor/storage/SqliteUsageRepository.java:60-67,
    82-89. If either moves, this script must move with it.

    Usage:
        powershell -File tools\smoke\assert-last-success.ps1
#>
param(
    [string]$DbName = "sql-proof.db"
)

$ErrorActionPreference = "Stop"

. "$PSScriptRoot\..\env.ps1"

$outDir = Join-Path $PSScriptRoot "out"
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }
$db = Join-Path $outDir $DbName
Remove-Item $db -ErrorAction SilentlyContinue

$sqlite = "sqlite3"
if ($env:ANDROID_HOME) {
    $candidate = Join-Path $env:ANDROID_HOME "platform-tools\sqlite3.exe"
    if (Test-Path $candidate) { $sqlite = $candidate }
}

$failures = New-Object System.Collections.Generic.List[string]
$checks = New-Object System.Collections.Generic.List[object]

function Add-Check {
    param([string]$Name, [bool]$Ok, [string]$Detail)
    $checks.Add([pscustomobject]@{ Name = $Name; Ok = $Ok; Detail = $Detail })
    if (-not $Ok) { $failures.Add($Name) }
}

function Invoke-Sql {
    param([string]$Sql)
    # SQL goes in over stdin, never as a command-line argument: cmd would strip
    # the double quotes out of the JSON payloads and the comparison would fail
    # on a quoting artefact rather than on behaviour.
    return @($Sql | cmd /c "`"$sqlite`" `"$db`"") | Where-Object { $_ -ne "" }
}

Write-Output "=== Spec 39 rule 18: a failure never clears the last success ==="
Write-Output "# sqlite = $sqlite"
Write-Output "# db     = $db"

# --- schema, verbatim from Database.java:77-85 ---------------------------------
$schema = @"
CREATE TABLE usage_snapshots (
id INTEGER PRIMARY KEY AUTOINCREMENT,
account_id TEXT NOT NULL,
timestamp INTEGER NOT NULL,
usage_data TEXT NOT NULL,
source TEXT NOT NULL,
success INTEGER NOT NULL);
CREATE INDEX idx_snapshots_account_time ON usage_snapshots (account_id, timestamp DESC);
"@
$schema | cmd /c "`"$sqlite`" `"$db`"" | Out-Null
Write-Output "# schema applied (usage_snapshots + idx_snapshots_account_time)"

# --- the manager's write sequence: one success, then three failures ------------
# AccountRefreshManager.fetch() writes success=true; recordFailure() writes false.
$ACCOUNT = "acct_sqlproof"
$goodPayload = '{"balance":{"amount":42.5,"currency":"CNY"},"status":"OK"}'
$seed = @"
INSERT INTO usage_snapshots (account_id,timestamp,usage_data,source,success) VALUES ('$ACCOUNT',1000,'$goodPayload','DIRECT_API',1);
INSERT INTO usage_snapshots (account_id,timestamp,usage_data,source,success) VALUES ('$ACCOUNT',2000,'{"status":"NETWORK_ERROR"}','DIRECT_API',0);
INSERT INTO usage_snapshots (account_id,timestamp,usage_data,source,success) VALUES ('$ACCOUNT',3000,'{"status":"NETWORK_ERROR"}','DIRECT_API',0);
INSERT INTO usage_snapshots (account_id,timestamp,usage_data,source,success) VALUES ('$ACCOUNT',4000,'{"status":"AUTH_REQUIRED"}','DIRECT_API',0);
"@
$seed | cmd /c "`"$sqlite`" `"$db`"" | Out-Null
Write-Output "# wrote 1 success (t=1000) then 3 failures (t=2000,3000,4000)"

# --- assertion 1: every row was kept, including the failures ------------------
$counts = (Invoke-Sql "select count(*) || '|' || sum(success) || '|' || (count(*) - sum(success)) from usage_snapshots;") -join ""
$parts = $counts -split '\|'
Add-Check "all four rows were kept" ($parts[0] -eq "4") "rows=$($parts[0])"
Add-Check "exactly one success row" ($parts[1] -eq "1") "success=$($parts[1])"
Add-Check "three failure rows recorded" ($parts[2] -eq "3") "failures=$($parts[2])"

# --- assertion 2: the real latest() predicate still returns the good reading ---
# Verbatim: "account_id = ? AND success = 1", order "timestamp DESC, id DESC", limit 1
$latest = (Invoke-Sql "SELECT usage_data FROM usage_snapshots WHERE account_id = '$ACCOUNT' AND success = 1 ORDER BY timestamp DESC, id DESC LIMIT 1;") -join ""
Add-Check "latest() still returns the successful reading" ($latest -eq $goodPayload) "returned: $latest"
Add-Check "the balance survived three later failures" ($latest -match '"amount":42\.5') "amount present: $($latest -match '42\.5')"

# --- assertion 3: history() shows the failures (deliberately unfiltered) ------
# Verbatim predicate is "account_id = ?" with no success filter. That asymmetry
# is the contract: latest() hides failures, history() must not.
$history = (Invoke-Sql "SELECT success FROM usage_snapshots WHERE account_id = '$ACCOUNT' ORDER BY timestamp DESC, id DESC LIMIT 100;") -join ","
Add-Check "history() returns all four attempts" (($history -split ',').Count -eq 4) "success flags (newest first): $history"
Add-Check "history() puts the failures on the record" ($history -eq "0,0,0,1") "expected 0,0,0,1"

# --- assertion 4: a second success wins over the failures and the first --------
$newPayload = '{"balance":{"amount":50.0,"currency":"CNY"},"status":"OK"}'
Invoke-Sql "INSERT INTO usage_snapshots (account_id,timestamp,usage_data,source,success) VALUES ('$ACCOUNT',5000,'$newPayload','DIRECT_API',1);" | Out-Null
$latest2 = (Invoke-Sql "SELECT usage_data FROM usage_snapshots WHERE account_id = '$ACCOUNT' AND success = 1 ORDER BY timestamp DESC, id DESC LIMIT 1;") -join ""
Add-Check "a later success supersedes the earlier one" ($latest2 -eq $newPayload) "returned: $latest2"

# --- assertion 5: failures alone never fabricate data -------------------------
$OTHER = "acct_only_failures"
Invoke-Sql "INSERT INTO usage_snapshots (account_id,timestamp,usage_data,source,success) VALUES ('$OTHER',1000,'{`"status`":`"NETWORK_ERROR`"}','DIRECT_API',0);" | Out-Null
$none = (Invoke-Sql "SELECT count(*) FROM usage_snapshots WHERE account_id = '$OTHER' AND success = 1 ORDER BY timestamp DESC, id DESC LIMIT 1;") -join ""
Add-Check "an account with only failures has no latest()" ($none -eq "0") "success rows=$none"

# --- report -------------------------------------------------------------------
Write-Output ""
foreach ($check in $checks) {
    $mark = if ($check.Ok) { "PASS" } else { "FAIL" }
    Write-Output ("[{0}] {1}" -f $mark, $check.Name)
    Write-Output ("       {0}" -f $check.Detail)
}
Write-Output ""
if ($failures.Count -gt 0) {
    Write-Output "RESULT: FAILED ($($failures.Count) of $($checks.Count) checks)"
    exit 1
}
Write-Output "RESULT: all $($checks.Count) SQL checks passed"
exit 0
