param(
    [string]$SigningKey = '.local-state/release-signing/private-key.pkcs8',
    [string]$KeyId = 'catbud-2026',
    [ValidateRange(1, 2147483647)][int]$Revision = 1,
    [string]$OutputDirectory
)

$ErrorActionPreference = 'Stop'
$releaseWorkspace = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
Push-Location $releaseWorkspace
try {
    $releaseChanges = @(git status --porcelain --untracked-files=all)
    if ($LASTEXITCODE -ne 0 -or $releaseChanges.Count -gt 0) {
        throw 'Commit and review the release sources before creating a signed release.'
    }
    $releaseCommit = (git rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0) { throw 'Cannot determine the release commit.' }
    $releaseProperties = Get-Content -LiteralPath 'gradle.properties' -Raw
    $releaseVersion = [regex]::Match($releaseProperties, '(?m)^mod_version=(\d+\.\d+\.\d+)\r?$').Groups[1].Value
    $releaseCoreVersion = [regex]::Match($releaseProperties, '(?m)^core_version=(\d+\.\d+\.\d+)\r?$').Groups[1].Value
    if (-not $releaseVersion -or $releaseVersion -ne $releaseCoreVersion) {
        throw 'All addon and Core versions must match for this release format.'
    }
    $releaseTag = "v$releaseVersion"
    if ($Revision -gt 1) { $releaseTag += "-r$Revision" }
    $releasePrivateKey = if ([IO.Path]::IsPathRooted($SigningKey)) { $SigningKey } else { Join-Path $releaseWorkspace $SigningKey }
    if (-not (Test-Path -LiteralPath $releasePrivateKey -PathType Leaf)) { throw 'Release signing key is unavailable.' }
    $releaseOutput = if ($OutputDirectory) {
        [IO.Path]::GetFullPath($OutputDirectory)
    } else {
        Join-Path $releaseWorkspace "out/releases/$releaseTag"
    }
    if (Test-Path -LiteralPath $releaseOutput) { throw 'Release output directory already exists; use a new directory.' }
    $releaseJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java).Source }
    $releaseWrapper = Join-Path $releaseWorkspace 'gradle/wrapper/gradle-wrapper.jar'
    & $releaseJava -classpath $releaseWrapper org.gradle.wrapper.GradleWrapperMain build releaseJars --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Release compilation or tests failed.' }
    New-Item -ItemType Directory -Path (Split-Path $releaseOutput -Parent) -Force | Out-Null
    $releaseArguments = @(
        ':update-engine:releaseManifest',
        "-PreleaseVersion=$releaseVersion", "-PreleaseCommit=$releaseCommit",
        "-PreleaseRevision=$Revision",
        "-PreleaseSigningKey=$releasePrivateKey", "-PreleaseKeyId=$KeyId",
        "-PreleaseArtifactDirectory=$(Join-Path $releaseWorkspace 'out')",
        "-PreleaseOutputDirectory=$releaseOutput",
        "-PreleaseNotesFile=$(Join-Path $releaseWorkspace 'release/notes.json')",
        "-PreleaseKeysFile=$(Join-Path $releaseWorkspace 'release/keys.json')",
        "-PreleaseModulesFile=$(Join-Path $releaseWorkspace 'release/modules.json')", '--console=plain'
    )
    & $releaseJava -classpath $releaseWrapper org.gradle.wrapper.GradleWrapperMain @releaseArguments
    if ($LASTEXITCODE -ne 0) { throw 'Release manifest verification or signing failed.' }
    Write-Output "Verified release prepared at $releaseOutput. Upload only this directory's files to the matching GitHub release."
} finally {
    Pop-Location
}
