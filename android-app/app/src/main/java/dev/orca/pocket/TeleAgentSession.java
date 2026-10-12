package dev.orca.pocket;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** NDJSON transport to the phone-owned TeleAgent HTTP bridge over SSH stdio. */
final class TeleAgentSession implements SshTransport.Duplex {
    private final String run,prompt,directory,host,name;
    private final boolean write;
    private final EventDecoder decoder;
    private final Interactions interactions;
    private final Attachments attachments;
    private OutputStream input;
    private String session="";
    private boolean ended;
    private final Map<String,Pending> requests=new LinkedHashMap<>();

    private static final class Pending {
        final String id,kind;
        final List<String> tokens=new ArrayList<>();
        JSONArray questions;
        final JSONArray answers=new JSONArray();
        int questionIndex;
        boolean responded,closed;
        Pending(String id,String kind){this.id=id;this.kind=kind;}
    }

    TeleAgentSession(String run,String prompt,String directory,String host,String name,boolean write,
                     EventDecoder decoder,Interactions interactions,Attachments attachments){
        this.run=run;this.prompt=prompt;this.directory=directory;this.host=host;this.name=name;
        this.write=write;this.decoder=decoder;this.interactions=interactions;this.attachments=attachments;
    }

    public synchronized void start(OutputStream out)throws Exception{
        if(input!=null)throw new IOException("TeleAgent 交互通道已启动");
        input=out;
        send(new JSONObject().put("op","start").put("directory",directory).put("prompt",prompt).put("write",write));
    }

    private synchronized void send(JSONObject value)throws Exception{
        if(input==null||ended)throw new IOException("TeleAgent 交互通道已结束");
        input.write((value.toString()+"\n").getBytes(StandardCharsets.UTF_8));
        input.flush();
    }

    public synchronized boolean accept(String line)throws Exception{
        if(ended)return false;
        if(!line.trim().startsWith("{"))return false;
        JSONObject event=new JSONObject(line);
        String type=event.optString("type");
        if(!type.startsWith("teleagent."))return false;
        String incoming=event.optString("sessionId");
        if(type.equals("teleagent.session")){
            if(incoming.isEmpty()||incoming.length()>512)throw new IOException("TeleAgent 未提供有效会话编号");
            if(session.isEmpty()){
                session=incoming;
                if(decoder.extra!=null)decoder.extra.accept(new JSONObject().put("type","agent.session")
                    .put("protocol","teleagent-http").put("sessionId",session));
            }
            return false;
        }
        if(type.equals("teleagent.error")){
            if(!incoming.isEmpty()&&!incoming.equals(session))return false;
            String message=event.optString("message","TeleAgent 执行失败");
            // Only a refusal known to precede submission may trigger desktop fallback.
            boolean beforeSubmit=!decoder.workStarted&&!event.optBoolean("submitted")&&
                (session.isEmpty()||event.has("submitted")&&!event.optBoolean("submitted"));
            // PocketEngine also inspects exception text for auth failures. Mark
            // attempted/uncertain submission so that path cannot replay a task.
            if(!beforeSubmit)decoder.workStarted=true;
            if(beforeSubmit&&authFailure(event,message))decoder.accept("{\"type\":\"error\",\"message\":\"Authentication required\"}");
            ended=true;closeAll();
            throw new IOException(name+"："+message);
        }
        if(session.isEmpty()||!session.equals(incoming))return false;
        if(type.equals("teleagent.snapshot")){
            String text=event.optString("text");
            if(!text.trim().isEmpty())decoder.workStarted=true;
            snapshot(text);
        }else if(type.equals("teleagent.tool")){
            decoder.workStarted=true;
            decoder.accept(new JSONObject().put("type","status").put("message",event.optString("title","正在处理电脑任务")).toString());
            if(event.optString("status").equals("completed")){
                attachments.generated(run,event.opt("output"),host);
                JSONArray content=event.optJSONArray("attachments");
                if(content!=null)for(int i=0;i<content.length();i++){
                    JSONObject item=content.optJSONObject(i);
                    if(item!=null){attachment(item);attachments.generated(run,item,host);}
                }
            }
        }else if(type.equals("teleagent.status")){
            decoder.accept(new JSONObject().put("type","status").put("message",event.optString("message","正在处理电脑任务")).toString());
        }else if(type.equals("teleagent.permission")){
            permission(event.getJSONObject("request"));
        }else if(type.equals("teleagent.question")){
            questions(event.getJSONObject("request"));
        }else if(type.equals("teleagent.interaction.closed")){
            Pending pending=requests.get(event.optString("requestId"));
            if(pending!=null){pending.closed=true;resolve(pending);}
        }else if(type.equals("teleagent.done")){
            for(Pending pending:requests.values())if(!pending.responded&&!pending.closed)
                throw new IOException("TeleAgent 仍有等待回答的请求，不能确认任务完成");
            String text=event.optString("text");
            if(text.trim().isEmpty())throw new IOException("TeleAgent 未返回完整回复");
            decoder.workStarted=true;snapshot(text);
            decoder.accept("{\"type\":\"turn.completed\"}");
            ended=true;closeAll();return true;
        }
        return false;
    }

