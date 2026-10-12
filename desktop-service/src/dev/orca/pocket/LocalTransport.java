package dev.orca.pocket;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Execution belongs to the computer. Disconnecting a phone never closes this transport. */
final class LocalTransport extends SshTransport {
    private final Set<Process> children=ConcurrentHashMap.newKeySet();
    private final Map<Process,List<ProcessHandle>> terminating= new ConcurrentHashMap<>();
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor();
    private volatile boolean open=true;
    @Override public boolean connected(){return open;}
    private Process launch(String command)throws IOException{
        ProcessBuilder builder=System.getProperty("os.name").startsWith("Windows")
            ?new ProcessBuilder("cmd.exe","/d","/s","/c",command)
            :new ProcessBuilder("/bin/sh","-c",command);
        Process child=builder.start();children.add(child);return child;
    }
    private void terminate(Process child){
        if(!child.isAlive()||terminating.containsKey(child))return;
        List<ProcessHandle> descendants=child.descendants().toList();terminating.put(child,descendants);
        // Record the outcome as unverifiable elsewhere: signalling cannot prove every descendant exited.
        descendants.forEach(p->{try{p.destroy();}catch(Exception ignored){}});child.destroy();
        if(!deadlines.isShutdown())deadlines.schedule(()->{descendants.forEach(p->{try{if(p.isAlive())p.destroyForcibly();}catch(Exception ignored){}});child.destroyForcibly();},2,TimeUnit.SECONDS);
    }
    private void execute(String command,String stdin,Line callback,Duplex duplex,AtomicBoolean cancelled,long timeout)throws Exception{
        if(!open||cancelled.get())throw new CancellationException();
        Process child=launch(command);AtomicBoolean expired=new AtomicBoolean();
        ByteArrayOutputStream errors=new ByteArrayOutputStream();
        Thread drain=new Thread(()->{try(InputStream in=child.getErrorStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){synchronized(errors){if(errors.size()<16384)errors.write(b,0,Math.min(n,16384-errors.size()));}}}catch(IOException ignored){}},"agent-stderr");drain.setDaemon(true);drain.start();
        ScheduledFuture<?> deadline=deadlines.schedule(()->{expired.set(true);terminate(child);},timeout,TimeUnit.MILLISECONDS);
        ScheduledFuture<?> stop=deadlines.scheduleWithFixedDelay(()->{if(cancelled.get())terminate(child);},100,100,TimeUnit.MILLISECONDS);
        try{
            if(duplex!=null)duplex.start(child.getOutputStream());
            else{child.getOutputStream().write(stdin.getBytes(StandardCharsets.UTF_8));child.getOutputStream().close();}
            try(Reader in=new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8)){
                StringBuilder line=new StringBuilder();int ch,total=0;
                while((ch=in.read())!=-1){
                    if(cancelled.get())throw new CancellationException();
                    if(++total>16000000||line.length()>8000000)throw new IOException("Agent 输出超过大小限制");
                    if(ch=='\n'){
                        String value=line.toString().replaceFirst("\\r$","");line.setLength(0);
                        if(duplex!=null){if(duplex.accept(value))return;}else callback.accept(value);
                    }else line.append((char)ch);
                }
                if(line.length()>0){if(duplex!=null){if(duplex.accept(line.toString()))return;}else callback.accept(line.toString());}
            }
            if(expired.get())throw new IOException("等待超时，执行状态未知，请在电脑确认");
            if(cancelled.get())throw new CancellationException();
            int exit=child.waitFor();drain.join(1000);
            if(exit!=0)throw new IOException("远端退出码 "+exit+"："+errors.toString("UTF-8"));
            if(duplex!=null)throw new IOException("交互通道结束，执行状态未知；不会重发任务");
        }finally{
            deadline.cancel(false);stop.cancel(false);terminate(child);
            List<ProcessHandle> descendants=terminating.getOrDefault(child,List.of());long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while((child.isAlive()||descendants.stream().anyMatch(ProcessHandle::isAlive))&&System.nanoTime()<until)Thread.sleep(50);
            if(child.isAlive()||descendants.stream().anyMatch(ProcessHandle::isAlive))throw new IOException("电脑任务进程尚未退出，不能确认会话已结束");
            children.remove(child);terminating.remove(child);
        }
    }
    @Override public int run(String command,String stdin,Line line,AtomicBoolean cancelled,long timeout)throws Exception{execute(command,stdin,line,null,cancelled,timeout);return 0;}
    @Override public void interactive(String command,Duplex protocol,AtomicBoolean cancelled,long timeout)throws Exception{execute(command,"",null,protocol,cancelled,timeout);}
    @Override public int runWindowsScript(String script,Line line,AtomicBoolean cancelled,long timeout)throws Exception{
        return windowsScript(script,line,null,cancelled,timeout);
    }
    @Override public void interactiveWindowsScript(String script,Duplex protocol,AtomicBoolean cancelled,long timeout)throws Exception{
        windowsScript(script,null,protocol,cancelled,timeout);
    }
    private int windowsScript(String script,Line line,Duplex protocol,AtomicBoolean cancelled,long timeout)throws Exception{
        Path file=Files.createTempFile("agent-control-",".ps1");
        try{Files.write(file,(script.startsWith("\uFEFF")?script:"\uFEFF"+script).getBytes(StandardCharsets.UTF_8));String command=Agent.powershell("& "+Agent.psQuote(file.toString())+"; if($null -ne $LASTEXITCODE){exit $LASTEXITCODE}");if(protocol!=null){interactive(command,protocol,cancelled,timeout);return 0;}return run(command,"",line,cancelled,timeout);}
        finally{Files.deleteIfExists(file);}
    }
    @Override public byte[] download(String file,int limit)throws Exception{
        Path path=Paths.get(file);if(!Files.isRegularFile(path)||Files.size(path)>limit)throw new IOException("仅支持 20MB 以内的普通文件");
        try(InputStream in=Files.newInputStream(path)){byte[] result=in.readNBytes(limit+1);if(result.length>limit)throw new IOException("文件超过大小限制");return result;}
    }
    @Override public JSONArray listDownloads(String directory)throws Exception{
        JSONArray result=new JSONArray();try(java.util.stream.Stream<Path> paths=Files.list(Paths.get(directory))){
            List<Path> files=paths.filter(Files::isRegularFile).sorted((a,b)->{try{return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));}catch(IOException e){return 0;}}).limit(30).toList();
            for(Path p:files)result.put(new JSONObject().put("path",p.toString()).put("name",p.getFileName().toString()).put("size",Files.size(p)));
        }return result;
    }
    @Override public void cancel(){for(Process p:children)terminate(p);}
    @Override public void disconnect(){cancel();open=false;}
    @Override public void close(){disconnect();deadlines.shutdown();super.close();}
}
