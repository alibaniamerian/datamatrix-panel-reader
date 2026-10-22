param(
    [string]$Adb = (Join-Path $PSScriptRoot '..\tools\android\platform-tools\adb.exe'),
    [string]$Output = (Join-Path $PSScriptRoot '..\inputs\camera_captures'),
    [string]$DeviceSerial = ''
)
$ErrorActionPreference = 'Stop'
$taskRemotes = @('/sdcard/DCIM/QR_Reader', '/sdcard/Android/data/org.datamatrix.rawcapture/files/Pictures/captures')
$taskDeviceArgs = @()
if ($DeviceSerial) { $taskDeviceArgs = @('-s', $DeviceSerial) }
& $Adb @taskDeviceArgs get-state
if ($LASTEXITCODE -ne 0) { throw 'One authorized USB or Wi-Fi device is required; use -DeviceSerial if more than one transport is connected.' }
New-Item -ItemType Directory -Force -Path $Output | Out-Null
foreach ($taskRemote in $taskRemotes) {
$taskNames = @(& $Adb @taskDeviceArgs shell "if [ -d '$taskRemote' ]; then ls -1 '$taskRemote'; fi")
if ($LASTEXITCODE -ne 0) { throw "Cannot list $taskRemote" }
foreach ($taskName in $taskNames) {
    $taskName = $taskName.Trim()
    if ($taskName -notmatch '^(raw|yuv)_\d{8}_\d{6}_\d{3}(\.(dng|json|yuv)|_(preview|Y)\.png)$') { continue }
    $taskDestination = Join-Path $Output $taskName
    $taskRemoteFile = "$taskRemote/$taskName"
    $taskHashLine = (& $Adb @taskDeviceArgs shell sha256sum $taskRemoteFile) -join ''
    if ($LASTEXITCODE -ne 0 -or $taskHashLine -notmatch '^([0-9a-fA-F]{64})\s') { throw "Cannot hash $taskName" }
    $taskExpected = $Matches[1].ToLowerInvariant()
    if (Test-Path -LiteralPath $taskDestination) {
        if ((Get-FileHash -LiteralPath $taskDestination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskExpected) { throw "Existing local file differs; preserving it: $taskDestination" }
        Write-Output "Already verified: $taskName"
        continue
    }
    & $Adb @taskDeviceArgs pull $taskRemoteFile $taskDestination
    if ($LASTEXITCODE -ne 0) { throw "Transfer failed: $taskName" }
    if ((Get-FileHash -LiteralPath $taskDestination -Algorithm SHA256).Hash.ToLowerInvariant() -ne $taskExpected) { throw "Transfer checksum mismatch: $taskName" }
    Write-Output "Verified SHA256: $taskName"
}

}
