<#
    Phase 5 Bridge acceptance: read the three numbers Spec asks for over the
    localhost API, and prove the four properties that make the reading safe to
    show - the bind guard, the cache, the never-clear-on-failure rule, and the
    absence of any credential in what the Bridge serves or stores.

    Covers docs/PHASE-5-PLAN.md rows B1-B6 and B9, plus the branches the Phase 5
    review asked to be asserted on parsed fields instead of substrings
    (docs/PHASE-5-REVIEW.md §6.4): the class a cold failure serves, the mask on
    what a failure leaves in the response and on disk, and two refresh requests
    running at the same moment.

    B7 (Codex reporting "no such method") stays unit-tested: producing it here
    would mean replacing a real Codex install. B8 (Codex asking the client to
    refresh its tokens) is now produced against a real child process - the
    internal/bridge test binary impersonates an app server when started with
    AIUSAGE_FAKE_APPSERVER, so discovery, the JSON-RPC client and the mask all run
    over a pipe. Neither is reported as a check that could never fail.

    Nothing here writes to a device and nothing here touches %USERPROFILE%\.codex.
    The Bridge is started against a throwaway --data-dir each run, and the fake app
    server is a compiled test binary inside that throwaway directory.

    Usage:
        powershell -File tools\smoke\assert-bridge.ps1
        powershell -File tools\smoke\assert-bridge.ps1 -Port 38500
#>
param(
    [int]$Port = 38477,
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
$goVersionLine = (& $GoExe "version") -join " "
Write-Output "built: $exe  ($goVersionLine)"

# --- helpers ---------------------------------------------------------------

$script:procs = @()

function Start-Bridge {
    param([string]$DataDir, [int]$Port, [string[]]$Extra = @())
    $argList = @("--port", "$Port", "--data-dir", "$DataDir") + $Extra
    $p = Start-Process -FilePath $exe -ArgumentList $argList -PassThru -WindowStyle Hidden
    $script:procs += $p
    return $p
}

function Wait-Health {
    param([int]$Port, [int]$Tries = 30)
    $i = 0
    while ($i -lt $Tries) {
        $i++
        try {
            $r = Invoke-WebRequest -UseBasicParsing -Uri "http://127.0.0.1:$Port/v1/health" -TimeoutSec 3
            if ($r.StatusCode -eq 200) {
                return $r
            }
        } catch {
            Start-Sleep -Milliseconds 300
        }
    }
    return $null
}

# Get-Url returns status and body even for non-2xx, because 503 with a class name
# in the body is the correct answer for a broken Codex and must be inspectable.
function Get-Url {
    param([string]$Url)
    $status = 0
    $body = ""
    try {
        $r = Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 30
        $status = [int]$r.StatusCode
        $body = $r.Content
    } catch {
        $resp = $_.Exception.Response
        if ($null -eq $resp) {
            return @{ Status = 0; Body = $_.Exception.Message }
        }
        $status = [int]$resp.StatusCode
        # PowerShell 5.1 has already drained the response stream by the time the
        # error record surfaces, so the body is only reachable on ErrorDetails.
        # Reading the stream instead returns "" and turns a real 503 with a class
        # name into a check that fails for the wrong reason.
        if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
            $body = $_.ErrorDetails.Message
        } else {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $body = $reader.ReadToEnd()
            $reader.Close()
        }
    }
    return @{ Status = $status; Body = $body }
}

function Stop-All {
    foreach ($p in $script:procs) {
        try {
            Stop-Process -Id $p.Id -Force -ErrorAction Stop
        } catch {
            Note "process $($p.Id) had already exited"
        }
    }
    $script:procs = @()
}

# The host's Codex reaches an internet this Bridge does not, and it fails
# intermittently: measured twice on this machine as a 503 saying "failed to fetch
# codex rate limits: error sending request for url
# (https://chatgpt.com/backend-api/wham/usage)", clearing by itself within about a
# minute. A check that needs a real reading must not blame the Bridge for that, so
# it asks a few times and, if the host never answered, says so out loud instead of
# either passing silently or reporting a product failure that is not one.
function Get-ReadingWithRetry {
    param([string]$Url, [int]$Tries = 4, [int]$SleepSec = 12)
    $last = $null
    for ($i = 1; $i -le $Tries; $i++) {
        $last = Get-Url $Url
        if ($last.Status -eq 200) { return $last }
        $brief = (([string]$last.Body) -replace "\s+", " ").Trim()
        if ($brief.Length -gt 150) { $brief = $brief.Substring(0, 150) }
        Note "the host's Codex has no reading yet ($i/$Tries): $brief"
        if ($i -lt $Tries) { Start-Sleep -Seconds $SleepSec }
    }
    return $last
}

