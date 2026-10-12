# Only isolated, labelled fixture windows are created. No installed APP is opened.
param([switch]$CompileOnly)
$ErrorActionPreference='Stop'
if($env:OS -ne 'Windows_NT' -or [Threading.Thread]::CurrentThread.ApartmentState -ne 'STA'){throw 'Run this fixture with Windows PowerShell 5.1 -STA.'}
$repo=(Resolve-Path "$PSScriptRoot/../../..").Path
. "$repo/windows/Desktop.ps1"
$temporaryRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$folder=Join-Path $temporaryRoot ('agent-control-utility-picker-test-'+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($folder)|Out-Null
$processes=[Collections.Generic.List[object]]::new()
$script:passed=0
function Check-Utility($Value,[string]$Name){if(!$Value){throw ('FAIL: '+$Name)};$script:passed++;Write-Output ('PASS '+$Name)}
function Wait-UtilityFile([string]$Path){
    $deadline=[DateTime]::UtcNow.AddSeconds(8)
    while([DateTime]::UtcNow -lt $deadline){if([IO.File]::Exists($Path)){return [IO.File]::ReadAllText($Path)};Start-Sleep -Milliseconds 50}
    throw ('PRECONDITION: Fixture did not publish '+$Path)
}
function Utility-Case([string]$Name,[string]$Arguments,[string]$Executable='same',[string]$Owner='main'){
    [IO.File]::WriteAllText((Join-Path $folder ($Name+'.command')),($Arguments+'|'+$Executable+'|'+$Owner),[Text.UTF8Encoding]::new($false))
    $handle=[IntPtr][long](Wait-UtilityFile (Join-Path $folder ($Name+'.handle')))
    $childId=[int](Wait-UtilityFile (Join-Path $folder ($Name+'.pid')))
    $processes.Add((Get-Process -Id $childId))
    return $handle
}
try{
    $fixtureExe=Join-Path $folder 'WorkBuddy.exe'
    $fixtureSource=@'
using System;
using System.IO;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows.Forms;
public static class UtilityPickerFixture {
 [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern IntPtr CreateWindowEx(uint ex,string cls,string title,uint style,int x,int y,int w,int h,IntPtr owner,IntPtr menu,IntPtr instance,IntPtr parameter);
 [DllImport("user32.dll")] static extern bool DestroyWindow(IntPtr window);
 [DllImport("user32.dll")] static extern IntPtr GetWindow(IntPtr window,uint kind);
 static string folder;
 static string ReadArg(string[] args,string key){foreach(string value in args)if(value.StartsWith(key+"=",StringComparison.Ordinal))return value.Substring(key.Length+1);return "";}
 static void Publish(string name,string value){string path=Path.Combine(folder,name),temp=path+".tmp";File.WriteAllText(temp,value);File.Move(temp,path);}
 static string Quoted(string value){return "\""+value+"\"";}
 [STAThread] public static void Main(string[] args){
  folder=ReadArg(args,"--fixture-folder");string role=ReadArg(args,"--fixture-role");
  if(String.IsNullOrEmpty(folder)||!Directory.Exists(folder))return;
  if(role=="owner"){
   Form main=new Form();main.Text="Agent Control utility owner fixture";main.Width=430;main.Height=120;
   Form decoy=new Form();decoy.Text="Agent Control unrelated owner fixture";decoy.Width=430;decoy.Height=120;
   var launched=new System.Collections.Generic.HashSet<string>();var timer=new Timer();timer.Interval=30;
   main.Shown+=(s,e)=>{decoy.Show();Publish("owner.handle",main.Handle.ToInt64().ToString());Publish("decoy.handle",decoy.Handle.ToInt64().ToString());timer.Start();};
   timer.Tick+=(s,e)=>{
    if(File.Exists(Path.Combine(folder,"stop"))){timer.Stop();decoy.Close();main.Close();return;}
    foreach(string command in Directory.GetFiles(folder,"*.command")){
     string name=Path.GetFileNameWithoutExtension(command);if(!launched.Add(name))continue;
     string[] parts=File.ReadAllText(command).Split('|');string exe=Path.Combine(folder,parts[1]=="other"?"OtherFixture.exe":"WorkBuddy.exe");
     IntPtr owner=parts[2]=="decoy"?decoy.Handle:main.Handle;
     var start=new ProcessStartInfo(exe,parts[0]+" --fixture-role=dialog --fixture-case="+name+" --fixture-folder="+Quoted(folder)+" --fixture-owner="+owner.ToInt64());start.UseShellExecute=false;start.CreateNoWindow=true;
     Process child=Process.Start(start);Publish(name+".pid",child.Id.ToString());child.Dispose();
    }
   };
   Application.Run(main);timer.Dispose();decoy.Dispose();main.Dispose();return;
  }
  if(role=="dialog"){
   string name=ReadArg(args,"--fixture-case");IntPtr owner=new IntPtr(Int64.Parse(ReadArg(args,"--fixture-owner")));
   // NOACTIVATE keeps these metadata fixtures out of the user's foreground.
   IntPtr window=CreateWindowEx(0x08000000,"#32770","Agent Control utility dialog fixture: "+name,0x90C80000,180,180,430,100,owner,IntPtr.Zero,IntPtr.Zero,IntPtr.Zero);
   if(window==IntPtr.Zero)throw new InvalidOperationException("Fixture dialog creation failed");
   Publish(name+".handle",window.ToInt64().ToString());
   var timer=new Timer();timer.Interval=30;timer.Tick+=(s,e)=>{if(File.Exists(Path.Combine(folder,"stop"))||File.Exists(Path.Combine(folder,name+".stop"))){timer.Stop();DestroyWindow(window);Application.ExitThread();}};
   timer.Start();Application.Run();timer.Dispose();
  }
 }
}
'@
    Add-Type -TypeDefinition $fixtureSource -ReferencedAssemblies System.dll,System.Core.dll,System.Windows.Forms.dll -OutputAssembly $fixtureExe -OutputType WindowsApplication
    [IO.File]::Copy($fixtureExe,(Join-Path $folder 'OtherFixture.exe'))
    if($CompileOnly){Write-Output 'PASS fixture compiled without opening windows';return}
    $ownerProcess=Start-Process -FilePath $fixtureExe -ArgumentList '--fixture-role=owner',('--fixture-folder="'+$folder+'"') -WindowStyle Hidden -PassThru
    $processes.Add($ownerProcess)
    $owner=[IntPtr][long](Wait-UtilityFile (Join-Path $folder 'owner.handle'))
    $decoy=[IntPtr][long](Wait-UtilityFile (Join-Path $folder 'decoy.handle'))
    $valid=Utility-Case 'valid' '--type=utility --utility-sub-type=chrome.mojom.UtilWin'
    Check-Utility ([PocketDesktop]::OwnedFileDialog($valid,$owner)) 'same executable, direct child, current user/session, native creation time and exact utility accepted'
    Check-Utility (@([PocketDesktop]::FileDialogsForOwner($owner)).Count -eq 1) 'verified cross-process dialog is included in owner enumeration'
    Check-Utility ([PocketDesktop]::OwnedFileDialog($valid,$owner)) 'repeated validation checks live identity with creation-bound cache'
    Check-Utility (![PocketDesktop]::OwnedFileDialog($valid,$decoy)) 'same-app unrelated owner is rejected'
    Check-Utility (@([PocketDesktop]::NativeFileControls($valid,$owner)).Count -eq 0) 'utility trust does not bypass native file-control validation'
    Check-Utility (![PocketDesktop]::SelectNativeFiles($valid,$owner,'"fixture.txt"')) 'non-picker fixture receives no file input or Open action'
    $noUtility=Utility-Case 'missing-utility' ''
    Check-Utility (![PocketDesktop]::OwnedFileDialog($noUtility,$owner)) 'same executable child without utility arguments rejected'
    $wrongType=Utility-Case 'wrong-type' '--type=renderer --utility-sub-type=chrome.mojom.UtilWin'
    Check-Utility (![PocketDesktop]::OwnedFileDialog($wrongType,$owner)) 'renderer child cannot impersonate file-picker utility'
    $wrongSubtype=Utility-Case 'wrong-subtype' '--type=utility --utility-sub-type=chrome.mojom.UtilWinFake'
    Check-Utility (![PocketDesktop]::OwnedFileDialog($wrongSubtype,$owner)) 'utility subtype must match the complete argument'
    $duplicate=Utility-Case 'duplicate-type' '--type=utility --type=utility --utility-sub-type=chrome.mojom.UtilWin'
    Check-Utility (![PocketDesktop]::OwnedFileDialog($duplicate,$owner)) 'duplicate process-type arguments rejected'
    $wrongExe=Utility-Case 'wrong-executable' '--type=utility --utility-sub-type=chrome.mojom.UtilWin' 'other'
    Check-Utility (![PocketDesktop]::OwnedFileDialog($wrongExe,$owner)) 'different executable child rejected despite correct utility flags'
    $wrongOwner=Utility-Case 'wrong-owner' '--type=utility --utility-sub-type=chrome.mojom.UtilWin' 'same' 'decoy'
    Check-Utility (![PocketDesktop]::OwnedFileDialog($wrongOwner,$owner)) 'exact native owner chain remains mandatory'
    $unrelated=Start-Process -FilePath $fixtureExe -ArgumentList '--type=utility','--utility-sub-type=chrome.mojom.UtilWin','--fixture-role=dialog','--fixture-case=wrong-parent',('--fixture-folder="'+$folder+'"'),('--fixture-owner='+$owner.ToInt64()) -WindowStyle Hidden -PassThru
    $processes.Add($unrelated)
    $unrelatedDialog=[IntPtr][long](Wait-UtilityFile (Join-Path $folder 'wrong-parent.handle'))
    Check-Utility (![PocketDesktop]::OwnedFileDialog($unrelatedDialog,$owner)) 'same executable and exact owner still reject a process launched by another parent'
    Check-Utility (![PocketDesktop]::OwnedFileDialog($valid,[IntPtr]::Zero)) 'null owner rejected'
    Check-Utility (![PocketDesktop]::SelectNativeFiles($valid,$owner,$null)) 'null file selection rejected safely'
    [IO.File]::WriteAllText((Join-Path $folder 'valid.stop'),'stop')
    $validProcess=Get-Process -Id ([int](Wait-UtilityFile (Join-Path $folder 'valid.pid')))
    Check-Utility ($validProcess.WaitForExit(3000)) 'validated utility fixture closes cleanly'
    Check-Utility (![PocketDesktop]::OwnedFileDialog($valid,$owner)) 'destroyed dialog cannot inherit cached utility approval'
    Write-Output ("ALL $script:passed UTILITY PICKER CHECKS PASSED")
}finally{
    if([IO.Directory]::Exists($folder)){[IO.File]::WriteAllText((Join-Path $folder 'stop'),'stop')}
    foreach($process in $processes){
        if($process.HasExited){continue}
        if(!$process.WaitForExit(2000)){
            $live=Get-Process -Id $process.Id -ErrorAction SilentlyContinue
            if($live -and $live.StartTime -eq $process.StartTime -and $live.Path.StartsWith($folder+'\',[StringComparison]::OrdinalIgnoreCase)){Stop-Process -Id $process.Id -Force}
        }
    }
    $resolved=[IO.Path]::GetFullPath($folder)
    if(!$resolved.StartsWith($temporaryRoot+'\agent-control-utility-picker-test-',[StringComparison]::OrdinalIgnoreCase)){throw 'Refusing cleanup outside fixture temp directory'}
    if([IO.Directory]::Exists($resolved)){[IO.Directory]::Delete($resolved,$true)}
}
