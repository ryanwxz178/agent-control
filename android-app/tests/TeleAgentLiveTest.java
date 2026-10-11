package dev.orca.pocket;

import org.json.JSONObject;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in live test: production engine, SSH, desktop protocol and a real TeleAgent desktop reply. */
public final class TeleAgentLiveTest {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath();
        String pin=args[1];
        boolean greeting=args.length>2&&args[2].equals("greeting");
        boolean switchView=args.length>2&&args[2].equals("switch");
        boolean workbuddy=args.length>2&&args[2].equals("workbuddy");
        java.util.concurrent.atomic.AtomicBoolean switched=new java.util.concurrent.atomic.AtomicBoolean();
        CountDownLatch connected=new CountDownLatch(1),done=new CountDownLatch(1);
        AtomicReference<String> reply=new AtomicReference<>(""),error=new AtomicReference<>("");
        PocketEngine engine=new PocketEngine(new PocketEngine.Host(){
            public String asset(String name)throws Exception{return Files.readString(root.resolve("android-app/app/src/main/assets").resolve(name));}
            public boolean trust(String host,String fingerprint){return pin.equals(fingerprint);}
            public void event(String type,JSONObject data){
                if(type.equals("connected"))connected.countDown();
                if(type.equals("update"))System.out.println("LIVE status: "+data.optString("status"));
                if(switchView&&type.equals("update")&&data.optString("status").startsWith("正在读取本次请求")&&switched.compareAndSet(false,true)){
                    try{
                        String shared=Files.readString(root.resolve("android-app/app/src/main/assets/desktop-chat.js"));shared=shared.substring(0,shared.indexOf("if(!bundles[request.app])")).replace("__REQUEST__","{app:'TeleAgent'}");
                        String click=shared+"\nvar running=$.NSRunningApplication.runningApplicationsWithBundleIdentifier($(bundles[request.app]));var application=running.objectAtIndex(0),root=$.AXUIElementCreateApplication(application.processIdentifier);var nodes=snapshot(root),fresh=nodes.filter(freshEntry);if(fresh.length!==1)throw Error('No unique new task');$.AXUIElementPerformAction(fresh[0].raw,$('AXPress'));delay(0.5);if(!teleagentBlank(snapshot(root)))throw Error('PC switch did not enter blank session');console.log('PC view switched to another blank session');";
                        Path script=Files.createTempFile("agent-control-switch-own-test-",".js");Files.writeString(script,click);
                        Process pc=new ProcessBuilder("/usr/bin/osascript","-l","JavaScript",script.toString()).inheritIO().start();
                        if(!pc.waitFor(20,TimeUnit.SECONDS)||pc.exitValue()!=0){pc.destroy();throw new Exception("PC switch verification failed");}
                    }catch(Exception e){error.set(e.getMessage());done.countDown();}
                }
                if(type.equals("error")){error.set(data.optString("message"));connected.countDown();done.countDown();}
                if(type.equals("update")&&data.optString("status").contains("失败")){error.set(data.optString("text")+" "+data.optString("status"));done.countDown();}
                if(type.equals("update")&&data.optString("status").equals("已完成")){reply.set(data.optString("text"));done.countDown();}
            }
        });
        try {
            engine.connect(new JSONObject().put("host","127.0.0.1").put("port",22992).put("user",System.getProperty("user.name")).put("password","").put("directory",root.resolve(".toolchain/teleagent-managed-inspect").toString()),Files.readAllBytes(root.resolve(".toolchain/ssh-test/client")));
            if(!connected.await(45,TimeUnit.SECONDS)||!error.get().isEmpty())throw new AssertionError("Connection failed: "+error.get());
            long idleDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(engine.busy.get()&&System.nanoTime()<idleDeadline)Thread.sleep(50);
            engine.send(new JSONObject().put("agent",workbuddy?"workbuddy":"teleagent").put("accessMode","desktop").put("write",false).put("prompt",greeting?"你好":"不要使用工具。请计算 33 × 73，简要解释，并在末尾写 ORCA-TELEAGENT-SSH-OK。"));
            if(!done.await(100,TimeUnit.SECONDS)||!error.get().isEmpty())throw new AssertionError("Task failed: "+error.get());
            String text=reply.get();
            if(switchView&&!switched.get())throw new AssertionError("No concurrent PC switch occurred");
            if(greeting?text.trim().isEmpty():(!text.replace(",","").contains("2409")||!text.contains("ORCA-TELEAGENT-SSH-OK")))throw new AssertionError("Unexpected actual reply: "+text);
            JSONObject evidence=new JSONObject().put("passed",true).put("pcViewSwitched",switched.get()).put("replySource","bound local session store").put("accessMode","desktop").put("desktopFallback",false).put("transport","Production PocketEngine -> JSch SSH -> macOS desktop adapter -> bound local session store").put("reply",text);
            Files.writeString(root.resolve(workbuddy?"android-app/test-output/workbuddy-store-live-051.json":switchView?"android-app/test-output/teleagent-pc-switch-051.json":greeting?"android-app/test-output/teleagent-greeting-ssh-verified.json":"android-app/test-output/teleagent-ssh-verified.json"),evidence.toString(2));
            System.out.println(evidence.toString(2));
        } finally {engine.close();}
    }
}
