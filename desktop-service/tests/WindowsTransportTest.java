package dev.orca.pocket;
import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;
import java.io.OutputStream;import java.nio.charset.StandardCharsets;
public final class WindowsTransportTest {
 public static void main(String[] args)throws Exception {
  if(!System.getProperty("os.name").startsWith("Windows"))return;
  try(LocalTransport transport=new LocalTransport()){
   for(String prefix:Arrays.asList("","\uFEFF")){
    List<String> lines=new ArrayList<>();
    transport.runWindowsScript(prefix+"$ErrorActionPreference='Stop'; Write-Output 'SCRIPT_OK'",lines::add,new AtomicBoolean(),15000);
    if(!lines.equals(Arrays.asList("SCRIPT_OK")))throw new AssertionError("A UTF-8 BOM must never become a PowerShell command: "+lines);
   }
   Inventory inventory=new Inventory();
   transport.runWindowsScript(TaskService.asset("detect-windows.ps1"),inventory::accept,new AtomicBoolean(),20000);
   if(!inventory.complete||!inventory.os.equals("Windows"))throw new AssertionError("Actual Windows inventory must complete");
   String expected="中文 duplex \"quoted\" "+"x".repeat(1000);
   String js="require('readline').createInterface({input:process.stdin}).on('line',line=>{console.log(line);});";
   List<String> runtimes=new ArrayList<>();runtimes.add("C:/Program Files/nodejs/node.exe");
   String electron=System.getenv("AGENT_CONTROL_TEST_ELECTRON");if(electron!=null&&!electron.isEmpty())runtimes.add(electron);
   for(String runtime:runtimes)transport.interactiveWindowsScript(Agent.windowsNodeScript(runtime,js,runtime.equals(electron)),new SshTransport.Duplex(){
    public void start(OutputStream out)throws Exception{out.write((expected+"\n").getBytes(StandardCharsets.UTF_8));out.flush();}
    public boolean accept(String line){if(!line.equals(expected))throw new AssertionError("Windows duplex must preserve UTF-8 input and output");return true;}
   },new AtomicBoolean(),15000);
   System.out.println("PASS Windows computer service executes scripts with and without existing BOM, and completes real agent detection");
   System.out.println("PASS Windows uploaded-script duplex preserves UTF-8 input/output and cleans up its child");
  }
 }
}
