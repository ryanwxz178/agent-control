$ErrorActionPreference='Stop'
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$task=Get-ScheduledTask -TaskName "OrcaPocket-$sid" -ErrorAction SilentlyContinue
if ($task) { Stop-ScheduledTask -InputObject $task; Unregister-ScheduledTask -InputObject $task -Confirm:$false }
Write-Host 'Logon startup removed. Close any manually started Bridge window. Files and diagnostics are retained in %LOCALAPPDATA%\OrcaPocket; no SSH settings were changed.'
