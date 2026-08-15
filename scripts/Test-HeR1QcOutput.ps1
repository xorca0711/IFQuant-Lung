[CmdletBinding()]
param([Parameter(Mandatory = $true)][string]$OutputRoot)

$ErrorActionPreference = "Stop"
function Assert-True { param([bool]$Condition,[string]$Message); if (-not $Condition) { throw $Message } }

$analysis = Join-Path $OutputRoot "analysis"
$manifestPath = Join-Path $analysis "he_run_manifest.json"
Assert-True (Test-Path -LiteralPath $manifestPath -PathType Leaf) "Missing R1 manifest."
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
Assert-True ($manifest.module -eq "brightfield_he_r1_qc_candidate") "Unexpected R1 module."
Assert-True ($manifest.status -eq "COMPLETE_REVIEW_REQUIRED") "R1 candidate must require review."
Assert-True ($manifest.run_classification -eq "R1_CANDIDATE_NOT_REPORTABLE") "R1 candidate must remain non-reportable."
Assert-True ($manifest.release_level -eq "R0" -and $manifest.requested_release -eq "R1") "R1 must not self-promote before review."
Assert-True ($manifest.mouse_count -eq 4 -and $manifest.section_count -eq 8) "Expected four mice and eight sections."
Assert-True ([bool]$manifest.denominator_reconciliation_passed) "Denominator reconciliation failed."
Assert-True (@($manifest.unavailable_artifact_detectors).Count -gt 0) "Remaining artifact limitations must be explicit."

$inventory = Import-Csv -LiteralPath (Join-Path $analysis "tables\he_input_inventory.csv")
$sections = Import-Csv -LiteralPath (Join-Path $analysis "tables\he_section_qc.csv")
$queue = Import-Csv -LiteralPath (Join-Path $analysis "tables\he_review_queue.csv")
Assert-True ($inventory.Count -eq 8 -and $sections.Count -eq 8) "Inventory/QC table must each contain eight sections."
Assert-True ($queue.Count -eq 24) "Review queue must contain three artifact reviews per section."
Assert-True (($sections.section_id | Sort-Object -Unique).Count -eq 8) "Section identities are not unique."
foreach ($row in $sections) {
    $detected = [int64]$row.detected_tissue_pixels
    $excluded = [int64]$row.automatic_excluded_pixels
    $usable = [int64]$row.provisional_usable_tissue_pixels
    Assert-True ($detected -eq ($excluded + $usable)) "Denominator mismatch for $($row.section_id)."
    Assert-True ($row.review_status -eq "REVIEW_REQUIRED") "Section review state must remain pending."
    foreach ($relative in @(
        "previews\$($row.raw_preview)", "stain_separation\$($row.stain_separation)",
        "masks\$($row.tissue_mask)", "masks\$($row.artifact_mask)", "qc_overlays\$($row.qc_overlay)")) {
        Assert-True (Test-Path -LiteralPath (Join-Path $analysis $relative) -PathType Leaf) "Missing output: $relative"
    }
}
Assert-True (($queue.decision | Where-Object { $_ -ne "PENDING" }).Count -eq 0) "New review queue must be pending."
Write-Host "H&E R1 QC candidate output: passed."
Write-Host "  mice: 4"
Write-Host "  sections: 8"
Write-Host "  review items: 24"
Write-Host "  release: R0 (R1 requested; review required)"
