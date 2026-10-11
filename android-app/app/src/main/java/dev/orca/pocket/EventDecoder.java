package dev.orca.pocket;

import org.json.*;
import java.util.*;

public final class EventDecoder {
    public interface Sink { void update(String text,String status); }
    private final Sink sink;
    public interface Extra { void accept(JSONObject event)throws Exception; }
    public Extra extra=event->{};
    private final LinkedHashMap<String,String> messages=new LinkedHashMap<>();
    private String result="";
    public boolean failed=false, completed=false, workStarted=false;
    static boolean authRefusal(String text){
        String t=text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");
        return t.equals("authentication required")||t.equals("authentication required. please use /login command to sign in to your account");
    }
    public EventDecoder(Sink sink){this.sink=sink;}
    public String text(){if(!result.isEmpty())return result;return String.join("\n\n",messages.values());}
    public void accept(String line) throws Exception {
        if(!line.trim().startsWith("{"))return;
        JSONObject j;
        try{j=new JSONObject(line);}catch(JSONException e){return;}
        extra.accept(j);
        String type=j.optString("type");
        if(type.equals("stream_event")||type.equals("tool")||type.equals("tool_result")||type.startsWith("item."))workStarted=true;
        if(type.equals("item.completed")||type.equals("item.updated")){
            JSONObject item=j.optJSONObject("item");
            if(item!=null){
                String kind=item.optString("type");
                if(kind.equals("agent_message"))messages.put(item.optString("id","message"),item.optString("text"));
                else if(kind.equals("command_execution"))sink.update(text(),"正在执行电脑任务");
                else if(kind.equals("file_change"))sink.update(text(),"正在修改文件");
            }
        }else if(type.equals("assistant")){
            JSONObject msg=j.optJSONObject("message");
            if(msg!=null){
                JSONArray content=msg.optJSONArray("content");StringBuilder b=new StringBuilder();
                if(content!=null)for(int i=0;i<content.length();i++){
                    JSONObject part=content.optJSONObject(i);
                    if(part==null)continue;
                    if(!part.optString("type").equals("text")||(!part.optString("text").trim().isEmpty()&&!authRefusal(part.optString("text"))))workStarted=true;
                    if(part.optString("type").equals("text"))b.append(part.optString("text"));
                    if(part.optString("type").equals("tool_use"))sink.update(text(),"正在使用 "+part.optString("name"));
                }
                if(b.length()>0)messages.put(msg.optString("id",j.optString("uuid","message")),b.toString());
            }
        }else if(type.equals("result")){
            completed=true;failed=j.optBoolean("is_error");
            result=j.optString("result",text());
            if(failed && result.isEmpty())result=j.optJSONArray("errors")==null?"Agent 返回失败":j.optJSONArray("errors").toString();
        }else if(type.equals("turn.completed")){completed=true;}
        else if(type.equals("error")||type.equals("turn.failed")){
            failed=true;result=j.optString("message",j.optJSONObject("error")==null?"Agent 执行失败":j.optJSONObject("error").optString("message"));
        }else if(type.equals("desktop.snapshot")){result=j.optString("text");}
        else if(type.equals("desktop.done")){completed=true;result=j.optString("text");}
        else if(type.equals("status")){sink.update(text(),j.optString("message"));return;}
        if(text().length()>150000)throw new IllegalStateException("回复超过显示限制");
        sink.update(text(),failed?"执行失败":completed?"已完成":"正在回复");
    }
}
