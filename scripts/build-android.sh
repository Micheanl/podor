#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

min_sdk=$(sed -n 's/^minSdk = "\([0-9]*\)"[[:space:]]*$/\1/p' gradle/libs.versions.toml)
[[ "$min_sdk" =~ ^[0-9]+$ ]]
rustup target add aarch64-linux-android x86_64-linux-android
cargo ndk --platform "$min_sdk" -t arm64-v8a -t x86_64 -o androidApp/src/main/jniLibs build --release --locked
./gradlew -PenableAndroid=true :androidApp:assembleDebug --console plain