    private static boolean authFailure(JSONObject event,String message){
        String code=event.optString("code").toLowerCase(Locale.ROOT);
        String reason=message.toLowerCase(Locale.ROOT);
        return EventDecoder.authRefusal(message)||code.startsWith("teleagent_auth_")||
            code.startsWith("teleagent_owner_token_")||code.startsWith("teleagent_storage_")||
            code.startsWith("teleagent_dpapi_")||code.contains("relogin")||
            reason.contains("authentication required")||reason.contains("local_auth_missing")||
            reason.contains("missing_credential");
    }

    private void snapshot(String text)throws Exception{
        decoder.accept(new JSONObject().put("type","desktop.snapshot").put("text",text).toString());
    }

    private void attachment(JSONObject item)throws Exception{
        attachments.content(run,item,host);
        // OpenCode tool results use FilePart rather than ACP resource_link.
        if(item.optString("type").equals("file")){
            String url=item.optString("url");
            if(url.startsWith("file:"))attachments.content(run,new JSONObject().put("type","resource_link").put("uri",url),host);
            else if(url.startsWith("data:image/")){
                int delimiter=url.indexOf(";base64,");
                if(delimiter>5)attachments.inline(run,url.substring(delimiter+8),url.substring(5,delimiter),host,item.optString("filename","TeleAgent 图片"));
            }
        }
    }

    private Pending begin(JSONObject request,String kind)throws Exception{
        String id=request.getString("id");
        if(id.isEmpty()||id.length()>512||id.matches("(?s).*[\\x00-\\x1f].*"))
            throw new IOException("TeleAgent 请求编号无效");
        Pending old=requests.get(id);
        if(old!=null){
            if(!old.kind.equals(kind))throw new IOException("TeleAgent 重用了不同类型的请求编号");
            return null;
        }
        if(requests.size()>=200)throw new IOException("TeleAgent 交互请求过多");
        decoder.workStarted=true;
        Pending pending=new Pending(id,kind);requests.put(id,pending);return pending;
    }

    private void permission(JSONObject request)throws Exception{
        Pending pending=begin(request,"permission");if(pending==null)return;
        String detail="操作："+request.optString("permission","未说明操作")+
            "\n范围："+String.valueOf(request.opt("patterns"));
        JSONObject metadata=request.optJSONObject("metadata");
        if(metadata!=null)detail+="\n"+metadata.toString(2);
        JSONObject card=new JSONObject().put("kind","approval").put("title",name+" 等待确认").put("detail",detail)
            .put("options",new JSONArray().put(option("once","仅允许这次")).put(option("reject","拒绝")));
        register(pending,card,answer->{
            synchronized(this){
                requirePending(pending);
                String choice=answer.optString("choice");
                if(!choice.equals("once")&&!choice.equals("reject"))throw new IllegalArgumentException("未知权限选项");
                send(new JSONObject().put("op","permission").put("sessionId",session)
                    .put("requestId",pending.id).put("reply",choice));
                pending.responded=true;
            }
        });
    }

    private void questions(JSONObject request)throws Exception{
        Pending pending=begin(request,"question");if(pending==null)return;
        JSONArray questions=request.getJSONArray("questions");
        if(questions.length()==0||questions.length()>100)throw new IOException("TeleAgent 问题列表无效");
        pending.questions=new JSONArray(questions.toString());
        // Validate every question before publishing the first card.
        for(int i=0;i<questions.length();i++)validateQuestion(questions.getJSONObject(i));
        showQuestion(pending);
    }

    private static void validateQuestion(JSONObject question)throws Exception{
        if(question.optString("question").trim().isEmpty())throw new IOException("TeleAgent 问题内容缺失");
        JSONArray options=question.optJSONArray("options");
        if(options!=null&&options.length()>100)throw new IOException("TeleAgent 问题选项过多");
        Set<String> labels=new HashSet<>();
        if(options!=null)for(int i=0;i<options.length();i++){
            String label=options.getJSONObject(i).getString("label");
            if(label.trim().isEmpty()||!labels.add(label))throw new IOException("TeleAgent 问题选项无法唯一识别");
        }
        if(question.has("multiple")&&!(question.get("multiple") instanceof Boolean))throw new IOException("TeleAgent 多选设置无效");
        if(question.has("custom")&&!(question.get("custom") instanceof Boolean))throw new IOException("TeleAgent 自定义回答设置无效");
        if((options==null||options.length()==0)&&!question.optBoolean("custom",true))throw new IOException("TeleAgent 未提供可回答的选项");
    }

