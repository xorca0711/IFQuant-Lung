<#
.SYNOPSIS
  Module A (morphometry) runner. Locks the analysis parameters in ONE place so a
  run is reproducible and so nobody re-picks a threshold per slide.

.DESCRIPTION
  Pipeline position -- morphometry is a SIDE BRANCH, not a stage:

    .vsi --+-- qupath_wsi_tile_export.groovy (Stage 1) -- IF_Quant_Pipeline (Stage 2)
           |     -- aggregate_tiles_to_slide.py (Stage 3) --+
           |                                                |
           +-- qupath_lung_morphometry.groovy   ------------+--> aggregate_to_mouse.py
                                                                 (the SAME script,
                                                                  run once per branch)

  Both branches feed the SAME aggregate_to_mouse.py. Neither modifies it.
  morphometry_derive.py then forms the ratios from the pooled totals, and can
  column-join the two mouse-level tables so there is one row per mouse.

.PARAMETER LockedFrom
  Where each locked number came from. Change nothing here without redoing the
  calibration that produced it and saying so in the commit message.

    IFQ_MORPH_DS_FINE = 2          -> 0.690 um/px on this scanner.
        MEASURED on control het m4-2, 1.41 mm window, fixed threshold:
        vs native 0.345 um/px, ds2 shifts airspace fraction +0.1%, MLI +1.8%,
        Crofton perimeter -1.6%, septal thickness +0.2%. ds4 is +6%/-5%; ds8 is
        +24%/-18%; ds16 (the Stage 1 mask resolution) is +105%/-50%.
        So ds2 buys native accuracy at a quarter of the pixels, and the Stage 1
        tissue mask is unusable for morphometry.

    IFQ_MORPH_DS_COARSE = 8        -> 2.760 um/px, the same downsample
        scripts/measure_damage_locked.groovy uses for the AGER damage detector,
        so the architecture map and the damage map are built on one grid.

    IFQ_MORPH_TISSUE_THRESHOLD     PROVISIONAL. Otsu inside the parenchyma ROI
        was 700.5 (het m4-2) and 785.9 (hom m6) on max(DAPI, T1alpha) at
        2.76 um/px. 700 is used below as a placeholder. It has NOT been locked
        by an alpha-controlled procedure the way the AGER threshold was.

    IFQ_MORPH_CONSOLIDATION_CUTOFF = 0.30
        Control-derived, alpha = 1% false positive on uninfected lung, exactly
        how the AGER cutoff 0.14 was derived. Local airspace fraction
        (sigma 40 um) p1 inside control parenchyma: 0.3123 (het m4-2) and
        0.2966 (hom m6); the lower is taken. The infected slides were not
        opened by this calibration.

    IFQ_MORPH_CHANNELS             NO DEFAULT, on purpose. There is no tissue
        counterstain in a marker panel, so "which channels mean tissue" is a
        scientific decision. For panel LEFT (DAPI/KRT5-488/AGER-555/T1a-647):
          "0"    DAPI only  -- the only choice fully independent of every
                              marker, but it measures NUCLEATED territory, not
                              septum.
          "0,3"  DAPI+T1alpha -- traces the alveolar lining, but T1alpha/PDPN
                              is an AT1 marker like AGER, so the "independent"
                              check on the AGER denominator is only independent
                              by channel, not by biology. Say so in the methods.
        Never include the AGER channel (2) or the KRT5 numerator channel (1).

