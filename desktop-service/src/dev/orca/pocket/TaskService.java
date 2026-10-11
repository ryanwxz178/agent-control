package dev.orca.pocket;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Single-owner service. IPC is protected by the SSH user's private filesystem, not a network port. */
public final class TaskService {
    private final TaskStore store;
    private final Map<String,PocketEngine> engines=new ConcurrentHashMap<>();
    private final Set<String> active=ConcurrentHashMap.newKeySet();
    private final Map<String,java.util.concurrent.atomic.AtomicBoolean> installations=new ConcurrentHashMap<>();
    private volatile String inventoryError="";
    private volatile boolean stopping;
    private JSONObject inventory=new JSONObject();
    private final BrowserBridge browser;
    TaskService(Path root)throws Exception{store=new TaskStore(root);browser=new BrowserBridge(root);}
    static Path root(){return Paths.get(System.getProperty("agent.control.data",Paths.get(System.getProperty("user.home"),".agent-control","data").toString()));}
    static String asset(String name)throws Exception{
        String fixtures=System.getProperty("agent.control.assets");if(fixtures!=null)return Files.readString(Paths.get(fixtures,name));
        try(InputStream in=TaskService.class.getResourceAsStream("/assets/"+name)){if(in==null)throw new FileNotFoundException(name);return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
    }
    private PocketEngine engine(String id){
        return new PocketEngine(new PocketEngine.Host(){
            public String asset(String name)throws Exception{return TaskService.asset(name);}
            public boolean trust(String host,String fingerprint){return false;}
            public boolean durable(){return true;}
            public JSONObject agentConfig(String run)throws Exception{
                if(!Files.exists(store.root.getParent().resolve("browser.json")))return new JSONObject();
                String java=Paths.get(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
                JSONArray args=new JSONArray().put("-Dagent.control.data="+store.root).put("-Dagent.control.task="+id).put("-Dagent.control.run="+run).put("-cp").put(System.getProperty("java.class.path")).put(BrowserMcp.class.getName());
                return new JSONObject().put("mcp_servers",new JSONObject().put("agent_control_browser",new JSONObject().put("command",java).put("args",args).put("startup_timeout_sec",30).put("tool_timeout_sec",60)));
            }
            public void event(String type,JSONObject data){
                try{
                    if(type.equals("connected")){inventory=TaskStore.copy(data);return;}
                    if(id==null)return;
                    JSONObject task=store.get(id);
                    if(type.equals("busy")){
                        if(!data.optBoolean("value")){
                            if(!Set.of("failed","unverifiable","paused","cancelled").contains(task.optString("status")))task.put("status","succeeded");
                            store.save(task);active.remove(id);schedule();
                        }return;
                    }
                    if(type.equals("interaction")){task.put("status",data.optString("kind").equals("questions")?"waiting_input":"waiting_approval");store.save(task);}
                    if(type.equals("interactionState")&&task.optString("status").startsWith("waiting_")){task.put("status","running");store.save(task);}
                    if(type.equals("error")){task.put("status","failed").put("error",data.optString("message"));store.save(task);}
                    if(type.equals("attachment")){
                        PocketEngine current=engines.get(id);Attachments.FileRef file=current==null?null:current.attachments.get(data.getString("attachmentId"));
                        if(file!=null){
                            if(file.inline!=null){Path target=store.root.resolve("files").resolve(data.getString("attachmentId"));TaskStore.atomic(target,file.inline);data.put("durablePath",target.toString());}
                            else {
                                try{byte[] bytes=readAttachment(file.path);Path target=store.root.resolve("files").resolve(data.getString("attachmentId"));TaskStore.atomic(target,bytes);data.put("durablePath",target.toString());}
                                catch(IOException unavailable){data.put("durablePath",file.path).put("durabilityWarning","原文件无法归档，下载取决于电脑原文件是否仍存在");}
                            }
                        }
                    }
                    store.event(id,type,data);
                }catch(Exception e){
                    System.err.println("Unable to checkpoint task "+id+": "+e.getMessage());
                    PocketEngine current=engines.get(id);if(current!=null)current.cancel();
                    // Fail closed: never keep acting while durable state is unavailable.
                    Runtime.getRuntime().halt(73);
                }
            }
        },new LocalTransport());
    }
    private static byte[] readAttachment(String path)throws IOException{
        Path source=Paths.get(path);if(!Files.isRegularFile(source)||Files.size(source)>20*1024*1024)throw new IOException("附件无法归档");
        try(InputStream input=Files.newInputStream(source)){byte[] bytes=input.readNBytes(20*1024*1024+1);if(bytes.length>20*1024*1024)throw new IOException("附件过大");return bytes;}
    }
    synchronized void schedule()throws Exception{
        JSONArray tasks=store.list();
        for(int i=0;i<tasks.length()&&active.size()<3;i++){
            JSONObject task=tasks.getJSONObject(i);if(!task.optString("status").equals("queued"))continue;
            JSONObject request=task.getJSONObject("request");String conversation=request.optString("conversationId");
            if(request.optString("kind").equals("monitor")){task.put("status","scheduled").put("nextRunAt",System.currentTimeMillis());store.save(task);continue;}
            boolean blocked=false;for(String running:active)if(store.get(running).getJSONObject("request").optString("conversationId").equals(conversation)){blocked=true;break;}
            if(blocked)continue;
            String id=task.getString("id");task.put("status","running").put("leaseId",UUID.randomUUID().toString());store.save(task);active.add(id);
            if(request.optString("kind").equals("browser_setup")){
                java.util.concurrent.atomic.AtomicBoolean cancel=new java.util.concurrent.atomic.AtomicBoolean();installations.put(id,cancel);
                store.event(id,"run",new JSONObject().put("id",id+"_setup").put("agent","OpenMuse").put("label","安装浏览器"));
                Thread installer=new Thread(()->{
                    StringBuilder output=new StringBuilder();
                    try{browser.install(line->{if(output.length()>20000)output.delete(0,10000);output.append(line).append('\n');store.event(id,"update",new JSONObject().put("id",id+"_setup").put("text",output.toString()).put("status","正在安装"));},cancel);
                        JSONObject t=store.get(id);if(!cancel.get()){store.event(id,"update",new JSONObject().put("id",id+"_setup").put("text","浏览器已安装，可以打开公网网页并从手机接管。").put("status","已完成"));t=store.get(id).put("status","succeeded");store.save(t);}
                    }catch(Exception error){try{JSONObject t=store.get(id);if(!cancel.get())t.put("status","failed").put("error",error.getMessage());store.save(t);}catch(Exception failure){Runtime.getRuntime().halt(73);}}
                    finally{installations.remove(id);active.remove(id);try{schedule();}catch(Exception failure){Runtime.getRuntime().halt(73);}}
                },"browser-install");installer.setDaemon(true);installer.start();continue;
            }
            // Close old presentation sessions so completed tasks don't retain unlimited executor threads.
            if(engines.size()>=30)for(String old:new ArrayList<>(engines.keySet()))if(!active.contains(old)){engines.remove(old).close();break;}
            PocketEngine e=engine(id);engines.put(id,e);
            StringBuilder history=new StringBuilder();
            List<JSONObject> prior=new ArrayList<>();
            for(int h=0;h<tasks.length();h++){
                JSONObject previous=tasks.getJSONObject(h);
                if(previous.getString("id").equals(id)||previous.optLong("createdAt")>=task.optLong("createdAt")||!previous.optString("status").equals("succeeded")||!previous.getJSONObject("request").optString("kind").isEmpty()||!previous.getJSONObject("request").optString("conversationId").equals(conversation))continue;
                prior.add(previous);
            }
            for(JSONObject previous:prior.subList(Math.max(0,prior.size()-8),prior.size())){
                JSONArray events=previous.getJSONArray("events");Set<String> finalRuns=new HashSet<>();
                for(int n=0;n<events.length();n++){JSONObject event=events.getJSONObject(n);JSONObject data=event.getJSONObject("data");if(event.optString("type").equals("run")&&!Set.of("规划分工","并行分析").contains(data.optString("label")))finalRuns.add(data.optString("id"));}
                StringBuilder answer=new StringBuilder();
                for(int n=0;n<events.length();n++){JSONObject event=events.getJSONObject(n);JSONObject data=event.getJSONObject("data");if(event.optString("type").equals("update")&&data.optString("status").equals("已完成")&&finalRuns.contains(data.optString("id")))answer.append(data.optString("text")).append('\n');}
                if(answer.length()>0)history.append("用户：").append(previous.getJSONObject("request").optString("prompt")).append("\n助手：").append(answer);
            }
            if(history.length()>0)request.put("history",history.substring(Math.max(0,history.length()-55000)));
            try{JSONObject local=TaskStore.copy(request).put("taskService",false);e.local(local);e.send(local);}catch(Exception error){task=store.get(id);task.put("status","failed").put("error",error.getMessage());store.save(task);active.remove(id);e.close();engines.remove(id);}
        }
    }
    synchronized JSONObject request(JSONObject input)throws Exception{
        String operation=input.getString("op");
        if(operation.startsWith("browser")){
            String taskId=input.optString("taskId");if(!taskId.isEmpty()&&!active.contains(taskId))throw new IllegalStateException("原 Agent 任务已结束，浏览器工具不能重放");
            if(!taskId.isEmpty()&&operation.equals("browserOpen")){
                JSONArray events=store.get(taskId).getJSONArray("events");for(int n=events.length()-1;n>=0;n--)if(events.getJSONObject(n).getString("type").equals("browser")){input.put("op","browserNavigate").put("sessionId",events.getJSONObject(n).getJSONObject("data").getString("sessionId"));break;}
            }
            JSONObject result=browser.request(input);
            if(!taskId.isEmpty()&&active.contains(taskId)&&result.has("browser"))store.event(taskId,"browser",TaskStore.copy(result.getJSONObject("browser")).put("id",input.optString("run")));
            return result;
        }
        if(operation.equals("snapshot"))return new JSONObject().put("tasks",store.snapshot()).put("inventory",inventory).put("inventoryError",inventoryError).put("version","0.5.6").put("runtime",new File(TaskService.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getAbsolutePath());
        if(operation.equals("shutdownIfIdle")){if(!active.isEmpty())return new JSONObject().put("deferred",true);stopping=true;return new JSONObject().put("deferred",false);}
        if(operation.equals("submit")){JSONObject result=store.create(input.getJSONObject("request"));schedule();return new JSONObject().put("task",result);}
        String id=TaskStore.key(input.getString("taskId"));JSONObject task=store.get(id);PocketEngine e=engines.get(id);
        if(operation.equals("control")){
            String action=input.getString("action"),status=task.getString("status");
            if(action.equals("pause")&&(status.equals("queued")||status.equals("scheduled")))task.put("status","paused");
            else if(action.equals("resume")&&status.equals("paused"))task.put("status","queued");
            else if(action.equals("cancel")){
                if(status.equals("queued")||status.equals("paused")||status.equals("scheduled"))task.put("status","cancelled");
                else if(active.contains(id)){task.put("status","unverifiable").put("error","已请求中断，仍需核对电脑端是否退出。");store.save(task);if(e!=null)e.cancel();if(installations.containsKey(id))installations.get(id).set(true);}
            }else throw new IllegalArgumentException("只可暂停尚未开始的任务；已执行或状态未知的任务不可自动重发。");
            store.save(task);schedule();return new JSONObject().put("task",task);
        }
        if(e==null)throw new IllegalStateException("该电脑会话已结束，操作不能从历史记录恢复");
        String token=input.optString("token"),run=input.optString("run");
        switch(operation){
            case "respond":e.respond(token,input.getJSONObject("answer"));break;
            case "inspectDesktop":e.inspectDesktop(run);break;
            case "operateDesktop":e.operateDesktop(token,input.getString("control"),input.optString("value"));break;
            case "tapDesktop":e.tapDesktop(token,input.getDouble("x"),input.getDouble("y"));break;
            case "collectDesktop":e.collectDesktop(run);break;
            case "listDownloads":e.listDownloads(run);break;
            case "addDownload":e.addDownload(run,input.getString("path"));break;
            default:throw new IllegalArgumentException("未知操作");
        }
        return new JSONObject().put("accepted",true);
    }
    void serve()throws Exception{
        TaskStore.privateDirectory(store.root.resolve("requests"));TaskStore.privateDirectory(store.root.resolve("responses"));
        try(FileChannel channel=FileChannel.open(store.root.resolve("service.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=channel.tryLock()){
            if(lock==null)return;store.recover();
            PocketEngine probe=engine(null);try{probe.local(new JSONObject());}catch(Exception e){inventoryError=e.getMessage();}finally{probe.close();}
            schedule();long heartbeat=0;
            while(!stopping){
                if(System.currentTimeMillis()-heartbeat>1000){heartbeat=System.currentTimeMillis();TaskStore.atomic(store.root.resolve("heartbeat.json"),new JSONObject().put("pid",ProcessHandle.current().pid()).put("time",heartbeat).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));checkMonitors();}
                try(java.util.stream.Stream<Path> files=Files.list(store.root.resolve("requests"))){
                    for(Path path:files.filter(p->p.toString().endsWith(".json")).sorted().toList()){
                        if(Files.isSymbolicLink(path)||Files.size(path)>1000000){Files.deleteIfExists(path);continue;}
                        String name=path.getFileName().toString();JSONObject result;
                        try{result=request(new JSONObject(Files.readString(path)));}catch(Exception e){result=new JSONObject().put("error",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}
                        TaskStore.atomic(store.root.resolve("responses").resolve(name),result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));Files.deleteIfExists(path);
                    }
                }
                Thread.sleep(100);
            }
            for(PocketEngine engine:engines.values())engine.close();
        }
    }
    private void checkMonitors()throws Exception{
        JSONArray tasks=store.list();for(int i=0;i<tasks.length();i++){
            JSONObject task=tasks.getJSONObject(i);if(!task.optString("status").equals("scheduled")||task.optLong("nextRunAt")>System.currentTimeMillis())continue;
            JSONObject request=task.getJSONObject("request");String id=task.getString("id");int interval=Math.max(1,Math.min(10080,request.optInt("intervalMinutes",15)));
            try{
                String session=task.optString("browserSession");if(session.isEmpty()){session=UUID.randomUUID().toString();task.put("browserSession",session);store.save(task);}
                JSONObject page=browser.observe(session,request.getString("url"));String text=page.optString("text"),hash=BrowserBridge.hash(text),previous=task.optString("lastHash");
                String condition=request.optString("condition","change"),value=request.optString("value");boolean matches=condition.equals("contains")?text.contains(value):!previous.isEmpty()&&!previous.equals(hash);
                boolean alert=matches&&(condition.equals("change")||!task.optBoolean("lastMatch"));
                task.put("lastHash",hash).put("lastMatch",matches).put("failures",0).remove("error");store.save(task);
                if(alert)store.event(id,"notice",new JSONObject().put("message","监控有更新："+page.optString("title",request.getString("url"))).put("url",page.optString("url")));
                task=store.get(id).put("nextRunAt",System.currentTimeMillis()+interval*60000L).put("lastCheckedAt",System.currentTimeMillis());store.save(task);
            }catch(Exception e){int failures=task.optInt("failures")+1;task.put("failures",failures).put("error",e.getMessage()).put("status",failures>=5?"paused":"scheduled").put("nextRunAt",System.currentTimeMillis()+interval*60000L*Math.min(16,1L<<Math.min(failures,4)));store.save(task);}
        }
    }
    static JSONObject client(JSONObject input,Path root,boolean start)throws Exception{
        TaskStore.privateDirectory(root);TaskStore.privateDirectory(root.resolve("requests"));TaskStore.privateDirectory(root.resolve("responses"));
        boolean running;
        try(FileChannel channel=FileChannel.open(root.resolve("service.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE)){
            FileLock lock=channel.tryLock();running=lock==null;if(lock!=null)lock.release();
        }
        if(!running){
            if(!start)throw new IllegalStateException("任务服务未运行");
            String java=Paths.get(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
            new ProcessBuilder(java,"-Dagent.control.data="+root,"-cp",System.getProperty("java.class.path"),TaskService.class.getName(),"serve").redirectInput(ProcessBuilder.Redirect.from(new File(System.getProperty("os.name").startsWith("Windows")?"NUL":"/dev/null"))).redirectOutput(ProcessBuilder.Redirect.appendTo(root.resolve("service.log").toFile())).redirectErrorStream(true).start();
        }
        String id=UUID.randomUUID().toString()+".json";Path response=root.resolve("responses").resolve(id);
        TaskStore.atomic(root.resolve("requests").resolve(id),input.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long until=System.currentTimeMillis()+45000;
        while(System.currentTimeMillis()<until){if(Files.exists(response)){JSONObject result=new JSONObject(Files.readString(response));Files.deleteIfExists(response);return result;}Thread.sleep(100);}
        throw new IOException("服务响应超时；提交状态未知，请刷新任务列表，不要重复发送");
    }
    public static void main(String[] args)throws Exception{
        if(args.length>0&&args[0].equals("serve")){new TaskService(root()).serve();return;}
        String payload;
        if(args.length==2&&args[0].equals("--file")){Path file=Paths.get(args[1]);if(Files.size(file)>1000000)throw new IllegalArgumentException("请求过大");payload=Files.readString(file);Files.delete(file);}
        else payload=args.length>0?new String(Base64.getDecoder().decode(args[0]),java.nio.charset.StandardCharsets.UTF_8):new String(System.in.readNBytes(1000001),java.nio.charset.StandardCharsets.UTF_8);
        if(payload.length()>1000000)throw new IllegalArgumentException("请求过大");
        System.out.println(client(new JSONObject(payload),root(),true));
    }
}
