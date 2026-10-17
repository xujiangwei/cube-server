param([Parameter(Mandatory=$true)][string]$Stage, [Parameter(Mandatory=$true)][string]$Config,
      [Parameter(Mandatory=$true)][string]$StopFile, [string]$Java = "java")
$ErrorActionPreference = "Stop"
$stagePath = (Resolve-Path -LiteralPath $Stage).Path
$configPath = (Resolve-Path -LiteralPath $Config).Path
Push-Location -LiteralPath $stagePath
try {
    & $Java "-Dharness.config=$configPath" -cp "bin/cell.jar;libs/*;cellets/cube-service-harness-3.0.jar" cube.service.harness.HarnessCell "cell.xml" $StopFile
    exit $LASTEXITCODE
} finally { Pop-Location }
