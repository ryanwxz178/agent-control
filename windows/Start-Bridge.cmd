@echo off
powershell.exe -NoLogo -NoProfile -STA -ExecutionPolicy Bypass -Command "$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value; $p=Join-Path $env:ProgramData ('AgentControl\'+$sid+'\bin\Bridge.ps1'); if(Test-Path -LiteralPath $p){& $p}"
pause
