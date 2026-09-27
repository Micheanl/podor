$ErrorActionPreference = 'Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
if (Test-Path .tools/cargo/bin/cargo.exe) {
    $env:CARGO_HOME = Join-Path (Get-Location) '.tools/cargo'
    $env:RUSTUP_HOME = Join-Path (Get-Location) '.tools/rustup'
    $env:PATH = "$env:CARGO_HOME/bin;$env:PATH"
    $env:RUSTFLAGS = '-C linker=rust-lld -C link-self-contained=yes'
}
cargo fmt --check
if ($LASTEXITCODE) { exit $LASTEXITCODE }
cargo clippy --all-targets --locked -- -D warnings
if ($LASTEXITCODE) { exit $LASTEXITCODE }
cargo test --locked
if ($LASTEXITCODE) { exit $LASTEXITCODE }
./gradlew.bat :shared:jvmTest :desktopApp:test --console plain
if ($LASTEXITCODE) { exit $LASTEXITCODE }
if ($env:OS -eq 'Windows_NT') {
    & "$env:SystemRoot/System32/WindowsPowerShell/v1.0/powershell.exe" -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$PSScriptRoot/test-update-handoff.ps1"
    if ($LASTEXITCODE) { exit $LASTEXITCODE }
}
exit 0

