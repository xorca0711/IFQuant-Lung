[CmdletBinding()]
param(
    [string]$Configuration = "Release",
    [string]$OutputName = ""
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$source = Join-Path $PSScriptRoot "IFQuantLauncher.cs"
$manifest = Join-Path $PSScriptRoot "app.manifest"
$pipeline = Join-Path $repo "IF_Quant_Pipeline.groovy"
$registry = Join-Path $repo "config\lung_marker_registry.json"
$sourceText = [System.IO.File]::ReadAllText($source)
$versionMatch = [regex]::Match(
    $sourceText,
    '\[assembly:\s*AssemblyFileVersion\("(?<version>\d+\.\d+\.\d+)\.\d+"\)\]'
)
if (-not $versionMatch.Success) {
    throw "Could not resolve the launcher version from IFQuantLauncher.cs."
}
$launcherVersion = $versionMatch.Groups["version"].Value
if ([string]::IsNullOrWhiteSpace($OutputName)) {
    $OutputName = "IFQuantLauncher-v$launcherVersion.exe"
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
    throw "The Windows .NET Framework C# compiler was not found."
}

foreach ($required in @($source, $manifest, $pipeline, $registry)) {
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
    "/reference:System.Web.Extensions.dll",
    "/resource:$pipeline,IFQuant.IF_Quant_Pipeline.groovy",
    "/resource:$registry,IFQuant.lung_marker_registry.json",
    $source
)

& $compiler @arguments
if ($LASTEXITCODE -ne 0) {
    throw "C# compilation failed with exit code $LASTEXITCODE."
}

$hash = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
$hashRecord = "$hash  $OutputName`r`n"
$hashFileName = [System.IO.Path]::GetFileNameWithoutExtension($OutputName) + ".sha256.txt"
[System.IO.File]::WriteAllText(
    (Join-Path $repo $hashFileName),
    $hashRecord,
    [System.Text.UTF8Encoding]::new($false)
)

Write-Host "Built: $output"
Write-Host "SHA256: $hash"
