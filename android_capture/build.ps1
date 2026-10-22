param([string]$Sdk,[string]$Jdk,[string]$Gradle)
& (Join-Path $PSScriptRoot 'build-live.ps1') @PSBoundParameters
if($LASTEXITCODE -ne 0){throw 'Android build failed'}
