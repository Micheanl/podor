param([Parameter(Mandatory)][string]$Path)
$ErrorActionPreference = 'Stop'
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.OpenDatabase((Resolve-Path -LiteralPath $Path).Path, 1)
function Execute-Sql([string]$Sql) {
    $view = $database.OpenView($Sql)
    try { [void]$view.Execute() } finally {
        [void]$view.Close()
        [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($view)
    }
}
function Has-Row([string]$Sql) {
    $view = $database.OpenView($Sql)
    $row = $null
    try { [void]$view.Execute(); $row = $view.Fetch(); return $null -ne $row } finally {
        if ($null -ne $row) { [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($row) }
        [void]$view.Close()
        [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($view)
    }
}
try {
    if (Has-Row 'SELECT `File` FROM `File` WHERE `FileName` = ''ucrtbase.dll''') {
        throw 'Windows 10 and later use the system UCRT; rebuild the runtime without ucrtbase.dll'
    }
    if (-not (Has-Row 'SELECT `Directory` FROM `Directory` WHERE `Directory` = ''INSTALLDIR''') -or
        -not (Has-Row 'SELECT `File` FROM `File` WHERE `FileName` = ''podor.exe''') -or
        -not (Has-Row 'SELECT `Shortcut` FROM `Shortcut` WHERE `Directory_` = ''DesktopFolder''') -or
        -not (Has-Row 'SELECT `Control` FROM `Control` WHERE `Dialog_` = ''ExitDialog'' AND `Control` = ''OptionalCheckBox''')) {
        throw 'Installer layout or desktop shortcut is missing'
    }
    foreach ($sequence in @('InstallExecuteSequence', 'InstallUISequence')) {
        if (Has-Row "SELECT ``Action`` FROM ``$sequence`` WHERE ``Action`` = 'ForceReboot' OR ``Action`` = 'ScheduleReboot'") {
            throw 'Installer contains a reboot action'
        }
    }
    foreach ($property in @('REBOOT', 'MSIRESTARTMANAGERCONTROL', 'WIXUI_EXITDIALOGOPTIONALCHECKBOXTEXT', 'WIXUI_EXITDIALOGOPTIONALCHECKBOX')) {
        Execute-Sql "DELETE FROM ``Property`` WHERE ``Property`` = '$property'"
    }
    Execute-Sql "INSERT INTO ``Property`` (``Property``, ``Value``) VALUES ('REBOOT', 'ReallySuppress')"
    Execute-Sql "INSERT INTO ``Property`` (``Property``, ``Value``) VALUES ('MSIRESTARTMANAGERCONTROL', 'DisableShutdown')"
    Execute-Sql "INSERT INTO ``Property`` (``Property``, ``Value``) VALUES ('WIXUI_EXITDIALOGOPTIONALCHECKBOXTEXT', 'Open podor')"
    Execute-Sql "INSERT INTO ``Property`` (``Property``, ``Value``) VALUES ('WIXUI_EXITDIALOGOPTIONALCHECKBOX', '1')"
    Execute-Sql "DELETE FROM ``CustomAction`` WHERE ``Action`` = 'PodorLaunch'"
    Execute-Sql 'INSERT INTO `CustomAction` (`Action`, `Type`, `Source`, `Target`) VALUES (''PodorLaunch'', 226, ''INSTALLDIR'', ''"[INSTALLDIR]podor.exe"'')'
    Execute-Sql "DELETE FROM ``ControlEvent`` WHERE ``Dialog_`` = 'ExitDialog' AND ``Control_`` = 'Finish' AND ``Event`` = 'DoAction' AND ``Argument`` = 'PodorLaunch'"
    Execute-Sql 'INSERT INTO `ControlEvent` (`Dialog_`, `Control_`, `Event`, `Argument`, `Condition`, `Ordering`) VALUES (''ExitDialog'', ''Finish'', ''DoAction'', ''PodorLaunch'', ''WIXUI_EXITDIALOGOPTIONALCHECKBOX = 1 AND NOT Installed AND NOT (REMOVE = "ALL") AND NOT ReplacedInUseFiles'', 1)'
    [void]$database.Commit()
} finally {
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($database)
    [void][Runtime.InteropServices.Marshal]::FinalReleaseComObject($installer)
}
