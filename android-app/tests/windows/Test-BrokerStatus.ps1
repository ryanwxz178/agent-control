$ErrorActionPreference='Stop'
$repo=(Resolve-Path "$PSScriptRoot\..\..\..").Path
. "$repo/windows/Protocol.ps1"
$script:passed=0
function Assert($Value,[string]$Name){if(!$Value){throw "FAIL: $Name"};$script:passed++;Write-Output "PASS $Name"}
Add-Type -TypeDefinition @'
using System;
using System.IO;
using System.Text;
using System.Threading;
using System.Collections.Generic;
using System.Web.Script.Serialization;
public sealed class LegacyBrokerStatusReport {
 public int Updates, IoFailures;
 public string LastError;
}
public sealed class BrokerStatusProbe : IDisposable {
 readonly string path, first, second; readonly bool parse;
 readonly ManualResetEvent started=new ManualResetEvent(false);
 volatile bool running; Thread thread;
 public long Samples, Missing, Valid, Empty, RetryExhausted, Mixed;
 public string Error;
 public BrokerStatusProbe(string path,string first,string second,bool parse){this.path=path;this.first=first;this.second=second;this.parse=parse;}
 public void Start(){running=true;thread=new Thread(ReadLoop);thread.IsBackground=true;thread.Start();if(!started.WaitOne(3000))throw new TimeoutException("Reader did not start");}
 void ReadLoop(){
  var serializer=new JavaScriptSerializer();serializer.MaxJsonLength=500000;started.Set();
  try {
   while(running){
    Interlocked.Increment(ref Samples);
    // This is intentionally outside the retry, like the already installed phone client.
    if(!File.Exists(path)){Interlocked.Increment(ref Missing);Thread.Yield();continue;}
    if(!parse){Thread.Yield();continue;}
    bool accepted=false;
    for(int attempt=0;attempt<8;attempt++){
     try {
      string text;
      using(var stream=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.ReadWrite|FileShare.Delete))
      using(var reader=new StreamReader(stream,new UTF8Encoding(false,true))){text=reader.ReadToEnd();}
      if(String.IsNullOrWhiteSpace(text)){Interlocked.Increment(ref Empty);throw new InvalidDataException("Empty status");}
      var value=serializer.DeserializeObject(text) as Dictionary<string,object>;
      if(value==null||!value.ContainsKey("protocol")||Convert.ToInt32(value["protocol"])!=1||
       !value.ContainsKey("brokerId")||((string)value["brokerId"]).Length!=32||
       !value.ContainsKey("userSid")||!(value["userSid"] is string)||
       !value.ContainsKey("sessionId")||Convert.ToInt32(value["sessionId"])<=0||
       !value.ContainsKey("ready")||!(value["ready"] is bool)||
       !value.ContainsKey("busy")||!(value["busy"] is bool)||
       !value.ContainsKey("apps")||!(value["apps"] is object[])||
       !value.ContainsKey("updatedUtc")||Math.Abs((DateTime.UtcNow-DateTime.Parse((string)value["updatedUtc"]).ToUniversalTime()).TotalSeconds)>15)
       throw new InvalidDataException("Incomplete status");
      if(text!=first&&text!=second)Interlocked.Increment(ref Mixed);
      Interlocked.Increment(ref Valid);accepted=true;break;
     }catch {if(attempt<7)Thread.Sleep(75);}
    }
    if(!accepted)Interlocked.Increment(ref RetryExhausted);
   }
  }catch(Exception e){Error=e.ToString();}
 }
 public static LegacyBrokerStatusReport LegacyReplace(string path,string first,string second,int count){
  var utf8=new UTF8Encoding(false);
  var report=new LegacyBrokerStatusReport();
  for(int i=0;i<count;i++){
   try {File.WriteAllText(path+".tmp",(i&1)==0?first:second,utf8);File.Replace(path+".tmp",path,path+".previous");report.Updates++;}
   catch(IOException e){report.IoFailures++;report.LastError=e.Message;break;}
  }
  return report;
 }
 public void Stop(){running=false;if(thread!=null&&!thread.Join(3000))throw new TimeoutException("Reader did not stop");}
 public void Dispose(){Stop();started.Dispose();}
}
'@ -ReferencedAssemblies System.dll,System.Core.dll,System.Web.Extensions.dll
$folder=Join-Path ([IO.Path]::GetTempPath()) ('agent-control-status-test-'+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($folder)|Out-Null
$path=Join-Path $folder 'status.json';$legacyPath=$path;$newPath=Join-Path $folder 'new-status.json';$probe=$null;$held=$null
$utf8=[Text.UTF8Encoding]::new($false)
$brokerA=[Guid]::NewGuid().ToString('N');$brokerB=[Guid]::NewGuid().ToString('N')
function StatusJson([string]$Broker,[string]$Reason,[bool]$Busy){
    return (@{protocol=1;fileUploads=$true;brokerId=$Broker;userSid='S-1-5-21-100-200-300-1001';sessionId=1;updatedUtc=[DateTime]::UtcNow.ToString('o');ready=$true;reason=$Reason;busy=$Busy;apps=@(@{id='workbuddy';path='C:/test/WorkBuddy.exe';fileUploads=$true})}|ConvertTo-Json -Depth 5 -Compress)
}
try {
    $long=StatusJson $brokerA ('长状态'+('a'*4096)) $true
    $short=StatusJson $brokerA '短状态' $false
    Write-BrokerStatus $path $long
    Assert ([IO.File]::ReadAllText($path,$utf8) -ceq $long) 'first status publication creates valid UTF8 JSON'
    Write-BrokerStatus $path $short
    Assert ([IO.File]::ReadAllText($path,$utf8) -ceq $short -and ([IO.FileInfo]$path).Length -eq $utf8.GetByteCount($short)) 'long-to-short heartbeat clears all trailing bytes without deleting the filename'
    # Reproduce the old filename gap with the actual File.Replace API, without a bridge or UI.
    $probe=[BrokerStatusProbe]::new($legacyPath,$long,$short,$false)
    try {
        $probe.Start()
        # A replacement may itself fail while a Windows name lookup is in flight.
        # Record that old-scheme race rather than hiding the new writer's tests.
        $legacy=[BrokerStatusProbe]::LegacyReplace($legacyPath,$long,$short,2500)
    } finally {
        $probe.Stop()
        $legacyRead=@{samples=$probe.Samples;missing=$probe.Missing;error=$probe.Error}
        $probe.Dispose();$probe=$null
    }
    Assert ($legacyRead.samples -gt 0 -and !$legacyRead.error) 'concurrent old-client existence probe ran during bounded legacy publication'
    if($env:OS -eq 'Windows_NT'){Assert ($legacyRead.missing -gt 0 -or $legacy.IoFailures -gt 0) 'Windows legacy replacement reproduces a missing path or replacement I/O race'}
    Write-Output ('Legacy probe: samples='+$legacyRead.samples+' missing='+$legacyRead.missing+' updates='+$legacy.Updates+' ioFailures='+$legacy.IoFailures+' lastError='+$legacy.LastError)
    # Publish both lengths repeatedly through the exact production helper. A held
    # read handle also checks compatibility with the phone share flags.
    # Use a freshly initialized independent file: failed legacy replacements can
    # leave their destination in a delete-pending state, unrelated to our writer.
    $path=$newPath
    $long=StatusJson $brokerA ('长状态'+('b'*4096)) $true;$short=StatusJson $brokerA '短状态' $false
    Write-BrokerStatus $path $short
    $probe=[BrokerStatusProbe]::new($path,$long,$short,$true);$probe.Start()
    for($i=0;$i -lt 750;$i++){if($i%2){Write-BrokerStatus $path $short}else{Write-BrokerStatus $path $long}}
    Start-Sleep -Milliseconds 100
    $probe.Stop()
    Assert ($probe.Samples -gt 10 -and $probe.Valid -gt 0 -and !$probe.Error) 'old-client schema and freshness reader accepts complete status while production writer updates'
    Assert ($probe.Missing -eq 0) 'in-place publication never removes the path before the installed phone retry loop'
    Assert ($probe.Empty -eq 0 -and $probe.Mixed -eq 0 -and $probe.RetryExhausted -eq 0) 'concurrent readers accept only whole known snapshots and recover within eight 75ms retries'
    Write-Output ('New probe: samples='+$probe.Samples+' valid='+$probe.Valid+' missing='+$probe.Missing+' empty='+$probe.Empty+' mixed='+$probe.Mixed+' exhausted='+$probe.RetryExhausted)
    $probe.Dispose();$probe=$null
    $held=[IO.File]::Open($path,[IO.FileMode]::Open,[IO.FileAccess]::Read,([IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete))
    Write-BrokerStatus $path $short
    Assert ($held.Length -eq $utf8.GetByteCount($short)) 'a reader holding the original file handle sees the updated file rather than a replaced stale inode'
    $held.Dispose();$held=$null
    $before=[IO.File]::ReadAllText($path,$utf8)
    $held=[IO.File]::Open($path,[IO.FileMode]::Open,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $watch=[Diagnostics.Stopwatch]::StartNew();$failed=$false
    try{Write-BrokerStatus $path $long}catch [IO.IOException]{$failed=$true}
    $watch.Stop();$held.Dispose();$held=$null
    Assert ($failed -and $watch.ElapsedMilliseconds -ge 180 -and $watch.ElapsedMilliseconds -lt 1500) 'persistent exclusive sharing lock fails after bounded retries'
    Assert ([IO.File]::ReadAllText($path,$utf8) -ceq $before) 'a failed write-open preserves the prior complete published status'
    $failed=$false;try{Write-BrokerStatus $path ''}catch{$failed=$true}
    Assert ($failed -and [IO.File]::ReadAllText($path,$utf8) -ceq $before) 'empty heartbeat is rejected before touching the existing snapshot'
    $fresh=StatusJson $brokerB '重启' $false;Write-BrokerStatus $path $fresh
    $status=[IO.File]::ReadAllText($path,$utf8)|ConvertFrom-Json
    Assert ($status.brokerId -ceq $brokerB -and $status.brokerId -cne $brokerA -and $status.apps.Count -eq 1 -and !$status.busy) 'a restarted broker publishes a fresh identity and complete app inventory on the same stable path'
    $request=@{protocol=1;id=('a'*32);brokerId=$brokerA;createdUtc=[DateTime]::UtcNow.ToString('o');prompt='不重发'}
    $failed=$false;try{Assert-FreshRequest $request ('a'*32) $status.brokerId}catch{$failed=$true}
    Assert $failed 'stable status filename never allows a pre-restart task to run under the new broker'
} finally {
    if($probe){$probe.Dispose()};if($held){$held.Dispose()}
    # The absolute fixture directory was created by this test, with no nested directories.
    foreach($file in @($legacyPath,($legacyPath+'.tmp'),($legacyPath+'.previous'),$newPath,($newPath+'.tmp'),($newPath+'.previous'))){if([IO.File]::Exists($file)){[IO.File]::Delete($file)}}
    [IO.Directory]::Delete($folder,$false)
}
Write-Output "ALL $script:passed BROKER STATUS CHECKS PASSED"
