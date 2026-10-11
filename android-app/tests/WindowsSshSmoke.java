package dev.orca.pocket;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional real Windows OpenSSH acceptance; takes host, port, user, key file, pinned SHA256 fingerprint. */
public final class WindowsSshSmoke {
    public static void main(String[] args)throws Exception {
        if(args.length!=5)throw new IllegalArgumentException("host port user keyFile SHA256Fingerprint required");
        try(SshTransport ssh=new SshTransport()) {
            ssh.connect(args[0],Integer.parseInt(args[1]),args[2],"",Files.readAllBytes(Paths.get(args[3])),(host,fp)->fp.equals(args[4]));
            String text=String.join("",Collections.nCopies(50000,"x"))+"中文\n第二行 ' $(`echo test`)";
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hash=new StringBuilder();for(byte b:digest)hash.append(String.format("%02x",b&255));
            String script="$v="+Agent.psQuote(text)+";$sha=[Security.Cryptography.SHA256]::Create();try{$hash=([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($v)))).Replace('-','').ToLowerInvariant();if($hash -ne '"+hash+"'){throw 'UTF-8 payload mismatch'}}finally{$sha.Dispose()};[Console]::WriteLine('LONG_UTF8_SCRIPT_OK')";
            List<String> lines=new ArrayList<>();ssh.runWindowsScript(script,lines::add,new AtomicBoolean(),45000);
            if(!lines.contains("LONG_UTF8_SCRIPT_OK\r")&&!lines.contains("LONG_UTF8_SCRIPT_OK"))throw new AssertionError("No payload confirmation");
            System.out.println("PASS 50KB UTF-8 script, Chinese, multiline and inert shell characters on real Windows");
            lines.clear();String child="$v=[Console]::In.ReadToEnd();$sha=[Security.Cryptography.SHA256]::Create();try{$hash=([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($v)))).Replace('-','').ToLowerInvariant();if($hash -ne '"+hash+"'){throw 'Native stdin mismatch'}}finally{$sha.Dispose()};[Console]::WriteLine('LONG_UTF8_CLI_OK')";
            ssh.runWindowsScript(Agent.windowsInputScript(Agent.powershell(child),text),lines::add,new AtomicBoolean(),45000);
            if(!lines.contains("LONG_UTF8_CLI_OK"))throw new AssertionError("No native stdin confirmation");System.out.println("PASS native child receives exact 50KB UTF-8 stdin");
            boolean failed=false;try{ssh.runWindowsScript("exit 23",line->{},new AtomicBoolean(),30000);}catch(java.io.IOException e){failed=e.getMessage().contains("23");}
            if(!failed)throw new AssertionError("Exit code lost");System.out.println("PASS Windows script exit code retained");
            boolean stopped=false;try{ssh.runWindowsScript("throw 'Must not execute'",line->{},new AtomicBoolean(true),30000);}catch(java.util.concurrent.CancellationException e){stopped=true;}
            if(!stopped)throw new AssertionError("Pre-cancelled script dispatched");System.out.println("PASS cancelled script never dispatched");
            Inventory inventory=new Inventory();ssh.runWindowsScript(new String(Files.readAllBytes(Paths.get("android-app/app/src/main/assets/detect-windows.ps1")),StandardCharsets.UTF_8),inventory::accept,new AtomicBoolean(),45000);
            if(!inventory.complete||!inventory.os.equals("Windows"))throw new AssertionError("Incomplete Windows discovery");System.out.println("PASS actual Windows discovery with "+inventory.agents.size()+" agents");
        }
    }
}
