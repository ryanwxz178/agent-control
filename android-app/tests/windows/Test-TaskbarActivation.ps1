# Opt-in live integration test: activates WorkBuddy, without reading or sending chat.
# Put WorkBuddy in the background first; run in Windows PowerShell 5.1 -STA.
param([Parameter(Mandatory=$true)][string]$AppPath)
$ErrorActionPreference='Stop'
if($env:OS -ne 'Windows_NT' -or [Threading.Thread]::CurrentThread.ApartmentState -ne 'STA'){
    throw 'Run with Windows PowerShell -STA on an unlocked desktop.'
}
$repo=(Resolve-Path "$PSScriptRoot/../../..").Path
. "$repo/windows/Desktop.ps1"
$adapter=(Get-Content "$repo/windows/profiles.json" -Raw -Encoding UTF8|ConvertFrom-Json).workbuddy
$targets=@(Get-Process WorkBuddy -ErrorAction SilentlyContinue|Where-Object {
    $_.MainWindowHandle -ne 0 -and $_.Path -eq $AppPath -and
    $_.SessionId -eq [Diagnostics.Process]::GetCurrentProcess().SessionId
})
if($targets.Count -ne 1){throw 'Exactly one matching WorkBuddy window is required.'}
$target=$targets[0].MainWindowHandle
$window=[Windows.Automation.AutomationElement]::FromHandle($target)
$before=[PocketDesktop]::GetForegroundWindow()
if($before -eq $target){throw 'Precondition: WorkBuddy must be in the background.'}
$missing=[pscustomobject]@{taskbarId='Appid: AgentControl.Missing.Test.Fixture'}
if(Set-TaskbarWindowForeground $window $missing){throw 'An unmatched taskbar ID was accepted.'}
if([PocketDesktop]::GetForegroundWindow() -ne $before){throw 'Unmatched taskbar ID changed focus.'}
if([PocketDesktop]::ClickTaskbar([IntPtr]::Zero,$target,0,0)){
    throw 'Invalid shell handle was accepted.'
}
if([PocketDesktop]::GetForegroundWindow() -ne $before){throw 'Invalid shell handle changed focus.'}
$watch=[Diagnostics.Stopwatch]::StartNew()
$ok=Set-TaskbarWindowForeground $window $adapter
$after=[PocketDesktop]::GetForegroundWindow()
if(!$ok -or $after -ne $target){throw 'Production taskbar activation did not focus WorkBuddy.'}
[pscustomobject]@{Before=$before.ToInt64();Target=$target.ToInt64();After=$after.ToInt64();
    Passed=$true;Milliseconds=$watch.ElapsedMilliseconds;UnmatchedIdRejected=$true;InvalidShellRejected=$true
}|ConvertTo-Json -Compress
