$ErrorActionPreference='Stop'
$repo=(Resolve-Path "$PSScriptRoot/../../..").Path
$clientPath=Join-Path $repo 'android-app/app/src/main/assets/desktop-windows-client.ps1'
$tokens=$null;$parseErrors=$null
$ast=[Management.Automation.Language.Parser]::ParseInput([IO.File]::ReadAllText($clientPath,[Text.Encoding]::UTF8),[ref]$tokens,[ref]$parseErrors)
if($parseErrors.Count){throw 'Production desktop client has syntax errors'}
foreach($name in @('Resolve-BrokerRoot','Read-Broker')) {
    $definition=$ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $name},$true)
    if(@($definition).Count -ne 1){throw "Missing unique production function: $name"}
    Invoke-Expression $definition[0].Extent.Text
}
$endPath=Join-Path $repo 'android-app/app/src/main/assets/desktop-windows-end.ps1'
$endAst=[Management.Automation.Language.Parser]::ParseInput([IO.File]::ReadAllText($endPath,[Text.Encoding]::UTF8),[ref]$tokens,[ref]$parseErrors)
if($parseErrors.Count){throw 'Production end-conversation client has syntax errors'}
$endReader=$endAst.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Read-EndBrokerStatus'},$true)
if(@($endReader).Count -ne 1){throw 'Missing production end-conversation status reader'}
Invoke-Expression $endReader[0].Extent.Text
$script:passed=0
function Assert($Value,[string]$Name){if(!$Value){throw "FAIL: $Name"};$script:passed++;Write-Output "PASS $Name"}
function Expect-Failure([scriptblock]$Action,[string]$Pattern,[string]$Name){
    $failure='';try{& $Action|Out-Null}catch{$failure=$_.Exception.Message}
    Assert ($failure -match $Pattern) $Name|Out-Host
    return $failure
}
$fixtureRoot=Join-Path ([IO.Path]::GetTempPath()) ('agent-control-broker-test-'+[Guid]::NewGuid().ToString('N'))
$script:fixtureDirectories=New-Object 'Collections.Generic.List[string]'
$script:fixtureFiles=New-Object 'Collections.Generic.List[string]'
function Ensure-FixtureDirectory([string]$Path){
    if([IO.Directory]::Exists($Path)){return}
    if($Path -cne $fixtureRoot){Ensure-FixtureDirectory ([IO.Path]::GetDirectoryName($Path))}
    [IO.Directory]::CreateDirectory($Path)|Out-Null;$script:fixtureDirectories.Add($Path)
}
function Track-FixtureFile([string]$Path){if(!$script:fixtureFiles.Contains($Path)){$script:fixtureFiles.Add($Path)}}
$utf8=New-Object Text.UTF8Encoding($false)
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
$script:waits=0;$script:retryAction=$null;$script:fixtureLock=$null
# Only replace waiting in this isolated test process. All actual filesystem reads,
# locks, parsing, identity checks and root selection are the production functions.
function Start-Sleep([int]$Milliseconds){
    if($Milliseconds -ne 75){throw "Unexpected retry delay: $Milliseconds"}
    $script:waits++;if($script:retryAction){& $script:retryAction}
}
function Reset-Retry{$script:waits=0;$script:retryAction=$null}
function Write-Status([string]$Directory,[hashtable]$Changes=@{}){
    $status=@{protocol=1;userSid=$sid;updatedUtc=[DateTime]::UtcNow.ToString('o');ready=$true;busy=$false;brokerId='broker-fixture';reason='';apps=@();fileUploads=$true}
    foreach($key in $Changes.Keys){$status[$key]=$Changes[$key]}
    $path=Join-Path $Directory 'status.json';Track-FixtureFile $path
    [IO.File]::WriteAllText($path,($status|ConvertTo-Json -Depth 5 -Compress),$utf8)
}
function Invoke-Probe([string]$DataRoot,[string]$LocalRoot){
    $start=New-Object Diagnostics.ProcessStartInfo
    $start.FileName=Join-Path $env:SystemRoot 'System32/WindowsPowerShell/v1.0/powershell.exe'
    $start.Arguments='-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "'+$clientPath+'" -ProbeOnly'
    $start.UseShellExecute=$false;$start.CreateNoWindow=$true
    $start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
    $start.StandardOutputEncoding=$utf8;$start.StandardErrorEncoding=$utf8
    $start.EnvironmentVariables['ProgramData']=$DataRoot
    $start.EnvironmentVariables['LOCALAPPDATA']=$LocalRoot
    $process=New-Object Diagnostics.Process;$process.StartInfo=$start
    try{
        $process.Start()|Out-Null
        $output=$process.StandardOutput.ReadToEnd();$errors=$process.StandardError.ReadToEnd()
        if(!$process.WaitForExit(15000)){$process.Kill();throw 'Read-only probe did not exit'}
        return @{code=$process.ExitCode;output=$output;errors=$errors;event=($output|ConvertFrom-Json)}
    }finally{$process.Dispose()}
}
try{
    Ensure-FixtureDirectory $fixtureRoot
    $shared=Join-Path $fixtureRoot 'shared';$legacy=Join-Path $fixtureRoot 'legacy'
    Ensure-FixtureDirectory $shared;Ensure-FixtureDirectory $legacy
    Write-Status $legacy
    Assert ((Resolve-BrokerRoot $shared $legacy) -ceq $shared) 'installed shared directory stays selected while status is absent'
    Assert ((Resolve-BrokerRoot (Join-Path $fixtureRoot 'not-installed') $legacy) -ceq $legacy) 'legacy queue remains compatible when the shared directory is not installed'
    $script:root=Resolve-BrokerRoot $shared $legacy
    Write-Status $shared;Reset-Retry
    Assert ((Read-Broker).brokerId -eq 'broker-fixture' -and $script:waits -eq 0) 'valid shared status reads without retry'

    $statusPath=Join-Path $shared 'status.json'
    [IO.File]::Delete($statusPath);Reset-Retry
    $script:retryAction={Write-Status $shared}
    Assert ((Read-Broker).brokerId -eq 'broker-fixture' -and $script:waits -eq 1) 'a heartbeat missing-file window is retried before declaring the broker absent'
    Assert ($script:root -ceq $shared) 'missing status never redirects an installed shared queue to the legacy queue'

    Reset-Retry
    $script:fixtureLock=[IO.File]::Open($statusPath,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $script:retryAction={$script:fixtureLock.Dispose();$script:fixtureLock=$null}
    Assert ((Read-Broker).brokerId -eq 'broker-fixture' -and $script:waits -eq 1) 'one real sharing violation is retried and recovers'

    [IO.File]::WriteAllText($statusPath,'{partial',$utf8);Reset-Retry
    $script:retryAction={Write-Status $shared}
    Assert ((Read-Broker).brokerId -eq 'broker-fixture' -and $script:waits -eq 1) 'one corrupt status snapshot is retried and recovers'
    [IO.File]::WriteAllText($statusPath,'',$utf8);Reset-Retry
    $script:retryAction={Write-Status $shared}
    Assert ((Read-Broker).brokerId -eq 'broker-fixture' -and $script:waits -eq 1) 'one empty status snapshot is retried and recovers'

    Write-Status $shared @{updatedUtc=[DateTime]::UtcNow.AddSeconds(-30).ToString('o')};Reset-Retry
    Expect-Failure {Read-Broker} '已离线|不属于' 'stale heartbeat remains rejected'|Out-Null
    Assert ($script:waits -eq 0) 'a readable stale heartbeat is not confused with transient I/O'
    Write-Status $shared @{userSid='S-1-5-18'}
    Expect-Failure {Read-Broker} '已离线|不属于' 'another user SID remains rejected'|Out-Null
    Write-Status $shared @{protocol=9}
    Expect-Failure {Read-Broker} '已离线|不属于' 'unsupported protocol remains rejected'|Out-Null
    Write-Status $shared @{ready=$false;reason='locked-fixture'}
    Expect-Failure {Read-Broker} 'Windows 桌面不可用.*locked-fixture' 'a locked or unavailable interactive desktop remains rejected'|Out-Null

    [IO.File]::Delete($statusPath);Reset-Retry
    $missing=Expect-Failure {Read-Broker} 'Windows 桌面桥接未运行' 'sustained missing status fails after bounded retries'
    Assert ($script:waits -eq 7 -and $missing.Contains($statusPath)) 'missing failure reports the actual queue path after all eight attempts'
    Assert ($script:root -ceq $shared) 'a healthy legacy fixture cannot bypass the missing installed shared broker'

    Write-Status $shared;Reset-Retry
    $script:fixtureLock=[IO.File]::Open($statusPath,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $unreadable=Expect-Failure {Read-Broker} '状态暂时无法读取' 'sustained sharing failure reports unreadable rather than absent'
    Assert ($script:waits -eq 7 -and $unreadable.Contains($statusPath)) 'unreadable failure is bounded and identifies the queue path'
    $script:fixtureLock.Dispose();$script:fixtureLock=$null

    [IO.File]::Delete($statusPath);Reset-Retry
    $script:retryAction={Write-Status $shared}
    Assert ((Read-EndBrokerStatus).brokerId -eq 'broker-fixture' -and $script:waits -eq 1) 'end-conversation reader recovers a transient missing status without sending anything'
    Reset-Retry
    $script:fixtureLock=[IO.File]::Open($statusPath,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $script:retryAction={$script:fixtureLock.Dispose();$script:fixtureLock=$null}
    Assert ((Read-EndBrokerStatus).brokerId -eq 'broker-fixture' -and $script:waits -eq 1) 'end-conversation reader recovers a real sharing violation'
    [IO.File]::WriteAllText($statusPath,'',$utf8);Reset-Retry
    $script:retryAction={Write-Status $shared}
    Assert ((Read-EndBrokerStatus).brokerId -eq 'broker-fixture' -and $script:waits -eq 1) 'end-conversation reader retries an empty snapshot'

    # Run the complete production script in a child using only isolated roots.
    # The unexpanded request placeholder proves the probe exits before decoding.
    $probeData=Join-Path $fixtureRoot 'program-data';$probeLocal=Join-Path $fixtureRoot 'local-data'
    $probeShared=Join-Path $probeData ('AgentControl/'+$sid+'/bridge')
    $probeLegacy=Join-Path $probeLocal 'OrcaPocket/bridge'
    Ensure-FixtureDirectory $probeShared;Ensure-FixtureDirectory $probeLegacy
    Write-Status $probeShared;Write-Status $probeLegacy
    $probe=Invoke-Probe $probeData $probeLocal
    Assert ($probe.code -eq 0 -and $probe.event.type -eq 'desktop.broker' -and $probe.event.root -ceq $probeShared -and $probe.event.userSid -ceq $sid) 'read-only probe uses the shared queue and reports its validated identity'
    Assert (@(Get-ChildItem -LiteralPath $probeShared -File).Count -eq 1) 'successful probe creates no desktop request, lease, cancel or event files'
    Write-Status $probeShared @{userSid='S-1-5-18'}
    $probe=Invoke-Probe $probeData $probeLocal
    Assert ($probe.code -ne 0 -and $probe.event.type -eq 'error') 'read-only probe cannot bypass SID validation'
    Assert (@(Get-ChildItem -LiteralPath $probeShared -File).Count -eq 1) 'rejected probe also creates no desktop queue files'
    [IO.File]::Delete((Join-Path $probeShared 'status.json'))
    $probe=Invoke-Probe $probeData $probeLocal
    Assert ($probe.code -ne 0 -and $probe.event.message -match 'Windows 桌面桥接未运行') 'probe does not fall back to a healthy legacy status during sustained shared absence'
    Assert (@(Get-ChildItem -LiteralPath $probeShared -File).Count -eq 0) 'missing-status probe does not create any queue files'

    $source=[IO.File]::ReadAllText($clientPath)
    Assert ($source -match 'if\s*\(\$current\.brokerId\s*-ne\s*\$status\.brokerId\)\s*\{\s*throw') 'normal execution still rejects a restarted broker without resubmitting'
    Write-Output "ALL $script:passed BROKER READ CHECKS PASSED"
}finally{
    if($script:fixtureLock){$script:fixtureLock.Dispose()}
    foreach($file in $script:fixtureFiles){if([IO.File]::Exists($file)){[IO.File]::Delete($file)}}
    for($index=$script:fixtureDirectories.Count-1;$index -ge 0;$index--){[IO.Directory]::Delete($script:fixtureDirectories[$index],$false)}
}
