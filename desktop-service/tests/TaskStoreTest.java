package dev.orca.pocket;
import java.nio.file.*;
import org.json.*;
public final class TaskStoreTest {
    public static void main(String[] args)throws Exception{
        Path directory=Files.createTempDirectory("agent-control-store-");TaskStore store=new TaskStore(directory);
        JSONObject input=new JSONObject().put("messageId","test_1").put("prompt","hello").put("conversationId","conversation_1");
        store.create(input);assert store.create(input).getString("status").equals("queued");assert store.list().length()==1;
        try{store.create(TaskStore.copy(input).put("prompt","changed"));throw new AssertionError("must reject id conflict");}catch(IllegalArgumentException expected){}
        store.event("test_1","run",new JSONObject().put("id","run_1"));
        long runTime=store.get("test_1").getJSONArray("events").getJSONObject(0).getLong("createdAt");assert runTime>0;
        for(int i=0;i<100;i++)store.event("test_1","update",new JSONObject().put("id","run_1").put("text","response "+i));
        assert store.get("test_1").getJSONArray("events").length()==2;
        store.event("test_1","interaction",new JSONObject().put("id","run_1").put("token","permission_1").put("state","pending"));
        JSONObject task=store.get("test_1").put("status","waiting_approval");store.save(task);
        TaskStore recovered=new TaskStore(directory);recovered.recover();assert recovered.get("test_1").getString("status").equals("unverifiable");
        assert recovered.get("test_1").getJSONArray("events").getJSONObject(0).getLong("createdAt")==runTime;
        assert recovered.get("test_1").getJSONArray("events").getJSONObject(2).getJSONObject("data").getString("state").equals("closed");
        try{store.get("../escape");throw new AssertionError("path traversal");}catch(IllegalArgumentException expected){}
        System.out.println("Task store: duplicate/conflict, durable projections, restart uncertainty, approval invalidation, path validation passed");
    }
}
