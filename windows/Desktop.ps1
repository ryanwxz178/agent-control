Add-Type -AssemblyName UIAutomationClient,UIAutomationTypes,System.Windows.Forms
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Collections.Generic;
using System.Text;
using System.Text.RegularExpressions;
public static class PocketDesktop {
 public delegate bool EnumProc(IntPtr hwnd,IntPtr lp);
 [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc callback,IntPtr param);
 [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hwnd,out uint processId);
 [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr hwnd);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern int GetWindowText(IntPtr hwnd,StringBuilder text,int length);
 public static IntPtr[] WindowsForProcess(int processId,string titlePattern) {
  var found=new List<IntPtr>();
  EnumWindows((hwnd,param)=>{uint pid;GetWindowThreadProcessId(hwnd,out pid);
   if(pid==(uint)processId&&IsWindowVisible(hwnd)){var title=new StringBuilder(1024);GetWindowText(hwnd,title,1024);
    if(String.IsNullOrEmpty(titlePattern)||Regex.IsMatch(title.ToString(),titlePattern))found.Add(hwnd);}
   return true;},IntPtr.Zero);
  return found.ToArray();
 }
 [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hwnd);
 [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
 [DllImport("user32.dll")] static extern bool AttachThreadInput(uint from,uint to,bool attach);
 public static bool Activate(IntPtr hwnd) {
  if(GetForegroundWindow()==hwnd)return true;
  uint processId;uint current=GetCurrentThreadId();
  uint foreground=GetWindowThreadProcessId(GetForegroundWindow(),out processId);
  bool attached=foreground!=0&&foreground!=current&&AttachThreadInput(current,foreground,true);
  try { ShowWindow(hwnd,9);SetForegroundWindow(hwnd);return GetForegroundWindow()==hwnd; }
  finally { if(attached)AttachThreadInput(current,foreground,false); }
 }
 [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hwnd,int cmd);
 [DllImport("user32.dll")] public static extern bool SetProcessDPIAware();
 [DllImport("user32.dll")] public static extern bool SetCursorPos(int x,int y);
 [DllImport("user32.dll")] public static extern void mouse_event(uint flags,uint x,uint y,uint data,UIntPtr extra);
 [DllImport("user32.dll",SetLastError=true)] public static extern IntPtr OpenInputDesktop(uint flags,bool inherit,uint access);
 [DllImport("user32.dll")] public static extern bool CloseDesktop(IntPtr desktop);
 [DllImport("user32.dll")] public static extern bool SwitchDesktop(IntPtr desktop);
 [DllImport("wtsapi32.dll",CharSet=CharSet.Unicode)] static extern bool WTSQuerySessionInformation(IntPtr server,int session,int info,out IntPtr buffer,out int size);
 [DllImport("wtsapi32.dll")] static extern void WTSFreeMemory(IntPtr buffer);
 public static bool Active(int session) { IntPtr p; int n; if(!WTSQuerySessionInformation(IntPtr.Zero,session,8,out p,out n))return false; try{return n>=4 && Marshal.ReadInt32(p)==0;} finally{WTSFreeMemory(p);} }
 public static bool Unlocked() { var d=OpenInputDesktop(0,false,0x0100); if(d==IntPtr.Zero)return false; try{return SwitchDesktop(d);} finally{CloseDesktop(d);} }
}
'@
[PocketDesktop]::SetProcessDPIAware() | Out-Null
. "$PSScriptRoot\UiLogic.ps1"
function Get-DesktopHealth {
    if ([Diagnostics.Process]::GetCurrentProcess().SessionId -eq 0) { return '桥接运行在服务会话 Session 0，请在用户桌面启动' }
    if (![PocketDesktop]::Active([Diagnostics.Process]::GetCurrentProcess().SessionId)) { return '用户会话已断开或未处于活动状态' }
    if (![PocketDesktop]::Unlocked()) { return '桌面锁定、UAC 安全桌面或没有交互桌面' }
    return ''
}
function Get-UiSnapshot($Window) {
    $list = [Collections.Generic.List[object]]::new()
    $walker = [Windows.Automation.TreeWalker]::ControlViewWalker
    function Visit($Element,[int]$Parent,[int]$Depth) {
        if ($Depth -gt 40 -or $list.Count -ge 8000) { throw '应用辅助功能树过大，停止操作' }
        $c = $Element.Current
        if ($null -eq $c.ControlType -or !$c.ControlType.ProgrammaticName) { throw '应用辅助功能提供者返回了无类型控件，请稍后重试诊断；未进行输入或发送。' }
        $index = $list.Count
        $role=$c.ControlType.ProgrammaticName.Replace('ControlType.','')
        $value = ''; $pattern = $null
        if ($role -eq 'Edit') {
            if ($Element.TryGetCurrentPattern([Windows.Automation.ValuePattern]::Pattern,[ref]$pattern)) { $value = $pattern.Current.Value }
            elseif ($Element.TryGetCurrentPattern([Windows.Automation.TextPattern]::Pattern,[ref]$pattern)) { $value = $pattern.DocumentRange.GetText(-1) }
        } elseif ($role -eq 'Group' -and $c.IsKeyboardFocusable) {
            # CEF contenteditable nodes can expose a writable TextPattern as Group.
            if ($Element.TryGetCurrentPattern([Windows.Automation.ValuePattern]::Pattern,[ref]$pattern) -and !$pattern.Current.IsReadOnly) {
                $role='Edit';$value=$pattern.Current.Value
            } elseif ($Element.TryGetCurrentPattern([Windows.Automation.TextPattern]::Pattern,[ref]$pattern) -and $pattern.DocumentRange.GetAttributeValue([Windows.Automation.TextPattern]::IsReadOnlyAttribute) -eq $false) {
                $role='Edit';$value=$pattern.DocumentRange.GetText(-1)
            }
        }
        # .NET Framework's UIA wrapper does not expose every UIA3 property.
        $ariaRole=Get-OptionalUiValue $Element ([Windows.Automation.AutomationProperty]::LookupById(30101))
        $list.Add([pscustomobject]@{index=$index;parent=$Parent;role=$role;name=$c.Name;className=$c.ClassName;automationId=$c.AutomationId;ariaRole=[string]$ariaRole;enabled=$c.IsEnabled;offscreen=$c.IsOffscreen;value=$value;top=$c.BoundingRectangle.Top;height=$c.BoundingRectangle.Height;raw=$Element})
        $child=$walker.GetFirstChild($Element)
        while ($null -ne $child) { Visit $child $index ($Depth+1); $child=$walker.GetNextSibling($child) }
    }
    Visit $Window -1 0
    return $list.ToArray()
}
function Assert-Window($Window,[switch]$Ending) {
    Pulse -Ending:$Ending
    $reason = Get-DesktopHealth
    if ($reason) { throw $reason }
    if ([PocketDesktop]::GetForegroundWindow() -ne [IntPtr]$Window.Current.NativeWindowHandle) { throw '目标窗口失去前台焦点，已停止输入。请勿同时操作电脑。' }
}
function Invoke-UiButton($Node,$Window,[switch]$Ending) {
    Assert-Window $Window -Ending:$Ending
    if (!$Node.enabled -or $Node.offscreen) { throw '按钮不可用，未点击' }
    $pattern=$null
    if ($Node.raw.TryGetCurrentPattern([Windows.Automation.InvokePattern]::Pattern,[ref]$pattern)) { $pattern.Invoke(); return }
    if ($Node.raw.TryGetCurrentPattern([Windows.Automation.SelectionItemPattern]::Pattern,[ref]$pattern)) { $pattern.Select(); return }
    $point=[Windows.Point]::new()
    if (!$Node.raw.TryGetClickablePoint([ref]$point)) { throw '无法获得按钮的可点击位置，未操作' }
    Assert-Window $Window -Ending:$Ending
    if (![PocketDesktop]::SetCursorPos([int]$point.X,[int]$point.Y)) { throw '无法定位鼠标，未操作' }
    [PocketDesktop]::mouse_event(2,0,0,0,[UIntPtr]::Zero)
    [PocketDesktop]::mouse_event(4,0,0,0,[UIntPtr]::Zero)
}
function Get-AppWindow($App,$Adapter) {
    $session=[Diagnostics.Process]::GetCurrentProcess().SessionId
    function Find-AppWindows {
        foreach ($process in @(Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.SessionId -eq $session -and $_.ProcessName -in $Adapter.processNames -and $_.Path -eq $App.path })) {
            [PocketDesktop]::WindowsForProcess($process.Id,[string]$Adapter.windowTitle)
        }
    }
    $handles=@(Find-AppWindows)
    if ($handles.Count -eq 0) {
        if ($Adapter.launchArgs) { Start-Process -FilePath $App.path -ArgumentList $Adapter.launchArgs | Out-Null }
        else { Start-Process -FilePath $App.path | Out-Null }
        for ($i=0; $i -lt 20 -and $handles.Count -eq 0; $i++) {
            Pulse; Start-Sleep -Milliseconds 500
            $handles=@(Find-AppWindows)
        }
    }
    if ($handles.Count -ne 1) { throw '无法唯一识别当前用户会话中的应用主窗口，请关闭额外窗口或手动启动应用。' }
    $hwnd=[IntPtr]$handles[0]
    if (![PocketDesktop]::Activate($hwnd)) { throw '无法将目标应用切到前台，请先在电脑打开该应用后重试。' }
    Start-Sleep -Milliseconds 500
    return [Windows.Automation.AutomationElement]::FromHandle($hwnd)
}
function Get-ConversationTranscript($Chat) {
    return (Normalize-Message (($Chat.nodes | Where-Object { $_.role -eq 'Text' } | ForEach-Object { $_.name }) -join "`n"))
}
function Save-DesktopConversation($Path,$State) {
    if (!$Path) { return }
    $folder=[IO.Path]::GetDirectoryName($Path)
    [IO.Directory]::CreateDirectory($folder) | Out-Null
    $temporary=$Path+'.tmp'
    [IO.File]::WriteAllText($temporary,($State|ConvertTo-Json -Depth 8 -Compress),[Text.UTF8Encoding]::new($false))
    if ([IO.File]::Exists($Path)) { [IO.File]::Replace($temporary,$Path,$Path+'.previous') } else { [IO.File]::Move($temporary,$Path) }
}
function Invoke-EndDesktopConversation($App,$Adapter,[string]$ConversationId) {
    if($ConversationId -notmatch '^[a-zA-Z0-9_-]{1,160}$'){throw '手机会话标识无效'}
    $path=Join-Path $env:USERPROFILE ('.agent-control/desktop-conversations/'+$App.id+'-'+$ConversationId+'.json')
    $previous=[IO.File]::ReadAllText($path,[Text.Encoding]::UTF8)|ConvertFrom-Json
    if($previous.app -cne $App.id -or $previous.conversationId -cne $ConversationId -or !$previous.prompt){throw '原桌面会话绑定无效'}
    $window=Get-AppWindow $App $Adapter
    Assert-Window $window -Ending
    $nodes=@(Get-UiSnapshot $window);$chat=Get-ChatScope $nodes $Adapter
    if(!(Get-ReplyState $nodes $Adapter $previous.prompt).sent){throw '请在电脑打开原会话后重试结束，不会停止其他任务'}
    $stops=@($chat.nodes | Where-Object { $_.role -eq 'Button' -and $_.name -match $Adapter.busy -and $_.enabled -and !$_.offscreen })
    if($stops.Count -gt 1){throw '停止按钮不唯一，未操作电脑'}
    if($stops.Count -eq 1){Invoke-UiButton $stops[0] $window -Ending}
    for($attempt=0;$attempt -lt 20;$attempt++){
        Assert-Window $window -Ending
        $nodes=@(Get-UiSnapshot $window);$reply=Get-ReplyState $nodes $Adapter $previous.prompt
        if(!$reply.sent){throw '原会话身份已改变，不能确认结束'}
        if(!$reply.busy){$previous.status='ended';Save-DesktopConversation $path $previous;Write-Event 'desktop.cancelled' '原桌面会话已结束';return}
        Start-Sleep -Milliseconds 250
    }
    throw '原桌面任务尚未确认停止，请先在电脑结束任务'
}
function Invoke-DesktopChat($App,$Adapter,[string]$Prompt,[string]$ConversationId) {
    $window=Get-AppWindow $App $Adapter
    Assert-Window $window
    $nodes=@(Get-UiSnapshot $window)
    $conversationPath=$null;$previous=$null
    if ($ConversationId) {
        if ($ConversationId -notmatch '^[a-zA-Z0-9_-]{1,160}$' -or $App.id -notmatch '^[a-zA-Z0-9 _-]+$') { throw '手机会话标识无效' }
        $conversationPath=Join-Path $env:USERPROFILE ('.agent-control/desktop-conversations/'+($App.id -replace '[^a-zA-Z0-9_-]','_')+'-'+$ConversationId+'.json')
        if ([IO.File]::Exists($conversationPath)) {
            $previous=[IO.File]::ReadAllText($conversationPath)|ConvertFrom-Json
            if ($previous.version -ne 1 -or $previous.app -cne $App.id -or $previous.conversationId -cne $ConversationId -or !$previous.prompt) { throw '手机与电脑的会话绑定损坏，未发送' }
            if ($previous.status -ne 'complete') { throw '上一条电脑消息尚未确认完成，不能新建对话或重复发送，请先在电脑检查' }
        }
    }
    $before=($nodes | ForEach-Object { $_.role+':'+$_.name }) -join "`n"
    $beforeBlank=$false
    try {
        $initial=Get-ChatScope $nodes $Adapter
        $beforeBlank=(!(Get-EditorValue $initial.editor $Adapter) -and @($initial.nodes | Where-Object { $Adapter.blank -and $_.name -match $Adapter.blank }).Count -gt 0 -and @($initial.nodes | Where-Object { $_.name -match $Adapter.complete }).Count -eq 0)
    } catch { # A home screen may not expose the editor until New chat is invoked.
    }
    if (@($nodes | Where-Object { $_.role -eq 'Button' -and $_.name -match $Adapter.busy }).Count) { throw '应用已有运行中的任务，未新建或发送任务' }
    $chat=$null
    if ($previous) {
        $chat=Get-ChatScope $nodes $Adapter
        $prior=Get-ReplyState $nodes $Adapter $previous.prompt
        if (!$prior.sent -or !$prior.complete -or (Get-ConversationTranscript $chat) -cne $previous.transcript) { throw '请在电脑打开此手机会话的原对话后重试；不会自动新建或发送到其他对话' }
        if (Get-EditorValue $chat.editor $Adapter) { throw '原对话输入框有草稿，未覆盖' }
        Write-Event 'status' '正在继续同一个电脑对话'
    } else {
    $new=@($nodes | Where-Object { $_.role -in @('Button','RadioButton','Hyperlink','TabItem') -and $_.name -match $Adapter.newChat -and !$_.offscreen })
    if ($new.Count -ne 1) { throw '无法唯一识别新对话按钮，请运行 Diagnose.ps1 校验当前版本的 UIA 控件。' }
    Invoke-UiButton $new[0] $window
    $chat=$null
    for ($i=0; $i -lt 15; $i++) {
        Pulse; Start-Sleep -Milliseconds 300
        $nodes=@(Get-UiSnapshot $window)
        try { $chat=Get-ChatScope $nodes $Adapter } catch { $chat=$null;continue }
        $old=@($chat.nodes | Where-Object { $_.name -match $Adapter.complete -or ($_.role -eq 'Button' -and $_.name -match $Adapter.busy) })
        $after=($nodes | ForEach-Object { $_.role+':'+$_.name }) -join "`n"
        $blank=Test-BlankChat $chat $Adapter
        if (!(Get-EditorValue $chat.editor $Adapter) -and $old.Count -eq 0 -and $blank -and ($after -cne $before -or $beforeBlank)) { break }
        $chat=$null
    }
    if (!$chat) { throw '未能确认空白新对话，未发送' }
    }
    Assert-Window $window
    # Paste raises the application's real editor events. Restore only if still our clipboard.
    $saved=[Windows.Forms.Clipboard]::GetDataObject()
    try {
        [Windows.Forms.Clipboard]::SetText($Prompt)
        $chat.editor.raw.SetFocus()
        Assert-Window $window
        [Windows.Forms.SendKeys]::SendWait('^a')
        [Windows.Forms.SendKeys]::SendWait('^v')
        Start-Sleep -Milliseconds 500
    } finally {
        try {
            if ([Windows.Forms.Clipboard]::ContainsText() -and [Windows.Forms.Clipboard]::GetText() -eq $Prompt) {
                if ($saved) { [Windows.Forms.Clipboard]::SetDataObject($saved,$true) } else { [Windows.Forms.Clipboard]::Clear() }
            }
        } catch { Write-Event 'status' '剪贴板被其他程序占用，正在核对电脑输入框' }
    }
    $nodes=@(Get-UiSnapshot $window); $chat=Get-ChatScope $nodes $Adapter
    if (((Get-EditorValue $chat.editor $Adapter) -replace "`r`n","`n").TrimEnd("`r","`n") -cne ($Prompt -replace "`r`n","`n").TrimEnd("`r","`n")) { throw '输入框内容回读不一致，未发送' }
    $send=@($chat.nodes | Where-Object { (Test-AdapterButton $_ $Adapter.send $Adapter.sendClass) -and $_.enabled -and !$_.offscreen })
    if ($send.Count -ne 1) { throw '发送按钮不唯一或未启用，保留草稿，未发送' }
    $conversationState=@{version=1;app=$App.id;conversationId=$ConversationId;prompt=$Prompt;status='pending';transcript=''}
    Save-DesktopConversation $conversationPath $conversationState
    $baselineCopies=@($chat.nodes | Where-Object { $_.role -eq 'Button' -and $_.name -match '^(复制|Copy)$' }).Count
    # One invocation only. Never retry an uncertain send.
    Invoke-UiButton $send[0] $window
    Write-Event 'status' '正在确认消息已进入电脑对话'
    $started=[DateTime]::UtcNow; $confirmed=$false; $last=''; $stable=0; $editorIdentity=$null
    while (([DateTime]::UtcNow-$started).TotalSeconds -lt 600) {
        if(Test-Path -LiteralPath "$Root\$script:activeId.cancel") {
            Assert-Window $window -Ending
            $cancelNodes=@(Get-UiSnapshot $window);$cancelChat=Get-ChatScope $cancelNodes $Adapter
            $cancelState=Get-ReplyState $cancelNodes $Adapter $Prompt
            if(!$cancelState.sent){throw '无法确认原桌面对话，未停止其他任务'}
            $stopButtons=@($cancelChat.nodes | Where-Object { $_.role -eq 'Button' -and $_.name -match $Adapter.busy -and $_.enabled -and !$_.offscreen })
            if($stopButtons.Count -gt 1){throw '停止按钮不唯一，未操作电脑'}
            if($stopButtons.Count -eq 1){Invoke-UiButton $stopButtons[0] $window -Ending}
            for($stopAttempt=0;$stopAttempt -lt 40;$stopAttempt++) {
                Start-Sleep -Milliseconds 250;Assert-Window $window -Ending
                $cancelNodes=@(Get-UiSnapshot $window);$cancelChat=Get-ChatScope $cancelNodes $Adapter
                if(!(Get-ReplyState $cancelNodes $Adapter $Prompt).sent){throw '电脑已切换对话，无法确认原任务结束'}
                if(!@($cancelChat.nodes | Where-Object { $_.role -eq 'Button' -and $_.name -match $Adapter.busy }).Count) {
                    $conversationState.status='ended';Save-DesktopConversation $conversationPath $conversationState
                    Write-Event 'desktop.cancelled' '原桌面会话已结束';return
                }
            }
            throw '原桌面任务尚未确认停止，请先在电脑结束任务'
        }
        Pulse; Start-Sleep -Milliseconds 500
        $currentNodes=@(Get-UiSnapshot $window)
        $currentChat=Get-ChatScope $currentNodes $Adapter
        $identity=($currentChat.editor.raw.GetRuntimeId() -join '-')
        if ($editorIdentity -and $identity -ne $editorIdentity) { throw '电脑聊天输入框身份已改变，已停止本轮；不会重发' }
        $editorIdentity=$identity
        $draft=Get-EditorValue $currentChat.editor $Adapter
        if ($draft -and !((([DateTime]::UtcNow-$started).TotalSeconds -lt 3) -and (Normalize-Message $draft) -ceq (Normalize-Message $Prompt))) { throw '电脑输入框被修改，已停止本轮，未覆盖或重发消息' }
        $copies=@($currentChat.nodes | Where-Object { $_.role -eq 'Button' -and $_.name -match '^(复制|Copy)$' })
        if ($copies.Count -gt $baselineCopies+2) { throw '电脑在当前会话增加了其他消息，已停止本轮' }
        $state=Get-ReplyState $currentNodes $Adapter $Prompt
        if ($confirmed -and !$state.sent) { throw '电脑已切换对话，已停止接收；不会读取其他会话或重发' }
        if ($state.sent) { $confirmed=$true }
        if (!$confirmed -and ([DateTime]::UtcNow-$started).TotalSeconds -gt 15) { throw '发送后未能确认消息进入对话，状态未知；不会重发' }
        if ($state.complete -and $state.text -ceq $last) { $stable++ } else { $stable=0 }
        $last=$state.text
        if ($last.Length -gt 150000) { throw '回复超过大小限制' }
        if ($stable -ge 2) { $conversationState.status='complete';$conversationState.transcript=Get-ConversationTranscript $currentChat;Save-DesktopConversation $conversationPath $conversationState;Write-Event 'desktop.done' $last; return }
    }
    throw '等待回复超时，状态未知；电脑任务可能仍在运行'
}
