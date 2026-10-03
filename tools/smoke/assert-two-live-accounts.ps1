#requires -Version 5
<#
Phase 2 acceptance 1, 2 and 3, asserted from the database the device actually
wrote.

Why this exists: those three criteria are about two *real* keys working
independently, and the difference between "two accounts are configured" and "two
accounts are actually live and independent" only shows up in the data. The
account list renders "—" for an account that has never succeeded, so a
screenshot cannot tell a working second account from a configured-but-dead one.

What it asserts:
  1. each account holds at least one successful snapshot, with a balance amount
     parsed out of the snapshot JSON;
  2. the two credentials are bound to distinct account rows, each with its own
      credential binding (see the credentials-table checks);
  3. each account's newest snapshot is a success - a stale success followed by
     failures is not a live account;
  4. every snapshot row belongs to a known account, and each account's rows form
     its own timeline (acceptance 3: no cross-account contamination).

Balance equality is NOT evidence of shared credentials: two keys can belong to
the same DeepSeek platform account, in which case the balances are equal by
definition. The old "the two balances differ" check failed in exactly that
state, so it is replaced by credential-binding evidence from the credentials
table. Proving the keys themselves are *different secrets* is beyond what the
device can see - the credentials table stores ciphertext.

This script is read-only against the device: it pulls the database and queries
the copy on the host. Safe to run repeatedly, and safe to run before the keys
exist - in that state it fails on assertion 1 with the reason stated, which is
the point: it tells you the two-account path is configured but not yet proven.

Usage:
  .\assert-two-live-accounts.ps1
  .\assert-two-live-accounts.ps1 -AccountA "DeepSeek" -AccountB "DeepSeekWork"
#>
[CmdletBinding()]
param(
  [string]$Package = "com.aiusage.monitor",
  [string]$AccountA = "DeepSeek",
  [string]$AccountB = "DeepSeekWork"
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\..\env.ps1"

$out = Join-Path $PSScriptRoot "out"
New-Item -ItemType Directory -Path $out -Force | Out-Null

$sqlite = Join-Path $env:ANDROID_HOME "platform-tools\sqlite3.exe"
if (-not (Test-Path $sqlite)) { throw "sqlite3 not found at $sqlite" }

$db = Join-Path $out "live-accounts.db"
Remove-Item $db -Force -ErrorAction SilentlyContinue

# The device has no sqlite3 binary, so the database is pulled and queried on the
# host. run-as works because the debug build is debuggable. Route through cmd so
# adb's stderr chatter cannot become a terminating NativeCommandError.
$pull = cmd /c "adb exec-out run-as $Package cat databases/ai_usage_monitor.db > `"$db`" 2>&1"
if (-not (Test-Path $db) -or (Get-Item $db).Length -eq 0) {
  throw "could not pull the database from $Package (is the app installed and has it been launched once?)"
}

function Invoke-Sql {
  param([string]$Sql)
  # SQL goes in over stdin: as an argv value cmd.exe eats the JSON quotes inside
  # the json_extract() paths.
  $r = $Sql | cmd /c "`"$sqlite`" `"$db`" 2>&1"
  return @($r)
}

function Get-Scalar {
  param([string]$Sql)
  # @() has to wrap the CALL, not the return: PowerShell unwraps a
  # single-element array as it leaves the function, so Invoke-Sql's own @()
  # collapses back to a bare string and $rows[0] would hand back that string's
  # first character. That silently turned every account id into "a" and made
  # every row lookup miss.
  $rows = @(Invoke-Sql $Sql)
  if ($rows.Count -eq 0) { return "" }
  return ([string]$rows[0]).Trim()
}

$results = @()
function Add-Check {
  param([bool]$Ok, [string]$Name, [string]$Detail)
  $script:results += [pscustomobject]@{ Ok = $Ok; Name = $Name; Detail = $Detail }
}

Write-Output "two live accounts: $AccountA / $AccountB"
Write-Output ("database pulled: {0} bytes" -f (Get-Item $db).Length)
Write-Output ""

# ---- resolve the two accounts -------------------------------------------------
$idA = Get-Scalar "SELECT id FROM accounts WHERE display_name = '$AccountA';"
$idB = Get-Scalar "SELECT id FROM accounts WHERE display_name = '$AccountB';"

Add-Check ($idA -ne "") "account '$AccountA' exists" "id=$idA"
Add-Check ($idB -ne "") "account '$AccountB' exists" "id=$idB"
Add-Check ($idA -ne $idB) "the two accounts are distinct rows" "$idA vs $idB"
if ($idA -eq "" -or $idB -eq "") {
  Write-Output "cannot continue without both accounts; aborting before the data checks"
  $results | ForEach-Object { Write-Output ("[{0}] {1} - {2}" -f $(if ($_.Ok) { "PASS" } else { "FAIL" }), $_.Name, $_.Detail) }
  exit 1
}

$enabledA = Get-Scalar "SELECT enabled FROM accounts WHERE id = '$idA';"
$enabledB = Get-Scalar "SELECT enabled FROM accounts WHERE id = '$idB';"
Add-Check ($enabledA -eq "1") "account '$AccountA' is enabled" "enabled=$enabledA"
Add-Check ($enabledB -eq "1") "account '$AccountB' is enabled" "enabled=$enabledB"

