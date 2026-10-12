# Downloads the wake-word model into models\ (once), checking it against a pinned SHA-256.
#
#   vosk-model-small-en-us-0.15  -  Apache-2.0, https://alphacephei.com/vosk/models
#
# Used by run.ps1 and package.ps1; the packaged app ships the extracted model, so Haris itself
# never downloads anything at run time.
$ErrorActionPreference = 'Stop'

$name = 'vosk-model-small-en-us-0.15'
$url = "https://alphacephei.com/vosk/models/$name.zip"
$sha256 = '30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498'
$target = Join-Path $PSScriptRoot $name

if (Test-Path (Join-Path $target 'am\final.mdl')) { return }

Write-Host ">> Downloading wake-word model ($name, ~40 MB)..." -ForegroundColor Cyan
$zip = Join-Path ([IO.Path]::GetTempPath()) "$name.zip"
Invoke-WebRequest -Uri $url -OutFile $zip -UseBasicParsing
$actual = (Get-FileHash $zip -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $sha256) {
    Remove-Item $zip -Force
    throw "Wake-word model checksum mismatch (got $actual) - not using it."
}
Expand-Archive $zip -DestinationPath $PSScriptRoot -Force
Remove-Item $zip -Force
Write-Host ">> Wake-word model ready: $target" -ForegroundColor Green
