param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = "$env:LOCALAPPDATA\Android\Sdk",
    [string]$GradleExecutable = '',
    [switch]$DeviceTests
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $JavaHome) {
    $candidate = Join-Path $env:USERPROFILE '.jdks\jbr-21.0.11'
    if (Test-Path -LiteralPath (Join-Path $candidate 'bin\java.exe')) { $JavaHome = $candidate }
}
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw 'Set -JavaHome to a JDK 17 or 21 installation.'
}
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
if (-not $GradleExecutable) { $GradleExecutable = Join-Path $projectRoot 'gradlew.bat' }
Push-Location $projectRoot
try {
    & $GradleExecutable ':core-map:testDebugUnitTest' ':citizen-app:assembleDebug' ':rescue-app:assembleDebug' '--console=plain'
    if ($LASTEXITCODE -ne 0) { throw "Build or unit tests failed ($LASTEXITCODE)." }
    if ($DeviceTests) {
        & $GradleExecutable ':core-map:connectedDebugAndroidTest' '--console=plain'
        if ($LASTEXITCODE -ne 0) { throw "Device tests failed ($LASTEXITCODE)." }
    }
} finally { Pop-Location }
