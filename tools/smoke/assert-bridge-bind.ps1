<#
    Phase 7 bind acceptance: the four ways an operator starts a paired Bridge, and
    the two things that must be true in every one of them - the addresses the
    Bridge puts in a pairing offer are addresses its listener actually serves, and
    the command line it prints for pairing a phone works when pasted.

    This exists because docs/PHASE-7-REVIEW.md P2 measured the opposite: the
    default --pair bound 127.0.0.1 while offering this machine's LAN address, and
    the suggested --add-device line omitted --host and --data-dir, so it either
    dialled an address that refused it or read an identity directory belonging to
    nobody. Everything below runs against the real binary, and the advice the
    Bridge prints is executed as printed rather than re-derived.

    What is checked here is the process: startup lines, refusals, and the
    reachability of each offered address over TLS, pinned to the certificate in the
    identity directory (a DER thumbprint comparison; the SPKI digest the phone pins
    is checked against the same certificate in bridge/internal/server/bind_test.go).

    Nothing here writes to a device. The default-data-dir run uses the directory the
    operator's own Bridge uses, which is what the default configuration means; the
    other runs use a throwaway directory under %TEMP%.

    Usage:
        powershell -File tools/smoke/assert-bridge-bind.ps1
        powershell -File tools/smoke/assert-bridge-bind.ps1 -Port 38590