# ---- acceptance 1: each account independently produced a real reading --------
# One row per account: its newest successful snapshot and the balance inside it.
function Get-LiveReading {
  param([string]$Id, [string]$Label)
  $okCount = Get-Scalar "SELECT COUNT(*) FROM usage_snapshots WHERE account_id = '$Id' AND success = 1;"
  $amount = Get-Scalar "SELECT json_extract(usage_data,'$.balance.amount') FROM usage_snapshots WHERE account_id = '$Id' AND success = 1 ORDER BY timestamp DESC, id DESC LIMIT 1;"
  $currency = Get-Scalar "SELECT json_extract(usage_data,'$.balance.currency') FROM usage_snapshots WHERE account_id = '$Id' AND success = 1 ORDER BY timestamp DESC, id DESC LIMIT 1;"
  $status = Get-Scalar "SELECT json_extract(usage_data,'$.status') FROM usage_snapshots WHERE account_id = '$Id' ORDER BY timestamp DESC, id DESC LIMIT 1;"
  $latestOk = Get-Scalar "SELECT success FROM usage_snapshots WHERE account_id = '$Id' ORDER BY timestamp DESC, id DESC LIMIT 1;"

  Add-Check ([int]$okCount -ge 1) "account '$Label' has a successful reading" "successful rows=$okCount"
  Add-Check ($amount -ne "") "account '$Label' reading carries a balance amount" "amount='$amount' currency='$currency'"
  Add-Check ($latestOk -eq "1") "account '$Label' newest snapshot is a success" "newest success=$latestOk newest status=$status"

  return [pscustomobject]@{ Amount = $amount; Currency = $currency; Status = $status; OkCount = [int]$okCount }
}

$liveA = Get-LiveReading $idA $AccountA
$liveB = Get-LiveReading $idB $AccountB

if ($liveA.Amount -ne "" -and $liveB.Amount -ne "") {
  # Equal balances are expected when both keys belong to the same DeepSeek
  # platform account - that is not a failure. Credential independence is
  # asserted against the credentials table below instead.
  Write-Output ("balances: {0}={1} {2} | {3}={4} {5} (equal is allowed: both keys may belong to one platform account)" -f $AccountA, $liveA.Amount, $liveA.Currency, $AccountB, $liveB.Amount, $liveB.Currency)
} else {
  Add-Check $false "each account's reading carries a balance" "not comparable yet: $AccountA='$($liveA.Amount)' $AccountB='$($liveB.Amount)'"
}

# ---- acceptance 2: the credentials are bound per-account ----------------------
# accounts.credential_id points at the credentials row; "has its own binding"
# means the reference is set AND the referenced row exists, and "distinct
# bindings" means the two accounts reference different credential rows.
$credIdA = Get-Scalar "SELECT credential_id FROM accounts WHERE id = '$idA';"
$credIdB = Get-Scalar "SELECT credential_id FROM accounts WHERE id = '$idB';"
$credRowA = Get-Scalar "SELECT COUNT(*) FROM credentials WHERE id = '$credIdA';"
$credRowB = Get-Scalar "SELECT COUNT(*) FROM credentials WHERE id = '$credIdB';"
Add-Check ($credIdA -ne "" -and $credRowA -eq "1") "account '$AccountA' has its own credential binding" "credential_id=$credIdA (row exists)"
Add-Check ($credIdB -ne "" -and $credRowB -eq "1") "account '$AccountB' has its own credential binding" "credential_id=$credIdB (row exists)"
Add-Check ($credIdA -ne "" -and $credIdB -ne "" -and $credIdA -ne $credIdB) "the two credential bindings are distinct rows" "$credIdA vs $credIdB"

# ---- acceptance 3: the timelines are per-account, not shared -----------------
$orphans = Get-Scalar "SELECT COUNT(*) FROM usage_snapshots WHERE account_id NOT IN (SELECT id FROM accounts);"
Add-Check ($orphans -eq "0") "every snapshot belongs to a known account" "orphan rows=$orphans"

$rowsA = Get-Scalar "SELECT COUNT(*) FROM usage_snapshots WHERE account_id = '$idA';"
$rowsB = Get-Scalar "SELECT COUNT(*) FROM usage_snapshots WHERE account_id = '$idB';"
Add-Check ([int]$rowsA -ge 1 -and [int]$rowsB -ge 1) "each account has its own snapshot timeline" "$AccountA=$rowsA rows, $AccountB=$rowsB rows"

# Interleaving is the evidence that one account's writes do not overwrite the
# other's: shared rows would give each account exactly one contiguous id block.
$idSpanA = Get-Scalar "SELECT MIN(id) || '-' || MAX(id) FROM usage_snapshots WHERE account_id = '$idA';"
$idSpanB = Get-Scalar "SELECT MIN(id) || '-' || MAX(id) FROM usage_snapshots WHERE account_id = '$idB';"

Write-Output "per-account evidence:"
Write-Output ("  {0,-14} id-span {1,-24} successes {2}" -f $AccountA, $idSpanA, $liveA.OkCount)
Write-Output ("  {0,-14} id-span {1,-24} successes {2}" -f $AccountB, $idSpanB, $liveB.OkCount)
Write-Output ("  latest: {0}={1} {2} | {3}={4} {5}" -f $AccountA, $liveA.Amount, $liveA.Currency, $AccountB, $liveB.Amount, $liveB.Currency)
Write-Output ""

Write-Output "checks:"
foreach ($c in $results) {
  Write-Output ("[{0}] {1} - {2}" -f $(if ($c.Ok) { "PASS" } else { "FAIL" }), $c.Name, $c.Detail)
}

$failed = @($results | Where-Object { -not $_.Ok }).Count
Write-Output ""
if ($failed -eq 0) {
  Write-Output "RESULT: both accounts are live and independent (acceptance 1, 2 and 3 pass)"
  exit 0
}
Write-Output "RESULT: $failed check(s) failed - see the FAIL lines above"
exit 1
