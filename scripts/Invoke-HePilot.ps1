[CmdletBinding()]
param(
    [string]$QuPath = "X:\QuPath\QuPath-0.7.0 (console).exe",
    [string]$StudyConfig = "",
    [string]$OutputRoot = "",
    [double]$Downsample = 64,
    [double]$TissueOdThreshold = 0.18
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($StudyConfig)) {
    $StudyConfig = Join-Path $repo "config\studies\g_surf_he_20260812.json"
}
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $runStamp = Get-Date -Format "yyyyMMdd_HHmmss_fff"
    $OutputRoot = Join-Path "D:\IFQ_Runs\he_20260812" ("pilot_{0}" -f $runStamp)
}
$script = Join-Path $repo "brightfield\qupath_he_exploratory_pilot.groovy"

foreach ($required in @($QuPath, $StudyConfig, $script)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required H&E pilot input is missing: $required"
    }
}
if (Test-Path -LiteralPath $OutputRoot) {
    if ((Get-ChildItem -LiteralPath $OutputRoot -Force | Measure-Object).Count -gt 0) {
        throw "OutputRoot must not already contain files: $OutputRoot"
    }
} else {
    New-Item -ItemType Directory -Path $OutputRoot | Out-Null
}

$resolvedOutput = (Resolve-Path -LiteralPath $OutputRoot).Path
if (-not $resolvedOutput.StartsWith("D:\IFQ_Runs\", [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "H&E pilot outputs must remain under D:\IFQ_Runs: $resolvedOutput"
}

$logs = Join-Path $resolvedOutput "logs"
$provenance = Join-Path $resolvedOutput "provenance"
New-Item -ItemType Directory -Path $logs,$provenance -Force | Out-Null
Copy-Item -LiteralPath $StudyConfig -Destination (Join-Path $provenance "g_surf_he_20260812.json")
Copy-Item -LiteralPath (Join-Path $repo "config\brightfield\he_decision_hierarchy.json") -Destination $provenance
Copy-Item -LiteralPath (Join-Path $repo "config\brightfield\he_endpoints.json") -Destination $provenance
Copy-Item -LiteralPath $script -Destination $provenance

$runParameters = [ordered]@{
    schema_version = "1.0.0"
    created_utc = [DateTime]::UtcNow.ToString("o")
    qupath = (Resolve-Path -LiteralPath $QuPath).Path
    qupath_sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $QuPath).Hash.ToLowerInvariant()
    study_config = (Resolve-Path -LiteralPath $StudyConfig).Path
    study_config_sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $StudyConfig).Hash.ToLowerInvariant()
    pilot_script = (Resolve-Path -LiteralPath $script).Path
    pilot_script_sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $script).Hash.ToLowerInvariant()
    output_root = $resolvedOutput
    downsample = $Downsample
    tissue_od_sum_threshold = $TissueOdThreshold
}
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText(
    (Join-Path $provenance "run_parameters.json"),
    ($runParameters | ConvertTo-Json -Depth 4),
    $utf8NoBom
)

# The Groovy script requires an empty analytical output. Provenance and logs are
# siblings so the run can be audited without violating that guard.
$analysis = Join-Path $resolvedOutput "analysis"
New-Item -ItemType Directory -Path $analysis | Out-Null
$env:IFQ_HE_STUDY_CONFIG = (Resolve-Path -LiteralPath $StudyConfig).Path
$env:IFQ_HE_OUTPUT = $analysis
$env:IFQ_HE_DOWNSAMPLE = $Downsample.ToString([Globalization.CultureInfo]::InvariantCulture)
$env:IFQ_HE_TISSUE_OD_THRESHOLD = $TissueOdThreshold.ToString([Globalization.CultureInfo]::InvariantCulture)

$stdout = Join-Path $logs "qupath_stdout.log"
$stderr = Join-Path $logs "qupath_stderr.log"
$start = New-Object System.Diagnostics.ProcessStartInfo
$start.FileName = $QuPath
$start.Arguments = 'script "' + $script + '"'
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.RedirectStandardOutput = $true
$start.RedirectStandardError = $true
$process = New-Object System.Diagnostics.Process
$process.StartInfo = $start
if (-not $process.Start()) { throw "QuPath process did not start." }
$stdoutTask = $process.StandardOutput.ReadToEndAsync()
$stderrTask = $process.StandardError.ReadToEndAsync()
$process.WaitForExit()
$stdoutText = $stdoutTask.Result
$stderrText = $stderrTask.Result
[System.IO.File]::WriteAllText($stdout, $stdoutText, $utf8NoBom)
[System.IO.File]::WriteAllText($stderr, $stderrText, $utf8NoBom)
if ($process.ExitCode -ne 0) {
    throw "QuPath H&E pilot failed with exit code $($process.ExitCode). See $stderr"
}
if (-not (Test-Path -LiteralPath (Join-Path $analysis "he_run_manifest.json"))) {
    throw "QuPath exited 0 but wrote no H&E run manifest."
}

Write-Host "H&E exploratory pilot complete: $resolvedOutput"
Write-Host "Manifest: $(Join-Path $analysis 'he_run_manifest.json')"
Write-Host "Section table: $(Join-Path $analysis 'tables\he_section_qc.csv')"
Write-Host "Review overlays: $(Join-Path $analysis 'qc_overlays')"
