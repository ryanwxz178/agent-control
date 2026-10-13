param([switch]$StartAtLogon,[switch]$NoStart)
$ErrorActionPreference='Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Windows only' }
function Test-PrivateBridgeAcl($Acl,$UserSid) {
    if (!$Acl.AreAccessRulesProtected -or $Acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ne $UserSid.Value) { return $false }
    $rules=@($Acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]))
    if ($rules.Count -ne 2) { return $false }
    foreach ($identity in @($UserSid.Value,'S-1-5-18')) {
        $matching=@($rules | Where-Object { $_.IdentityReference.Value -eq $identity })
        if ($matching.Count -ne 1) { return $false }
        $rule=$matching[0]
        if ($rule.IsInherited -or $rule.AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
            $rule.FileSystemRights -ne [Security.AccessControl.FileSystemRights]::FullControl -or
            $rule.InheritanceFlags -ne ([Security.AccessControl.InheritanceFlags]::ContainerInherit -bor [Security.AccessControl.InheritanceFlags]::ObjectInherit) -or
            $rule.PropagationFlags -ne [Security.AccessControl.PropagationFlags]::None) { return $false }
    }
    return $true
}
function Test-BridgeRunning([string]$QueueRoot) {
    $lockPath=Join-Path $QueueRoot 'broker.lock'
    if (!(Test-Path -LiteralPath $lockPath)) { return $false }
    try {
        $probe=[IO.File]::Open($lockPath,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
        $probe.Dispose()
        return $false
    } catch [IO.IOException] { return $true }
}
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
foreach ($folder in @($root,$bridge,$bin)) {
    # Normal desktop users can update an already-private installation without
    # asking WinPS 5.1 to rewrite security sections requiring SeSecurityPrivilege.
    if (!(Test-PrivateBridgeAcl (Get-Acl -LiteralPath $folder) $sid)) { Set-Acl -LiteralPath $folder -AclObject $acl }
}
foreach ($file in @('Bridge.ps1','Desktop.ps1','UiLogic.ps1','WorkBuddyStore.ps1','Protocol.ps1','Diagnose.ps1','profiles.json')) {
    if ($file -eq 'profiles.json' -and (Test-Path -LiteralPath "$bin\$file")) {
        $existing=Get-Content -LiteralPath "$bin\$file" -Raw -Encoding UTF8 | ConvertFrom-Json
        $defaults=Get-Content -LiteralPath "$PSScriptRoot\$file" -Raw -Encoding UTF8 | ConvertFrom-Json
        foreach($adapter in $defaults.PSObject.Properties){
            if(!$existing.PSObject.Properties[$adapter.Name]){$existing|Add-Member -NotePropertyName $adapter.Name -NotePropertyValue $adapter.Value;continue}
            foreach($setting in $adapter.Value.PSObject.Properties){
                if(!$existing.($adapter.Name).PSObject.Properties[$setting.Name]){$existing.($adapter.Name)|Add-Member -NotePropertyName $setting.Name -NotePropertyValue $setting.Value}
            }
        }
        [IO.File]::WriteAllText("$bin\$file",($existing|ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
        continue
    }
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
$bridgeRunning=Test-BridgeRunning $bridge
if ($bridgeRunning) { Write-Warning 'Bridge files were updated, but the running Bridge still uses its loaded version. Close the old Bridge and run Start-Bridge.cmd from your normal desktop session.' }
if (!$NoStart -and !$bridgeRunning) {
    if ([Diagnostics.Process]::GetCurrentProcess().SessionId -eq 0 -or ![Environment]::UserInteractive) { Write-Host 'Start Bridge.ps1 from your signed-in desktop, not from SSH.' }
    else { Start-Process -FilePath $exe -ArgumentList $arguments -WindowStyle Hidden }
}
