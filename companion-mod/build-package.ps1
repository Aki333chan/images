param(
    [Parameter(Mandatory = $true)][string]$GameManagedDirectory,
    [Parameter(Mandatory = $true)][string]$OutputDirectory,
    [string]$Dotnet = 'dotnet',
    [string]$HarmonyAssembly = ''
)
$ErrorActionPreference = 'Stop'
$managed = (Resolve-Path -LiteralPath $GameManagedDirectory).Path
if (-not $HarmonyAssembly) { $HarmonyAssembly = Join-Path $managed '../../Mods/0_TFP_Harmony/0Harmony.dll' }
$harmony = (Resolve-Path -LiteralPath $HarmonyAssembly).Path
$required = @('Assembly-CSharp.dll', 'Assembly-CSharp-firstpass.dll', 'UnityEngine.CoreModule.dll', 'LogLibrary.dll')
foreach ($file in $required) {
    if (-not (Test-Path -LiteralPath (Join-Path $managed $file) -PathType Leaf)) {
        throw "Missing game reference: $file"
    }
}
$gameProject = Join-Path $PSScriptRoot 'src/Aurum.Companion.Game'
[xml]$props = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'Directory.Build.props') -Raw
$version = [string]$props.Project.PropertyGroup.Version
if ($version -notmatch '^\d+\.\d+\.\d+(-[A-Za-z0-9.-]+)?$') { throw 'Invalid package version' }
[xml]$modInfo = Get-Content -LiteralPath (Join-Path $gameProject 'ModInfo.xml') -Raw
# The game uses System.Version, not semver; prerelease suffix belongs in ZIP/assembly metadata.
$null = [version]$modInfo.ModInfo.Version.value
foreach ($project in @('src/Aurum.Companion.Core', 'tests/Aurum.Companion.Game.StubCheck')) {
    & $Dotnet build (Join-Path $PSScriptRoot $project) -c Release --nologo
    if ($LASTEXITCODE -ne 0) { throw "Build failed: $project" }
}
& $Dotnet test (Join-Path $PSScriptRoot 'tests/Aurum.Companion.Core.Tests') -c Release --nologo
if ($LASTEXITCODE -ne 0) { throw 'Core tests failed' }
& $Dotnet build (Join-Path $gameProject 'Aurum.Companion.Game.csproj') -c Release "-p:GameManagedDirectory=$managed" "-p:HarmonyAssembly=$harmony" --nologo
if ($LASTEXITCODE -ne 0) { throw 'Real game reference build failed' }

$output = [IO.Path]::GetFullPath($OutputDirectory)
$null = New-Item -ItemType Directory -Path $output -Force
$zip = Join-Path $output "AurumCompanion-7DTD-v$version.zip"
if (Test-Path -LiteralPath $zip) { throw "Refusing to overwrite $zip" }
$staging = Join-Path $output ('.7dtd-package-' + [guid]::NewGuid().ToString('N'))
$modFolder = Join-Path $staging 'AurumCompanion'
$null = New-Item -ItemType Directory -Path $modFolder
# Whitelist only: never ship game DLLs, stub builds, tokens, or server data.
foreach ($file in @('Aurum.Companion.dll', 'Aurum.Companion.Core.dll', 'ModInfo.xml', 'companion.cfg.example')) {
    Copy-Item -LiteralPath (Join-Path $gameProject "bin/Release/net48/$file") -Destination $modFolder
}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'SMOKE-TEST.ru.md') -Destination (Join-Path $modFolder 'SMOKE-TEST.ru.md')
$configFolder = Join-Path $modFolder 'Config'
$null = New-Item -ItemType Directory -Path $configFolder
Copy-Item -LiteralPath (Join-Path $gameProject 'Config/buffs.xml') -Destination $configFolder
Compress-Archive -LiteralPath $modFolder -DestinationPath $zip
$digest = (Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$zip.sha256", "$digest  $([IO.Path]::GetFileName($zip))`n", [Text.UTF8Encoding]::new($false))
Write-Output "Package: $zip"
Write-Output "SHA256: $digest"
Write-Output "Staging retained: $staging"
