# Runs Haris from source: builds the HUD and core when sources changed, then starts it.
# The core opens its own window, starts the local voice/search containers if Docker is
# running, and stops everything when the window is closed.
#   .\run.ps1           build if needed, then run
#   .\run.ps1 -Rebuild  force a full rebuild
# To build the installable app instead, use .\package.ps1
param([switch]$Rebuild)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$jdk = 'C:\Program Files\Java\jdk-21'
if (Test-Path "$jdk\bin\java.exe") { $env:JAVA_HOME = $jdk }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }

& "$PSScriptRoot\models\fetch-models.ps1"

$jar = Get-ChildItem target\haris-core-*.jar -ErrorAction SilentlyContinue | Select-Object -First 1
$newestSource = Get-ChildItem src, ui\src, ui\public, ui\index.html, pom.xml -Recurse -File |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1

if ($Rebuild -or -not $jar -or $newestSource.LastWriteTime -gt $jar.LastWriteTime) {
    Write-Host '>> Building HUD...' -ForegroundColor Cyan
    Push-Location ui
    if (-not (Test-Path node_modules)) { npm ci }
    npm run build
    if ($LASTEXITCODE) { throw 'HUD build failed' }
    Pop-Location

    Write-Host '>> Building core...' -ForegroundColor Cyan
    mvn -q -B package -DskipTests
    if ($LASTEXITCODE) { throw 'Core build failed' }
    $jar = Get-ChildItem target\haris-core-*.jar | Select-Object -First 1
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Host '>> Docker not found: voice and web search will be off.' -ForegroundColor Yellow
}

Write-Host '>> Starting Haris (loopback only)...' -ForegroundColor Cyan
& $java -jar $jar.FullName
