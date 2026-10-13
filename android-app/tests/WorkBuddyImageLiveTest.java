package dev.orca.pocket;

import org.json.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Opt-in model test. Requires explicit approval to send the supplied geometry fixture. */
public final class WorkBuddyImageLiveTest {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath(),fixture=Path.of(args[1]).toAbsolutePath();
        String suffix=args.length>2?args[2]:"";
        if(!suffix.matches("(?:-[A-Za-z0-9_-]{1,60})?"))throw new IllegalArgumentException("Invalid evidence suffix");
        String prompt="仅说明附件中左右两种图形及其颜色，请简短回答";
        CountDownLatch done=new CountDownLatch(1);
        AtomicReference<String> reply=new AtomicReference<>(""),error=new AtomicReference<>(""),protocol=new AtomicReference<>("");
        AtomicBoolean desktopFallback=new AtomicBoolean();
        AtomicInteger previewBytes=new AtomicInteger();
        Path sizes=root.resolve("android-app/test-output/workbuddy-request-sizes"+suffix+".jsonl");
        Files.createDirectories(sizes.getParent());Files.deleteIfExists(sizes);
        PocketEngine engine=new PocketEngine(new PocketEngine.Host(){
            public String asset(String name)throws Exception{
                String source=Files.readString(root.resolve("android-app/app/src/main/assets").resolve(name));
                if(name.equals("workbuddy-windows-cli.js")){
                    // Test-only metadata: never save prompt, headers, image bytes or account credentials.
                    String metrics="fs.appendFileSync("+JSONObject.quote(sizes.toString())+",JSON.stringify({bytes:body.length})+'\\n');";
                    source=source.replace("const result=await rpc(channel+'/http.fetch'",metrics+"const result=await rpc(channel+'/http.fetch'");
                }
                return source;
            }
            public boolean trust(String host,String fingerprint){return false;}
            public byte[] previewImage(byte[] bytes,int maxBytes)throws Exception{byte[] preview=TaskService.previewImage(bytes,maxBytes);previewBytes.set(preview.length);return preview;}
            public void event(String type,JSONObject data){
                if(type.equals("agentSession"))protocol.set(data.optString("protocol"));
                if(type.equals("interaction")){error.set("Unexpected model permission request; test did not approve it");done.countDown();}
                if(type.equals("error")){error.set(data.optString("message"));done.countDown();}
                if(type.equals("update")){
                    String status=data.optString("status");System.out.println("WorkBuddy image status: "+status);
                    if(status.contains("切换桌面"))desktopFallback.set(true);
                    if(status.startsWith("失败")){error.set(status);done.countDown();}
                    if(status.equals("已完成")){reply.set(data.optString("text"));done.countDown();}
                }
            }
        },new LocalTransport());
        try {
            engine.local(new JSONObject().put("host","local").put("port",22).put("user",System.getProperty("user.name")).put("directory",root.toString()).put("taskService",false));
            InputFiles files=new InputFiles();
            JSONObject uploaded=new JSONObject().put("name",fixture.getFileName().toString()).put("path",fixture.toString()).put("size",Files.size(fixture)).put("mime","image/png").put("computer",engine.computerKey()).put("directory",root.toString());
            JSONObject publicFile=files.add(uploaded);
            JSONObject request=new JSONObject().put("agent","workbuddy").put("accessMode","cli").put("write",false).put("directory",root.toString()).put("prompt",prompt).put("uploads",new JSONArray().put(publicFile.getString("id")));
            files.attach(request,engine.computerKey());engine.send(request);
            if(!done.await(150,TimeUnit.SECONDS)||!error.get().isEmpty())throw new AssertionError("Approved image test did not complete: "+error.get());
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
            while(engine.busy.get()&&System.nanoTime()<deadline)Thread.sleep(50);
            String text=reply.get();
            if(engine.busy.get()||desktopFallback.get()||!protocol.get().equals("acp")||!text.contains("红")||!text.contains("蓝")||!text.contains("圆")||!(text.contains("方")||text.contains("正方形")))throw new AssertionError("Unexpected image reply or transport: "+text);
            JSONObject evidence=new JSONObject().put("passed",true).put("accessMode","cli").put("transport","Production InputFiles -> PocketEngine -> LocalTransport -> WorkBuddy WBIPC -> ACP native image").put("desktopFallback",false).put("protocol",protocol.get()).put("fixtureBytes",Files.size(fixture)).put("previewBytes",previewBytes.get()).put("prompt",prompt).put("reply",text);
            if(Files.exists(sizes)){
                JSONArray requests=new JSONArray();for(String row:Files.readAllLines(sizes)){JSONObject metric=new JSONObject(row);if(metric.getInt("bytes")>655360)throw new AssertionError("Actual model request exceeded WBIPC limit");requests.put(metric);}
                if(requests.length()==0)throw new AssertionError("No model request observed");evidence.put("actualModelRequests",requests);
            }
            Files.createDirectories(root.resolve("android-app/test-output"));Files.writeString(root.resolve("android-app/test-output/workbuddy-image-cli-live"+suffix+".json"),evidence.toString(2));System.out.println(evidence.toString(2));
        }finally{engine.close();}
    }
}
