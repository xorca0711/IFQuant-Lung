<#
.SYNOPSIS
    Run STAGE 2 (IF_Quant_Pipeline.groovy) over a Stage 1 tile folder in N
    parallel shards.

.DESCRIPTION
    Stage 2 costs roughly 2-3 minutes per tile. A whole slide is ~370 tiles, so
    a single-threaded run is 12-15 hours. The Fiji engine loops over files with
    a per-file try/catch and never carries state between images, so tiles can be
    split across independent processes safely.

    Sharding uses NTFS HARD LINKS, so no image data is copied and no extra disk
    is used. Each shard gets its own tiles folder containing links to its tiles
    AND to their companion "<stem>.ome_RoiSet.zip" files, plus its own
    samplesheet.csv carved from the Stage 1 one.

    Stage 3 (aggregate_tiles_to_slide.py) globs for every run_summary.csv under
    the slide folder, so sharded output needs no further bookkeeping.

    THIS SCRIPT DOES NOT SET THRESHOLDS. Pass calibrated values, or the engine
    falls back to per-tile adaptive Otsu, which on a mostly-background tile
    reports KRT5_pod_area_frac ~0.89. See docs/WSI_TILING_WORKFLOW.md section 7.

.EXAMPLE
    .\scripts\Invoke-Stage2Sharded.ps1 `
        -TilesDir   "D:\wsi_stage1\slideA\tiles" `
        -OutputRoot "D:\wsi_stage1\slideA" `
        -Shards 5 `
        -Krt5Threshold 400 -AgerThreshold 600 -T1aThreshold 400
#>
param(
    [Parameter(Mandatory = $true)][string]$TilesDir,
    [Parameter(Mandatory = $true)][string]$OutputRoot,
    [int]$Shards = 4,
    [string]$FijiDir = "X:\Fiji",
    [string]$ScriptPath = (Join-Path (Split-Path $PSScriptRoot -Parent) 'IF_Quant_Pipeline.groovy'),
    [string]$Panel = "LEFT",
    [string]$Segmenter = "classic",
    [string]$Krt5Threshold = "",
    [string]$AgerThreshold = "",
    [string]$T1aThreshold = "",
    [string]$JavaXmx = "8g"
)

$ErrorActionPreference = 'Stop'

$TilesDir   = (Resolve-Path $TilesDir).Path
$ScriptPath = (Resolve-Path $ScriptPath).Path
if (-not (Test-Path $OutputRoot)) { New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null }
$OutputRoot = (Resolve-Path $OutputRoot).Path

$samplesheet = Join-Path $TilesDir 'samplesheet.csv'
if (-not (Test-Path $samplesheet)) {
    throw "No samplesheet.csv in $TilesDir. Stage 1 writes it; do not run Stage 2 without it (mouse_id would become 'NA')."
}

# Hard links only work within one volume.
if ((Split-Path $TilesDir -Qualifier) -ne (Split-Path $OutputRoot -Qualifier)) {
    throw "TilesDir ($TilesDir) and OutputRoot ($OutputRoot) must be on the same volume for hard links."
}

if (-not $Krt5Threshold -or -not $AgerThreshold -or -not $T1aThreshold) {
    Write-Warning ("No fixed thresholds supplied for one or more markers. The engine will use " +
                   "per-tile adaptive Otsu and every call will be exploratory. On background-dominated " +
                   "tiles this manufactures large false pod areas. Supply calibrated thresholds " +
                   "before any confirmatory run.")
}

$rows = Import-Csv $samplesheet
if ($rows.Count -eq 0) { throw "samplesheet.csv has no rows." }
Write-Host "Tiles in samplesheet : $($rows.Count)"

$java = Get-ChildItem (Join-Path $FijiDir 'java') -Recurse -Filter java.exe -ErrorAction SilentlyContinue |
        Select-Object -First 1
if (-not $java) { throw "No java.exe found under $FijiDir\java" }
$patcher = Get-ChildItem (Join-Path $FijiDir 'jars') -Filter 'ij1-patcher-*.jar' -ErrorAction SilentlyContinue |
           Select-Object -First 1
if (-not $patcher) { throw "No ij1-patcher-*.jar found in $FijiDir\jars (required for headless ImageJ1)" }

if ($Shards -lt 1) { $Shards = 1 }
if ($Shards -gt $rows.Count) { $Shards = $rows.Count }

