package dev.orca.pocket;
import org.json.*;
import java.io.*;
import java.util.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public final class InteractionTest {
 static List<JSONObject> events=new CopyOnWriteArrayList<>();
 static Interactions interactions=new Interactions((t,j)->{try{events.add(new JSONObject(j.toString()).put("event",t));}catch(Exception e){throw new RuntimeException(e);}});
 static Attachments files=new Attachments((t,j)->events.add(j));
 static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);System.out.println("PASS "+label);}
 static JSONObject last(){return events.get(events.size()-1);}
 static JSONObject rpc(String method,JSONObject params){return new JSONObject().put("method",method).put("params",params);}
 static void fixtures()throws Exception{
  AtomicInteger count=new AtomicInteger();String token=interactions.add("run",new JSONObject(),a->count.incrementAndGet());
  interactions.respond(token,new JSONObject());try{interactions.respond(token,new JSONObject());throw new AssertionError();}catch(IllegalStateException expected){}
  check(count.get()==1,"double tap cannot repeat action");
  token=interactions.add("run",new JSONObject(),a->count.incrementAndGet());interactions.clear("run");try{interactions.respond(token,new JSONObject());throw new AssertionError();}catch(IllegalStateException expected){}
  check(count.get()==1,"ended turn invalidates action capabilities");
  events.clear();files.scan("run","[图片](</tmp/image space.png>) [bad](javascript:evil) [web](https://example.com/x) [remote](file://evil/tmp/a)","host");
  check(events.size()==1&&last().getString("name").equals("image space.png"),"only explicit local links accepted; remote authority and active URLs rejected");
  files.scan("run","[图片](/tmp/image space.png)","host");check(events.size()==1,"attachment references deduplicated per run");
  EventDecoder decoder=new EventDecoder((t,s)->{});ByteArrayOutputStream output=new ByteArrayOutputStream();CodexSession session=new CodexSession("run","hello","/tmp",false,"host",decoder,interactions,files);session.start(output);
  session.accept("{\"id\":1,\"result\":{}}");check(output.toString("UTF-8").contains("on-request")&&output.toString("UTF-8").contains("read-only"),"interactive session preserves sandbox and routes approval to user");
  session.accept("{\"id\":2,\"result\":{\"thread\":{\"id\":\"thread\"}}}");
  session.accept(rpc("item/commandExecution/requestApproval",new JSONObject().put("threadId","other")).put("id",44).toString());check(!last().optString("title").contains("Codex"),"foreign-thread approval ignored");
  session.accept(rpc("item/commandExecution/requestApproval",new JSONObject().put("threadId","thread").put("command","printf harmless")).put("id",45).toString());
  token=last().getString("token");interactions.respond(token,new JSONObject().put("choice","decline"));check(output.toString("UTF-8").contains("\"decision\":\"decline\""),"phone rejection sent with original RPC request id");
  session.accept(rpc("item/commandExecution/requestApproval",new JSONObject().put("threadId","thread").put("command","printf harmless")).put("id",46).toString());token=last().getString("token");
  session.accept(rpc("serverRequest/resolved",new JSONObject().put("threadId","thread").put("requestId",46)).toString());
  try{interactions.respond(token,new JSONObject());throw new AssertionError();}catch(IllegalStateException expected){}check(true,"server resolution invalidates phone action");
  JSONArray questions=new JSONArray().put(new JSONObject().put("id","q").put("header","偏好").put("question","选哪个？"));
  session.accept(rpc("item/tool/requestUserInput",new JSONObject().put("threadId","thread").put("questions",questions)).put("id",47).toString());interactions.respond(last().getString("token"),new JSONObject().put("answers",new JSONObject().put("q","中文回答")));
  check(output.toString("UTF-8").contains("中文回答"),"free-text question returns structured answers");
  check(session.accept(rpc("turn/completed",new JSONObject().put("threadId","thread").put("turn",new JSONObject().put("status","completed"))).toString())&&decoder.completed,"turn completion closes protocol deliberately");
 }
 static void acpFixtures()throws Exception{
  EventDecoder decoder=new EventDecoder((t,s)->{});ByteArrayOutputStream output=new ByteArrayOutputStream();AcpSession acp=new AcpSession("acp","hello","/tmp","host","WorkBuddy",decoder,interactions,files);
  acp.start(output);acp.accept("{\"id\":1,\"result\":{\"protocolVersion\":1}}");acp.accept("{\"id\":2,\"result\":{\"sessionId\":\"s\"}}");
  int cardsBefore=events.size();
  acp.accept(rpc("session/update",new JSONObject().put("update",new JSONObject().put("sessionUpdate","agent_message_chunk").put("content",new JSONObject().put("type","text").put("text","FOREIGN_NO_ID")))).toString());
  acp.accept(rpc("session/update",new JSONObject().put("sessionId","other").put("update",new JSONObject().put("sessionUpdate","agent_message_chunk").put("content",new JSONObject().put("type","text").put("text","FOREIGN_SESSION")))).toString());
  acp.accept(rpc("session/request_permission",new JSONObject()).put("id","unscoped-request").toString());
  check(decoder.text().isEmpty()&&events.size()==cardsBefore,"ACP missing and foreign session identities cannot reach phone messages or approvals");
  JSONArray options=new JSONArray().put(new JSONObject().put("optionId","allow").put("name","仅允许一次").put("kind","allow_once")).put(new JSONObject().put("optionId","always").put("name","总是允许").put("kind","allow_always")).put(new JSONObject().put("optionId","deny").put("name","拒绝").put("kind","reject_once"));
  acp.accept(rpc("session/request_permission",new JSONObject().put("sessionId","s").put("options",options).put("toolCall",new JSONObject().put("title","测试操作"))).put("id","req").toString());check(last().getJSONArray("options").length()==2,"ACP exposes one-shot choices only");interactions.respond(last().getString("token"),new JSONObject().put("choice","deny"));check(output.toString("UTF-8").contains("\"optionId\":\"deny\""),"ACP phone selection returns original permission identifier");
  events.clear();files.clear();
  JSONObject image=new JSONObject().put("type","image_gen_tool_result").put("status","completed").put("images",new JSONArray().put(new JSONObject().put("localPath","/tmp/cli-result.png")));
  JSONObject update=new JSONObject().put("sessionUpdate","tool_call_update").put("toolCallId","image-call").put("status","completed").put("rawOutput",image).put("content",new JSONArray().put(new JSONObject().put("type","content").put("content",new JSONObject().put("type","text").put("text",image.toString()))));
  acp.accept(rpc("session/update",new JSONObject().put("sessionId","other").put("update",update)).toString());check(events.isEmpty(),"foreign ACP image result rejected");
  acp.accept(rpc("session/update",new JSONObject().put("sessionId","s").put("update",new JSONObject(update.toString()).put("status","failed"))).toString());check(events.isEmpty(),"failed ACP generation cannot create local image capability");
  acp.accept(rpc("session/update",new JSONObject().put("sessionId","s").put("update",update)).toString());check(events.size()==1&&last().optBoolean("preview")&&last().getString("name").equals("cli-result.png"),"ACP completed ImageGen raw output and text content produce one auto-preview attachment");
  files.clear();events.clear();update.remove("rawOutput");acp.accept(rpc("session/update",new JSONObject().put("sessionId","s").put("update",update)).toString());check(events.size()==1&&last().optBoolean("preview"),"ACP text-only ImageGen result also reaches phone attachment decoder");
  decoder=new EventDecoder((t,st)->{});acp=new AcpSession("auth","hello","/tmp","host","WorkBuddy",decoder,interactions,files);acp.start(new ByteArrayOutputStream());
  JSONObject refusal=new JSONObject().put("stopReason","refusal").put("_meta",new JSONObject().put("codebuddy.ai/errorMessage",new JSONObject().put("code",-32000).put("message","Authentication required").toString()));
  try{acp.accept(new JSONObject().put("id",3).put("result",refusal).toString());throw new AssertionError();}catch(IOException error){check(PocketEngine.canFallback(decoder,error),"observed WorkBuddy ACP authentication refusal permits desktop before work");}
  decoder=new EventDecoder((t,st)->{});acp=new AcpSession("auth","hello","/tmp","host","DSH",decoder,interactions,files);acp.start(new ByteArrayOutputStream());
  try{acp.accept(new JSONObject().put("id",3).put("error",new JSONObject().put("code",-32603).put("message","Internal error: turn failed: llm-deepseek: no API key for provider route \"deepseek-official\"")).toString());throw new AssertionError();}catch(IOException error){check(PocketEngine.canFallback(decoder,error),"observed DSH ACP missing provider credential permits desktop before work");}
 }
 static void live()throws Exception{
  Path dir=Files.createTempDirectory("orca-interaction-live-");
  EventDecoder decoder=new EventDecoder((t,s)->{});
  AtomicReference<Throwable> error=new AtomicReference<>();AtomicBoolean approved=new AtomicBoolean();
  Interactions real=new Interactions((type,card)->{
   if(!type.equals("interaction"))return;
   System.out.println("LIVE confirmation: "+card.optString("title")+"\n"+card.optString("detail"));
   // Only this test's harmless fixed printf is authorized. Never approve arbitrary model commands.
   new Thread(()->{try{String detail=card.optString("detail");String command=detail.substring(detail.lastIndexOf('\n')+1).trim();String choice=(command.equals("printf ORCA_APPROVAL_OK")||command.equals("/bin/zsh -lc 'printf ORCA_APPROVAL_OK'")||command.equals("/bin/zsh -lc \"printf ORCA_APPROVAL_OK\""))?"accept":"decline";Thread.sleep(100);interactions.respond(card.getString("token"),new JSONObject().put("choice",choice));if(choice.equals("accept"))approved.set(true);}catch(Throwable e){error.set(e);}}).start();
  });
  interactions=real;
  String prompt="This is a harmless permission integration test. Use exec_command exactly once with cmd exactly `printf ORCA_APPROVAL_OK`, sandbox_permissions `require_escalated`, justification `May I run this harmless printf to verify the phone approval?`. Do not use any other commands or tools. After the command result, reply exactly ORCA_APPROVAL_OK.";
  CodexSession protocol=new CodexSession("live",prompt,dir.toString(),false,"host",decoder,real,files);
  Process process=new ProcessBuilder("codex","app-server","--listen","stdio://").redirectError(dir.resolve("stderr.log").toFile()).start();
  ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor();timer.schedule(process::destroyForcibly,100,TimeUnit.SECONDS);
  try{protocol.start(process.getOutputStream());try(BufferedReader reader=new BufferedReader(new InputStreamReader(process.getInputStream(),"UTF-8"))){String line;while((line=reader.readLine())!=null){if(protocol.accept(line))break;}}
    check(error.get()==null,"live response callback had no errors");check(decoder.completed,"installed Codex completed app-server turn");check(approved.get(),"real Codex approval received and exact harmless command approved");check(decoder.text().contains("ORCA_APPROVAL_OK"),"Codex receiving-side reply confirms command result");
  }finally{process.destroy();timer.shutdownNow();real.clear(null);}
 }
 public static void main(String[] args)throws Exception{fixtures();acpFixtures();if(args.length>0&&args[0].equals("live"))live();}
}
