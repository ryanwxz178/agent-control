package dev.orca.pocket;

import org.json.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class EngineSmoke {
    public static void main(String[] args)throws Exception{
        String root=args[0];Path keys=Path.of(root,".toolchain/ssh-test");
        byte[] blob=Base64.getDecoder().decode(Files.readString(keys.resolve("host.pub")).split(" ")[1]);
        String pinned="SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(blob));
        CountDownLatch connected=new CountDownLatch(1),finished=new CountDownLatch(1);AtomicBoolean sending=new AtomicBoolean(),matched=new AtomicBoolean();
        List<String> errors=new CopyOnWriteArrayList<>();
        PocketEngine engine=new PocketEngine(new PocketEngine.Host(){
            public String asset(String name)throws Exception{return Files.readString(Path.of(root,"android-app/app/src/main/assets",name));}
            public boolean trust(String host,String fp){return pinned.equals(fp);}
            public void event(String type,JSONObject data){
                if(type.equals("connected")){System.out.println("Detected agents: "+data.optJSONArray("agents").length());connected.countDown();}
                if(type.equals("error"))errors.add(data.optString("message"));
                if(type.equals("update")&&data.optString("status").equals("已完成")&&data.optString("text").contains("ORCA_POCKET_SSH_OK"))matched.set(true);
                if(type.equals("busy")&&!data.optBoolean("value")&&sending.get())finished.countDown();
            }
        });
        try{
            JSONObject profile=new JSONObject().put("host","127.0.0.1").put("port",22992).put("user",System.getProperty("user.name")).put("directory",root);
            engine.connect(profile,Files.readAllBytes(keys.resolve("client")));
            if(!connected.await(40,TimeUnit.SECONDS))throw new AssertionError("connect: "+errors);
            while(engine.busy.get())Thread.sleep(20);
            sending.set(true);
            engine.send(new JSONObject().put("agent","codex").put("directory",root).put("prompt","不要调用任何工具，不要读取文件，只回复 ORCA_POCKET_SSH_OK。这是 SSH 连接验证。"));
            if(!finished.await(90,TimeUnit.SECONDS))throw new AssertionError("task timeout");
            if(!matched.get()||!errors.isEmpty())throw new AssertionError("no verified result: "+errors);
            System.out.println("PASS real SSH -> detect agents -> Codex CLI -> JSON events -> chat engine completed with ORCA_POCKET_SSH_OK");
        }finally{engine.close();}
    }
}
