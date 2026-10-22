param([string]$Python='')
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
if(!$Python){$venv=Join-Path $root '.venv/Scripts/python.exe';if(Test-Path -LiteralPath $venv){$Python=$venv}else{$Python='python'}}
Push-Location $root
try{
 $tests=@('panel_registration/test_registration.py','panel_registration/test_body_outline.py','panel_registration/test_panel_touch_crop.py','panel_registration/test_raw_panel.py','android_capture/tests/test_core_localization.py','android_capture/tests/test_touch_roi.py')
 foreach($test in $tests){& $Python $test;if($LASTEXITCODE -ne 0){throw "Test failed: $test"}}
}finally{Pop-Location}
