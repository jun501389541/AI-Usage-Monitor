<#
    Phase 1 acceptance 5 / Phase 2 acceptance 5, Spec §14:
    "改 Key 后 Account ID 不变、历史不丢、Widget 配置不丢"

    Types a new key into the running app with "remember" ticked, then compares
    the database before and after. The invariant under test is the ACCOUNT's
    identity, not the credential row's: retyping a key through the UI clears the
    credential and creates a fresh one, which is legitimate. What must not move
    is the account id, the existing history, and the widget bindings.

    Phase 2 moved the launcher to the account list and demoted MainActivity to a
    per-account detail page (exported=false), so this script now navigates
    list -> detail by tapping the first card, and takes the key field and the
    remember checkbox from the UI dump instead of hardcoding coordinates. With
    two accounts on the device it also asserts the second account is untouched.

    Usage:
        powershell -File tools\smoke\assert-account-identity.ps1
        powershell -File tools\smoke\assert-account-identity.ps1 -NewKey "sk-..."
#>
param(
    [string]$Package = "com.aiusage.monitor",
    [string]$Activity = "com.aiusage.monitor.ui.account.AccountListActivity",
    [string]$DetailActivity = "com.aiusage.monitor.ui.MainActivity",
    [string]$NewKey = "sk-identityprobe22222222222222222222222"
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\..\env.ps1"

$outDir = Join-Path $PSScriptRoot "out"
if (-not (Test-Path $outDir)) { New-Item -ItemType Directory -Path $outDir | Out-Null }
$sqlite = Join-Path $env:ANDROID_HOME "platform-tools\sqlite3.exe"

$failures = New-Object System.Collections.Generic.List[string]
$checks = New-Object System.Collections.Generic.List[object]

function Add-Check {
    param([string]$Name, [bool]$Ok, [string]$Detail)
    $checks.Add([pscustomobject]@{ Name = $Name; Ok = $Ok; Detail = $Detail })
    if (-not $Ok) { $failures.Add($Name) }
}

function Get-Db {
    param([string]$Tag)
    $dbPath = Join-Path $outDir "$Tag.db"
    cmd /c "adb exec-out run-as $Package cat databases/ai_usage_monitor.db > `"$dbPath`""
    if (-not (Test-Path $dbPath)) { throw "pull failed for $Tag" }
    return $dbPath
}

function Invoke-Sql {
    param([string]$DbPath, [string]$Sql)
    $result = cmd /c "`"$sqlite`" `"$DbPath`" `"$Sql`" 2>&1"
    return @($result) | Where-Object { $_ -ne "" }
}

# Dumps the current screen. The remote file is removed first because
# uiautomator otherwise hands back the previous screen's XML and reports a
# stale state as fact.
function Get-UiNodes {
    $uiPath = Join-Path $outDir "identity-ui.xml"
    if (Test-Path $uiPath) { Remove-Item $uiPath -Force }
    cmd /c "adb shell rm -f /sdcard/u.xml" | Out-Null
    cmd /c "adb shell uiautomator dump /sdcard/u.xml" | Out-Null
    # stderr is merged inside cmd on purpose: adb reports its progress
    # ("1 file pulled...") on stderr, and with $ErrorActionPreference = "Stop"
    # PowerShell turns that into a terminating NativeCommandError.
    cmd /c "adb pull /sdcard/u.xml `"$uiPath`" 2>&1" | Out-Null
    if (-not (Test-Path $uiPath)) { return @() }
    $xml = [IO.File]::ReadAllText($uiPath, [Text.Encoding]::UTF8)
    $nodes = @()
    foreach ($m in [regex]::Matches($xml, "<node[^>]*>")) {
        $n = $m.Value
        $text = if ($n -match 'text="([^"]*)"') { $matches[1] } else { "" }
        $bounds = if ($n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            [pscustomobject]@{
                L = [int]$matches[1]; T = [int]$matches[2]
                R = [int]$matches[3]; B = [int]$matches[4]
            }
        } else { $null }
        $checked = if ($n -match 'checked="true"') { $true } else { $false }
        $nodes += [pscustomobject]@{ Text = $text; Bounds = $bounds; Checked = $checked }
    }
    return $nodes
}

function Tap-Node {
    param($Node)
    $x = [int](($Node.Bounds.L + $Node.Bounds.R) / 2)
    $y = [int](($Node.Bounds.T + $Node.Bounds.B) / 2)
    cmd /c "adb shell input tap $x $y" | Out-Null
    return @($x, $y)
}

Write-Output "=== Spec 14: account identity survives a key change ==="
Write-Output "# new key = $NewKey"

# ---------------------------------------------------------------- before
$before = Get-Db "identity-before"
$accountBefore = (Invoke-Sql $before "select id from accounts order by sort_order limit 1;") -join ""
$nameBefore = (Invoke-Sql $before "select display_name from accounts order by sort_order limit 1;") -join ""
$countBefore = (Invoke-Sql $before "select count(*) from accounts;") -join ""
$credCountBefore = (Invoke-Sql $before "select count(*) from credentials;") -join ""
$snapIdsBefore = (Invoke-Sql $before "select id from usage_snapshots order by id;") -join ","
$widgetBefore = (Invoke-Sql $before "select c.widget_id || '|' || c.widget_type || '|' || ifnull(group_concat(s.slot_index || ':' || s.account_id, ',') || '', '-') from widget_config c left join widget_slots s on s.widget_id = c.widget_id group by c.widget_id order by c.widget_id;") -join ";"
# Credential rows of every account OTHER than the one being edited. A key change
# on account 1 must not so much as touch account 2's credential.
$otherCredsBefore = (Invoke-Sql $before "select id from credentials where id not in (select credential_id from accounts where id = '$accountBefore') order by id;") -join ","
Write-Output "# before: account=$accountBefore ($nameBefore) accounts=$countBefore credentials=$credCountBefore snapshots=$((@($snapIdsBefore -split ',' | Where-Object { $_ -ne '' })).Count)"
if ([string]::IsNullOrWhiteSpace($accountBefore)) {
    throw "no account exists yet; open the app once so it creates one"
}

# ------------------------------------------------- list -> detail navigation
cmd /c "adb shell am force-stop $Package" | Out-Null
Start-Sleep -Milliseconds 800
cmd /c "adb shell am start -n $Package/$Activity" | Out-Null
Start-Sleep -Seconds 4

$nodes = Get-UiNodes
$card = $nodes | Where-Object { $_.Bounds -ne $null -and $_.Text -eq $nameBefore } | Select-Object -First 1
if (-not $card) {
    Write-Output "RESULT: FAILED (could not find the '$nameBefore' card on the account list)"
    exit 1
}
$tap = Tap-Node $card
Write-Output "# tapped the '$nameBefore' card at $($tap -join ',')"
Start-Sleep -Seconds 3

$focus = cmd /c "adb shell dumpsys window 2>&1" | Select-String -Pattern "mCurrentFocus"
if ($focus -notmatch [regex]::Escape($DetailActivity)) {
    Write-Output "RESULT: FAILED (the detail page did not open; focus=$($focus -join ';'))"
    exit 1
}

$nodes = Get-UiNodes
$onDetail = ($nodes | Where-Object { $_.Text -eq $nameBefore } | Measure-Object).Count -gt 0
Add-Check "the detail page shows the account that was tapped" $onDetail "looking for '$nameBefore'"

# The key field shows the masked key when one is stored and the 'sk-...' hint
# when it is empty. Either way it is the only text node of that shape.
$keyField = $nodes | Where-Object { $_.Bounds -ne $null -and ($_.Text -match '^[•]+$' -or $_.Text -eq "sk-...") } | Select-Object -First 1
if (-not $keyField) {
    Write-Output "RESULT: FAILED (no API key field on the detail page)"
    exit 1
}

# The box is already ticked whenever a credential exists, so a blind tap
# UNTICKS it and clears the stored key — the exact opposite of what this
# script needs.
$rememberBox = $nodes | Where-Object { $_.Text -eq "记住密钥" } | Select-Object -First 1
$wasChecked = $false
if ($rememberBox) { $wasChecked = [bool]$rememberBox.Checked }
Write-Output "# remember checkbox before tap: checked=$wasChecked"

# ---------------------------------------------------------------- change the key
$tap = Tap-Node $keyField
Write-Output "# focused the key field at $($tap -join ',')"
Start-Sleep -Milliseconds 800
# Cursor to end, then delete everything already there.
cmd /c "adb shell input keyevent 123" | Out-Null
$delete = ("67 " * 64).Trim()
cmd /c "adb shell input keyevent $delete" | Out-Null
Start-Sleep -Milliseconds 600
cmd /c "adb shell input text $NewKey" | Out-Null
Start-Sleep -Milliseconds 800
cmd /c "adb shell input keyevent 111" | Out-Null
Start-Sleep -Milliseconds 600

if ($rememberBox -and -not $wasChecked) {
    $tap = Tap-Node $rememberBox
    Start-Sleep -Seconds 3
    $nodes = Get-UiNodes
    $after = $nodes | Where-Object { $_.Text -eq "记住密钥" } | Select-Object -First 1
    $nowChecked = $false
    if ($after) { $nowChecked = [bool]$after.Checked }
    Write-Output "# remember checkbox after tap at $($tap -join ','): checked=$nowChecked"
    if (-not $nowChecked) {
        Write-Output "RESULT: FAILED (could not tick the remember checkbox)"
        exit 1
    }
}
Start-Sleep -Seconds 2

# ---------------------------------------------------------------- after
$after = Get-Db "identity-after"
$accountAfter = (Invoke-Sql $after "select id from accounts order by sort_order limit 1;") -join ""
$countAfter = (Invoke-Sql $after "select count(*) from accounts;") -join ""
$credCountAfter = (Invoke-Sql $after "select count(*) from credentials;") -join ""
$snapIdsAfter = (Invoke-Sql $after "select id from usage_snapshots order by id;") -join ","
$widgetAfter = (Invoke-Sql $after "select c.widget_id || '|' || c.widget_type || '|' || ifnull(group_concat(s.slot_index || ':' || s.account_id, ',') || '', '-') from widget_config c left join widget_slots s on s.widget_id = c.widget_id group by c.widget_id order by c.widget_id;") -join ";"
$otherCredsAfter = (Invoke-Sql $after "select id from credentials where id not in (select credential_id from accounts where id = '$accountAfter') order by id;") -join ","
$protection = (Invoke-Sql $after "select protection from credentials where id in (select credential_id from accounts where id = '$accountAfter');") -join ""
Write-Output "# after:  account=$accountAfter accounts=$countAfter credentials=$credCountAfter snapshots=$((@($snapIdsAfter -split ',' | Where-Object { $_ -ne '' })).Count) protection=$protection"

# ---------------------------------------------------------------- assertions
Add-Check "account id is unchanged" ($accountBefore -eq $accountAfter) "$accountBefore -> $accountAfter"
Add-Check "account count is unchanged" ($countBefore -eq $countAfter) "$countBefore -> $countAfter"
Add-Check "no account was recreated" (-not [string]::IsNullOrWhiteSpace($accountAfter)) "id=$accountAfter"

# History is append-only: every row that existed before must still exist.
$missing = @()
foreach ($id in @($snapIdsBefore -split ',' | Where-Object { $_ -ne '' })) {
    if ($snapIdsAfter -notmatch "(^|,)$id(,|$)") { $missing += $id }
}
Add-Check "no history row was lost" ($missing.Count -eq 0) "missing ids: $($missing -join ',')"

Add-Check "widget configuration is unchanged" ($widgetBefore -eq $widgetAfter) "$widgetBefore -> $widgetAfter"

# Editing account 1 is an edit of account 1 only.
$otherCredsChanged = ($otherCredsBefore -ne $otherCredsAfter)
Add-Check "other accounts' credentials are untouched" (-not $otherCredsChanged) "'$otherCredsBefore' -> '$otherCredsAfter'"

# A key change must leave exactly one credential per account, not an orphan per
# attempt. The checkbox was verified ticked above, so a missing row is real.
Add-Check "each account still has exactly one credential" ([int]$credCountAfter -eq [int]$countAfter) "credential rows: $credCountBefore -> $credCountAfter for $countAfter account(s)"

# The stored payload must be Keystore-encrypted, never plaintext.
Add-Check "credential is Keystore-protected" ($protection -eq "keystore-aes-gcm") "protection=$protection"
$rawText = [Text.Encoding]::ASCII.GetString([IO.File]::ReadAllBytes($after))
$plaintextHits = ([regex]::Matches($rawText, "sk-[A-Za-z0-9_\-]{8,}")).Count
Add-Check "no plaintext key in the database" ($plaintextHits -eq 0) "sk- occurrences=$plaintextHits"

# ---------------------------------------------------------------- report
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
Write-Output "RESULT: all $($checks.Count) identity checks passed"
exit 0
