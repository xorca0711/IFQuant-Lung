[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$OutputRoot)

$ErrorActionPreference = "Stop"
function Assert-True { param([bool]$Condition,[string]$Message); if (-not $Condition) { throw $Message } }

$analysis = Join-Path $OutputRoot "analysis"
$manifest = Get-Content -LiteralPath (Join-Path $analysis "he_run_manifest.json") -Raw | ConvertFrom-Json
$renderPath = Join-Path $analysis "overlay_render_manifest.json"
Assert-True (Test-Path -LiteralPath $renderPath -PathType Leaf) "Missing opaque-overlay render manifest."
$render = Get-Content -LiteralPath $renderPath -Raw | ConvertFrom-Json
Assert-True ($manifest.overlay_rendering.method -eq "precomposited_opaque_v1") "Manifest does not authorize the fixed overlay renderer."
Assert-True ([bool]$manifest.overlay_rendering.morphology_visible) "Overlay morphology visibility was not asserted."
Assert-True (@($render.sections).Count -eq 8) "Expected eight rendered overlay records."
Add-Type -AssemblyName System.Drawing
foreach ($entry in @($render.sections)) {
    $path = Join-Path $analysis ($entry.path -replace '/', '\')
    Assert-True (Test-Path -LiteralPath $path -PathType Leaf) "Missing rendered overlay: $path"
    Assert-True ((Get-FileHash -Algorithm SHA256 -LiteralPath $path).Hash.ToLowerInvariant() -eq $entry.sha256) "Overlay hash mismatch: $path"
    $image = [Drawing.Image]::FromFile($path)
    try { Assert-True ($image.PixelFormat.ToString() -notmatch 'Alpha') "Overlay is not opaque: $path" }
    finally { $image.Dispose() }
}
Write-Host "H&E R1 opaque overlay output: passed (8/8)."
