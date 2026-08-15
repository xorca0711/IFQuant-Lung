[CmdletBinding()]
param(
    [string]$QuPath = "X:\QuPath\QuPath-0.7.0 (console).exe",
    [string]$StudyConfig = "",
    [string]$StainProfile = "",
    [string]$OutputRoot = "",
    [double]$Downsample = 64,
    [double]$TissueOdThreshold = 0.18,
    [int]$MinTissueComponentPixels = 16
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$runner = Join-Path $repo "scripts\Invoke-HeR1QcCandidate.ps1"
$renderer = Join-Path $repo "scripts\render_he_r1_qc_overlays.py"
$renderValidator = Join-Path $repo "scripts\Test-HeR1RenderedOutput.ps1"
$arguments = @{
    QuPath = $QuPath
    Downsample = $Downsample
    TissueOdThreshold = $TissueOdThreshold
    MinTissueComponentPixels = $MinTissueComponentPixels
    MaxHolePixels = 0
}
if (-not [string]::IsNullOrWhiteSpace($StudyConfig)) { $arguments.StudyConfig = $StudyConfig }
if (-not [string]::IsNullOrWhiteSpace($StainProfile)) { $arguments.StainProfile = $StainProfile }
if (-not [string]::IsNullOrWhiteSpace($OutputRoot)) { $arguments.OutputRoot = $OutputRoot }
& $runner @arguments
if ($LASTEXITCODE -ne 0) { throw "Base H&E R1 runner failed." }

if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    throw "Canonical R1 execution requires an explicit OutputRoot for overlay finalization."
}
python $renderer --output-root $OutputRoot
if ($LASTEXITCODE -ne 0) { throw "H&E R1 opaque-overlay rendering failed." }
& powershell -ExecutionPolicy Bypass -File $renderValidator -OutputRoot $OutputRoot
if ($LASTEXITCODE -ne 0) { throw "H&E R1 opaque-overlay validation failed." }
Write-Host "Canonical H&E R1 QC candidate complete: $OutputRoot"
