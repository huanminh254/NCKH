param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][string]$RunId,
    [ValidateSet('citizen','rescue')][string]$Role='citizen',
    [string]$OutputRoot = (Join-Path $PSScriptRoot '..\docs\evidence\T4')
)
$ErrorActionPreference = 'Stop'
if ($RunId -notmatch '^[A-Za-z0-9_-]+$') { throw 'RunId accepts letters, numbers, underscore and hyphen.' }
if ($Serial -notmatch '^[A-Za-z0-9_.:-]+$') { throw 'Invalid device serial.' }
$destination=Join-Path $OutputRoot (Join-Path $RunId $Serial)
New-Item -ItemType Directory -Path $destination -Force | Out-Null
$package='vn.tlu.sosmesh.'+$Role
& adb '-s' $Serial 'get-state'
if ($LASTEXITCODE -ne 0) { throw 'Device is not ready.' }
& adb '-s' $Serial 'shell' 'getprop' | Set-Content -LiteralPath (Join-Path $destination 'getprop.txt') -Encoding utf8
& adb '-s' $Serial 'shell' 'dumpsys' 'battery' | Set-Content -LiteralPath (Join-Path $destination 'battery.txt') -Encoding utf8
& adb '-s' $Serial 'shell' 'dumpsys' 'batterystats' $package | Set-Content -LiteralPath (Join-Path $destination 'batterystats.txt') -Encoding utf8
& adb '-s' $Serial 'logcat' '-d' '-v' 'epoch' '-s' 'NearbyMeshTransport' 'CitizenLocation' 'AndroidRuntime' | Set-Content -LiteralPath (Join-Path $destination 'logcat.txt') -Encoding utf8
& adb '-s' $Serial 'pull' "/sdcard/Android/data/$package/files/thu-nghiem" (Join-Path $destination 'app-exports')
if ($LASTEXITCODE -ne 0) { Write-Warning 'No exported CSV was pulled. Use the app export action, then rerun.' }
$metadata=[ordered]@{run_id=$RunId;serial=$Serial;role=$Role;collected_at=(Get-Date).ToString('o');note='Add topology, APK hash, clock offset and operator to run manifest.'}
$metadata | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $destination 'collection.json') -Encoding utf8
Write-Output $destination
