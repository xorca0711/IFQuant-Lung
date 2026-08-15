[CmdletBinding()]
param(
    [string]$QuPath = "X:\QuPath\QuPath-0.7.0 (console).exe",
    [string]$StudyConfig = "",
    [string]$StainProfile = "",
    [string]$OutputRoot = "",
    [double]$Downsample = 32,
    [double]$TissueOdThreshold = 0.18,
    [int]$MinTissueComponentPixels = 64,
    [int]$MaxHolePixels = 32
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($StudyConfig)) {
    $StudyConfig = Join-Path $repo "config\studies\g_surf_he_20260812.json"
}
if ([string]::IsNullOrWhiteSpace($StainProfile)) {
    $StainProfile = Join-Path $repo "config\brightfield\he_stain_profiles\g_surf_he_20260812_candidate.json"
}
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $stamp = Get-Date -Format "yyyyMMdd_HHmmss_fff"
    $OutputRoot = Join-Path "D:\IFQ_Runs\Pilot_analysis\H&E_20260812" ("03_r1_qc_candidate_{0}" -f $stamp)
}
$script = Join-Path $repo "brightfield\qupath_he_r1_qc_candidate.groovy"
$validator = Join-Path $repo "scripts\Test-HeR1QcOutput.ps1"

foreach ($required in @($QuPath, $StudyConfig, $StainProfile, $script, $validator)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required H&E R1 input is missing: $required"
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
    throw "H&E outputs must remain under D:\IFQ_Runs: $resolvedOutput"
}
$logs = Join-Path $resolvedOutput "logs"
$provenance = Join-Path $resolvedOutput "provenance"
$analysis = Join-Path $resolvedOutput "analysis"
New-Item -ItemType Directory -Path $logs,$provenance,$analysis | Out-Null
foreach ($file in @($StudyConfig,$StainProfile,$script,$validator)) {
    Copy-Item -LiteralPath $file -Destination $provenance
}

$parameters = [ordered]@{
    schema_version = "1.0.0"
    created_utc = [DateTime]::UtcNow.ToString("o")
    qupath = (Resolve-Path -LiteralPath $QuPath).Path
    qupath_sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $QuPath).Hash.ToLowerInvariant()
    study_config = (Resolve-Path -LiteralPath $StudyConfig).Path
    study_config_sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $StudyConfig).Hash.ToLowerInvariant()
    stain_profile = (Resolve-Path -LiteralPath $StainProfile).Path
    stain_profile_sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $StainProfile).Hash.ToLowerInvariant()
    script = (Resolve-Path -LiteralPath $script).Path
    script_sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $script).Hash.ToLowerInvariant()
    output_root = $resolvedOutput
    downsample = $Downsample
    tissue_od_sum_threshold = $TissueOdThreshold
    min_tissue_component_pixels = $MinTissueComponentPixels
    max_hole_pixels = $MaxHolePixels
}
$utf8 = New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText((Join-Path $provenance "run_parameters.json"), ($parameters | ConvertTo-Json -Depth 5), $utf8)

$env:IFQ_HE_STUDY_CONFIG = (Resolve-Path -LiteralPath $StudyConfig).Path
$env:IFQ_HE_STAIN_PROFILE = (Resolve-Path -LiteralPath $StainProfile).Path
$env:IFQ_HE_OUTPUT = $analysis
$env:IFQ_HE_DOWNSAMPLE = $Downsample.ToString([Globalization.CultureInfo]::InvariantCulture)
$env:IFQ_HE_TISSUE_OD_THRESHOLD = $TissueOdThreshold.ToString([Globalization.CultureInfo]::InvariantCulture)
$env:IFQ_HE_MIN_TISSUE_COMPONENT_PIXELS = $MinTissueComponentPixels.ToString([Globalization.CultureInfo]::InvariantCulture)
$env:IFQ_HE_MAX_HOLE_PIXELS = $MaxHolePixels.ToString([Globalization.CultureInfo]::InvariantCulture)

$stdout = Join-Path $logs "qupath_stdout.log"
$stderr = Join-Path $logs "qupath_stderr.log"
$start = New-Object Diagnostics.ProcessStartInfo
$start.FileName = $QuPath
$start.Arguments = 'script "' + $script + '"'
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.RedirectStandardOutput = $true
$start.RedirectStandardError = $true
$process = New-Object Diagnostics.Process
$process.StartInfo = $start
if (-not $process.Start()) { throw "QuPath process did not start." }
$stdoutTask = $process.StandardOutput.ReadToEndAsync()
$stderrTask = $process.StandardError.ReadToEndAsync()
$process.WaitForExit()
[IO.File]::WriteAllText($stdout, $stdoutTask.Result, $utf8)
[IO.File]::WriteAllText($stderr, $stderrTask.Result, $utf8)
if ($process.ExitCode -ne 0) {
    throw "QuPath H&E R1 candidate failed with exit code $($process.ExitCode). See $stderr"
}

& powershell -ExecutionPolicy Bypass -File $validator -OutputRoot $resolvedOutput
if ($LASTEXITCODE -ne 0) { throw "H&E R1 output validation failed." }
Write-Host "H&E R1 QC candidate complete: $resolvedOutput"
