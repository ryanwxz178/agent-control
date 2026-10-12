package dev.orca.pocket;

import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Opt-in real model test, through the same PocketEngine and SSH transport as Android. */
public final class TeleAgentCliLiveTest {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath();
        String pin=args[1],user=args[2];
        byte[] key=args[3].equals("-")?null:Files.readAllBytes(Path.of(args[3]));
        String password="";
        if(key==null){
            Console console=System.console();
            if(console==null)throw new IOException("Use an interactive console for password input, or pass an SSH key path.");
            char[] secret=console.readPassword("SSH authentication: ");
            if(secret==null)throw new IOException("No SSH authentication supplied.");
            password=new String(secret);Arrays.fill(secret,'\0');
        }
        int port=args.length>4?Integer.parseInt(args[4]):22;
        String marker="TA_SSH_"+UUID.randomUUID().toString().replace("-","").substring(0,12).toUpperCase(Locale.ROOT);
        CountDownLatch connected=new CountDownLatch(1),done=new CountDownLatch(1);
        AtomicReference<String> reply=new AtomicReference<>(""),error=new AtomicReference<>(""),protocol=new AtomicReference<>("");
        AtomicBoolean found=new AtomicBoolean(),fallback=new AtomicBoolean();
        PocketEngine engine=new PocketEngine(new PocketEngine.Host(){
            public String asset(String name)throws Exception{return Files.readString(root.resolve("android-app/app/src/main/assets").resolve(name));}
            public boolean trust(String host,String fingerprint){return pin.equals(fingerprint);}
            public void event(String type,JSONObject data){
                if(type.equals("connected")){
                    JSONArray agents=data.getJSONArray("agents");
                    for(int i=0;i<agents.length();i++){
                        JSONObject agent=agents.getJSONObject(i);
                        if(agent.optString("id").equals("teleagent")&&agent.optString("kind").equals("teleagent"))found.set(true);
                    }
                    connected.countDown();
                }
                if(type.equals("agentSession"))protocol.set(data.optString("protocol"));
                if(type.equals("error")){error.set(data.optString("message"));connected.countDown();done.countDown();}
                if(type.equals("interaction")){error.set("Unexpected interaction in no-tool test");done.countDown();}
                if(type.equals("update")){
                    String status=data.optString("status");
                    System.out.println("TeleAgent status: "+status);
                    if(status.contains("切换桌面"))fallback.set(true);
                    if(status.startsWith("失败")){error.set(status);done.countDown();}
                    if(status.equals("已完成")){reply.set(data.optString("text"));done.countDown();}
                }
            }
        });
        try{
            engine.connect(new JSONObject().put("host","127.0.0.1").put("port",port).put("user",user)
                .put("password",password).put("taskService",false).put("directory",root.toString()),key);
            if(!connected.await(50,TimeUnit.SECONDS)||!error.get().isEmpty())throw new AssertionError("Connection failed: "+error.get());
            if(!found.get())throw new AssertionError("TeleAgent CLI missing from actual SSH inventory");
            long idle=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(engine.busy.get()&&System.nanoTime()<idle)Thread.sleep(50);
            engine.send(new JSONObject().put("agent","teleagent").put("accessMode","cli").put("write",false)
                .put("prompt","不要调用任何工具。计算 53 × 97，简要解释，并在末尾原样写 "+marker+"。"));
            if(!done.await(160,TimeUnit.SECONDS)||!error.get().isEmpty())throw new AssertionError("Task failed: "+error.get());
            idle=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(engine.busy.get()&&System.nanoTime()<idle)Thread.sleep(50);
            if(engine.busy.get()||!error.get().isEmpty())throw new AssertionError("Transport did not finish: "+error.get());
            String text=reply.get();
            if(fallback.get()||!protocol.get().equals("teleagent-http")||!text.replace(",","").contains("5141")||!text.contains(marker))
                throw new AssertionError("Unexpected actual CLI reply or protocol: "+text);
            JSONObject evidence=new JSONObject().put("passed",true).put("accessMode","cli").put("desktopFallback",false)
                .put("transport","Production PocketEngine -> JSch SSH/SFTP -> NDJSON bridge -> isolated TeleAgent HTTP backend")
                .put("protocol",protocol.get()).put("reply",text);
            Files.createDirectories(root.resolve("android-app/test-output"));
            Files.writeString(root.resolve("android-app/test-output/teleagent-cli-ssh-verified.json"),evidence.toString(2));
            System.out.println(evidence.toString(2));
        }finally{engine.close();}
    }
}
