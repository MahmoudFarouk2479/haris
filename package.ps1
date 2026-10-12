# Builds the installable Haris for Windows:
#   target\dist\Haris\              self-contained app (its own Java runtime, no Java needed)
#   target\Haris-<ver>-windows.zip   the app + install.ps1 / uninstall.ps1, ready to hand over
#
#   .\package.ps1             build with tests
#   .\package.ps1 -SkipTests  faster, without running the test suite
param([switch]$SkipTests)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$jdk = 'C:\Program Files\Java\jdk-21'
if (Test-Path "$jdk\bin\jpackage.exe") { $env:JAVA_HOME = $jdk }
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to a JDK 21 (jpackage is needed).' }
$jpackage = Join-Path $env:JAVA_HOME 'bin\jpackage.exe'

[xml]$pom = Get-Content pom.xml
$version = $pom.project.version

& "$PSScriptRoot\models\fetch-models.ps1"

Write-Host ">> Building HUD..." -ForegroundColor Cyan
Push-Location ui
if (-not (Test-Path node_modules)) { npm ci }
npm run build
if ($LASTEXITCODE) { throw 'HUD build failed' }
Pop-Location

Write-Host ">> Building core $version..." -ForegroundColor Cyan
$mvnArgs = @('-q', '-B', 'package')
if ($SkipTests) { $mvnArgs += '-DskipTests' }
mvn @mvnArgs
if ($LASTEXITCODE) { throw 'Core build failed (tests?)' }

$stage = 'target\jpackage-input'
$dist = 'target\dist'
Remove-Item $stage, $dist -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force $stage | Out-Null
Copy-Item "target\haris-core-$version.jar" $stage

Write-Host ">> Creating app image (bundled Java runtime)..." -ForegroundColor Cyan
& $jpackage --type app-image `
    --name Haris `
    --app-version $version `
    --vendor 'Haris' `
    --description 'Local-first, security-first desktop AI assistant' `
    --icon packaging\haris.ico `
    --input $stage `
    --main-jar "haris-core-$version.jar" `
    --app-content voice `
    --app-content search `
    --app-content models `
    --java-options '-Xmx768m' `
    --java-options '-Dfile.encoding=UTF-8' `
    --dest $dist
if ($LASTEXITCODE) { throw 'jpackage failed' }

# Never ship local secrets or personal overrides by accident.
Get-ChildItem "$dist\Haris" -Recurse -Force -Filter '.env' | Remove-Item -Force
Remove-Item "$dist\Haris\models\fetch-models.ps1" -ErrorAction SilentlyContinue

Copy-Item packaging\install.ps1, packaging\uninstall.ps1, colab\haris_colab.ipynb $dist
$zip = "target\Haris-$version-windows.zip"
Remove-Item $zip -ErrorAction SilentlyContinue
Compress-Archive -Path "$dist\*" -DestinationPath $zip
$size = [math]::Round((Get-Item $zip).Length / 1MB, 1)
Write-Host ">> Done: $zip ($size MB). Unzip and run install.ps1." -ForegroundColor Green
