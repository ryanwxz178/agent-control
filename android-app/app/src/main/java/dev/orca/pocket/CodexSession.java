package dev.orca.pocket;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** App-server stdio protocol, scoped to one phone turn and one SSH channel. */
final class CodexSession implements SshTransport.Duplex {
    private OutputStream input;
    private final String run,prompt,directory,host;
    private final boolean write;
    private final EventDecoder decoder;
    private final Interactions interactions;
    private final Attachments attachments;
    private final JSONObject config;
    private final boolean durable;
    private final Map<String,JSONObject> items=new HashMap<>();
    private final Map<String,String> deltas=new LinkedHashMap<>();
    private String thread="";
    private final Map<String,String> requests=new HashMap<>();
    CodexSession(String run,String prompt,String directory,boolean write,String host,EventDecoder decoder,Interactions interactions,Attachments attachments){
        this(run,prompt,directory,write,host,decoder,interactions,attachments,new JSONObject(),false);
    }
    CodexSession(String run,String prompt,String directory,boolean write,String host,EventDecoder decoder,Interactions interactions,Attachments attachments,JSONObject config,boolean durable){
        this.run=run;this.prompt=prompt;this.directory=directory;this.write=write;this.host=host;this.decoder=decoder;this.interactions=interactions;this.attachments=attachments;
        this.config=config;this.durable=durable;
    }
    synchronized void send(JSONObject value)throws Exception{input.write((value.toString()+"\n").getBytes(StandardCharsets.UTF_8));input.flush();}
    void request(int id,String method,JSONObject params)throws Exception{send(new JSONObject().put("id",id).put("method",method).put("params",params));}
    public void start(OutputStream out)throws Exception{
        input=out;request(1,"initialize",new JSONObject().put("clientInfo",new JSONObject().put("name","orca_pocket").put("version","0.3.0")).put("capabilities",new JSONObject().put("experimentalApi",true)));
    }
    public boolean accept(String line)throws Exception{
        if(!line.startsWith("{"))return false;
        JSONObject j=new JSONObject(line);String method=j.optString("method");JSONObject p=j.optJSONObject("params");
        if(method.isEmpty()&&j.has("id")){
            if(j.has("error"))throw new IOException("Codex 交互协议错误："+j.getJSONObject("error").optString("message"));
            if(j.optInt("id")==1){
                send(new JSONObject().put("method","initialized"));
                JSONObject params=new JSONObject().put("cwd",directory).put("approvalPolicy","on-request").put("approvalsReviewer","user").put("sandbox",write?"workspace-write":"read-only").put("ephemeral",!durable);if(config.length()>0)params.put("config",config);request(2,"thread/start",params);
            }else if(j.optInt("id")==2){
                thread=j.getJSONObject("result").getJSONObject("thread").getString("id");
                if(decoder.extra!=null)decoder.extra.accept(new JSONObject().put("type","agent.session").put("protocol","codex").put("sessionId",thread));
                request(3,"turn/start",new JSONObject().put("threadId",thread).put("input",new JSONArray().put(new JSONObject().put("type","text").put("text",prompt))));
            }
            return false;
        }
        if(p==null)return false;
        if(p.has("threadId")&&!thread.equals(p.optString("threadId")))return false;
        if(j.has("id")){handleRequest(j,p);return false;}
        if(method.equals("serverRequest/resolved")){String token=requests.remove(p.optString("requestId"));if(token!=null)interactions.resolve(token);}
        if(method.equals("item/started")||method.equals("item/completed")){
            JSONObject item=p.optJSONObject("item");if(item==null)return false;
            items.put(item.optString("id"),item);decoder.workStarted=true;
            if(method.equals("item/completed")){
                if(item.optString("type").equals("agentMessage"))message(item.optString("id"),item.optString("text"));
                attachments.content(run,item,host);
                JSONObject result=item.optJSONObject("result");if(result!=null){JSONArray content=result.optJSONArray("content");if(content!=null)for(int i=0;i<content.length();i++)if(content.optJSONObject(i)!=null)attachments.content(run,content.getJSONObject(i),host);}
            }
        }else if(method.equals("item/agentMessage/delta")){
            String id=p.getString("itemId"),value=deltas.getOrDefault(id,"")+p.optString("delta");deltas.put(id,value);message(id,value);
        }else if(method.equals("turn/completed")){
            JSONObject turn=p.getJSONObject("turn");String status=turn.optString("status");
            interactions.clear(run);
            if(!status.equals("completed"))throw new IOException("Codex 任务"+(status.equals("interrupted")?"已中断": "失败："+turn.opt("error")));
            decoder.accept("{\"type\":\"turn.completed\"}");return true;
        }
        return false;
    }
    private void message(String id,String text)throws Exception{
        decoder.accept(new JSONObject().put("type","item.updated").put("item",new JSONObject().put("type","agent_message").put("id",id).put("text",text)).toString());
    }
    private void handleRequest(JSONObject request,JSONObject p)throws Exception{
        String method=request.getString("method");Object id=request.get("id");decoder.workStarted=true;
        JSONObject card=new JSONObject().put("title","Codex 需要你的确认").put("detail",p.optString("reason",""));
        if(method.equals("item/commandExecution/requestApproval")||method.equals("item/fileChange/requestApproval")){
            String detail=p.optString("reason","");
            if(method.contains("commandExecution"))detail+="\n工作目录："+p.optString("cwd",directory)+"\n"+p.optString("command","");
            else {JSONObject item=items.get(p.optString("itemId"));detail+="\n"+(item==null?"修改内容未提供，请选择拒绝并到电脑查看":item.toString());}
            card.put("detail",detail).put("kind","approval").put("options",new JSONArray().put(new JSONObject().put("id","accept").put("label","仅允许这次")).put(new JSONObject().put("id","decline").put("label","拒绝")));
            requests.put(String.valueOf(id),interactions.add(run,card,answer->{String choice=answer.optString("choice");if(!choice.equals("accept")&&!choice.equals("decline"))throw new IllegalArgumentException("未知确认选项");send(new JSONObject().put("id",id).put("result",new JSONObject().put("decision",choice)));}));
        }else if(method.equals("item/tool/requestUserInput")){
            JSONArray questions=p.getJSONArray("questions");
            card.put("kind","questions").put("title","Codex 需要补充信息").put("questions",questions);
            requests.put(String.valueOf(id),interactions.add(run,card,answer->{
                JSONObject incoming=answer.getJSONObject("answers"),answers=new JSONObject();
                for(int i=0;i<questions.length();i++){JSONObject q=questions.getJSONObject(i);String value=incoming.getString(q.getString("id"));if(value.trim().isEmpty()||value.length()>10000)throw new IllegalArgumentException("请填写每个问题，单项最多 10000 字");answers.put(q.getString("id"),new JSONObject().put("answers",new JSONArray().put(value)));}
                send(new JSONObject().put("id",id).put("result",new JSONObject().put("answers",answers)));
            }));
        }else{
            send(new JSONObject().put("id",id).put("error",new JSONObject().put("code",-32601).put("message","手机尚不支持该交互，请在电脑处理："+method)));
            decoder.accept(new JSONObject().put("type","status").put("message","该交互暂需电脑处理："+method).toString());
        }
    }
    static String command(Agent agent,String directory,String os){
        StringBuilder command=new StringBuilder();
        if(os.equals("Windows")){
            command.append("Set-Location -LiteralPath ").append(Agent.psQuote(directory)).append("; & ");
            for(String s:agent.command)command.append(Agent.psQuote(s)).append(' ');
            command.append("app-server --listen stdio://");return Agent.powershell(command.toString());
        }
        command.append("cd -- ").append(Agent.quote(directory)).append(" && exec ");
        for(String s:agent.command)command.append(Agent.quote(s)).append(' ');
        return Agent.login(command+"app-server --listen stdio://");
    }
}
