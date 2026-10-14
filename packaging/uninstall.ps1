# Removes Haris.
#   uninstall.ps1               remove the program, shortcuts and the Windows sign-in entry
#   uninstall.ps1 -RemoveData   also erase settings, memory, audit log, stored keys and engine data
param([switch]$RemoveData)
$ErrorActionPreference = 'Continue'

if (Get-Process Haris -ErrorAction SilentlyContinue) {
    throw 'Haris is running. Close its window first.'
}

$target = Join-Path $env:LOCALAPPDATA 'Programs\Haris'

# Start-with-Windows entry (only ever written by Haris itself, per user).
Remove-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Run' -Name 'Haris' -ErrorAction SilentlyContinue

foreach ($lnk in @((Join-Path ([Environment]::GetFolderPath('Programs')) 'Haris.lnk'),
                   (Join-Path ([Environment]::GetFolderPath('Desktop')) 'Haris.lnk'))) {
    Remove-Item $lnk -ErrorAction SilentlyContinue
}

if ($RemoveData) {
    Write-Host '>> Erasing Haris data...' -ForegroundColor Yellow
    # Containers and their model/cache volumes.
    if (Get-Command docker -ErrorAction SilentlyContinue) {
        foreach ($f in @("$target\voice\compose.yaml", "$target\search\compose.yaml")) {
            if (Test-Path $f) {
                $env:SEARXNG_SECRET = 'removing'
                $files = @('-f', $f)
                $gpu = Join-Path (Split-Path $f) 'compose.gpu.yaml'   # declares the GPU model volume
                if (Test-Path $gpu) { $files += @('-f', $gpu) }
                docker compose @files down --volumes 2>$null | Out-Null
            }
        }
    }
    # Keys stored in Windows Credential Manager under the Haris service.
    (cmdkey /list) -match 'Target:.*(Haris|JARVIS)\|' | ForEach-Object {
        $name = ($_ -split 'Target:\s*')[1].Trim()
        if ($name -match '=') { $name = ($name -split '=', 2)[1] }
        cmdkey /delete:$name | Out-Null
    }
    foreach ($dir in '.haris', '.jarvis') {   # .jarvis: data from before the rename, if never migrated
        Remove-Item (Join-Path $env:USERPROFILE $dir) -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# Removing the folder this script runs from: schedule it for after exit.
if (Test-Path $target) {
    Start-Process -WindowStyle Hidden powershell -ArgumentList @(
        '-NoProfile', '-Command', "Start-Sleep 2; Remove-Item -LiteralPath '$target' -Recurse -Force")
}
Write-Host '>> Haris removed.' -ForegroundColor Green
if (-not $RemoveData) { Write-Host "   Your data is still in $env:USERPROFILE\.haris (run with -RemoveData to erase it)." }
