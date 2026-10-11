package dev.orca.pocket;
import org.json.*;
import java.util.*;
import java.util.concurrent.*;

/** One-shot, process-local capabilities. Never restored from conversation history. */
final class Interactions {
    interface Reply { void send(JSONObject answer)throws Exception; }
    interface Events { void emit(String type,JSONObject data); }
    private static final class Pending {
        final String run; final long until; final Reply reply;
        Pending(String r,Reply p){run=r;reply=p;until=System.currentTimeMillis()+180000;}
    }
    private final Map<String,Pending> pending=new HashMap<>();
    private final Events events;
    Interactions(Events e){events=e;}
    synchronized String add(String run,JSONObject card,Reply reply)throws Exception{
        if(pending.size()>=12)throw new IllegalStateException("待确认操作过多，请先处理电脑请求");
        String token=UUID.randomUUID().toString();Pending p=new Pending(run,reply);pending.put(token,p);
        events.emit("interaction",card.put("id",run).put("token",token).put("expiresAt",p.until).put("state","pending"));return token;
    }
    void respond(String token,JSONObject answer)throws Exception{
        Pending p;
        synchronized(this){p=pending.remove(token);}
        if(p==null)throw new IllegalStateException("该确认已处理或已失效，不会重复操作");
        String status="已发送选择，等待电脑更新";
        try{if(System.currentTimeMillis()>p.until)throw new IllegalStateException("确认已过期，请在电脑查看当前状态");p.reply.send(answer);}
        catch(Exception e){status="未确认成功："+PocketEngine.message(e);throw e;}
        finally{events.emit("interactionState",new JSONObject().put("id",p.run).put("token",token).put("state","closed").put("message",status));}
    }
    synchronized void resolve(String token){
        Pending p=pending.remove(token);if(p==null)return;
        try{events.emit("interactionState",new JSONObject().put("id",p.run).put("token",token).put("state","closed").put("message","电脑已结束该请求"));}catch(Exception ignored){}
    }
    synchronized void clear(String run){
        Iterator<Map.Entry<String,Pending>> it=pending.entrySet().iterator();
        while(it.hasNext()){Map.Entry<String,Pending> e=it.next();if(run==null||run.equals(e.getValue().run)){
            try{events.emit("interactionState",new JSONObject().put("id",e.getValue().run).put("token",e.getKey()).put("state","closed").put("message","请求已结束或失效"));}catch(Exception ignored){}it.remove();}}
    }
}
