[CmdletBinding()]
param(
    [string]$Configuration = "Release",
    [string]$OutputName = ""
)

# Builds IFQuantLauncher_QuPath_SlideScanner-vX.Y.Z.exe, embedding the exact
# QuPath_SlideScanner_Quant.groovy present at build time. Mirrors launcher/build.ps1.

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$source = Join-Path $PSScriptRoot "IFQuantLauncher_QuPath_SlideScanner.cs"
$manifest = Join-Path $PSScriptRoot "app.manifest"
$script = Join-Path $repo "QuPath_SlideScanner_Quant.groovy"

$sourceText = [System.IO.File]::ReadAllText($source)
$versionMatch = [regex]::Match(
    $sourceText,
    '\[assembly:\s*AssemblyFileVersion\("(?<version>\d+\.\d+\.\d+)\.\d+"\)\]')
if (-not $versionMatch.Success) {
    throw "Could not resolve the launcher version from the C# source."
}
$launcherVersion = $versionMatch.Groups["version"].Value
if ([string]::IsNullOrWhiteSpace($OutputName)) {
    $OutputName = "IFQuantLauncher_QuPath_SlideScanner-v$launcherVersion.exe"
}
if ([System.IO.Path]::GetFileName($OutputName) -ne $OutputName -or
    -not $OutputName.EndsWith(".exe", [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OutputName must be a simple .exe filename."
}
$output = Join-Path $repo $OutputName

$compilerCandidates = @(
    "$env:WINDIR\Microsoft.NET\Framework64\v4.0.30319\csc.exe",
    "$env:WINDIR\Microsoft.NET\Framework\v4.0.30319\csc.exe"
)
$compiler = $compilerCandidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
if (-not $compiler) {
    throw "The Windows .NET Framework C# compiler (csc.exe) was not found."
}

foreach ($required in @($source, $manifest, $script)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Required build input is missing: $required"
    }
}

$arguments = @(
    "/nologo",
    "/target:winexe",
    "/platform:anycpu",
    "/optimize+",
    "/win32manifest:$manifest",
    "/out:$output",
    "/reference:System.dll",
    "/reference:System.Core.dll",
    "/reference:System.Drawing.dll",
    "/reference:System.Windows.Forms.dll",
    "/resource:$script,IFQuant.QuPath_SlideScanner_Quant.groovy",
    $source
)

& $compiler @arguments
if ($LASTEXITCODE -ne 0) {
    throw "C# compilation failed with exit code $LASTEXITCODE."
}

$hash = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
$hashFileName = [System.IO.Path]::GetFileNameWithoutExtension($OutputName) + ".sha256.txt"
[System.IO.File]::WriteAllText(
    (Join-Path $repo $hashFileName),
    "$hash  $OutputName`r`n",
    [System.Text.UTF8Encoding]::new($false))

# Embedded-runtime self-test (exit 0 = pass).
& $output --self-test
$selfTest = $LASTEXITCODE

Write-Host "Built: $output"
Write-Host "SHA256: $hash"
Write-Host "Self-test exit code: $selfTest (0 = pass)"
if ($selfTest -ne 0) { throw "Self-test failed with exit code $selfTest." }