.EXAMPLE
  .\Invoke-Morphometry.ps1 -Input "D:\Confocal_Images\20260806_CW\20260806_CW" `
                           -Output "D:\morphometry_out" -SelfTest
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$InputPath,
  [Parameter(Mandatory = $true)][string]$Output,
  [string]$QuPath = "X:\QuPath\QuPath-0.7.0 (console).exe",
  [string]$ScriptPath = "$PSScriptRoot\qupath_lung_morphometry.groovy",
  [string]$Channels = "0,3",
  [double]$TissueThreshold = 700,
  [double]$ConsolidationCutoff = 0.30,
  [double]$DsFine = 2,
  [double]$DsCoarse = 8,
  [int]$MaxBlocks = 0,
  [switch]$CompareAger,
  [switch]$Partition,
  [switch]$SelfTest,
  [switch]$Calibrate,
  [switch]$Sweep
)

$ErrorActionPreference = "Stop"
if (-not (Test-Path $QuPath))     { throw "QuPath not found: $QuPath" }
if (-not (Test-Path $ScriptPath)) { throw "Script not found: $ScriptPath" }

# --- SELF-TEST FIRST, ALWAYS. -----------------------------------------------
# The measurement code is validated against synthetic phantoms with analytically
# known MLI, airspace fraction, septal thickness and perimeter, plus an exact
# block-additivity check. It costs a few seconds and it is the difference
# between "the numbers are wrong" and "the numbers are wrong and nobody knew".
Write-Host "[IFQ_MORPH] running self-test ..." -ForegroundColor Cyan
$env:IFQ_MORPH_SELFTEST = "true"
& $QuPath script $ScriptPath
if ($LASTEXITCODE -ne 0) { throw "SELF-TEST FAILED (exit $LASTEXITCODE). Refusing to measure anything." }
Remove-Item Env:\IFQ_MORPH_SELFTEST
if ($SelfTest) { Write-Host "[IFQ_MORPH] self-test only; done."; return }

$env:IFQ_MORPH_INPUT                 = $InputPath
$env:IFQ_MORPH_OUTPUT                = $Output
$env:IFQ_MORPH_CHANNELS              = $Channels
$env:IFQ_MORPH_TISSUE_THRESHOLD      = "$TissueThreshold"
$env:IFQ_MORPH_CONSOLIDATION_CUTOFF  = "$ConsolidationCutoff"
$env:IFQ_MORPH_DS_FINE               = "$DsFine"
$env:IFQ_MORPH_DS_COARSE             = "$DsCoarse"
$env:IFQ_MORPH_MAX_BLOCKS            = "$MaxBlocks"
$env:IFQ_MORPH_COMPARE_AGER          = ($CompareAger.IsPresent).ToString().ToLower()
$env:IFQ_MORPH_PARTITION_DAMAGE      = ($Partition.IsPresent).ToString().ToLower()
if ($Calibrate) { $env:IFQ_MORPH_CALIBRATE = "true" }
if ($Sweep)     { $env:IFQ_MORPH_SWEEP     = "true" }
# Stamp the expected fine pixel size so class_morph_pxfine_ok_count actually
# checks something. Without it the check is a no-op.
$env:IFQ_MORPH_EXPECT_PXFINE_UM      = "$([math]::Round(0.3449973537 * $DsFine, 7))"

Write-Host "[IFQ_MORPH] fine=$([math]::Round(0.345*$DsFine,3)) um/px  coarse=$([math]::Round(0.345*$DsCoarse,3)) um/px  channels=$Channels  thr=$TissueThreshold" -ForegroundColor Cyan
& $QuPath script $ScriptPath
if ($LASTEXITCODE -ne 0) { throw "morphometry run failed (exit $LASTEXITCODE)" }
if ($Calibrate -or $Sweep) { return }

$csv = Join-Path $Output "morphometry_slide_summary.csv"
if (-not (Test-Path $csv)) { throw "expected $csv" }

Write-Host ""
Write-Host "NEXT -- against the UNMODIFIED aggregate_to_mouse.py:" -ForegroundColor Green
Write-Host "  python aggregate_to_mouse.py `"$csv`" --outdir `"$Output\stats`""
Write-Host "  python morphometry_derive.py `"$Output\stats\mouse_level_summary.csv`" ``"
Write-Host "         --px-fine-um $([math]::Round(0.3449973537 * $DsFine, 4)) ``"
Write-Host "         --join-marker-mouse-level <the marker mouse_level_summary.csv>"
Write-Host ""
Write-Host "n = MICE. Every ratio is formed by morphometry_derive.py from POOLED" -ForegroundColor Yellow
Write-Host "numerators and denominators; none is ever summed or averaged." -ForegroundColor Yellow
