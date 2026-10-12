package dev.orca.pocket;

import org.json.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

final class PocketEngine {
    interface Host { String asset(String name)throws Exception; void event(String type,JSONObject data); boolean trust(String host,String fingerprint)throws Exception; default JSONObject agentConfig(String run)throws Exception{return new JSONObject();} default boolean durable(){return false;} default byte[] bytes(String name)throws Exception{return asset(name).getBytes(java.nio.charset.StandardCharsets.UTF_8);} default void file(String id,String name,byte[] data)throws Exception{} }
    private final Host host;
    final Interactions interactions;
    final Attachments attachments;
    private final ExecutorService actions=Executors.newSingleThreadExecutor();
    final SshTransport ssh;
    final AtomicBoolean busy=new AtomicBoolean(), cancelled=new AtomicBoolean();
    private final ExecutorService control=Executors.newSingleThreadExecutor();
    private static final Object desktopLock=new Object();
    private final Map<String,JSONObject> desktopRuns=new ConcurrentHashMap<>();
    private final Map<String,JSONObject> desktopViews=new ConcurrentHashMap<>();
    private volatile String activeDesktopRun="";
    private final ScheduledExecutorService monitor=Executors.newSingleThreadScheduledExecutor();
    // Credentials live only in this Activity's memory, never in events or saved state.
    private volatile JSONObject reconnectProfile;
    private volatile byte[] reconnectKey;
    private volatile boolean closed;
    private final AtomicInteger downloading=new AtomicInteger();
    private int reconnectAttempts;
    private long nextReconnect;
    private static final int MAX_RECONNECT_ATTEMPTS=3;
    private volatile Inventory inventory=new Inventory();
    private volatile JSONObject profile=new JSONObject();
    private final PersistentClient service;
    private volatile boolean serviceReady;
    private final AtomicBoolean polling=new AtomicBoolean();
    PocketEngine(Host host){this(host,new SshTransport());}
    PocketEngine(Host host,SshTransport transport){
        this.host=host;this.ssh=transport;interactions=new Interactions(host::event);attachments=new Attachments(host::event);
        service=new PersistentClient(host,ssh,attachments);
        monitor.scheduleWithFixedDelay(this::checkConnection,5,10,TimeUnit.SECONDS);
        monitor.scheduleWithFixedDelay(this::pollTasks,3,3,TimeUnit.SECONDS);
    }
    void local(JSONObject p)throws Exception{profile=new JSONObject(p.toString());refresh(false);}
    private void pollTasks(){
        if(closed||!serviceReady||!ssh.connected()||!polling.compareAndSet(false,true))return;
        try{actions.execute(()->{try{service.snapshot();}catch(Exception e){emit("serviceError","message",message(e));}finally{polling.set(false);}});}catch(RejectedExecutionException e){polling.set(false);}
    }
    void taskRequest(String raw){actions.execute(()->{try{host.event("serviceResponse",service.request(new JSONObject(raw)));service.snapshot();}catch(Exception e){emit("serviceError","message",message(e));}});}
    private static JSONObject fields(Object... values){
        try{JSONObject data=new JSONObject();for(int i=0;i<values.length;i+=2)data.put((String)values[i],values[i+1]);return data;}catch(Exception e){throw new IllegalArgumentException(e);}
    }
    private boolean remote(String op,JSONObject data,String field,String identity){
        if(!profile.optBoolean("taskService"))return false;
        actions.execute(()->{try{data.put("op",op).put("taskId",service.taskFor(field,identity));service.request(data);service.snapshot();}catch(Exception e){emit("serviceError","message",message(e));}});return true;
    }
    void checkConnection(){
        if(closed || downloading.get()>0 || reconnectProfile==null || !busy.compareAndSet(false,true))return;
        try{control.execute(()->{
            try{
                if(ssh.connected()){
                    try{ssh.run(inventory.os.equals("Windows")?Agent.powershell("exit 0"):"true","",line->{},new AtomicBoolean(),8000);return;}
                    catch(Exception e){ssh.disconnect();}
                }
                emit("connection","status","连接已断开");
                if(reconnectProfile==null || closed || reconnectAttempts>=MAX_RECONNECT_ATTEMPTS || System.currentTimeMillis()<nextReconnect)return;
                JSONObject p=reconnectProfile;
                reconnectAttempts++;
                emit("connection","status","正在重连（"+reconnectAttempts+"/"+MAX_RECONNECT_ATTEMPTS+"）");
                emit("busy","value",true);
                try{
                    ssh.connect(p.getString("host").trim(),p.optInt("port",22),p.getString("user").trim(),p.optString("password"),reconnectKey,host::trust);
                    if(closed || reconnectProfile==null){ssh.disconnect();return;}
                    refresh(true);reconnectAttempts=0;
                }catch(Exception e){
                    ssh.disconnect();nextReconnect=System.currentTimeMillis()+10000L*reconnectAttempts;
                    emit("connection","status",reconnectAttempts>=MAX_RECONNECT_ATTEMPTS?"重连失败，请手动连接":"连接已断开，等待重连");
                    if(reconnectAttempts>=MAX_RECONNECT_ATTEMPTS)emit("error","message","自动重连失败："+message(e)+"。请检查电脑唤醒状态和网络后手动连接。");
                }
            }catch(Exception e){emit("connection","status","连接已断开");}
            finally{busy.set(false);emit("busy","value",false);}
        });}catch(RejectedExecutionException e){busy.set(false);}
    }
    void disconnect(){serviceReady=false;interactions.clear(null);attachments.clear();desktopRuns.clear();desktopViews.clear();reconnectProfile=null;reconnectKey=null;ssh.disconnect();emit("connection","status","未连接");}
    void emit(String type,String key,Object value){try{host.event(type,new JSONObject().put(key,value));}catch(Exception ignored){}}
    void connect(JSONObject p,byte[] key){
        if(downloading.get()>0){emit("error","message","附件正在读取，请稍后再更换连接");return;}
        if(!busy.compareAndSet(false,true)){emit("error","message","当前任务尚未结束");return;}
        control.execute(()->{
            try{
                String address=p.getString("host").trim(),user=p.getString("user").trim();int port=p.optInt("port",22);
                if(address.isEmpty()||address.contains("/")||user.isEmpty()||port<1||port>65535)throw new IllegalArgumentException("请输入有效电脑地址、SSH 端口和用户名");
                reconnectProfile=null;reconnectKey=null;interactions.clear(null);attachments.clear();desktopRuns.clear();desktopViews.clear();
                emit("connection","status","正在连接");
                ssh.connect(address,port,user,p.optString("password"),key,host::trust);
                profile=new JSONObject(p.toString());profile.remove("password");
                refresh(false);
                reconnectProfile=new JSONObject(p.toString());reconnectKey=key==null?null:key.clone();
                reconnectAttempts=0;nextReconnect=0;
            }catch(Exception e){ssh.disconnect();emit("error","message","连接失败："+message(e));emit("connection","status","未连接");}
            finally{busy.set(false);emit("busy","value",false);}
        });
    }
    private void refresh(boolean recovered)throws Exception{
        serviceReady=false;
        Inventory found=new Inventory();
        Exception posixError=null;
        try{ssh.run(Agent.login(host.asset("detect.sh")),"",found::accept,new AtomicBoolean(),45000);}
        catch(Exception e){posixError=e;}
        if(!found.complete){
            Inventory windows=new Inventory();
            try{ssh.runWindowsScript(host.asset("detect-windows.ps1"),windows::accept,new AtomicBoolean(),45000);}
            catch(Exception e){throw new IllegalStateException("主机检测失败。Windows 需要 Windows PowerShell 5.1 和 OpenSSH；macOS/Linux 需要 POSIX shell。"+message(e),posixError);}
            found=windows;
        }
        if(!found.complete)throw new IllegalStateException("未收到完整的 Agent 检测结果");
        if(!found.os.equals("Darwin") && !found.os.equals("Linux") && !found.os.equals("Windows"))throw new IllegalStateException("不支持的电脑系统："+found.os);
        inventory=found;
        if(profile.optString("directory").trim().isEmpty())profile.put("directory",found.home);
        JSONArray agents=new JSONArray();for(Agent a:found.agents.values())agents.put(a.json());
        host.event("connected",new JSONObject().put("agents",agents).put("profile",profile).put("os",found.os).put("recovered",recovered));
        if(profile.optBoolean("taskService")){
            try{service.prepare(found,profile);serviceReady=true;emit("serviceReady","value",true);}
            catch(Exception e){emit("serviceError","message",message(e));}
        }
        if(found.os.equals("Windows")&&found.desktopApps.isEmpty())emit("notice","message","Windows SSH 已连接。若要使用 WorkBuddy / 豆包桌面，请在同一 Windows 用户的已解锁桌面启动 Agent Control Bridge，再重新连接以刷新应用列表。");
    }
    void send(JSONObject request){
        if(profile.optBoolean("taskService")){
            if(!ssh.connected()||!serviceReady){emit("serviceError","message","持久任务服务未就绪，本条消息尚未提交；请重新连接或检查 Java 17。");try{host.event("rejected",new JSONObject().put("messageId",request.optString("messageId")).put("prompt",request.optString("displayPrompt",request.optString("prompt"))).put("uploads",request.optJSONArray("uploads")));}catch(Exception ignored){}return;}
            actions.execute(()->{try{service.request(new JSONObject().put("op","submit").put("request",request));service.snapshot();}catch(Exception e){emit("serviceError","message",message(e));}finally{emit("busy","value",false);}});return;
        }
        if(!ssh.connected()){
            emit("connection","status","连接已断开");
            try{host.event("rejected",new JSONObject().put("messageId",request.optString("messageId")).put("prompt",request.optString("displayPrompt",request.optString("prompt"))).put("uploads",request.optJSONArray("uploads")));}catch(Exception ignored){}
            emit("error","message","连接已断开，本条消息尚未发送，已保留在输入框。正在尝试恢复连接。");
            emit("busy","value",busy.get());checkConnection();return;
        }
        if(!busy.compareAndSet(false,true)){
            try{host.event("rejected",new JSONObject().put("messageId",request.optString("messageId")).put("prompt",request.optString("displayPrompt",request.optString("prompt"))).put("uploads",request.optJSONArray("uploads")));}catch(Exception ignored){}
            emit("error","message","正在检查连接或执行任务，本条消息尚未发送，已保留在输入框。");emit("busy","value",busy.get());return;
        }
        cancelled.set(false);emit("busy","value",true);
        control.execute(()->{
            try{
                String prompt=request.getString("prompt"),history=request.optString("history"),id=request.optString("agent","auto");
                if(prompt.trim().isEmpty()||prompt.length()>20000||history.length()>60000)throw new IllegalArgumentException("消息过长或为空");
                boolean write=request.optBoolean("write"), desktop=true, hostPermissions=request.optBoolean("hostPermissions");
                String mode=request.optString("accessMode","auto");
                boolean hasFiles=request.optJSONArray("inputFiles")!=null&&request.getJSONArray("inputFiles").length()>0;
                JSONArray desktopFiles=request.optJSONArray("desktopFiles");
                if(hasFiles&&mode.equals("desktop")&&(desktopFiles==null||desktopFiles.length()==0))throw new IllegalArgumentException("桌面附件引用缺失，请重新选择文件");
                if(hasFiles&&mode.equals("desktop")&&id.equals("auto"))throw new IllegalArgumentException("带附件的桌面对话请先选择一个 Agent");
                if(!mode.equals("auto")&&!mode.equals("desktop")&&!mode.equals("cli"))throw new IllegalArgumentException("未知接入方式");
                String context=history.isEmpty()?prompt:"当前任务：\n"+prompt+"\n\n此前对话（仅供上下文）：\n"+history;
                String directory=request.optString("directory",profile.optString("directory"));
                if(!Agent.absoluteDirectory(directory,inventory.os))throw new IllegalArgumentException("工作目录须为电脑上的绝对路径：Windows 如 C:\\Users\\你的用户名\\project，macOS/Linux 如 /Users/name/project");
                TaskRouter.Runner runner=(a,text,allowWrite,label)->runAgent(a,text,allowWrite,label,directory,!mode.equals("cli")&&(!hasFiles||mode.equals("desktop")),hostPermissions,request.optString("conversationId"),id.equals("auto")?text:prompt,desktopFiles);
                if(id.equals("auto")){
                    List<Agent> eligible=new ArrayList<>();
                    for(Agent a:inventory.agents.values()){
                        if(!a.supported()||(hasFiles&&!mode.equals("desktop")&&a.desktop()))continue;
                        if(mode.equals("desktop")){a=inventory.desktopAgent(a.id);if(a==null)continue;}
                        if(mode.equals("cli")&&a.desktop())continue;
                        if(!a.kind.equals("dsh")||hostPermissions)eligible.add(a);
                    }
                    new TaskRouter(runner,t->emit("notice","message",t)).run(eligible,context,write,cancelled);
                }else{
                    Agent agent=inventory.agents.get(id);
                    if(agent==null)throw new IllegalArgumentException("该 Agent 不在当前电脑的安装列表中");
                    agent=chooseAccess(inventory,agent,hasFiles&&!mode.equals("desktop")?"cli":mode);
                    runner.run(agent,context,write,"回复");
                }
            }catch(EventDecoder.DesktopEnded e){emit("notice","message","原桌面会话已确认结束。");}
            catch(CancellationException e){emit("notice","message","已停止接收并请求中断。桌面应用或远端子任务可能仍在执行，请到电脑确认。");}
            catch(Exception e){emit("error","message",message(e));}
            finally{busy.set(false);emit("busy","value",false);if(!ssh.connected())emit("connection","status","连接已断开");}
        });
    }
    private String runAgent(Agent a,String prompt,boolean write,String label,String directory,boolean desktop,boolean hostPermissions,String conversationId,String currentPrompt,JSONArray desktopFiles)throws Exception{
        if(cancelled.get())throw new CancellationException();
        String run=UUID.randomUUID().toString();
        final String desktopPrompt=(conversationId.isEmpty()?prompt:currentPrompt)+"\n\n[Agent Control request "+run+"]";
        host.event("run",new JSONObject().put("id",run).put("agent",a.name).put("label",label));
        AtomicLong last=new AtomicLong();
        EventDecoder decoder=new EventDecoder((text,status)->{
            long now=System.currentTimeMillis();
            if(now-last.get()<100 && !status.equals("已完成") && !status.equals("执行失败"))return;
            last.set(now);
            try{host.event("update",new JSONObject().put("id",run).put("text",text).put("status",status));}catch(Exception ignored){}
        });
        decoder.extra=event->{
            String type=event.optString("type");
            if(type.equals("agent.session"))host.event("agentSession",event.put("id",run));
            if(type.equals("assistant")){JSONObject msg=event.optJSONObject("message");JSONArray content=msg==null?null:msg.optJSONArray("content");if(content!=null)for(int i=0;i<content.length();i++)if(content.optJSONObject(i)!=null)attachments.content(run,content.getJSONObject(i),computerKey());}
            if(type.equals("attachment"))attachments.content(run,event,computerKey());
            if(type.equals("desktop.interaction"))desktopInteraction(run,a,event,desktopPrompt);
            if(type.equals("desktop.media"))captureDesktopMedia(run);
            if(type.equals("desktop.interaction.closed"))interactions.clear(run);
        };
        String result;
        try{
            if(a.desktop()){
                if(!desktop)throw new IllegalStateException("请在连接设置开启桌面接入；豆包等桌面 Agent 需要 macOS 辅助功能权限和解锁的屏幕");
                synchronized(desktopLock){
                    if(cancelled.get())throw new CancellationException();
                    if(inventory.os.equals("Windows")){
                        String payload=new JSONObject().put("app",a.command.get(0)).put("prompt",desktopPrompt).put("conversationId",conversationId).put("files",desktopFiles==null?new JSONArray():desktopFiles).toString();
                        String script=host.asset("desktop-windows-client.ps1").replace("__REQUEST_BASE64__",Base64.getEncoder().encodeToString(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                        ssh.runWindowsScript(script,decoder::accept,cancelled,650000);
                    }else{
                        activeDesktopRun=run;
                        JSONObject context=new JSONObject().put("app",a.command.get(0)).put("prompt",desktopPrompt).put("computer",computerKey());
                        desktopRuns.entrySet().removeIf(entry->entry.getValue().optString("app").equals(a.command.get(0)));
                        if(desktopRuns.size()>=8)desktopRuns.clear();desktopViews.clear();desktopRuns.put(run,context);
                        host.event("desktopAvailable",new JSONObject().put("id",run).put("agent",a.name));
                        String script=host.asset("desktop-session.js")+"\n"+host.asset("desktop-chat.js").replace("__REQUEST__",new JSONObject().put("app",a.command.get(0)).put("prompt",desktopPrompt).put("conversationId",conversationId).put("files",desktopFiles==null?new JSONArray():desktopFiles).toString());
                        ssh.run("/usr/bin/osascript -l JavaScript -",script,decoder::accept,cancelled,650000);
                    }
                }
                result=decoder.text();
                if(!decoder.completed&&!decoder.failed)throw new IllegalStateException("桌面连接结束但没有完成事件，执行状态未知；请在电脑确认");
            }else if(a.kind.equals("teleagent")){
                if(!inventory.os.equals("Windows")||a.command.size()!=2)throw new IllegalStateException("TeleAgent 后台 CLI 目前仅支持 Windows");
                ssh.interactiveWindowsScript(teleAgentWindowsScript(a,directory),new TeleAgentSession(run,prompt,directory,computerKey(),a.name,write,decoder,interactions,attachments),cancelled,900000);
                result=decoder.text();
                if(!decoder.completed)throw new IllegalStateException("TeleAgent 后台连接结束但没有完成事件，执行状态未知");
            }else if(a.kind.equals("codex")){
                ssh.interactive(CodexSession.command(a,directory,inventory.os),new CodexSession(run,prompt,directory,write,computerKey(),decoder,interactions,attachments,host.agentConfig(run),host.durable()),cancelled,900000);
                result=decoder.text();
            }else if(a.kind.equals("dsh")||a.kind.equals("buddy")){
                if(a.kind.equals("dsh")&&!hostPermissions)throw new IllegalStateException("DeepSeek Harness 使用电脑现有权限，请先在设置中启用对应选项");
                String acpCommand=AcpSession.command(a,directory,write,inventory.os);
                if(a.bundledWorkBuddy()&&inventory.os.equals("Darwin")){
                    String script=host.asset("workbuddy-acp-launch.js").replace("__COMMAND__",new JSONArray(a.command).toString()).replace("__WRITE__",write?"true":"false");
                    acpCommand=Agent.login("cd -- "+Agent.quote(directory)+" && exec "+Agent.quote(a.command.get(0))+" -e "+Agent.quote(script));
                }
                if(a.kind.equals("dsh")&&inventory.os.equals("Darwin")&&a.command.size()==1&&a.command.get(0).endsWith("/DeepSeek Harness.app/Contents/Resources/runtime/cli/bin/dsh")){
                    String launcher=a.command.get(0),resources=launcher.substring(0,launcher.length()-"runtime/cli/bin/dsh".length());
                    String script=host.asset("dsh-acp-launch.js").replace("__LAUNCHER__",JSONObject.quote(launcher));
                    acpCommand=Agent.login("cd -- "+Agent.quote(directory)+" && exec env ELECTRON_RUN_AS_NODE=1 "+Agent.quote(resources+"../MacOS/DeepSeek Harness")+" -e "+Agent.quote(script));
                }
                AcpSession protocol=new AcpSession(run,prompt,directory,computerKey(),a.name,decoder,interactions,attachments);
                if(a.bundledWorkBuddy()&&inventory.os.equals("Windows")){
                    String script=host.asset("workbuddy-windows-cli.js").replace("__COMMAND__",new JSONArray(a.command).toString()).replace("__WRITE__",write?"true":"false");
                    ssh.interactiveWindowsScript("Set-Location -LiteralPath "+Agent.psQuote(directory)+"; "+Agent.windowsNodeScript(a.command.get(0),script,false),protocol,cancelled,900000);
                }else if(a.id.equals("deepseek")&&inventory.os.equals("Windows")&&a.command.size()==1&&a.command.get(0).replace('\\','/').endsWith("/resources/runtime/cli/bin/dsh.cmd")){
                    String launcher=a.command.get(0).replace('\\','/'),resources=launcher.substring(0,launcher.length()-"runtime/cli/bin/dsh.cmd".length());
                    String script=host.asset("dsh-acp-launch.js").replace("__LAUNCHER__",JSONObject.quote(launcher));
                    ssh.interactiveWindowsScript("Set-Location -LiteralPath "+Agent.psQuote(directory)+"; "+Agent.windowsNodeScript(resources+"../DeepSeek Harness.exe",script,true),protocol,cancelled,900000);
                }else ssh.interactive(acpCommand,protocol,cancelled,900000);
                result=decoder.text();
            }else{
                String command=a.shell(directory,write,inventory.os);
                if(inventory.os.equals("Windows"))ssh.runWindowsScript(Agent.windowsInputScript(command,prompt),decoder::accept,cancelled,900000);
                else ssh.run(command,prompt,decoder::accept,cancelled,900000);
                result=decoder.text();
                if(!decoder.completed)throw new IllegalStateException("Agent 连接已结束，但没有返回任务完成事件；请检查电脑端登录或版本");
            }
            if(decoder.failed)throw new IllegalStateException(decoder.text());
            attachments.scan(run,result,computerKey());
            if(result.trim().isEmpty()&&attachments.has(run))result="已返回附件，请在下方查看。";
            if(result.trim().isEmpty())throw new IllegalStateException("Agent 未返回回复，请检查电脑端登录和权限；WorkBuddy 可切换为桌面接入");
            host.event("update",new JSONObject().put("id",run).put("text",result).put("status","已完成"));
            return result;
        }catch(Exception e){
            if(!a.desktop()&&desktop&&inventory.desktopAgent(a.id)!=null&&!cancelled.get()&&ssh.connected()&&canFallback(decoder,e)){
                host.event("update",new JSONObject().put("id",run).put("text","CLI 启动失败，正在使用电脑上的 "+a.name+" 桌面应用。").put("status","已自动切换桌面"));
                return runAgent(inventory.desktopAgent(a.id),prompt,write,label,directory,true,hostPermissions,conversationId,currentPrompt,desktopFiles);
            }
            host.event("update",new JSONObject().put("id",run).put("text",decoder.text()).put("status",e instanceof EventDecoder.DesktopEnded?"已结束":cancelled.get()?"状态待确认":"失败："+message(e)));
            throw e;
        }finally{interactions.clear(run);if(activeDesktopRun.equals(run))activeDesktopRun="";}
    }
    private String teleAgentWindowsScript(Agent agent,String directory)throws Exception{
        String[] assets={"teleagent-auth.js","teleagent-config.js","teleagent-headless-cli.js"};
        StringBuilder script=new StringBuilder("Set-Location -LiteralPath "+Agent.psQuote(directory)+"; $moduleFiles=@(); try { ");
        for(int i=0;i<assets.length;i++)script.append("$module").append(i).append("=$PSCommandPath+'.teleagent-").append(i).append(".js'; $moduleFiles+= $module").append(i).append("; [IO.File]::WriteAllText($module").append(i).append(',').append(Agent.psQuote(host.asset(assets[i]))).append(",[Text.UTF8Encoding]::new($false)); ");
        script.append("$env:TELEAGENT_BRIDGE_AUTH_MODULE=$module0; $env:TELEAGENT_BRIDGE_CONFIG_MODULE=$module1; $env:TELEAGENT_DESKTOP_PATH=").append(Agent.psQuote(agent.command.get(1))).append("; & ").append(Agent.psQuote(agent.command.get(0))).append(" $module2; $code=$LASTEXITCODE } finally { foreach($file in $moduleFiles){[IO.File]::Delete($file)} }; exit $code");
        return script.toString();
    }
    void listDownloads(String run){if(remote("listDownloads",fields("run",run),"id",run))return;actions.execute(()->{try{
        JSONObject context=desktopRuns.get(run);if(context==null||!context.optString("computer").equals(computerKey()))throw new IllegalStateException("原电脑连接已改变");
        JSONArray files=ssh.listDownloads(inventory.home.replace('\\','/')+"/Downloads");host.event("desktopDownloads",new JSONObject().put("id",run).put("files",files));
    }catch(Exception e){emit("desktopError","message",message(e));}});}
    void addDownload(String run,String path){if(remote("addDownload",fields("run",run,"path",path),"id",run))return;actions.execute(()->{try{
        JSONObject context=desktopRuns.get(run);String folder=inventory.home.replace('\\','/')+"/Downloads/";
        if(context==null||!context.optString("computer").equals(computerKey())||!path.startsWith(folder)||path.substring(folder.length()).contains("/")||path.substring(folder.length()).contains("\\")||path.endsWith("/.."))throw new IllegalStateException("文件路径已失效");
        attachments.local(run,path,computerKey());host.event("desktopOperated",new JSONObject().put("id",run).put("message","已添加到聊天附件，请返回聊天点按该文件读取"));
    }catch(Exception e){emit("desktopError","message",message(e));}});}
    void captureDesktopMedia(String run){actions.execute(()->{try{
        JSONObject context=desktopRuns.get(run);if(context==null||!context.optString("computer").equals(computerKey()))return;
        runPanel(new JSONObject(context.toString()).put("action","inspect"),event->{String preview=event.optString("preview");if(preview.startsWith("data:image/jpeg;base64,"))attachments.inline(run,preview.substring(preview.indexOf(',')+1),"image/jpeg",computerKey(),"电脑画面（非原图）.jpg");});
    }catch(Exception e){emit("notice","message","图片画面未能读取："+message(e)+"。可在对应回复点击查看电脑。");}});}
    void tapDesktop(String token,double x,double y){if(remote("tapDesktop",fields("token",token,"x",x,"y",y),"token",token))return;actions.execute(()->{try{
        JSONObject request=desktopViews.remove(token);if(request==null||request.getLong("expiresAt")<System.currentTimeMillis()||!request.optString("computer").equals(computerKey())||(!activeDesktopRun.isEmpty()&&!activeDesktopRun.equals(request.getString("run"))))throw new IllegalStateException("画面已失效，请刷新");
        if(!Double.isFinite(x)||!Double.isFinite(y)||x<0||x>1||y<0||y>1)throw new IllegalArgumentException("点击位置无效");
        request.put("action","point").put("x",x).put("y",y);
        runPanel(request,event->{if(event.optString("type").equals("desktop.operated"))host.event("desktopOperated",event.put("id",request.getString("run")));});
    }catch(Exception e){emit("desktopError","message",message(e));}});}
    void collectDesktop(String run){if(remote("collectDesktop",fields("run",run),"id",run))return;actions.execute(()->{try{
        JSONObject context=desktopRuns.get(run);if(context==null||!context.optString("computer").equals(computerKey()))throw new IllegalStateException("桌面对话已失效");
        runPanel(new JSONObject(context.toString()).put("action","collect"),event->{if(event.optString("type").equals("attachment"))attachments.content(run,event,computerKey());if(event.optString("type").equals("desktop.reply")){attachments.scan(run,event.getString("text"),computerKey());host.event("desktopOperated",new JSONObject().put("id",run).put("message","已提取回复中的本地文件链接，请回聊天查看附件；没有链接时请使用电脑画面的下载控件"));}});
    }catch(Exception e){emit("desktopError","message",message(e));}});}
    void inspectDesktop(String run){if(remote("inspectDesktop",fields("run",run),"id",run))return;actions.execute(()->{try{
        JSONObject context=desktopRuns.get(run);
        if(context==null||!context.optString("computer").equals(computerKey())||(!activeDesktopRun.isEmpty()&&!activeDesktopRun.equals(run)))throw new IllegalStateException("这条桌面对话已结束或被切换，请查看当前任务");
        JSONObject request=new JSONObject(context.toString()).put("action","inspect");
        runPanel(request,event->{
            if(!event.optString("type").equals("desktop.panel"))return;
            String token=UUID.randomUUID().toString();
            desktopViews.clear();desktopViews.put(token,new JSONObject(request.toString()).put("fingerprint",event.getString("fingerprint")).put("controls",event.getJSONArray("controls")).put("run",run).put("expiresAt",System.currentTimeMillis()+60000));
            event.remove("fingerprint");event.put("id",run).put("token",token);host.event("desktopPanel",event);
        });
    }catch(Exception e){emit("desktopError","message",message(e));}});}
    void operateDesktop(String token,String control,String value){if(remote("operateDesktop",fields("token",token,"control",control,"value",value),"token",token))return;actions.execute(()->{try{
        JSONObject request=desktopViews.remove(token);if(request==null||request.getLong("expiresAt")<System.currentTimeMillis()||!request.optString("computer").equals(computerKey())||(!activeDesktopRun.isEmpty()&&!activeDesktopRun.equals(request.getString("run"))))throw new IllegalStateException("电脑画面已过期，请先刷新");
        JSONArray controls=request.getJSONArray("controls");boolean found=false;for(int i=0;i<controls.length();i++)if(controls.getJSONObject(i).getString("id").equals(control))found=true;
        if(!found||value.length()>20000)throw new IllegalArgumentException("未知控件或输入过长");
        request.put("action","operate").put("control",control).put("value",value);
        runPanel(request,event->{if(event.optString("type").equals("desktop.operated"))host.event("desktopOperated",event.put("id",request.getString("run")));});
    }catch(Exception e){emit("desktopError","message",message(e));}});}
    private void runPanel(JSONObject request,EventDecoder.Extra result)throws Exception{
        String shared=host.asset("desktop-chat.js");shared=shared.substring(0,shared.indexOf("if(!bundles[request.app])"));
        String script=(shared+(host.asset("desktop-session.js")+"\n"+host.asset("desktop-panel.js"))).replace("__REQUEST__",request.toString());
        ssh.run("/usr/bin/osascript -l JavaScript -",script,line->{if(!line.startsWith("{"))return;JSONObject event=new JSONObject(line);if(event.optString("type").equals("error"))throw new IllegalStateException(event.optString("text"));result.accept(event);},new AtomicBoolean(),30000);
    }
    String computerKey(){return profile.optString("host")+"|"+profile.optInt("port",22)+"|"+profile.optString("user");}
    JSONObject uploadInput(String name,String mime,byte[] bytes)throws Exception{
        if(!ssh.connected())throw new IllegalStateException("请先连接电脑再上传文件");
        if(bytes.length>20*1024*1024)throw new IllegalArgumentException("单个文件不能超过 20MB");
        String computer=computerKey(),directory=profile.optString("directory"),os=inventory.os;
        if(!Agent.absoluteDirectory(directory,os))throw new IllegalArgumentException("请先设置有效的电脑工作目录");
        String safe=name.replaceAll("[^\\p{L}\\p{N}._ -]","_").replaceAll("[ .]+$","");if(safe.isEmpty())safe="file";if(safe.length()>120)safe=safe.substring(safe.length()-120);
        String folder=directory.replace('\\','/').replaceAll("/+$","")+"/.agent-control-input";
        String path=folder+"/"+UUID.randomUUID()+"-"+safe;
        String mkdir=os.equals("Windows")?Agent.powershell("New-Item -ItemType Directory -Force -Path "+Agent.psQuote(folder)+" | Out-Null"):"mkdir -p -- "+Agent.quote(folder)+" && chmod 700 "+Agent.quote(folder);
        ssh.run(mkdir,"",line->{},new AtomicBoolean(),15000);
        if(!computer.equals(computerKey()))throw new IllegalStateException("电脑连接已改变，请重新上传");
        ssh.upload(path,bytes);
        if(!computer.equals(computerKey()))throw new IllegalStateException("电脑连接已改变，请重新上传");
        return new JSONObject().put("name",name).put("mime",mime).put("size",bytes.length).put("path",path).put("computer",computer).put("directory",directory);
    }
    void respond(String token,JSONObject answer){if(remote("respond",fields("token",token,"answer",answer),"token",token))return;actions.execute(()->{try{interactions.respond(token,answer);}catch(Exception e){emit("notice","message",message(e));}});}
    void download(String id){downloading.incrementAndGet();actions.execute(()->{try{
        Attachments.FileRef file=attachments.get(id);
        if(file==null||!file.host.equals(computerKey()))throw new IllegalStateException("附件链接已失效，请在原电脑重新获取回复");
        byte[] data=file.inline!=null?file.inline:RemoteImages.allowed(file.path)?RemoteImages.read(file.path):ssh.download(file.path,Attachments.LIMIT);
        host.file(id,file.name,data);
    }catch(Exception e){try{host.event("attachmentError",new JSONObject().put("attachmentId",id).put("message",message(e)));}catch(Exception ignored){}}finally{downloading.decrementAndGet();}});}
    private void desktopInteraction(String run,Agent agent,JSONObject event,String prompt)throws Exception{
        if(!inventory.os.equals("Darwin"))return;
        final String fingerprint=event.getString("fingerprint");final JSONArray options=event.getJSONArray("options");final JSONArray inputs=event.optJSONArray("inputs")==null?new JSONArray():event.getJSONArray("inputs");
        interactions.clear(run);
        interactions.add(run,new JSONObject().put("title",agent.name+" 等待确认").put("detail",event.getString("detail")).put("kind","approval").put("desktop",true).put("options",options).put("inputs",inputs),answer->{
            String choice=answer.getString("choice");boolean allowed=false;
            for(int i=0;i<options.length();i++)if(options.getJSONObject(i).getString("id").equals(choice))allowed=true;
            if(choice.equals("__desktop_text__")&&inputs.length()>0)allowed=true;
            if(!allowed||cancelled.get())throw new IllegalStateException("确认请求已失效");
            JSONObject values=answer.optJSONObject("textValues");if(values==null)values=new JSONObject();
            for(Iterator<String> keys=values.keys();keys.hasNext();){String id=keys.next();boolean valid=false;for(int i=0;i<inputs.length();i++)if(inputs.getJSONObject(i).getString("id").equals(id))valid=true;Object value=values.get(id);if(!valid||!(value instanceof String)||((String)value).length()>10000||((String)value).indexOf(0)>=0)throw new IllegalStateException("问答文字无效或输入框已失效");}
            JSONObject req=new JSONObject().put("app",agent.command.get(0)).put("action","confirm").put("prompt",prompt).put("fingerprint",fingerprint).put("choice",choice).put("textValues",values);
            String script=host.asset("desktop-session.js")+"\n"+host.asset("desktop-chat.js").replace("__REQUEST__",req.toString());
            AtomicBoolean acknowledged=new AtomicBoolean();
            ssh.run("/usr/bin/osascript -l JavaScript -",script,line->{JSONObject j=new JSONObject(line);if(j.optString("type").equals("desktop.action.done"))acknowledged.set(true);if(j.optString("type").equals("error"))throw new IllegalStateException(j.optString("text"));},cancelled,20000);
            if(!acknowledged.get())throw new IllegalStateException("电脑未确认操作结果，请在电脑检查，不会自动重试");
        });
    }
    static Agent chooseAccess(Inventory inventory,Agent agent,String mode){
        if(!agent.supported())throw new IllegalStateException(agent.name+" 已安装，但尚未接入对话协议");
        if(mode.equals("cli")&&agent.desktop())throw new IllegalStateException(agent.name+" 未检测到可用 CLI，请选择默认或桌面接入");
        if(!mode.equals("desktop"))return agent;
        Agent desktop=inventory.desktopAgent(agent.id);
        if(desktop==null)throw new IllegalStateException("电脑未检测到 "+agent.name+" 的可用桌面应用，请改用默认接入方式");
        return desktop;
    }
    static boolean canFallback(EventDecoder decoder,Exception e){
        if(decoder.workStarted)return false;
        String failure=message(e).toLowerCase(Locale.ROOT);
        if(failure.contains("状态未知")||failure.contains("超时")||failure.contains("timed out")||failure.contains("断开")||failure.contains("中断"))return false;
        String reason=(decoder.failed?decoder.text():message(e)).toLowerCase(Locale.ROOT);
        return reason.startsWith("acp missing_credential: missing_credential: llm-deepseek: no api key for provider route ")||failure.startsWith("远端退出码 1：dsh: missing_credential: llm-deepseek: no api key for provider route ")||reason.contains("authentication required")||reason.contains("not authenticated")||reason.contains("not logged in")||reason.contains("please use /login")||
            (failure.contains("退出码 127")&&(reason.contains("command not found")||reason.contains("no such file or directory")));
    }
    static String message(Exception e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    void cancel(){if(profile.optBoolean("taskService")){emit("notice","message","持久任务请从任务卡片停止；关闭手机不会中断电脑任务。");return;}cancelled.set(true);interactions.clear(null);ssh.cancel();}
    void close(){closed=true;reconnectProfile=null;reconnectKey=null;monitor.shutdownNow();cancel();ssh.close();control.shutdownNow();actions.shutdownNow();}
}