#>
param(
    [int]$Port = 38478,
    [string]$GoExe = "C:\Program Files\Go\bin\go.exe"
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

function OneLine {
    param([string]$Text)
    $brief = (([string]$Text) -replace "\s+", " ").Trim()
    if ($brief.Length -gt 400) { $brief = $brief.Substring(0, 400) }
    return $brief
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$bridgeDir = Join-Path $repoRoot "bridge"
$exe = Join-Path $bridgeDir "bin\aiusage-bridge.exe"

if (-not (Test-Path $GoExe)) {
    throw "Go toolchain not found at $GoExe - install it or pass -GoExe"
}

Write-Output "== building the Bridge =="
Push-Location $bridgeDir
try {
    & $GoExe "build" "-o" "bin\aiusage-bridge.exe" ".\cmd\aiusage-bridge"
    $buildExit = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($buildExit -ne 0 -or -not (Test-Path $exe)) {
    throw "go build failed (exit $buildExit)"
}
Note "built: $exe"

# --- helpers ---------------------------------------------------------------

$script:procs = @()

# The Bridge process still owns its redirected stdout, so the file is opened for
# sharing: ReadAllText fails with "another process is using it" and a poll that
# cannot read looks like a Bridge that never started.
function Read-FileText {
    param([string]$Path)
    if (-not (Test-Path $Path)) { return "" }
    try {
        $stream = [IO.File]::Open($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::ReadWrite)
        try {
            $reader = New-Object IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
            try {
                return $reader.ReadToEnd()
            } finally {
                $reader.Dispose()
            }
        } finally {
            $stream.Dispose()
        }
    } catch {
        return ""
    }
}

function Count-Matches {
    param([string]$Text, [string]$Pattern)
    return @([regex]::Matches($Text, $Pattern)).Count
}

# Start-Process joins an argument array without quoting it, so a data directory
# containing a space arrives as two words - and Go's flag package stops parsing at
# the first stray one, which is how a LAN bind silently became a loopback bind in
# the first run of this script. Quoting here is what makes the spaced directory a
# test rather than a trap.
function Format-Arguments {
    param([string[]]$Arguments)
    return @($Arguments | ForEach-Object {
        if ($_ -match '\s') { '"' + $_ + '"' } else { $_ }
    })
}

function Start-PairedBridge {
    param([string]$DataDir, [int]$Port, [string[]]$Extra = @(), [string]$Label)
    $out = Join-Path $script:work ("out-$Label.txt")
    $err = Join-Path $script:work ("err-$Label.txt")
    $argList = @("--pair", "--port", "$Port")
    if ($DataDir) { $argList += @("--data-dir", "$DataDir") }
    $argList += $Extra
    $p = Start-Process -FilePath $exe -ArgumentList (Format-Arguments $argList) `
        -RedirectStandardOutput $out -RedirectStandardError $err -PassThru -WindowStyle Hidden
    $handle = @{ Proc = $p; Out = $out; Err = $err; Label = $Label; Args = (Format-Arguments $argList) }
    $script:procs += $handle
    return $handle
}

# Wait for the startup words rather than for a port: the addresses printed are then
# the ones this process produced, which is the whole subject of these checks.
function Wait-Startup {
    param($Handle, [int]$Tries = 80)
    for ($i = 0; $i -lt $Tries; $i++) {
        $text = Read-FileText $Handle.Out
        if ($text -match "listening on") { return $text }
        if ($Handle.Proc.HasExited) { return ((Read-FileText $Handle.Out) + "`n" + (Read-FileText $Handle.Err)) }
        Start-Sleep -Milliseconds 250
    }
    Note "no startup line appeared; the Bridge was called as: $($Handle.Args -join ' ')"
    return (Read-FileText $Handle.Out)
}

function Get-OfferedAddresses {
    param([string]$Text)
    if ($Text -notmatch "addresses offered to phones: ([^\r\n]*)") { return "" }
    # The startup line joins with ", " for a human; the comparison below is against
    # the payload's own list, so the separators are normalised rather than assumed.
    return (@($Matches[1].Split(",") | ForEach-Object { $_.Trim() } | Where-Object { $_ }) -join ",")
}

function Get-Fingerprint {
    param([string]$Text)
    if ($Text -match "fingerprint: ([0-9a-f]{64})") { return $Matches[1] }
    return ""
}

function Get-StatePath {
    param([string]$Text)
    if ($Text -match "state: ([^\r\n]+\.json)") { return $Matches[1].Trim() }
    return ""
}

# The hint is meant to be pasted, so its exact shape is asserted and then its
# captured pieces are run. An unquoted Windows path with a space in it fails the
# shape match, which is why one configuration deliberately uses such a directory.
$script:hintPattern = '^"([^"]*)" --port (\d+) --data-dir "([^"]*)" --add-device$'

# Runs the Bridge's own printed advice and asserts the four properties that make it
# usable. The result is left in $script:hintResult rather than returned: a returned
# value would capture the Check lines into it, and four failures per configuration
# would be counted and never shown - which is what happened on the first run.
function Assert-HintWorks {
    param([string]$Label, [string]$StartupText, [string]$ExpectedPort)
    $hint = ""
    if ($StartupText -match "(?m)^\s*to pair a phone: (.*)$") { $hint = $Matches[1] }
    Check "$Label the printed hint is a quotable command line" ($hint -match $script:hintPattern) "hint=$(OneLine $hint)"
    $ran = Invoke-Hint $hint
    Check "$Label the printed hint pairs against this instance" ($ran.Exit -eq 0 -and $ran.Text -match "offer:") "exit=$($ran.Exit) out=$(OneLine $ran.Text)"
    Check "$Label the hint carries the port that was asked for" ($ran.Port -eq "$ExpectedPort") "hint=$hint"
    $statePath = Get-StatePath $StartupText
    $dataDir = Split-Path $statePath -Parent
    Check "$Label the hint carries this instance's --data-dir" ($ran.DataDir -eq $dataDir) "hint=$hint state=$statePath"
    $script:hintResult = $ran
}

# One short-lived call of the binary, with stdout and stderr read from files.
# Merging them through the PowerShell pipeline instead (2>&1) re-renders a refusal
# as an error record, wraps it mid-word and hides the very text being asserted -
# measured on the first run, where the message naming "--host" arrived as "- -host".
function Invoke-Bridge {
    param([string]$FilePath, [string[]]$Arguments)
    $tag = [Guid]::NewGuid().ToString("N").Substring(0, 8)
    $outFile = Join-Path $script:work "run-$tag.out"
    $errFile = Join-Path $script:work "run-$tag.err"
    # -Wait rather than a manual WaitForExit: a Process handed back without it has no
    # cached exit status, and $null -ne 0 is true, so every "was refused" check below
    # would have passed without the Bridge refusing anything. Measured: the first run
    # of this script printed "exit=" and still reported a refusal.
    $p = Start-Process -FilePath $FilePath -ArgumentList (Format-Arguments $Arguments) `
        -RedirectStandardOutput $outFile -RedirectStandardError $errFile -PassThru -Wait -NoNewWindow
    if ($null -eq $p.ExitCode) {
        throw "no exit code from $FilePath $($Arguments -join ' ') - a check on it would be vacuous"
    }
    return @{ Exit = [int]$p.ExitCode; Text = ((Read-FileText $outFile) + "`n" + (Read-FileText $errFile)) }
}

function Invoke-Hint {
    param([string]$Hint)
    $line = $Hint.Trim()
    if ($line -notmatch $script:hintPattern) {
        return @{ Shape = $false; Exit = -1; Text = $line; Port = ""; DataDir = "" }
    }
    $result = Invoke-Bridge $Matches[1] @("--port", $Matches[2], "--data-dir", $Matches[3], "--add-device")
    return @{ Shape = $true; Exit = $result.Exit; Text = $result.Text; Port = $Matches[2]; DataDir = $Matches[3] }
}

function Get-IdentityThumbprint {
    param([string]$DataDir)
    $pem = Read-FileText (Join-Path $DataDir "identity.crt.pem")
    if (-not $pem) { return "" }
    $b64 = ($pem -split "\r?\n" | Where-Object { $_ -notmatch "-----" }) -join ""
    try {
        $cert = New-Object System.Security.Cryptography.X509Certificates.X509Certificate2
        $cert.Import([Convert]::FromBase64String($b64))
        return $cert.GetCertHashString().ToUpperInvariant()
    } catch {
        Note "the identity certificate in $DataDir could not be read: $($_.Exception.Message)"
        return ""
    }
}

# Reachability over TLS, pinned: the certificate on the wire must be the one in
# this instance's identity directory. An empty expected thumbprint fails closed
# rather than trusting anything. This is the certificate's DER thumbprint, which is
# a different digest from the SPKI digest the phone pins; the two are tied together
# in Go (TestFingerprintIsTheServedCertificatesSPKI), and what is checked here is
# that a listener answers at the address the offer named, with this instance's own
# certificate.
#
# The callback is a compiled static method rather than a PowerShell scriptblock: a
# scriptblock delegate runs on a thread with no runspace, and Invoke-WebRequest
# then fails the handshake with "线程中没有可用的执行空间" - measured on the first
# run of this script, where every pinned request failed for that reason.
$script:pinSource = @"
using System;
using System.Net;
using System.Net.Security;
using System.Security.Cryptography.X509Certificates;
public static class BridgePin {
    public static string Thumbprint = "";
    public static bool Check(object sender, X509Certificate certificate, X509Chain chain, SslPolicyErrors errors) {
        if (string.IsNullOrEmpty(Thumbprint) || certificate == null) { return false; }
        return string.Equals(certificate.GetCertHashString(), Thumbprint, StringComparison.OrdinalIgnoreCase);
    }
}
"@

function Install-PinCallback {
    if (-not ("BridgePin" -as [type])) {
        Add-Type -TypeDefinition $script:pinSource | Out-Null
    }
    $method = [BridgePin].GetMethod("Check")
    $callbackType = [Net.ServicePointManager].GetProperty("ServerCertificateValidationCallback").PropertyType
    # PowerShell 5.1 offers TLS 1.0 by default and the Bridge requires 1.2, so the
    # handshake is asked for in a protocol both sides have.
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    [Net.ServicePointManager]::ServerCertificateValidationCallback =
        [System.Delegate]::CreateDelegate($callbackType, $method)
}

function Get-Tls {
    param([string]$HostAddress, [int]$Port, [string]$Thumbprint)
    [BridgePin]::Thumbprint = $Thumbprint
    try {
        # -DisableKeepAlive because a pooled connection skips the handshake, and a
        # skipped handshake means the pin was never consulted: measured here, where
        # the "foreign certificate is refused" check saw a 200 because its request
        # rode the connection the correct pin had already opened to the same port.
        $r = Invoke-WebRequest -UseBasicParsing -DisableKeepAlive `
            -Uri "https://${HostAddress}:$Port/v1/health" -TimeoutSec 8
        return @{ Status = [int]$r.StatusCode; Body = $r.Content; Error = "" }
    } catch {
        $resp = $_.Exception.Response
        if ($null -ne $resp) {
            $body = ""
            if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $body = $_.ErrorDetails.Message }
            return @{ Status = [int]$resp.StatusCode; Body = $body; Error = "" }
        }
        return @{ Status = 0; Body = ""; Error = $_.Exception.Message }
    }
}

function Get-TlsStatus {
    param([string]$HostAddress, [int]$Port, [string]$Thumbprint)
    return (Get-Tls $HostAddress $Port $Thumbprint).Status
}

function Get-TlsFailure {
    param([string]$HostAddress, [int]$Port, [string]$Thumbprint)
    $r = Get-Tls $HostAddress $Port $Thumbprint
    $why = $r.Error
    if (-not $why) { $why = "status=$($r.Status) body=$(OneLine $r.Body)" }
    return "at https://${HostAddress}:$Port : $why"
}

# aiusage://pair#<base64url json>, decoded here rather than trusted because the
# payload is what the phone will act on.
function Decode-Payload {
    param([string]$Text)
    if ($Text -notmatch "(aiusage://pair#([A-Za-z0-9_\-]+))") { return $null }
    $b64 = $Matches[2].Replace("-", "+").Replace("_", "/")
    switch ($b64.Length % 4) {
        2 { $b64 += "==" }
        3 { $b64 += "=" }
        1 { return $null }
    }
    $raw = [Convert]::FromBase64String($b64)
    return ([Text.Encoding]::UTF8.GetString($raw) | ConvertFrom-Json)
}

function Get-PayloadFromOutput {
    param([string]$Text)
    if ($Text -match "offer:\s+(aiusage://pair#\S+)") { return $Matches[1] }
    return ""
}

function Stop-All {
    foreach ($h in $script:procs) {
        try {
            Stop-Process -Id $h.Proc.Id -Force -ErrorAction Stop
        } catch {
            Note "process had already exited"
        }
    }
    $script:procs = @()
}

# --- run -------------------------------------------------------------------

$script:work = Join-Path $env:TEMP ("aiusage-bind-accept-" + (Get-Date -Format "yyyyMMddHHmmss"))
New-Item -ItemType Directory -Path $script:work | Out-Null

$lanIp = $null
try {
    $lanIp = (Get-NetIPAddress -AddressFamily IPv4 |
        Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } |
        Select-Object -First 1).IPAddress
} catch {
    Note "Get-NetIPAddress unavailable; the LAN and wildcard configurations are skipped"
}
Note "this machine's LAN address: $(if ($lanIp) { $lanIp } else { '(none found)' })"

$probe = New-Object System.Net.Sockets.TcpClient
$busy = $false
try {
    $probe.Connect("127.0.0.1", $Port)
    $busy = $probe.Connected
} catch {
    $busy = $false
} finally {
    $probe.Close()
}
if ($busy) {
    throw "port $Port is already in use - stop the running Bridge or pass -Port"
}
Install-PinCallback

try {
    Note "== 1. default --pair: loopback bind, default data dir =="
    $h1 = Start-PairedBridge -Port $Port -Label "default"
    $text1 = Wait-Startup $h1
    Check "1 the Bridge starts in paired mode on loopback" ($text1 -match "listening on https://127\.0\.0\.1:$Port") "stdout=$(OneLine $text1)"
    Check "1 a loopback bind prints one listener" ((Count-Matches $text1 "listening on") -eq 1) (OneLine $text1)
    $offered1 = Get-OfferedAddresses $text1
    # The regression itself: the old default offered the LAN address of a Bridge
    # that was listening on loopback only.
    Check "1 the offer names only the loopback address it serves" ($offered1 -eq "127.0.0.1") "offered=$offered1"
    Check "1 the offer names no LAN address" (-not ($offered1 -match [regex]::Escape("$lanIp"))) "offered=$offered1"
    $state1 = Get-StatePath $text1
    Check "1 the startup line names the state file it uses" ($state1 -match "state\.json$") "state=$state1"
    $thumb1 = Get-IdentityThumbprint (Split-Path $state1 -Parent)
    Check "1 loopback answers over TLS with this instance's certificate" `
        ((Get-TlsStatus "127.0.0.1" $Port $thumb1) -eq 200) (Get-TlsFailure "127.0.0.1" $Port $thumb1)
    Assert-HintWorks "1" $text1 $Port
    $payload1 = Decode-Payload (Get-PayloadFromOutput $script:hintResult.Text)
    Check "1 the pairing payload decodes" ($null -ne $payload1) "output=$(OneLine $script:hintResult.Text)"
    if ($null -ne $payload1) {
        Check "1 the payload offers the addresses the startup line promised" ((@($payload1.hosts) -join ",") -eq $offered1) `
            "payload=$(($payload1.hosts) -join ',') startup=$offered1"
        Check "1 the payload carries the listening port" ([int]$payload1.port -eq $Port) "port=$($payload1.port)"
        Check "1 the payload pins the printed fingerprint" ($payload1.fingerprint -eq (Get-Fingerprint $text1)) `
            "payload=$($payload1.fingerprint) startup=$(Get-Fingerprint $text1)"
        Check "1 the payload carries a 64-character one-time token" (@($payload1.pairToken).Count -eq 1 -and $payload1.pairToken.Length -eq 64) `
            "token length=$($payload1.pairToken.Length)"
    }
    Stop-All

    Note "== 2. --advertise naming an address the bind does not serve =="
    $dir2 = Join-Path $script:work "advertise-mismatch"
    New-Item -ItemType Directory -Path $dir2 | Out-Null
    $bogus = "198.51.100.7"
    if ($lanIp) { $bogus = $lanIp }
    $mismatch = Invoke-Bridge $exe @("--pair", "--port", "$($Port + 1)", "--data-dir", $dir2, "--advertise", $bogus)
    Check "2 binding loopback while advertising $bogus is refused" ($mismatch.Exit -ne 0) "exit=$($mismatch.Exit) out=$(OneLine $mismatch.Text)"
    Check "2 the refusal names the address it would have offered" ($mismatch.Text -match [regex]::Escape($bogus)) "out=$(OneLine $mismatch.Text)"
    Check "2 the refusal says what to change" ($mismatch.Text -match "--host") "out=$(OneLine $mismatch.Text)"
    $probe2 = New-Object System.Net.Sockets.TcpClient
    $listening = $false
    try {
        $probe2.Connect("127.0.0.1", ($Port + 1))
        $listening = $probe2.Connected
    } catch {
        $listening = $false
    } finally {
        $probe2.Close()
    }
    Check "2 the refused Bridge serves nothing" (-not $listening) "a listener answered on $(($Port + 1))"

    Note "== 3. --pair bound to the LAN address =="
    if ($lanIp) {
        # A directory with a space in it, because the hint the Bridge prints has to
        # survive being pasted.
        $dir3 = Join-Path $script:work "lan bind"
        New-Item -ItemType Directory -Path $dir3 | Out-Null
        $lanPort = $Port + 2
        $h3 = Start-PairedBridge -DataDir $dir3 -Port $lanPort -Extra @("--host", $lanIp) -Label "lan"
        $text3 = Wait-Startup $h3
        Check "3 a LAN bind prints a listener on the bound address" ((Count-Matches $text3 "listening on https://$([regex]::Escape($lanIp)):$lanPort") -eq 1) (OneLine $text3)
        Check "3 a LAN bind also prints a loopback listener" ((Count-Matches $text3 "listening on https://127\.0\.0\.1:$lanPort") -eq 1) (OneLine $text3)
        Check "3 a LAN bind prints exactly those two listeners" ((Count-Matches $text3 "listening on") -eq 2) (OneLine $text3)
        $offered3 = Get-OfferedAddresses $text3
        Check "3 the offer names the bound address and loopback" ($offered3 -eq "$lanIp,127.0.0.1") "offered=$offered3"
        $thumb3 = Get-IdentityThumbprint $dir3
        Check "3 the bound address answers over TLS, pinned" ((Get-TlsStatus $lanIp $lanPort $thumb3) -eq 200) (Get-TlsFailure $lanIp $lanPort $thumb3)
        Check "3 loopback still answers, pinned" ((Get-TlsStatus "127.0.0.1" $lanPort $thumb3) -eq 200) (Get-TlsFailure "127.0.0.1" $lanPort $thumb3)
        # A wrong pin must not read as reachable: the same socket, another
        # instance's certificate expected.
        $otherThumb = Get-IdentityThumbprint (Split-Path $state1 -Parent)
        Check "3 the other instance is a different certificate" ($otherThumb -ne "" -and $otherThumb -ne $thumb3) `
            "other=$otherThumb this=$thumb3"
        Check "3 a foreign certificate is refused at the LAN address" ((Get-TlsStatus $lanIp $lanPort $otherThumb) -eq 0) `
            "the pin did not bite at $(Get-TlsFailure $lanIp $lanPort $otherThumb)"
        Assert-HintWorks "3" $text3 $lanPort
        $payload3 = Decode-Payload (Get-PayloadFromOutput $script:hintResult.Text)
        if ($null -eq $payload3) {
            Check "3 the LAN-bound offer decodes" $false "output=$(OneLine $script:hintResult.Text)"
        } else {
            Check "3 the payload carries the reachable addresses" ((@($payload3.hosts) -join ",") -eq "$lanIp,127.0.0.1") `
                "payload=$(($payload3.hosts) -join ',')"
        }
        # The other half of the review's complaint: the hint used to omit --data-dir,
        # so pasting it read some other identity directory. Refusal is the property;
        # which of the two messages appears depends on whether the default directory
        # happens to hold an identity, so both are named and neither is silently
        # accepted.
        $wrong = Invoke-Bridge $exe @("--port", "$lanPort", "--add-device")
        Check "3 --add-device without the Bridge's --data-dir cannot pair" `
            ($wrong.Exit -ne 0 -and ($wrong.Text -match "no Bridge identity" -or $wrong.Text -match "does not match the pinned fingerprint")) `
            "exit=$($wrong.Exit) out=$(OneLine $wrong.Text)"
        Stop-All
    } else {
        Note "3 skipped: no LAN address on this machine"
    }

    Note "== 4. --pair bound to every address =="
    if ($lanIp) {
        $dir4 = Join-Path $script:work "wildcard"
        New-Item -ItemType Directory -Path $dir4 | Out-Null
        $wildPort = $Port + 3
        $h4 = Start-PairedBridge -DataDir $dir4 -Port $wildPort -Extra @("--host", "0.0.0.0") -Label "wildcard"
        $text4 = Wait-Startup $h4
        Check "4 a wildcard bind prints one listener" ((Count-Matches $text4 "listening on") -eq 1) (OneLine $text4)
        $offered4 = Get-OfferedAddresses $text4
        Check "4 the offer carries the LAN address" ($offered4 -match [regex]::Escape($lanIp)) "offered=$offered4"
        Check "4 the offer carries loopback" ($offered4 -match "127\.0\.0\.1") "offered=$offered4"
        Check "4 the offer carries no link-local address" (-not ($offered4 -match "169\.254\.")) "offered=$offered4"
        $thumb4 = Get-IdentityThumbprint $dir4
        Check "4 the LAN address answers, pinned" ((Get-TlsStatus $lanIp $wildPort $thumb4) -eq 200) (Get-TlsFailure $lanIp $wildPort $thumb4)
        Check "4 loopback answers, pinned" ((Get-TlsStatus "127.0.0.1" $wildPort $thumb4) -eq 200) (Get-TlsFailure "127.0.0.1" $wildPort $thumb4)
        Assert-HintWorks "4" $text4 $wildPort
        Stop-All
    } else {
        Note "4 skipped: no LAN address on this machine"
    }

    Note "== 5. a custom --data-dir is the instance the hint points at =="
    $dir5 = Join-Path $script:work "custom-dir"
    New-Item -ItemType Directory -Path $dir5 | Out-Null
    $customPort = $Port + 4
    $h5 = Start-PairedBridge -DataDir $dir5 -Port $customPort -Label "custom"
    $text5 = Wait-Startup $h5
    Check "5 the startup line names the state file inside the custom directory" `
        ((Get-StatePath $text5) -eq (Join-Path $dir5 "state.json")) "line=$(OneLine $text5)"
    Assert-HintWorks "5" $text5 $customPort
    Check "5 pairing through the printed hint leaves the registry on disk" (Test-Path (Join-Path $dir5 "pairing.json")) `
        "no pairing.json in $dir5"
    Stop-All

    Note "== 5b. an admin call against an empty directory mints nothing =="
    $dirNoId = Join-Path $script:work "no-identity"
    New-Item -ItemType Directory -Path $dirNoId | Out-Null
    $empty = Invoke-Bridge $exe @("--port", "$($Port + 6)", "--data-dir", $dirNoId, "--add-device")
    Check "5b --add-device against a directory with no identity is refused" `
        ($empty.Exit -ne 0 -and $empty.Text -match "no Bridge identity") "exit=$($empty.Exit) out=$(OneLine $empty.Text)"
    $left = @(Get-ChildItem $dirNoId -Force)
    Check "5b the refused call left no identity behind" ($left.Count -eq 0) "files: $(($left | ForEach-Object { $_.Name }) -join ', ')"

    Note "== 6. a network bind without --pair is still refused =="
    $plain = Invoke-Bridge $exe @("--host", $(if ($lanIp) { $lanIp } else { "0.0.0.0" }), "--port", "$($Port + 5)", "--data-dir", $dir5)
    Check "6 binding a network address without --pair is refused" ($plain.Exit -ne 0 -and $plain.Text -match "non-loopback") `
        "exit=$($plain.Exit) out=$(OneLine $plain.Text)"
    Check "6 an identity on disk is not enough to earn a network bind" ($plain.Text -match "--pair") "out=$(OneLine $plain.Text)"
} finally {
    Stop-All
    if (Test-Path $script:work) {
        Remove-Item -Recurse -Force $script:work -ErrorAction SilentlyContinue
    }
}

Write-Output ""
Write-Output "== summary: $script:checks checks, $script:failures failed =="
if ($script:failures -gt 0) {
    exit 1
}
Write-Output "RESULT: *PASS*"
