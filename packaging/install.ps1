# Installs Haris for the current user — no administrator rights, nothing outside your profile.
#   powershell -ExecutionPolicy Bypass -File install.ps1 [-DesktopShortcut]
param([switch]$DesktopShortcut)
$ErrorActionPreference = 'Stop'

$source = Join-Path $PSScriptRoot 'Haris'
if (-not (Test-Path (Join-Path $source 'Haris.exe'))) { throw "Haris app folder not found next to this script." }
$target = Join-Path $env:LOCALAPPDATA 'Programs\Haris'

if (Get-Process Haris, JARVIS -ErrorAction SilentlyContinue) {
    throw 'Haris (or the older JARVIS) is running. Close its window first, then run the installer again.'
}

# This app used to be called JARVIS: remove its program files and shortcuts. Your data in
# ~\.jarvis is not touched here; Haris moves it to ~\.haris on its first start.
$legacy = Join-Path $env:LOCALAPPDATA 'Programs\JARVIS'
if (Test-Path (Join-Path $legacy 'JARVIS.exe')) {
    Write-Host ">> Removing the old JARVIS program files ($legacy)" -ForegroundColor Cyan
    Remove-Item $legacy -Recurse -Force
}
foreach ($lnk in @((Join-Path ([Environment]::GetFolderPath('Programs')) 'JARVIS.lnk'),
                   (Join-Path ([Environment]::GetFolderPath('Desktop')) 'JARVIS.lnk'))) {
    Remove-Item $lnk -ErrorAction SilentlyContinue
}

Write-Host ">> Installing to $target" -ForegroundColor Cyan
if (Test-Path $target) { Remove-Item $target -Recurse -Force }   # upgrade: replace the program files only
New-Item -ItemType Directory -Force $target | Out-Null
Copy-Item (Join-Path $source '*') $target -Recurse -Force
Copy-Item (Join-Path $PSScriptRoot 'uninstall.ps1') $target -Force

# Verify the copy instead of trusting it: a half-copied install would start and exit silently.
$expected = Get-ChildItem (Join-Path $source 'app') -Filter 'haris-core-*.jar'
$copied = Join-Path $target "app\$($expected.Name)"
if (-not (Test-Path $copied) -or (Get-Item $copied).Length -ne $expected.Length) {
    throw "Install incomplete: $copied is missing or truncated. Close Haris (and any Haris window) and run install.ps1 again."
}

$shell = New-Object -ComObject WScript.Shell
function New-Shortcut($path) {
    $lnk = $shell.CreateShortcut($path)
    $lnk.TargetPath = Join-Path $target 'Haris.exe'
    $lnk.WorkingDirectory = $target
    $lnk.IconLocation = (Join-Path $target 'Haris.exe') + ',0'
    $lnk.Description = 'Haris — local, private AI assistant'
    $lnk.Save()
}
New-Shortcut (Join-Path ([Environment]::GetFolderPath('Programs')) 'Haris.lnk')
if ($DesktopShortcut) { New-Shortcut (Join-Path ([Environment]::GetFolderPath('Desktop')) 'Haris.lnk') }

Write-Host ">> Installed. Start Haris from the Start menu." -ForegroundColor Green
Write-Host "   Your settings and memory stay in $env:USERPROFILE\.haris (kept across upgrades)."
Write-Host "   Optional: Docker Desktop for voice and private web search."
