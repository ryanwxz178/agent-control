package dev.orca.pocket;

import org.json.JSONObject;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in live test: production engine, SSH, stdio ACP and a real model reply. */
public final class WorkBuddyManagedLiveTest {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath();
        String pin=args[1];
        boolean permission=args.length>2&&args[2].equals("permission");
        AtomicReference<PocketEngine> active=new AtomicReference<>();java.util.concurrent.atomic.AtomicInteger approvals=new java.util.concurrent.atomic.AtomicInteger();
        CountDownLatch connected=new CountDownLatch(1),done=new CountDownLatch(1);
        AtomicReference<String> reply=new AtomicReference<>(""),error=new AtomicReference<>("");
        PocketEngine engine=new PocketEngine(new PocketEngine.Host(){
            public String asset(String name)throws Exception{return Files.readString(root.resolve("android-app/app/src/main/assets").resolve(name));}
            public boolean trust(String host,String fingerprint){return pin.equals(fingerprint);}
            public void event(String type,JSONObject data){
                if(type.equals("connected"))connected.countDown();
                if(type.equals("interaction")&&permission){
                    if(!data.optString("detail").contains("AGENT_CONTROL_CLI_PERMISSION_OK")){error.set("Unexpected permission request");done.countDown();return;}
                    org.json.JSONArray choices=data.optJSONArray("options");String choice="";
                    for(int i=0;i<choices.length();i++){JSONObject option=choices.getJSONObject(i);if(option.optString("label").toLowerCase().contains("allow")){choice=option.getString("id");break;}}
                    if(choice.isEmpty()){error.set("No allow_once option");done.countDown();return;}
                    approvals.incrementAndGet();active.get().respond(data.getString("token"),new JSONObject().put("choice",choice));
                }
                if(type.equals("update")){System.out.println("CLI status: "+data.optString("status"));if(data.optString("status").contains("失败")){error.set(data.optString("text"));done.countDown();}}
                if(type.equals("error")){error.set(data.optString("message"));connected.countDown();done.countDown();}
                if(type.equals("update")&&data.optString("status").equals("已完成")){reply.set(data.optString("text"));done.countDown();}
            }
        });
        active.set(engine);
        try {
            engine.connect(new JSONObject().put("host","127.0.0.1").put("port",22992).put("user",System.getProperty("user.name")).put("password","").put("directory",root.resolve(".toolchain/workbuddy-managed-inspect").toString()),Files.readAllBytes(root.resolve(".toolchain/ssh-test/client")));
            if(!connected.await(45,TimeUnit.SECONDS)||!error.get().isEmpty())throw new AssertionError("Connection failed: "+error.get());
            long idleDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            while(engine.busy.get()&&System.nanoTime()<idleDeadline)Thread.sleep(50);
            engine.send(new JSONObject().put("agent","workbuddy").put("accessMode","cli").put("write",permission).put("prompt",permission?"必须使用 Bash 工具执行一次只读命令 printf 'AGENT_CONTROL_CLI_PERMISSION_OK\\n'。不要修改文件。必须立即发起工具调用；如工具需要权限，使用协议权限请求，不要在普通文字里等待许可。执行成功后仅回复 AGENT_CONTROL_CLI_PERMISSION_OK。":"不要使用工具。请计算 326 × 41，简要解释，并在末尾写 ORCA-SSH-MANAGED-OK。"));
            if(!done.await(100,TimeUnit.SECONDS)||!error.get().isEmpty())throw new AssertionError("Task failed: "+error.get());
            String text=reply.get();
            if(permission&&(approvals.get()<1||!text.trim().equals("AGENT_CONTROL_CLI_PERMISSION_OK")))throw new AssertionError("Permission continuation was not actually completed: approvals="+approvals.get()+" reply="+text);
            if(permission?!text.contains("AGENT_CONTROL_CLI_PERMISSION_OK"):(!text.replace(",","").contains("13366")||!text.contains("ORCA-SSH-MANAGED-OK")))throw new AssertionError("Unexpected actual reply: "+text);
            JSONObject evidence=new JSONObject().put("passed",true).put("accessMode","cli").put("desktopFallback",false).put("transport","Production PocketEngine -> JSch SSH -> stdio ACP -> desktop-managed CLI").put("approvals",approvals.get()).put("reply",text);
            Files.writeString(root.resolve("android-app/test-output/workbuddy-managed-ssh-verified.json"),evidence.toString(2));
            System.out.println(evidence.toString(2));
        } finally {engine.close();}
    }
}
