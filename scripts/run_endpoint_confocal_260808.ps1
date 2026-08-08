# Relational endpoint for the 260808 confocal batch (whole fields, no RoiSet).
#
# Two stages, both OUTSIDE the frozen engine:
#   1. export_tissue_region_masks.groovy re-derives the auto_dapi tissue region
#      per field and PROVES it against run_summary.region_area_um2.
#   2. evaluate_endpoints.groovy runs with IFQ_ENDPOINT_REGION_MODE=tissue_mask,
#      which clips the mask algebra to those regions.
#
# Nothing is written to D:\Confocal_Images (read only) or to <run>\analysis
# (the measured result). Masks land in <run>\tissue_masks.

$ErrorActionPreference = "Continue"
$repo = "X:\GitHub\IFQuant-Lung"
$data = "D:\Confocal_Images\260808-CW\260808-CW"
$out  = "D:\IFQ_Runs\confocal_260808"
$fj   = "X:\Fiji"
$jre  = (Get-ChildItem "$fj\java" -Recurse -Filter java.exe | Select-Object -First 1).FullName

function Invoke-FijiScript($scriptPath, $logPath) {
  & $jre '--add-opens=java.base/java.lang=ALL-UNNAMED' `
    "-javaagent:$fj\jars\ij1-patcher-2.0.0.jar=init" `
    '-Djava.awt.headless=true' "-Dplugins.dir=$fj" '-Xmx4g' `
    -cp "$fj\jars\*;$fj\plugins\*" net.imagej.Main --headless `
    --run $scriptPath 2>&1 | Out-File $logPath -Encoding utf8
  return $LASTEXITCODE
}

# ---- stage 1: tissue region masks ------------------------------------------
$env:IFQ_ANALYSIS_DIR     = "$out\analysis"
$env:IFQ_SOURCE_DIR       = $data
$env:IFQ_TISSUE_MASK_DIR  = "$out\tissue_masks"
$env:IFQ_PANEL_FILTER     = "LEFT"          # the endpoint is a LEFT-panel relation
$env:IFQ_TISSUE_RECON_TOL = "0.001"
$env:IFQ_MAX_FIELDS       = ""

$rc1 = Invoke-FijiScript "$repo\endpoints\export_tissue_region_masks.groovy" "$out\tissue_export.log"
"stage1 exit=$rc1"
if ($rc1 -ne 0) { "stage 1 FAILED - see $out\tissue_export.log"; exit 1 }

# ---- stage 2: relational endpoint ------------------------------------------
$env:IFQ_ENDPOINT_SPEC        = "$repo\config\endpoints\ectopic_pod_over_damaged.json"
$env:IFQ_ANALYSIS_DIR         = "$out\analysis"
$env:IFQ_ENDPOINT_REGION_MODE = "tissue_mask"
$env:IFQ_TISSUE_MASK_DIR      = "$out\tissue_masks"
$env:IFQ_ENDPOINT_OUT         = "$out\endpoint_areas.csv"
$env:IFQ_TILES_DIR            = ""          # not used in tissue_mask mode

$rc2 = Invoke-FijiScript "$repo\endpoints\evaluate_endpoints.groovy" "$out\endpoint.log"
"stage2 exit=$rc2"
