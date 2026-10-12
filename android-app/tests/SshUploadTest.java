package dev.orca.pocket;

import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Offline transfer failures with the same publication path and deadline used by real SFTP. */
public final class SshUploadTest {
    private static int checks;
    private static final String FINAL="/D:/项目/.agent-control-input/id-图.jpg",TEMP=FINAL+".private.tmp";
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static final class Target implements SshTransport.UploadTarget {
        final AtomicLong clock=new AtomicLong();
        final List<String> operations=new ArrayList<>();
        long chunkSeconds,reportedSize=-1,sizeDelay,chmodDelay,renameDelay;
        int chunks=1;
        boolean connected=true,zeroProgress,chmodFailure,renameFailure,disconnectOnPut,disconnectOnRename;
        byte[] temporary,published;
        public boolean connected(){return connected;}
        public void put(String path,byte[] bytes,SftpProgressMonitor monitor)throws SftpException{
            operations.add("put:"+path);monitor.init(SftpProgressMonitor.PUT,"phone",path,bytes.length);
            for(int i=0;i<chunks;i++){
                clock.addAndGet(TimeUnit.SECONDS.toNanos(chunkSeconds));
                if(!monitor.count(zeroProgress?0:Math.max(1,bytes.length/chunks)))throw new SftpException(4,"transfer stopped");
            }
            temporary=bytes.clone();monitor.end();if(disconnectOnPut)connected=false;
        }
        public long size(String path){operations.add("stat:"+path);clock.addAndGet(TimeUnit.SECONDS.toNanos(sizeDelay));return reportedSize<0?temporary.length:reportedSize;}
        public void chmod(String path)throws SftpException{operations.add("chmod:"+path);clock.addAndGet(TimeUnit.SECONDS.toNanos(chmodDelay));if(chmodFailure)throw new SftpException(3,"permission denied");}
        public void rename(String source,String destination)throws SftpException{
            operations.add("rename:"+destination);clock.addAndGet(TimeUnit.SECONDS.toNanos(renameDelay));if(renameFailure)throw new SftpException(4,"rename rejected");
            published=temporary;temporary=null;if(disconnectOnRename)connected=false;
        }
        SshTransport.TransferDeadline deadline(){return new SshTransport.TransferDeadline(clock::get);}
    }
    private interface Throwing {void run()throws Exception;}
    private static Exception rejects(Throwing action)throws Exception{
        try{action.run();throw new AssertionError("upload unexpectedly reported success");}catch(IOException|SftpException expected){return expected;}
    }
    public static void main(String[] args)throws Exception{
        byte[] bytes=new byte[2*1024*1024];new Random(7).nextBytes(bytes);
        Target slow=new Target();slow.chunks=6;slow.chunkSeconds=30;
        SshTransport.publishUpload(slow,FINAL,TEMP,bytes,slow.deadline());
        check(Arrays.equals(slow.published,bytes),"a progressing 180-second upload preserves all bytes");
        check(slow.operations.equals(Arrays.asList("put:"+TEMP,"stat:"+TEMP,"chmod:"+TEMP,"rename:"+FINAL)),"only validated private temporary bytes are published; 0600 remains required");

        Target stalled=new Target();stalled.chunkSeconds=60;SshTransport.TransferDeadline stallDeadline=stalled.deadline();
        rejects(()->SshTransport.publishUpload(stalled,FINAL,TEMP,bytes,stallDeadline));
        check(stallDeadline.expired()&&stalled.published==null&&stalled.operations.size()==1,"60 seconds without progress never publishes a partial file");
        check(stallDeadline.message().contains("60 秒")&&stallDeadline.message().contains("尚未发送"),"idle timeout explains recovery without implying a model message was sent");

        Target endless=new Target();endless.chunks=10;endless.chunkSeconds=30;SshTransport.TransferDeadline totalDeadline=endless.deadline();
        rejects(()->SshTransport.publishUpload(endless,FINAL,TEMP,bytes,totalDeadline));
        check(totalDeadline.expired()&&totalDeadline.message().contains("5 分钟")&&endless.published==null,"absolute five-minute limit applies even while bytes keep moving");

        Target emptyProgress=new Target();emptyProgress.chunks=6;emptyProgress.chunkSeconds=10;emptyProgress.zeroProgress=true;
        rejects(()->SshTransport.publishUpload(emptyProgress,FINAL,TEMP,bytes,emptyProgress.deadline()));
        check(emptyProgress.published==null,"zero-byte progress callbacks do not keep a stalled upload alive");

        Target shortFile=new Target();shortFile.reportedSize=bytes.length-1;
        Exception incomplete=rejects(()->SshTransport.publishUpload(shortFile,FINAL,TEMP,bytes,shortFile.deadline()));
        check(shortFile.published==null&&shortFile.operations.size()==2&&incomplete.getMessage().contains("不完整"),"server truncation is detected before chmod or commit");
        Target longFile=new Target();longFile.reportedSize=bytes.length+1;
        rejects(()->SshTransport.publishUpload(longFile,FINAL,TEMP,bytes,longFile.deadline()));
        check(longFile.published==null,"a larger-than-expected temporary file is also rejected");

        Target privateFailure=new Target();privateFailure.chmodFailure=true;
        rejects(()->SshTransport.publishUpload(privateFailure,FINAL,TEMP,bytes,privateFailure.deadline()));
        check(privateFailure.published==null&&privateFailure.operations.size()==3,"permission hardening failure cannot report upload success");

        Target commitFailure=new Target();commitFailure.renameFailure=true;
        rejects(()->SshTransport.publishUpload(commitFailure,FINAL,TEMP,bytes,commitFailure.deadline()));
        check(commitFailure.published==null&&commitFailure.operations.size()==4,"a rejected rename leaves the destination unpublished");

        Target lostBeforeCommit=new Target();lostBeforeCommit.disconnectOnPut=true;
        Exception disconnected=rejects(()->SshTransport.publishUpload(lostBeforeCommit,FINAL,TEMP,bytes,lostBeforeCommit.deadline()));
        check(lostBeforeCommit.published==null&&lostBeforeCommit.operations.size()==1&&disconnected.getMessage().contains("重新连接"),"session loss never commits or returns a usable file capability");

        Target lostCommitReply=new Target();lostCommitReply.disconnectOnRename=true;
        rejects(()->SshTransport.publishUpload(lostCommitReply,FINAL,TEMP,bytes,lostCommitReply.deadline()));
        check(lostCommitReply.published!=null&&lostCommitReply.operations.size()==4,"a lost commit reply remains uncertain and is never retried or reported successful");

        Target slowStat=new Target();slowStat.sizeDelay=60;
        rejects(()->SshTransport.publishUpload(slowStat,FINAL,TEMP,bytes,slowStat.deadline()));
        check(slowStat.published==null&&slowStat.operations.size()==2,"expiry during stat stops before permission changes or commit");
        Target slowChmod=new Target();slowChmod.chmodDelay=60;
        rejects(()->SshTransport.publishUpload(slowChmod,FINAL,TEMP,bytes,slowChmod.deadline()));
        check(slowChmod.published==null&&slowChmod.operations.size()==3,"expiry during chmod stops before commit");
        Target slowRename=new Target();slowRename.renameDelay=60;
        Exception lateCommit=rejects(()->SshTransport.publishUpload(slowRename,FINAL,TEMP,bytes,slowRename.deadline()));
        check(slowRename.published!=null&&lateCommit.getMessage().contains("60 秒"),"expiry while a rename reply is delayed cannot be returned as upload success");

        AtomicLong now=new AtomicLong();SshTransport.TransferDeadline expired=new SshTransport.TransferDeadline(now::get);
        now.set(TimeUnit.SECONDS.toNanos(60));check(expired.expired(),"watchdog notices the exact idle boundary");
        expired.progress(500);check(expired.expired(),"late progress cannot revive an expired transfer");
        System.out.println("PASS "+checks+" SFTP upload checks: slow progress, idle/absolute limits, complete publication, private permissions and connection loss.");
    }
}
