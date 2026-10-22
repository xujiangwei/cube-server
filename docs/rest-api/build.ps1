[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$nodeCommand = Get-Command node.exe -CommandType Application -All -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($null -eq $nodeCommand) {
    throw 'Node.js was not found. Install Node.js and ensure node.exe is available on PATH.'
}

$npxCommand = Get-Command npx.cmd -CommandType Application -All -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($null -eq $npxCommand) {
    throw 'npx was not found. Install npm and ensure npx.cmd is available on PATH.'
}

$locationPushed = $false
try {
    Push-Location -LiteralPath $PSScriptRoot
    $locationPushed = $true

    Write-Host 'Make Baize Server API documents...'
    & $npxCommand.Path --yes apidoc@0.53.1 -i src -o output
    $apiDocExitCode = $LASTEXITCODE
    if ($apiDocExitCode -ne 0) {
        throw "apiDoc generation failed with exit code $apiDocExitCode."
    }

    Write-Host 'Applying modern theme...'
    & $nodeCommand.Path apidoc-theme/apply.mjs output
    $themeExitCode = $LASTEXITCODE
    if ($themeExitCode -ne 0) {
        throw "Theme application failed with exit code $themeExitCode."
    }
}
finally {
    if ($locationPushed) {
        Pop-Location
    }
}
