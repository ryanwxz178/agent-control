package dev.orca.pocket;
import org.json.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real engine routing, with a transport fixture; does not operate the user's desktop. */
public final class DesktopContinuityTest {
    public static void main(String[] args)throws Exception{
        Path assets=Path.of(args[0],"android-app/app/src/main/assets");
        List<JSONObject> sent=new ArrayList<>();
        for(String conversation:List.of("phone-one","phone-one","phone-two")){
            CountDownLatch finished=new CountDownLatch(1);AtomicBoolean sending=new AtomicBoolean();List<String> errors=new CopyOnWriteArrayList<>();
            SshTransport transport=new SshTransport(){
                public boolean connected(){return true;}
                public int run(String command,String stdin,Line line,AtomicBoolean stop,long timeout)throws Exception{
                    if(command.startsWith("/usr/bin/osascript")){
                        int start=stdin.indexOf("var request=")+"var request=".length(),end=stdin.indexOf(";\nvar bundles",start);
                        sent.add(new JSONObject(stdin.substring(start,end)));
                        line.accept(new JSONObject().put("type","desktop.done").put("text","fixture completed").toString());
                    }else{for(String row:List.of("ORCA_OS|Darwin","ORCA_HOME|/tmp","ORCA_AGENT|doubao|desktop|Doubao","ORCA_DETECTION_DONE"))line.accept(row);}
                    return 0;
                }
            };
            PocketEngine engine=new PocketEngine(new PocketEngine.Host(){
                public String asset(String name)throws Exception{return Files.readString(assets.resolve(name));}
                public boolean trust(String h,String pin){return false;}
                public void event(String type,JSONObject data){if(type.equals("error"))errors.add(data.optString("message"));if(type.equals("busy")&&!data.optBoolean("value")&&sending.get())finished.countDown();}
            },transport);
            try{
                engine.local(new JSONObject().put("directory","/tmp"));sending.set(true);
                engine.send(new JSONObject().put("directory","/tmp").put("agent","doubao").put("conversationId",conversation).put("prompt","继续当前消息").put("history","前文不能在原生续聊时重复发送"));
                if(!finished.await(5,TimeUnit.SECONDS)||!errors.isEmpty())throw new AssertionError(errors);
            }finally{engine.close();}
        }
        assert sent.size()==3;
        Set<String> markers=new HashSet<>();
        for(int i=0;i<sent.size();i++){
            JSONObject request=sent.get(i);assert request.getString("conversationId").equals(i==2?"phone-two":"phone-one");
            String prompt=request.getString("prompt");assert prompt.startsWith("继续当前消息\n\n[Agent Control request ");assert !prompt.contains("前文");assert markers.add(prompt);
        }
        System.out.println("PASS actual PocketEngine sends stable mobile conversation ID across fresh executors, isolates new conversations, sends only the current native turn, and uses a fresh request marker each time.");
    }
}
