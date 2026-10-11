package dev.orca.pocket;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.nio.file.*;
import java.security.*;
import org.json.*;

public final class CoreTest {
    static int passed;
    static void check(boolean b,String name){if(!b)throw new AssertionError(name);passed++;System.out.println("PASS "+name);}
    static Agent codex=new Agent("codex","Codex","codex",Arrays.asList("/usr/local/bin/codex"),true);
    static Agent claude=new Agent("claude","Claude","claude",Arrays.asList("/usr/local/bin/claude"),true);
    static void javaProbeTests()throws Exception{
        Path temp=Files.createTempDirectory("agent java ' $()-");
        Path fake=temp.resolve("runtime/bin/java");Files.createDirectories(fake.getParent());
        Files.writeString(fake,"#!/bin/sh\nexit 0\n");fake.toFile().setExecutable(true);
        Path project=temp.resolve("project space/subdir");Files.createDirectories(project);
        for(String shell:Arrays.asList("/bin/sh","/bin/bash","/bin/zsh")){
            if(!Files.isExecutable(Path.of(shell)))continue;
            ProcessBuilder builder=new ProcessBuilder(shell,"-c",PersistentClient.unixJavaProbe(project.toString(),temp.toString(),""));
            builder.environment().put("JAVA_HOME",fake.getParent().getParent().toString());
            Process child=builder.redirectErrorStream(true).start();String out=new String(child.getInputStream().readAllBytes(),"UTF-8");
            check(child.waitFor()==0&&out.trim().equals("AGENT_JAVA|"+fake),shell+" missing optional JDK globs do not abort valid JAVA_HOME");
        }
        // A PATH Java stub must not mask the project's bundled runtime.
        Path stub=temp.resolve("path/java");Files.createDirectories(stub.getParent());Files.writeString(stub,"#!/bin/sh\nexit 1\n");stub.toFile().setExecutable(true);
        Path bundled=temp.resolve("project space/.toolchain/jdk-test/Contents/Home/bin/java");Files.createDirectories(bundled.getParent());Files.copy(fake,bundled);bundled.toFile().setExecutable(true);
        ProcessBuilder builder=new ProcessBuilder("/bin/zsh","-c",PersistentClient.unixJavaProbe(project.toString(),temp.toString(),""));
        builder.environment().put("JAVA_HOME",temp.resolve("absent").toString());builder.environment().put("PATH",stub.getParent()+":/usr/bin:/bin");
        Process child=builder.redirectErrorStream(true).start();String out=new String(child.getInputStream().readAllBytes(),"UTF-8");
        check(child.waitFor()==0&&out.trim().equals("AGENT_JAVA|"+bundled),"bundled macOS JDK found in project ancestor with shell-special path");
        Path linux=temp.resolve("project space/.toolchain/jdk-test/bin/java");Files.createDirectories(linux.getParent());Files.move(bundled,linux);
        child=builder.redirectErrorStream(true).start();out=new String(child.getInputStream().readAllBytes(),"UTF-8");
        check(child.waitFor()==0&&out.trim().equals("AGENT_JAVA|"+linux),"bundled Linux JDK found when macOS glob is absent");
        Path workspace=temp.resolve("workspace/team/repo/.toolchain/jdk-test/bin/java");Files.createDirectories(workspace.getParent());Files.move(linux,workspace);
        builder=new ProcessBuilder("/bin/zsh","-c",PersistentClient.unixJavaProbe(temp.toString(),temp.toString(),""));
        builder.environment().put("JAVA_HOME",temp.resolve("absent").toString());builder.environment().put("PATH",stub.getParent()+":/usr/bin:/bin");
        child=builder.redirectErrorStream(true).start();out=new String(child.getInputStream().readAllBytes(),"UTF-8");
        check(child.waitFor()==0&&out.trim().equals("AGENT_JAVA|"+workspace),"default home directory discovers bounded workspace toolchains");
        Files.copy(workspace,linux);linux.toFile().setExecutable(true);
        child=new ProcessBuilder("/bin/zsh","-c",PersistentClient.unixJavaProbe(project.toString(),temp.toString(),temp.resolve("missing java").toString())).redirectErrorStream(true).start();
        out=new String(child.getInputStream().readAllBytes(),"UTF-8");check(child.waitFor()!=0&&out.contains("高级设置填写 Java 路径")&&!out.contains("no matches found"),"invalid explicit Java gives actionable error without silent fallback");
        child=new ProcessBuilder("/bin/zsh","-c",PersistentClient.unixJavaProbe(project.toString(),temp.toString(),linux.toString())).redirectErrorStream(true).start();
        out=new String(child.getInputStream().readAllBytes(),"UTF-8");check(child.waitFor()==0&&out.trim().equals("AGENT_JAVA|"+linux),"explicit Java remains supported");
    }
    static void tests()throws Exception{
        javaProbeTests();
        check(RemoteImages.allowed("https://p3-flow-imagex-sign.byteimg.com/path/photo.png?signature=test"),"Doubao CDN reply images accepted");
        for(String url:Arrays.asList("http://p3.byteimg.com/a","https://byteimg.com.evil.test/a","https://localhost/a","file:///tmp/a","https://user@p3.byteimg.com/a","https://p3.byteimg.com:8443/a"))check(!RemoteImages.allowed(url),"Unsafe image endpoint rejected");
        AtomicReference<String> greetingStatus=new AtomicReference<>("");
        EventDecoder greetingDecoder=new EventDecoder((text,status)->greetingStatus.set(status));
        greetingDecoder.accept(new JSONObject().put("type","desktop.snapshot").put("text","你好！有什么可以帮你的吗？").toString());
        greetingDecoder.accept(new JSONObject().put("type","desktop.done").put("text","你好！有什么可以帮你的吗？").toString());
        check(greetingDecoder.completed&&!greetingDecoder.failed&&greetingDecoder.text().equals("你好！有什么可以帮你的吗？")&&greetingStatus.get().equals("已完成"),"TeleAgent ordinary reply events reach phone decoder with final text and completion");
        Inventory tele=new Inventory();tele.accept("ORCA_OS|Darwin");tele.accept("ORCA_APP|teleagent|TeleAgent");
        Agent ta=tele.agents.get("teleagent");
        check(ta!=null&&ta.installed&&ta.name.equals("TeleAgent")&&ta.supported()&&ta.desktop()&&ta.json().getBoolean("supported")&&tele.desktopAgent("teleagent")!=null,"TeleAgent detected as supported desktop adapter");
        check(PocketEngine.chooseAccess(tele,ta,"auto").desktop(),"TeleAgent default access uses desktop adapter");
        boolean blockedTele=false;try{PocketEngine.chooseAccess(tele,ta,"cli");}catch(IllegalStateException e){blockedTele=true;}
        check(blockedTele,"TeleAgent CLI-only rejected without inventing a CLI");
        String hostile="hi'; touch /tmp/never; echo '中文\n$(id)`id`";
        Process p=new ProcessBuilder("/bin/sh","-c","printf %s "+Agent.quote(hostile)).start();
        check(new String(p.getInputStream().readAllBytes(),"UTF-8").equals(hostile)&&p.waitFor()==0,"shell argument injection is inert");
        check(codex.shell("/tmp/a'b",false).contains("read-only"),"Codex analysis uses read-only sandbox");
        check(claude.shell("/tmp",false).contains("Read,Glob,Grep")&&!claude.shell("/tmp",false).contains("acceptEdits"),"Claude analysis restricts built-in tools");
        String windowsDir="C:\\项目\\a'b & $(whoami)";
        String windowsCmd=codex.shell(windowsDir,false,"Windows");
        String windowsSource=new String(Base64.getDecoder().decode(windowsCmd.substring(windowsCmd.lastIndexOf(' ')+1)),"UTF-16LE");
        check(windowsSource.contains("Set-Location -LiteralPath 'C:\\项目\\a''b & $(whoami)'")&&windowsSource.contains("'read-only'"),"Windows command uses encoded UTF-16 and literal directory with read-only sandbox");
        check(!windowsCmd.contains(windowsDir)&&windowsCmd.length()<8191,"Windows command remains safe through cmd.exe and PowerShell SSH shells");
        check(Agent.absoluteDirectory("C:\\Users\\name\\repo","Windows")&&Agent.absoluteDirectory("D:/项目","Windows")&&Agent.absoluteDirectory("\\\\server\\share\\repo","Windows"),"Windows accepts drive and UNC absolute paths");
        check(!Agent.absoluteDirectory("C:relative","Windows")&&!Agent.absoluteDirectory("/tmp","Windows")&&!Agent.absoluteDirectory("repo","Windows")&&!Agent.absoluteDirectory("C:\\x\nwhoami","Windows"),"Windows rejects relative POSIX and multiline paths");
        check(Agent.absoluteDirectory("/tmp/project","Linux")&&!Agent.absoluteDirectory("C:\\repo","Linux"),"WSL keeps POSIX path semantics");
        boolean nul=false;try{Agent.psQuote("x\0y");}catch(IllegalArgumentException e){nul=true;}check(nul,"PowerShell rejects NUL");
        Inventory win=new Inventory();win.accept("ORCA_OS|Windows");win.accept("ORCA_AGENT|codex|codex|C:\\Tools\\codex.cmd");win.accept("ORCA_APP|codex|codex");win.accept("ORCA_APP|doubao|doubao");
        check(win.desktopAgent("doubao")!=null&&PocketEngine.chooseAccess(win,win.agents.get("codex"),"desktop").desktop()&&!win.agents.get("codex").desktop(),"Windows desktop and native CLI access coexist");
        String psRuntime=System.getenv("PWSH_TEST_EXEC");
        if(psRuntime!=null){
            Path psDir=Files.createTempDirectory("pocket-ps-' $()-");
            Agent nativeProbe=new Agent("codex","Probe","codex",Arrays.asList("/bin/sh","-c","cat; exit 23"),true);
            String encoded=nativeProbe.shell(psDir.toString(),false,"Windows");
            Process nativePs=new ProcessBuilder(psRuntime,"-NoLogo","-NoProfile","-NonInteractive","-EncodedCommand",encoded.substring(encoded.lastIndexOf(' ')+1)).start();
            String unicode="中文 task\n'; $(touch SHOULD_NOT_EXIST) & `whoami`\n";
            nativePs.getOutputStream().write(unicode.getBytes("UTF-8"));nativePs.getOutputStream().close();
            if(!nativePs.waitFor(15,TimeUnit.SECONDS)){nativePs.destroyForcibly();throw new AssertionError("PowerShell stdin timeout");}
            String reply=new String(nativePs.getInputStream().readAllBytes(),"UTF-8");
            check(reply.replace("\r\n","\n").equals(unicode)&&nativePs.exitValue()==23,"real PowerShell forwards UTF-8 native stdin and exit code");
            check(!Files.exists(psDir.resolve("SHOULD_NOT_EXIST")),"real PowerShell treats prompt and quoted working directory as data");
            Files.delete(psDir);
        }
        Inventory inv=new Inventory();inv.accept("ORCA_OS|Darwin");inv.accept("ORCA_AGENT|claude|claude|/tmp/claude");inv.accept("ORCA_APP|claude|Claude");inv.accept("ORCA_DETECTION_DONE");
        check(inv.agents.get("claude").kind.equals("claude")&&inv.complete,"app detection cannot overwrite verified CLI");
        check(PocketEngine.chooseAccess(inv,inv.agents.get("claude"),"auto").kind.equals("claude"),"default access preserves CLI priority");
        check(PocketEngine.chooseAccess(inv,inv.agents.get("claude"),"desktop").desktop(),"explicit desktop selects installed application without CLI");
        check(PocketEngine.chooseAccess(inv,inv.agents.get("claude"),"cli").kind.equals("claude"),"CLI-only access preserves command adapter");
        boolean noCli=false;try{PocketEngine.chooseAccess(inv,new Agent("doubao","豆包","desktop",Arrays.asList("Doubao"),true),"cli");}catch(IllegalStateException e){noCli=true;}check(noCli,"CLI-only rejects desktop-only agent before execution");
        boolean missingDesktop=false;try{PocketEngine.chooseAccess(inv,codex,"desktop");}catch(IllegalStateException e){missingDesktop=true;}
        check(missingDesktop,"desktop-only refuses an undetected application");
        Exception missingDsh=new Exception("远端退出码 1：dsh: MISSING_CREDENTIAL: llm-deepseek: no API key for provider route \"deepseek-official\"");
        EventDecoder freshDsh=new EventDecoder((t,s)->{});
        check(PocketEngine.canFallback(freshDsh,missingDsh),"DeepSeek missing credential before output permits desktop fallback");
        freshDsh.workStarted=true;
        check(!PocketEngine.canFallback(freshDsh,missingDsh),"DeepSeek failure after output cannot replay task");
        check(!PocketEngine.canFallback(new EventDecoder((t,s)->{}),new Exception("MISSING_CREDENTIAL")),"unclassified credential text cannot replay task");
        EventDecoder decoder=new EventDecoder((t,s)->{});
        decoder.accept("{\"type\":\"item.completed\",\"item\":{\"id\":\"1\",\"type\":\"agent_message\",\"text\":\"你好\"}}");
        decoder.accept("{\"type\":\"item.completed\",\"item\":{\"id\":\"1\",\"type\":\"agent_message\",\"text\":\"你好世界\"}}");
        decoder.accept("{\"type\":\"turn.completed\"}");
        check(decoder.completed&&decoder.text().equals("你好世界"),"Codex deduplicates replayed item events");
        EventDecoder buddy=new EventDecoder((t,s)->{});
        buddy.accept("{\"type\":\"result\",\"is_error\":true,\"errors\":[\"Authentication required\"]}");
        check(buddy.failed&&buddy.text().contains("Authentication required"),"WorkBuddy exit-zero auth failure remains failure");
        check(PocketEngine.canFallback(buddy,new Exception(buddy.text())),"CLI authentication rejection permits desktop fallback");
        check(PocketEngine.canFallback(new EventDecoder((t,s)->{}),new Exception("远端退出码 1：Authentication required")),"nonzero CLI authentication exit permits desktop fallback");
        check(!PocketEngine.canFallback(buddy,new Exception("SSH 通道中断，状态未知")),"ambiguous disconnect prevents desktop replay");
        EventDecoder executedBuddy=new EventDecoder((t,s)->{});
        executedBuddy.accept("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"Write\"}]}}");
        executedBuddy.accept("{\"type\":\"result\",\"is_error\":true,\"errors\":[\"Authentication required\"]}");
        check(!PocketEngine.canFallback(executedBuddy,new Exception(executedBuddy.text())),"authentication error after tool execution cannot replay on desktop");
        check(!PocketEngine.canFallback(new EventDecoder((t,s)->{}),new Exception("unknown task failure")),"unclassified CLI failure never triggers desktop replay");
        EventDecoder authAssistant=new EventDecoder((t,s)->{});
        authAssistant.accept("{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"text\",\"text\":\"Authentication required. Please use /login command to sign in to your account\"}]}}");
        authAssistant.accept("{\"type\":\"result\",\"is_error\":true,\"errors\":[\"Authentication required\"]}");
        check(PocketEngine.canFallback(authAssistant,new Exception(authAssistant.text())),"actual WorkBuddy assistant auth refusal is a startup failure");
        Inventory desktopInventory=new Inventory();desktopInventory.accept("ORCA_OS|Darwin");desktopInventory.accept("ORCA_AGENT|workbuddy|buddy|/usr/bin/node|/tmp/codebuddy");desktopInventory.accept("ORCA_APP|workbuddy|WorkBuddy");desktopInventory.accept("ORCA_AGENT|doubao|desktop|Doubao");
        check(desktopInventory.desktopAgent("workbuddy")!=null&&desktopInventory.agents.get("workbuddy").kind.equals("buddy")&&desktopInventory.desktopAgent("doubao")!=null,"CLI and desktop detection coexist for WorkBuddy and legacy desktop rows");
        List<Agent> roster=Arrays.asList(codex,claude);
        for(String invalid:Arrays.asList("{\"tasks\":[]}","{\"tasks\":[{\"agent\":\"unknown\",\"task\":\"x\"}]}","{\"tasks\":[{\"agent\":\"codex\",\"task\":\"x\"},{\"agent\":\"codex\",\"task\":\"y\"}]}")){
            boolean rejected=false;try{TaskRouter.validatePlan(invalid,roster);}catch(JSONException e){rejected=true;}check(rejected,"invalid agent plan rejected");
        }
        AtomicInteger active=new AtomicInteger(),peak=new AtomicInteger(),writes=new AtomicInteger();
        CountDownLatch entered=new CountDownLatch(2);
        TaskRouter router=new TaskRouter((a,text,write,label)->{
            if(label.equals("规划分工"))return "{\"tasks\":[{\"agent\":\"codex\",\"task\":\"inspect A\"},{\"agent\":\"claude\",\"task\":\"inspect B\"}]}";
            if(write)writes.incrementAndGet();
            if(label.equals("并行分析")){check(!write,"parallel worker is read-only");peak.accumulateAndGet(active.incrementAndGet(),Math::max);entered.countDown();if(!entered.await(3,TimeUnit.SECONDS))throw new AssertionError("workers not concurrent");Thread.sleep(50);active.decrementAndGet();return a.name+" analysis";}
            check(active.get()==0&&text.contains("Claude analysis")&&text.contains("Codex analysis"),"synthesis waits for all successful workers");return "combined result";
        },s->{});
        check(router.run(roster,"task",true,new AtomicBoolean()).equals("combined result")&&peak.get()==2&&writes.get()==1,"parallel analysis with one final writer");
        AtomicInteger calls=new AtomicInteger();
        TaskRouter fallback=new TaskRouter((a,t,w,l)->{calls.incrementAndGet();if(a==codex)throw new Exception("Authentication required");return "fallback success";},s->{});
        check(fallback.run(roster,"task",false,new AtomicBoolean()).equals("fallback success"),"unavailable coordinator falls back to another agent");
        AtomicInteger executed=new AtomicInteger();
        TaskRouter uncertain=new TaskRouter((a,t,w,l)->{if(l.equals("规划分工"))return "{\"tasks\":[{\"agent\":\"codex\",\"task\":\"work\"}]}";executed.incrementAndGet();throw new Exception("connection lost after dispatch");},s->{});
        try{uncertain.run(roster,"task",true,new AtomicBoolean());}catch(Exception expected){}
        check(executed.get()==1,"ambiguous execution is never automatically replayed");
        AtomicBoolean cancelled=new AtomicBoolean(true);AtomicInteger workerCalls=new AtomicInteger();
        TaskRouter cancel=new TaskRouter((a,t,w,l)->{workerCalls.incrementAndGet();return "";},s->{});
        boolean stopped=false;try{cancel.run(roster,"task",false,cancelled);}catch(CancellationException e){stopped=true;}
        check(stopped,"cancelled plan cannot dispatch work");
    }
    static void ssh(String root)throws Exception{
        Path keys=Path.of(root,".toolchain/ssh-test");
        byte[] key=Files.readAllBytes(keys.resolve("client"));
        byte[] hostBlob=Base64.getDecoder().decode(Files.readString(keys.resolve("host.pub")).split(" ")[1]);
        String fingerprint="SHA256:"+Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(hostBlob));
        try(SshTransport ssh=new SshTransport()){
            boolean rejected=false;try{ssh.connect("127.0.0.1",22992,System.getProperty("user.name"),"",key,(h,f)->false);}catch(Exception e){rejected=true;}
            check(rejected&&!ssh.connected(),"SSH rejects untrusted host keys");
            ssh.connect("127.0.0.1",22992,System.getProperty("user.name"),"",key,(h,f)->f.equals(fingerprint));
            StringBuilder out=new StringBuilder();String input="中文 multi-line\n'; touch /tmp/never; $(id)\n";
            ssh.run("cat",input,line->out.append(line).append('\n'),new AtomicBoolean(),3000);
            check(out.toString().equals(input),"SSH sends UTF-8 prompt via stdin without interpolation");
            long start=System.currentTimeMillis();boolean timeout=false;
            try{ssh.run("sleep 8","",line->{},new AtomicBoolean(),300);}catch(Exception e){timeout=true;}
            check(timeout&&System.currentTimeMillis()-start<4000,"SSH timeout closes its channel");
            AtomicBoolean cancelled=new AtomicBoolean();ScheduledExecutorService t=Executors.newSingleThreadScheduledExecutor();t.schedule(()->cancelled.set(true),300,TimeUnit.MILLISECONDS);
            boolean stopped=false;try{ssh.run("sleep 8","",line->{},cancelled,5000);}catch(CancellationException e){stopped=true;}finally{t.shutdownNow();}
            check(stopped,"SSH cancellation interrupts an active channel");
            check(ssh.connected(),"cancelling a task preserves SSH connection");
            boolean denied=false;
            try{ssh.run("echo permission-denied >&2; exit 1","",line->{},new AtomicBoolean(),3000);}catch(Exception e){denied=true;}
            StringBuilder alive=new StringBuilder();ssh.run("printf alive","",alive::append,new AtomicBoolean(),3000);
            check(denied&&ssh.connected()&&alive.toString().equals("alive"),"remote permission failure does not disconnect transport");
        }
    }
    static void recovery(String root)throws Exception{
        CountDownLatch connected=new CountDownLatch(1), recovered=new CountDownLatch(1), rejected=new CountDownLatch(1);
        AtomicInteger runs=new AtomicInteger();AtomicBoolean leaked=new AtomicBoolean();
        PocketEngine engine=new PocketEngine(new PocketEngine.Host(){
            public String asset(String name){return "printf 'ORCA_OS|Darwin\\nORCA_HOME|/tmp\\nORCA_DETECTION_DONE\\n'";}
            public boolean trust(String host,String fp){return true;}
            public void event(String type,JSONObject data){
                if(type.equals("connected")){if(data.toString().contains("password"))leaked.set(true);if(data.optBoolean("recovered"))recovered.countDown();else connected.countDown();}
                if(type.equals("rejected"))rejected.countDown();if(type.equals("run"))runs.incrementAndGet();
            }
        });
        try{
            engine.connect(new JSONObject().put("host","127.0.0.1").put("port",22992).put("user",System.getProperty("user.name")).put("password",""),Files.readAllBytes(Path.of(root,".toolchain/ssh-test/client")));
            check(connected.await(15,TimeUnit.SECONDS),"engine connects to real SSH");
            while(engine.busy.get())Thread.sleep(20);
            engine.ssh.disconnect();
            engine.send(new JSONObject().put("messageId","unsent").put("prompt","must not execute"));
            check(rejected.await(2,TimeUnit.SECONDS),"offline send returns unsent message to UI");
            check(recovered.await(15,TimeUnit.SECONDS)&&engine.ssh.connected(),"dropped SSH reconnects and refreshes agent inventory");
            check(runs.get()==0&&!leaked.get(),"reconnect never replays task or emits credentials");
            while(engine.busy.get())Thread.sleep(20);
            engine.disconnect();engine.checkConnection();Thread.sleep(100);
            check(!engine.ssh.connected(),"explicit disconnect disables automatic reconnect");
        }finally{engine.close();}
    }
    public static void main(String[] args)throws Exception{tests();if(args.length>0){ssh(args[0]);recovery(args[0]);}System.out.println("ALL "+passed+" CHECKS PASSED");}
}