    private void showQuestion(Pending pending)throws Exception{
        final int index=pending.questionIndex;
        JSONObject question=pending.questions.getJSONObject(index);
        JSONArray source=question.optJSONArray("options"),visible=new JSONArray();
        final Map<String,String> labels=new LinkedHashMap<>();
        String detail=question.getString("question");
        if(source!=null)for(int i=0;i<source.length();i++){
            JSONObject value=source.getJSONObject(i);String label=value.getString("label"),key="teleagent_option_"+i;
            labels.put(key,label);visible.put(option(key,label));
            if(!value.optString("description").isEmpty())detail+="\n"+label+"："+value.optString("description");
        }
        final boolean multiple=question.optBoolean("multiple",false),custom=question.optBoolean("custom",true);
        final String field="teleagent_answer_"+index;
        JSONArray inputs=new JSONArray();
        if(multiple||custom){
            String label=multiple?"多选回答：填写选项文字，用逗号分隔":"自定义回答";
            if(multiple){detail+="\n这是多选题。可点选一个选项，或在输入框填写多个选项，用逗号分隔。";
                if(!custom)detail+="只能填写上方已有选项。";}
            inputs.put(new JSONObject().put("id",field).put("label",label).put("required",true));
        }
        visible.put(option("teleagent_question_reject","取消本次问答"));
        String header=question.optString("header","需要补充信息");
        JSONObject card=new JSONObject().put("kind","approval").put("desktop",true)
            .put("title",name+" · "+header+"（"+(index+1)+"/"+pending.questions.length()+"）")
            .put("detail",detail).put("options",visible).put("inputs",inputs);
        register(pending,card,answer->{
            synchronized(this){
                requirePending(pending);
                if(index!=pending.questionIndex)throw new IllegalStateException("该问题已回答或已失效");
                String choice=answer.optString("choice");
                if(choice.equals("teleagent_question_reject")){
                    send(new JSONObject().put("op","question-reject").put("sessionId",session).put("requestId",pending.id));
                    pending.responded=true;resolve(pending);return;
                }
                JSONArray selected=new JSONArray();
                if(labels.containsKey(choice))selected.put(labels.get(choice));
                else if(choice.equals("__desktop_text__")&&(multiple||custom)){
                    JSONObject values=answer.optJSONObject("textValues");
                    String text=values==null?"":values.optString(field).trim();
                    if(text.isEmpty()||text.length()>10000)throw new IllegalArgumentException("请填写回答，最多 10000 字");
                    if(multiple){
                        Set<String> unique=new LinkedHashSet<>();
                        for(String value:text.split("[,，\\r\\n]",-1)){
                            String trimmed=value.trim();
                            if(trimmed.isEmpty())throw new IllegalArgumentException("多选回答中不能包含空选项");
                            if(!custom&&!labels.containsValue(trimmed))throw new IllegalArgumentException("多选回答必须使用给定选项文字");
                            if(!unique.add(trimmed))throw new IllegalArgumentException("请勿重复填写同一个选项");
                            selected.put(trimmed);
                        }
                    }else selected.put(text);
                }else throw new IllegalArgumentException("未知问答选项");
                if(selected.length()==0)throw new IllegalArgumentException("请回答当前问题");
                pending.answers.put(selected);pending.questionIndex++;
                if(pending.questionIndex==pending.questions.length()){
                    send(new JSONObject().put("op","question").put("sessionId",session).put("requestId",pending.id)
                        .put("answers",pending.answers));
                    pending.responded=true;
                }else showQuestion(pending);
            }
        });
    }

    private static JSONObject option(String id,String label)throws JSONException{
        return new JSONObject().put("id",id).put("label",label);
    }

    private void register(Pending pending,JSONObject card,Interactions.Reply reply)throws Exception{
        String token=interactions.add(run,card,answer->{
            try{reply.send(answer);}
            catch(IllegalArgumentException invalid){
                // A mistyped multi-select answer remains correctable on a fresh
                // one-shot card; it must never submit a guessed/default answer.
                synchronized(this){
                    if(!ended&&!pending.closed&&!pending.responded)
                        register(pending,new JSONObject(card.toString()),reply);
                }
                throw invalid;
            }
        });
        if(pending.responded||pending.closed||ended)interactions.resolve(token);
        else pending.tokens.add(token);
    }

    private void requirePending(Pending pending)throws IOException{
        if(ended||pending.closed||pending.responded||requests.get(pending.id)!=pending)
            throw new IOException("TeleAgent 该请求已处理或已失效");
    }

    private void resolve(Pending pending){
        for(String token:pending.tokens)interactions.resolve(token);
        pending.tokens.clear();
    }

    private void closeAll(){for(Pending pending:requests.values()){pending.closed=true;resolve(pending);}}
}
