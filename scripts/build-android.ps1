$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (-not (Get-Command cargo-ndk -ErrorAction SilentlyContinue)) { throw '请先安装 Rust 和 cargo-ndk，并配置 ANDROID_NDK_HOME' }
rustup target add aarch64-linux-android x86_64-linux-android
if ($LASTEXITCODE) { exit $LASTEXITCODE }
$catalog = Get-Content gradle/libs.versions.toml -Raw
$minSdk = [regex]::Match($catalog, '(?m)^minSdk = "(\d+)"\s*$').Groups[1].Value
if (-not $minSdk) { throw '版本目录缺少 minSdk' }
cargo ndk --platform $minSdk -t arm64-v8a -t x86_64 -o androidApp/src/main/jniLibs build --release --locked
if ($LASTEXITCODE) { exit $LASTEXITCODE }
./gradlew.bat -PenableAndroid=true :androidApp:assembleDebug
exit $LASTEXITCODE

