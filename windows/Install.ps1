param([switch]$StartAtLogon,[switch]$NoStart)
$ErrorActionPreference='Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Windows only' }
$root=Join-Path $env:ProgramData ('AgentControl\'+[Security.Principal.WindowsIdentity]::GetCurrent().User.Value)
$bridge=Join-Path $root 'bridge'
$bin=Join-Path $root 'bin'
New-Item -ItemType Directory -Path $root,$bridge,$bin -Force | Out-Null
# The queue grants access only to the current user and SYSTEM, never all local users.
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
$acl=[Security.AccessControl.DirectorySecurity]::new()
$acl.SetOwner($sid); $acl.SetAccessRuleProtection($true,$false)
foreach ($principal in @($sid,[Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($principal,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
}
Set-Acl -LiteralPath $root -AclObject $acl
Set-Acl -LiteralPath $bridge -AclObject $acl
Set-Acl -LiteralPath $bin -AclObject $acl
foreach ($file in @('Bridge.ps1','Desktop.ps1','UiLogic.ps1','Protocol.ps1','Diagnose.ps1','profiles.json')) {
    if ($file -eq 'profiles.json' -and (Test-Path -LiteralPath "$bin\$file")) { continue }
    Copy-Item -LiteralPath "$PSScriptRoot\$file" -Destination "$bin\$file" -Force
}
$exe="$env:SystemRoot\System32\WindowsPowerShell\v1.0\powershell.exe"
$arguments='-NoLogo -NoProfile -STA -ExecutionPolicy Bypass -File "'+$bin+'\Bridge.ps1"'
if ($StartAtLogon) {
    $user=[Security.Principal.WindowsIdentity]::GetCurrent().Name
    $task=New-ScheduledTask -Action (New-ScheduledTaskAction -Execute $exe -Argument $arguments) -Trigger (New-ScheduledTaskTrigger -AtLogOn -User $user) -Principal (New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited) -Settings (New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) -MultipleInstances IgnoreNew -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries)
    Register-ScheduledTask -TaskName ('OrcaPocket-'+$sid.Value) -InputObject $task -Force | Out-Null
}
Write-Host "Installed to $bin. SSH must use the same Windows account."
if (!$NoStart) {
    if ([Diagnostics.Process]::GetCurrentProcess().SessionId -eq 0 -or ![Environment]::UserInteractive) { Write-Host 'Start Bridge.ps1 from your signed-in desktop, not from SSH.' }
    else { Start-Process -FilePath $exe -ArgumentList $arguments -WindowStyle Hidden }
}
