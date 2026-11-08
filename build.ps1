param(
    [string]$ToolsRoot = 'D:\Developer\tools',
    [switch]$SkipNative,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$env:RUSTUP_HOME = Join-Path $ToolsRoot 'rust\rustup'
$env:CARGO_HOME = Join-Path $ToolsRoot 'rust\cargo'
$env:Path = "$env:CARGO_HOME\bin;$env:Path"
$env:ANDROID_HOME = Join-Path $ToolsRoot 'AndroidSDK'
$env:ANDROID_NDK_HOME = Join-Path $env:ANDROID_HOME 'ndk\30.0.14904198'
$env:CRAFT_FONTS_DIR = Join-Path $projectRoot 'native\mobile-fonts'
Remove-Item Env:CRAFT_I18N_DIR,Env:CRAFT_FONTS_REQUIRED -ErrorAction SilentlyContinue
$gradle = Join-Path $ToolsRoot 'gradle\gradle-9.4.1\bin\gradle.bat'
if (!(Test-Path -LiteralPath $gradle)) { $gradle = Join-Path $projectRoot 'gradlew.bat' }

# Release identity lives outside the public repository. The password is DPAPI-encrypted
# for the current Windows user; keytool reads it through the environment, not argv.
$signingDir = Join-Path $ToolsRoot 'doc-reader\signing'
New-Item -ItemType Directory -Force $signingDir | Out-Null
$env:DOCREADER_KEYSTORE = Join-Path $signingDir 'doc-reader-release.jks'
$secretPath = Join-Path $signingDir 'password.dpapi'
if (!(Test-Path -LiteralPath $secretPath)) {
    if (Test-Path -LiteralPath $env:DOCREADER_KEYSTORE) { throw 'Existing keystore has no saved password; restore the signing backup.' }
    $password = [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    ConvertTo-SecureString $password -AsPlainText -Force | ConvertFrom-SecureString | Set-Content -LiteralPath $secretPath
}
$secure = Get-Content -LiteralPath $secretPath | ConvertTo-SecureString
$env:DOCREADER_KEY_PASSWORD = [Net.NetworkCredential]::new('', $secure).Password
if (!(Test-Path -LiteralPath $env:DOCREADER_KEYSTORE)) {
    & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -keystore $env:DOCREADER_KEYSTORE -storetype JKS -alias doc-reader -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=doc-reader' -storepass:env DOCREADER_KEY_PASSWORD -keypass:env DOCREADER_KEY_PASSWORD
    if ($LASTEXITCODE -ne 0) { throw 'Signing key creation failed' }
}
try {
    Push-Location $projectRoot
    if (!(Test-Path 'vendor/wordcraft/crates/docx/Cargo.toml')) { throw 'Initialize the WordCraft submodule first: git submodule update --init --depth 1' }
    if (!$SkipNative) {
        Push-Location native
        try {
            $cargoArgs = @('ndk','-t','arm64-v8a','-t','x86_64','--platform','34','-o','../app/src/main/jniLibs','build','--release','--locked')
            if ($Offline) { $cargoArgs += '--offline' }
            & cargo @cargoArgs
            if ($LASTEXITCODE -ne 0) { throw 'Native build failed' }
        } finally { Pop-Location }
    }
    & python tests/collect_licenses.py
    if ($LASTEXITCODE -ne 0) { throw 'License collection failed' }
    $gradleArgs = @('assembleRelease','lint')
    if ($Offline) { $gradleArgs += '--offline' }
    & $gradle @gradleArgs
    if ($LASTEXITCODE -ne 0) { throw 'Android build or lint failed' }
    New-Item -ItemType Directory -Force deliverables | Out-Null
    Copy-Item app/build/outputs/apk/release/app-release.apk deliverables/doc-reader-v0.3.0.apk -Force
    Write-Output (Join-Path $projectRoot 'deliverables/doc-reader-v0.3.0.apk')
} finally {
    Pop-Location
    Remove-Item Env:DOCREADER_KEY_PASSWORD -ErrorAction SilentlyContinue
}
