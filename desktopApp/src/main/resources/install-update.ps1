param(
    [Parameter(Mandatory)][int]$ParentId,
    [Parameter(Mandatory)][string]$Installer,
    [Parameter(Mandatory)][string]$Sha256,
    [Parameter(Mandatory)][long]$Size,
    [Parameter(Mandatory)][string]$ReadyFile
)
$ErrorActionPreference = 'Stop'
try {
    $parentProcess = [Diagnostics.Process]::GetProcessById($ParentId)
    if ((Get-Item -LiteralPath $Installer).Length -ne $Size -or (Get-FileHash -LiteralPath $Installer -Algorithm SHA256).Hash -ne $Sha256) { throw 'The installer has changed. Please download it again.' }
    [IO.File]::WriteAllText($ReadyFile, 'ready')
    if (-not $parentProcess.WaitForExit(60000)) { throw 'podor is still running. Close it before installing the update.' }
    $log = [IO.Path]::ChangeExtension($Installer, '.log')
    $arguments = @('/i', ('"' + $Installer + '"'), '/norestart', 'REBOOT=ReallySuppress', '/L*v', ('"' + $log + '"'))
    $process = Start-Process -FilePath "$env:SystemRoot\System32\msiexec.exe" -ArgumentList $arguments -Wait -PassThru
    if ($process.ExitCode -eq 3010) { throw 'Windows could not replace files that are in use. Your computer will not restart automatically. Close other copies of podor and run the installer again.' }
    if ($process.ExitCode -notin @(0, 1602)) { throw "Installation failed ($($process.ExitCode)). The installer and log are in $([IO.Path]::GetDirectoryName($Installer))." }
} catch {
    Add-Type -AssemblyName System.Windows.Forms
    [void][System.Windows.Forms.MessageBox]::Show($_.Exception.Message, 'podor', 'OK', 'Warning')
    exit 1
} finally {
    if (Test-Path -LiteralPath $ReadyFile) { Remove-Item -LiteralPath $ReadyFile }
}
