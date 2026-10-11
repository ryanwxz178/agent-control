package dev.orca.pocket;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** SSH client for the private, detached computer service. Polls state; never resubmits work. */
final class PersistentClient {
    private final PocketEngine.Host host;
    private final SshTransport ssh;
    private final Attachments attachments;
    private String computer="";
    private String java,jar,root,os;
    PersistentClient(PocketEngine.Host host,SshTransport ssh,Attachments attachments){this.host=host;this.ssh=ssh;this.attachments=attachments;}
    synchronized void prepare(Inventory inventory,JSONObject profile)throws Exception{
        os=inventory.os;root=inventory.home.replace('\\','/')+"/.agent-control/runtime";
        computer=profile.optString("host")+"|"+profile.optInt("port",22)+"|"+profile.optString("user");
        String explicit=profile.optString("serviceJava").trim();
        List<String> output=new ArrayList<>();
        if(os.equals("Windows")){
            String command=explicit.isEmpty()?"$j=(Get-Command java.exe -ErrorAction Stop).Source": "$j="+Agent.psQuote(explicit);
            ssh.run(Agent.powershell(command+"; New-Item -ItemType Directory -Force -Path "+Agent.psQuote(root)+" | Out-Null; Write-Output ('AGENT_JAVA|'+$j)"),"",output::add,new AtomicBoolean(),20000);
        }else{
            String directory=profile.optString("directory",inventory.home);
            String probe="mkdir -p -- "+Agent.quote(root)+" && chmod 700 "+Agent.quote(root)+" && "+unixJavaProbe(directory,inventory.home,explicit);
            ssh.run(Agent.login(probe),"",output::add,new AtomicBoolean(),20000);
        }
        java=null;for(String line:output)if(line.startsWith("AGENT_JAVA|"))java=line.substring(11);
        if(java==null||java.trim().isEmpty())throw new IllegalStateException("未找到 Java 17，无法启动持久任务服务");
        byte[] bytes=host.bytes("agent-control-service.jar");StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(Locale.ROOT,"%02x",b&255));
        jar=root+"/service-"+hash+".jar";
        output.clear();String check=os.equals("Windows")?Agent.powershell("if(Test-Path -LiteralPath "+Agent.psQuote(jar)+"){Write-Output 'PRESENT'}"):"if [ -f "+Agent.quote(jar)+" ]; then echo PRESENT; fi";
        ssh.run(check,"",output::add,new AtomicBoolean(),10000);if(!output.contains("PRESENT"))ssh.upload(jar,bytes);
        JSONObject current=snapshot();String runtime=current.optString("runtime").replace('\\','/');
        if(!runtime.isEmpty()&&!runtime.equals(jar)){
            JSONObject upgrade=request(new JSONObject().put("op","shutdownIfIdle"));
            if(!upgrade.optBoolean("deferred")){Thread.sleep(500);snapshot();}
            else host.event("notice",new JSONObject().put("message","电脑服务有更新；当前任务继续运行，结束后重新连接即可升级。"));
        }
    }
    /** Expand optional JDK globs in POSIX sh, regardless of the SSH user's login shell. */
    static String unixJavaProbe(String directory,String home,String explicit){
        String script="probe_java() { [ -x \"$1\" ] && \"$1\" -version >/dev/null 2>&1 || return 1; printf 'AGENT_JAVA|%s\\n' \"$1\"; }; ";
        if(!explicit.isEmpty())script+="probe_java "+Agent.quote(explicit)+" && exit 0; ";
        else script+="for p in \"${JAVA_HOME:-/nonexistent}/bin/java\" \"$(command -v java 2>/dev/null)\"; do probe_java \"$p\" && exit 0; done; "+
            "for base in "+Agent.quote(directory)+" "+Agent.quote(home)+"; do "+
            "while [ -n \"$base\" ]; do for p in \"$base\"/.toolchain/jdk*/Contents/Home/bin/java \"$base\"/.toolchain/jdk*/bin/java; do probe_java \"$p\" && exit 0; done; "+
            "[ \"$base\" = / ] && break; parent=$(dirname -- \"$base\"); [ \"$parent\" = \"$base\" ] && break; base=$parent; done; done; "+
            "for base in "+Agent.quote(home)+"/workspace "+Agent.quote(home)+"/projects; do "+
            "for project in \"$base\" \"$base\"/* \"$base\"/*/* \"$base\"/*/*/*; do "+
            "for p in \"$project\"/.toolchain/jdk*/Contents/Home/bin/java \"$project\"/.toolchain/jdk*/bin/java; do probe_java \"$p\" && exit 0; done; done; done; "+
            "if [ -x /usr/libexec/java_home ]; then p=$(/usr/libexec/java_home -v '17+' 2>/dev/null); [ -n \"$p\" ] && probe_java \"$p/bin/java\" && exit 0; fi; ";
        script+="echo '电脑端持久任务需要 Java 17，请在高级设置填写 Java 路径。' >&2; exit 1";
        return "/bin/sh -c "+Agent.quote(script);
    }
    synchronized JSONObject request(JSONObject value)throws Exception{
        if(jar==null)throw new IllegalStateException("持久任务服务未就绪，请重新连接电脑");
        String file=root+"/rpc-"+UUID.randomUUID()+".json";ssh.upload(file,value.toString().getBytes(StandardCharsets.UTF_8));
        List<String> lines=new ArrayList<>();
        String command=os.equals("Windows")?Agent.powershell("& "+Agent.psQuote(java)+" '-cp' "+Agent.psQuote(jar)+" 'dev.orca.pocket.TaskService' '--file' "+Agent.psQuote(file)+"; exit $LASTEXITCODE"):
            Agent.login("exec "+Agent.quote(java)+" -cp "+Agent.quote(jar)+" dev.orca.pocket.TaskService --file "+Agent.quote(file));
        ssh.run(command,"",lines::add,new AtomicBoolean(),60000);
        JSONObject response=null;for(String line:lines)if(line.startsWith("{"))response=new JSONObject(line);
        if(response==null)throw new IllegalStateException("未收到任务服务响应，提交状态待确认；请刷新，不要重发");
        if(response.has("error"))throw new IllegalStateException(response.getString("error"));return response;
    }
    JSONObject snapshot()throws Exception{
        JSONObject result=request(new JSONObject().put("op","snapshot"));JSONArray tasks=result.getJSONArray("tasks");
        for(int i=0;i<tasks.length();i++){JSONArray events=tasks.getJSONObject(i).getJSONArray("events");for(int j=0;j<events.length();j++){JSONObject e=events.getJSONObject(j);if(e.getString("type").equals("attachment"))attachments.restore(e.getJSONObject("data"),computer);}}
        host.event("serviceSnapshot",result);
        return result;
    }
    String taskFor(String field,String value)throws Exception{
        JSONArray tasks=request(new JSONObject().put("op","snapshot")).getJSONArray("tasks");
        for(int i=0;i<tasks.length();i++){JSONObject task=tasks.getJSONObject(i);JSONArray events=task.getJSONArray("events");for(int j=0;j<events.length();j++)if(events.getJSONObject(j).getJSONObject("data").optString(field).equals(value))return task.getString("id");}
        throw new IllegalStateException("找不到该电脑任务，操作不可从历史记录重放");
    }
}
