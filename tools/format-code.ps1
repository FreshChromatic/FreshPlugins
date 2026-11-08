param([switch]$Check)

$ErrorActionPreference = 'Stop'
$workspacePath = Split-Path $PSScriptRoot -Parent
Push-Location $workspacePath
try {
    $javaExecutable = if ($env:JAVA_HOME) {
        Join-Path $env:JAVA_HOME 'bin/java.exe'
    } else {
        (Get-Command java).Source
    }
    $javaSources = @(rg --files projects -g '*.java' -g '!**/build/**')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate Java sources.' }
    $javaSources += @(rg --files libraries -g '*.java' -g '!**/build/**')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate library Java sources.' }
    $javaSources += @(rg --files tools -g '*.java' -g '!**/build/**')
    if ($LASTEXITCODE -ne 0) { throw 'Cannot enumerate Java formatting tools.' }
    $readabilityOptions = @()
    if ($Check) {
        $readabilityOptions += '--check'
    }
    $argumentsFile = [System.IO.Path]::GetTempFileName()
    try {
        $arguments = @('tools/ReadableJava.java') + $readabilityOptions + @($javaSources | ForEach-Object {
            '"' + $_.Replace('\', '/') + '"'
        })
        [System.IO.File]::WriteAllLines($argumentsFile, [string[]]$arguments, [System.Text.UTF8Encoding]::new($false))
        & $javaExecutable "@$argumentsFile"
        if ($LASTEXITCODE -ne 0) { throw 'Java control-flow readability check failed.' }
    } finally {
        Remove-Item -LiteralPath $argumentsFile -Force
    }
    $formatOptions = @('-p', 'tools/java-formatter', 'test', 'formatJava', '--console=plain')
    if ($Check) {
        $formatOptions += '-PcheckFormat'
    }
    & (Join-Path $workspacePath 'gradlew.bat') @formatOptions
    if ($LASTEXITCODE -ne 0) { throw 'Java formatting check failed.' }

    if (Test-Path -LiteralPath 'package.json') {
        if (-not (Test-Path -LiteralPath 'node_modules/prettier')) {
            npm ci --ignore-scripts
            if ($LASTEXITCODE -ne 0) { throw 'Cannot install development formatter.' }
        }
        $serviceTask = if ($Check) { 'check:service' } else { 'format:service' }
        npm run $serviceTask
        if ($LASTEXITCODE -ne 0) { throw 'Service formatting check failed.' }
    }
} finally {
    Pop-Location
}
