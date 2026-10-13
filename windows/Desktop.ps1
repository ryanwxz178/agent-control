Add-Type -AssemblyName UIAutomationClient,UIAutomationTypes,System.Windows.Forms
Add-Type -AssemblyName WindowsBase
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
using System.Collections.Generic;
using System.Text;
using System.Text.RegularExpressions;
using System.Management;
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
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] public static extern IntPtr FindWindow(string className,string title);
 [StructLayout(LayoutKind.Sequential)] public struct ScreenPoint {public int x;public int y;}
 [DllImport("user32.dll")] static extern IntPtr WindowFromPoint(ScreenPoint point);
 [DllImport("user32.dll")] static extern IntPtr GetAncestor(IntPtr hwnd,uint flag);
 public static bool ClickTaskbar(IntPtr taskbar,IntPtr target,int x,int y) {
  if(GetForegroundWindow()==target)return true;
  var point=new ScreenPoint{x=x,y=y};
  if(taskbar==IntPtr.Zero||FindWindow("Shell_TrayWnd",null)!=taskbar||GetAncestor(WindowFromPoint(point),2)!=taskbar)return false;
  if(!SetCursorPos(x,y)||GetAncestor(WindowFromPoint(point),2)!=taskbar)return false;
  if(GetForegroundWindow()==target)return true;
  mouse_event(2,0,0,0,UIntPtr.Zero);mouse_event(4,0,0,0,UIntPtr.Zero);return true;
 }
 [DllImport("user32.dll")] static extern IntPtr GetWindow(IntPtr hwnd,uint command);
 [DllImport("user32.dll")] static extern IntPtr GetParent(IntPtr hwnd);
 [DllImport("user32.dll")] static extern bool EnumChildWindows(IntPtr hwnd,EnumProc callback,IntPtr param);
 [DllImport("user32.dll")] static extern int GetDlgCtrlID(IntPtr hwnd);
 [DllImport("user32.dll")] static extern bool IsWindowEnabled(IntPtr hwnd);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern IntPtr SendMessageTimeout(IntPtr hwnd,uint msg,IntPtr wParam,string lParam,uint flags,uint timeout,out IntPtr result);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern IntPtr SendMessageTimeout(IntPtr hwnd,uint msg,IntPtr wParam,StringBuilder lParam,uint flags,uint timeout,out IntPtr result);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern IntPtr SendMessageTimeout(IntPtr hwnd,uint msg,IntPtr wParam,IntPtr lParam,uint flags,uint timeout,out IntPtr result);
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern int GetClassName(IntPtr hwnd,StringBuilder text,int length);
 [DllImport("kernel32.dll")] static extern IntPtr OpenProcess(uint access,bool inherit,uint processId);
 [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr handle);
 [DllImport("kernel32.dll",CharSet=CharSet.Unicode)] static extern bool QueryFullProcessImageName(IntPtr process,uint flags,StringBuilder path,ref uint length);
 [DllImport("kernel32.dll")] static extern bool GetProcessTimes(IntPtr process,out long created,out long exited,out long kernel,out long user);
 [DllImport("kernel32.dll")] static extern bool GetExitCodeProcess(IntPtr process,out uint code);
 [DllImport("kernel32.dll")] static extern bool ProcessIdToSessionId(uint processId,out uint session);
 [DllImport("advapi32.dll")] static extern bool OpenProcessToken(IntPtr process,uint access,out IntPtr token);
 [DllImport("advapi32.dll")] static extern bool GetTokenInformation(IntPtr token,int kind,IntPtr buffer,int size,out int needed);
 [DllImport("shell32.dll",CharSet=CharSet.Unicode)] static extern IntPtr CommandLineToArgvW(string commandLine,out int count);
 [DllImport("kernel32.dll")] static extern IntPtr LocalFree(IntPtr memory);
 static readonly object pickerTrustLock=new object();
 static readonly HashSet<string> trustedPickerUtilities=new HashSet<string>();
 static readonly System.Threading.SemaphoreSlim pickerQueries=new System.Threading.SemaphoreSlim(2,2);
 static string ProcessUser(IntPtr process) {
  IntPtr token=IntPtr.Zero,buffer=IntPtr.Zero;
  try {
   if(!OpenProcessToken(process,8,out token))return null;
   int needed;GetTokenInformation(token,1,IntPtr.Zero,0,out needed);
   if(needed<=0||needed>4096)return null;
   buffer=Marshal.AllocHGlobal(needed);
   if(!GetTokenInformation(token,1,buffer,needed,out needed))return null;
   return new System.Security.Principal.SecurityIdentifier(Marshal.ReadIntPtr(buffer)).Value;
  } finally {if(buffer!=IntPtr.Zero)Marshal.FreeHGlobal(buffer);if(token!=IntPtr.Zero)CloseHandle(token);}
 }
 static string ProcessImage(IntPtr process) {
  uint size=32768;var path=new StringBuilder((int)size);
  return QueryFullProcessImageName(process,0,path,ref size)?path.ToString():null;
 }
 static bool UtilityArguments(string commandLine) {
  if(String.IsNullOrEmpty(commandLine))return false;
  int count;IntPtr arguments=CommandLineToArgvW(commandLine,out count);
  if(arguments==IntPtr.Zero)return false;
  try {
   int type=0,subtype=0;
   for(int i=1;i<count;i++) {
    string value=Marshal.PtrToStringUni(Marshal.ReadIntPtr(arguments,i*IntPtr.Size));
    if(value.StartsWith("--type=",StringComparison.Ordinal)){if(value!="--type=utility")return false;type++;}
    if(value.StartsWith("--utility-sub-type=",StringComparison.Ordinal)){if(value!="--utility-sub-type=chrome.mojom.UtilWin")return false;subtype++;}
   }
   return type==1&&subtype==1;
  } finally {LocalFree(arguments);}
 }
 static bool TrustedPickerUtility(uint dialogPid,uint ownerPid) {
  IntPtr ownerProcess=IntPtr.Zero,dialogProcess=IntPtr.Zero;
  try {
   ownerProcess=OpenProcess(0x1000,false,ownerPid);dialogProcess=OpenProcess(0x1000,false,dialogPid);
   if(ownerProcess==IntPtr.Zero||dialogProcess==IntPtr.Zero)return false;
   long ownerCreated,childCreated,exited,kernel,user;uint ownerSession,childSession,exitCode;
   if(!GetProcessTimes(ownerProcess,out ownerCreated,out exited,out kernel,out user)||!GetProcessTimes(dialogProcess,out childCreated,out exited,out kernel,out user)||ownerCreated<=0||childCreated<ownerCreated)return false;
   if(!GetExitCodeProcess(ownerProcess,out exitCode)||exitCode!=259||!GetExitCodeProcess(dialogProcess,out exitCode)||exitCode!=259)return false;
   if(!ProcessIdToSessionId(ownerPid,out ownerSession)||!ProcessIdToSessionId(dialogPid,out childSession)||ownerSession!=childSession||ownerSession!=(uint)System.Diagnostics.Process.GetCurrentProcess().SessionId)return false;
   string ownerPath=ProcessImage(ownerProcess),childPath=ProcessImage(dialogProcess);
   if(String.IsNullOrEmpty(ownerPath)||!String.Equals(System.IO.Path.GetFileName(ownerPath),"WorkBuddy.exe",StringComparison.OrdinalIgnoreCase)||!String.Equals(ownerPath,childPath,StringComparison.OrdinalIgnoreCase))return false;
   string ownerUser=ProcessUser(ownerProcess),childUser=ProcessUser(dialogProcess);
   using(var identity=System.Security.Principal.WindowsIdentity.GetCurrent()){if(String.IsNullOrEmpty(ownerUser)||ownerUser!=childUser||identity.User==null||ownerUser!=identity.User.Value)return false;}
   string key=ownerPid+":"+ownerCreated+":"+dialogPid+":"+childCreated;
   lock(pickerTrustLock){if(trustedPickerUtilities.Contains(key))return true;}
   if(!pickerQueries.Wait(0))return false;
   bool metadataTrusted=false;
   var queryThread=new System.Threading.Thread(()=>{
    try {
     var options=new EnumerationOptions();options.Timeout=TimeSpan.FromMilliseconds(1000);options.ReturnImmediately=false;options.Rewindable=false;
     using(var query=new ManagementObjectSearcher(new ManagementScope("root\\CIMV2"),new ObjectQuery("SELECT ParentProcessId,CreationDate,ExecutablePath,CommandLine FROM Win32_Process WHERE ProcessId="+dialogPid),options))
     using(var matches=query.Get()) {
      int count=0;
      foreach(ManagementObject match in matches)using(match) {
       count++;
       if(Convert.ToUInt32(match["ParentProcessId"])!=ownerPid||!String.Equals(Convert.ToString(match["ExecutablePath"]),childPath,StringComparison.OrdinalIgnoreCase)||!UtilityArguments(Convert.ToString(match["CommandLine"])))return;
       string created=Convert.ToString(match["CreationDate"]);
       if(String.IsNullOrEmpty(created)||Math.Abs(ManagementDateTimeConverter.ToDateTime(created).ToUniversalTime().ToFileTimeUtc()-childCreated)>10)return;
      }
      metadataTrusted=count==1;
     }
    } catch {metadataTrusted=false;}
    finally {pickerQueries.Release();}
   });
   // The caller's deadline also bounds WMI providers that ignore their timeout.
   // A late result cannot approve a dialog; this worker never performs UI work.
   try {queryThread.IsBackground=true;queryThread.SetApartmentState(System.Threading.ApartmentState.MTA);queryThread.Start();}
   catch {pickerQueries.Release();throw;}
   if(!queryThread.Join(1500)||!metadataTrusted)return false;
   // Immutable process metadata is cached with both native creation times; a
   // recycled PID or unreadable identity cannot inherit a previous approval.
   lock(pickerTrustLock){if(trustedPickerUtilities.Count>=32)trustedPickerUtilities.Clear();trustedPickerUtilities.Add(key);}
   return true;
  } catch {return false;}
  finally {if(dialogProcess!=IntPtr.Zero)CloseHandle(dialogProcess);if(ownerProcess!=IntPtr.Zero)CloseHandle(ownerProcess);}
 }
 public static bool OwnedFileDialog(IntPtr dialog,IntPtr owner) {
  var name=new StringBuilder(256);GetClassName(dialog,name,256);
  if(owner==IntPtr.Zero||!IsWindow(owner)||name.ToString()!="#32770"||!IsWindowVisible(dialog))return false;
  uint dialogPid,ownerPid;GetWindowThreadProcessId(dialog,out dialogPid);GetWindowThreadProcessId(owner,out ownerPid);
  if(dialogPid==0||ownerPid==0)return false;
  var at=dialog;
  for(int i=0;i<16;i++){at=GetWindow(at,4);if(at==owner)return dialogPid==ownerPid||TrustedPickerUtility(dialogPid,ownerPid);if(at==IntPtr.Zero)return false;}
  return false;
 }
 public static IntPtr[] FileDialogsForOwner(IntPtr owner) {
  var found=new List<IntPtr>();
  EnumWindows((hwnd,param)=>{if(OwnedFileDialog(hwnd,owner))found.Add(hwnd);return true;},IntPtr.Zero);
  return found.ToArray();
 }
 static string WindowClass(IntPtr hwnd) {var value=new StringBuilder(256);GetClassName(hwnd,value,256);return value.ToString();}
 static bool ChildOf(IntPtr child,IntPtr parent) {for(int i=0;i<32;i++){child=GetParent(child);if(child==parent)return true;if(child==IntPtr.Zero)return false;}return false;}
 public static IntPtr[] NativeFileControls(IntPtr dialog,IntPtr owner) {
  if(!OwnedFileDialog(dialog,owner))return new IntPtr[0];
  var hosts=new List<IntPtr>();var buttons=new List<IntPtr>();var edits=new List<IntPtr>();
  EnumChildWindows(dialog,(hwnd,param)=>{
   if(IsWindowVisible(hwnd)&&IsWindowEnabled(hwnd)){
    var type=WindowClass(hwnd);int id=GetDlgCtrlID(hwnd);
    if(id==1148&&(type=="ComboBoxEx32"||type=="ComboBox"))hosts.Add(hwnd);
    if(id==1&&type=="Button")buttons.Add(hwnd);
    if(type=="Edit")edits.Add(hwnd);
   }return true;},IntPtr.Zero);
  var selected=new List<IntPtr>();
  foreach(var edit in edits){foreach(var host in hosts){if(ChildOf(edit,host)){selected.Add(edit);break;}}}
  return selected.Count==1&&buttons.Count==1?new[]{selected[0],buttons[0]}:new IntPtr[0];
 }
 public static bool SelectNativeFiles(IntPtr dialog,IntPtr owner,string files) {
  if(files==null||GetForegroundWindow()!=dialog||!OwnedFileDialog(dialog,owner)||files.Length>32767)return false;
  var controls=NativeFileControls(dialog,owner);if(controls.Length!=2)return false;
  if(GetForegroundWindow()!=dialog||!OwnedFileDialog(dialog,owner))return false;
  IntPtr result;
  if(SendMessageTimeout(controls[0],0x000C,IntPtr.Zero,files,2,2000,out result)==IntPtr.Zero||result==IntPtr.Zero)return false;
  var read=new StringBuilder(files.Length+2);
  if(SendMessageTimeout(controls[0],0x000D,(IntPtr)read.Capacity,read,2,2000,out result)==IntPtr.Zero||read.ToString()!=files)return false;
  if(GetForegroundWindow()!=dialog||!OwnedFileDialog(dialog,owner))return false;
  // Only the verified native picker Open button is clicked, once.
  return SendMessageTimeout(controls[1],0x00F5,IntPtr.Zero,(string)null,2,2000,out result)!=IntPtr.Zero;
 }
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hwnd);
 [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
 [DllImport("user32.dll",SetLastError=true)] static extern bool AttachThreadInput(uint from,uint to,bool attach);
 [DllImport("user32.dll")] static extern bool IsWindow(IntPtr hwnd);
 [DllImport("user32.dll")] static extern bool IsIconic(IntPtr hwnd);
 [DllImport("user32.dll")] static extern bool ShowWindowAsync(IntPtr hwnd,int cmd);
 [StructLayout(LayoutKind.Sequential)] struct NativeMessage {
  public IntPtr hwnd;public uint message;public UIntPtr wParam;public IntPtr lParam;
  public uint time;public int x;public int y;public uint reserved;
 }
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern bool PeekMessage(out NativeMessage message,IntPtr hwnd,uint first,uint last,uint remove);
 public static string ActivationDiagnostic {get;private set;}
 public static bool Activate(IntPtr hwnd) {
  ActivationDiagnostic="target="+hwnd+";valid="+IsWindow(hwnd)+";visible="+IsWindowVisible(hwnd);
  if(hwnd==IntPtr.Zero||!IsWindow(hwnd)||!IsWindowVisible(hwnd))return false;
  if(IsIconic(hwnd))ShowWindowAsync(hwnd,9);
  if(GetForegroundWindow()==hwnd&&!IsIconic(hwnd)){ActivationDiagnostic+=";alreadyForeground=true";return true;}
  NativeMessage message;PeekMessage(out message,IntPtr.Zero,0,0,0);
  uint processId;uint current=GetCurrentThreadId();
  IntPtr before=GetForegroundWindow();uint foreground=GetWindowThreadProcessId(before,out processId);
  bool attachAttempted=foreground!=0&&foreground!=current;
  bool attached=attachAttempted&&AttachThreadInput(current,foreground,true);
  int attachError=attachAttempted&&!attached?Marshal.GetLastWin32Error():0;
  ActivationDiagnostic+=";before="+before+";beforeProcess="+processId+";beforeThread="+foreground+";currentThread="+current+";attachAttempted="+attachAttempted+";attached="+attached+";attachError="+attachError;
  var timer=System.Diagnostics.Stopwatch.StartNew();
  try {
   bool requested=SetForegroundWindow(hwnd);
   ActivationDiagnostic+=";setForeground="+requested;
   // The target input queue stays separate. Give its asynchronous activation
   // up to one second to complete before releasing the foreground association.
   IntPtr result;
   bool acknowledged=SendMessageTimeout(hwnd,0,IntPtr.Zero,IntPtr.Zero,2|32,1000,out result)!=IntPtr.Zero;
   ActivationDiagnostic+=";ack="+acknowledged;
   if(!acknowledged)return false;
  } finally {
   if(attached)AttachThreadInput(current,foreground,false);
   timer.Stop();
   ActivationDiagnostic+=";elapsedMs="+timer.ElapsedMilliseconds+";after="+GetForegroundWindow()+";iconic="+IsIconic(hwnd);
  }
  return IsWindow(hwnd)&&GetForegroundWindow()==hwnd&&!IsIconic(hwnd);
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
'@ -ReferencedAssemblies System.dll,System.Core.dll,System.Management.dll
[PocketDesktop]::SetProcessDPIAware() | Out-Null
. "$PSScriptRoot\UiLogic.ps1"
. "$PSScriptRoot\WorkBuddyStore.ps1"
function Get-DesktopHealth {
    if ([Diagnostics.Process]::GetCurrentProcess().SessionId -eq 0) { return '桥接运行在服务会话 Session 0，请在用户桌面启动' }
    if (![PocketDesktop]::Active([Diagnostics.Process]::GetCurrentProcess().SessionId)) { return '用户会话已断开或未处于活动状态' }
    if (![PocketDesktop]::Unlocked()) { return '桌面锁定、UAC 安全桌面或没有交互桌面' }
    return ''
}
function Get-UiTextStyle($Element,$TextProvider,[string]$Role) {
    $style=@{fontWeight=0;fontFamily='';italic=$false;strike=$false;headingLevel=0;listMarker='';linkUrl=''}
    if($Role -notin @('Text','ListItem','Hyperlink')){return $style}
    try {
        $heading=$Element.GetCurrentPropertyValue([Windows.Automation.AutomationProperty]::LookupById(30173))
        [int]$number=0
        try {$number=[int]$heading}catch {$number=0}
        if($number -ge 80051 -and $number -le 80056){$style.headingLevel=$number-80050}
    } catch { }
    try {
        $pattern=$null;$range=$null
        if($Element.TryGetCurrentPattern([Windows.Automation.TextPattern]::Pattern,[ref]$pattern)){$range=$pattern.DocumentRange}
        elseif($TextProvider){$range=$TextProvider.RangeFromChild($Element)}
        if($range) {
            # Mixed/unsupported UIA attributes are COM sentinel objects, not numbers.
            $weight=$range.GetAttributeValue([Windows.Automation.TextPattern]::FontWeightAttribute)
            [int]$number=0;if([int]::TryParse([string]$weight,[ref]$number)){$style.fontWeight=$number}
            $style.italic=($range.GetAttributeValue([Windows.Automation.TextPattern]::IsItalicAttribute) -eq $true)
            $strike=$range.GetAttributeValue([Windows.Automation.TextPattern]::StrikethroughStyleAttribute)
            $style.strike=([string]$strike -eq 'Single')
            $font=$range.GetAttributeValue([Windows.Automation.TextPattern]::FontNameAttribute)
            if($font -is [string]){$style.fontFamily=$font}
            if($Role -eq 'ListItem') {
                $prefix=[regex]::Match($range.GetText(150001),'^\s*(\d+[.)])\s+')
                if($prefix.Success){$style.listMarker=$prefix.Groups[1].Value+' '}
            }
        }
    } catch { }
    if($Role -eq 'Hyperlink') {
        try {
            $pattern=$null
            if($Element.TryGetCurrentPattern([Windows.Automation.ValuePattern]::Pattern,[ref]$pattern)){$style.linkUrl=[string]$pattern.Current.Value}
        } catch { }
    }
    return $style
}
function Get-UiSnapshot($Window) {
    $list = [Collections.Generic.List[object]]::new()
    $walker = [Windows.Automation.TreeWalker]::ControlViewWalker
    function Visit($Element,[int]$Parent,[int]$Depth,$TextProvider) {
        if ($Depth -gt 40 -or $list.Count -ge 8000) { throw '应用辅助功能树过大，停止操作' }
        $c = $Element.Current
        if ($null -eq $c.ControlType -or !$c.ControlType.ProgrammaticName) { throw '应用辅助功能提供者返回了无类型控件，请稍后重试诊断；未进行输入或发送。' }
        $index = $list.Count
        $role=$c.ControlType.ProgrammaticName.Replace('ControlType.','')
        $value = ''; $pattern = $null
        if($role -eq 'Document') {
            try {if($Element.TryGetCurrentPattern([Windows.Automation.TextPattern]::Pattern,[ref]$pattern)){$TextProvider=$pattern}}catch { }
        }
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
        $style=Get-UiTextStyle $Element $TextProvider $role
        $list.Add([pscustomobject]@{index=$index;parent=$Parent;role=$role;name=$c.Name;className=$c.ClassName;automationId=$c.AutomationId;ariaRole=[string]$ariaRole;enabled=$c.IsEnabled;offscreen=$c.IsOffscreen;value=$value;top=$c.BoundingRectangle.Top;height=$c.BoundingRectangle.Height;fontWeight=$style.fontWeight;fontFamily=$style.fontFamily;italic=$style.italic;strike=$style.strike;headingLevel=$style.headingLevel;listMarker=$style.listMarker;linkUrl=$style.linkUrl;raw=$Element})
        $child=$walker.GetFirstChild($Element)
        while ($null -ne $child) { Visit $child $index ($Depth+1) $TextProvider; $child=$walker.GetNextSibling($child) }
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
    if ($Node.raw.TryGetCurrentPattern([Windows.Automation.ExpandCollapsePattern]::Pattern,[ref]$pattern)) { $pattern.Expand(); return }
    if ($Node.raw.TryGetCurrentPattern([Windows.Automation.SelectionItemPattern]::Pattern,[ref]$pattern)) { $pattern.Select(); return }
    $point=[Windows.Point]::new(0,0)
    if (!$Node.raw.TryGetClickablePoint([ref]$point)) { throw '无法获得按钮的可点击位置，未操作' }
    Assert-Window $Window -Ending:$Ending
    if (![PocketDesktop]::SetCursorPos([int]$point.X,[int]$point.Y)) { throw '无法定位鼠标，未操作' }
    [PocketDesktop]::mouse_event(2,0,0,0,[UIntPtr]::Zero)
    [PocketDesktop]::mouse_event(4,0,0,0,[UIntPtr]::Zero)
}
function Set-AppWindowForeground($Window,$Adapter=$null) {
    $handle=[IntPtr]$Window.Current.NativeWindowHandle
    if([PocketDesktop]::Activate($handle)){return $true}
    # Use the target application's accessibility provider when Windows declines
    # background SetForegroundWindow. No keystrokes or clicks go to another app.
    try {$Window.SetFocus()} catch { # A top-level Electron window may not accept UIA focus.
    }
    for($attempt=0;$attempt -lt 10;$attempt++){
        if([PocketDesktop]::GetForegroundWindow() -eq $handle){return $true}
        Start-Sleep -Milliseconds 100
    }
    if(!$Adapter -or !$Adapter.taskbarId){return $false}
    return Set-TaskbarWindowForeground $Window $Adapter
}
function Set-TaskbarWindowForeground($Window,$Adapter) {
    $handle=[IntPtr]$Window.Current.NativeWindowHandle
    if([PocketDesktop]::GetForegroundWindow() -eq $handle){return $true}
    if(Get-DesktopHealth){return $false}
    $trayHandle=[PocketDesktop]::FindWindow('Shell_TrayWnd',$null)
    if($trayHandle -eq [IntPtr]::Zero){return $false}
    $tray=[Windows.Automation.AutomationElement]::FromHandle($trayHandle)
    $shell=Get-Process -Id $tray.Current.ProcessId -ErrorAction SilentlyContinue
    if(!$shell -or $shell.Path -ine (Join-Path $env:SystemRoot explorer.exe) -or $shell.SessionId -ne [Diagnostics.Process]::GetCurrentProcess().SessionId){return $false}
    $buttons=@(Get-UiSnapshot $tray|Where-Object { $_.role -eq 'Button' -and $_.automationId -ceq $Adapter.taskbarId -and $_.enabled -and !$_.offscreen })
    if($buttons.Count -ne 1){return $false}
    $button=$buttons[0];$point=[Windows.Point]::new(0,0)
    if(!$button.raw.TryGetClickablePoint([ref]$point)){
        $bounds=$button.raw.Current.BoundingRectangle
        if($bounds.IsEmpty -or $bounds.Width -le 0 -or $bounds.Height -le 0){return $false}
        $point=[Windows.Point]::new($bounds.Left+$bounds.Width/2,$bounds.Top+$bounds.Height/2)
    }
    if($button.raw.Current.AutomationId -cne $Adapter.taskbarId){return $false}
    $hit=[Windows.Automation.AutomationElement]::FromPoint($point)
    # Windows 11 may expose only the shell root for point hit-testing.
    $pointOnTray=($hit.Current.NativeWindowHandle -eq $trayHandle.ToInt64())
    $matched=$false
    for($depth=0;$hit -and $depth -lt 12;$depth++){
        if($hit.Current.AutomationId -ceq $Adapter.taskbarId){$matched=$true;break}
        $hit=[Windows.Automation.TreeWalker]::ControlViewWalker.GetParent($hit)
    }
    if(!$matched -and !$pointOnTray){return $false}
    # One real click on the exact shell app button, never on an occluded app surface.
    if(![PocketDesktop]::ClickTaskbar($trayHandle,$handle,[int]$point.X,[int]$point.Y)){return $false}
    for($attempt=0;$attempt -lt 20;$attempt++){
        if([PocketDesktop]::GetForegroundWindow() -eq $handle){return $true}
        Start-Sleep -Milliseconds 100
    }
    return $false
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
    $window=[Windows.Automation.AutomationElement]::FromHandle($hwnd)
    if (!(Set-AppWindowForeground $window $Adapter)) { throw 'Windows 未允许目标应用获得前台焦点，请在电脑点击该应用窗口后重试；未输入或发送。' }
    Start-Sleep -Milliseconds 500
    return $window
}
function Get-ConversationTranscript($Chat,[switch]$Legacy) {
    return (Normalize-Message (($Chat.nodes | Where-Object { $_.role -eq 'Text' -or (!$Legacy -and $_.role -in @('ListItem','Hyperlink')) } | ForEach-Object { $_.name }) -join "`n"))
}
function Test-ConversationTranscript($Chat,$Previous) {
    if($Previous.transcriptVersion -and $Previous.transcriptVersion -ne 2){return $false}
    # Already saved phone bindings used Text-only fingerprints. Check that exact
    # legacy fingerprint once; the next completed turn writes the complete v2 form.
    return ((Get-ConversationTranscript $Chat -Legacy:(!$Previous.transcriptVersion)) -ceq $Previous.transcript)
}
function Get-DesktopCompletedText($App,$State,[string]$Prompt) {
    $text=[string]$State.text
    # The local store supplements formatting only after the visible current request
    # has been confirmed sent and complete; it cannot establish completion itself.
    if($App.id -eq 'workbuddy' -and $State.sent -and $State.complete) {
        $stored=Get-WorkBuddyStoredReply -Prompt $Prompt
        if($stored -and ![string]::IsNullOrWhiteSpace($stored.text)){$text=[string]$stored.text}
    }
    if($text.Length -gt 150000){throw '回复超过大小限制'}
    return $text
}
function Wait-DesktopFileMenu($Window,$Step) {
    for($attempt=0;$attempt -lt 16;$attempt++) {
        Assert-Window $Window
        $nodes=@(Get-UiSnapshot $Window)
        $matches=@($nodes | Where-Object { $_.role -in @('MenuItem','Button') -and $_.enabled -and !$_.offscreen -and $_.name -match $Step.name -and (!$Step.className -or $_.className -match $Step.className) })
        if($matches.Count -gt 1){throw '附件菜单不唯一，未添加或发送'}
        if($matches.Count -eq 1){return $matches[0]}
        Start-Sleep -Milliseconds 200
    }
    throw '未能识别桌面 APP 的附件菜单，请在电脑检查当前版本；未发送'
}
function Invoke-DesktopFilePicker($Window,$Chat,$Adapter,$Files) {
    if(@(Get-ComposerFileTags @(Get-UiSnapshot $Window) $Chat.editor $Adapter).Count){throw '电脑输入框已有附件草稿，未覆盖或发送'}
    $triggers=@($Chat.nodes | Where-Object { $_.role -in @('Button','ComboBox') -and $_.enabled -and !$_.offscreen -and $Adapter.fileMenuClass -and $_.className -match $Adapter.fileMenuClass })
    if($triggers.Count -ne 1){throw '无法唯一识别桌面 APP 的附件入口，请在电脑检查；未发送'}
    if(@([PocketDesktop]::FileDialogsForOwner([IntPtr]$Window.Current.NativeWindowHandle)).Count){throw '电脑已打开文件选择窗口，未覆盖或发送'}
    Invoke-UiButton $triggers[0] $Window
    foreach($step in @($Adapter.fileMenuSteps)) {
        $node=Wait-DesktopFileMenu $Window $step
        if($step.hover) {
            # WorkBuddy's Add Files submenu opens on hover. Position comes from UIA only.
            $point=[Windows.Point]::new(0,0)
            if(!$node.raw.TryGetClickablePoint([ref]$point)){throw '附件子菜单没有可用位置，未发送'}
            Assert-Window $Window
            if(![PocketDesktop]::SetCursorPos([int]$point.X,[int]$point.Y)){throw '无法打开附件子菜单，未发送'}
        } else { Invoke-UiButton $node $Window }
    }
    $dialog=$null;$focusChanged=$false;$dialogWait=[Diagnostics.Stopwatch]::StartNew()
    for($attempt=0;$attempt -lt 24 -and $dialogWait.ElapsedMilliseconds -lt 6000;$attempt++) {
        Pulse
        $handles=@([PocketDesktop]::FileDialogsForOwner([IntPtr]$Window.Current.NativeWindowHandle))
        if($dialogWait.ElapsedMilliseconds -ge 6000){break}
        if($handles.Count -gt 1){throw '目标 APP 的文件选择窗口不唯一，未发送'}
        if($handles.Count -eq 1) {
            if([PocketDesktop]::GetForegroundWindow() -ne [IntPtr]$handles[0]){throw '目标文件选择窗口没有前台焦点，未输入或发送'}
            $dialog=[Windows.Automation.AutomationElement]::FromHandle([IntPtr]$handles[0]);break
        }
        # Dialog activation can precede its visible/owned window enumeration.
        # Only wait during that transition; never type before the checks above.
        if([PocketDesktop]::GetForegroundWindow() -ne [IntPtr]$Window.Current.NativeWindowHandle){$focusChanged=$true}
        Start-Sleep -Milliseconds 250
    }
    if(!$dialog){if($focusChanged){throw '未出现可验证归属的文件窗口或其他窗口获得焦点，未输入附件路径或发送'};throw '目标 APP 未打开可识别的文件选择窗口，未发送'}
    $controls=$null
    $nativeControls=$false
    for($attempt=0;$attempt -lt 24;$attempt++) {
        Assert-Window $dialog
        if(![PocketDesktop]::OwnedFileDialog([IntPtr]$dialog.Current.NativeWindowHandle,[IntPtr]$Window.Current.NativeWindowHandle)){throw '文件窗口归属已改变，未输入或发送'}
        try { $controls=Get-FileDialogControls @(Get-UiSnapshot $dialog);break } catch { $controls=$null }
        # Some WinPS UIA providers report all native controls as Pane. Use exact child HWNDs.
        if(@([PocketDesktop]::NativeFileControls([IntPtr]$dialog.Current.NativeWindowHandle,[IntPtr]$Window.Current.NativeWindowHandle)).Count -eq 2){$nativeControls=$true;break}
        Start-Sleep -Milliseconds 200
    }
    if(!$controls -and !$nativeControls){throw '无法唯一识别目标文件窗口的文件名和打开控件，未发送'}
    $selection=(@($Files | ForEach-Object { '"'+$_.path+'"' }) -join ' ')
    Assert-Window $dialog
    if(![PocketDesktop]::OwnedFileDialog([IntPtr]$dialog.Current.NativeWindowHandle,[IntPtr]$Window.Current.NativeWindowHandle)){throw '文件窗口归属已改变，未输入或发送'}
    if($nativeControls) {
        if(![PocketDesktop]::SelectNativeFiles([IntPtr]$dialog.Current.NativeWindowHandle,[IntPtr]$Window.Current.NativeWindowHandle,$selection)){throw '原生附件选择内容或窗口核对失败，未发送'}
    } else {
        $pattern=$null
        if(!$controls.edit.raw.TryGetCurrentPattern([Windows.Automation.ValuePattern]::Pattern,[ref]$pattern) -or $pattern.Current.IsReadOnly){throw '文件选择框不可写，未发送'}
        $pattern.SetValue($selection)
        if($pattern.Current.Value -cne $selection){throw '附件选择内容回读不一致，未打开或发送'}
        Invoke-UiButton $controls.open $dialog
    }
    Write-Event 'status' '正在确认桌面 APP 已添加文件附件'
    $returned=$false
    for($attempt=0;$attempt -lt 80;$attempt++) {
        Pulse
        $foreground=[PocketDesktop]::GetForegroundWindow()
        if($foreground -ne [IntPtr]$Window.Current.NativeWindowHandle) {
            if([PocketDesktop]::OwnedFileDialog($foreground,[IntPtr]$Window.Current.NativeWindowHandle)){Start-Sleep -Milliseconds 400;continue}
            throw '桌面 APP 添加附件时失去前台焦点，未发送'
        }
        $returned=$true
        $nodes=@(Get-UiSnapshot $Window);$current=Get-ChatScope $nodes $Adapter
        if(($current.editor.raw.GetRuntimeId() -join '-') -cne ($Chat.editor.raw.GetRuntimeId() -join '-')){throw '附件添加后输入框身份已改变，未发送'}
        $tags=@(Get-ComposerFileTags $nodes $current.editor $Adapter)
        if(@($tags | Where-Object { $_.className -match '(?:^| )_failed_[A-Za-z0-9]+_\d+(?: |$)' }).Count){throw '桌面 APP 未能处理附件，请在电脑检查格式或大小限制；未发送'}
        if(Test-ComposerFilesReady $nodes $current.editor $Adapter $Files){return}
        Start-Sleep -Milliseconds 400
    }
    if(!$returned){throw '桌面 APP 未关闭文件选择窗口，请在电脑检查；未发送'}
    throw '未能确认全部文件附件已就绪，请在电脑检查附件草稿；未发送'
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
function Invoke-DesktopChat($App,$Adapter,[string]$Prompt,[string]$ConversationId,$Files=$null) {
    $files=@(Resolve-DesktopFiles $Files $Adapter)
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
    try {
        $existingChat=Get-ChatScope $nodes $Adapter
        if(@(Get-ComposerFileTags $nodes $existingChat.editor $Adapter).Count){throw '电脑输入框已有附件草稿，未覆盖、新建或发送'}
    } catch {
        if($_.Exception.Message -like '*附件草稿*'){throw}
        # Home screens without an editor may still be opened through New chat.
    }
    $chat=$null
    if ($previous) {
        $chat=Get-ChatScope $nodes $Adapter
        $prior=Get-ReplyState $nodes $Adapter $previous.prompt
        if (!$prior.sent -or !$prior.complete -or !(Test-ConversationTranscript $chat $previous)) { throw '请在电脑打开此手机会话的原对话后重试；不会自动新建或发送到其他对话' }
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
    $nodes=@(Get-UiSnapshot $window);$chat=Get-ChatScope $nodes $Adapter
    if(@(Get-ComposerFileTags $nodes $chat.editor $Adapter).Count){throw '电脑输入框已有附件草稿，未覆盖或发送'}
    if(Get-EditorValue $chat.editor $Adapter){throw '电脑输入框有草稿，未覆盖或发送'}
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
    if($files.Count) {
        Invoke-DesktopFilePicker $window $chat $Adapter $files
        $nodes=@(Get-UiSnapshot $window);$chat=Get-ChatScope $nodes $Adapter
        if(!(Test-ComposerFilesReady $nodes $chat.editor $Adapter $files) -or !(Test-ComposerPrompt $nodes $chat.editor $Adapter $Prompt)){throw '附件或任务文本核对失败，保留电脑草稿，未发送'}
    }
    $send=@($chat.nodes | Where-Object { (Test-AdapterButton $_ $Adapter.send $Adapter.sendClass) -and $_.enabled -and !$_.offscreen })
    if ($send.Count -ne 1) { throw '发送按钮不唯一或未启用，保留草稿，未发送' }
    $conversationState=@{version=1;app=$App.id;conversationId=$ConversationId;prompt=$Prompt;status='pending';transcript='';transcriptVersion=2}
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
        $ownDraft=(Normalize-Message $draft) -ceq (Normalize-Message $Prompt)
        if($files.Count -and $draft){$ownDraft=((Test-ComposerFilesReady $currentNodes $currentChat.editor $Adapter $files) -and (Test-ComposerPrompt $currentNodes $currentChat.editor $Adapter $Prompt))}
        if ($draft -and !((([DateTime]::UtcNow-$started).TotalSeconds -lt 3) -and $ownDraft)) { throw '电脑输入框被修改，已停止本轮，未覆盖或重发消息' }
        $copies=@($currentChat.nodes | Where-Object { $_.role -eq 'Button' -and $_.name -match '^(复制|Copy)$' })
        if ($copies.Count -gt $baselineCopies+2) { throw '电脑在当前会话增加了其他消息，已停止本轮' }
        $state=Get-ReplyState $currentNodes $Adapter $Prompt
        if ($confirmed -and !$state.sent) { throw '电脑已切换对话，已停止接收；不会读取其他会话或重发' }
        if ($state.sent) { $confirmed=$true }
        if (!$confirmed -and ([DateTime]::UtcNow-$started).TotalSeconds -gt 15) { throw '发送后未能确认消息进入对话，状态未知；不会重发' }
        if ($state.complete -and $state.text -ceq $last) { $stable++ } else { $stable=0 }
        $last=$state.text
        if ($last.Length -gt 150000) { throw '回复超过大小限制' }
        if ($stable -ge 2) {
            $last=Get-DesktopCompletedText $App $state $Prompt
            $conversationState.status='complete'
            $conversationState.transcript=Get-ConversationTranscript $currentChat
            Save-DesktopConversation $conversationPath $conversationState
            Write-Event 'desktop.done' $last
            return
        }
    }
    throw '等待回复超时，状态未知；电脑任务可能仍在运行'
}
