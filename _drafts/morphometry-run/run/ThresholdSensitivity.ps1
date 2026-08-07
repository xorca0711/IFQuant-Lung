# Threshold sensitivity required by PREREGISTERED_RULES.md R2.
# The het PAIR only (m4-1 infected / m4-2 control -- the repo's internal control
# pair, matched AGER staining intensity), at ds 4 with block stride 4, so the
# whole sweep is a few minutes. A damaged-vs-intact contrast that exists only at
# the locked threshold is not a finding.
param([double[]]$Thresholds = @(440, 660, 880, 1320, 1760), [int]$Stride = 4)
$ErrorActionPreference = "Stop"
$SP  = Split-Path -Parent $PSScriptRoot
$DIR = "D:\Confocal_Images\20260806_CW\20260806_CW"

# a folder containing only the het pair, so IFQ_MORPH_INPUT can point at it
$pair = Join-Path $SP "run\_hetpair"
if (-not (Test-Path $pair)) { New-Item -ItemType Directory $pair | Out-Null }
foreach ($n in @("IFNg KO(het) 26.03.25 m4-1 pr8 infection",
                 "IFNg KO(het) 26.03.25 m4-2 pr8 no infection")) {
  $link = Join-Path $pair "$n.vsi"
  if (-not (Test-Path $link)) {
    # .vsi needs its sibling _<name>_ data folder; a directory junction is not
    # possible for the file, so copy the small .vsi and junction the data dir.
    Copy-Item (Join-Path $DIR "$n.vsi") $link
    $src = Join-Path $DIR "_${n}_"
    $dst = Join-Path $pair "_${n}_"
    if (-not (Test-Path $dst)) { cmd /c mklink /J "`"$dst`"" "`"$src`"" | Out-Null }
  }
}

foreach ($t in $Thresholds) {
  $out = Join-Path $SP ("out_thr" + [int]$t)
  Write-Host "=== threshold $t -> $out ===" -ForegroundColor Cyan
  & "$SP\run\Invoke-Morphometry.ps1" -InputPath $pair -Output $out `
      -TissueThreshold $t -DsFine "4" -BlockStride $Stride -SkipSelfTest |
    Select-String -Pattern "IFQ_MORPH" | Select-Object -Last 2
}
