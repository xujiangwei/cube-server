[CmdletBinding()]
param([switch]$NoSync)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$nodeCommand = Get-Command node.exe -CommandType Application -All -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($null -eq $nodeCommand) {
    throw 'Node.js was not found. Install Node.js and ensure node.exe is available on PATH.'
}

$npmCommand = Get-Command npm.cmd -CommandType Application -All -ErrorAction SilentlyContinue |
    Select-Object -First 1
if ($null -eq $npmCommand) {
    throw 'npm was not found. Install Node.js 18+ with npm and ensure npm.cmd is available on PATH.'
}

$locationPushed = $false
try {
    Push-Location -LiteralPath $PSScriptRoot
    $locationPushed = $true

    # Reinstall only when the locked dependency set changes or dependencies are missing.
    $lockHash = (Get-FileHash -LiteralPath 'package-lock.json' -Algorithm SHA256).Hash
    $stamp = 'node_modules/.cube-docs-lock'
    if (!(Test-Path -LiteralPath $stamp) -or (Get-Content -LiteralPath $stamp -Raw).Trim() -ne $lockHash) {
        & $npmCommand.Path ci --include=dev --ignore-scripts --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { throw 'Documentation dependency installation failed.' }
        Set-Content -LiteralPath $stamp -Value $lockHash -NoNewline
    }
    Write-Host 'Building API documentation reader...'
    if ($NoSync) { & $nodeCommand.Path reader/build.mjs --no-sync }
    else { & $nodeCommand.Path reader/build.mjs }
    if ($LASTEXITCODE -ne 0) {
        throw "Documentation build failed with exit code $LASTEXITCODE."
    }
}
finally {
    if ($locationPushed) {
        Pop-Location
    }
}
