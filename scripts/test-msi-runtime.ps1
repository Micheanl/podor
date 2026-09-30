param([Parameter(Mandatory)][string]$Path)
$ErrorActionPreference = 'Stop'
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.OpenDatabase((Resolve-Path -LiteralPath $Path).Path, 0)
function Has-Row([string]$Sql) {
    $view = $database.OpenView($Sql)
    $row = $null
    try {
        [void]$view.Execute()
        $row = $view.Fetch()
        return $null -ne $row
    } finally {
        if ($null -ne $row) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($row) }
        [void]$view.Close()
        [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($view)
    }
}
try {
    if (Has-Row 'SELECT `File` FROM `File` WHERE `FileName` = ''ucrtbase.dll''') {
        throw 'MSI must use the Windows system UCRT'
    }
    $files = @()
    $view = $database.OpenView('SELECT `FileName` FROM `File`')
    try {
        [void]$view.Execute()
        while ($null -ne ($row = $view.Fetch())) {
            try { $files += $row.StringData(1).Split('|')[-1] } finally {
                [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($row)
            }
        }
    } finally {
        [void]$view.Close()
        [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($view)
    }
    foreach ($name in @('jvm.dll', 'vcruntime140.dll', 'podor.exe')) {
        if ($files -notcontains $name) {
            throw "Required runtime file missing: $name"
        }
    }
    if (-not (Has-Row 'SELECT `Property` FROM `Property` WHERE `Property` = ''REBOOT'' AND `Value` = ''ReallySuppress''')) {
        throw 'MSI must suppress automatic reboot'
    }
    if (-not (Has-Row 'SELECT `Shortcut` FROM `Shortcut` WHERE `Directory_` = ''DesktopFolder''')) {
        throw 'Desktop shortcut missing'
    }
    Write-Output 'MSI runtime, shortcut and reboot checks passed'
} finally {
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($database)
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer)
}
