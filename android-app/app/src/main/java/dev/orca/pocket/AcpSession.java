package dev.orca.pocket;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.net.URI;
import java.util.*;

/** Standard ACP v1 over SSH stdio. No HTTP listener and no companion daemon. */
final class AcpSession implements SshTransport.Duplex {
    static final class Refusal extends IOException {
        final boolean authentication;
        Refusal(String message,boolean authentication){super(message);this.authentication=authentication;}
    }
    private OutputStream input;
    private final String run,prompt,directory,host,name;
    private final EventDecoder decoder;private final Interactions interactions;private final Attachments attachments;
    interface FileReader { byte[] read(String path,int limit)throws Exception; }
    interface ImagePreview { byte[] preview(byte[] bytes,int maxBytes)throws Exception; }
    // WorkBuddy expands ACP content into its model request (including provider metadata).
    // Reserve room for that expansion, system instructions and tool schemas under WBIPC's 640KiB limit.
    static final int IMAGE_BUDGET=128*1024,MAX_CONTENT_BYTES=256*1024;
    private final JSONArray inputFiles;private final FileReader fileReader;
    private final ImagePreview imagePreview;
    private JSONObject promptCapabilities=new JSONObject();
    private String session="",text="";
    AcpSession(String run,String prompt,String directory,String host,String name,EventDecoder decoder,Interactions interactions,Attachments attachments){this(run,prompt,directory,host,name,decoder,interactions,attachments,null,null);}
    AcpSession(String run,String prompt,String directory,String host,String name,EventDecoder decoder,Interactions interactions,Attachments attachments,JSONArray inputFiles,FileReader fileReader){this(run,prompt,directory,host,name,decoder,interactions,attachments,inputFiles,fileReader,null);}
    AcpSession(String run,String prompt,String directory,String host,String name,EventDecoder decoder,Interactions interactions,Attachments attachments,JSONArray inputFiles,FileReader fileReader,ImagePreview imagePreview){this.run=run;this.prompt=prompt;this.directory=directory;this.host=host;this.name=name;this.decoder=decoder;this.interactions=interactions;this.attachments=attachments;this.inputFiles=inputFiles;this.fileReader=fileReader;this.imagePreview=imagePreview;}
    synchronized void send(JSONObject json)throws Exception{input.write((json.toString()+"\n").getBytes(StandardCharsets.UTF_8));input.flush();}
    void request(int id,String method,JSONObject params)throws Exception{
        JSONObject json=new JSONObject().put("jsonrpc","2.0").put("id",id).put("method",method).put("params",params);
        if(name.equals("WorkBuddy")&&method.equals("session/prompt")&&json.toString().getBytes(StandardCharsets.UTF_8).length>MAX_CONTENT_BYTES)
            throw new IOException("CLI 附件或对话内容超过托管传输大小限制，请减少附件或新建对话；未发送");
        send(json);
    }
    public void start(OutputStream out)throws Exception{input=out;request(1,"initialize",new JSONObject().put("protocolVersion",1).put("clientInfo",new JSONObject().put("name","orca-pocket").put("version","0.3.0")).put("clientCapabilities",new JSONObject()));}
    public boolean accept(String line)throws Exception{
        if(!line.startsWith("{"))return false;JSONObject j=new JSONObject(line);String method=j.optString("method");JSONObject p=j.optJSONObject("params");
        if(method.isEmpty()&&j.has("id")){
            if(j.has("error")){
                JSONObject e=j.getJSONObject("error");String reason=e.optString("message");
                // Structured auth refusals before any work may use the established desktop fallback.
                if(!decoder.workStarted&&(e.optInt("code")==-32000&&reason.toLowerCase(Locale.ROOT).contains("auth")||reason.startsWith("MISSING_CREDENTIAL:")))decoder.accept(new JSONObject().put("type","error").put("message",reason.startsWith("MISSING_CREDENTIAL:")?"ACP MISSING_CREDENTIAL: "+reason:"Authentication required").toString());
                if(!decoder.workStarted&&e.optInt("code")==-32603&&reason.startsWith("Internal error: turn failed: llm-deepseek: no API key for provider route "))decoder.accept(new JSONObject().put("type","error").put("message","ACP MISSING_CREDENTIAL: MISSING_CREDENTIAL: "+reason.substring("Internal error: turn failed: ".length())).toString());
                throw new IOException(name+" ACP："+reason);
            }
            JSONObject result=j.optJSONObject("result");
            if(j.optInt("id")==1){if(result==null||result.optInt("protocolVersion")!=1)throw new IOException("该 Agent 不支持 ACP v1");JSONObject capabilities=result.optJSONObject("agentCapabilities");JSONObject advertised=capabilities==null?null:capabilities.optJSONObject("promptCapabilities");promptCapabilities=advertised==null?new JSONObject():advertised;request(2,"session/new",new JSONObject().put("cwd",directory).put("mcpServers",new JSONArray()));}
            else if(j.optInt("id")==2){session=j.getJSONObject("result").getString("sessionId");if(decoder.extra!=null)decoder.extra.accept(new JSONObject().put("type","agent.session").put("protocol","acp").put("sessionId",session));request(3,"session/prompt",new JSONObject().put("sessionId",session).put("prompt",promptContent()));}
            else if(j.optInt("id")==3){interactions.clear(run);
                if(result!=null&&result.optString("stopReason").equals("refusal")&&!decoder.workStarted){JSONObject meta=result.optJSONObject("_meta");if(meta!=null){try{JSONObject err=new JSONObject(meta.optString("codebuddy.ai/errorMessage"));if(err.optInt("code")==-32000&&EventDecoder.authRefusal(err.optString("message")))decoder.accept("{\"type\":\"error\",\"message\":\"Authentication required\"}");}catch(JSONException ignored){}}}
                if(result==null||!result.optString("stopReason").equals("end_turn")){String failure=name+" 未正常完成："+(result==null?"无结果":result.optString("stopReason"))+failureDetail(result);if(result!=null&&result.optString("stopReason").equals("refusal"))throw new Refusal(failure,decoder.failed&&!decoder.workStarted&&EventDecoder.authRefusal(decoder.text()));throw new IOException(failure);}decoder.accept("{\"type\":\"turn.completed\"}");return true;}
            return false;
        }
        if(p==null)return false;
        if(method.startsWith("session/")&&(session.isEmpty()||!session.equals(p.optString("sessionId"))))return false;
        if(j.has("id")){
            Object id=j.get("id");
            if(method.equals("session/request_permission")){
                decoder.workStarted=true;JSONArray options=p.getJSONArray("options"),visible=new JSONArray();Set<String> allowed=new HashSet<>();
                for(int i=0;i<options.length();i++){JSONObject option=options.getJSONObject(i);String kind=option.optString("kind");if(kind.equals("allow_once")||kind.equals("reject_once")){String key=option.getString("optionId");allowed.add(key);visible.put(new JSONObject().put("id",key).put("label",option.getString("name")));}}
                JSONObject tool=p.optJSONObject("toolCall");String detail=tool==null?"电脑需要确认操作":tool.toString(2);
                if(visible.length()==0){send(new JSONObject().put("jsonrpc","2.0").put("id",id).put("result",new JSONObject().put("outcome",new JSONObject().put("outcome","cancelled"))));return false;}
                interactions.add(run,new JSONObject().put("kind","approval").put("title",name+" 等待确认").put("detail",detail).put("options",visible),answer->{String selected=answer.getString("choice");if(!allowed.contains(selected))throw new IllegalArgumentException("未知权限选项");send(new JSONObject().put("jsonrpc","2.0").put("id",id).put("result",new JSONObject().put("outcome",new JSONObject().put("outcome","selected").put("optionId",selected))));});
            }else send(new JSONObject().put("jsonrpc","2.0").put("id",id).put("error",new JSONObject().put("code",-32601).put("message","手机不支持这个 ACP 操作")));
            return false;
        }
        if(method.equals("session/update")){
            JSONObject update=p.getJSONObject("update");String kind=update.optString("sessionUpdate");
            if(kind.equals("agent_message_chunk")){JSONObject content=update.optJSONObject("content");if(content!=null){decoder.workStarted=true;if(content.optString("type").equals("text")){text+=content.optString("text");decoder.accept(new JSONObject().put("type","desktop.snapshot").put("text",text).toString());}attachments.content(run,content,host);}}
            if(kind.equals("tool_call")||kind.equals("tool_call_update")){decoder.workStarted=true;decoder.accept(new JSONObject().put("type","status").put("message",update.optString("title","正在处理电脑任务")).toString());boolean completed=update.optString("status").equals("completed");if(completed){attachments.generated(run,update.opt("rawOutput"),host);attachments.generated(run,update.opt("toolResult"),host);}JSONArray content=update.optJSONArray("content");if(content!=null)for(int i=0;i<content.length();i++){JSONObject part=content.optJSONObject(i);if(part!=null&&part.optJSONObject("content")!=null){JSONObject value=part.getJSONObject("content");attachments.content(run,value,host);if(completed&&value.optString("type").equals("text"))attachments.generated(run,value.optString("text"),host);}}}
        }
        return false;
    }
    /** Only native upload references are supplied by PocketEngine; no model-provided path is read. */
    private JSONArray promptContent()throws Exception{
        JSONArray content=new JSONArray().put(new JSONObject().put("type","text").put("text",prompt));
        if(inputFiles==null)return content;
        boolean workBuddyTransport=name.equals("WorkBuddy");
        int imageCount=0;
        if(promptCapabilities.optBoolean("image"))for(int i=0;i<inputFiles.length();i++)if(!imageMime(inputFiles.getJSONObject(i).getString("name")).isEmpty())imageCount++;
        int imageLimit=workBuddyTransport?IMAGE_BUDGET/Math.max(1,imageCount):InputFiles.LIMIT;boolean previewNotice=false;
        long embedded=0;
        for(int i=0;i<inputFiles.length();i++){
            JSONObject file=inputFiles.getJSONObject(i);String path=file.getString("path"),name=file.getString("name"),mime=file.optString("mime");
            String normalized=path.replace('\\','/');
            if(!normalized.matches("(?:[A-Za-z]:/|/).*/\\.agent-control-input/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[^/]+")||normalized.contains("/../")||normalized.contains("/./")||normalized.indexOf('\0')>=0)throw new IOException("CLI 附件路径不属于已上传目录，未发送");
            String uri=new URI("file","",normalized.startsWith("/")?normalized:"/"+normalized,null).toASCIIString();
            String extension=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
            String imageMime=imageMime(name);
            boolean image=!imageMime.isEmpty()&&promptCapabilities.optBoolean("image"),textFile=promptCapabilities.optBoolean("embeddedContext")&&(mime.startsWith("text/")||Arrays.asList("txt","md","json","jsonl","xml","yaml","yml","csv","tsv","log","js","ts","jsx","tsx","py","go","java","cpp","c","h","css","scss","html").contains(extension));
            long size=file.optLong("size",-1);
            if(size<0||size>InputFiles.LIMIT)throw new IOException("CLI 附件大小无效，未发送");
            // Binary document parsing stays with the Agent's own read tools and permissions.
            if((image||textFile)&&fileReader!=null){
                if(!workBuddyTransport){embedded+=size;if(embedded>InputFiles.TOTAL)throw new IOException("CLI 内嵌附件总大小超过 40MB，请减少本轮或历史附件；未发送");}
                byte[] bytes=fileReader.read(path,InputFiles.LIMIT);
                if(bytes==null||bytes.length!=size)throw new IOException("CLI 附件上传后已改变，请重新上传；未发送");
                if(image){
                    if(bytes.length>imageLimit){
                        if(imagePreview==null)throw new IOException("此运行环境无法生成 CLI 图片预览，请使用直接桌面或上传更小图片；未发送");
                        bytes=imagePreview.preview(bytes,imageLimit);
                        if(bytes==null||bytes.length>imageLimit||bytes.length<3||(bytes[0]&255)!=255||(bytes[1]&255)!=216||(bytes[2]&255)!=255)throw new IOException("CLI 图片预览无效或超过传输大小限制；原图仍保留在电脑，未发送");
                        imageMime="image/jpeg";
                        if(!previewNotice){decoder.accept(new JSONObject().put("type","status").put("message","CLI 使用图片预览，原图仍保留在电脑").toString());previewNotice=true;}
                    }
                    if(workBuddyTransport){embedded+=bytes.length;if(embedded>InputFiles.TOTAL)throw new IOException("CLI 内嵌附件总大小超过 40MB，请减少本轮或历史附件；未发送");}
                    content.put(new JSONObject().put("type","image").put("mimeType",imageMime).put("data",Base64.getEncoder().encodeToString(bytes)).put("uri",uri).put("_meta",new JSONObject().put("filename",name).put("path",path)));continue;
                }
                try{
                    String value=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                    if(workBuddyTransport){embedded+=bytes.length;if(embedded>InputFiles.TOTAL)throw new IOException("CLI 内嵌附件总大小超过 40MB，请减少本轮或历史附件；未发送");}
                    content.put(new JSONObject().put("type","resource").put("resource",new JSONObject().put("uri",uri).put("mimeType",mime.isEmpty()?"text/plain":mime).put("text",value)));continue;
                }catch(java.nio.charset.CharacterCodingException ignored){/* Non-UTF-8 files remain local resource links. */}
            }
            content.put(new JSONObject().put("type","resource_link").put("uri",uri).put("name",name).put("size",size).put("mimeType",mime.isEmpty()?"application/octet-stream":mime));
        }
        if(workBuddyTransport&&content.toString().getBytes(StandardCharsets.UTF_8).length>MAX_CONTENT_BYTES)throw new IOException("CLI 附件内容超过托管传输大小限制，请减少文件或使用直接桌面；未发送");
        return content;
    }
    private static String imageMime(String name){String extension=name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);return extension.equals("jpg")||extension.equals("jpeg")?"image/jpeg":extension.equals("png")?"image/png":extension.equals("gif")?"image/gif":extension.equals("webp")?"image/webp":"";}
    private static String failureDetail(JSONObject result){
        if(result==null)return "";
        JSONObject meta=result.optJSONObject("_meta");Object value=meta==null?null:meta.opt("codebuddy.ai/errorMessage");
        if(value==null&&meta!=null)value=meta.opt("errorMessage");
        if(value==null)value=result.opt("errorMessage");
        if(value==null||value==JSONObject.NULL)return "";
        String detail=String.valueOf(value);
        try{JSONObject error=value instanceof JSONObject?(JSONObject)value:new JSONObject(detail);detail=error.optString("message",detail);}catch(JSONException ignored){}
        detail=detail.replace('\0',' ').trim();if(detail.length()>2000)detail=detail.substring(0,2000)+"…";
        return detail.isEmpty()?"":"；"+detail;
    }
    static String command(Agent agent,String directory,boolean write,String os){
        List<String> args=new ArrayList<>(agent.command);
        if(agent.kind.equals("dsh"))args.add("acp");else{args.addAll(Arrays.asList("--acp","--permission-mode","default"));if(!write)args.addAll(Arrays.asList("--tools","Read,Glob,Grep"));}
        StringBuilder cmd=new StringBuilder(os.equals("Windows")?"Set-Location -LiteralPath "+Agent.psQuote(directory)+"; & ":"cd -- "+Agent.quote(directory)+" && exec ");
        for(String arg:args)cmd.append(os.equals("Windows")?Agent.psQuote(arg):Agent.quote(arg)).append(' ');
        return os.equals("Windows")?Agent.powershell(cmd.toString()):Agent.login(cmd.toString());
    }
}
