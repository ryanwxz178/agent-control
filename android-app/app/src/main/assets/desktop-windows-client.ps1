param([switch]$ProbeOnly)
$ErrorActionPreference = 'Stop'
$utf8 = [Text.UTF8Encoding]::new($false)
function Resolve-BrokerRoot([string]$SharedRoot,[string]$LegacyRoot) {
    # Heartbeat replacement can briefly hide status.json. The installed queue
    # directory stays present; never switch an installed broker to the old queue.
    if ([IO.Directory]::Exists($SharedRoot)) { return $SharedRoot }
    return $LegacyRoot
}
$legacyRoot = Join-Path $env:LOCALAPPDATA 'OrcaPocket\bridge'
$sharedRoot = Join-Path $env:ProgramData ('AgentControl\'+[Security.Principal.WindowsIdentity]::GetCurrent().User.Value+'\bridge')
$root = Resolve-BrokerRoot $sharedRoot $legacyRoot
function Read-Broker {
    $s=$null
    for($attempt=0;$attempt -lt 8;$attempt++) {
        $statusStream=$null;$statusReader=$null
        try {
            $statusStream=[IO.File]::Open("$root\status.json",[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
            $statusReader=[IO.StreamReader]::new($statusStream,$utf8)
            $s=$statusReader.ReadToEnd() | ConvertFrom-Json
            if($null -eq $s){throw [IO.InvalidDataException]::new('桌面桥接状态为空')}
            break
        } catch {
            if($attempt -eq 7){
                $failure=$_.Exception
                while($failure.InnerException){$failure=$failure.InnerException}
                if($failure -is [IO.FileNotFoundException] -or $failure -is [IO.DirectoryNotFoundException]) {
                    throw ('Windows 桌面桥接未运行。请在已登录的 Windows 桌面启动 Agent Control Bridge，然后在手机重新连接。状态路径：'+$root+'\status.json')
                }
                throw ('Windows 桌面桥接状态暂时无法读取，请稍后重试。状态路径：'+$root+'\status.json；'+$failure.Message)
            }
            Start-Sleep -Milliseconds 75
        } finally {
            if($statusReader){$statusReader.Dispose()}elseif($statusStream){$statusStream.Dispose()}
        }
    }
    if ($s.protocol -ne 1 -or $s.userSid -ne [Security.Principal.WindowsIdentity]::GetCurrent().User.Value -or
        ([DateTime]::UtcNow - [DateTime]::Parse($s.updatedUtc).ToUniversalTime()).TotalSeconds -gt 15) { throw '桌面桥接已离线或不属于当前 SSH 用户。' }
    if (!$s.ready) { throw ('Windows 桌面不可用：' + $s.reason + '。请登录并解锁对应桌面；云电脑需要保持可交互的用户会话。') }
    return $s
}
if($ProbeOnly) {
    # Read-only diagnostics stop before creating a lease, decoding a request,
    # or writing anything to the desktop queue. All broker checks still apply.
    [Console]::OutputEncoding=$utf8
    try {
        $probe=Read-Broker
        [Console]::WriteLine((@{type='desktop.broker';root=$root;userSid=$probe.userSid;brokerId=$probe.brokerId}|ConvertTo-Json -Compress))
    } catch {
        [Console]::WriteLine((@{type='error';message=$_.Exception.Message}|ConvertTo-Json -Compress))
        exit 1
    }
    return
}
$id = [Guid]::NewGuid().ToString('N')
$lease = "$root\$id.lease"
$cancel = "$root\$id.cancel"
$outputFile = "$root\$id.events"
try {
    $status = Read-Broker
    if ($status.busy) { throw 'Windows 桌面正在执行其他任务，请完成后再发送。' }
    $request = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String('__REQUEST_BASE64__')) | ConvertFrom-Json
    $requestFiles=@();if($null -ne $request.files){$requestFiles=@($request.files)}
    if ($requestFiles.Count -gt 0 -and !$status.fileUploads) { throw '电脑上的 Windows 桌面桥接需要更新并重启，才能添加手机上传的附件；本条消息尚未发送。' }
    if ($request.app -notin @($status.apps.id)) { throw '当前桌面桥接没有检测到该应用，请在手机重新连接。' }
    $body = @{protocol=1; id=$id; app=$request.app; prompt=$request.prompt; conversationId=$request.conversationId; files=$requestFiles; brokerId=$status.brokerId; createdUtc=[DateTime]::UtcNow.ToString('o')}
    [IO.File]::WriteAllText($lease,'',$utf8)
    [IO.File]::WriteAllText("$root\$id.tmp",($body | ConvertTo-Json -Depth 8 -Compress),$utf8)
    [IO.File]::Move("$root\$id.tmp","$root\$id.request")
    $start = [DateTime]::UtcNow
    $reader = $null
    $pending = ''
    while (([DateTime]::UtcNow - $start).TotalSeconds -lt 620) {
        [IO.File]::SetLastWriteTimeUtc($lease,[DateTime]::UtcNow)
        if (!$reader -and (Test-Path -LiteralPath $outputFile)) {
            $stream = [IO.File]::Open($outputFile,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
            $reader = [IO.StreamReader]::new($stream,$utf8)
        }
        if ($reader) {
            $pending += $reader.ReadToEnd()
            if ($pending.Length -gt 2000000) { throw '桌面输出超过限制，状态未知' }
            while (($newline = $pending.IndexOf("`n")) -ge 0) {
                $line=$pending.Substring(0,$newline).TrimEnd("`r")
                $pending=$pending.Substring($newline+1)
                if ($line.Length -gt 262144) { throw '桌面事件过大，状态未知' }
                $event = $line | ConvertFrom-Json
                [Console]::WriteLine($line)
                if ($event.type -eq 'desktop.done' -or $event.type -eq 'desktop.cancelled' -or $event.type -eq 'error') { return }
            }
        }
        # Do not replay on a broker restart, lost session or broken transport.
        $current = Read-Broker
        if ($current.brokerId -ne $status.brokerId) { throw '桌面桥接已重启，执行状态未知；不会重发任务。' }
        Start-Sleep -Milliseconds 300
    }
    throw '桌面任务等待超时，执行状态未知；请在电脑确认。'
} catch {
    [Console]::WriteLine((@{type='error';message=$_.Exception.Message} | ConvertTo-Json -Compress))
} finally {
    if ($reader) { $reader.Dispose() }
    if (Test-Path -LiteralPath $lease) { [IO.File]::WriteAllText($cancel,'',$utf8) }
}
