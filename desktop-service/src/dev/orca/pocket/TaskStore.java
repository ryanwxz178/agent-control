package dev.orca.pocket;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Local durable projections; inspiration: OpenMuse task/checkpoint/idempotency contracts (MIT). */
final class TaskStore {
    final Path root;
    private final Map<String,JSONObject> tasks=new LinkedHashMap<>();
    TaskStore(Path root)throws Exception{
        this.root=root;privateDirectory(root);privateDirectory(root.resolve("tasks"));privateDirectory(root.resolve("files"));
        try(java.util.stream.Stream<Path> files=Files.list(root.resolve("tasks"))){
            for(Path p:files.filter(p->p.toString().endsWith(".json")).sorted().toList()){
                JSONObject task=new JSONObject(Files.readString(p));tasks.put(task.getString("id"),task);
            }
        }
    }
    static void privateDirectory(Path path)throws IOException{
        Files.createDirectories(path);
        if(Files.getFileStore(path).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"));
    }
    static void atomic(Path target,byte[] bytes)throws IOException{
        Path temp=Files.createTempFile(target.getParent(),".writing-",".tmp");
        try{
            if(Files.getFileStore(temp).supportsFileAttributeView("posix"))Files.setPosixFilePermissions(temp,PosixFilePermissions.fromString("rw-------"));
            try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.WRITE)){ByteBuffer buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            if(!System.getProperty("os.name").startsWith("Windows"))try(FileChannel parent=FileChannel.open(target.getParent(),StandardOpenOption.READ)){parent.force(true);}
        }finally{Files.deleteIfExists(temp);}
    }
    static String key(String value){if(!value.matches("[A-Za-z0-9_-]{1,100}"))throw new IllegalArgumentException("无效任务标识");return value;}
    static JSONObject copy(JSONObject value){return new JSONObject(value.toString());}
    synchronized JSONObject get(String id){JSONObject value=tasks.get(key(id));if(value==null)throw new IllegalArgumentException("找不到这个任务");return copy(value);}
    synchronized void save(JSONObject task)throws IOException{
        String id=key(task.getString("id"));task.put("updatedAt",System.currentTimeMillis());
        atomic(root.resolve("tasks").resolve(id+".json"),task.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));tasks.put(id,copy(task));
    }
    synchronized JSONObject create(JSONObject request)throws Exception{
        String id=key(request.getString("messageId"));String prompt=request.getString("prompt");
        if(prompt.isBlank()||prompt.length()>20000)throw new IllegalArgumentException("消息过长或为空");
        String fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        if(tasks.containsKey(id)){JSONObject old=get(id);if(!old.getString("fingerprint").equals(fingerprint))throw new IllegalArgumentException("同一请求标识对应不同内容，拒绝重复执行");return old;}
        if(tasks.size()>=1000)throw new IllegalStateException("任务记录已达 1000 条，请在电脑归档后继续");
        JSONObject task=new JSONObject().put("id",id).put("fingerprint",fingerprint).put("request",copy(request)).put("status","queued").put("createdAt",System.currentTimeMillis()).put("events",new JSONArray()).put("seq",0);
        save(task);return task;
    }
    synchronized JSONArray list(){JSONArray result=new JSONArray();tasks.values().stream().sorted(Comparator.comparingLong(t->t.optLong("createdAt"))).forEach(t->result.put(copy(t)));return result;}
    synchronized void recover()throws IOException{
        for(JSONObject value:new ArrayList<>(tasks.values())){
            JSONObject t=copy(value);String status=t.optString("status");
            if(Set.of("running","waiting_approval","waiting_input","stopping").contains(status)){
                t.put("status","unverifiable").put("error","电脑服务重启，原执行状态待确认；不会自动重发。");
                JSONArray events=t.getJSONArray("events");for(int i=0;i<events.length();i++){
                    JSONObject e=events.getJSONObject(i);JSONObject data=e.getJSONObject("data");
                    if(e.getString("type").equals("interaction"))data.put("state","closed").put("message","服务已重启，原确认失效");
                }save(t);
            }
        }
    }
    synchronized void event(String id,String type,JSONObject data)throws IOException{
        JSONObject task=get(id);JSONArray before=task.getJSONArray("events"),next=new JSONArray();
        String identity=data.optString("token",data.optString("attachmentId",data.optString("id","")));
        // Updates are projections, not an unbounded log of repeated complete reply text.
        for(int i=0;i<before.length();i++){
            JSONObject e=before.getJSONObject(i);JSONObject d=e.getJSONObject("data");
            if(e.getString("type").equals(type)&&!identity.isEmpty()&&identity.equals(d.optString("token",d.optString("attachmentId",d.optString("id","")))))continue;
            next.put(e);
        }
        if(next.length()>350)throw new IOException("任务事件过多，停止执行以保留可恢复记录");
        long seq=task.optLong("seq")+1;next.put(new JSONObject().put("seq",seq).put("type",type).put("data",copy(data)));
        task.put("events",next).put("seq",seq);save(task);
    }
    synchronized JSONArray snapshot(){
        JSONArray result=new JSONArray();List<JSONObject> ordered=new ArrayList<>(tasks.values());ordered.sort(Comparator.comparingLong(t->-t.optLong("createdAt")));
        int bytes=0;for(JSONObject original:ordered.subList(0,Math.min(30,ordered.size()))){JSONObject t=copy(original);t.remove("fingerprint");int size=t.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;if(bytes+size>8000000)break;bytes+=size;result.put(t);}return result;
    }
}
