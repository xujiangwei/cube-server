param([Parameter(Mandatory=$true)][string]$Stage, [string]$Config,
      [Parameter(Mandatory=$true)][string]$StopFile, [string]$Java = "java")
$ErrorActionPreference = "Stop"
$stagePath = (Resolve-Path -LiteralPath $Stage).Path
$configArgs = @()
if ($Config) {
    $configPath = (Resolve-Path -LiteralPath $Config).Path
    $configArgs = @("-Dharness.config=$configPath")
}
Push-Location -LiteralPath $stagePath
try {
    & $Java @configArgs -cp "bin/cell.jar;libs/*;cellets/cube-service-harness-3.0.jar" cube.service.harness.HarnessCell "cell.xml" $StopFile
    exit $LASTEXITCODE
} finally { Pop-Location }