# A JWT is three dot-separated base64url segments; a bare "eyJ" is what the
# Bridge deliberately leaves visible as a fingerprint, so the tail is the part
# that must be gone.
$script:secretPattern = "eyJ[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}\.[A-Za-z0-9_-]{4,}"
$script:skPattern = "sk-[A-Za-z0-9_-]{12,}"

function Assert-NoSecret {
    param([string]$Label, [string]$Text)
    $hitJwt = ([regex]::Matches($Text, $script:secretPattern)).Count
    $hitSk = ([regex]::Matches($Text, $script:skPattern)).Count
    $ok = ($hitJwt -eq 0 -and $hitSk -eq 0)
    Check "$Label carries no credential shape" $ok "jwt=$hitJwt sk=$hitSk"
}

# --- run -------------------------------------------------------------------

$work = Join-Path $env:TEMP ("aiusage-bridge-accept-" + (Get-Date -Format "yyyyMMddHHmmss"))
New-Item -ItemType Directory -Path $work | Out-Null
$dirMain = Join-Path $work "main"
$dirSeeded = Join-Path $work "seeded"
$dirEmpty = Join-Path $work "empty"
New-Item -ItemType Directory -Path $dirMain, $dirSeeded, $dirEmpty | Out-Null

try {
    $usageUrl = "http://127.0.0.1:$Port/v1/accounts/codex/usage"

    Note "== B1 / B3 / B4 / B6: a normal Bridge over a live Codex =="
    $null = Start-Bridge -DataDir $dirMain -Port $Port
    $health = Wait-Health -Port $Port
    Check "bridge started and answers /v1/health" ($null -ne $health)

    # Retried, because a transient host outage otherwise turns one missing reading
    # into ten skipped checks and a B1 failure that reads like a product bug.
    $cold = Get-ReadingWithRetry -Url $usageUrl
    Check "B1 usage responds 200" ($cold.Status -eq 200) "status=$($cold.Status) body=$($cold.Body)"

    $doc = $null
    if ($cold.Status -eq 200) {
        $doc = $cold.Body | ConvertFrom-Json
    }

    if ($null -ne $doc) {
        $windows = @($doc.state.windows)
        Check "B1 at least two quota windows" ($windows.Count -ge 2) "got $($windows.Count)"

        $typed = $true
        $typeDetail = ""
        foreach ($w in $windows) {
            if (-not $w.id -or -not $w.label) {
                $typed = $false
                $typeDetail = "window missing id/label: $($w | ConvertTo-Json -Compress)"
            }
            if ($w.usedPercent -lt 0 -or $w.usedPercent -gt 100) {
                $typed = $false
                $typeDetail = "usedPercent out of range: $($w.usedPercent)"
            }
            if ($w.usedPercent + $w.remainingPercent -ne 100) {
                $typed = $false
                $typeDetail = "used+remaining != 100 for $($w.id)"
            }
        }
        Check "B1 every window is typed and self-consistent" $typed $typeDetail

        # Spec Phase 5 asks for exactly three things: 5H, Weekly, Reset. Percentages
        # are deliberately NOT asserted - they moved from 100 to 31 to 82 during the
        # development of this step, and a plan without a 5-hour window is a real
        # finding rather than a flake, so say which one it is.
        $has5h = $false
        $hasWeek = $false
        foreach ($w in $windows) {
            if ([int]$w.windowMinutes -eq 300) { $has5h = $true }
            if ([int]$w.windowMinutes -eq 10080) { $hasWeek = $true }
        }
        Check "B1 a 5-hour window is readable (Spec: 5H)" $has5h "durations: $(($windows | ForEach-Object { $_.windowMinutes }) -join ',')"
        Check "B1 a weekly window is readable (Spec: Weekly)" $hasWeek "durations: $(($windows | ForEach-Object { $_.windowMinutes }) -join ',')"

        $resetKnown = $false
        foreach ($w in $windows) {
            if ([long]$w.resetAtMillis -gt [long]1000000000000) { $resetKnown = $true }
        }
        Check "B1 a reset time is readable in epoch milliseconds (Spec: Reset)" $resetKnown `
            "resets: $(($windows | ForEach-Object { $_.resetAtMillis }) -join ',')"

        $readings = ""
        foreach ($w in $windows) {
            $resetText = "-"
            if ([long]$w.resetAtMillis -gt 0) {
                $resetText = [DateTimeOffset]::FromUnixTimeMilliseconds([long]$w.resetAtMillis).LocalDateTime.ToString("MM-dd HH:mm")
            }
            $readings += "$($w.label)=$($w.usedPercent)% reset=$resetText | "
        }
        Note "readings: $readings"

        Check "B1 plan type reported" ($doc.state.codexVersion -match "^[0-9]+\.[0-9]+\.") "codexVersion=$($doc.state.codexVersion)"

        $warm = Get-Url $usageUrl
        $warmDoc = $warm.Body | ConvertFrom-Json
        Check "B3 second read served from cache" ($warmDoc.fromCache -eq $true) "fromCache=$($warmDoc.fromCache)"
        Check "B3 cached read keeps the same source timestamp" ($warmDoc.dataTimestamp -eq $doc.dataTimestamp) `
            "$($warmDoc.dataTimestamp) vs $($doc.dataTimestamp)"

        $forced = $null
        $forcedDoc = $null
        for ($attempt = 1; $attempt -le 3; $attempt++) {
            $forced = Get-Url "$usageUrl`?refresh=1"
            $forcedDoc = $forced.Body | ConvertFrom-Json
            if ($forced.Status -eq 200 -and $forcedDoc.fromCache -eq $false) { break }
            if ($attempt -lt 3) { Start-Sleep -Seconds 12 }
        }
        $wentBack = ($forced.Status -eq 200 -and $forcedDoc.fromCache -eq $false)
        if (-not $wentBack -and ($null -ne $forcedDoc.degraded)) {
            # refresh=1 *did* go back to Codex and got nothing; serving the retained
            # numbers with the failure beside them is rule 18 working, and the
            # degraded marker is what distinguishes that from a stale cache.
            Note "B4 not judged this run: Codex answered nothing to the forced refresh ($((([string]$forced.Body) -replace '\s+', ' ').Trim())); the Bridge went back and kept the older reading"
        } else {
            Check "B4 refresh=1 goes back to Codex" $wentBack "fromCache=$($forcedDoc.fromCache) status=$($forced.Status)"
        }

        Assert-NoSecret "B6 usage body" $cold.Body
        Assert-NoSecret "B6 usage body (refreshed)" $forced.Body
        $stateFile = Join-Path $dirMain "state.json"
        if (Test-Path $stateFile) {
            Assert-NoSecret "B6 state.json" (Get-Content $stateFile -Raw)
        } else {
            Check "B6 state.json exists" $false "not written at $stateFile"
        }
    }

    $providers = Get-Url "http://127.0.0.1:$Port/v1/providers"
    Check "providers responds 200" ($providers.Status -eq 200) "status=$($providers.Status)"
    Assert-NoSecret "B6 providers body" $providers.Body

    Stop-All

    Note "== B5: the bind guard =="
    $badHost = Join-Path $work "guard"
    New-Item -ItemType Directory -Path $badHost | Out-Null
    $guardLog = Join-Path $work "guard.txt"
    $proc = Start-Process -FilePath $exe -ArgumentList @("--host", "0.0.0.0", "--port", "$($Port + 1)", "--data-dir", $badHost) `
        -RedirectStandardError $guardLog -PassThru -WindowStyle Hidden
    $proc.WaitForExit(10000) | Out-Null
    $guardText = ""
    if (Test-Path $guardLog) {
        $guardText = Get-Content $guardLog -Raw
    }
    Check "B5 refusing to bind 0.0.0.0 is reported" ($guardText -match "non-loopback") "stderr=$guardText"
    Check "B5 the Bridge exits rather than serving" ($proc.ExitCode -ne 0) "exit=$($proc.ExitCode)"

    $lanIp = $null
    try {
        $lanIp = (Get-NetIPAddress -AddressFamily IPv4 -ErrorAction Stop |
            Where-Object { $_.IPAddress -notlike "127.*" -and $_.IPAddress -notlike "169.254.*" } |
            Select-Object -First 1).IPAddress
    } catch {
        Note "Get-NetIPAddress unavailable; LAN reachability check skipped"
    }
    if ($lanIp) {
        $null = Start-Bridge -DataDir $dirMain -Port $Port
        $health2 = Wait-Health -Port $Port
        if ($null -ne $health2) {
            $client = New-Object System.Net.Sockets.TcpClient
            $ok = $false
            try {
                $task = $client.ConnectAsync($lanIp, $Port)
                $done = $task.Wait(1500)
                $ok = ($done -and -not $task.IsFaulted -and $client.Connected)
            } catch {
                $ok = $false
            }
            $client.Close()
            Check "B5 the port is not reachable on the LAN address $lanIp" (-not $ok) "connection succeeded"
        }
        Stop-All
    }

    Note "== B2: a Bridge that cannot find Codex =="
    $bogus = Join-Path $work "definitely-not-codex.exe"
    $emptyPort = $Port + 2
    $null = Start-Bridge -DataDir $dirEmpty -Port $emptyPort -Extra @("--codex", $bogus)
    $h3 = Wait-Health -Port $emptyPort
    $r3 = Get-Url "http://127.0.0.1:$emptyPort/v1/accounts/codex/usage"
    Check "B2 with no data and no Codex the answer is 503" ($r3.Status -eq 503) "status=$($r3.Status) body=$($r3.Body)"
    # Parsed rather than searched. The detail string also contains the class name,
    # so the old `-match "CODEX_NOT_FOUND"` stayed green while every cold failure
    # was being served as CODEX_UNKNOWN (docs/PHASE-5-REVIEW.md §2).
    $doc3 = $null
    try {
        $doc3 = $r3.Body | ConvertFrom-Json
    } catch {
        Note "B2 body is not the JSON a caller parses: $($r3.Body)"
    }
    Check "B2 the error field says the computer or Codex is missing" ($doc3.error -eq "CODEX_NOT_FOUND") `
        "error=$($doc3.error) body=$($r3.Body)"
    Assert-NoSecret "B2 the cold 503" $r3.Body
    Stop-All

    Note "== review §1 / §4 end to end: a real child that refuses, then leaks =="
    # The test binary of internal/bridge impersonates a Codex app server when it is
    # started with AIUSAGE_FAKE_APPSERVER set, so discovery, the JSON-RPC client,
    # the refusal bookkeeping and the mask all run over a genuine pipe. A stub
    # could not fail §4, because §4 is the production fetcher dropping the record.
    $fakeBin = Join-Path $work "bridgepkg.test.exe"
    Push-Location $bridgeDir
    try {
        & $GoExe "test" "-c" "-o" $fakeBin ".\internal\bridge"
        $testBuildExit = $LASTEXITCODE
    } finally {
        Pop-Location
    }
    if ($testBuildExit -eq 0 -and (Test-Path $fakeBin)) {
        # Two scenarios, because one shape cannot prove both properties: a
        # *recognised* refusal replaces the upstream sentence (so there is nothing
        # left to mask, and the first run of this section failed demanding a marker
        # that correctly never appeared), while an unclassified error keeps the
        # upstream text and is exactly where the mask has to bite.
        $probes = @(
            @{ scenario = "refuse-then-secret-error"; class = "CODEX_AUTH_REQUIRED"; marker = $false;
               label = "a recognised refusal replaces the upstream text instead of serving it" },
            @{ scenario = "secret-error-no-refusal";  class = "CODEX_UNKNOWN";        marker = $true;
               label = "an unclassified upstream string is served masked" }
        )
        $index = 0
        foreach ($probe in $probes) {
            $index++
            $dirFake = Join-Path $work ("fakedata-$index")
            New-Item -ItemType Directory -Path $dirFake | Out-Null
            $fakePort = $Port + 6 + $index
            $env:AIUSAGE_FAKE_APPSERVER = $probe.scenario
            try {
                $null = Start-Bridge -DataDir $dirFake -Port $fakePort -Extra @("--codex", $fakeBin)
                $null = Wait-Health -Port $fakePort
                $rf = Get-Url "http://127.0.0.1:$fakePort/v1/accounts/codex/usage"
                $docf = $null
                try {
                    $docf = $rf.Body | ConvertFrom-Json
                } catch {
                    Note "the fake's body is not JSON: $($rf.Body)"
                }
                Check "§4 $($probe.scenario) reaches the caller as $($probe.class)" `
                    (($rf.Status -eq 503) -and ($docf.error -eq $probe.class)) `
                    "status=$($rf.Status) error=$($docf.error)"
                Assert-NoSecret "§1 $($probe.scenario) response" $rf.Body
                $marked = ([string]$rf.Body).Contains("[redacted]")
                Check "§1 $($probe.label)" ($marked -eq $probe.marker) "body=$($rf.Body)"
                $fakeState = Join-Path $dirFake "state.json"
                if (Test-Path $fakeState) {
                    $stored = [IO.File]::ReadAllText($fakeState, [System.Text.Encoding]::UTF8)
                    Assert-NoSecret "§1 $($probe.scenario) state file" $stored
                } else {
                    Check "§1 $($probe.scenario) still wrote a state file" $false "no state.json in $dirFake"
                }
            } finally {
                Stop-All
                Remove-Item "Env:AIUSAGE_FAKE_APPSERVER" -ErrorAction SilentlyContinue
            }
        }
    } else {
        Check "the fake app server could be built" $false "go test -c exit=$testBuildExit"
    }

    Note "== rule 18: a failure must not erase the last good numbers =="
    $seededState = Join-Path $dirMain "state.json"
    if (Test-Path $seededState) {
        Copy-Item $seededState (Join-Path $dirSeeded "state.json") -Force
        $seededPort = $Port + 3
        # --ttl 1s is what makes this a failure test rather than a cache test:
        # the seeded state is seconds old, so with the default five-minute TTL the
        # Bridge would answer from cache and never discover that Codex is missing.
        $null = Start-Bridge -DataDir $dirSeeded -Port $seededPort -Extra @("--codex", $bogus, "--ttl", "1s")
        $h4 = Wait-Health -Port $seededPort
        $r4 = Get-Url "http://127.0.0.1:$seededPort/v1/accounts/codex/usage"
        $doc4 = $null
        if ($r4.Status -eq 200) {
            $doc4 = $r4.Body | ConvertFrom-Json
        }
        Check "numbers survive a failed refresh" ($r4.Status -eq 200) "status=$($r4.Status) body=$($r4.Body)"
        if ($null -ne $doc4) {
            $kept = @($doc4.state.windows).Count
            Check "the windows are still there" ($kept -ge 2) "kept=$kept"
            Check "the failure is reported alongside them" ($doc4.degraded.class -eq "CODEX_NOT_FOUND") `
            "degraded=$($doc4.degraded | ConvertTo-Json -Compress) fromCache=$($doc4.fromCache)"
        }
        Stop-All
    } else {
        Check "rule 18 needs a state file to seed" $false "no state.json at $seededState"
    }

    Note "== B9: timestamps move when the cache expires =="
    $ttlDir = Join-Path $work "ttl"
    New-Item -ItemType Directory -Path $ttlDir | Out-Null
    $ttlPort = $Port + 4
    $null = Start-Bridge -DataDir $ttlDir -Port $ttlPort -Extra @("--ttl", "2s")
    $h5 = Wait-Health -Port $ttlPort
    $first = Get-ReadingWithRetry -Url "http://127.0.0.1:$ttlPort/v1/accounts/codex/usage"
    Start-Sleep -Seconds 4
    $second = Get-Url "http://127.0.0.1:$ttlPort/v1/accounts/codex/usage"
    if ($first.Status -eq 200 -and $second.Status -eq 200) {
        $d1 = $first.Body | ConvertFrom-Json
        $d2 = $second.Body | ConvertFrom-Json
        Check "B9 both reads are fresh, not cached" ($d1.fromCache -eq $false -and $d2.fromCache -eq $false) `
            "first=$($d1.fromCache) second=$($d2.fromCache)"
        $t1 = [DateTime]::Parse($d1.dataTimestamp).ToUniversalTime()
        $t2 = [DateTime]::Parse($d2.dataTimestamp).ToUniversalTime()
        Check "B9 dataTimestamp advanced with the new reading" ($t2 -gt $t1) "$t1 -> $t2"
    } elseif ($first.Status -eq 200) {
        # A reading existed, so the second one failing is the Bridge's problem: a
        # failed refetch over retained data answers 200 with a marker, never a 503.
        Check "B9 the second read succeeded" $false "second=$($second.Status) $((([string]$second.Body) -replace '\s+', ' ').Trim())"
    } else {
        Note "B9 not judged: the host's Codex produced no reading for a fresh data dir ($((([string]$first.Body) -replace '\s+', ' ').Trim()))"
    }
    Stop-All

    Note "== review §3 at process level: two force refreshes at once =="
    # Both must be answered, and the file left behind must still be a complete
    # reading. The erasing interleaving itself is proven deterministically in Go
    # (TestLateFailureCannotEraseAConcurrentSuccess); what a live run can show is
    # that serialising the transaction does not cost anyone an answer.
    $concDir = Join-Path $work "concurrent"
    New-Item -ItemType Directory -Path $concDir | Out-Null
    $concPort = $Port + 6
    $null = Start-Bridge -DataDir $concDir -Port $concPort
    $hc = Wait-Health -Port $concPort
    $seed = Get-Url "http://127.0.0.1:$concPort/v1/accounts/codex/usage"
    if ($seed.Status -eq 200) {
        $jobs = @()
        for ($i = 0; $i -lt 2; $i++) {
            $jobs += Start-Job -ArgumentList "http://127.0.0.1:$concPort/v1/accounts/codex/usage?refresh=1" -ScriptBlock {
                param($u)
                try {
                    $r = Invoke-WebRequest -UseBasicParsing -Uri $u -TimeoutSec 40
                    return @{ Status = [int]$r.StatusCode; Body = $r.Content }
                } catch {
                    $resp = $_.Exception.Response
                    if ($null -ne $resp) {
                        return @{ Status = [int]$resp.StatusCode; Body = "$($_.ErrorDetails.Message)" }
                    }
                    return @{ Status = 0; Body = $_.Exception.Message }
                }
            }
        }
        $null = $jobs | Wait-Job -Timeout 120
        $answers = @($jobs | ForEach-Object { Receive-Job $_ })
        $jobs | ForEach-Object { Remove-Job $_ -Force }
        $okCount = @($answers | Where-Object { $_.Status -eq 200 }).Count
        Check "§3 both simultaneous refreshes are answered" ($okCount -eq 2) `
            "statuses: $(($answers | ForEach-Object { $_.Status }) -join ',')"
        $concState = Join-Path $concDir "state.json"
        if (Test-Path $concState) {
            $after = [IO.File]::ReadAllText($concState, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
            Check "§3 the state file is still a complete reading" (@($after.windows).Count -ge 2) `
                "windows=$(@($after.windows).Count)"
        } else {
            Check "§3 a state file exists after concurrent refreshes" $false "no $concState"
        }
    } else {
        Note "§3 not judged: no reading to race over (status=$($seed.Status) body=$($seed.Body))"
    }
    Stop-All

    Note "== Spec §54 red line: the Bridge must not read Codex credentials =="
    $sourceHits = Get-ChildItem -Path $bridgeDir -Recurse -Filter "*.go" |
        Where-Object { $_.FullName -notmatch "\\bin\\" } |
        Select-String -Pattern "auth\.json"
    Check "no Go source under bridge/ mentions auth.json" ($null -eq $sourceHits) `
        "matches: $(if ($sourceHits) { ($sourceHits | ForEach-Object { $_.Path + ":" + $_.LineNumber }) -join ', ' } else { '' })"

    Note "B7 (method unavailable) stays unit-tested in bridge/internal/codex: producing it"
    Note "here would mean replacing a real Codex install. B8 (the reverse refresh request)"
    Note "is now exercised end to end against the fake app server above - a real child"
    Note "process and the production fetcher - because manufacturing a genuinely expired"
    Note "login here would mean logging this machine's real account out, which is not on."
} finally {
    Stop-All
    if (Test-Path $work) {
        Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
    }
}

Write-Output ""
Write-Output "== summary: $script:checks checks, $script:failures failed =="
if ($script:failures -gt 0) {
    exit 1
}
