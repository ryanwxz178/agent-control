$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'Not a Windows host' }
[Console]::WriteLine('ORCA_OS|Windows')
[Console]::WriteLine('ORCA_HOME|' + $env:USERPROFILE)
$env:Path = "$env:APPDATA\npm;$env:USERPROFILE\.local\bin;$env:LOCALAPPDATA\Microsoft\WinGet\Links;" + $env:Path
foreach ($spec in @(@('codex','codex',@('codex')), @('claude','claude',@('claude')), @('deepseek','dsh',@('dsh')), @('codebuddy','buddy',@('codebuddy','cbc')))) {
    foreach ($name in $spec[2]) {
        $cmd = Get-Command $name -CommandType Application,ExternalScript -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($cmd -and (Test-Path -LiteralPath $cmd.Source -PathType Leaf)) {
            [Console]::WriteLine(('ORCA_AGENT|{0}|{1}|{2}' -f $spec[0],$spec[1],$cmd.Source)); break
        }
    }
}
# Only a live interactive broker can advertise desktop apps. CLI use needs no broker.
$statusFile = Join-Path $env:LOCALAPPDATA 'OrcaPocket\bridge\status.json'
if (Test-Path -LiteralPath $statusFile) {
    try {
        $status = Get-Content -LiteralPath $statusFile -Raw -Encoding UTF8 | ConvertFrom-Json
        if ($status.protocol -eq 1 -and $status.userSid -eq [Security.Principal.WindowsIdentity]::GetCurrent().User.Value -and
            ([DateTime]::UtcNow - [DateTime]::Parse($status.updatedUtc).ToUniversalTime()).TotalSeconds -lt 15) {
            foreach ($app in $status.apps) {
                if ($app.id -in @('workbuddy','teleagent','doubao','deepseek','claude','codex') -and $app.path -and (Test-Path -LiteralPath $app.path -PathType Leaf)) {
                    [Console]::WriteLine(('ORCA_APP|{0}|{0}' -f $app.id))
                }
            }
        }
    } catch { # Missing/stale broker never masquerades as a desktop agent.
    }
}
[Console]::WriteLine('ORCA_DETECTION_DONE')
