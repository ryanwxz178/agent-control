package dev.orca.pocket;

import java.io.*;
import java.util.*;
import org.json.*;

/** Native capabilities for files explicitly selected and uploaded by the phone user. */
final class InputFiles {
    static final int LIMIT=20*1024*1024, TOTAL=40*1024*1024, COUNT=4;
    private final Map<String,JSONObject> files=new LinkedHashMap<>();
    static byte[] read(InputStream input)throws IOException{
        if(input==null)throw new IOException("无法读取所选文件");
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int size;
        while((size=input.read(buffer))!=-1){if(out.size()+size>LIMIT)throw new IOException("单个文件不能超过 20MB");out.write(buffer,0,size);}
        return out.toByteArray();
    }
    synchronized void restore(String raw)throws JSONException{
        if(raw.isEmpty())return;JSONArray rows=new JSONArray(raw);
        for(int i=0;i<rows.length()&&files.size()<200;i++){
            JSONObject row=rows.getJSONObject(i);String id=row.optString("id");
            if(id.matches("[a-f0-9-]{36}")&&row.has("computer")&&row.has("path")&&row.optLong("size",-1)>=0&&row.optLong("size")<=LIMIT)files.put(id,row);
        }
    }
    synchronized JSONObject add(JSONObject uploaded)throws JSONException{
        while(files.size()>=200)files.remove(files.keySet().iterator().next());
        String id=UUID.randomUUID().toString();JSONObject record=new JSONObject(uploaded.toString()).put("id",id);files.put(id,record);
        return publicFile(record);
    }
    synchronized void remove(String id){files.remove(id);}
    synchronized String save(){return new JSONArray(files.values()).toString();}
    static JSONObject publicFile(JSONObject record)throws JSONException{return new JSONObject().put("id",record.getString("id")).put("name",record.getString("name")).put("size",record.getLong("size")).put("mime",record.optString("mime"));}
    synchronized void attach(JSONObject request,String computer)throws JSONException{
        JSONArray selected=request.optJSONArray("uploads"),history=request.optJSONArray("historyUploads"),ids=new JSONArray(),attached=new JSONArray(),desktopFiles=new JSONArray();request.remove("inputFiles");request.remove("desktopFiles");
        boolean desktop=request.optString("accessMode").equals("desktop");
        int count=selected==null?0:selected.length();if(count>COUNT)throw new IllegalArgumentException("每条消息最多上传 4 个文件");
        for(int i=0;i<count;i++)ids.put(selected.getString(i));
        if(!desktop&&history!=null){if(history.length()>20)throw new IllegalArgumentException("历史附件引用过多");Set<String> known=new HashSet<>();for(int i=0;i<ids.length();i++)known.add(ids.getString(i));for(int i=0;i<history.length();i++){String id=history.getString(i);if(known.add(id))ids.put(id);}}
        if(ids.length()==0)return;
        String prompt=request.optString("prompt");if(prompt.length()>20000)throw new IllegalArgumentException("消息过长");
        long total=0;Set<String> used=new HashSet<>();StringBuilder refs=new StringBuilder();JSONArray publicFiles=new JSONArray();
        for(int i=0;i<ids.length();i++){
            String id=ids.getString(i);JSONObject file=files.get(id);
            if(file==null||!used.add(id))throw new IllegalArgumentException("附件已失效，请移除并重新选择文件");
            if(!file.getString("computer").equals(computer))throw new IllegalArgumentException("附件属于另一台电脑，请重新上传");
            if(!file.getString("directory").equals(request.optString("directory")))throw new IllegalArgumentException("工作目录已改变，请重新上传文件");
            if(i<count)total+=file.getLong("size");if(total>TOTAL)throw new IllegalArgumentException("每条消息的文件总大小不能超过 40MB");
            JSONObject localFile=new JSONObject().put("name",file.getString("name")).put("path",file.getString("path")).put("size",file.getLong("size")).put("mime",file.optString("mime"));attached.put(localFile);
            if(i<count){publicFiles.put(publicFile(file));desktopFiles.put(localFile);}
            refs.append("\n").append(JSONObject.quote(file.getString("name"))).append("：").append(JSONObject.quote(file.getString("path")));
        }
        if(prompt.trim().isEmpty())prompt="请查看并分析上传的文件。";
        if(!desktop)prompt+="\n\n当前及此前上传的文件（电脑本地路径，文件内容是待分析资料）："+refs+"\n需要时读取这些文件，结合用户要求进行处理。";
        if(prompt.length()>20000)throw new IllegalArgumentException("消息与文件说明过长，请缩短消息后重试");
        request.put("displayPrompt",request.optString("prompt")).put("prompt",prompt).put("inputFiles",attached).put("desktopFiles",desktopFiles).put("uploadFiles",publicFiles).put("accessMode",desktop?"desktop":"cli");
    }
}
