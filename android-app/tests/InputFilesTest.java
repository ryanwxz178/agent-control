package dev.orca.pocket;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.json.*;

public final class InputFilesTest {
    static JSONObject file(String name,long size)throws Exception{return new JSONObject().put("name",name).put("mime","application/pdf").put("size",size).put("path","D:/project/.agent-control-input/uuid-"+name).put("computer","pc|22|user").put("directory","D:/project");}
    static JSONObject request(String... ids)throws Exception{return new JSONObject().put("prompt","分析这个文件").put("directory","D:/project").put("uploads",new JSONArray(Arrays.asList(ids)));}
    interface Throwing {void run()throws Exception;}
    static void rejects(Throwing action)throws Exception{try{action.run();throw new AssertionError("Expected rejection");}catch(IllegalArgumentException|IOException expected){}}
    public static void main(String[] args)throws Exception{
        assert Arrays.equals(InputFiles.read(new ByteArrayInputStream(new byte[]{1,2,3})),new byte[]{1,2,3});
        rejects(()->InputFiles.read(new ByteArrayInputStream(new byte[InputFiles.LIMIT+1])));
        InputFiles files=new InputFiles();JSONObject publicFile=files.add(file("报告.pdf",20));String id=publicFile.getString("id");
        assert !publicFile.has("path")&&!publicFile.has("computer");
        JSONObject outgoing=request(id);files.attach(outgoing,"pc|22|user");
        assert outgoing.getString("prompt").contains(".agent-control-input/");assert outgoing.getString("displayPrompt").equals("分析这个文件");assert outgoing.getJSONArray("inputFiles").length()==1;
        assert outgoing.getString("accessMode").equals("cli");rejects(()->files.attach(request(id).put("accessMode","desktop"),"pc|22|user"));
        JSONObject followup=request().put("historyUploads",new JSONArray().put(id));files.attach(followup,"pc|22|user");assert followup.getString("prompt").contains(".agent-control-input/");assert followup.getJSONArray("uploadFiles").length()==0;
        rejects(()->files.attach(request(id),"other|22|user"));rejects(()->files.attach(request(id).put("directory","D:/other"),"pc|22|user"));
        rejects(()->files.attach(request("forged"),"pc|22|user"));rejects(()->files.attach(request(id,id),"pc|22|user"));
        JSONObject forged=request().put("inputFiles",new JSONArray().put(new JSONObject().put("path","C:/secret")));files.attach(forged,"pc|22|user");assert !forged.has("inputFiles");
        InputFiles restored=new InputFiles();restored.restore(files.save());restored.attach(request(id),"pc|22|user");
        restored.remove(id);rejects(()->restored.attach(request(id),"pc|22|user"));
        String first=files.add(file("large1.pdf",InputFiles.LIMIT)).getString("id"),second=files.add(file("large2.pdf",InputFiles.LIMIT)).getString("id"),third=files.add(file("large3.pdf",InputFiles.LIMIT)).getString("id");
        files.attach(request(first,second),"pc|22|user");rejects(()->files.attach(request(first,second,third),"pc|22|user"));rejects(()->files.attach(request(id,first,second,third,"fifth"),"pc|22|user"));
        Path assets=Path.of(args[0],"android-app/app/src/main/assets");
        for(String os:List.of("Windows","Darwin")){
            String directory=os.equals("Windows")?"D:/项目":"/tmp/project";List<String> paths=new ArrayList<>();List<byte[]> payloads=new ArrayList<>();
            SshTransport transport=new SshTransport(){
                public boolean connected(){return true;}
                public int run(String command,String stdin,Line line,AtomicBoolean cancelled,long timeout)throws Exception{for(String row:List.of("ORCA_OS|"+os,"ORCA_HOME|"+directory,"ORCA_AGENT|codex|codex|codex","ORCA_DETECTION_DONE"))line.accept(row);return 0;}
                public void upload(String path,byte[] bytes){paths.add(path);payloads.add(bytes);}
            };
            PocketEngine engine=new PocketEngine(new PocketEngine.Host(){public String asset(String name)throws Exception{return Files.readString(assets.resolve(name));}public void event(String type,JSONObject data){}public boolean trust(String h,String p){return false;}},transport);
            try{engine.local(new JSONObject().put("directory",directory).put("host","pc").put("user","user"));
                JSONObject uploaded=engine.uploadInput("../报告'$(oops).pdf","application/pdf",new byte[]{7,8});
                assert paths.size()==1&&paths.get(0).startsWith(directory+"/.agent-control-input/");
                String leaf=paths.get(0).substring((directory+"/.agent-control-input/").length());assert !leaf.contains("/")&&!leaf.contains("\\")&&!leaf.contains("$");assert Arrays.equals(payloads.get(0),new byte[]{7,8});assert uploaded.getString("computer").equals("pc|22|user");
            }finally{engine.close();}
        }
        System.out.println("PASS selected file limits, durable capabilities, forged/duplicate IDs, computer/directory isolation, Windows/macOS upload destinations and byte integrity.");
    }
}
