package dev.orca.pocket;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.json.*;

/** Offline ACP content negotiation and production upload routing; no WorkBuddy account or model request. */
public final class WorkBuddyFilesTest {
    static int passed;
    static final String DIRECTORY="D:/项目",PREFIX=DIRECTORY+"/.agent-control-input/12345678-1234-1234-1234-123456789abc-";
    static void check(boolean value,String name){if(!value)throw new AssertionError(name);passed++;System.out.println("PASS "+name);}
    static JSONObject file(String name,String mime,byte[] bytes)throws Exception{return new JSONObject().put("name",name).put("path",PREFIX+name).put("mime",mime).put("size",bytes.length);}
    static JSONObject response(int id,JSONObject result)throws Exception{return new JSONObject().put("jsonrpc","2.0").put("id",id).put("result",result);}
    static JSONObject lastRequest(ByteArrayOutputStream output)throws Exception{String[] lines=output.toString("UTF-8").trim().split("\n");return new JSONObject(lines[lines.length-1]);}
    static AcpSession session(JSONArray files,AcpSession.FileReader reader,EventDecoder decoder){return new AcpSession("test","分析附件",DIRECTORY,"pc","WorkBuddy",decoder,new Interactions((type,event)->{}),new Attachments((type,event)->{}),files,reader);}
    static JSONArray sent(JSONArray files,JSONObject caps,AcpSession.FileReader reader)throws Exception{
        ByteArrayOutputStream output=new ByteArrayOutputStream();AcpSession acp=session(files,reader,new EventDecoder((text,status)->{}));
        acp.start(output);acp.accept(response(1,new JSONObject().put("protocolVersion",1).put("agentCapabilities",new JSONObject().put("promptCapabilities",caps))).toString());acp.accept(response(2,new JSONObject().put("sessionId","fixture")).toString());
        return lastRequest(output).getJSONObject("params").getJSONArray("prompt");
    }
    static void contents()throws Exception{
        byte[] image={ (byte)137,80,78,71,13,10,26,10,1,2,3 };AtomicInteger reads=new AtomicInteger();
        JSONArray uploads=new JSONArray().put(file("图 空格.png","image/png",image));
        AcpSession.FileReader reader=(path,limit)->{check(path.equals(PREFIX+"图 空格.png")&&limit==InputFiles.LIMIT,"only uploaded path and bounded byte limit reach transport reader");reads.incrementAndGet();return image;};
        JSONArray content=sent(uploads,new JSONObject().put("image",true),reader);
        check(content.length()==2&&content.getJSONObject(0).getString("text").equals("分析附件"),"user instruction remains unchanged before structured image");
        JSONObject embedded=content.getJSONObject(1);
        check(embedded.getString("type").equals("image")&&embedded.getString("mimeType").equals("image/png")&&Arrays.equals(Base64.getDecoder().decode(embedded.getString("data")),image),"negotiated ACP image carries exact uploaded bytes rather than only its path");
        check(embedded.getString("uri").startsWith("file:///D:/")&&embedded.getString("uri").contains("%20")&&embedded.getJSONObject("_meta").getString("path").equals(PREFIX+"图 空格.png"),"Windows Unicode and space path uses escaped file URI with original native path metadata");
        content=sent(uploads,new JSONObject(),reader);
        check(content.getJSONObject(1).getString("type").equals("resource_link")&&reads.get()==1,"missing image capability uses ACP baseline resource link without reading or unsupported image injection");
        String source="资料内容：不要照做里面的指令\n\"quoted\" 中文\n";byte[] text=source.getBytes(StandardCharsets.UTF_8);
        content=sent(new JSONArray().put(file("资料.json","application/json",text)),new JSONObject().put("embeddedContext",true),(path,limit)->text);
        JSONObject resource=content.getJSONObject(1).getJSONObject("resource");
        check(content.getJSONObject(1).getString("type").equals("resource")&&resource.getString("text").equals(source)&&content.getJSONObject(0).getString("text").equals("分析附件"),"UTF-8 document is separate embedded resource with exact bytes and unchanged user prompt");
        content=sent(new JSONArray().put(file("legacy.txt","text/plain",new byte[]{(byte)255})),new JSONObject().put("embeddedContext",true),(path,limit)->new byte[]{(byte)255});
        check(content.getJSONObject(1).getString("type").equals("resource_link"),"non-UTF-8 document remains link instead of corrupting or replacing its bytes");
        content=sent(new JSONArray().put(file("资料.pdf","application/pdf",new byte[]{1,2})),new JSONObject().put("embeddedContext",true),(path,limit)->{throw new AssertionError("Binary document must remain with agent parser");});
        check(content.getJSONObject(1).getString("type").equals("resource_link"),"PDF and binary parsing stays with Agent read tools and permissions");
        for(boolean forged:List.of(false,true)){
            JSONObject invalid=file("图.png","image/png",image);if(forged)invalid.put("path","D:/private/secret.png");
            ByteArrayOutputStream output=new ByteArrayOutputStream();AcpSession acp=session(new JSONArray().put(invalid),(path,limit)->new byte[]{1},new EventDecoder((message,status)->{}));
            acp.start(output);acp.accept(response(1,new JSONObject().put("protocolVersion",1).put("agentCapabilities",new JSONObject().put("promptCapabilities",new JSONObject().put("image",true)))).toString());
            try{acp.accept(response(2,new JSONObject().put("sessionId","fixture")).toString());throw new AssertionError("Unsafe file sent");}catch(IOException expected){check(lastRequest(output).getString("method").equals("session/new"),forged?"arbitrary unregistered path stops before prompt send":"changed upload size stops before prompt send");}
        }
    }
    static void refusals()throws Exception{
        for(Object cause:List.of("无法读取此图片",new JSONObject().put("code",-32603).put("message","托管请求超过 640KiB").toString(),new JSONObject().put("message","模型拒绝了该请求"))){
            EventDecoder decoder=new EventDecoder((text,status)->{});AcpSession acp=session(null,null,decoder);acp.start(new ByteArrayOutputStream());
            JSONObject result=new JSONObject().put("stopReason","refusal").put("_meta",new JSONObject().put("codebuddy.ai/errorMessage",cause));
            try{acp.accept(response(3,result).toString());throw new AssertionError("Refusal falsely completed");}catch(IOException error){check(error.getMessage().contains("refusal")&&error.getMessage().contains(cause instanceof JSONObject?"模型拒绝":cause.toString().startsWith("{")?"640KiB":"无法读取"),"refusal preserves specific upstream cause");check(!decoder.completed&&!PocketEngine.canFallback(decoder,error),"refusal remains unsuccessful and cannot automatically replay into desktop");}
        }
        EventDecoder unclassified=new EventDecoder((text,status)->{});AcpSession quoted=session(null,null,unclassified);quoted.start(new ByteArrayOutputStream());
        try{quoted.accept(response(3,new JSONObject().put("stopReason","refusal").put("_meta",new JSONObject().put("codebuddy.ai/errorMessage","Denied request containing Authentication required quoted text"))).toString());throw new AssertionError();}catch(IOException error){check(!PocketEngine.canFallback(unclassified,error),"unclassified refusal text mentioning authentication cannot be mistaken for startup authentication or replayed");}
        EventDecoder decoder=new EventDecoder((text,status)->{});AcpSession acp=session(null,null,decoder);acp.start(new ByteArrayOutputStream());
        JSONObject refusal=new JSONObject().put("stopReason","refusal").put("_meta",new JSONObject().put("codebuddy.ai/errorMessage",new JSONObject().put("code",-32000).put("message","Authentication required").toString()));
        try{acp.accept(response(3,refusal).toString());throw new AssertionError();}catch(IOException error){check(PocketEngine.canFallback(decoder,error),"exact pre-work authentication error keeps established authentication classification");}
        decoder=new EventDecoder((text,status)->{});acp=session(null,null,decoder);acp.start(new ByteArrayOutputStream());
        try{acp.accept(response(3,new JSONObject().put("stopReason","refusal").put("_meta",new JSONObject().put("codebuddy.ai/errorMessage","x".repeat(2500)))).toString());throw new AssertionError();}catch(IOException error){check(error.getMessage().length()<2100,"upstream diagnostic is bounded without serializing arbitrary full metadata");}
    }
    static void previews()throws Exception{
        // Fixture tests budgets and byte ownership. Real image quality belongs to platform preview tests.
        byte[] original=new byte[763*1024];original[0]=(byte)137;original[1]=80;original[2]=78;original[3]=71;
        JSONArray selected=new JSONArray();for(int i=0;i<4;i++)selected.put(file("large"+i+".png","image/png",original));
        List<Integer> limits=new ArrayList<>();List<String> statuses=new ArrayList<>();AtomicInteger reads=new AtomicInteger();
        EventDecoder decoder=new EventDecoder((text,status)->statuses.add(status));ByteArrayOutputStream output=new ByteArrayOutputStream();
        AcpSession acp=new AcpSession("preview","分析附件",DIRECTORY,"pc","WorkBuddy",decoder,new Interactions((type,event)->{}),new Attachments((type,event)->{}),selected,(path,limit)->{reads.incrementAndGet();return original;},(bytes,limit)->{check(bytes==original,"preview receives original bytes without changing uploaded storage");limits.add(limit);byte[] jpeg=new byte[limit];jpeg[0]=(byte)255;jpeg[1]=(byte)216;jpeg[2]=(byte)255;return jpeg;});
        acp.start(output);acp.accept(response(1,new JSONObject().put("protocolVersion",1).put("agentCapabilities",new JSONObject().put("promptCapabilities",new JSONObject().put("image",true)))).toString());acp.accept(response(2,new JSONObject().put("sessionId","fixture")).toString());
        JSONArray content=lastRequest(output).getJSONObject("params").getJSONArray("prompt");int sum=0;
        for(int i=1;i<content.length();i++){JSONObject part=content.getJSONObject(i);sum+=Base64.getDecoder().decode(part.getString("data")).length;check(part.getString("mimeType").equals("image/jpeg")&&part.getJSONObject("_meta").getString("path").endsWith(".png"),"preview has actual JPEG MIME while retaining original PNG reference");}
        check(reads.get()==4&&limits.equals(List.of(65536,65536,65536,65536))&&sum==AcpSession.IMAGE_BUDGET,"current and historical images share one 256KiB ACP image budget");
        check(statuses.stream().filter(s->s.equals("CLI 使用图片预览，原图仍保留在电脑")).count()==1,"preview disclosure reaches user exactly once without claiming original image was sent");
        check(original.length==763*1024&&(original[0]&255)==137&&output.size()<AcpSession.MAX_CONTENT_BYTES,"large original remains intact while resulting ACP packet fits bounded transport budget");
        for(int invalid:List.of(0,1,2)){
            ByteArrayOutputStream rejectedOutput=new ByteArrayOutputStream();AcpSession.ImagePreview bad=invalid==0?null:(bytes,limit)->new byte[invalid==1?limit+1:10];
            AcpSession rejected=new AcpSession("preview","分析附件",DIRECTORY,"pc","WorkBuddy",new EventDecoder((t,s)->{}),new Interactions((type,event)->{}),new Attachments((type,event)->{}),new JSONArray().put(file("large.png","image/png",original)),(path,limit)->original,bad);
            rejected.start(rejectedOutput);rejected.accept(response(1,new JSONObject().put("protocolVersion",1).put("agentCapabilities",new JSONObject().put("promptCapabilities",new JSONObject().put("image",true)))).toString());
            try{rejected.accept(response(2,new JSONObject().put("sessionId","fixture")).toString());throw new AssertionError("Invalid preview sent");}catch(IOException expected){check(lastRequest(rejectedOutput).getString("method").equals("session/new"),"missing, oversized or wrong-format preview stops before prompt send");}
        }
        byte[] largeText="x".repeat(AcpSession.MAX_CONTENT_BYTES).getBytes(StandardCharsets.UTF_8);ByteArrayOutputStream textOutput=new ByteArrayOutputStream();AcpSession text=session(new JSONArray().put(file("large.txt","text/plain",largeText)),(path,limit)->largeText,new EventDecoder((t,s)->{}));
        text.start(textOutput);text.accept(response(1,new JSONObject().put("protocolVersion",1).put("agentCapabilities",new JSONObject().put("promptCapabilities",new JSONObject().put("embeddedContext",true)))).toString());
        try{text.accept(response(2,new JSONObject().put("sessionId","fixture")).toString());throw new AssertionError("Oversized resource sent");}catch(IOException expected){check(expected.getMessage().contains("传输大小限制")&&lastRequest(textOutput).getString("method").equals("session/new"),"oversized embedded document gets actionable pre-send transport error without truncation");}
        ByteArrayOutputStream dshOutput=new ByteArrayOutputStream();AcpSession dsh=new AcpSession("dsh","分析附件",DIRECTORY,"pc","DeepSeek Harness",new EventDecoder((t,s)->{}),new Interactions((type,event)->{}),new Attachments((type,event)->{}),new JSONArray().put(file("large.png","image/png",original)).put(file("large.txt","text/plain",largeText)),(path,limit)->path.endsWith(".png")?original:largeText,(bytes,limit)->{throw new AssertionError("Other ACP Agent must not use WorkBuddy transport preview");});
        dsh.start(dshOutput);dsh.accept(response(1,new JSONObject().put("protocolVersion",1).put("agentCapabilities",new JSONObject().put("promptCapabilities",new JSONObject().put("image",true).put("embeddedContext",true)))).toString());dsh.accept(response(2,new JSONObject().put("sessionId","fixture")).toString());JSONArray dshContent=lastRequest(dshOutput).getJSONObject("params").getJSONArray("prompt");
        check(Arrays.equals(Base64.getDecoder().decode(dshContent.getJSONObject(1).getString("data")),original)&&dshContent.getJSONObject(1).getString("mimeType").equals("image/png")&&dshContent.getJSONObject(2).getJSONObject("resource").getString("text").length()==largeText.length,"other ACP Agents keep negotiated original images and complete text without WorkBuddy-specific budgets");
    }
    static void engine(Path assets)throws Exception{
        byte[] bytes={ (byte)137,80,78,71,13,10,26,10,6,7 };List<JSONObject> prompts=new CopyOnWriteArrayList<>();List<String> errors=new CopyOnWriteArrayList<>();AtomicInteger downloads=new AtomicInteger();AtomicReference<CountDownLatch> idle=new AtomicReference<>();
        SshTransport transport=new SshTransport(){
            public boolean connected(){return true;}
            public int run(String command,String stdin,Line line,AtomicBoolean stop,long timeout)throws Exception{for(String row:List.of("ORCA_OS|Windows","ORCA_HOME|"+DIRECTORY,"ORCA_AGENT|workbuddy|buddy|node|D:/app/resources/app.asar.unpacked/cli/bin/codebuddy","ORCA_APP|workbuddy|WorkBuddy","ORCA_DETECTION_DONE"))line.accept(row);return 0;}
            public byte[] download(String path,int limit){if(!path.equals(PREFIX+"fixture.png"))throw new AssertionError(path);downloads.incrementAndGet();return bytes;}
            public void interactiveWindowsScript(String script,Duplex protocol,AtomicBoolean stop,long timeout)throws Exception{
                if(!script.contains("WBIPC"))throw new AssertionError("Expected managed CLI adapter");ByteArrayOutputStream output=new ByteArrayOutputStream();protocol.start(output);
                protocol.accept(response(1,new JSONObject().put("protocolVersion",1).put("agentCapabilities",new JSONObject().put("promptCapabilities",new JSONObject().put("image",true)))).toString());protocol.accept(response(2,new JSONObject().put("sessionId","fixture")).toString());prompts.add(lastRequest(output));
                protocol.accept(new JSONObject().put("method","session/update").put("params",new JSONObject().put("sessionId","fixture").put("update",new JSONObject().put("sessionUpdate","agent_message_chunk").put("content",new JSONObject().put("type","text").put("text","offline attachment reply")))).toString());protocol.accept(response(3,new JSONObject().put("stopReason","end_turn")).toString());
            }
        };
        PocketEngine engine=new PocketEngine(new PocketEngine.Host(){public String asset(String name)throws Exception{return Files.readString(assets.resolve(name));}public boolean trust(String h,String p){return false;}public void event(String type,JSONObject data){if(type.equals("error"))errors.add(data.optString("message"));if(type.equals("busy")&&!data.optBoolean("value")&&idle.get()!=null)idle.get().countDown();}},transport);
        try{
            engine.local(new JSONObject().put("directory",DIRECTORY).put("host","pc").put("user","user"));InputFiles registry=new InputFiles();String id=registry.add(file("fixture.png","image/png",bytes).put("computer","pc|22|user").put("directory",DIRECTORY)).getString("id");
            JSONObject request=new JSONObject().put("agent","workbuddy").put("directory",DIRECTORY).put("accessMode","cli").put("prompt","仅描述图形").put("uploads",new JSONArray().put(id));registry.attach(request,"pc|22|user");idle.set(new CountDownLatch(1));engine.send(request);
            check(idle.get().await(5,TimeUnit.SECONDS)&&errors.isEmpty(),"production CLI engine completes negotiated offline attachment conversation");
            check(downloads.get()==1&&prompts.size()==1&&Arrays.equals(Base64.getDecoder().decode(prompts.get(0).getJSONObject("params").getJSONArray("prompt").getJSONObject(1).getString("data")),bytes),"opaque native upload registry reaches actual ACP image payload through engine and bounded transport reader");
        }finally{engine.close();}
    }
    static void largeHistory()throws Exception{
        // Reuse one 20MiB array so this realistic metadata case stays bounded in memory.
        byte[] historical=new byte[InputFiles.LIMIT],current=new byte[1024*1024];InputFiles registry=new InputFiles();List<String> ids=new ArrayList<>();
        for(int i=0;i<3;i++)ids.add(registry.add(file("history"+i+".png","image/png",i<2?historical:current).put("computer","pc|22|user").put("directory",DIRECTORY)).getString("id"));
        AtomicInteger previewCalls=new AtomicInteger();
        for(boolean followup:List.of(false,true)){
            JSONObject request=new JSONObject().put("prompt",followup?"继续分析":"分析新图").put("directory",DIRECTORY).put("accessMode","cli").put("uploads",followup?new JSONArray():new JSONArray().put(ids.get(2))).put("historyUploads",new JSONArray(followup?ids:ids.subList(0,2)));
            registry.attach(request,"pc|22|user");JSONArray uploads=request.getJSONArray("inputFiles");check(uploads.length()==3,"native registry permits two historical 20MiB images with a new 1MiB image and text-only follow-up");
            ByteArrayOutputStream output=new ByteArrayOutputStream();AcpSession acp=new AcpSession("history",request.getString("prompt"),DIRECTORY,"pc","WorkBuddy",new EventDecoder((t,s)->{}),new Interactions((type,event)->{}),new Attachments((type,event)->{}),uploads,(path,limit)->path.endsWith("history2.png")?current:historical,(bytes,limit)->{previewCalls.incrementAndGet();byte[] jpeg=new byte[limit];jpeg[0]=(byte)255;jpeg[1]=(byte)216;jpeg[2]=(byte)255;return jpeg;});
            acp.start(output);acp.accept(response(1,new JSONObject().put("protocolVersion",1).put("agentCapabilities",new JSONObject().put("promptCapabilities",new JSONObject().put("image",true)))).toString());acp.accept(response(2,new JSONObject().put("sessionId","fixture")).toString());
            JSONArray sent=lastRequest(output).getJSONObject("params").getJSONArray("prompt");int injected=0;for(int i=1;i<sent.length();i++)injected+=Base64.getDecoder().decode(sent.getJSONObject(i).getString("data")).length;
            check(sent.length()==4&&injected<=AcpSession.IMAGE_BUDGET&&output.size()<AcpSession.MAX_CONTENT_BYTES,"WorkBuddy budget counts injected previews instead of rejecting 41MiB source history");
        }
        check(previewCalls.get()==6&&historical.length==InputFiles.LIMIT&&current.length==1024*1024,"history and follow-up each process all selected images while original bytes remain unchanged");
        ByteArrayOutputStream otherOutput=new ByteArrayOutputStream();AcpSession other=new AcpSession("dsh","分析附件",DIRECTORY,"pc","DeepSeek Harness",new EventDecoder((t,s)->{}),new Interactions((type,event)->{}),new Attachments((type,event)->{}),new JSONArray().put(file("h0.png","image/png",historical)).put(file("h1.png","image/png",historical)).put(file("h2.png","image/png",current)),(path,limit)->path.endsWith("h2.png")?current:historical);
        other.start(otherOutput);other.accept(response(1,new JSONObject().put("protocolVersion",1).put("agentCapabilities",new JSONObject().put("promptCapabilities",new JSONObject().put("image",true)))).toString());
        try{other.accept(response(2,new JSONObject().put("sessionId","fixture")).toString());throw new AssertionError("Other ACP source budget unexpectedly removed");}catch(IOException expected){check(expected.getMessage().contains("40MB")&&lastRequest(otherOutput).getString("method").equals("session/new"),"other ACP Agents retain 40MiB original-byte limit before prompt send");}
    }
    public static void main(String[] args)throws Exception{contents();refusals();previews();largeHistory();engine(Path.of(args[0],"android-app/app/src/main/assets"));System.out.println("ALL "+passed+" WORKBUDDY FILE CHECKS PASSED");}
}
