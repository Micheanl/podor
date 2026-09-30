#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
version=$(sed -n 's/^podor = "\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)"/\1/p' gradle/libs.versions.toml)
test -n "$version"
IFS=. read -r version_major version_minor version_patch <<< "$version"
build_number=$((version_major * 1000000 + version_minor * 1000 + version_patch))
rustup target add aarch64-apple-ios aarch64-apple-ios-sim
cargo build --release --locked --target aarch64-apple-ios
cargo build --release --locked --target aarch64-apple-ios-sim
cd iosApp
xcodegen generate
xcodebuild -project podor.xcodeproj -scheme podor -configuration Release \
  -sdk iphonesimulator -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build/simulator ARCHS=arm64 ONLY_ACTIVE_ARCH=YES CODE_SIGNING_ALLOWED=NO \
  MARKETING_VERSION="$version" CURRENT_PROJECT_VERSION="$build_number" build
xcodebuild -project podor.xcodeproj -scheme podor -configuration Release \
  -sdk iphoneos -destination 'generic/platform=iOS' \
  -derivedDataPath build/device-derived -archivePath build/device/podor.xcarchive \
  CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO \
  MARKETING_VERSION="$version" CURRENT_PROJECT_VERSION="$build_number" archive
python3 ../scripts/verify-ios-package.py build/simulator/Build/Products/Release-iphonesimulator/podor.app "$version"
python3 ../scripts/verify-ios-package.py build/device/podor.xcarchive/Products/Applications/podor.app "$version"
mkdir -p build/artifacts
ditto -c -k --sequesterRsrc --keepParent \
  build/simulator/Build/Products/Release-iphonesimulator/podor.app \
  build/artifacts/podor-ios-simulator.zip
ditto -c -k --sequesterRsrc --keepParent \
  build/device/podor.xcarchive build/artifacts/podor-ios-device-unsigned.zip
mkdir -p build/framework-package/Rust/aarch64-apple-ios build/framework-package/Rust/aarch64-apple-ios-sim
mkdir -p build/framework-package/PodorShared
cp -R ../shared/build/xcode-frameworks/. build/framework-package/PodorShared/
cp ../target/aarch64-apple-ios/release/libpodor_engine.a build/framework-package/Rust/aarch64-apple-ios/
cp ../target/aarch64-apple-ios-sim/release/libpodor_engine.a build/framework-package/Rust/aarch64-apple-ios-sim/
cp -R build/simulator/Build/Products/Release-iphonesimulator/podor.app/compose-resources build/framework-package/
ditto -c -k --sequesterRsrc --keepParent build/framework-package build/artifacts/podor-ios-frameworks.zip
