$ErrorActionPreference = "Stop"

$alias = "knowledge-atlas"
$keystore = Join-Path (Get-Location) "knowledge-atlas-release.jks"

if (Test-Path $keystore) {
    throw "The keystore already exists at $keystore. Refusing to overwrite a signing identity."
}

$keytool = Get-Command keytool -ErrorAction SilentlyContinue
if (-not $keytool) {
    throw "keytool was not found. Install JDK 17 or newer and reopen PowerShell."
}

Write-Host "Creating the permanent Knowledge Atlas signing key."
Write-Host "Keep the keystore and both passwords private. Do not upload the JKS file to GitHub."
Write-Host "Use a strong unique password. When keytool asks for the key password, press ENTER to reuse the keystore password."

& $keytool.Source -genkeypair -v -keystore $keystore -alias $alias -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Knowledge Atlas, OU=Release, O=Knowledge Atlas, C=EG"

if ($LASTEXITCODE -ne 0 -or -not (Test-Path $keystore)) {
    throw "keytool did not create the release keystore."
}

$bytes = [System.IO.File]::ReadAllBytes($keystore)
$base64 = [Convert]::ToBase64String($bytes)
Set-Clipboard -Value $base64

Write-Host ""
Write-Host "Keystore created: $keystore"
Write-Host "Base64 keystore copied to clipboard."
Write-Host "Add the clipboard content to GitHub secret ATLAS_RELEASE_KEYSTORE_BASE64."
Write-Host "Add the password you entered to ATLAS_RELEASE_STORE_PASSWORD and ATLAS_RELEASE_KEY_PASSWORD."
Write-Host "Set ATLAS_RELEASE_KEY_ALIAS to: $alias"
Write-Host "Back up the JKS file and passwords offline in two secure places."
Write-Host "Never send the JKS file or passwords in chat."
