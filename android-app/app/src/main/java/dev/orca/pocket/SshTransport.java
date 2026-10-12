package dev.orca.pocket;

import com.jcraft.jsch.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

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
        try{
            c.setCommand(command);c.setErrStream(errors);InputStream in=c.getInputStream();OutputStream out=c.getOutputStream();c.connect(15000);
            protocol.start(out);InputStreamReader reader=new InputStreamReader(in,StandardCharsets.UTF_8);
            StringBuilder line=new StringBuilder();int ch,total=0;
            while((ch=reader.read())!=-1){
                if(cancelled.get())throw new CancellationException();
                if(++total>16_000_000||line.length()>8_000_000)throw new IOException("交互事件超过大小限制");
                if(ch=='\n'){if(protocol.accept(protocolLine(line)))return;line.setLength(0);}else line.append((char)ch);
            }
            if(cancelled.get())throw new CancellationException();
            throw new IOException("交互通道结束，执行状态未知；不会重发任务。"+errors.toString("UTF-8"));
        }finally{timeout.cancel(false);active.remove(c);c.disconnect();}
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
        ScheduledFuture<?> timeout=timer.schedule(c::disconnect,60000,TimeUnit.MILLISECONDS);
        String temporary=path+"."+UUID.randomUUID()+".tmp";
        try{c.connect(15000);c.put(new ByteArrayInputStream(bytes),temporary);c.chmod(0600,temporary);c.rename(temporary,path);}
        catch(SftpException e){throw new IOException("无法上传电脑文件 "+path+"："+e.getMessage(),e);}
        finally{try{if(c.isConnected())c.rm(temporary);}catch(Exception ignored){}timeout.cancel(false);c.disconnect();}
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
