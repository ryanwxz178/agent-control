# Runs only an isolated fixture window, never an installed chat APP.
$ErrorActionPreference='Stop'
if($env:OS -ne 'Windows_NT' -or [Threading.Thread]::CurrentThread.ApartmentState -ne 'STA'){throw 'Run this native picker test with Windows PowerShell -STA.'}
$repo=(Resolve-Path "$PSScriptRoot/../../..").Path
. "$repo/windows/Desktop.ps1"
if(Get-DesktopHealth){throw 'Native picker fixture needs the current user desktop unlocked.'}
$temporaryRoot=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$folder=Join-Path $temporaryRoot ('agent-control-picker-test-'+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($folder)|Out-Null
$process=$null
try {
    $inputFolder=Join-Path $folder '.agent-control-input'
    [IO.Directory]::CreateDirectory($inputFolder)|Out-Null
    $files=@((Join-Path $inputFolder ([Guid]::NewGuid().ToString()+'-中文报告.txt')),(Join-Path $inputFolder ([Guid]::NewGuid().ToString()+'-with spaces.txt')))
    foreach($file in $files){[IO.File]::WriteAllText($file,'fixture contents',[Text.UTF8Encoding]::new($false))}
    $fixturePath=Join-Path $folder 'fixture.ps1'
    $fixture=@'
Add-Type -AssemblyName System.Windows.Forms
$form=[Windows.Forms.Form]::new()
$form.Text='Agent Control File Picker Fixture'
$form.Width=500;$form.Height=180
$form.Add_Shown({
    [IO.File]::WriteAllText("$PSScriptRoot/owner.txt",$form.Handle.ToInt64().ToString())
    $form.BeginInvoke([Action]{
        $dialog=[Windows.Forms.OpenFileDialog]::new()
        $dialog.Multiselect=$true;$dialog.Title='Agent Control Native Picker Fixture'
        $dialog.ShowDialog($form)|Out-Null
        [IO.File]::WriteAllLines("$PSScriptRoot/selected.txt",$dialog.FileNames,[Text.UTF8Encoding]::new($false))
        $dialog.Dispose();$form.Close()
    })|Out-Null
})
[Windows.Forms.Application]::Run($form)
'@
    [IO.File]::WriteAllText($fixturePath,$fixture,[Text.UTF8Encoding]::new($true))
    $process=Start-Process "$env:SystemRoot/System32/WindowsPowerShell/v1.0/powershell.exe" -ArgumentList '-NoProfile','-STA','-File',('"'+$fixturePath+'"') -WindowStyle Hidden -RedirectStandardError "$folder/error.txt" -PassThru
    $owner=[IntPtr]::Zero;$dialog=[IntPtr]::Zero
    for($attempt=0;$attempt -lt 40;$attempt++) {
        if(Test-Path "$folder/owner.txt") {
            $owner=[IntPtr][long](Get-Content "$folder/owner.txt")
            $handles=@([PocketDesktop]::FileDialogsForOwner($owner))
            if($handles.Count -eq 1 -and @([PocketDesktop]::NativeFileControls($handles[0],$owner)).Count -eq 2){$dialog=$handles[0];break}
        }
        Start-Sleep -Milliseconds 200
    }
    if($dialog -eq [IntPtr]::Zero){throw ('Fixture picker did not open: '+(Get-Content "$folder/error.txt" -Raw -ErrorAction SilentlyContinue))}
    if(@([PocketDesktop]::NativeFileControls($dialog,[IntPtr]::Zero)).Count){throw 'Unrelated owner incorrectly accepted'}
    $selection=($files | ForEach-Object { '"'+$_+'"' }) -join ' '
    if(![PocketDesktop]::SelectNativeFiles($dialog,$owner,$selection)){throw 'Verified fixture selection failed'}
    if(!$process.WaitForExit(5000)){throw 'Fixture picker did not complete'}
    $selected=[IO.File]::ReadAllLines("$folder/selected.txt",[Text.Encoding]::UTF8)
    if($selected.Count -ne 2 -or $selected[0] -cne $files[0] -or $selected[1] -cne $files[1]){throw 'Native picker selected different files'}
    Write-Output 'PASS isolated Windows file picker: owner chain, unrelated owner rejected, exact two files with Unicode/spaces, bounded text write/readback and one Open action.'
} finally {
    if($process -and !$process.HasExited){Stop-Process -Id $process.Id -Force}
    $resolved=[IO.Path]::GetFullPath($folder)
    if(!$resolved.StartsWith($temporaryRoot+'\agent-control-picker-test-',[StringComparison]::OrdinalIgnoreCase)){throw 'Refusing cleanup outside fixture temp directory'}
    if([IO.Directory]::Exists($resolved)){[IO.Directory]::Delete($resolved,$true)}
}
