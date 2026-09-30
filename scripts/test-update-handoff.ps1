$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../desktopApp/build/installer-handoff'))
$directory = Join-Path $root ([guid]::NewGuid().ToString())
[void][IO.Directory]::CreateDirectory($directory)
$ready = Join-Path $directory 'helper.ready'
$package = Join-Path $directory 'test package.msi'
[IO.File]::WriteAllBytes($package, [byte[]]@(1, 2, 3))
$parentScript = @'
$watch = [Diagnostics.Stopwatch]::StartNew()
while (-not (Test-Path -LiteralPath $env:PODOR_TEST_READY)) {
    if ($watch.ElapsedMilliseconds -gt 10000) { exit 1 }
    Start-Sleep -Milliseconds 20
}
exit 0
'@
$start = New-Object Diagnostics.ProcessStartInfo
$start.FileName = "$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
$start.Arguments = '-NoProfile -NonInteractive -EncodedCommand ' + [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($parentScript))
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.EnvironmentVariables['PODOR_TEST_READY'] = $ready
$testParent = [Diagnostics.Process]::Start($start)
$handoff = [pscustomobject]@{ Started = $false }
function Start-Process {
    param([string]$FilePath, [string[]]$ArgumentList, [switch]$Wait, [switch]$PassThru)
    if (-not $testParent.HasExited -or $testParent.ExitCode -ne 0) { throw 'Installer started before the application exited' }
    if ($FilePath -ne "$env:SystemRoot\System32\msiexec.exe" -or
        $ArgumentList -notcontains '/norestart' -or $ArgumentList -notcontains 'REBOOT=ReallySuppress' -or
        $ArgumentList[0] -ne '/i' -or $ArgumentList[1] -ne ('"' + $package + '"') -or -not $Wait -or -not $PassThru) {
        throw 'Incorrect installer arguments'
    }
    $handoff.Started = $true
    [pscustomobject]@{ ExitCode = 0 }
}
try {
    & (Join-Path $PSScriptRoot '../desktopApp/src/main/resources/install-update.ps1') -ParentId $testParent.Id -Installer $package -Sha256 (Get-FileHash -LiteralPath $package -Algorithm SHA256).Hash -Size 3 -ReadyFile $ready
    if (-not $handoff.Started -or (Test-Path -LiteralPath $ready)) { throw 'Installer handoff did not finish' }
    Write-Output 'Installer handoff passed; no installer was run.'
} finally {
    if (-not $testParent.HasExited) { $testParent.Kill() }
    $testParent.Dispose()
    $resolved = [IO.Path]::GetFullPath($directory)
    if (-not $resolved.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Test directory is outside the build folder' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
