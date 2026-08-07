# Calibration: CONTROL slides only. The infected slides are not opened.
$ErrorActionPreference = "Stop"
$QP  = "X:\QuPath\QuPath-0.7.0 (console).exe"
$SP  = "C:\Users\dream\AppData\Local\Temp\claude\X--QuPath\7933abe5-e14c-44b2-aa07-c4127fa41a9e\scratchpad\build2\morphometry"
$DIR = "D:\Confocal_Images\20260806_CW\20260806_CW"

$controls = @(
  "IFNg KO(het) 26.03.25 m4-2 pr8 no infection.vsi",
  "IFNg KO(hom) 26.03.25 m6 pr8 no infection.vsi"
)

$env:IFQ_MORPH_CALIBRATE = "true"
$env:IFQ_MORPH_CHANNELS  = "0"
$env:IFQ_MORPH_DS_FINE   = "1,2,4"
$env:IFQ_MORPH_OUTPUT    = ""
Remove-Item Env:\IFQ_MORPH_TISSUE_THRESHOLD -ErrorAction SilentlyContinue

foreach ($c in $controls) {
  $env:IFQ_MORPH_INPUT = Join-Path $DIR $c
  Write-Host "=== CALIBRATE $c ==="
  # retry: the D: volume has dropped out twice this session
  for ($try = 1; $try -le 3; $try++) {
    & $QP script "$SP\lung_morphometry.groovy" | Select-String -Pattern "IFQ_MORPH"
    if ($LASTEXITCODE -eq 0) { break }
    Write-Host "  retry $try after exit $LASTEXITCODE"
    Start-Sleep -Seconds 10
  }
}
