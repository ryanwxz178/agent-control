param([Parameter(Mandatory=$true)][string[]]$AllowedAddresses,[switch]$UseWin32OpenSsh)
$ErrorActionPreference='Stop'
$principal=[Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Run this script in an elevated Windows PowerShell window.' }
if ($AllowedAddresses.Count -eq 0 -or @($AllowedAddresses | Where-Object { $_ -in @('Any','*','0.0.0.0/0','::/0') }).Count) { throw 'Specify trusted client IP addresses or private network subnets.' }
# Preserve an existing GitHub Win32-OpenSSH installation as well as Windows capabilities.
if (!(Get-Service sshd -ErrorAction SilentlyContinue)) {
    if ($UseWin32OpenSsh) { & "$PSScriptRoot/Install-Win32OpenSsh.ps1" }
    else {
        try {
            $cap=Get-WindowsCapability -Online -Name 'OpenSSH.Server~~~~0.0.1.0'
            if ($cap.State -ne 'Installed') { Add-WindowsCapability -Online -Name 'OpenSSH.Server~~~~0.0.1.0' | Out-Null }
        } catch {
            Write-Warning ('Windows optional-component installation failed: '+$_.Exception.Message+'. Using the verified Microsoft Win32-OpenSSH package.')
            & "$PSScriptRoot/Install-Win32OpenSsh.ps1"
        }
    }
}
$service=Get-CimInstance Win32_Service -Filter "Name='sshd'"
if (!$service) { throw 'OpenSSH installation did not create the sshd service.' }
$match=[regex]::Match($service.PathName,'^\s*(?:"([^"]+)"|(\S+))')
$serverPath=if($match.Groups[1].Success){$match.Groups[1].Value}else{$match.Groups[2].Value}
$serverPath=[Environment]::ExpandEnvironmentVariables($serverPath)
$sshDirectory=Split-Path $serverPath
$config=Join-Path $env:ProgramData 'ssh\sshd_config'
# The first service start creates the default config and host keys. Patch SFTP
# after that generation as well as for an already configured server.
if (!(Test-Path -LiteralPath $config)) { Start-Service sshd }
$restart=$false
if ((Test-Path $config) -and (Test-Path "$sshDirectory\sftp-server.exe")) {
    $text=[IO.File]::ReadAllText($config)
    $replacement='Subsystem sftp "'+(Join-Path $sshDirectory 'sftp-server.exe').Replace('\','/')+'"'
    $fixed=[regex]::Replace($text,'(?m)^Subsystem\s+sftp\s+sftp-server\.exe\s*$', [Text.RegularExpressions.MatchEvaluator]{param($m) $replacement})
    if ($fixed -cne $text) {
        $backup=$config+'.orca-backup-'+[DateTime]::UtcNow.ToString('yyyyMMddHHmmss')
        [IO.File]::Copy($config,$backup)
        [IO.File]::WriteAllText($config,$fixed,[Text.UTF8Encoding]::new($false))
        & $serverPath -t
        if ($LASTEXITCODE -ne 0) { [IO.File]::Copy($backup,$config,$true);throw 'Invalid SSH configuration; backup restored.' }
        $restart=$true
    }
}
Set-Service sshd -StartupType Automatic
if ($restart -and (Get-Service sshd).Status -eq 'Running') { Restart-Service sshd } else { Start-Service sshd }
$rule=Get-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -ErrorAction SilentlyContinue
if ($rule) { $rule | Set-NetFirewallRule -Enabled True -RemoteAddress $AllowedAddresses }
else { New-NetFirewallRule -Name 'OpenSSH-Server-In-TCP' -DisplayName 'OpenSSH Server (trusted clients)' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 22 -RemoteAddress $AllowedAddresses | Out-Null }
Get-NetTCPConnection -LocalPort 22 -State Listen | Select-Object LocalAddress,LocalPort,State
Get-ChildItem "$env:ProgramData\ssh\ssh_host_*_key.pub" | ForEach-Object { & "$sshDirectory\ssh-keygen.exe" -lf $_.FullName -E sha256 }
Write-Host 'SSH enabled. Existing authentication and default shell configuration were preserved.'
