$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Not a Windows host' }
[Console]::WriteLine('ORCA_OS|Windows')
[Console]::WriteLine('ORCA_HOME|' + $env:USERPROFILE)
$env:Path = "$env:APPDATA\npm;$env:USERPROFILE\.local\bin;$env:LOCALAPPDATA\Microsoft\WinGet\Links;" + $env:Path
$cliFound=@{}
$node=Get-Command node -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
$nodePath=if($node){$node.Source}else{Join-Path $env:ProgramFiles 'nodejs\node.exe'}
function Write-TeleAgentCli([string]$DesktopPath) {
    if($cliFound['teleagent'] -or !$DesktopPath -or $DesktopPath -match '[\x00-\x1f|]') { return }
    if(![IO.Path]::IsPathRooted($DesktopPath) -or [IO.Path]::GetFileName($DesktopPath) -ine 'TeleAgent.exe') { return }
    $resources=Join-Path ([IO.Path]::GetDirectoryName($DesktopPath)) 'resources'
    $backend=Join-Path $env:USERPROFILE '.local\share\TeleAgent\runtimes\super-agent-code\bin\TeleAgent.exe'
    if((Test-Path -LiteralPath $DesktopPath -PathType Leaf) -and (Test-Path -LiteralPath $backend -PathType Leaf) -and (Test-Path -LiteralPath $nodePath -PathType Leaf) -and (Test-Path -LiteralPath (Join-Path $resources 'app.asar') -PathType Leaf)) {
        [Console]::WriteLine(('ORCA_AGENT|teleagent|teleagent|{0}|{1}' -f $nodePath,$DesktopPath))
        $cliFound['teleagent']=$true
    }
}
function Get-TeleAgentInstalledPaths {
    # NSIS records and named Start Menu shortcuts also exist without a GUI broker.
    # Read metadata only; never execute uninstall commands or shortcut arguments.
    foreach($registryPath in @('HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*','HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\*','HKLM:\Software\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall\*')) {
        foreach($record in @(Get-ItemProperty -Path $registryPath -ErrorAction SilentlyContinue | Where-Object { $_.DisplayName -match '^TeleAgent(?:\s|$)' })) {
            try {
                if($record.InstallLocation) { Join-Path $record.InstallLocation 'TeleAgent.exe' }
                if($record.DisplayIcon) { ($record.DisplayIcon -replace ',\s*-?\d+\s*$','').Trim().Trim('"') }
                if($record.UninstallString) {
                    $command=[string]$record.UninstallString
                    $match=[regex]::Match($command,'^\s*(?:"([^"]+\.exe)"|(.+?\.exe))(?:\s|$)')
                    if($match.Success) {
                        $uninstaller=if($match.Groups[1].Success){$match.Groups[1].Value}else{$match.Groups[2].Value}
                        Join-Path ([IO.Path]::GetDirectoryName($uninstaller)) 'TeleAgent.exe'
                    }
                }
            } catch { # Ignore a malformed or partially removed install record.
            }
        }
    }
    try {
        $shortcutReader=New-Object -ComObject WScript.Shell
        foreach($menu in @((Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs'),(Join-Path $env:ProgramData 'Microsoft\Windows\Start Menu\Programs'))) {
            foreach($relative in @('TeleAgent.lnk','TeleAgent\TeleAgent.lnk')) {
                $shortcut=Join-Path $menu $relative
                if(Test-Path -LiteralPath $shortcut -PathType Leaf) { $shortcutReader.CreateShortcut($shortcut).TargetPath }
            }
        }
    } catch { # A missing COM shortcut reader does not prevent registry discovery.
    }
}
foreach ($spec in @(@('codex','codex',@('codex')), @('claude','claude',@('claude')), @('deepseek','dsh',@('dsh')), @('codebuddy','buddy',@('codebuddy','cbc')))) {
    foreach ($name in $spec[2]) {
        $cmd = Get-Command $name -CommandType Application,ExternalScript -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($cmd -and (Test-Path -LiteralPath $cmd.Source -PathType Leaf)) {
            $entry=$cmd.Source
            if($spec[0] -eq 'claude' -and [IO.Path]::GetExtension($entry) -in @('.ps1','.cmd')){
                # Some npm installations leave the Bun placeholder in bin/claude.exe.
                # The platform package contains the actual compiled Claude Code program.
                $native=Join-Path ([IO.Path]::GetDirectoryName($entry)) 'node_modules\@anthropic-ai\claude-code\node_modules\@anthropic-ai\claude-code-win32-x64\claude.exe'
                if(Test-Path -LiteralPath $native -PathType Leaf){$entry=$native}
            }
            [Console]::WriteLine(('ORCA_AGENT|{0}|{1}|{2}' -f $spec[0],$spec[1],$entry)); $cliFound[$spec[0]]=$true; break
        }
    }
}
# Only a live interactive broker can advertise desktop apps. CLI use needs no broker.
$statusFile = Join-Path $env:LOCALAPPDATA 'OrcaPocket\bridge\status.json'
$sharedRoot = Join-Path $env:ProgramData ('AgentControl\'+[Security.Principal.WindowsIdentity]::GetCurrent().User.Value+'\bridge')
# The directory is stable; replacing a heartbeat must not select a different broker.
if ([IO.Directory]::Exists($sharedRoot)) { $statusFile=Join-Path $sharedRoot 'status.json' }
    try {
        for($statusAttempt=0;$statusAttempt -lt 8;$statusAttempt++) {
            $statusStream=$null;$statusReader=$null
            try {
                $statusStream=[IO.File]::Open($statusFile,[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
                $statusReader=[IO.StreamReader]::new($statusStream,[Text.Encoding]::UTF8)
                $status=$statusReader.ReadToEnd() | ConvertFrom-Json
                if($null -eq $status){throw '桌面状态尚未写入完整'}
                break
            } catch { if($statusAttempt -eq 7){throw};Start-Sleep -Milliseconds 75 }
            finally { if($statusReader){$statusReader.Dispose()}elseif($statusStream){$statusStream.Dispose()} }
        }
        if ($status.protocol -eq 1 -and $status.userSid -eq [Security.Principal.WindowsIdentity]::GetCurrent().User.Value) {
            $desktopLive=([DateTime]::UtcNow - [DateTime]::Parse($status.updatedUtc).ToUniversalTime()).TotalSeconds -lt 15
            foreach ($app in $status.apps) {
                if ($app.id -in @('workbuddy','teleagent','doubao','deepseek','claude','codex') -and $app.path -and (Test-Path -LiteralPath $app.path -PathType Leaf)) {
                    if ($app.id -eq 'codex' -and [IO.Path]::GetFileName($app.path) -eq 'codex.exe') {
                        [Console]::WriteLine(('ORCA_AGENT|codex|codex|{0}' -f $app.path))
                    } else {
                        if($desktopLive){[Console]::WriteLine(('ORCA_APP|{0}|{0}' -f $app.id))}
                        $resources=Join-Path ([IO.Path]::GetDirectoryName($app.path)) 'resources'
                        if($app.id -eq 'workbuddy'){
                            $entry=Join-Path $resources 'app.asar.unpacked\cli\bin\codebuddy'
                            if((Test-Path -LiteralPath $entry -PathType Leaf) -and (Test-Path -LiteralPath $nodePath -PathType Leaf)){
                                [Console]::WriteLine(('ORCA_AGENT|workbuddy|buddy|{0}|{1}' -f $nodePath,$entry))
                            }
                        }elseif($app.id -eq 'deepseek' -and !$cliFound['deepseek']){
                            $entry=Join-Path $resources 'runtime\cli\bin\dsh.cmd'
                            if(Test-Path -LiteralPath $entry -PathType Leaf){[Console]::WriteLine(('ORCA_AGENT|deepseek|dsh|{0}' -f $entry))}
                        }elseif($app.id -eq 'teleagent'){
                            Write-TeleAgentCli $app.path
                        }
                    }
                }
            }
        }
    } catch { # Missing/stale broker never masquerades as a desktop agent.
    }
if(!$cliFound['teleagent']) {
    foreach($installedPath in @(Get-TeleAgentInstalledPaths | Select-Object -Unique)) {
        try { Write-TeleAgentCli $installedPath } catch { # Ignore malformed metadata from a stale install record.
        }
    }
}
[Console]::WriteLine('ORCA_DETECTION_DONE')
