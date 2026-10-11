package dev.orca.pocket;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Reuses OpenMuse's authenticated Chromium worker, including its public-destination policy. */
final class BrowserBridge {
    private final Path root;
    private final Map<String,JSONObject> sessions=new LinkedHashMap<>();
    BrowserBridge(Path root)throws Exception{
        this.root=root;Path file=root.resolve("browsers.json");if(Files.exists(file)){JSONArray list=new JSONArray(Files.readString(file));for(int i=0;i<list.length();i++){JSONObject s=list.getJSONObject(i);sessions.put(s.getString("id"),s);}}
    }
    void install(SshTransport.Line progress,java.util.concurrent.atomic.AtomicBoolean cancelled)throws Exception{
        Path directory=root.getParent().resolve("browser");TaskStore.privateDirectory(directory);
        String[] files={"package.json","package-lock.json","setup.mjs","LICENSE-OpenMuse","src/index.ts","src/server.ts","src/browser.ts","src/downloads.ts","src/proxy.ts","src/network.ts","src/errors.ts"};
        for(String name:files){Path target=directory.resolve(name);TaskStore.privateDirectory(target.getParent());try(InputStream in=BrowserBridge.class.getResourceAsStream("/browser/"+name)){if(in==null)throw new FileNotFoundException("浏览器资源缺失："+name);TaskStore.atomic(target,in.readAllBytes());}}
        String script=directory.resolve("setup.mjs").toString();String command=System.getProperty("os.name").startsWith("Windows")?Agent.powershell("& node "+Agent.psQuote(script)+"; exit $LASTEXITCODE"):Agent.login("exec node "+Agent.quote(script));
        try(LocalTransport runner=new LocalTransport()){runner.run(command,"",progress,cancelled,900000);}
    }
    private byte[] call(String path,JSONObject input)throws Exception{
        Path config=root.getParent().resolve("browser.json");if(!Files.exists(config))throw new IllegalStateException("浏览器未安装；请在电脑运行 desktop-service/browser/setup.mjs");
        JSONObject settings=new JSONObject(Files.readString(config));URI endpoint=URI.create(settings.getString("url"));
        if(!Set.of("127.0.0.1","localhost","[::1]").contains(endpoint.getHost())||!endpoint.getScheme().equals("http"))throw new IllegalStateException("浏览器 worker 必须位于电脑 loopback 地址");
        HttpURLConnection request=(HttpURLConnection)endpoint.resolve(path).toURL().openConnection();request.setInstanceFollowRedirects(false);request.setConnectTimeout(5000);request.setReadTimeout(30000);
        request.setRequestProperty("Authorization","Bearer "+settings.getString("token"));
        try{
            if(input!=null){request.setRequestMethod("POST");request.setDoOutput(true);request.setRequestProperty("Content-Type","application/json");try(OutputStream out=request.getOutputStream()){out.write(input.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}}
            int status=request.getResponseCode();InputStream stream=status>=400?request.getErrorStream():request.getInputStream();
            if(stream==null)throw new IOException("浏览器 worker 响应 "+status);
            byte[] data;try(InputStream in=stream){data=in.readNBytes(11*1024*1024);}
            if(data.length>=11*1024*1024)throw new IOException("浏览器结果超过大小限制");
            if(status>=400)throw new IOException(new String(data,java.nio.charset.StandardCharsets.UTF_8));return data;
        }finally{request.disconnect();}
    }
    private JSONObject json(String path,JSONObject body)throws Exception{return new JSONObject(new String(call(path,body),java.nio.charset.StandardCharsets.UTF_8));}
    private void save()throws Exception{JSONArray result=new JSONArray();sessions.values().forEach(result::put);TaskStore.atomic(root.resolve("browsers.json"),result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private String known(String id){UUID.fromString(id);if(!sessions.containsKey(id))throw new IllegalArgumentException("浏览器会话已失效");return id;}
    synchronized JSONObject request(JSONObject input)throws Exception{
        String op=input.getString("op"),id=input.optString("sessionId");
        if(op.equals("browserOpen")){
            if(sessions.size()>=20)throw new IllegalStateException("浏览器已达 20 个保存会话，请先关闭并在电脑归档");
            id=UUID.randomUUID().toString();JSONObject session=new JSONObject().put("id",id).put("url",input.getString("url"));sessions.put(id,session);save();
            try{json("/sessions",session);}catch(Exception failure){sessions.remove(id);save();throw failure;}
        }else{
            known(id);
            if(op.equals("browserInput"))json("/sessions/"+id+"/input",input.getJSONObject("input"));
            else if(op.equals("browserNavigate"))json("/sessions/"+id+"/navigate",new JSONObject().put("url",input.getString("url")));
            else if(op.equals("browserClose")){json("/sessions/"+id+"/close",new JSONObject());sessions.remove(id);save();return new JSONObject().put("browserClosed",id);}
            else if(!op.equals("browserRead"))throw new IllegalArgumentException("未知浏览器操作");
        }
        JSONObject page;
        try{page=json("/sessions/"+id+"/read",null);}catch(IOException closed){
            if(!op.equals("browserRead")||!closed.getMessage().contains("SESSION_CLOSED"))throw closed;
            json("/sessions",sessions.get(id));page=json("/sessions/"+id+"/read",null);
        }sessions.put(id,new JSONObject().put("id",id).put("url",page.optString("url")).put("title",page.optString("title")));save();
        byte[] screenshot=call("/sessions/"+id+"/screenshot",null);
        return new JSONObject().put("browser",page.put("sessionId",id).put("preview","data:image/png;base64,"+Base64.getEncoder().encodeToString(screenshot)));
    }
    synchronized JSONObject observe(String id,String url)throws Exception{
        sessions.put(id,new JSONObject().put("id",id).put("url",url));save();json("/sessions",sessions.get(id));
        JSONObject result=json("/sessions/"+id+"/read",null);json("/sessions/"+id+"/close",new JSONObject());return result;
    }
    static String hash(String value)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
}
