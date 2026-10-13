package dev.orca.pocket;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.*;

/** Real Windows pipes and production ACP: no WorkBuddy account, uploaded user file or model call. */
public final class WindowsDuplexBackpressureTest {
    public static void main(String[] args)throws Exception {
        if(!System.getProperty("os.name").startsWith("Windows")){System.out.println("SKIP Windows duplex backpressure");return;}
        String node=System.getenv("AGENT_CONTROL_TEST_NODE");if(node==null||node.isEmpty())node="C:/Program Files/nodejs/node.exe";
        for(int bytes:new int[]{1024,256*1024})run(node,bytes,args.length>0?Integer.parseInt(args[0]):10000);
    }
    private static void run(String node,int bytes,long timeout)throws Exception {
        // The agent is allowed to announce commands after session/new. It finishes writing
        // a large stdout notification before reading the following prompt, as a native CLI can.
        String source="const rl=require('readline').createInterface({input:process.stdin});const send=j=>process.stdout.write(JSON.stringify(j)+'\\n');"+
            "rl.on('line',line=>{const j=JSON.parse(line);if(j.method==='initialize')send({jsonrpc:'2.0',id:j.id,result:{protocolVersion:1,agentCapabilities:{promptCapabilities:{image:true}}}});"+
            "else if(j.method==='session/new'){rl.pause();send({jsonrpc:'2.0',id:j.id,result:{sessionId:'fixture'}});"+
            "process.stdout.write(JSON.stringify({jsonrpc:'2.0',method:'session/update',params:{sessionId:'fixture',update:{sessionUpdate:'available_commands_update',padding:'x'.repeat(512*1024)}}})+'\\n',()=>rl.resume());}"+
            "else if(j.method==='session/prompt'){const image=j.params.prompt.find(x=>x.type==='image');const data=Buffer.from(image.data,'base64');if(data.length!=="+bytes+"||data.some(x=>x!==11))throw Error('Fixture image integrity mismatch');"+
            "send({jsonrpc:'2.0',method:'session/update',params:{sessionId:'fixture',update:{sessionUpdate:'agent_message_chunk',content:{type:'text',text:'NATIVE_DUPLEX_OK'}}}});send({jsonrpc:'2.0',id:j.id,result:{stopReason:'end_turn'}});}});";
        byte[] image=new byte[bytes];Arrays.fill(image,(byte)11);
        JSONArray files=new JSONArray().put(new JSONObject().put("name","fixture.png").put("mime","image/png").put("size",image.length).put("path","D:/fixture/.agent-control-input/12345678-1234-1234-1234-123456789abc-fixture.png"));
        EventDecoder decoder=new EventDecoder((text,status)->{});
        // Exercise large pipe writes independently of WorkBuddy's smaller hosted image budget.
        AcpSession protocol=new AcpSession("backpressure","synthetic fixture only","D:/fixture","fixture-pc","DeepSeek Harness",decoder,new Interactions((type,event)->{}),new Attachments((type,event)->{}),files,(path,limit)->image);
        long start=System.nanoTime();
        try(LocalTransport transport=new LocalTransport()){
            transport.interactiveWindowsScript(Agent.windowsNodeScript(node,source,false),protocol,new AtomicBoolean(),timeout);
        }
        if(!decoder.completed||!decoder.text().equals("NATIVE_DUPLEX_OK"))throw new AssertionError("Prompt or completion event was lost");
        System.out.println("PASS native Windows ACP image="+bytes+" stdoutBurst=524288 elapsedMs="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
    }
}
