package dev.orca.pocket;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/** Native Windows sharing locks must not turn a short read into a service crash. */
public final class WindowsTaskStoreAtomicTest {
    private static String quote(Path path){return "'"+path.toString().replace("'","''")+"'";}
    private static final class SharingLock implements AutoCloseable {
        final Path released;
        final Process process;
        SharingLock(Path target)throws Exception {
            Path ready=target.resolveSibling("lock-ready"),release=target.resolveSibling("lock-release");
            Files.deleteIfExists(ready);Files.deleteIfExists(release);released=release;
            String script="$ErrorActionPreference='Stop'; $handle=[IO.File]::Open("+quote(target)+",[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite); try{[IO.File]::WriteAllText("+quote(ready)+",'locked'); $until=[DateTime]::UtcNow.AddSeconds(15); while(!(Test-Path -LiteralPath "+quote(release)+") -and [DateTime]::UtcNow -lt $until){Start-Sleep -Milliseconds 10}}finally{$handle.Dispose()}";
            process=new ProcessBuilder("powershell.exe","-NoLogo","-NoProfile","-NonInteractive","-EncodedCommand",Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE))).start();
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(!Files.exists(ready)&&process.isAlive()&&System.nanoTime()<until)Thread.sleep(10);
            if(!Files.exists(ready)){close();throw new AssertionError("Native sharing lock was not acquired");}
        }
        public void close()throws Exception {
            Files.writeString(released,"release");
            if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();throw new AssertionError("Native fixture did not close its file handle");}
            if(process.exitValue()!=0)throw new AssertionError(new String(process.getErrorStream().readAllBytes(),StandardCharsets.UTF_8));
        }
    }
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    private static void noTemporaryFiles(Path directory)throws IOException{
        try(var files=Files.list(directory)){check(files.noneMatch(p->p.getFileName().toString().startsWith(".writing-")),"Atomic write left temporary files");}
    }
    public static void main(String[] args)throws Exception {
        if(!System.getProperty("os.name").startsWith("Windows"))return;
        Path directory=Files.createTempDirectory("agent-control-atomic-");
        Path target=directory.resolve("heartbeat.json");Files.writeString(target,"old heartbeat");
        try(SharingLock held=new SharingLock(target)){
            Thread release=new Thread(()->{try{Thread.sleep(250);Files.writeString(held.released,"release");}catch(Exception failure){throw new RuntimeException(failure);}},"release-native-lock");
            release.start();
            try{TaskStore.atomic(target,"new heartbeat".getBytes(StandardCharsets.UTF_8));}
            catch(IOException failure){check(Files.readString(target).equals("old heartbeat"),"Failed replacement lost the old heartbeat");throw new AssertionError("Short native sharing lock must be retried instead of killing the service",failure);}
            finally{release.join();}
            check(Files.readString(target).equals("new heartbeat"),"Replacement was not complete");
        }
        noTemporaryFiles(directory);
        System.out.println("PASS native transient sharing lock: atomic replacement succeeds after the reader releases");
        try(SharingLock held=new SharingLock(target)){
            long started=System.nanoTime();IOException failure=null;
            try{TaskStore.atomic(target,"must not replace".getBytes(StandardCharsets.UTF_8));}catch(IOException expected){failure=expected;}
            check(failure!=null,"Permanent sharing lock must report failure");
            long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started);
            check(elapsed>=750&&elapsed<3000,"Sharing lock retry must have a bounded deadline: "+elapsed+" ms");
            check(failure.getMessage().contains("heartbeat.json"),"Failure must identify the target");
            check(Files.readString(target).equals("new heartbeat"),"Permanent failure lost old contents");
        }
        noTemporaryFiles(directory);
        System.out.println("PASS native persistent sharing lock: bounded failure preserves the old file and cleans temporary bytes");
        Files.setAttribute(target,"dos:readonly",true);
        try{
            IOException failure=null;try{TaskStore.atomic(target,"must not replace".getBytes(StandardCharsets.UTF_8));}catch(IOException expected){failure=expected;}
            check(failure!=null,"Read-only target must report access denial");
            check(failure.getCause() instanceof AccessDeniedException,"Permanent permission failure must preserve the native access-denied cause");
            check(Files.readString(target).equals("new heartbeat"),"Permission failure lost old contents");
        }finally{Files.setAttribute(target,"dos:readonly",false);}
        noTemporaryFiles(directory);
        System.out.println("PASS native read-only target: permission failure is explicit and preserves the old file");
    }
}
