# Creates Mixer's permanent release signing key in signing\ (ignored by git).
# You choose the password; it is only written to signing\keystore.properties on this PC.
# Run once:  powershell -ExecutionPolicy Bypass -File make-release-key.ps1
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$dir = Join-Path $root "signing"
$jks = Join-Path $dir "mixer-release.jks"
$keytool = Join-Path $root ".tools\jdk-17\bin\keytool.exe"

if (Test-Path $jks) { Write-Host "A key already exists at $jks. Not overwriting it." -ForegroundColor Yellow; exit 1 }
if (-not (Test-Path $keytool)) { Write-Host "keytool not found at $keytool" -ForegroundColor Red; exit 1 }
New-Item -ItemType Directory -Force $dir | Out-Null

function Read-Plain([string]$prompt) {
    $s = Read-Host $prompt -AsSecureString
    [Runtime.InteropServices.Marshal]::PtrToStringAuto([Runtime.InteropServices.Marshal]::SecureStringToBSTR($s))
}
do {
    $pw = Read-Plain "Choose a password for the signing key (at least 8 characters)"
    $pw2 = Read-Plain "Type it again"
    if ($pw -ne $pw2) { Write-Host "Passwords don't match, try again." -ForegroundColor Yellow }
    elseif ($pw.Length -lt 8) { Write-Host "Too short." -ForegroundColor Yellow }
} until ($pw -eq $pw2 -and $pw.Length -ge 8)

& $keytool -genkeypair -keystore $jks -alias mixer -keyalg RSA -keysize 4096 -validity 36500 `
    -storepass $pw -keypass $pw -dname "CN=Mixer Keyboard, OU=Alpha, O=Mixer" | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Host "keytool failed." -ForegroundColor Red; exit 1 }

@"
storeFile=mixer-release.jks
storePassword=$pw
keyAlias=mixer
keyPassword=$pw
"@ | Set-Content -Encoding ascii (Join-Path $dir "keystore.properties")

Write-Host ""
Write-Host "Done. Your release key is in $dir" -ForegroundColor Green
Write-Host "BACK UP the whole signing folder (USB drive / password manager). If it is lost,"
Write-Host "testers will have to uninstall Mixer to get any future version."
