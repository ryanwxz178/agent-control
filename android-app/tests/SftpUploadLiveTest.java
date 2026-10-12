package dev.orca.pocket;

import java.io.IOException;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Optional loopback SSH/SFTP acceptance; uses the actual JSch-backed upload implementation. */
public final class SftpUploadLiveTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static String digest(byte[] value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));}
    private static byte[] random(int count){byte[] value=new byte[count];new SecureRandom().nextBytes(value);return value;}
    private static void connect(SshTransport ssh,int port,String fingerprint)throws Exception{
        // These credentials identify only the isolated fixture and never a real user account.
        ssh.connect("127.0.0.1",port,"fixture","fixture-password",null,(host,key)->key.equals(fingerprint));
    }
    private static long upload(SshTransport ssh,Path path,byte[] bytes)throws Exception{
        long started=System.nanoTime();ssh.upload(path.toString(),bytes);
        check(Files.size(path)==bytes.length,"Published byte count mismatch");
        String expected=digest(bytes);check(digest(Files.readAllBytes(path)).equals(expected),"Published SHA-256 mismatch");
        long elapsed=(System.nanoTime()-started)/1_000_000;
        System.out.println("PASS "+path.getFileName()+" bytes="+bytes.length+" durationMs="+elapsed+" sha256="+expected);
        return elapsed;
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=3)throw new IllegalArgumentException("fixture port, pinned fingerprint and owned Windows directory required");
        Path directory=Path.of(args[2]).toAbsolutePath().normalize();
        check(System.getProperty("os.name").startsWith("Windows"),"Acceptance requires the actual Windows filesystem");
        check(directory.getFileName().toString().startsWith("sftp-live-"),"Only an owned fixture directory is permitted");
        int port=Integer.parseInt(args[0]);Path unicode=directory.resolve("附件 测试🐳");Files.createDirectories(unicode);
        try(SshTransport ssh=new SshTransport()){
            connect(ssh,port,args[1]);
            upload(ssh,unicode.resolve("随机 2MB-中文.bin"),random(2*1024*1024));
            upload(ssh,unicode.resolve("随机 20MB-中文.bin"),random(20*1024*1024));
            long elapsed=upload(ssh,unicode.resolve("slow-2mb-持续进展.bin"),random(2*1024*1024));
            check(elapsed>60_000&&elapsed<180_000,"Healthy slow upload must exceed one minute and remain bounded");
            Path interrupted=unicode.resolve("interrupted-2mb.bin");byte[] bytes=random(2*1024*1024);boolean failed=false;
            try{ssh.upload(interrupted.toString(),bytes);}
            catch(IOException expected){failed=true;check(expected.getMessage().contains("本条消息尚未发送"),"Interrupted upload must report that the message was not sent");}
            check(failed,"Connection interruption was incorrectly reported as success");
            Thread.sleep(1500);check(!Files.exists(interrupted),"Interrupted file was published or automatically retried");
            check(!ssh.connected(),"Broken session must not remain connected");
            System.out.println("PASS interrupted transfer reports failure and no final file appears before manual reconnect");
            connect(ssh,port,args[1]);upload(ssh,interrupted,bytes);
            System.out.println("PASS manual new SSH connection uploads the original bytes again successfully");
        }
    }
}
