package dev.orca.pocket;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Standard ACP v1 over SSH stdio. No HTTP listener and no companion daemon. */
final class AcpSession implements SshTransport.Duplex {
    private OutputStream input;
    private final String run,prompt,directory,host,name;
    private final EventDecoder decoder;private final Interactions interactions;private final Attachments attachments;
    private String session="",text="";
    AcpSession(String run,String prompt,String directory,String host,String name,EventDecoder decoder,Interactions interactions,Attachments attachments){this.run=run;this.prompt=prompt;this.directory=directory;this.host=host;this.name=name;this.decoder=decoder;this.interactions=interactions;this.attachments=attachments;}
    synchronized void send(JSONObject json)throws Exception{input.write((json.toString()+"\n").getBytes(StandardCharsets.UTF_8));input.flush();}
    void request(int id,String method,JSONObject params)throws Exception{send(new JSONObject().put("jsonrpc","2.0").put("id",id).put("method",method).put("params",params));}
    public void start(OutputStream out)throws Exception{input=out;request(1,"initialize",new JSONObject().put("protocolVersion",1).put("clientInfo",new JSONObject().put("name","orca-pocket").put("version","0.3.0")).put("clientCapabilities",new JSONObject()));}
    public boolean accept(String line)throws Exception{
        if(!line.startsWith("{"))return false;JSONObject j=new JSONObject(line);String method=j.optString("method");JSONObject p=j.optJSONObject("params");
        if(method.isEmpty()&&j.has("id")){
            if(j.has("error")){
                JSONObject e=j.getJSONObject("error");String reason=e.optString("message");
                // Structured auth refusals before any work may use the established desktop fallback.
                if(!decoder.workStarted&&(e.optInt("code")==-32000&&reason.toLowerCase(Locale.ROOT).contains("auth")||reason.startsWith("MISSING_CREDENTIAL:")))decoder.accept(new JSONObject().put("type","error").put("message",reason.startsWith("MISSING_CREDENTIAL:")?"ACP MISSING_CREDENTIAL: "+reason:"Authentication required").toString());
                if(!decoder.workStarted&&e.optInt("code")==-32603&&reason.startsWith("Internal error: turn failed: llm-deepseek: no API key for provider route "))decoder.accept(new JSONObject().put("type","error").put("message","ACP MISSING_CREDENTIAL: MISSING_CREDENTIAL: "+reason.substring("Internal error: turn failed: ".length())).toString());
                throw new IOException(name+" ACP："+reason);
            }
            JSONObject result=j.optJSONObject("result");
            if(j.optInt("id")==1){if(result==null||result.optInt("protocolVersion")!=1)throw new IOException("该 Agent 不支持 ACP v1");request(2,"session/new",new JSONObject().put("cwd",directory).put("mcpServers",new JSONArray()));}
            else if(j.optInt("id")==2){session=j.getJSONObject("result").getString("sessionId");if(decoder.extra!=null)decoder.extra.accept(new JSONObject().put("type","agent.session").put("protocol","acp").put("sessionId",session));request(3,"session/prompt",new JSONObject().put("sessionId",session).put("prompt",new JSONArray().put(new JSONObject().put("type","text").put("text",prompt))));}
            else if(j.optInt("id")==3){interactions.clear(run);
                if(result!=null&&result.optString("stopReason").equals("refusal")&&!decoder.workStarted){JSONObject meta=result.optJSONObject("_meta");if(meta!=null){try{JSONObject err=new JSONObject(meta.optString("codebuddy.ai/errorMessage"));if(err.optInt("code")==-32000&&EventDecoder.authRefusal(err.optString("message")))decoder.accept("{\"type\":\"error\",\"message\":\"Authentication required\"}");}catch(JSONException ignored){}}}
if(result==null||!result.optString("stopReason").equals("end_turn"))throw new IOException(name+" 未正常完成："+(result==null?"无结果":result.optString("stopReason")));decoder.accept("{\"type\":\"turn.completed\"}");return true;}
            return false;
        }
        if(p==null)return false;
        if(method.startsWith("session/")&&(session.isEmpty()||!session.equals(p.optString("sessionId"))))return false;
        if(j.has("id")){
            Object id=j.get("id");
            if(method.equals("session/request_permission")){
                decoder.workStarted=true;JSONArray options=p.getJSONArray("options"),visible=new JSONArray();Set<String> allowed=new HashSet<>();
                for(int i=0;i<options.length();i++){JSONObject option=options.getJSONObject(i);String kind=option.optString("kind");if(kind.equals("allow_once")||kind.equals("reject_once")){String key=option.getString("optionId");allowed.add(key);visible.put(new JSONObject().put("id",key).put("label",option.getString("name")));}}
                JSONObject tool=p.optJSONObject("toolCall");String detail=tool==null?"电脑需要确认操作":tool.toString(2);
                if(visible.length()==0){send(new JSONObject().put("jsonrpc","2.0").put("id",id).put("result",new JSONObject().put("outcome",new JSONObject().put("outcome","cancelled"))));return false;}
                interactions.add(run,new JSONObject().put("kind","approval").put("title",name+" 等待确认").put("detail",detail).put("options",visible),answer->{String selected=answer.getString("choice");if(!allowed.contains(selected))throw new IllegalArgumentException("未知权限选项");send(new JSONObject().put("jsonrpc","2.0").put("id",id).put("result",new JSONObject().put("outcome",new JSONObject().put("outcome","selected").put("optionId",selected))));});
            }else send(new JSONObject().put("jsonrpc","2.0").put("id",id).put("error",new JSONObject().put("code",-32601).put("message","手机不支持这个 ACP 操作")));
            return false;
        }
        if(method.equals("session/update")){
            JSONObject update=p.getJSONObject("update");String kind=update.optString("sessionUpdate");
            if(kind.equals("agent_message_chunk")){JSONObject content=update.optJSONObject("content");if(content!=null){decoder.workStarted=true;if(content.optString("type").equals("text")){text+=content.optString("text");decoder.accept(new JSONObject().put("type","desktop.snapshot").put("text",text).toString());}attachments.content(run,content,host);}}
            if(kind.equals("tool_call")||kind.equals("tool_call_update")){decoder.workStarted=true;decoder.accept(new JSONObject().put("type","status").put("message",update.optString("title","正在处理电脑任务")).toString());boolean completed=update.optString("status").equals("completed");if(completed){attachments.generated(run,update.opt("rawOutput"),host);attachments.generated(run,update.opt("toolResult"),host);}JSONArray content=update.optJSONArray("content");if(content!=null)for(int i=0;i<content.length();i++){JSONObject part=content.optJSONObject(i);if(part!=null&&part.optJSONObject("content")!=null){JSONObject value=part.getJSONObject("content");attachments.content(run,value,host);if(completed&&value.optString("type").equals("text"))attachments.generated(run,value.optString("text"),host);}}}
        }
        return false;
    }
    static String command(Agent agent,String directory,boolean write,String os){
        List<String> args=new ArrayList<>(agent.command);
        if(agent.kind.equals("dsh"))args.add("acp");else{args.addAll(Arrays.asList("--acp","--permission-mode","default"));if(!write)args.addAll(Arrays.asList("--tools","Read,Glob,Grep"));}
        StringBuilder cmd=new StringBuilder(os.equals("Windows")?"Set-Location -LiteralPath "+Agent.psQuote(directory)+"; & ":"cd -- "+Agent.quote(directory)+" && exec ");
        for(String arg:args)cmd.append(os.equals("Windows")?Agent.psQuote(arg):Agent.quote(arg)).append(' ');
        return os.equals("Windows")?Agent.powershell(cmd.toString()):Agent.login(cmd.toString());
    }
}
