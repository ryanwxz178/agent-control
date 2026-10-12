$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Not a Windows host' }
[Console]::WriteLine('ORCA_OS|Windows')
[Console]::WriteLine('ORCA_HOME|' + $env:USERPROFILE)
$env:Path = "$env:APPDATA\npm;$env:USERPROFILE\.local\bin;$env:LOCALAPPDATA\Microsoft\WinGet\Links;" + $env:Path
$cliFound=@{}
$node=Get-Command node -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
$nodePath=if($node){$node.Source}else{Join-Path $env:ProgramFiles 'nodejs\node.exe'}
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
$sharedStatus = Join-Path $env:ProgramData ('AgentControl\'+[Security.Principal.WindowsIdentity]::GetCurrent().User.Value+'\bridge\status.json')
if (Test-Path -LiteralPath $sharedStatus) { $statusFile=$sharedStatus }
if (Test-Path -LiteralPath $statusFile) {
    try {
        $status = Get-Content -LiteralPath $statusFile -Raw -Encoding UTF8 | ConvertFrom-Json
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
                        }
                    }
                }
            }
        }
    } catch { # Missing/stale broker never masquerades as a desktop agent.
    }
}
[Console]::WriteLine('ORCA_DETECTION_DONE')
