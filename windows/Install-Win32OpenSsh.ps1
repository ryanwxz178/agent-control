param([string]$PackagePath)
$ErrorActionPreference='Stop'
$principal=[Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
if (!$principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) { throw 'Run in an elevated Windows PowerShell window.' }
if (Get-Service sshd -ErrorAction SilentlyContinue) { Write-Output 'Existing SSH server preserved.'; return }
if (!$PackagePath) {
    $PackagePath=Join-Path $PSScriptRoot 'OpenSSH-Win64.zip'
    if (!(Test-Path -LiteralPath $PackagePath)) { $PackagePath=Join-Path (Split-Path $PSScriptRoot) '.toolchain/OpenSSH-Win64.zip' }
}
if (!(Test-Path -LiteralPath $PackagePath)) { throw 'Verified OpenSSH-Win64.zip is missing. Download the prepared Windows Bridge package or run the download step first.' }
# Microsoft's PowerShell/Win32-OpenSSH 10.0.0.0p2-Preview release asset digest.
$expected='23f50f3458c4c5d0b12217c6a5ddfde0137210a30fa870e98b29827f7b43aba5'
if ((Get-FileHash -LiteralPath $PackagePath -Algorithm SHA256).Hash -ine $expected) { throw 'OpenSSH archive checksum mismatch; installation stopped.' }
$target=Join-Path $env:ProgramFiles 'OpenSSH-AgentControl'
if (Test-Path -LiteralPath $target) { throw "Installation directory already exists without sshd service: $target. Review it before retrying; no files overwritten." }
New-Item -ItemType Directory -Path $target | Out-Null
$acl=[Security.AccessControl.DirectorySecurity]::new()
$administrators=[Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
$acl.SetOwner($administrators);$acl.SetAccessRuleProtection($true,$false)
foreach ($sid in @($administrators,[Security.Principal.SecurityIdentifier]::new('S-1-5-18'))) {
    $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
}
$acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new('S-1-5-11'),'ReadAndExecute','ContainerInherit,ObjectInherit','None','Allow'))
Set-Acl -LiteralPath $target -AclObject $acl
Expand-Archive -LiteralPath $PackagePath -DestinationPath $target
$bin=Join-Path $target 'OpenSSH-Win64'
$signature=Get-AuthenticodeSignature -FilePath "$bin/sshd.exe"
if ($signature.Status -ne 'Valid' -or $signature.SignerCertificate.Subject -notmatch 'O=Microsoft Corporation') { throw 'OpenSSH Microsoft signature validation failed; service not installed.' }
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$bin/install-sshd.ps1"
if ($LASTEXITCODE -ne 0 -or !(Get-Service sshd -ErrorAction SilentlyContinue)) { throw 'Microsoft install-sshd.ps1 failed; inspect its output before retrying.' }
Write-Output "Microsoft Win32-OpenSSH installed: $bin"
