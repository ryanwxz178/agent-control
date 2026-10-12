$ErrorActionPreference='Stop'
$conversation='__CONVERSATION__'
$root=Join-Path $env:ProgramData ('AgentControl/'+[Security.Principal.WindowsIdentity]::GetCurrent().User.Value+'/bridge')
if(!(Test-Path -LiteralPath $root)){$root=Join-Path $env:LOCALAPPDATA 'OrcaPocket/bridge'}
$pending=$false
foreach($file in @(Get-ChildItem -LiteralPath $root -Filter '*.working' -ErrorAction SilentlyContinue)) {
    $request=[IO.File]::ReadAllText($file.FullName,[Text.Encoding]::UTF8) | ConvertFrom-Json
    if($request.conversationId -cne $conversation){continue}
    if($file.BaseName -notmatch '^[0-9a-f]{32}$'){throw '桌面请求身份无效'}
    if($request.operation -eq 'endConversation'){[IO.File]::SetLastWriteTimeUtc((Join-Path $root ($file.BaseName+'.lease')),[DateTime]::UtcNow)}
    else{[IO.File]::WriteAllText((Join-Path $root ($file.BaseName+'.cancel')),'',[Text.UTF8Encoding]::new($false))}
    $pending=$true
}
if($pending){[Console]::WriteLine('DESKTOP_END_PENDING')}else{
    $states=Join-Path $env:USERPROFILE '.agent-control/desktop-conversations'
    foreach($stateFile in @(Get-ChildItem -LiteralPath $states -Filter ('*-'+$conversation+'.json') -ErrorAction SilentlyContinue)) {
        $state=[IO.File]::ReadAllText($stateFile.FullName,[Text.Encoding]::UTF8) | ConvertFrom-Json
        if($state.conversationId -ceq $conversation -and $state.status -notin @('complete','ended')){
            if($state.app -notmatch '^[a-zA-Z0-9_-]+$' -or !$state.prompt){throw '原桌面会话绑定无效'}
            $marker=Join-Path $root ('end-'+$state.app+'-'+$conversation+'.json')
            if(Test-Path -LiteralPath $marker){
                $ending=[IO.File]::ReadAllText($marker,[Text.Encoding]::UTF8)|ConvertFrom-Json
                $eventsFile=Join-Path $root ($ending.id+'.events')
                if(Test-Path -LiteralPath $eventsFile){
                    $stream=[IO.File]::Open($eventsFile,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
                    $reader=[IO.StreamReader]::new($stream,[Text.Encoding]::UTF8)
                    try{$lines=$reader.ReadToEnd() -split "`n"}finally{$reader.Dispose()}
                    foreach($line in $lines){if($line.Trim()){try{$event=$line|ConvertFrom-Json}catch{continue};if($event.type -eq 'error'){[IO.File]::Delete($marker);throw $event.message}}}
                }
                if(!(Test-Path -LiteralPath (Join-Path $root ($ending.id+'.request'))) -and !(Test-Path -LiteralPath (Join-Path $root ($ending.id+'.working')))){throw '原桌面会话未确认结束，请在电脑查看后重试'}
                [IO.File]::SetLastWriteTimeUtc((Join-Path $root ($ending.id+'.lease')),[DateTime]::UtcNow)
            }else{
                $statusStream=[IO.File]::Open((Join-Path $root 'status.json'),[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
                $statusReader=[IO.StreamReader]::new($statusStream,[Text.Encoding]::UTF8)
                try{$status=$statusReader.ReadToEnd()|ConvertFrom-Json}finally{$statusReader.Dispose()}
                if(!$status.ready -or $status.busy -or $status.userSid -cne [Security.Principal.WindowsIdentity]::GetCurrent().User.Value -or ([DateTime]::UtcNow-[DateTime]::Parse($status.updatedUtc).ToUniversalTime()).TotalSeconds -gt 15){throw '桌面桥接不可用，不能确认原会话结束'}
                $id=[Guid]::NewGuid().ToString('N')
                $body=@{protocol=1;id=$id;app=$state.app;prompt=$state.prompt;conversationId=$conversation;operation='endConversation';brokerId=$status.brokerId;createdUtc=[DateTime]::UtcNow.ToString('o')}
                [IO.File]::WriteAllText($marker,(@{id=$id}|ConvertTo-Json -Compress),[Text.UTF8Encoding]::new($false))
                [IO.File]::WriteAllText((Join-Path $root ($id+'.lease')),'',[Text.UTF8Encoding]::new($false))
                $temporary=Join-Path $root ($id+'.tmp')
                [IO.File]::WriteAllText($temporary,($body|ConvertTo-Json -Compress),[Text.UTF8Encoding]::new($false))
                [IO.File]::Move($temporary,(Join-Path $root ($id+'.request')))
            }
            $pending=$true
        }
    }
    if($pending){[Console]::WriteLine('DESKTOP_END_PENDING')}else{[Console]::WriteLine('DESKTOP_END_IDLE')}
}
