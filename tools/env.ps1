# Android build environment for this project.
#
# Dot-source it before any gradle / adb / emulator command:
#     . .\tools\env.ps1
#
# Existing environment variables take precedence over the legacy D:\Android
# defaults. local.properties (gitignored) can also hold the SDK path for Gradle.

$ErrorActionPreference = "Stop"

if (-not $env:ANDROID_HOME -and $env:ANDROID_SDK_ROOT) {
    $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
}
$AndroidRoot = if ($env:ANDROID_HOME) { Split-Path -Parent $env:ANDROID_HOME } else { "D:\Android" }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = Join-Path $AndroidRoot "jdk-17" }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $AndroidRoot "sdk" }
if (-not $env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME }
if (-not $env:ANDROID_AVD_HOME) {
    $legacyAvd = Join-Path $AndroidRoot ".android\avd"
    if (Test-Path $legacyAvd) { $env:ANDROID_AVD_HOME = $legacyAvd }
}
if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $AndroidRoot ".gradle" }

# JDK first so `java` resolves for Gradle's launcher and for adb tooling.
$jdkBin = Join-Path $env:JAVA_HOME "bin"
if ((Test-Path $jdkBin) -and ($env:PATH -notlike "*$jdkBin*")) {
    $env:PATH = "$jdkBin;$env:PATH"
}

foreach ($dir in @("platform-tools", "emulator", "cmdline-tools\latest\bin")) {
    $full = Join-Path $env:ANDROID_HOME $dir
    if ((Test-Path $full) -and ($env:PATH -notlike "*$full*")) {
        $env:PATH = "$full;$env:PATH"
    }
}

if (-not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    throw "JDK not found at $env:JAVA_HOME - edit tools\env.ps1"
}
if (-not (Test-Path (Join-Path $env:ANDROID_HOME "platforms"))) {
    throw "Android SDK not found at $env:ANDROID_HOME - edit tools\env.ps1"
}

Write-Host "env ready: JAVA_HOME=$env:JAVA_HOME  ANDROID_HOME=$env:ANDROID_HOME" -ForegroundColor DarkGray
