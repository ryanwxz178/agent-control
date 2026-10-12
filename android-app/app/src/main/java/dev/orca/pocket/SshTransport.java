package dev.orca.pocket;

import com.jcraft.jsch.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

public class SshTransport implements AutoCloseable {
    public interface Trust { boolean verify(String host, String fingerprint) throws Exception; }
    public interface Line { void accept(String line) throws Exception; }
    private volatile Session session;
    private final Set<ChannelExec> active=ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();
    public void connect(String host,int port,String user,String password,byte[] key,Trust trust) throws Exception {
        disconnect();
        JSch jsch=new JSch();
        jsch.setHostKeyRepository(new HostKeyRepository() {
            public int check(String h,byte[] k) {
                try {
                    String fp="SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(k));
                    return trust.verify(h,fp)?OK:CHANGED;
                } catch(Exception e) { return CHANGED; }
            }
            public void add(HostKey k,UserInfo u){}
            public void remove(String h,String t){}
            public void remove(String h,String t,byte[] k){}
            public String getKnownHostsRepositoryID(){return "Agent Control pinned host keys";}
            public HostKey[] getHostKey(){return new HostKey[0];}
            public HostKey[] getHostKey(String h,String t){return new HostKey[0];}
        });
        if(key!=null && key.length>0)jsch.addIdentity("phone-key",key,null,password.getBytes(StandardCharsets.UTF_8));
        Session s=jsch.getSession(user,host,port);
        s.setConfig("StrictHostKeyChecking","yes");
        s.setConfig("PreferredAuthentications",key!=null?"publickey":"password,keyboard-interactive");
        s.setPassword(password);
        s.setServerAliveInterval(15000); s.setServerAliveCountMax(3);
        session=s;
        try{s.connect(20000);}catch(Exception e){s.disconnect(); session=null; throw e;}
    }
    public boolean connected(){Session s=session; return s!=null && s.isConnected();}
    public int run(String command,String stdin,Line line,AtomicBoolean cancelled,long timeoutMs) throws Exception {
        Session s=session;
        if(s==null || !s.isConnected())throw new IOException("SSH 已断开，请重新连接电脑");
        if(cancelled.get())throw new CancellationException();
        ChannelExec c=(ChannelExec)s.openChannel("exec");
        active.add(c);
        AtomicBoolean timedOut=new AtomicBoolean();
        ByteArrayOutputStream stderr=new ByteArrayOutputStream(){
            @Override public synchronized void write(byte[] b,int off,int len){super.write(b,off,Math.min(len,Math.max(0,16384-size())));}
        };
        ScheduledFuture<?> timeout=timer.schedule(()->{timedOut.set(true); c.disconnect();},timeoutMs,TimeUnit.MILLISECONDS);
        ScheduledFuture<?> stop=timer.scheduleWithFixedDelay(()->{
            if(cancelled.get()){try{c.sendSignal("INT");}catch(Exception ignored){} c.disconnect();}
        },100,100,TimeUnit.MILLISECONDS);
        try {
            c.setCommand(command); c.setInputStream(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
            c.setErrStream(stderr);
            InputStream in=c.getInputStream();
            c.connect(15000);
            InputStreamReader reader=new InputStreamReader(in,StandardCharsets.UTF_8);
            StringBuilder current=new StringBuilder(); int ch, total=0;
            while((ch=reader.read())!=-1){
                if(cancelled.get())throw new CancellationException();
                if(++total>16_000_000)throw new IOException("输出超过 16MB，本次连接已中止");
                if(ch=='\n'){line.accept(protocolLine(current)); current.setLength(0);}
                else if(current.length()<8_000_000)current.append((char)ch);
                else throw new IOException("Agent 单条事件超过大小限制");
            }
            if(current.length()>0)line.accept(protocolLine(current));
            while(!c.isClosed() && !cancelled.get() && !timedOut.get())Thread.sleep(50);
            if(cancelled.get())throw new CancellationException();
            if(timedOut.get())throw new IOException("等待超时；远端执行状态未知，请在电脑上确认");
            int exit=c.getExitStatus();
            if(exit<0)throw new IOException("SSH 通道中断，远端执行状态未知；恢复连接后不会自动重发任务");
            if(exit!=0)throw new IOException("远端退出码 "+exit+"："+stderr.toString("UTF-8"));
            return exit;
        } finally {timeout.cancel(false);stop.cancel(false);active.remove(c);c.disconnect();}
    }
    public interface Duplex {
        void start(OutputStream input)throws Exception;
        boolean accept(String line)throws Exception;
    }
    /**
     * Protocol callbacks may enqueue the next request while the agent is still writing
     * notifications. Keep that stdin write off the stdout reader to avoid pipe deadlock.
     */
    static final class DuplexInput extends OutputStream {
        static final int MAX_PENDING_BYTES=64*1024*1024,MAX_PENDING_WRITES=128;
        private final Object lock=new Object();
        private final ArrayDeque<byte[]> pending=new ArrayDeque<>();
        private final OutputStream output;
        private final Runnable abort;
        private final Thread writer;
        private int pendingBytes;
        private boolean closed;
        private IOException failure;
        // A zero-length chunk is an ordered flush; JSON writes themselves are nonempty.
        DuplexInput(OutputStream output,Runnable abort){
            this.output=output;this.abort=abort;
            writer=new Thread(this::writePending,"agent-duplex-input");writer.setDaemon(true);writer.start();
        }
        private void checkLocked()throws IOException{
            if(failure!=null)throw failure;
            if(closed)throw new IOException("交互输入通道已结束，执行状态未知；不会自动重发任务");
        }
        void checkError()throws IOException{synchronized(lock){if(failure!=null)throw failure;}}
        @Override public void write(int value)throws IOException{write(new byte[]{(byte)value},0,1);}
        @Override public void write(byte[] bytes,int offset,int length)throws IOException{
            if(bytes==null)throw new NullPointerException();
            if(offset<0||length<0||offset>bytes.length-length)throw new IndexOutOfBoundsException();
            if(length==0)return;
            synchronized(lock){
                checkLocked();
                if(length>MAX_PENDING_BYTES-pendingBytes||pending.size()>=MAX_PENDING_WRITES)throw new IOException("交互输入超过缓冲限制，执行状态未知；不会自动重发任务");
                pending.addLast(Arrays.copyOfRange(bytes,offset,offset+length));pendingBytes+=length;lock.notifyAll();
            }
        }
        @Override public void flush()throws IOException{
            synchronized(lock){checkLocked();if(pending.size()>=MAX_PENDING_WRITES)throw new IOException("交互输入超过缓冲限制，执行状态未知；不会自动重发任务");pending.addLast(new byte[0]);lock.notifyAll();}
        }
        private void writePending(){
            try{
                while(true){
                    byte[] bytes;
                    synchronized(lock){while(pending.isEmpty()&&!closed)lock.wait();if(closed)return;bytes=pending.removeFirst();}
                    try{if(bytes.length==0)output.flush();else output.write(bytes);}
                    finally{synchronized(lock){pendingBytes=Math.max(0,pendingBytes-bytes.length);}}
                }
            }catch(Exception error){
                boolean stop;
                synchronized(lock){stop=!closed;if(stop)failure=new IOException("交互输入通道中断，执行状态未知；不会自动重发任务",error);closed=true;pending.clear();pendingBytes=0;lock.notifyAll();}
                if(stop)try{abort.run();}catch(Exception ignored){}
            }
        }
        @Override public void close(){synchronized(lock){closed=true;pending.clear();pendingBytes=0;lock.notifyAll();}writer.interrupt();}
        void awaitStopped()throws InterruptedException{writer.join(1000);}
    }
    /** Windows OpenSSH can truncate exec stdin at 16KB; transfer scripts via SFTP. */
    public int runWindowsScript(String script,Line line,AtomicBoolean cancelled,long timeoutMs)throws Exception {
        return windowsScript(script,line,null,cancelled,timeoutMs);
    }
    public void interactiveWindowsScript(String script,Duplex protocol,AtomicBoolean cancelled,long timeoutMs)throws Exception {
        windowsScript(script,null,protocol,cancelled,timeoutMs);
    }
    private int windowsScript(String script,Line line,Duplex protocol,AtomicBoolean cancelled,long timeoutMs)throws Exception {
        Session s=session;
        if(s==null||!s.isConnected())throw new IOException("SSH 已断开，请重新连接电脑");
        if(cancelled.get())throw new CancellationException();
        byte[] body=(script.startsWith("\uFEFF")?script:"\uFEFF"+script).getBytes(StandardCharsets.UTF_8);
        if(body.length>2_000_000)throw new IOException("Windows 脚本超过大小限制");
        ChannelSftp file=(ChannelSftp)s.openChannel("sftp");
        ScheduledFuture<?> transferTimeout=timer.schedule(file::disconnect,45000,TimeUnit.MILLISECONDS);
        String path=null;
        try {
            file.connect(15000);
            path=file.pwd()+"/.orca-pocket-"+UUID.randomUUID().toString()+".ps1";
            file.put(new ByteArrayInputStream(body),path,new SftpProgressMonitor(){
                public void init(int op,String src,String dst,long max){}
                public boolean count(long bytes){return !cancelled.get();}
                public void end(){}
            });
            if(cancelled.get())throw new CancellationException();
            transferTimeout.cancel(false);
            String nativePath=path.matches("^/[A-Za-z]:/.*")?path.substring(1):path;
            String command=Agent.powershell("& "+Agent.psQuote(nativePath)+"; if($null -ne $LASTEXITCODE){exit $LASTEXITCODE}");
            if(protocol!=null){interactive(command,protocol,cancelled,timeoutMs);return 0;}
            return run(command,"",line,cancelled,timeoutMs);
        } catch(SftpException|JSchException e) {
            throw new IOException("Windows SFTP 传输失败，请检查 OpenSSH 的 sftp 子系统配置："+e.getMessage(),e);
        } finally {
            transferTimeout.cancel(false);
            // Never repeat a command after uncertain execution. Cleanup is best effort only.
            ScheduledFuture<?> cleanupTimeout=timer.schedule(file::disconnect,5000,TimeUnit.MILLISECONDS);
            try{if(path!=null&&file.isConnected())try{file.rm(path);}catch(Exception ignored){}}
            finally{cleanupTimeout.cancel(false);file.disconnect();}
        }
    }
    public void interactive(String command,Duplex protocol,AtomicBoolean cancelled,long timeoutMs)throws Exception{
        Session s=session;if(s==null||!s.isConnected())throw new IOException("SSH 已断开");
        ChannelExec c=(ChannelExec)s.openChannel("exec");active.add(c);
        ByteArrayOutputStream errors=new ByteArrayOutputStream(){
            @Override public synchronized void write(byte[] b,int off,int len){super.write(b,off,Math.min(len,Math.max(0,16384-size())));}
        };
        ScheduledFuture<?> timeout=timer.schedule(c::disconnect,timeoutMs,TimeUnit.MILLISECONDS);
        DuplexInput queued=null;
        try{
            c.setCommand(command);c.setErrStream(errors);InputStream in=c.getInputStream();OutputStream out=c.getOutputStream();c.connect(15000);
            queued=new DuplexInput(out,c::disconnect);protocol.start(queued);InputStreamReader reader=new InputStreamReader(in,StandardCharsets.UTF_8);
            StringBuilder line=new StringBuilder();int ch,total=0;
            while((ch=reader.read())!=-1){
                queued.checkError();
                if(cancelled.get())throw new CancellationException();
                if(++total>16_000_000||line.length()>8_000_000)throw new IOException("交互事件超过大小限制");
                if(ch=='\n'){if(protocol.accept(protocolLine(line)))return;line.setLength(0);}else line.append((char)ch);
            }
            queued.checkError();
            if(cancelled.get())throw new CancellationException();
            throw new IOException("交互通道结束，执行状态未知；不会重发任务。"+errors.toString("UTF-8"));
        }finally{timeout.cancel(false);if(queued!=null)queued.close();active.remove(c);c.disconnect();if(queued!=null)queued.awaitStopped();}
    }
    public org.json.JSONArray listDownloads(String path)throws Exception{
        path=sftpPath(path);
        Session s=session;if(s==null||!s.isConnected())throw new IOException("请先连接电脑");ChannelSftp c=(ChannelSftp)s.openChannel("sftp");ScheduledFuture<?> timeout=timer.schedule(c::disconnect,20000,TimeUnit.MILLISECONDS);
        try{c.connect(10000);java.util.List<ChannelSftp.LsEntry> files=new ArrayList<>();
            for(Object item:c.ls(path)){ChannelSftp.LsEntry entry=(ChannelSftp.LsEntry)item;if(entry.getAttrs().isReg()&&!entry.getFilename().startsWith("."))files.add(entry);}
            files.sort((a,b)->Integer.compare(b.getAttrs().getMTime(),a.getAttrs().getMTime()));org.json.JSONArray result=new org.json.JSONArray();
            for(ChannelSftp.LsEntry entry:files.subList(0,Math.min(files.size(),30)))result.put(new org.json.JSONObject().put("path",path+"/"+entry.getFilename()).put("name",entry.getFilename()).put("size",entry.getAttrs().getSize()));return result;
        }finally{timeout.cancel(false);c.disconnect();}
    }
    public byte[] download(String path,int limit)throws Exception{
        path=sftpPath(path);
        Session s=session;if(s==null||!s.isConnected())throw new IOException("请先连接原电脑");
        ChannelSftp c=(ChannelSftp)s.openChannel("sftp");
        ScheduledFuture<?> timeout=timer.schedule(c::disconnect,45000,TimeUnit.MILLISECONDS);
        try{
            c.connect(15000);SftpATTRS attrs=c.stat(path);
            if(!attrs.isReg()||attrs.getSize()>limit)throw new IOException("仅支持下载 20MB 以内的普通文件");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=c.get(path)){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){if(bytes.size()+n>limit)throw new IOException("文件超过大小限制");bytes.write(buffer,0,n);}}
            return bytes.toByteArray();
        }finally{timeout.cancel(false);c.disconnect();}
    }
    /** Publish versioned runtime files without exposing an SSH command containing their bytes. */
    public void upload(String path,byte[] bytes)throws Exception{
        path=sftpPath(path);
        Session s=session;if(s==null||!s.isConnected())throw new IOException("请先连接电脑");
        ChannelSftp c=(ChannelSftp)s.openChannel("sftp");
        TransferDeadline deadline=new TransferDeadline(System::nanoTime);
        ScheduledFuture<?> timeout=timer.scheduleWithFixedDelay(()->{if(deadline.expired())c.disconnect();},1000,1000,TimeUnit.MILLISECONDS);
        String temporary=path+"."+UUID.randomUUID()+".tmp";
        try{
            c.connect(15000);
            publishUpload(new UploadTarget(){
                public void put(String destination,byte[] data,SftpProgressMonitor progress)throws SftpException{c.put(new ByteArrayInputStream(data),destination,progress);}
                public void chmod(String destination)throws SftpException{c.chmod(0600,destination);}
                public long size(String destination)throws SftpException{return c.stat(destination).getSize();}
                public void rename(String source,String destination)throws SftpException{c.rename(source,destination);}
                public boolean connected(){return c.isConnected()&&s.isConnected();}
            },path,temporary,bytes,deadline);
        }catch(SftpException|JSchException|IOException e){
            if(deadline.expired())throw new IOException(deadline.message(),e);
            if(!s.isConnected()||!c.isConnected()||connectionFailure(e))throw new IOException("文件上传时连接中断，原文件仍在手机；请重新连接后重新选择文件。本条消息尚未发送。",e);
            if(e instanceof IOException)throw e;
            throw new IOException("无法上传电脑文件："+e.getMessage(),e);
        }finally{
            timeout.cancel(false);
            // Cleanup must not keep an already failed upload pending indefinitely.
            ScheduledFuture<?> cleanupTimeout;
            try{cleanupTimeout=timer.schedule(c::disconnect,5000,TimeUnit.MILLISECONDS);}
            catch(RejectedExecutionException closing){c.disconnect();cleanupTimeout=null;}
            try{if(c.isConnected())try{c.rm(temporary);}catch(Exception ignored){}}
            finally{if(cleanupTimeout!=null)cleanupTimeout.cancel(false);c.disconnect();}
        }
    }
    interface UploadTarget {
        void put(String destination,byte[] data,SftpProgressMonitor progress)throws SftpException;
        void chmod(String destination)throws SftpException;
        long size(String destination)throws SftpException;
        void rename(String source,String destination)throws SftpException;
        boolean connected();
    }
    /** A healthy slow transfer may exceed one minute; only stalled or unbounded transfers expire. */
    static final class TransferDeadline {
        static final long IDLE_NANOS=TimeUnit.SECONDS.toNanos(60),TOTAL_NANOS=TimeUnit.MINUTES.toNanos(5);
        private final LongSupplier clock;
        private final long start;
        private final AtomicLong lastProgress;
        private final AtomicReference<String> reason=new AtomicReference<>();
        TransferDeadline(LongSupplier clock){this.clock=clock;start=clock.getAsLong();lastProgress=new AtomicLong(start);}
        void progress(long bytes){if(bytes>0&&!expired())lastProgress.set(clock.getAsLong());}
        boolean expired(){
            long now=clock.getAsLong();
            if(now-start>=TOTAL_NANOS)reason.compareAndSet(null,"上传超过 5 分钟");
            else if(now-lastProgress.get()>=IDLE_NANOS)reason.compareAndSet(null,"上传连续 60 秒没有进展");
            return reason.get()!=null;
        }
        String message(){return "文件"+(reason.get()==null?"上传超时":reason.get())+"，请检查网络后重新选择文件。本条消息尚未发送。";}
        void check()throws IOException{if(expired())throw new IOException(message());}
    }
    static void publishUpload(UploadTarget target,String path,String temporary,byte[] bytes,TransferDeadline deadline)throws IOException,SftpException{
        checkUpload(target,deadline);
        target.put(temporary,bytes,new SftpProgressMonitor(){
            public void init(int op,String src,String dst,long max){}
            public boolean count(long count){deadline.progress(count);return !deadline.expired()&&target.connected();}
            public void end(){}
        });
        checkUpload(target,deadline);
        if(target.size(temporary)!=bytes.length)throw new IOException("上传文件不完整，请重新选择文件。本条消息尚未发送。");
        checkUpload(target,deadline);
        target.chmod(temporary);
        checkUpload(target,deadline);
        target.rename(temporary,path);
        checkUpload(target,deadline);
    }
    private static void checkUpload(UploadTarget target,TransferDeadline deadline)throws IOException{
        deadline.check();
        if(!target.connected())throw new IOException("文件上传时连接中断，原文件仍在手机；请重新连接后重新选择文件。本条消息尚未发送。");
    }
    private static boolean connectionFailure(Throwable error){
        for(Throwable value=error;value!=null;value=value.getCause())if(value instanceof java.net.SocketException||value instanceof EOFException)return true;
        return false;
    }
    /** JSch resolves paths without a leading slash against the remote home directory. */
    static String sftpPath(String path){
        if(path.matches("^[A-Za-z]:[\\\\/].*"))return "/"+path.replace('\\','/');
        return path;
    }
    public void cancel(){for(ChannelExec c:active){try{c.sendSignal("INT");}catch(Exception ignored){} c.disconnect();}}
    private static String protocolLine(StringBuilder value){
        int length=value.length();
        return length>0&&value.charAt(length-1)=='\r'?value.substring(0,length-1):value.toString();
    }
    public void disconnect(){cancel(); Session s=session; session=null; if(s!=null)s.disconnect();}
    @Override public void close(){disconnect();timer.shutdownNow();}
}
