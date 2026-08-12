[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputRoot
)

$ErrorActionPreference = "Stop"

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

$resolved = (Resolve-Path -LiteralPath $OutputRoot).Path
Assert-True ($resolved.StartsWith("D:\IFQ_Runs\", [System.StringComparison]::OrdinalIgnoreCase)) `
    "Pilot validation is restricted to D:\IFQ_Runs outputs."

$analysis = Join-Path $resolved "analysis"
$manifestPath = Join-Path $analysis "he_run_manifest.json"
$tablePath = Join-Path $analysis "tables\he_section_qc.csv"
$provenanceConfig = Join-Path $resolved "provenance\g_surf_he_20260812.json"
Assert-True (Test-Path -LiteralPath $manifestPath -PathType Leaf) "Missing H&E manifest."
Assert-True (Test-Path -LiteralPath $tablePath -PathType Leaf) "Missing H&E section table."
Assert-True (Test-Path -LiteralPath $provenanceConfig -PathType Leaf) "Missing frozen study configuration."

$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$rows = @(Import-Csv -LiteralPath $tablePath)
Assert-True ($manifest.status -eq "COMPLETE_REVIEW_REQUIRED") `
    "Pilot must remain review-gated."
Assert-True ($manifest.run_classification -eq "EXPLORATORY_ENGINEERING_ONLY") `
    "Pilot must not be labelled as biological output."
Assert-True ($manifest.section_count -eq 8 -and $rows.Count -eq 8) `
    "Pilot output must contain exactly eight declared sections."
Assert-True ((Get-FileHash -Algorithm SHA256 -LiteralPath $provenanceConfig).Hash.ToLowerInvariant() -eq $manifest.study_config_sha256) `
    "Frozen study configuration does not match the manifest hash."
Assert-True (-not (Test-Path -LiteralPath (Join-Path $analysis "he_mouse_summary.csv"))) `
    "Exploratory pilot must not emit a mouse-level biological summary."

$expectedIds = @("M2_BF_01", "M2_BF_02", "M4-1_BF_01", "M4-1_BF_02", "M4-2_BF_01", "M4-2_BF_02", "M6_BF_01", "M6_BF_02")
$observedIds = @($rows | ForEach-Object { $_.section_id } | Sort-Object)
Assert-True (($observedIds -join ",") -eq (($expectedIds | Sort-Object) -join ",")) `
    "Section identities do not match the frozen study contract."

foreach ($sectionId in $expectedIds) {
    Assert-True (Test-Path -LiteralPath (Join-Path $analysis "previews\${sectionId}__raw.png") -PathType Leaf) `
        "Missing raw preview for $sectionId."
    Assert-True (Test-Path -LiteralPath (Join-Path $analysis "qc_overlays\${sectionId}__H3_candidates.png") -PathType Leaf) `
        "Missing H3 review overlay for $sectionId."
}

Write-Host "H&E pilot output: passed."
Write-Host "  root:          $resolved"
Write-Host "  sections:      $($rows.Count)"
Write-Host "  previews:      8"
Write-Host "  review overlays: 8"
Write-Host "  classification: $($manifest.run_classification)"
