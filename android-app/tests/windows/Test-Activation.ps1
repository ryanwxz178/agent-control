# Opt-in engineering integration test. Only labelled fixture windows receive focus.
# Run with Windows PowerShell 5.1 -STA on an unlocked, idle desktop.
param([switch]$Baseline)
$ErrorActionPreference='Stop'
if ($env:OS -ne 'Windows_NT' -or [Threading.Thread]::CurrentThread.ApartmentState -ne 'STA') {
    throw 'PRECONDITION: Run Test-Activation.ps1 with Windows PowerShell -STA on Windows.'
}
$repo=(Resolve-Path "$PSScriptRoot/../../..").Path
if ($Baseline) {
    $desktopSource=(& git -C $repo show HEAD:windows/Desktop.ps1 | Out-String)
    if ($LASTEXITCODE -ne 0) { throw 'PRECONDITION: Cannot read the committed activation baseline.' }
    Write-Output 'INFO Testing the committed activation baseline.'
} else { $desktopSource=[IO.File]::ReadAllText("$repo/windows/Desktop.ps1",[Text.Encoding]::UTF8) }
$native=[regex]::Match($desktopSource,"(?s)Add-Type -TypeDefinition @'\r?\n(.*?)\r?\n'@").Groups[1].Value
if (!$native) { throw 'FAIL: Production PocketDesktop native helper was not found.' }
Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
public static class ActivationFixtureObserver {
 [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
 [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hwnd);
 [DllImport("user32.dll")] public static extern bool IsIconic(IntPtr hwnd);
 [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr hwnd);
}
'@
$temporaryRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$folder=Join-Path $temporaryRoot ('agent-control-activation-test-'+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($folder)|Out-Null
$processes=[Collections.Generic.List[object]]::new()
$utf8=[Text.UTF8Encoding]::new($true)
$script:probeNumber=0
$script:passed=0
function Assert-Activation($Value,[string]$Name) {
    if (!$Value) { throw "FAIL: $Name" }
    $script:passed++;Write-Output "PASS $Name"
}
function Wait-FixtureFile([string]$Path,$Process,[int]$Milliseconds=10000) {
    $deadline=[DateTime]::UtcNow.AddMilliseconds($Milliseconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ([IO.File]::Exists($Path)) { return }
        if ($Process.HasExited) { throw ("PRECONDITION: Fixture exited unexpectedly; see "+$Path) }
        Start-Sleep -Milliseconds 50
    }
    throw "PRECONDITION: Fixture did not become ready: $Path"
}
function Send-FixtureCommand([string]$Role,[string]$Action) {
    $commandId=[Guid]::NewGuid().ToString('N')
    $roleFolder=Join-Path $folder $Role
    [IO.File]::WriteAllText((Join-Path $roleFolder 'command.txt'),($commandId+'|'+$Action),$utf8)
    $deadline=[DateTime]::UtcNow.AddSeconds(4)
    while ([DateTime]::UtcNow -lt $deadline) {
        $ackPath=Join-Path $roleFolder 'ack.txt'
        if ([IO.File]::Exists($ackPath) -and [IO.File]::ReadAllText($ackPath) -eq $commandId) { return }
        Start-Sleep -Milliseconds 50
    }
    throw "PRECONDITION: Fixture did not acknowledge $Role/$Action."
}
function Invoke-ActivationProbe([long]$Handle) {
    $script:probeNumber++
    $controllerFolder=Join-Path $folder 'controller'
    $requestPath=Join-Path $controllerFolder ("request-$script:probeNumber.json")
    $resultPath=Join-Path $controllerFolder ("result-$script:probeNumber.json")
    [IO.File]::WriteAllText($requestPath,(@{handle=$Handle}|ConvertTo-Json -Compress),$utf8)
    $deadline=[DateTime]::UtcNow.AddSeconds(12)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ([IO.File]::Exists($resultPath)) {
            $result=[IO.File]::ReadAllText($resultPath)|ConvertFrom-Json
            if ($result.error) { throw ("FAIL: Hidden STA activation probe: "+$result.error) }
            return $result
        }
        Start-Sleep -Milliseconds 50
    }
    throw 'FAIL: Hidden STA activation exceeded the integration test deadline.'
}
try {
    $fixturePath=Join-Path $folder 'fixture.ps1'
    $fixture=@'
param([string]$Role)
$ErrorActionPreference='Stop'
Add-Type -AssemblyName System.Windows.Forms
Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
public static class OwnedActivationFixture {
 [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hwnd);
 [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hwnd,int command);
}
"@
$roleFolder=Join-Path $PSScriptRoot $Role
$form=[Windows.Forms.Form]::new()
$form.Text='Agent Control Activation Fixture - '+$Role
$form.Width=460;$form.Height=160
$form.StartPosition='CenterScreen'
$label=[Windows.Forms.Label]::new()
$label.Text='Isolated activation test. No chat messages or keys are sent.'
$label.Dock='Top';$label.Height=45
$form.Controls.Add($label)
$editor=[Windows.Forms.TextBox]::new()
$editor.Text='Fixture window only';$editor.ReadOnly=$true;$editor.Dock='Bottom'
$form.Controls.Add($editor)
$script:lastCommand=''
$timer=[Windows.Forms.Timer]::new();$timer.Interval=50
$timer.Add_Tick({
    $commandPath=Join-Path $roleFolder 'command.txt'
    if (![IO.File]::Exists($commandPath)) { return }
    $command=[IO.File]::ReadAllText($commandPath)
    if ($command -eq $script:lastCommand) { return }
    $script:lastCommand=$command
    $parts=$command.Split('|')
    switch ($parts[1]) {
        front { $form.WindowState='Normal';$form.Activate();[OwnedActivationFixture]::SetForegroundWindow($form.Handle)|Out-Null }
        minimize { $form.WindowState='Minimized' }
        delay {
            # Acknowledge before delaying this fixture's GUI queue. The hidden
            # controller must wait for target activation, not just API return.
            [IO.File]::WriteAllText((Join-Path $roleFolder 'ack.txt'),$parts[0])
            [Threading.Thread]::Sleep(400)
            return
        }
        close { $timer.Stop();$form.Close() }
    }
    [IO.File]::WriteAllText((Join-Path $roleFolder 'ack.txt'),$parts[0])
})
$form.Add_Shown({
    # The process startup window is hidden, but this labelled fixture must be
    # explicitly shown before testing the visible-background-window contract.
    [OwnedActivationFixture]::ShowWindow($form.Handle,1)|Out-Null
    [IO.File]::WriteAllText((Join-Path $roleFolder 'ready.json'),(@{handle=$form.Handle.ToInt64();pid=$PID}|ConvertTo-Json -Compress))
    $timer.Start()
})
try { [Windows.Forms.Application]::Run($form) }
finally { $timer.Dispose();$form.Dispose() }
'@
    [IO.File]::WriteAllText($fixturePath,$fixture,$utf8)
    foreach ($role in @('target','decoy','controller')) { [IO.Directory]::CreateDirectory((Join-Path $folder $role))|Out-Null }
    $exe="$env:SystemRoot/System32/WindowsPowerShell/v1.0/powershell.exe"
    foreach ($role in @('target','decoy')) {
        $fixtureProcess=Start-Process -FilePath $exe -ArgumentList '-NoLogo','-NoProfile','-STA','-ExecutionPolicy','Bypass','-File',('"'+$fixturePath+'"'),'-Role',$role -WindowStyle Hidden -RedirectStandardError (Join-Path $folder ($role+'-error.txt')) -PassThru
        $processes.Add($fixtureProcess)
        Wait-FixtureFile (Join-Path $folder "$role/ready.json") $fixtureProcess
    }
    $targetInfo=[IO.File]::ReadAllText((Join-Path $folder 'target/ready.json'))|ConvertFrom-Json
    $decoyInfo=[IO.File]::ReadAllText((Join-Path $folder 'decoy/ready.json'))|ConvertFrom-Json
    $target=[IntPtr][long]$targetInfo.handle;$decoy=[IntPtr][long]$decoyInfo.handle
    $controllerNative=$native+@'

public sealed class ActivationFixtureResult {
 public bool activated;public long foreground;public long active;public long focus;public bool minimized;public long milliseconds;public string error;
}
public static class HiddenStaActivationProbe {
 [System.Runtime.InteropServices.DllImport("user32.dll")] static extern System.IntPtr GetActiveWindow();
 [System.Runtime.InteropServices.DllImport("user32.dll")] static extern System.IntPtr GetFocus();
 [System.Runtime.InteropServices.DllImport("user32.dll")] static extern bool IsIconic(System.IntPtr window);
 public static ActivationFixtureResult Run(long handle) {
  var result=new ActivationFixtureResult();
  // A fresh STA thread owns no windows and has not made a USER/GDI call.
  // Only the production helper establishes its input queue, if required.
  var worker=new System.Threading.Thread(()=>{
   try {
    var elapsed=System.Diagnostics.Stopwatch.StartNew();
    result.activated=PocketDesktop.Activate(new System.IntPtr(handle));
    result.milliseconds=elapsed.ElapsedMilliseconds;
    result.foreground=PocketDesktop.GetForegroundWindow().ToInt64();
    result.active=GetActiveWindow().ToInt64();result.focus=GetFocus().ToInt64();
    result.minimized=IsIconic(new System.IntPtr(handle));
   }catch(System.Exception exception){result.error=exception.ToString();}
  });
  worker.SetApartmentState(System.Threading.ApartmentState.STA);worker.IsBackground=true;worker.Start();
  if(!worker.Join(8000))result.error="Production activation did not return within 8 seconds.";
  return result;
 }
}
'@
    [IO.File]::WriteAllText((Join-Path $folder 'controller-native.cs'),$controllerNative,$utf8)
    $controllerPath=Join-Path $folder 'controller.ps1'
    $controller=@'
$ErrorActionPreference='Stop'
Add-Type -TypeDefinition ([IO.File]::ReadAllText((Join-Path $PSScriptRoot 'controller-native.cs'))) -ReferencedAssemblies System.dll,System.Core.dll,System.Management.dll
$controllerFolder=Join-Path $PSScriptRoot 'controller'
[IO.File]::WriteAllText((Join-Path $controllerFolder 'ready.txt'),'ready')
while (![IO.File]::Exists((Join-Path $controllerFolder 'stop.txt'))) {
    foreach ($request in @(Get-ChildItem -LiteralPath $controllerFolder -Filter 'request-*.json')) {
        $resultPath=Join-Path $controllerFolder $request.Name.Replace('request-','result-')
        if ([IO.File]::Exists($resultPath)) { continue }
        $body=[IO.File]::ReadAllText($request.FullName)|ConvertFrom-Json
        $result=[HiddenStaActivationProbe]::Run([long]$body.handle)
        $temporaryPath=$resultPath+'.tmp'
        [IO.File]::WriteAllText($temporaryPath,($result|ConvertTo-Json -Compress))
        [IO.File]::Move($temporaryPath,$resultPath)
    }
    Start-Sleep -Milliseconds 50
}
'@
    [IO.File]::WriteAllText($controllerPath,$controller,$utf8)
    $controllerProcess=Start-Process -FilePath $exe -ArgumentList '-NoLogo','-NoProfile','-STA','-ExecutionPolicy','Bypass','-File',('"'+$controllerPath+'"') -WindowStyle Hidden -RedirectStandardError (Join-Path $folder 'controller-error.txt') -PassThru
    $processes.Add($controllerProcess)
    Wait-FixtureFile (Join-Path $folder 'controller/ready.txt') $controllerProcess
    Write-Output ("INFO Fixture HWNDs: target=$($target.ToInt64()); decoy=$($decoy.ToInt64())")
    Send-FixtureCommand 'decoy' 'front'
    if ([ActivationFixtureObserver]::GetForegroundWindow() -ne $decoy) { throw 'PRECONDITION: Windows did not grant the decoy fixture foreground. No product assertion was attempted.' }
    if (![ActivationFixtureObserver]::IsWindowVisible($target)) { throw 'PRECONDITION: Target fixture is not natively visible.' }
    Send-FixtureCommand 'target' 'delay'
    $normal=Invoke-ActivationProbe $target.ToInt64()
    Write-Output ('INFO Delayed activation result: '+($normal|ConvertTo-Json -Compress))
    Assert-Activation ($normal.activated -and $normal.foreground -eq $target.ToInt64()) 'hidden STA background caller waits for a delayed target queue and verifies foreground'
    Assert-Activation ($normal.active -eq 0 -and $normal.focus -eq 0) 'activation releases shared input state before returning'
    $repeat=Invoke-ActivationProbe $target.ToInt64()
    Assert-Activation ($repeat.activated -and $repeat.foreground -eq $target.ToInt64()) 'already foreground target remains a verified success'
    Assert-Activation ($repeat.active -eq 0 -and $repeat.focus -eq 0) 'repeated activation leaves no controller focus or active window'
    Send-FixtureCommand 'target' 'minimize'
    if (![ActivationFixtureObserver]::IsIconic($target)) { throw 'PRECONDITION: Target fixture did not minimize.' }
    Send-FixtureCommand 'decoy' 'front'
    if ([ActivationFixtureObserver]::GetForegroundWindow() -ne $decoy) { throw 'PRECONDITION: Decoy fixture did not regain foreground before restore test.' }
    $restored=Invoke-ActivationProbe $target.ToInt64()
    Assert-Activation ($restored.activated -and !$restored.minimized -and $restored.foreground -eq $target.ToInt64()) 'minimized target restores before successful activation returns'
    Assert-Activation ($restored.active -eq 0 -and $restored.focus -eq 0) 'minimized restoration releases shared input state'
    $invalid=Invoke-ActivationProbe 0
    Assert-Activation (!$invalid.activated -and $invalid.foreground -eq $target.ToInt64()) 'null target fails without changing foreground'
    Send-FixtureCommand 'target' 'close'
    if (!$processes[0].WaitForExit(3000) -or [ActivationFixtureObserver]::IsWindow($target)) { throw 'PRECONDITION: Target fixture did not close.' }
    Send-FixtureCommand 'decoy' 'front'
    if ([ActivationFixtureObserver]::GetForegroundWindow() -ne $decoy) { throw 'PRECONDITION: Decoy fixture did not regain foreground before closed handle test.' }
    $closed=Invoke-ActivationProbe $target.ToInt64()
    Assert-Activation (!$closed.activated -and $closed.foreground -eq $decoy.ToInt64()) 'closed target fails without activating another window'
    Write-Output "ALL $script:passed NATIVE ACTIVATION CHECKS PASSED"
} finally {
    if ([IO.Directory]::Exists((Join-Path $folder 'controller'))) { [IO.File]::WriteAllText((Join-Path $folder 'controller/stop.txt'),'stop') }
    foreach ($role in @('target','decoy')) {
        $roleFolder=Join-Path $folder $role
        if ([IO.Directory]::Exists($roleFolder)) { [IO.File]::WriteAllText((Join-Path $roleFolder 'command.txt'),([Guid]::NewGuid().ToString('N')+'|close'),$utf8) }
    }
    foreach ($fixtureProcess in $processes) {
        if (!$fixtureProcess.HasExited -and !$fixtureProcess.WaitForExit(2000)) { $fixtureProcess.Kill();$fixtureProcess.WaitForExit(2000)|Out-Null }
        $fixtureProcess.Dispose()
    }
    $resolved=[IO.Path]::GetFullPath($folder)
    if (!$resolved.StartsWith($temporaryRoot+'\agent-control-activation-test-',[StringComparison]::OrdinalIgnoreCase)) { throw 'Refusing cleanup outside the isolated fixture directory.' }
    if ([IO.Directory]::Exists($resolved)) { [IO.Directory]::Delete($resolved,$true) }
}
