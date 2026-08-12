[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot

function Read-JsonFile {
    param([Parameter(Mandatory = $true)][string]$Path)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Required H&E configuration is missing: $Path"
    }
    return Get-Content -LiteralPath $Path -Raw | ConvertFrom-Json
}

function Assert-True {
    param([bool]$Condition, [string]$Message)
    if (-not $Condition) { throw $Message }
}

$hierarchy = Read-JsonFile (Join-Path $repo "config\brightfield\he_decision_hierarchy.json")
$endpoints = Read-JsonFile (Join-Path $repo "config\brightfield\he_endpoints.json")
$study = Read-JsonFile (Join-Path $repo "config\studies\g_surf_he_20260812.json")

$expectedStages = 0..9 | ForEach-Object { "H$_" }
$observedStages = @($hierarchy.stages | ForEach-Object { $_.id })
Assert-True (($observedStages -join ",") -eq ($expectedStages -join ",")) `
    "H&E stages must be ordered exactly H0 through H9."
Assert-True ($hierarchy.biological_unit -eq "mouse") `
    "The H&E biological unit must remain mouse."
Assert-True ($hierarchy.status -ne "VALIDATED") `
    "The proposed H&E hierarchy must not be labelled VALIDATED before execution."

$samples = @($study.samples)
$sectionCount = ($samples | ForEach-Object { @($_.section_ids).Count } | Measure-Object -Sum).Sum
$mouseIds = @($samples | ForEach-Object { $_.biological_unit_id } | Sort-Object -Unique)
Assert-True ($samples.Count -eq 4 -and $mouseIds.Count -eq 4) `
    "The current study contract must contain four unique mice."
Assert-True ($sectionCount -eq 8 -and $study.expected_analytical_sections -eq 8) `
    "The current study contract must contain eight technical sections."
Assert-True ((@($study.analytical_series.allow_names) -join ",") -eq "20x_BF_01,20x_BF_02") `
    "The analytical series allowlist has drifted."
Assert-True ($study.analytical_series.series_index_by_name.'20x_BF_01' -eq 2) `
    "20x_BF_01 must resolve to verified VSI series index 2."
Assert-True ($study.analytical_series.series_index_by_name.'20x_BF_02' -eq 3) `
    "20x_BF_02 must resolve to verified VSI series index 3."

Assert-True ($endpoints.aggregation.biological_unit -eq "mouse") `
    "H&E endpoint aggregation must remain mouse-level."
Assert-True ($endpoints.aggregation.fraction_rule -eq "sum_numerators_divided_by_sum_denominators") `
    "H&E fractions must pool raw numerators and denominators."
$deferred = @($endpoints.endpoint_tiers.tier_3_deferred | ForEach-Object { $_.id })
Assert-True ($deferred -contains "immune_lineage_from_he") `
    "H&E immune-lineage inference must remain explicitly deferred."
Assert-True ($deferred -contains "alveolar_number_or_volume") `
    "Invalid 2D inference of alveolar number/volume must remain deferred."

Write-Host "H&E configuration: passed."
Write-Host ("  stages:   {0}" -f $observedStages.Count)
Write-Host ("  mice:     {0}" -f $mouseIds.Count)
Write-Host ("  sections: {0}" -f $sectionCount)
Write-Host "  VSI series: BF_01=2, BF_02=3"
Write-Host ("  tier 1:   {0}" -f @($endpoints.endpoint_tiers.tier_1_quantitative).Count)
Write-Host ("  tier 2:   {0}" -f @($endpoints.endpoint_tiers.tier_2_blinded_ordinal).Count)
Write-Host ("  deferred: {0}" -f $deferred.Count)
