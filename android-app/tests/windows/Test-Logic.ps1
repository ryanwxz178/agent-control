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
$reply=Get-ReplyState $nodes $profiles.workbuddy '你好'
Assert (!$reply.complete) 'WorkBuddy header without answer cannot finish'
$nodes+=(Node 7 1 'Text' 'WIN-TEST-');$nodes+=(Node 8 1 'Text' '1');$nodes+=(Node 9 1 'Button' '复制');$nodes[9]|Add-Member className '_copyButton_1ncbp_1 '
$reply=Get-ReplyState $nodes $profiles.workbuddy '你好'
Assert ($reply.complete -and $reply.text -ceq 'WIN-TEST-1') 'WorkBuddy answer after header joins inline fragments without metadata'
$nodes[7]|Add-Member top 10;$nodes[7]|Add-Member height 15;$nodes[8]|Add-Member top 30
$reply=Get-ReplyState $nodes $profiles.workbuddy '你好'
Assert ($reply.text -ceq "WIN-TEST-`n1") 'WorkBuddy separate visual lines retain newline'
$nodes+=Node 10 1 'Button' '停止'
$reply=Get-ReplyState $nodes $profiles.workbuddy '你好'
Assert (!$reply.complete) 'WorkBuddy answer toolbar while busy cannot finish'
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
    Assert ([IO.File]::ReadAllText("$Root/status.previous") -eq $first) 'previous heartbeat retained during atomic replacement'
} finally { [IO.Directory]::Delete($Root,$true) }
# Compile the P/Invoke helper without invoking Windows APIs on this host.
$desktopSource=Get-Content "$repo/windows/Desktop.ps1" -Raw -Encoding UTF8
$native=[regex]::Match($desktopSource,"(?s)Add-Type -TypeDefinition @'\r?\n(.*?)\r?\n'@").Groups[1].Value
Add-Type -TypeDefinition $native
Assert ($null -ne ('PocketDesktop' -as [type])) 'native Windows API declarations compile'
# Syntax-parse every production PowerShell file, including phone-delivered scripts.
foreach ($file in @((Get-ChildItem "$repo/windows/*.ps1"),(Get-ChildItem "$repo/android-app/app/src/main/assets/*windows*.ps1")) | ForEach-Object { $_ }) {
    $tokens=$null; $errors=$null
    [Management.Automation.Language.Parser]::ParseFile($file.FullName,[ref]$tokens,[ref]$errors) | Out-Null
    Assert ($errors.Count -eq 0) ("PowerShell syntax: " + $file.Name + ' ' + ($errors.Message -join '; '))
}
Write-Output "ALL $script:passed WINDOWS LOGIC CHECKS PASSED"

# A repeated exact prompt cannot select an arbitrary visible turn.
$duplicate=@(@{index=0;parent=-1;role='Document';name='';automationId='doc_editor'},@{index=1;parent=0;role='Edit';name='';value='';enabled=$true;offscreen=$false},@{index=2;parent=0;role='Text';name='重复请求'},@{index=3;parent=0;role='Text';name='重复请求'})
$rejected=$false
try { Get-ReplyState $duplicate $profiles.doubao '重复请求' | Out-Null } catch { $rejected=$true }
Assert $rejected 'duplicate visible request rejected rather than choosing another turn'
