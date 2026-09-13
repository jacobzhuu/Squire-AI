param([string]$JavaHome = 'C:/Program Files/Java/jdk-21')
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$env:JAVA_HOME = $JavaHome
$evidence = Join-Path $projectRoot 'build/locomotion-validation'
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
function Run-Gradle([string]$label, [string[]]$gradleArguments) {
    Write-Output "Starting $label at $(Get-Date -Format o)"
    $ErrorActionPreference = 'Continue'
    & .\gradlew.bat @gradleArguments --console=plain *> (Join-Path $evidence "$label.log")
    if ($LASTEXITCODE -ne 0) { throw "$label failed; see build/locomotion-validation/$label.log" }
    if ($gradleArguments -contains 'runGametest') {
        Copy-Item -LiteralPath 'build/reports/gametest/report.xml' -Destination (Join-Path $evidence "$label.xml")
    }
    Write-Output "Passed $label at $(Get-Date -Format o)"
}
Run-Gradle 'movement-regression' @('-PsquireGameTestClass=EngineerLocomotionGameTests,M33ScaffoldingGameTests,M37EngineerTravelGameTests,M40ConstructionRecoveryGameTests,ConstructionRecoveryGameTests', 'runGametest')
# Full suite and unfiltered manifest for the actual release JAR.
Run-Gradle 'release' @('test', 'build')
Get-FileHash -LiteralPath 'build/libs/squire-0.1.0+mc1.20.1.jar' -Algorithm SHA256
Write-Output 'LOCOMOTION VALIDATION COMPLETE'
