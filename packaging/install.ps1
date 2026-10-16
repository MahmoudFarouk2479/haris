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

# Settings files you create inside the program folder (voice\.env, search\.env, ...). The package
# never ships one, so an upgrade keeps yours: saved before the old files go, put back afterwards.
function Test-UserConfig([string]$Name) {
    $Name -eq '.env' -or $Name -like '*.env' -or ($Name -like '.env.*' -and $Name -ne '.env.example')
}
function Save-UserConfig([string]$From, [string]$Backup) {
    if (-not (Test-Path -LiteralPath $From)) { return }
    $root = (Get-Item -LiteralPath $From).FullName.TrimEnd('\')
    Get-ChildItem -LiteralPath $root -Recurse -Force -File | Where-Object { Test-UserConfig $_.Name } | ForEach-Object {
        $relative = $_.FullName.Substring($root.Length + 1)
        $copy = Join-Path $Backup $relative
        New-Item -ItemType Directory -Force (Split-Path $copy) | Out-Null
        Copy-Item -LiteralPath $_.FullName $copy -Force
        $relative
    }
}
function Restore-UserConfig([string]$Backup, [string]$To) {
    if (-not (Test-Path -LiteralPath $Backup)) { return }
    $root = (Get-Item -LiteralPath $Backup).FullName.TrimEnd('\')
    Get-ChildItem -LiteralPath $root -Recurse -Force -File | ForEach-Object {
        $relative = $_.FullName.Substring($root.Length + 1)
        $dest = Join-Path $To $relative
        New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
        Copy-Item -LiteralPath $_.FullName $dest -Force   # yours wins over anything the package put there
    }
}

$backup = Join-Path ([IO.Path]::GetTempPath()) ('haris-config-' + [guid]::NewGuid().ToString('N'))
# This app used to be called JARVIS: its voice\.env carries over too (a Haris one wins, saved after it).
$legacy = Join-Path $env:LOCALAPPDATA 'Programs\JARVIS'
$kept = @()
if (Test-Path (Join-Path $legacy 'JARVIS.exe')) { $kept += @(Save-UserConfig $legacy $backup) }
$kept += @(Save-UserConfig $target $backup)
$kept = @($kept | Sort-Object -Unique)

# Remove the old JARVIS program files and shortcuts. Your data in ~\.jarvis is not touched here;
# Haris moves it to ~\.haris on its first start.
if (Test-Path (Join-Path $legacy 'JARVIS.exe')) {
    Write-Host ">> Removing the old JARVIS program files ($legacy)" -ForegroundColor Cyan
    Remove-Item $legacy -Recurse -Force
}
foreach ($lnk in @((Join-Path ([Environment]::GetFolderPath('Programs')) 'JARVIS.lnk'),
                   (Join-Path ([Environment]::GetFolderPath('Desktop')) 'JARVIS.lnk'))) {
    Remove-Item $lnk -ErrorAction SilentlyContinue
}

Write-Host ">> Installing to $target" -ForegroundColor Cyan
try {
    if (Test-Path $target) { Remove-Item $target -Recurse -Force }   # upgrade: replace the program files only
    New-Item -ItemType Directory -Force $target | Out-Null
    Copy-Item (Join-Path $source '*') $target -Recurse -Force
    Copy-Item (Join-Path $PSScriptRoot 'uninstall.ps1') $target -Force
} finally {
    # Also when the copy failed half-way, so your settings files are never lost.
    try {
        Restore-UserConfig $backup $target
        Remove-Item -LiteralPath $backup -Recurse -Force -ErrorAction SilentlyContinue
    } catch {
        Write-Host "!! Could not put back your settings files; copies are in $backup" -ForegroundColor Yellow
        throw
    }
}
foreach ($file in $kept) { Write-Host "   Kept your $file" }

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
