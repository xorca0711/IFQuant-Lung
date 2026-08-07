# Resolution sweep: every measure at ds 1,2,4,8,16 on the SAME windows, at a
# FIXED tissue threshold, so resolution is the only thing that varies.
param([double]$Threshold = 300)
$ErrorActionPreference = "Stop"
$QP  = "X:\QuPath\QuPath-0.7.0 (console).exe"
$SP  = "C:\Users\dream\AppData\Local\Temp\claude\X--QuPath\7933abe5-e14c-44b2-aa07-c4127fa41a9e\scratchpad\build2\morphometry"
$DIR = "D:\Confocal_Images\20260806_CW\20260806_CW"

$slides = @(
  "IFNg KO(het) 26.03.25 m4-1 pr8 infection.vsi",
  "IFNg KO(het) 26.03.25 m4-2 pr8 no infection.vsi"
)

$env:IFQ_MORPH_SWEEP = "true"
$env:IFQ_MORPH_CALIBRATE = "false"
$env:IFQ_MORPH_CHANNELS = "0"
$env:IFQ_MORPH_TISSUE_THRESHOLD = "$Threshold"
$env:IFQ_MORPH_OUTPUT = ""
$env:IFQ_MORPH_CORE_FULLRES_PX = "4096"

foreach ($s in $slides) {
  $env:IFQ_MORPH_INPUT = Join-Path $DIR $s
  Write-Host "=== SWEEP $s  (fixed tissue threshold $Threshold) ==="
  for ($try = 1; $try -le 3; $try++) {
    & $QP script "$SP\lung_morphometry.groovy" | Select-String -Pattern "IFQ_MORPH"
    if ($LASTEXITCODE -eq 0) { break }
    Write-Host "  retry $try after exit $LASTEXITCODE"
    Start-Sleep -Seconds 10
  }
}
