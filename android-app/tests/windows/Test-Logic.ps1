$ErrorActionPreference='Stop'
$repo=(Resolve-Path "$PSScriptRoot\..\..\..").Path
. "$repo/windows/UiLogic.ps1"
. "$repo/windows/Protocol.ps1"
$profiles=Get-Content "$repo/windows/profiles.json" -Raw -Encoding UTF8 | ConvertFrom-Json
$script:passed=0
function Assert($Value,[string]$Name) { if (!$Value) { throw "FAIL: $Name" }; $script:passed++; Write-Output "PASS $Name" }
Assert ((Get-OptionalUiValue $null $null) -eq '') 'missing .NET Framework UIA property does not abort snapshot'
$emptyEditor=@{value="$([char]0xFEFF)`n今天帮你做些什么？@ 添加上下文，/调用技能与指令"}
Assert ((Get-EditorValue $emptyEditor $profiles.workbuddy) -eq '') 'WorkBuddy UIA placeholder is an empty editor'
Assert ((Get-EditorValue @{value="$([char]0xFEFF)`n测试文本`n"} $profiles.workbuddy) -eq '测试文本') 'WorkBuddy UIA content retains actual input'
Assert ((Get-EditorValue @{value='今天帮你做些什么？任务内容'} $profiles.workbuddy) -eq '今天帮你做些什么？任务内容') 'similar task text is never treated as a placeholder'
$doubaoPlaceholder=@{value="发消息或按住空格说话...`n"}
Assert ((Get-EditorValue $doubaoPlaceholder $profiles.doubao) -eq '') 'CEF editor placeholder is empty input'
Assert (Test-BlankChat @{editor=$doubaoPlaceholder;nodes=@()} $profiles.doubao) 'known placeholder identifies new CEF composer'
Assert (!(Test-BlankChat @{editor=@{value='用户草稿'};nodes=@()} $profiles.doubao)) 'draft never identifies a blank composer'
Assert (Test-AdapterButton @{role='Button';name='';className='p-0 !bg-g-send-msg-btn-bg rounded-full'} $profiles.doubao.send $profiles.doubao.sendClass) 'observed unnamed CEF send button identified by exact class token'
Assert (!(Test-AdapterButton @{role='Button';name='';className='hover:!bg-g-send-msg-btn-hover-bg'} $profiles.doubao.send $profiles.doubao.sendClass)) 'similar hover-only styling cannot identify sending'
Assert (!(Test-AdapterButton @{role='Group';name='发送';className='!bg-g-send-msg-btn-bg'} $profiles.doubao.send $profiles.doubao.sendClass)) 'non-button cannot trigger sending'
function Node($Index,$Parent,$Role,$Name,$Value='') { return [pscustomobject]@{index=$Index;parent=$Parent;role=$Role;name=$Name;value=$Value;enabled=$true;offscreen=$false;automationId=''} }
function Tree {
    return @((Node 0 -1 'Window' ''),(Node 1 0 'Document' ''),(Node 2 1 'Edit' '' '你好'),(Node 3 2 'Text' '你好'),(Node 4 1 'Text' '你好'),(Node 5 1 'Text' '测试成功'),(Node 6 1 'Button' '朗读'))
}
$teleNodes=@((Node 0 -1 'Window' ''),(Node 1 0 'Document' 'TeleAgent'),(Node 2 1 'Edit' '' ''),(Node 3 1 'Text' '手机请求'),(Node 4 1 'Text' '2026-10-12 09:18:22'),(Node 5 1 'Button' '复制'),(Node 6 1 'Button' '已完成本轮思考与执行 09:18 完成 耗时 7.0 秒'),(Node 7 1 'Text' 'TELE-OK'),(Node 8 1 'Button' '仅复制文本'),(Node 9 1 'Button' '带格式复制'),(Node 10 1 'Text' '2026-10-12 09:18:29'))
$teleNodes[1].automationId='RootWebArea'
$teleReply=Get-ReplyState $teleNodes $profiles.teleagent '手机请求'
Assert ($teleReply.sent -and $teleReply.complete -and $teleReply.text -eq 'TELE-OK') 'TeleAgent observed completed header and copy toolbar isolate the answer from timestamps'
$teleReply=Get-ReplyState $teleNodes[0..7] $profiles.teleagent '手机请求'
Assert (!$teleReply.complete) 'TeleAgent completed header without reply toolbar cannot finish'
$teleBusy=@($teleNodes)+(Node 11 1 'Button' '停止执行')
$teleReply=Get-ReplyState $teleBusy $profiles.teleagent '手机请求'
Assert (!$teleReply.complete) 'TeleAgent generation remains incomplete even with previous completion controls'
$teleReply=Get-ReplyState $teleNodes $profiles.teleagent '其他请求'
Assert (!$teleReply.sent -and !$teleReply.complete) 'TeleAgent previous conversation never satisfies a different phone request'
$nodes=Tree
$reply=Get-ReplyState $nodes $profiles.doubao '你好'
Assert ($reply.sent -and $reply.complete -and $reply.text -eq '测试成功') 'confirmed user message and completed reply'
$stringParents=Tree;$stringParents[0].parent='-1'
$reply=Get-ReplyState $stringParents $profiles.doubao '你好'
Assert $reply.complete 'string negative parent terminates numerically on Windows cultures'
$cycle=Tree;$cycle[0].parent=0
$rejected=$false;try{Get-ReplyState $cycle $profiles.doubao '你好'|Out-Null}catch{$rejected=$true}
Assert $rejected 'cyclic parent chain rejected without looping'
$dateNodes=@((Node 0 -1 'Window' ''),(Node 1 0 'Document' ''),(Node 2 1 'Edit' '' ''),(Node 3 2 'Text' ''),(Node 4 1 'Text' '你好'),(Node 5 1 'Text' ''),(Node 6 5 'Text' '今天 19:07'),(Node 7 1 'Text' '测试成功'),(Node 8 1 'Button' '朗读'))
$dateNodes[5]|Add-Member className "shrink-0 self-center text-14 leading-20 whitespace-nowrap`ntext-dbx-text-secondary mr-14"
$reply=Get-ReplyState $dateNodes $profiles.doubao '你好'
Assert ($reply.complete -and $reply.text -eq '测试成功') 'observed CEF timestamp wrapper excluded from reply'
$dateNodes[7].name='今天 19:07';$reply=Get-ReplyState $dateNodes $profiles.doubao '你好'
Assert ($reply.text -eq '今天 19:07') 'model date text retained outside UI timestamp wrapper'
$draft=@($nodes[0..3])
$reply=Get-ReplyState $draft $profiles.doubao '你好'
Assert (!$reply.sent -and !$reply.complete) 'editor draft is never a sent message'
$nodes=Tree; $nodes[4].parent=0; $nodes[5].parent=0; $nodes[6].parent=0
$reply=Get-ReplyState $nodes $profiles.doubao '你好'
Assert (!$reply.sent) 'sidebar text cannot confirm sending'
$nodes=Tree; $nodes[4].automationId='sidebar-history'
$reply=Get-ReplyState $nodes $profiles.doubao '你好'
Assert (!$reply.sent) 'navigation inside a web document cannot confirm sending'
$nodes=Tree; $nodes+=Node 7 1 'Button' '停止生成'
$reply=Get-ReplyState $nodes $profiles.doubao '你好'
Assert ($reply.sent -and !$reply.complete) 'completion control during generation cannot finish'
$nodes=Tree; $nodes=$nodes[0..5]
$reply=Get-ReplyState $nodes $profiles.doubao '你好'
Assert ($reply.sent -and !$reply.complete) 'text alone does not prove completion'
$nodes=Tree; $nodes[4].name='你'; $nodes[5].name='好'; $nodes[6].index=7
$nodes=@($nodes[0..5]) + (Node 6 1 'Text' '结果') + $nodes[6]
$reply=Get-ReplyState $nodes $profiles.doubao "你`n好"
Assert ($reply.complete -and $reply.text -eq '结果') 'split user message matches without including it in reply'
$nodes=Tree; $nodes[4].name='相似但不同的任务'
$reply=Get-ReplyState $nodes $profiles.doubao '你好'
Assert (!$reply.sent -and !$reply.complete) 'old unrelated reply cannot satisfy current request'
$nodes=Tree; $nodes+=Node 7 1 'Edit' 'search'
$rejected=$false; try { Get-ReplyState $nodes $profiles.doubao '你好' | Out-Null } catch { $rejected=$true }
Assert $rejected 'ambiguous editor fails closed'
$nodes=Tree; $nodes[1].role='Group'
$rejected=$false; try { Get-ReplyState $nodes $profiles.doubao '你好' | Out-Null } catch { $rejected=$true }
Assert $rejected 'unknown transcript scope fails closed'
$nodes=Tree; $nodes[5].name='WorkBuddy';$nodes[6].role='Text';$nodes[6].name='已完成 26s'
$nodes+=Node 7 1 'Group' '';4..6|ForEach-Object{$nodes[$_].parent=7};$nodes+=Node 8 1 'Button' '发送';$nodes+=Node 9 7 'Button' '复制'
$reply=Get-ReplyState $nodes $profiles.workbuddy '你好'
Assert (!$reply.complete) 'WorkBuddy header without answer cannot finish'
$nodes+=(Node 10 7 'Text' 'WIN-TEST-');$nodes+=(Node 11 7 'Text' '1');$nodes+=(Node 12 7 'Button' '复制');$nodes[12]|Add-Member className '_copyButton_1ncbp_1 '
$reply=Get-ReplyState $nodes $profiles.workbuddy '你好'
Assert ($reply.complete -and $reply.text -ceq 'WIN-TEST-1') 'WorkBuddy answer after header joins inline fragments without metadata'
$nodes[10]|Add-Member top 10;$nodes[10]|Add-Member height 15;$nodes[11]|Add-Member top 30
$reply=Get-ReplyState $nodes $profiles.workbuddy '你好'
Assert ($reply.text -ceq "WIN-TEST-`n1") 'WorkBuddy separate visual lines retain newline'
$nodes[8]=Node 8 1 'Button' '停止'
$reply=Get-ReplyState $nodes $profiles.workbuddy '你好'
Assert (!$reply.complete) 'WorkBuddy answer toolbar while busy cannot finish'
$observed=@((Node 0 -1 'Window' ''),(Node 1 0 'Document' ''),(Node 2 1 'Text' '你好'),(Node 3 1 'Group' ''),(Node 4 3 'Group' ''),(Node 5 4 'Text' ([string][char]0xFEFF)),(Node 6 4 'Text' '你'),(Node 7 4 'Text' ([string][char]0xFEFF)),(Node 8 4 'Text' '好'),(Node 9 3 'Text' '已完成 15s'),(Node 10 3 'Text' '正确回复'),(Node 11 3 'Button' '复制'),(Node 12 1 'Edit' '' ''),(Node 13 1 'Button' '发送'))
$observed[11]|Add-Member className '_copyButton_1ncbp_1 '
$reply=Get-ReplyState $observed $profiles.workbuddy '你好'
Assert ($reply.sent -and $reply.complete -and $reply.text -ceq '正确回复') 'observed WorkBuddy header repeats the request outside chat; zero-width text runs do not duplicate it'
$fileA=@{path='C:\work\.agent-control-input\11111111-1111-1111-1111-111111111111-报告.pdf';size=3}
$fileB=@{path='C:\work\.agent-control-input\22222222-2222-2222-2222-222222222222-截图.png';size=4}
$fileNodes=@((Node 0 -1 'Window' ''),(Node 1 0 'Document' ''),(Node 2 1 'Edit' '' ''),(Node 3 2 'Text' '总结附件'),(Node 4 2 'Group' ''),(Node 5 4 'Text' ([IO.Path]::GetFileName($fileA.path))),(Node 6 2 'Group' ''),(Node 7 6 'Text' ([IO.Path]::GetFileName($fileB.path))))
$fileNodes[4]|Add-Member className '_tag_h25vi_19 _fileTag_h25vi_111 _inlineTag_h25vi_149'
$fileNodes[6]|Add-Member className '_tag_h25vi_19 _fileTag_h25vi_111 _inlineTag_h25vi_149'
Assert (Test-ComposerFilesReady $fileNodes $fileNodes[2] $profiles.workbuddy @($fileA,$fileB)) 'two exact inline file tokens confirm composer attachments'
Assert ((Get-ComposerPlainText $fileNodes $fileNodes[2] $profiles.workbuddy) -ceq '总结附件') 'prompt text excludes file tokens without deleting attachments'
Assert (Test-ComposerPrompt $fileNodes $fileNodes[2] $profiles.workbuddy '总结附件') 'attachment composer retains the original prompt'
$fileNodes[3].name='总结 附件'
Assert (!(Test-ComposerPrompt $fileNodes $fileNodes[2] $profiles.workbuddy '总结附件')) 'attachment confirmation retains meaningful spaces instead of accepting an edited prompt'
$fileNodes[3].name='总结附件'
$fileNodes[6].className+=' _uploading_h25vi_51'
Assert (!(Test-ComposerFilesReady $fileNodes $fileNodes[2] $profiles.workbuddy @($fileA,$fileB))) 'processing attachment prevents send readiness'
$fileNodes[6].className='_fileTag_h25vi_111 _failed_h25vi_60'
Assert (!(Test-ComposerFilesReady $fileNodes $fileNodes[2] $profiles.workbuddy @($fileA,$fileB))) 'failed attachment prevents send readiness'
$fileNodes[6].className='_fileTag_h25vi_111';$fileNodes[7].name=[IO.Path]::GetFileName($fileA.path)
Assert (!(Test-ComposerFilesReady $fileNodes $fileNodes[2] $profiles.workbuddy @($fileA,$fileB))) 'duplicate token cannot stand in for a missing file'
$fileNodes[7].name=[IO.Path]::GetFileName($fileB.path);$fileNodes[6].parent=1
Assert (!(Test-ComposerFilesReady $fileNodes $fileNodes[2] $profiles.workbuddy @($fileA,$fileB))) 'transcript or sidebar filenames cannot confirm composer attachments'
$fileNodes[6].parent=2
Assert (!(Test-ComposerFilesReady $fileNodes $fileNodes[2] $profiles.workbuddy @($fileA))) 'unrequested extra attachment prevents send readiness'
$messageFiles=@((Node 0 -1 'Window' ''),(Node 1 0 'Document' ''),(Node 2 1 'Edit' '' ''),(Node 3 1 'Group' ''),(Node 4 3 'Text' '总结附件'),(Node 5 3 'Group' ''),(Node 6 5 'Text' ([IO.Path]::GetFileName($fileA.path))),(Node 7 3 'Button' '复制'),(Node 8 3 'Text' '已完成'),(Node 9 3 'Text' '附件已总结'),(Node 10 3 'Button' '复制'),(Node 11 1 'Button' '发送'))
$messageFiles[5]|Add-Member className '_fileTag_h25vi_111 _readOnly_h25vi_57';$messageFiles[10]|Add-Member className '_copyButton_1ncbp_1'
$fileReply=Get-ReplyState $messageFiles $profiles.workbuddy '总结附件'
Assert ($fileReply.sent -and $fileReply.complete -and $fileReply.text -ceq '附件已总结') 'file message labels do not corrupt exact prompt/reply confirmation'
$pickerNodes=@((Node 0 -1 'Window' 'Open'),(Node 1 0 'Pane' ''),(Node 2 1 'ComboBox' 'File name'),(Node 3 2 'Edit' 'File name'),(Node 4 0 'Edit' 'Search'),(Node 5 0 'Button' '打开(O)'))
$pickerNodes[1].automationId='FileNameControlHost';$pickerNodes[2].automationId='1148';$pickerNodes[3].automationId='1001';$pickerNodes[4].automationId='SearchEditBox';$pickerNodes[5].automationId='1'
$pickerControls=Get-FileDialogControls $pickerNodes
Assert ($pickerControls.edit.index -eq 3 -and $pickerControls.open.index -eq 5) 'nested filename host and ComboBox resolve the same Edit without choosing Search'
$ambiguousPicker=@($pickerNodes)+(Node 6 2 'Edit' 'Other filename')
$rejected=$false;try{Get-FileDialogControls $ambiguousPicker|Out-Null}catch{$rejected=$true}
Assert $rejected 'two editable filename targets fail closed'
$pickerNodes[5].name='Delete'
$rejected=$false;try{Get-FileDialogControls $pickerNodes|Out-Null}catch{$rejected=$true}
Assert $rejected 'id1 with a non-Open semantic name is not clicked through UIA'
if($env:OS -eq 'Windows_NT') {
    $uploadFolder=Join-Path ([IO.Path]::GetTempPath()) ('agent-files-test-'+[Guid]::NewGuid().ToString('N'))
    $inputFolder=Join-Path $uploadFolder '.agent-control-input'
    [IO.Directory]::CreateDirectory($inputFolder)|Out-Null
    try {
        $filePath=Join-Path $inputFolder ([Guid]::NewGuid().ToString()+'-报告.pdf')
        [IO.File]::WriteAllBytes($filePath,[byte[]]@(1,2,3))
        $upload=@{path=$filePath.Replace('\','/');size=3;mime='application/pdf'}
        $resolved=@(Resolve-DesktopFiles @($upload) $profiles.workbuddy)
        Assert ($resolved.Count -eq 1 -and $resolved[0].path -ceq $filePath) 'uploaded local regular file resolves before desktop UI is touched'
        foreach($case in @('wrongSize','traversal','unc','outside','unsupportedApp','unsupportedType','duplicate','tooMany','notArray')) {
            $bad=$upload.Clone();$adapter=$profiles.workbuddy;$items=@($bad)
            switch($case) {
                wrongSize {$bad.size=4}
                traversal {$bad.path=$inputFolder+'\..\.agent-control-input\'+[IO.Path]::GetFileName($filePath)}
                unc {$bad.path='\\server\share\.agent-control-input\'+[IO.Path]::GetFileName($filePath)}
                outside {$bad.path=$uploadFolder+'\'+[IO.Path]::GetFileName($filePath);[IO.File]::WriteAllBytes($bad.path,[byte[]]@(1,2,3))}
                unsupportedApp {$adapter=$profiles.teleagent}
                unsupportedType {$bad.path=Join-Path $inputFolder ([Guid]::NewGuid().ToString()+'-program.exe');[IO.File]::WriteAllBytes($bad.path,[byte[]]@(1,2,3))}
                duplicate {$items=@($bad,$bad)}
                tooMany {$items=@($bad,$bad,$bad,$bad,$bad)}
                notArray {$items=$bad}
            }
            $rejected=$false;try{Resolve-DesktopFiles $items $adapter|Out-Null}catch{$rejected=$true}
            Assert $rejected ('unsafe desktop attachment rejected: '+$case)
        }
    } finally { [IO.Directory]::Delete($uploadFolder,$true) }
}
$id=[Guid]::NewGuid().ToString('N'); $broker=[Guid]::NewGuid().ToString('N'); $now=[DateTime]::UtcNow
$r=@{protocol=1;id=$id;brokerId=$broker;createdUtc=$now.ToString('o');prompt='test'}
Assert-FreshRequest $r $id $broker $now
Assert $true 'fresh broker-bound request accepted'
foreach ($case in @('old','future','restart','id','oversize','empty')) {
    $bad=$r.Clone()
    switch ($case) {
        old { $bad.createdUtc=$now.AddSeconds(-16).ToString('o') }
        future { $bad.createdUtc=$now.AddSeconds(6).ToString('o') }
        restart { $bad.brokerId='previous-instance' }
        id { $bad.id='../escape' }
        oversize { $bad.prompt='x'*85001 }
        empty { $bad.prompt=' ' }
    }
    $rejected=$false; try { Assert-FreshRequest $bad $id $broker $now } catch { $rejected=$true }
    Assert $rejected ("unsafe request rejected: $case")
}
# Execute the production status writer on the host runtime, including WinPS 5.1.
$bridgeSource=Get-Content "$repo/windows/Bridge.ps1" -Raw -Encoding UTF8
$tokens=$null;$parseErrors=$null
$ast=[Management.Automation.Language.Parser]::ParseInput($bridgeSource,[ref]$tokens,[ref]$parseErrors)
$writer=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Save-Status'},$true)
# Normalize separators for .NET on Unix; Windows accepts both separators.
Invoke-Expression ($writer.Extent.Text.Replace('$Root\','$Root/'))
function Get-DesktopHealth { return '' }
$Root=Join-Path ([IO.Path]::GetTempPath()) ('orca-status-test-'+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($Root)|Out-Null
try {
    $utf8=[Text.UTF8Encoding]::new($false);$sid='test';$brokerId='test';$script:activeId='';$script:lastInventory=[DateTime]::UtcNow;$script:apps=@()
    Save-Status
    $first=[IO.File]::ReadAllText("$Root/status.json")
    $script:activeId='running';Save-Status
    $updated=[IO.File]::ReadAllText("$Root/status.json")|ConvertFrom-Json
    Assert $updated.busy 'second heartbeat replaces existing status on host runtime'
    Assert $updated.fileUploads 'new bridge advertises attachment capability so old brokers cannot silently drop files'
    Assert ([IO.File]::ReadAllText("$Root/status.previous") -eq $first) 'previous heartbeat retained during atomic replacement'
} finally { [IO.Directory]::Delete($Root,$true) }
# Compile the P/Invoke helper without invoking Windows APIs on this host.
$desktopSource=Get-Content "$repo/windows/Desktop.ps1" -Raw -Encoding UTF8
$desktopAst=[Management.Automation.Language.Parser]::ParseInput($desktopSource,[ref]$tokens,[ref]$parseErrors)
$conversationWriter=$desktopAst.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Save-DesktopConversation'},$true)
Invoke-Expression $conversationWriter.Extent.Text
$conversationFolder=Join-Path ([IO.Path]::GetTempPath()) ('orca-conversation-test-'+[Guid]::NewGuid().ToString('N'))
try{
    $conversationFile=Join-Path $conversationFolder 'conversation.json'
    Save-DesktopConversation $conversationFile @{status='pending';prompt='中文请求'}
    Save-DesktopConversation $conversationFile @{status='complete';prompt='中文请求'}
    Assert (([IO.File]::ReadAllText($conversationFile,[Text.Encoding]::UTF8)|ConvertFrom-Json).status -eq 'complete') 'desktop completion replaces pending state on Windows PowerShell 5.1'
}finally{if([IO.Directory]::Exists($conversationFolder)){[IO.Directory]::Delete($conversationFolder,$true)}}
$native=[regex]::Match($desktopSource,"(?s)Add-Type -TypeDefinition @'\r?\n(.*?)\r?\n'@").Groups[1].Value
Add-Type -TypeDefinition $native
Assert ($null -ne ('PocketDesktop' -as [type])) 'native Windows API declarations compile'
# Syntax-parse every production PowerShell file, including phone-delivered scripts.
foreach ($file in @((Get-ChildItem "$repo/windows/*.ps1"),(Get-ChildItem "$repo/android-app/app/src/main/assets/*windows*.ps1")) | ForEach-Object { $_ }) {
    $tokens=$null; $errors=$null
    [Management.Automation.Language.Parser]::ParseInput([IO.File]::ReadAllText($file.FullName,[Text.Encoding]::UTF8),[ref]$tokens,[ref]$errors) | Out-Null
    Assert ($errors.Count -eq 0) ("PowerShell syntax: " + $file.Name + ' ' + ($errors.Message -join '; '))
}

# A repeated exact prompt cannot select an arbitrary visible turn.
$duplicate=@(@{index=0;parent=-1;role='Document';name='';automationId='doc_editor'},@{index=1;parent=0;role='Edit';name='';value='';enabled=$true;offscreen=$false},@{index=2;parent=0;role='Text';name='重复请求'},@{index=3;parent=0;role='Text';name='重复请求'})
$rejected=$false
try { Get-ReplyState $duplicate $profiles.doubao '重复请求' | Out-Null } catch { $rejected=$true }
Assert $rejected 'duplicate visible request rejected rather than choosing another turn'

# Execute the production client's payload decoding and queue construction without a broker or UI input.
$clientSource=Get-Content "$repo/android-app/app/src/main/assets/desktop-windows-client.ps1" -Raw -Encoding UTF8
$payload=@{app='teleagent';prompt='续聊当前手机消息';conversationId='phone-one'}|ConvertTo-Json -Compress
$encoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($payload))
$requestLine=[regex]::Match($clientSource,'(?m)^\s+\$request = .*ConvertFrom-Json\r?$').Value
$filesLine=[regex]::Match($clientSource,'(?m)^\s+\$requestFiles=@\(\);.*\r?$').Value
$capabilityLine=[regex]::Match($clientSource,'(?m)^\s+if \(\$requestFiles\.Count -gt 0 -and !\$status\.fileUploads\).*\r?$').Value
$bodyLine=[regex]::Match($clientSource,'(?m)^\s+\$body = @\{.*\}\r?$').Value
Assert ($requestLine -and $filesLine -and $capabilityLine -and $bodyLine) 'production Windows client queue and attachment guards found'
$status=@{brokerId='test-broker'};$id='test-request'
Invoke-Expression $requestLine.Replace('__REQUEST_BASE64__',$encoded)
Invoke-Expression $filesLine
Invoke-Expression $capabilityLine
Invoke-Expression $bodyLine
$queued=$body|ConvertTo-Json -Depth 8 -Compress|ConvertFrom-Json
Assert ($queued.conversationId -eq 'phone-one' -and $queued.prompt -eq '续聊当前手机消息' -and $queued.app -eq 'teleagent') 'production Windows queue preserves phone conversation ID and current prompt'
Assert ($null -ne $queued.files -and $queued.files.Count -eq 0) 'missing attachment field stays an empty array and text chat works with old bridges'
$uploadPayload=@{app='workbuddy';prompt='总结附件';conversationId='phone-files';files=@(@{path='C:/work/.agent-control-input/11111111-1111-1111-1111-111111111111-报告.pdf';name='报告.pdf';size=3;mime='application/pdf'})}|ConvertTo-Json -Depth 8 -Compress
$encoded=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($uploadPayload))
Invoke-Expression $requestLine.Replace('__REQUEST_BASE64__',$encoded)
Invoke-Expression $filesLine
$rejected=$false;try{Invoke-Expression $capabilityLine}catch{$rejected=$true}
Assert $rejected 'current uploads are rejected by old bridge capability before queueing or sending'
$status.fileUploads=$true
Invoke-Expression $capabilityLine
Invoke-Expression $bodyLine
$queued=$body|ConvertTo-Json -Depth 8 -Compress|ConvertFrom-Json
Assert ($queued.files.Count -eq 1 -and $queued.files[0].name -ceq '报告.pdf' -and $queued.files[0].size -eq 3 -and $queued.files[0].path -ceq $request.files[0].path) 'new Windows queue preserves current file metadata and path at JSON depth8'
Write-Output "ALL $script:passed WINDOWS LOGIC CHECKS PASSED"
