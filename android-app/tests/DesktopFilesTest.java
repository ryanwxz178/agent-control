package dev.orca.pocket;

import org.json.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.*;

/** Tests actual engine routing and desktop payloads without operating a desktop. */
public final class DesktopFilesTest {
    public static void main(String[] args)throws Exception{
        Path assets=Path.of(args[0],"android-app/app/src/main/assets");
        for(String os:List.of("Windows","Darwin")){
            String directory=os.equals("Windows")?"D:/project":"/tmp/project";
            List<JSONObject> sent=new CopyOnWriteArrayList<>();List<String> errors=new CopyOnWriteArrayList<>();AtomicReference<CountDownLatch> finished=new AtomicReference<>();
            SshTransport transport=new SshTransport(){
                public boolean connected(){return true;}
                public int run(String command,String stdin,Line line,AtomicBoolean stop,long timeout)throws Exception{
                    if(command.startsWith("/usr/bin/osascript")){
                        int start=stdin.indexOf("var request=")+"var request=".length(),end=stdin.indexOf(";\nvar bundles",start);
                        sent.add(new JSONObject(stdin.substring(start,end)));line.accept("{\"type\":\"desktop.done\",\"text\":\"attached fixture\"}");
                    }else for(String row:List.of("ORCA_OS|"+os,"ORCA_HOME|"+directory,"ORCA_AGENT|workbuddy|buddy|cli-command","ORCA_APP|workbuddy|WorkBuddy","ORCA_DETECTION_DONE"))line.accept(row);
                    return 0;
                }
                public int runWindowsScript(String script,Line line,AtomicBoolean stop,long timeout)throws Exception{
                    Matcher encoded=Pattern.compile("FromBase64String\\('([A-Za-z0-9+/=]+)'\\)").matcher(script);
                    if(!encoded.find())throw new AssertionError("Missing Windows desktop request");
                    sent.add(new JSONObject(new String(Base64.getDecoder().decode(encoded.group(1)),StandardCharsets.UTF_8)));line.accept("{\"type\":\"desktop.done\",\"text\":\"attached fixture\"}");return 0;
                }
            };
            PocketEngine engine=new PocketEngine(new PocketEngine.Host(){
                public String asset(String name)throws Exception{return Files.readString(assets.resolve(name)).replace("\r\n","\n");}
                public boolean trust(String host,String pin){return false;}
                public void event(String type,JSONObject data){if(type.equals("error"))errors.add(data.optString("message"));if(type.equals("busy")&&!data.optBoolean("value")&&finished.get()!=null)finished.get().countDown();}
            },transport);
            try{
                engine.local(new JSONObject().put("directory",directory).put("host","pc").put("user","user"));
                InputFiles registry=new InputFiles();String path=directory+"/.agent-control-input/12345678-1234-1234-1234-123456789abc-report.pdf";
                String id=registry.add(new JSONObject().put("name","report.pdf").put("path",path).put("size",3).put("mime","application/pdf").put("directory",directory).put("computer","pc|22|user")).getString("id");
                JSONObject automatic=new JSONObject().put("agent","auto").put("directory",directory).put("accessMode","desktop").put("prompt","总结报告").put("uploads",new JSONArray().put(id));registry.attach(automatic,"pc|22|user");finished.set(new CountDownLatch(1));engine.send(automatic);assert finished.get().await(5,TimeUnit.SECONDS);assert sent.isEmpty();assert errors.size()==1&&errors.get(0).contains("选择一个 Agent");errors.clear();
                for(boolean withFile:List.of(true,false)){
                    JSONObject request=new JSONObject().put("agent","workbuddy").put("directory",directory).put("conversationId","same-phone-chat").put("accessMode","desktop").put("prompt",withFile?"总结报告":"继续分析").put("history","之前的文件对话").put("uploads",withFile?new JSONArray().put(id):new JSONArray()).put("historyUploads",new JSONArray().put(id));
                    registry.attach(request,"pc|22|user");finished.set(new CountDownLatch(1));engine.send(request);
                    if(!finished.get().await(5,TimeUnit.SECONDS)||!errors.isEmpty())throw new AssertionError(errors);
                }
                assert sent.size()==2;assert sent.get(0).getString("app").equals("WorkBuddy");assert sent.get(0).getJSONArray("files").length()==1;assert sent.get(0).getJSONArray("files").getJSONObject(0).getString("path").equals(path);
                assert sent.get(1).getJSONArray("files").length()==0;assert sent.stream().allMatch(r->r.optString("conversationId").equals("same-phone-chat"));
                assert sent.get(0).getString("prompt").startsWith("总结报告\n\n[Agent Control request ");assert !sent.get(0).getString("prompt").contains(path);assert !sent.get(1).getString("prompt").contains("之前的文件对话");
            }finally{engine.close();}
        }
        System.out.println("PASS Windows/macOS selected desktop routing, actual current-file payloads, plain desktop prompts, and follow-up without reattaching history.");
    }
}