# ---- build shards -------------------------------------------------------
$shardDirs = @()
for ($i = 0; $i -lt $Shards; $i++) {
    $tag       = 'shard_{0:d2}' -f ($i + 1)
    $shardIn   = Join-Path $OutputRoot "$tag\tiles"
    $shardOut  = Join-Path $OutputRoot "analysis_$tag"
    if (Test-Path $shardIn)  { Remove-Item $shardIn  -Recurse -Force }
    if (Test-Path $shardOut) { Remove-Item $shardOut -Recurse -Force }
    New-Item -ItemType Directory -Path $shardIn -Force | Out-Null

    $mine = @()
    for ($k = $i; $k -lt $rows.Count; $k += $Shards) { $mine += $rows[$k] }

    foreach ($row in $mine) {
        $tif = Join-Path $TilesDir $row.filename
        if (-not (Test-Path $tif)) { throw "Tile listed in samplesheet.csv is missing: $tif" }
        New-Item -ItemType HardLink -Path (Join-Path $shardIn $row.filename) -Target $tif | Out-Null

        # The engine strips only the FINAL extension: foo.ome.tif -> foo.ome_RoiSet.zip
        $stem = [System.IO.Path]::GetFileNameWithoutExtension($row.filename)
        $roi  = Join-Path $TilesDir ($stem + '_RoiSet.zip')
        if (-not (Test-Path $roi)) {
            throw ("Missing companion ROI for $($row.filename): expected $roi . Without it the engine " +
                   "falls back to auto tissue detection or to the whole halo-inclusive frame, " +
                   "double-counting every seam.")
        }
        New-Item -ItemType HardLink -Path (Join-Path $shardIn ($stem + '_RoiSet.zip')) -Target $roi | Out-Null
    }
    $mine | Export-Csv (Join-Path $shardIn 'samplesheet.csv') -NoTypeInformation -Encoding UTF8
    Write-Host ("  {0}: {1} tiles -> {2}" -f $tag, $mine.Count, $shardOut)
    $shardDirs += [pscustomobject]@{ Tag = $tag; In = $shardIn; Out = $shardOut; N = $mine.Count }
}

# ---- launch -------------------------------------------------------------
$cp = (Join-Path $FijiDir 'jars\*') + ';' + (Join-Path $FijiDir 'plugins\*')
$procs = @()
foreach ($s in $shardDirs) {
    $envPairs = @{
        IFQ_INPUT_DIR          = $s.In
        IFQ_OUTPUT_DIR         = $s.Out
        IFQ_PANEL              = $Panel
        IFQ_SEGMENTER          = $Segmenter
        IFQ_MIN_INCLUDED_NUCLEI = '0'
    }
    if ($Krt5Threshold) { $envPairs['IFQ_KRT5_THRESHOLD'] = $Krt5Threshold }
    if ($AgerThreshold) { $envPairs['IFQ_AGER_THRESHOLD'] = $AgerThreshold }
    if ($T1aThreshold)  { $envPairs['IFQ_T1A_THRESHOLD']  = $T1aThreshold }

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $java.FullName
    foreach ($a in @(
        '--add-opens=java.base/java.lang=ALL-UNNAMED',
        ("-javaagent:" + $patcher.FullName + "=init"),
        '-Djava.awt.headless=true',
        ("-Dplugins.dir=" + $FijiDir),
        ("-Xmx" + $JavaXmx),
        '-cp', $cp,
        'net.imagej.Main', '--headless', '--run', $ScriptPath)) {
        $psi.ArgumentList.Add($a)
    }
    foreach ($k in $envPairs.Keys) { $psi.EnvironmentVariables[$k] = $envPairs[$k] }
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError  = $true

    $logPath = Join-Path $OutputRoot ("stage2_" + $s.Tag + ".log")
    $p = [System.Diagnostics.Process]::Start($psi)
    # Drain both pipes asynchronously or a full buffer deadlocks the child.
    $p.add_OutputDataReceived({ param($sender, $e) if ($e.Data) { Add-Content -Path $logPath -Value $e.Data } })
    $p.add_ErrorDataReceived( { param($sender, $e) if ($e.Data) { Add-Content -Path $logPath -Value $e.Data } })
    $p.BeginOutputReadLine(); $p.BeginErrorReadLine()
    Write-Host ("  launched {0} (pid {1}) -> {2}" -f $s.Tag, $p.Id, $logPath)
    $procs += [pscustomobject]@{ Shard = $s; Proc = $p; Log = $logPath }
}

Write-Host ""
Write-Host "Running $($procs.Count) shard(s). This is the long step."
foreach ($x in $procs) { $x.Proc.WaitForExit() }

Write-Host ""
$bad = 0
foreach ($x in $procs) {
    $summary = Join-Path $x.Shard.Out 'run_summary.csv'
    $n = 0
    if (Test-Path $summary) { $n = [Math]::Max(0, (Import-Csv $summary).Count) }
    # exit code 1 means "at least one image failed", NOT "no results" -- outputs
    # are written before the terminal failRun. Parse run_manifest.json instead.
    $status = 'unknown'
    $manifest = Join-Path $x.Shard.Out 'run_manifest.json'
    if (Test-Path $manifest) {
        try { $status = (Get-Content $manifest -Raw | ConvertFrom-Json).status } catch { $status = 'unparseable' }
    }
    Write-Host ("  {0}: exit={1} status={2} rows={3}/{4}  log={5}" -f `
        $x.Shard.Tag, $x.Proc.ExitCode, $status, $n, $x.Shard.N, $x.Log)
    if ($n -ne $x.Shard.N) { $bad++ }
}

Write-Host ""
if ($bad -gt 0) {
    Write-Warning ("$bad shard(s) produced fewer rows than tiles. Stage 3 will refuse to emit a " +
                   "slide summary until this is resolved -- a slide with missing tiles still " +
                   "produces a plausible number.")
} else {
    Write-Host "All shards produced one row per tile."
}
Write-Host "Next: python aggregate_tiles_to_slide.py --slide-root <stage1 output root>"
