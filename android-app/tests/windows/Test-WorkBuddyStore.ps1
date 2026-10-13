$ErrorActionPreference='Stop'
$repo=(Resolve-Path "$PSScriptRoot/../../..").Path
. "$repo/windows/WorkBuddyStore.ps1"
$script:passed=0
function Assert($Value,[string]$Name) { if (!$Value) { throw "FAIL: $Name" }; $script:passed++; Write-Output "PASS $Name" }
$fixtureRoot=Join-Path ([IO.Path]::GetTempPath()) ('agent-control-store-test-'+[Guid]::NewGuid().ToString('N'))
$script:fixtureFiles=New-Object 'Collections.Generic.List[string]'
$script:fixtureDirectories=New-Object 'Collections.Generic.List[string]'
[IO.Directory]::CreateDirectory($fixtureRoot)|Out-Null
$script:fixtureDirectories.Add($fixtureRoot)
$now=[DateTimeOffset]::UtcNow
$prompt="Explain the picture`n`n[Agent Control request 11111111-2222-3333-4444-555555555555]"
$markdown="## Basic information`n`n- **Price**: 6000`n- Condition: nearly new`n`n> A quote`n`n``````js`nconst value = 1;`n```````n`n[Link](https://example.org)"
function Message($Id,$Role,$Text,$Parent='',$Request='request-1',$Status='completed',$Session='session-1') {
    return @{type='message';id=$Id;sessionId=$Session;role=$Role;parentId=$Parent;providerData=@{conversationRequestId=$Request};status=$Status;content=@(@{type=$(if($Role -eq 'user'){'input_text'}else{'output_text'});text=$Text})}
}
function Tool($Id,$Type,$Status='',$Parent='answer-1',$Request='request-1',$Call='call-1') {
    return @{type=$Type;id=$Id;sessionId='session-1';parentId=$Parent;providerData=@{conversationRequestId=$Request};status=$Status;callId=$Call;output=@{type='text';text='Tool output must not become the assistant reply'}}
}
function Fixture($Name,$Records,$AgeMinutes=0) {
    $directory=Join-Path $fixtureRoot $Name
    if (![IO.Directory]::Exists($directory)) { [IO.Directory]::CreateDirectory($directory)|Out-Null; $script:fixtureDirectories.Add($directory) }
    $file=Join-Path $directory 'session.jsonl'
    $json=(@($Records | ForEach-Object { ConvertTo-Json -InputObject $_ -Depth 15 -Compress }) -join "`n")+"`n"
    [IO.File]::WriteAllText($file,$json,(New-Object Text.UTF8Encoding($false)))
    $script:fixtureFiles.Add($file)
    [IO.File]::SetLastWriteTimeUtc($file,$now.UtcDateTime.AddMinutes(-$AgeMinutes))
    return $directory
}
function ReadFixture($Directory,$TaskPrompt=$prompt) { return Get-WorkBuddyStoredReply -Prompt $TaskPrompt -Roots @($Directory) -Now $now }
try {
    $user=Message 'user-1' 'user' $prompt
    $answer=Message 'answer-1' 'assistant' $markdown 'reasoning-1'
    $directory=Fixture 'completed' @($user,@{type='reasoning';id='reasoning-1';sessionId='session-1';parentId='user-1'},$answer)
    $reply=ReadFixture $directory
    Assert ($reply.text -ceq $markdown) 'completed request-bound output retains exact Markdown and newlines'
    Assert ($reply.binding.id -eq 'user-1' -and $reply.binding.session -eq 'session-1' -and $reply.binding.answerIds[0] -eq 'answer-1') 'binding carries the selected user, session, and assistant identities'
    $wrapped=Message 'user-1' 'user' ("<system-reminder>context</system-reminder><user_query>"+$prompt+"@image#1:example.png</user_query>")
    $wrapped.content+=@{type='image_blob_ref';original_filename='example.png'}
    Assert ((ReadFixture (Fixture 'wrapped' @($wrapped,$answer))).text -ceq $markdown) 'observed WorkBuddy input_text wrapper preserves matching submitted request'
    $wrapperOnly=Message 'user-1' 'user' ("<system-reminder>context</system-reminder><user_query>"+$prompt+"</user_query>")
    Assert ((ReadFixture (Fixture 'wrapped-no-attachments' @($wrapperOnly,$answer))).text -ceq $markdown) 'the unique user_query matches a complete plain task without attachment references'
    $contextOnly=Message 'user-1' 'user' ("<system-reminder>"+$prompt+"</system-reminder><user_query>A different task</user_query>")
    Assert ($null -eq (ReadFixture (Fixture 'context-only-marker' @($contextOnly,$answer)))) 'a prompt and nonce present only in context cannot bind a different user_query'
    $changedQuery=Message 'user-1' 'user' ("<user_query>Changed task`n"+[regex]::Match($prompt,'\[Agent Control request [^\]]+\]').Value+"</user_query>")
    Assert ($null -eq (ReadFixture (Fixture 'changed-query' @($changedQuery,$answer)))) 'a matching nonce in a changed task is rejected'
    $multipleQueries=Message 'user-1' 'user' ("<user_query>"+$prompt+"</user_query><user_query>A different task</user_query>")
    Assert ($null -eq (ReadFixture (Fixture 'multiple-queries' @($multipleQueries,$answer)))) 'multiple user_query elements are ambiguous'
    $queryAfterPrefix=Message 'user-1' 'user' ("<user_query>Instead do this first: "+$prompt+"</user_query>")
    Assert ($null -eq (ReadFixture (Fixture 'query-later-substring' @($queryAfterPrefix,$answer)))) 'a requested prompt occurring after different task text cannot match by substring'
    $queryChangedTail=Message 'user-1' 'user' ("<user_query>"+$prompt+" and do another task</user_query>")
    Assert ($null -eq (ReadFixture (Fixture 'query-changed-tail' @($queryChangedTail,$answer)))) 'an unrecognized task suffix cannot be treated as an attachment'
    $missingImage=Message 'user-1' 'user' ("<user_query>"+$prompt+"@image#1:example.png</user_query>")
    Assert ($null -eq (ReadFixture (Fixture 'query-no-image-blob' @($missingImage,$answer)))) 'an image reference without its persisted blob cannot bind the wrapper'
    $differentImage=Message 'user-1' 'user' ("<user_query>"+$prompt+"@image#1:example.png</user_query>")
    $differentImage.content+=@{type='image_blob_ref';original_filename='different.png'}
    Assert ($null -eq (ReadFixture (Fixture 'query-wrong-image' @($differentImage,$answer)))) 'an attachment reference must match the persisted blob filename'
    $multipleImages=Message 'user-1' 'user' ("<user_query>"+$prompt+"@image#1:first.png`n@image#2:second image.png</user_query>")
    $multipleImages.content+=@{type='image_blob_ref';original_filename='first.png'},@{type='image_blob_ref';original_filename='second image.png'}
    Assert ((ReadFixture (Fixture 'query-multiple-images' @($multipleImages,$answer))).text -ceq $markdown) 'known ordered image references bind to all actual blobs'
    $ordinarySubstring=Message 'user-1' 'user' ('A different prefix '+$prompt)
    Assert ($null -eq (ReadFixture (Fixture 'ordinary-substring' @($ordinarySubstring,$answer)))) 'unwrapped input_text requires complete equality'
    $parentAnswer=Message 'answer-1' 'assistant' $markdown 'user-1' ''
    $parentUser=Message 'user-1' 'user' $prompt '' ''
    Assert ((ReadFixture (Fixture 'direct-parent' @($parentUser,$parentAnswer))).text -ceq $markdown) 'direct parent binds a reply without a provider request identity'
    Assert ($null -eq (ReadFixture (Fixture 'wrong-parent' @($parentUser,(Message 'answer-1' 'assistant' $markdown 'other-user' ''))))) 'unrelated parent cannot supply a reply'
    Assert ($null -eq (ReadFixture (Fixture 'wrong-request' @($user,(Message 'answer-1' 'assistant' $markdown 'user-1' 'other-request'))))) 'contradictory provider identity rejects even a matching parent'
    Assert ($null -eq (ReadFixture (Fixture 'wrong-session' @($user,(Message 'answer-1' 'assistant' $markdown 'user-1' 'request-1' 'completed' 'other-session'))))) 'another session cannot supply a reply'
    Assert ($null -eq (ReadFixture (Fixture 'missing-user-id' @((Message '' 'user' $prompt),$answer)))) 'a user record without an identity cannot bind a reply'
    Assert ($null -eq (ReadFixture (Fixture 'empty-answer' @($user,(Message 'answer-1' 'assistant' '' 'user-1'))))) 'an empty completed output does not erase the UI reply'
    Assert ($null -eq (ReadFixture (Fixture 'before-user' @($answer,$user)))) 'an earlier completed assistant cannot stand in for this request'
    Assert ($null -eq (ReadFixture (Fixture 'pending' @($user,(Message 'answer-1' 'assistant' $markdown 'user-1' 'request-1' 'in_progress'))))) 'streaming content is not a completed reply'
    Assert ($null -eq (ReadFixture (Fixture 'old' @($user,$answer) 21))) 'histories older than twenty minutes are not read'
    Assert ($null -eq (ReadFixture $directory 'no marker')) 'missing marker has no store authority'
    Assert ($null -eq (ReadFixture $directory ($prompt+"`n"+$prompt))) 'duplicate prompt markers have no store authority'
    Assert ($null -eq (ReadFixture (Fixture 'foreign-user' @($user,$answer,(Message 'other-user' 'user' 'An inserted desktop task'))))) 'another user message after this turn prevents stale reply recovery'
    Assert ($null -eq (ReadFixture (Fixture 'same-marker' @($user,$answer,(Message 'other-user' 'user' ($prompt+' changed')))))) 'same marker attached to changed text is ambiguous'
    $duplicateDirectory=Fixture 'duplicate-files' @($user,$answer)
    $copy=Join-Path $duplicateDirectory 'copy.jsonl'; [IO.File]::Copy((Join-Path $duplicateDirectory 'session.jsonl'),$copy); $script:fixtureFiles.Add($copy)
    Assert ($null -eq (ReadFixture $duplicateDirectory)) 'duplicate markers across files reject recovery'
    $changedCopyDirectory=Fixture 'duplicate-marker-changed-query' @($user,$answer)
    $changedCopyFile=Join-Path $changedCopyDirectory 'changed.jsonl'
    [IO.File]::WriteAllText($changedCopyFile,((Message 'other-user' 'user' ($prompt+' changed') | ConvertTo-Json -Depth 15 -Compress)+"`n"),(New-Object Text.UTF8Encoding($false)))
    $script:fixtureFiles.Add($changedCopyFile)
    Assert ($null -eq (ReadFixture $changedCopyDirectory)) 'a cross-file marker conflict is rejected even when the other prompt differs'
    $pendingAnswer=Message 'answer-1' 'assistant' 'partial' 'reasoning-1' 'request-1' 'in_progress'
    Assert ((ReadFixture (Fixture 'snapshot-complete' @($user,$pendingAnswer,$answer))).text -ceq $markdown) 'completed snapshot replaces a previous partial snapshot of the same identity'
    Assert ($null -eq (ReadFixture (Fixture 'snapshot-regression' @($user,$answer,$pendingAnswer)))) 'a latest incomplete snapshot invalidates prior completed content'
    Assert ($null -eq (ReadFixture (Fixture 'snapshot-rebind' @($user,$answer,(Message 'answer-1' 'assistant' 'unrelated' 'other-user' 'other-request'))))) 'a changed snapshot identity cannot reuse the older completed reply'
    Assert ($null -eq (ReadFixture (Fixture 'pending-tool' @($user,(Tool 'tool-1' 'function_call'),$answer)))) 'an unfinished bound tool prevents completed reply recovery'
    $toolReply=ReadFixture (Fixture 'tool-complete' @($user,(Tool 'tool-1' 'function_call'),(Tool 'result-1' 'function_call_result' 'completed'),$answer))
    Assert ($toolReply.text -ceq $markdown) 'completed tool result is excluded from assistant Markdown'
    Assert ($null -eq (ReadFixture (Fixture 'late-tool' @($user,$answer,(Tool 'tool-1' 'function_call'),(Tool 'result-1' 'function_call_result' 'completed'))))) 'tool activity later than the final reply prevents early completion'
    Assert ((ReadFixture (Fixture 'unrelated-tool' @($user,(Tool 'tool-1' 'function_call' '' 'unrelated' 'unrelated'),$answer))).text -ceq $markdown) 'a different request tool cannot contaminate this reply'
    $multi=ReadFixture (Fixture 'multiple-answers' @($user,$answer,(Message 'answer-2' 'assistant' 'Final paragraph' 'answer-1')))
    Assert ($multi.text -ceq ($markdown+"`n`nFinal paragraph")) 'multiple completed assistant messages retain their stored order'
    $badDirectory=Fixture 'malformed' @($user,$answer)
    [IO.File]::AppendAllText((Join-Path $badDirectory 'session.jsonl'),'{unfinished',(New-Object Text.UTF8Encoding($false)))
    Assert ($null -eq (ReadFixture $badDirectory)) 'a partial or corrupt JSONL tail rejects uncertain recovery'
    $largeDirectory=Fixture 'oversized' @($user,$answer)
    $largeFile=Join-Path $largeDirectory 'session.jsonl'; $largeStream=[IO.File]::Open($largeFile,[IO.FileMode]::Open,[IO.FileAccess]::Write)
    try { $largeStream.SetLength(20000001) } finally { $largeStream.Dispose() }
    Assert ($null -eq (ReadFixture $largeDirectory)) 'file budget is checked before oversized contents are read'
    $totalDirectory=Fixture 'total-budget' @($user,$answer)
    $extraFile=Join-Path $totalDirectory 'extra.jsonl'; [IO.File]::WriteAllText($extraFile,'')
    $script:fixtureFiles.Add($extraFile)
    foreach ($budgetFile in @((Join-Path $totalDirectory 'session.jsonl'),$extraFile)) {
        $budgetStream=[IO.File]::Open($budgetFile,[IO.FileMode]::Open,[IO.FileAccess]::Write)
        try { $budgetStream.SetLength(10000001) } finally { $budgetStream.Dispose() }
    }
    Assert ($null -eq (ReadFixture $totalDirectory)) 'the aggregate twenty megabyte budget bounds reads across files'
    $invalidEncodingDirectory=Fixture 'invalid-utf8' @($user,$answer)
    [IO.File]::WriteAllBytes((Join-Path $invalidEncodingDirectory 'session.jsonl'),[byte[]]@(0xC3,0x28))
    Assert ($null -eq (ReadFixture $invalidEncodingDirectory)) 'invalid UTF-8 cannot silently change stored text'
    $script:enumerationCount=0
    function Get-ChildItem {
        [CmdletBinding()]param($LiteralPath,$Filter,[switch]$Recurse,[switch]$File)
        for ($itemIndex=0; $itemIndex -lt 7000; $itemIndex++) {
            $script:enumerationCount++
            [pscustomobject]@{FullName=(Join-Path $LiteralPath ($itemIndex.ToString()+'.jsonl'));LastWriteTimeUtc=$now.UtcDateTime.AddDays(-1);Length=1}
        }
    }
    try {
        Assert ($null -eq (ReadFixture $directory)) 'more than six thousand old log candidates reject enumeration before content reads'
        Assert ($script:enumerationCount -eq 6001) 'old metadata enumeration stops after the bounded overflow sentinel'
    } finally { Remove-Item -LiteralPath Function:Get-ChildItem }
    Write-Output "ALL $script:passed WORKBUDDY STORE CHECKS PASSED"
} finally {
    foreach ($file in $script:fixtureFiles) { if ([IO.File]::Exists($file)) { [IO.File]::Delete($file) } }
    for ($index=$script:fixtureDirectories.Count-1; $index -ge 0; $index--) { [IO.Directory]::Delete($script:fixtureDirectories[$index],$false) }
}
