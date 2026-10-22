param([string]$Sdk=$env:ANDROID_HOME,[string]$Jdk=$env:JAVA_HOME,[string]$Gradle='')
$ErrorActionPreference='Stop'
$localConfig=Join-Path $PSScriptRoot '.local/build-config.json'
if(Test-Path -LiteralPath $localConfig){$cfg=Get-Content -LiteralPath $localConfig -Raw | ConvertFrom-Json;if(!$Sdk){$Sdk=$cfg.Sdk};if(!$Jdk){$Jdk=$cfg.Jdk}}
if(!$Sdk -or !(Test-Path -LiteralPath $Sdk)){throw 'Set ANDROID_HOME or pass -Sdk (Android SDK 36).'}
if(!$Jdk -or !(Test-Path -LiteralPath $Jdk)){throw 'Set JAVA_HOME or pass -Jdk (JDK 17).'}
if(!$Gradle){$bundled=Join-Path $PSScriptRoot '../tools/mobile-build/gradle/gradle-8.11.1/bin/gradle.bat';if(Test-Path -LiteralPath $bundled){$Gradle=$bundled}else{$command=Get-Command gradle -ErrorAction SilentlyContinue;if($command){$Gradle=$command.Source}}}
if(!$Gradle){throw 'Install Gradle 8.11.1 on PATH or pass -Gradle.'}
$env:JAVA_HOME=$Jdk
$env:GRADLE_USER_HOME=Join-Path $PSScriptRoot '.local/gradle-user-home'
$env:ANDROID_USER_HOME=Join-Path $PSScriptRoot '.local/android-user'
('sdk.dir='+$Sdk.Replace('\','/')) | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'local.properties') -Encoding ASCII
& $Gradle -p $PSScriptRoot assembleDebug --console=plain
if($LASTEXITCODE -ne 0){throw 'Android build failed'}
Write-Output (Join-Path $PSScriptRoot 'build/outputs/apk/debug/DataMatrixCapture-debug.apk')
