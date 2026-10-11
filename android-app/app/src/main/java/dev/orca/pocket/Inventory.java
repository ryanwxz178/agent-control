package dev.orca.pocket;

import java.util.*;

public final class Inventory {
    public final LinkedHashMap<String,Agent> agents=new LinkedHashMap<>();
    public final LinkedHashMap<String,String> desktopApps=new LinkedHashMap<>();
    public Agent desktopAgent(String id){String app=desktopApps.get(id);return app==null||(!os.equals("Darwin")&&!os.equals("Windows"))?null:new Agent(id,name(id),"desktop",Arrays.asList(app),true);}
    public String os="", home="";
    public boolean complete=false;
    public static String name(String id){
        switch(id){case "teleagent":return "TeleAgent";case "codex":return "Codex";case "claude":return "Claude";case "deepseek":return "DeepSeek Harness";case "workbuddy":return "WorkBuddy";case "doubao":return "豆包";case "codebuddy":return "CodeBuddy";default:return id;}
    }
    public void accept(String line){
        String[] p=line.split("\\|",-1);
        if(p.length==2 && p[0].equals("ORCA_OS"))os=p[1];
        if(p.length==2 && p[0].equals("ORCA_HOME"))home=p[1];
        if(p.length>=4 && p[0].equals("ORCA_AGENT")){
            agents.put(p[1],new Agent(p[1],name(p[1]),p[2],Arrays.asList(Arrays.copyOfRange(p,3,p.length)),true));
            if(p[2].equals("desktop"))desktopApps.put(p[1],p[3]);
        }
        if(p.length==3 && p[0].equals("ORCA_APP")){
            desktopApps.put(p[1],p[2]);
            if(!agents.containsKey(p[1]))agents.put(p[1],new Agent(p[1],name(p[1]),"desktop",Arrays.asList(p[2]),true));
        }
        if(line.equals("ORCA_DETECTION_DONE"))complete=true;
    }
}
