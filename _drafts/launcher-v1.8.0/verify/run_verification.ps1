<#
    run_verification.ps1 -- reproduce every check for the v1.8.0 launcher.

    Nothing here writes to the repository. It compiles the shipping sources
    into a scratch folder and runs four things:

      1. build.ps1 in a staging mirror of the repo   -> the launcher builds
      2. IFQuantLauncher-v1.8.0.exe --self-test      -> packaged invariants
      3. IFQuantLauncher-v1.8.0.exe --ui-smoke       -> the window, and R3
      4. LegacyEquivalence.exe                       -> route 4 == v1.7.2
      5. GateMatrix.exe                              -> one row per H rule

    IMPORTANT: the launcher is /target:winexe. PowerShell does NOT wait for a
    GUI-subsystem process invoked as `& $exe`, and $LASTEXITCODE then holds a
    stale value -- a self-test called that way reports success without having
    run. Everything below uses Start-Process -Wait -PassThru.

    Usage:
      .\run_verification.ps1 -Repo C:\Users\...\IFQuant-Lung -Work <scratch dir>
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Repo,
    [Parameter(Mandatory = $true)][string]$Work,
    [string]$Sources = ""
)

$ErrorActionPreference = "Stop"
if ([string]::IsNullOrWhiteSpace($Sources)) {
    $Sources = Join-Path (Split-Path -Parent $PSScriptRoot) "launcher"
}
$verifyDir = $PSScriptRoot

$compilerCandidates = @(
    "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe",
    "$env:WINDIR\Microsoft.NET\FrameworkArm64\v4.0.30319\csc.exe",
    "$env:WINDIR\Microsoft.NET\Framework\v4.0.30319\csc.exe"
)
$csc = $compilerCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $csc) { throw "No .NET Framework C# compiler found." }
Write-Host "compiler: $csc"

$staging = Join-Path $Work "staging"
$build = Join-Path $Work "build"
New-Item -ItemType Directory -Force (Join-Path $staging "config") | Out-Null
New-Item -ItemType Directory -Force (Join-Path $staging "launcher") | Out-Null
New-Item -ItemType Directory -Force $build | Out-Null

foreach ($name in @("IF_Quant_Pipeline.groovy", "qupath_wsi_tile_export.groovy",
                    "aggregate_tiles_to_slide.py")) {
    Copy-Item (Join-Path $Repo $name) (Join-Path $staging $name) -Force
}
Copy-Item (Join-Path $Repo "config\lung_marker_registry.json") `
          (Join-Path $staging "config\lung_marker_registry.json") -Force
foreach ($name in @("IFQuantLauncher.cs", "IFQuantLauncher.Routing.cs",
                    "MainForm.Routes.partial.cs", "app.manifest", "build.ps1")) {
    Copy-Item (Join-Path $Sources $name) (Join-Path $staging "launcher\$name") -Force
}

$results = New-Object System.Collections.Generic.List[string]
function Record { param([string]$Name, [int]$Code)
    $results.Add(("{0,-34} exit {1}  {2}" -f $Name, $Code, $(if ($Code -eq 0) { "PASS" } else { "FAIL" })))
}

Write-Host ""
Write-Host "=== 1-3. build.ps1 (compiles, then runs --self-test and --ui-smoke) ==="
& (Join-Path $staging "launcher\build.ps1")
Record "build.ps1 + self-test + ui-smoke" $(if ($?) { 0 } else { 1 })
$exe = Join-Path $staging "IFQuantLauncher-v1.8.0.exe"

Write-Host ""
Write-Host "=== re-running the two packaged checks explicitly ==="
$selfTest = (Start-Process -FilePath $exe -ArgumentList "--self-test" -Wait -PassThru).ExitCode
Record "--self-test" $selfTest
$uiSmoke = (Start-Process -FilePath $exe -ArgumentList "--ui-smoke" -Wait -PassThru).ExitCode
Record "--ui-smoke" $uiSmoke

Write-Host ""
Write-Host "=== 4. route 4 legacy equivalence, executed ==="
# 0162 (unreachable code) is NOT suppressed. It used to be, because
# LauncherBuild.BrightfieldRouteEnabled was a `const bool` and every branch
# guarded on it folded away; it is a static readonly field now, so real
# unreachable code must be allowed to surface here. 0649 stays: the harness
# declares fixture fields it never assigns.
& $csc /nologo /nowarn:0649 /target:exe "/out:$build\EnvProbe.exe" /reference:System.dll `
      (Join-Path $verifyDir "EnvProbe.cs")
& $csc /nologo /nowarn:0649 /target:exe "/out:$build\LegacyEquivalence.exe" `
      /reference:System.dll /reference:System.Core.dll `
      (Join-Path $Sources "IFQuantLauncher.Routing.cs") `
      (Join-Path $verifyDir "LegacyEquivalence.cs")
& "$build\LegacyEquivalence.exe" `
      (Join-Path $Repo "launcher\IFQuantLauncher.cs") `
      (Join-Path $Sources "IFQuantLauncher.cs") `
      "$build\EnvProbe.exe" `
      (Join-Path $Repo "IF_Quant_Pipeline.groovy") `
      (Join-Path $Repo "config\lung_marker_registry.json") `
      (Join-Path $Sources "IFQuantLauncher.Routing.cs")
Record "LegacyEquivalence" $LASTEXITCODE

Write-Host ""
Write-Host "=== 5. fail-closed rule matrix ==="
& $csc /nologo /nowarn:0649 /target:exe "/out:$build\GateMatrix.exe" `
      /reference:System.dll /reference:System.Core.dll `
      (Join-Path $Sources "IFQuantLauncher.Routing.cs") `
      (Join-Path $verifyDir "GateMatrix.cs")
& "$build\GateMatrix.exe" (Join-Path $Repo "IF_Quant_Pipeline.groovy") `
      (Join-Path $Repo "config\lung_marker_registry.json")
Record "GateMatrix" $LASTEXITCODE

Write-Host ""
Write-Host "================ SUMMARY ================"
$results | ForEach-Object { Write-Host $_ }
if ($results | Where-Object { $_ -match "FAIL" }) {
    throw "At least one verification step failed."
}
Write-Host "All verification steps passed."
