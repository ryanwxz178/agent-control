param([string]$Root = (Join-Path $env:LOCALAPPDATA 'OrcaPocket\bridge'))
$ErrorActionPreference='Stop'
if (!$PSBoundParameters.ContainsKey('Root')) { $Root=Join-Path $env:ProgramData ('AgentControl\'+[Security.Principal.WindowsIdentity]::GetCurrent().User.Value+'\bridge') }
$utf8=[Text.UTF8Encoding]::new($false)
if ($env:OS -ne 'Windows_NT' -or [Threading.Thread]::CurrentThread.ApartmentState -ne 'STA') { throw 'Use Windows PowerShell 5.1 -STA in your interactive desktop session.' }
. "$PSScriptRoot\Desktop.ps1"
. "$PSScriptRoot\Protocol.ps1"
$profiles=Get-Content -LiteralPath "$PSScriptRoot\profiles.json" -Raw -Encoding UTF8 | ConvertFrom-Json
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$brokerId=[Guid]::NewGuid().ToString('N')
$activeId=''; $events=$null; $lastInventory=[DateTime]::MinValue; $apps=@()
if (!(Test-Path -LiteralPath $Root)) { throw 'Run Install.ps1 first to create a private queue.' }
# A filesystem lock covers multiple RDP sessions for this user, not merely this process.
try { $lock=[IO.File]::Open("$Root\broker.lock",[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None) }
catch { throw 'Another desktop bridge is already running for this user. Stop it before switching sessions.' }
function Find-Apps {
    $paths=@{}
    $session=[Diagnostics.Process]::GetCurrentProcess().SessionId
    if (Test-Path -LiteralPath "$PSScriptRoot\apps.json") { $paths=Get-Content -LiteralPath "$PSScriptRoot\apps.json" -Raw -Encoding UTF8 | ConvertFrom-Json }
    $found=@()
    foreach ($property in $profiles.PSObject.Properties) {
        $id=$property.Name; $Adapter=$property.Value; $candidates=@()
        if ($paths.$id) { $candidates+= $paths.$id }
        $candidates+= @(Get-Process -Name $Adapter.processNames -ErrorAction SilentlyContinue | Where-Object { $_.SessionId -eq $session } | ForEach-Object { $_.Path })
        foreach ($name in $Adapter.processNames) {
            foreach ($base in @($env:LOCALAPPDATA,"$env:LOCALAPPDATA\Programs",$env:ProgramFiles,${env:ProgramFiles(x86)})) {
                if ($base) { $candidates+= "$base\$name\$name.exe" }
            }
        }
        $path=$candidates | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) -and [IO.Path]::GetExtension($_) -eq '.exe' } | Select-Object -First 1
        if ($path) { $found+= @{id=$id;path=[IO.Path]::GetFullPath($path);fileUploads=($Adapter.fileUploads -eq $true)} }
    }
    return $found
}
function Save-Status {
    if (([DateTime]::UtcNow-$script:lastInventory).TotalSeconds -gt 10 -and !$script:activeId) {
        $script:apps=@(Find-Apps); $script:lastInventory=[DateTime]::UtcNow
    }
    $reason=Get-DesktopHealth
    $status=@{protocol=1;fileUploads=$true;brokerId=$brokerId;userSid=$sid;sessionId=[Diagnostics.Process]::GetCurrentProcess().SessionId;updatedUtc=[DateTime]::UtcNow.ToString('o');ready=(!$reason);reason=$reason;busy=[bool]$script:activeId;apps=@($script:apps)}
    [IO.File]::WriteAllText("$Root\status.tmp",($status|ConvertTo-Json -Depth 5 -Compress),$utf8)
    for ($attempt=0; $attempt -lt 5; $attempt++) {
        try {
            # Windows PowerShell 5.1 binds a null string argument as an empty path.
            if (Test-Path -LiteralPath "$Root\status.json") { [IO.File]::Replace("$Root\status.tmp","$Root\status.json","$Root\status.previous") }
            else { [IO.File]::Move("$Root\status.tmp","$Root\status.json") }
            break
        } catch [IO.IOException] { if ($attempt -eq 4) { throw }; Start-Sleep -Milliseconds 50 }
    }
}
function Pulse([switch]$Ending) {
    Save-Status
    if ($script:activeId) {
        $lease="$Root\$script:activeId.lease"
        if ((!$Ending -and (Test-Path -LiteralPath "$Root\$script:activeId.cancel")) -or !(Test-Path -LiteralPath $lease) -or ([DateTime]::UtcNow-[IO.File]::GetLastWriteTimeUtc($lease)).TotalSeconds -gt 15) { throw '手机连接已中断或取消，停止桌面操作；已发送的电脑任务可能仍在运行' }
        $reason=Get-DesktopHealth
        if ($reason) { throw $reason }
    }
}
function Write-Event([string]$Type,[string]$Text) {
    if ($Text.Length -gt 150000) { $Type='error'; $Text='回复超过大小限制' }
    $script:events.WriteLine((@{type=$Type;text=$Text;message=$Text}|ConvertTo-Json -Compress)); $script:events.Flush()
}
function Save-RequestDiagnostic($Failure) {
    try {
        $diagnostic=@{updatedUtc=[DateTime]::UtcNow.ToString('o');brokerId=$brokerId;errorType=$Failure.Exception.GetType().FullName;message=$Failure.Exception.Message;stack=$Failure.ScriptStackTrace;position=$Failure.InvocationInfo.PositionMessage}
        if('PocketDesktop' -as [type]) { $diagnostic.activation=[PocketDesktop]::ActivationDiagnostic }
        [IO.File]::WriteAllText("$Root\$script:activeId.diagnostic.json",($diagnostic|ConvertTo-Json -Depth 3 -Compress),$utf8)
    } catch { # Diagnostic I/O must not replace the original task error.
    }
}
try {
    Write-Host 'Agent Control Windows Bridge running. Keep this user session signed in and unlocked.'
    while ($true) {
        Pulse
        foreach ($file in @(Get-ChildItem -LiteralPath $Root -Filter '*.request' | Sort-Object LastWriteTimeUtc)) {
            if ($file.BaseName -notmatch '^[0-9a-f]{32}$') { continue }
            $activeId=$file.BaseName
            [IO.File]::Move($file.FullName,"$Root\$activeId.working")
            $stream=[IO.File]::Open("$Root\$activeId.events",[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::ReadWrite)
            $events=[IO.StreamWriter]::new($stream,$utf8)
            try {
                if ($file.Length -gt 500000) { throw '请求超过大小限制' }
                $r=[IO.File]::ReadAllText("$Root\$activeId.working",$utf8)|ConvertFrom-Json
                Assert-FreshRequest $r $activeId $brokerId
                $app=@($apps | Where-Object { $_.id -eq $r.app })
                if ($app.Count -ne 1 -or !$profiles.($r.app)) { throw '未知或未安装的桌面应用' }
                Pulse
                if($r.operation -eq 'endConversation'){Invoke-EndDesktopConversation $app[0] $profiles.($r.app) $r.conversationId}
                else{Invoke-DesktopChat $app[0] $profiles.($r.app) $r.prompt $r.conversationId $r.files}
            } catch { $failure=$_; Save-RequestDiagnostic $failure; Write-Event 'error' $failure.Exception.Message }
            finally {
                $events.Dispose(); $events=$null
                # Remove completed request payloads. Keep events for 24h for reconnection diagnosis.
                [IO.File]::Delete("$Root\$activeId.working")
                $activeId=''
            }
        }
        foreach ($old in @(Get-ChildItem -LiteralPath $Root | Where-Object { $_.Name -match '^[0-9a-f]{32}\.(events|lease|cancel|tmp|working|diagnostic\.json)$' -and $_.LastWriteTimeUtc -lt [DateTime]::UtcNow.AddDays(-1) })) { [IO.File]::Delete($old.FullName) }
        Start-Sleep -Milliseconds 300
    }
} catch {
    [IO.File]::WriteAllText("$Root\last-error.txt",$_.Exception.ToString(),$utf8)
    throw
} finally { if ($events) { $events.Dispose() }; $lock.Dispose() }
