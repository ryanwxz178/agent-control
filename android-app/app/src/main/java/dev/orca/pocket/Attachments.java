package dev.orca.pocket;
import org.json.*;
import java.util.*;
import java.util.regex.*;

/** Only explicit output references become capabilities; WebView cannot supply arbitrary paths. */
final class Attachments {
    static final int LIMIT=20*1024*1024;
    interface Events { void emit(String type,JSONObject value); }
    static final class FileRef {
        final String run,path,name,host; final byte[] inline; final boolean preview;
        FileRef(String r,String p,String n,String h,byte[] bytes,boolean preview){this.preview=preview;run=r;path=p;name=n;host=h;inline=bytes;}
    }
    private final Map<String,FileRef> files=new LinkedHashMap<>();
    private final Set<String> seen=new HashSet<>();
    private final Events events;
    Attachments(Events events){this.events=events;}
    synchronized boolean has(String run){for(FileRef file:files.values())if(file.run.equals(run))return true;return false;}
    synchronized FileRef get(String id){return files.get(id);}
    synchronized void restore(JSONObject data,String host)throws Exception{
        String id=data.getString("attachmentId"),path=data.optString("durablePath");
        if(path.isEmpty()||(!RemoteImages.allowed(path)&&!Agent.absoluteDirectory(path,path.matches("^[A-Za-z]:.*")?"Windows":"Darwin")))return;
        if(files.size()>=120&&!files.containsKey(id))return;
        files.put(id,new FileRef(data.getString("id"),path,data.getString("name"),host,null,data.optBoolean("preview")));
    }
    synchronized void clear(){files.clear();seen.clear();}
    synchronized void local(String run,String raw,String host)throws Exception{local(run,raw,host,false);}
    synchronized void local(String run,String raw,String host,boolean preview)throws Exception{
        String path=raw.trim();
        if(path.startsWith("file://")){java.net.URI uri=new java.net.URI(path);if(uri.getHost()!=null&&!uri.getHost().isEmpty()&&!uri.getHost().equals("localhost"))return;path=uri.getPath();}
        if(path.matches("^/[A-Za-z]:/.*"))path=path.substring(1);
        if(!Agent.absoluteDirectory(path,path.matches("^[A-Za-z]:.*")?"Windows":"Darwin")||path.length()>4096)return;
        if(path.startsWith("//")||path.indexOf('\0')>=0||path.indexOf('\n')>=0)return;
        String name=path.replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);if(name.isEmpty())return;
        put(run,path,name,host,null,preview);
    }
    synchronized void remote(String run,String url,String name,String host)throws Exception{
        if(!RemoteImages.allowed(url))return;
        put(run,url,name,host,null);
    }
    synchronized void inline(String run,String base64,String mime,String host)throws Exception{inline(run,base64,mime,host,null);}
    synchronized void inline(String run,String base64,String mime,String host,String name)throws Exception{
        if(!Arrays.asList("image/png","image/jpeg","image/webp","image/gif").contains(mime)||base64.length()>6_000_000)return;
        byte[] bytes;try{bytes=Base64.getDecoder().decode(base64);}catch(IllegalArgumentException e){return;}
        long retained=0;for(FileRef f:files.values())if(f.inline!=null)retained+=f.inline.length;if(retained+bytes.length>12_000_000)return;
        put(run,"inline:"+Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)),name==null?"图片."+(mime.equals("image/jpeg")?"jpg":mime.substring(6)):name,host,bytes);
    }
    private void put(String run,String path,String name,String host,byte[] data)throws Exception{put(run,path,name,host,data,data!=null);}
    private void put(String run,String path,String name,String host,byte[] data,boolean preview)throws Exception{
        if(files.size()>=48||!seen.add(run+"|"+path))return;
        String id=UUID.randomUUID().toString();files.put(id,new FileRef(run,path,name,host,data,preview));
        events.emit("attachment",new JSONObject().put("id",run).put("attachmentId",id).put("name",name).put("path",RemoteImages.allowed(path)?"豆包回复图片":data==null?path:"Agent 返回的图片").put("inline",data!=null).put("remote",RemoteImages.allowed(path)).put("preview",preview));
    }
    void scan(String run,String text,String host)throws Exception{
        Matcher m=Pattern.compile("!?\\[[^\\]\\n]{0,250}\\]\\((<[^>\\n]+>|[^)\\n]+)\\)").matcher(text);
        while(m.find()){String path=m.group(1);if(path.startsWith("<"))path=path.substring(1,path.length()-1);local(run,path,host);}
    }
    void generated(String run,Object output,String host)throws Exception{
        JSONObject result;
        try{result=output instanceof JSONObject?(JSONObject)output:new JSONObject(String.valueOf(output));}catch(Exception invalid){return;}
        if(!result.optString("type").equals("image_gen_tool_result")){
            Object nested=result.opt("rawResponse");if(nested==null)nested=result.opt("output");
            if(nested instanceof JSONObject && ((JSONObject)nested).optString("type").equals("text"))nested=((JSONObject)nested).opt("text");
            try{result=nested instanceof JSONObject?(JSONObject)nested:new JSONObject(String.valueOf(nested));}catch(Exception invalid){return;}
        }
        if(!result.optString("type").equals("image_gen_tool_result")||!result.optString("status").equals("completed"))return;
        JSONArray images=result.optJSONArray("images");if(images==null)return;
        for(int i=0;i<images.length();i++){
            JSONObject image=images.optJSONObject(i);if(image==null)continue;String path=image.optString("localPath");
            if(path.matches("(?is).*\\.(png|jpe?g|webp|gif)$")&&!path.matches("(?s).*[\\x00-\\x1f].*")&&!path.replace('\\','/').matches("(?s).*(?:^|/)\\.\\.?(?:/|$).*"))local(run,path,host,true);
        }
    }
    void content(String run,JSONObject item,String host)throws Exception{
        String type=item.optString("type");
        if(type.equals("image")){
            JSONObject source=item.optJSONObject("source");
            if(source!=null&&source.optString("type").equals("base64"))inline(run,source.optString("data"),source.optString("media_type"),host);
            else inline(run,item.optString("data"),item.optString("mimeType"),host);
        }
        if(type.equals("imageGeneration")&&!item.optString("savedPath").isEmpty())local(run,item.optString("savedPath"),host);
        if(type.equals("imageGeneration")&&item.optString("savedPath").isEmpty())inline(run,item.optString("result"),"image/png",host);
        if(type.equals("resource_link")&&item.optString("uri").startsWith("file:"))local(run,item.optString("uri"),host);
        if(type.equals("attachment")){if(item.has("url"))remote(run,item.optString("url"),item.optString("name","豆包生成图片.png"),host);else local(run,item.optString("path"),host,item.optBoolean("preview"));}
    }
}
