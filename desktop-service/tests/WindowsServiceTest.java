package dev.orca.pocket;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import org.json.*;

/** Real RPC JVMs use GBK deliberately; the protocol must still preserve UTF-8. */
public final class WindowsServiceTest {
    static JSONObject rpc(Path root,JSONObject request)throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin","java.exe").toString();
        Path file=Files.createTempFile(root,"rpc-",".json");Files.writeString(file,request.toString());
        Process client=new ProcessBuilder(java,"-Dfile.encoding=GBK","-Dagent.control.data="+root,"-cp",System.getProperty("java.class.path"),TaskService.class.getName(),"--file",file.toString()).start();
        if(!client.waitFor(55,TimeUnit.SECONDS)){client.destroyForcibly();throw new AssertionError("RPC timed out");}
        String output=new String(client.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        if(client.exitValue()!=0)throw new AssertionError(new String(client.getErrorStream().readAllBytes(),StandardCharsets.UTF_8));
        return new JSONObject(output);
    }
    public static void main(String[] args)throws Exception {
        if(!System.getProperty("os.name").startsWith("Windows"))return;
        Path root=Files.createTempDirectory("agent-control-service-中文-");
        TaskStore store=new TaskStore(root);
        JSONObject task=store.create(new JSONObject().put("messageId","encoding_test").put("conversationId","encoding_session").put("agent","teleagent").put("prompt","你好，测试中文与 emoji 🐳"));
        task.put("status","paused").put("error","电脑服务重启，状态待确认");store.save(task);
        try {
            JSONObject first=rpc(root,new JSONObject().put("op","snapshot"));
            JSONObject received=first.getJSONArray("tasks").getJSONObject(0);
            assert received.getJSONObject("request").getString("prompt").equals("你好，测试中文与 emoji 🐳"):received;
            assert received.getString("error").equals("电脑服务重启，状态待确认"):received;
            long pid=new JSONObject(Files.readString(root.resolve("heartbeat.json"))).getLong("pid");
            Thread.sleep(3500);
            rpc(root,new JSONObject().put("op","snapshot"));
            JSONObject heartbeat=new JSONObject(Files.readString(root.resolve("heartbeat.json")));
            assert heartbeat.getLong("pid")==pid:"Service restarted after client exited";
            assert System.currentTimeMillis()-heartbeat.getLong("time")<3000:"Service heartbeat stopped";
            JSONObject ended=rpc(root,new JSONObject().put("op","endConversation").put("conversationId","encoding_session"));
            assert ended.getBoolean("conversationEnded");
            JSONObject after=rpc(root,new JSONObject().put("op","snapshot"));
            assert after.getJSONArray("tasks").getJSONObject(0).getString("status").equals("cancelled");
            JSONObject rejected=rpc(root,new JSONObject().put("op","submit").put("request",new JSONObject().put("messageId","late_submission").put("conversationId","encoding_session").put("prompt","不应发送")));
            assert rejected.getString("error").equals("该会话已结束，请开始新对话");
            System.out.println("PASS Windows detached service survives RPC exit and preserves Chinese/emoji with a GBK client JVM");
            System.out.println("PASS ending a conversation cancels its queue and rejects late submissions");
        } finally { rpc(root,new JSONObject().put("op","shutdownIfIdle")); }
    }
}
