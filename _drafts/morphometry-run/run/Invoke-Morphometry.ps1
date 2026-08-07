<#
.SYNOPSIS
  Morphometry module runner. Locks the analysis parameters in ONE place.

.DESCRIPTION
  Pipeline position -- morphometry is a SIDE BRANCH, not a stage:

    .vsi --+-- qupath_wsi_tile_export.groovy (Stage 1) -> IF_Quant_Pipeline (Stage 2)
           |      -> aggregate_tiles_to_slide.py (Stage 3) --+
           |                                                 |
           +-- lung_morphometry.groovy ---------------------+--> aggregate_to_mouse.py
                                                                 (the SAME script,
                                                                  run once per branch)

  Both branches feed the SAME aggregate_to_mouse.py. Neither modifies it.
  morphometry_derive.py then forms the ratios from the pooled totals.

  Compartments (damaged / intact) are carried in `panel` as "<PANEL>@<scope>".
  They CANNOT be carried in `region`: aggregate_to_mouse.py pools across regions
  inside a mouse and the contrast would be silently added away. Proved in
  test_aggregation_contract.py T4.

.PARAMETER LockedFrom
  IFQ_MORPH_CHANNELS = 0        DAPI only. See PREREGISTERED_RULES.md R1.
  IFQ_MORPH_TISSUE_THRESHOLD    control-derived, R2.
  IFQ_MORPH_DS_FINE             locked by the resolution sweep, R3.
  IFQ_WSI_AGER_THRESHOLD/SIGMA/CUTOFF   the LOCKED damage detector, unchanged.
#>
[CmdletBinding()]
param(
  [string]$InputPath = "D:\Confocal_Images\20260806_CW\20260806_CW",
  [Parameter(Mandatory = $true)][string]$Output,
  [Parameter(Mandatory = $true)][double]$TissueThreshold,
  [string]$DsFine = "2",
  [string]$Channels = "0",
  [int]$CoreFullRes = 4096,
  [int]$HaloFullRes = 512,
  [int]$Threads = 5,
  [int]$MaxBlocks = 0,
  [int]$BlockStride = 1,
  [double]$ConsolidationCutoff = -1,
  [switch]$SkipSelfTest
)
$ErrorActionPreference = "Stop"
$QP = "X:\QuPath\QuPath-0.7.0 (console).exe"
$SP = Split-Path -Parent $PSScriptRoot
$SCRIPT = Join-Path $SP "lung_morphometry.groovy"
if (-not (Test-Path $QP))     { throw "QuPath not found: $QP" }
if (-not (Test-Path $SCRIPT)) { throw "Script not found: $SCRIPT" }

# --- SELF-TEST FIRST, ALWAYS. -----------------------------------------------
# Synthetic phantoms with analytically known MLI, airspace fraction, wall
# thickness and perimeter, plus an exact block-additivity check. Costs seconds
# and is the difference between "the numbers are wrong" and "the numbers are
# wrong and nobody knew".
if (-not $SkipSelfTest) {
  Write-Host "[IFQ_MORPH] self-test ..." -ForegroundColor Cyan
  $env:IFQ_MORPH_SELFTEST = "true"
  & $QP script $SCRIPT | Select-String -Pattern "SELF-TEST:|FAIL"
  if ($LASTEXITCODE -ne 0) { throw "SELF-TEST FAILED (exit $LASTEXITCODE). Refusing to measure anything." }
  Remove-Item Env:\IFQ_MORPH_SELFTEST
}

$env:IFQ_MORPH_SELFTEST            = "false"
$env:IFQ_MORPH_CALIBRATE           = "false"
$env:IFQ_MORPH_SWEEP               = "false"
$env:IFQ_MORPH_INPUT               = $InputPath
$env:IFQ_MORPH_OUTPUT              = $Output
$env:IFQ_MORPH_CHANNELS            = $Channels
$env:IFQ_MORPH_TISSUE_THRESHOLD    = "$TissueThreshold"
$env:IFQ_MORPH_DS_FINE             = $DsFine
$env:IFQ_MORPH_DS_COARSE           = "8"
$env:IFQ_MORPH_CORE_FULLRES_PX     = "$CoreFullRes"
$env:IFQ_MORPH_HALO_FULLRES_PX     = "$HaloFullRes"
$env:IFQ_MORPH_THREADS             = "$Threads"
$env:IFQ_MORPH_MAX_BLOCKS          = "$MaxBlocks"
$env:IFQ_MORPH_BLOCK_STRIDE        = "$BlockStride"
$env:IFQ_MORPH_CONSOLIDATION_CUTOFF = "$ConsolidationCutoff"
# the LOCKED damage detector -- do not change without redoing the calibration
$env:IFQ_WSI_AGER_THRESHOLD        = "150"
$env:IFQ_WSI_DAMAGE_SIGMA_UM       = "40"
$env:IFQ_WSI_DAMAGE_CUTOFF         = "0.14"
$env:IFQ_MORPH_COMPARTMENT_ERODE_UM = "40"

Write-Host "[IFQ_MORPH] ds_fine=$DsFine channels=$Channels thr=$TissueThreshold -> $Output" -ForegroundColor Cyan
# the D: volume has dropped out twice this session; retry rather than lose the run
for ($try = 1; $try -le 3; $try++) {
  & $QP script $SCRIPT
  if ($LASTEXITCODE -eq 0) { break }
  Write-Host "[IFQ_MORPH] retry $try after exit $LASTEXITCODE" -ForegroundColor Yellow
  Start-Sleep -Seconds 20
}
if ($LASTEXITCODE -ne 0) { throw "morphometry run failed (exit $LASTEXITCODE)" }

Write-Host ""
Write-Host "NEXT -- against the UNMODIFIED aggregate_to_mouse.py:" -ForegroundColor Green
Get-ChildItem (Join-Path $Output "morphometry_slide_summary_ds*.csv") | ForEach-Object {
  $tag = ($_.BaseName -replace '^morphometry_slide_summary', '')
  Write-Host "  python aggregate_to_mouse.py `"$($_.FullName)`" --outdir `"$Output\stats$tag`""
  Write-Host "  python morphometry_derive.py `"$Output\stats$tag\mouse_level_summary.csv`" --tag $tag"
}
