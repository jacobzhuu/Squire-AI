param([string]$JavaHome = 'C:/Program Files/Java/jdk-21', [switch]$SkipCatalog)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $projectRoot
$env:JAVA_HOME = $JavaHome
$evidence = Join-Path $projectRoot 'build/construction-validation'
New-Item -ItemType Directory -Path $evidence -Force | Out-Null
function Run-Gradle([string]$label, [string[]]$gradleArguments) {
    Write-Output "Starting $label at $(Get-Date -Format o)"
    # PowerShell 5 treats native stderr as an ErrorRecord even for harmless javac warnings.
    $ErrorActionPreference = 'Continue'
    & .\gradlew.bat @gradleArguments --console=plain *> (Join-Path $evidence "$label.log")
    if ($LASTEXITCODE -ne 0) { throw "$label failed; see build/construction-validation/$label.log" }
    if ($gradleArguments -contains 'runGametest') {
        Copy-Item -LiteralPath 'build/reports/gametest/report.xml' -Destination (Join-Path $evidence "$label.xml")
    }
    Write-Output "Passed $label at $(Get-Date -Format o)"
}
if (!$SkipCatalog) {
    Run-Gradle 'catalog' @('-PsquireGameTestClass=M30CatalogQualificationGameTests,ConstructionRecoveryGameTests', 'runGametest')
    Copy-Item -LiteralPath 'build/gametest/catalog-qualification.json' -Destination (Join-Path $evidence 'catalog-qualification.json')
    & python tools/import_keepitlevel.py --qualification build/construction-validation/catalog-qualification.json
    if ($LASTEXITCODE -ne 0) { throw 'Catalog generation failed' }
    & python tools/import_keepitlevel.py --check
    if ($LASTEXITCODE -ne 0) { throw 'Catalog reproducibility check failed' }
} elseif (!(Test-Path -LiteralPath (Join-Path $evidence 'catalog-qualification.json'))) {
    throw 'No previous catalog evidence; run without -SkipCatalog first'
}
Run-Gradle 'matrix-regression' @('-PsquireGameTestClass=M41ConstructionMatrixGameTests,M40ConstructionRecoveryGameTests,M33ScaffoldingGameTests,M34FluidConstructionGameTests,M35GroundPreparationGameTests,M37EngineerTravelGameTests,ConstructionRecoveryGameTests', 'runGametest')
Copy-Item -LiteralPath 'build/gametest/construction-matrix.json' -Destination (Join-Path $evidence 'construction-matrix.json')
# Unfiltered manifest is restored for the actual release JAR.
Run-Gradle 'release' @('test', 'build')
Get-FileHash -LiteralPath 'build/libs/squire-0.1.0+mc1.20.1.jar' -Algorithm SHA256
Write-Output 'CONSTRUCTION VALIDATION COMPLETE'
