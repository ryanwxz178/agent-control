param([Parameter(Mandatory=$true)][string[]]$AllowedAddresses,[switch]$UseWin32OpenSsh,[string]$LogPath)
$ErrorActionPreference='Stop'
$principal=[Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Open Windows PowerShell as Administrator, then run this script with -AllowedAddresses specifying your trusted phone subnet.' }
if ($LogPath) { Start-Transcript -Path $LogPath -Force | Out-Null }
try {
& "$PSScriptRoot/Enable-Ssh.ps1" -AllowedAddresses $AllowedAddresses -UseWin32OpenSsh:$UseWin32OpenSsh
& "$PSScriptRoot/Install.ps1" -NoStart
$bin=Join-Path $env:ProgramData ('AgentControl\'+[Security.Principal.WindowsIdentity]::GetCurrent().User.Value+'\bin')
if ((Test-Path "$PSScriptRoot/apps.json") -and !(Test-Path "$bin/apps.json")) { Copy-Item "$PSScriptRoot/apps.json" "$bin/apps.json" }
Write-Output 'SSH and bridge files installed. Start Start-Bridge.cmd from your normal, non-administrator desktop session.'
Write-Output ('SSH user: '+[Security.Principal.WindowsIdentity]::GetCurrent().Name)
Get-NetIPAddress -AddressFamily IPv4 | Where-Object { $_.IPAddress -notmatch '^(127\.|169\.254\.)' } | Select-Object IPAddress,InterfaceAlias
Write-Host 'AGENT_CONTROL_SETUP_COMPLETE'
} finally { if ($LogPath) { Stop-Transcript | Out-Null } }
