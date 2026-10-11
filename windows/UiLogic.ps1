# Pure snapshot logic, also testable without a Windows desktop.
function Get-OptionalUiValue($Element,$Property) {
    if ($null -eq $Property) { return '' }
    try { return [string]$Element.GetCurrentPropertyValue($Property) } catch { return '' }
}
function Normalize-Message([string]$Text) { return ($Text -replace '[\s\u200B\u200C\u200D\u2060\uFEFF]','') }
function Test-AdapterButton($Node,[string]$NamePattern,[string]$ClassPattern) {
    return ($Node.role -eq 'Button' -and (($NamePattern -and $Node.name -match $NamePattern) -or ($ClassPattern -and $Node.className -match $ClassPattern)))
}
function Get-EditorValue($Editor,$Adapter) {
    $value=([string]$Editor.value).Trim([char]0xFEFF,[char]13,[char]10)
    if ($Adapter.placeholder -and $value -match $Adapter.placeholder) { return '' }
    return $value
}
function Test-BlankChat($Chat,$Adapter) {
    if (Get-EditorValue $Chat.editor $Adapter) { return $false }
    $placeholder=([string]$Chat.editor.value).Trim([char]0xFEFF,[char]13,[char]10)
    return (($Adapter.placeholder -and $placeholder -match $Adapter.placeholder) -or @($Chat.nodes | Where-Object { $Adapter.blank -and $_.name -match $Adapter.blank }).Count -gt 0)
}
function Test-Descendant($Node, [int]$Ancestor, $Nodes) {
    [int]$at = $Node.parent; $hops=0
    while ($at -ge 0) {
        if (++$hops -gt $Nodes.Count -or $at -ge $Nodes.Count) { throw '辅助功能树的父节点链无效' }
        if ($at -eq $Ancestor) { return $true }; $at = $Nodes[$at].parent
    }
    return $false
}
function Test-Navigation($Node,$Nodes) {
    [int]$at=$Node.index; $hops=0
    while ($at -ge 0) {
        if (++$hops -gt $Nodes.Count -or $at -ge $Nodes.Count) { throw '辅助功能树的导航父节点链无效' }
        $item=$Nodes[$at]
        if ($item.role -in @('Menu','MenuBar','MenuItem','Tree','TreeItem','Tab','TabItem') -or $item.ariaRole -match '^(navigation|complementary)$' -or $item.automationId -match '(?i)(sidebar|navigation|history)' -or $item.name -match '^(侧边栏|历史会话|Sidebar|Navigation)$') { return $true }
        $at=$item.parent
    }
    return $false
}
function Test-ReplyDecoration($Node,$Nodes,$Adapter) {
    if (!$Adapter.replyDecorationClass) { return $false }
    [int]$at=$Node.index;$hops=0
    while ($at -ge 0) {
        if (++$hops -gt $Nodes.Count -or $at -ge $Nodes.Count) { throw '辅助功能树的回复父节点链无效' }
        if ($Nodes[$at].className -match $Adapter.replyDecorationClass) { return $true }
        $at=$Nodes[$at].parent
    }
    return $false
}
function Get-ChatScope($Nodes, $Adapter) {
    $editors = @($Nodes | Where-Object { $_.role -eq 'Edit' -and $_.enabled -and !$_.offscreen -and (!$Adapter.editorId -or $_.automationId -eq $Adapter.editorId) })
    if ($editors.Count -ne 1) { throw '无法唯一识别聊天输入框。请关闭弹窗，或在 profiles.json 配置 editorId；Electron 应用可能需要开启辅助功能支持。' }
    $editor = $editors[0]
    if ($Adapter.chatId) {
        $scopes = @($Nodes | Where-Object { $_.automationId -eq $Adapter.chatId })
        if ($scopes.Count -ne 1 -or !(Test-Descendant $editor $scopes[0].index $Nodes)) { throw '配置的聊天区域与输入框不匹配。' }
        $scope = $scopes[0]
    } else {
        [int]$at = $editor.parent; $scope = $null; $hops=0
        while ($at -ge 0) {
            if (++$hops -gt $Nodes.Count -or $at -ge $Nodes.Count) { throw '辅助功能树的输入框父节点链无效' }
            if ($Nodes[$at].role -eq 'Document') { $scope = $Nodes[$at]; break }; $at = $Nodes[$at].parent
        }
        if (!$scope) { throw '无法确定聊天区域，请根据诊断快照配置 chatId。' }
    }
    $inside = @($Nodes | Where-Object { (Test-Descendant $_ $scope.index $Nodes) -and $_.index -ne $editor.index -and !(Test-Descendant $_ $editor.index $Nodes) -and !(Test-Navigation $_ $Nodes) })
    return @{ editor=$editor; nodes=$inside; scope=$scope }
}
function Get-ReplyState($Nodes, $Adapter, [string]$Prompt) {
    $chat = Get-ChatScope $Nodes $Adapter
    $items = @($chat.nodes)
    $busy = @($items | Where-Object { $_.role -eq 'Button' -and $_.name -match $Adapter.busy }).Count -gt 0
    $key = Normalize-Message $Prompt
    # Match complete user text, including split UIA text runs, outside the editor.
    $textNodes = @($items | Where-Object { $_.role -eq 'Text' -and $_.name })
    $start = -1; $end = -1; $matches=0
    for ($i=0; $i -lt $textNodes.Count; $i++) {
        $joined = ''
        for ($j=$i; $j -lt $textNodes.Count; $j++) {
            $joined += Normalize-Message $textNodes[$j].name
            if ($joined -eq $key) { $matches++; $start=$textNodes[$i].index; $end=$textNodes[$j].index; break }
            if ($joined.Length -ge $key.Length -or !$key.StartsWith($joined,[StringComparison]::Ordinal)) { break }
        }

    }
    if ($matches -gt 1) { throw '当前对话包含重复请求标识，已停止读取' }
    if ($end -lt 0) { return @{ sent=$false; complete=$false; text=''; busy=$busy } }
    $markers = @($items | Where-Object { $_.index -gt $end -and $_.name -match $Adapter.complete -and $_.role -in @('Button','Text') })
    if ($markers.Count -eq 0) { return @{ sent=$true; complete=$false; text=''; busy=$busy } }
    $boundary=$markers[0].index
    if ($Adapter.replyAfterComplete) {
        # WorkBuddy renders its completed header before the answer, then a copy toolbar.
        $ends=@($items | Where-Object { $_.index -gt $boundary -and $_.role -eq 'Button' -and $Adapter.replyEndClass -and $_.className -match $Adapter.replyEndClass })
        if ($ends.Count -eq 0) { return @{sent=$true;complete=$false;text='';busy=$busy} }
        $parts=@($textNodes | Where-Object { $_.index -gt $boundary -and $_.index -lt $ends[0].index -and !(Test-ReplyDecoration $_ $Nodes $Adapter) })
        $reply='';$previous=$null
        foreach ($part in $parts) {
            # Chromium can split a single line into many Text nodes, even single characters.
            if ($previous -and $null -ne $part.top -and $null -ne $previous.top -and [double]$part.top -gt [double]$previous.top+[Math]::Max(2,[double]$previous.height/2)) { $reply+="`n" }
            $reply+=$part.name;$previous=$part
        }
        return @{sent=$true;complete=(!$busy -and ![string]::IsNullOrWhiteSpace($reply));text=$reply;busy=$busy}
    }
    $reply = @($textNodes | Where-Object { $_.index -gt $end -and $_.index -lt $boundary -and !(Test-ReplyDecoration $_ $Nodes $Adapter) } | ForEach-Object { $_.name }) -join "`n"
    return @{ sent=$true; complete=(!$busy -and ![string]::IsNullOrWhiteSpace($reply)); text=$reply; busy=$busy }
}
