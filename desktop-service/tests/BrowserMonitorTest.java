package dev.orca.pocket;
import com.sun.net.httpserver.*;import java.nio.file.*;import java.util.*;import java.util.concurrent.atomic.*;import java.lang.reflect.*;import org.json.*;

public final class BrowserMonitorTest {
 public static void main(String[] args)throws Exception{
  Path parent=Files.createTempDirectory("agent-control-browser-test-");Path root=parent.resolve("data");TaskStore.privateDirectory(root);
  AtomicReference<String> body=new AtomicReference<>("original page");AtomicBoolean fail=new AtomicBoolean();AtomicInteger reads=new AtomicInteger();
  HttpServer server=HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);String token="fixture-token-with-at-least-32-characters";
  server.createContext("/",exchange->{try{
   if(!("Bearer "+token).equals(exchange.getRequestHeaders().getFirst("Authorization"))){exchange.sendResponseHeaders(401,0);exchange.close();return;}
   String path=exchange.getRequestURI().getPath();byte[] response;
   if(fail.get()){response="fixture failure".getBytes();exchange.sendResponseHeaders(503,response.length);}
   else if(path.endsWith("/read")){reads.incrementAndGet();response=new JSONObject().put("url","https://example.com").put("title","Fixture page").put("text",body.get()).toString().getBytes();exchange.sendResponseHeaders(200,response.length);}
   else if(path.endsWith("/screenshot")){response=Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jFssAAAAASUVORK5CYII=");exchange.sendResponseHeaders(200,response.length);}
   else{response="{}".getBytes();exchange.sendResponseHeaders(200,response.length);}
   exchange.getResponseBody().write(response);exchange.close();
  }catch(Exception e){throw new RuntimeException(e);}});server.start();
  try{
   TaskStore.atomic(parent.resolve("browser.json"),new JSONObject().put("url","http://127.0.0.1:"+server.getAddress().getPort()).put("token",token).toString().getBytes());
   BrowserBridge browser=new BrowserBridge(root);JSONObject page=browser.request(new JSONObject().put("op","browserOpen").put("url","https://example.com")).getJSONObject("browser");assert page.getString("text").equals("original page");assert page.getString("preview").startsWith("data:image/png;base64,");
   browser.request(new JSONObject().put("op","browserInput").put("sessionId",page.getString("sessionId")).put("input",new JSONObject().put("type","click").put("x",20).put("y",30)));
   TaskService service=new TaskService(root);JSONObject input=new JSONObject().put("messageId","watch_1").put("conversationId","watch_conversation").put("kind","monitor").put("prompt","Watch fixture").put("url","https://example.com").put("intervalMinutes",1);
   service.request(new JSONObject().put("op","submit").put("request",input));Field field=TaskService.class.getDeclaredField("store");field.setAccessible(true);TaskStore store=(TaskStore)field.get(service);Method check=TaskService.class.getDeclaredMethod("checkMonitors");check.setAccessible(true);
   check.invoke(service);assert store.get("watch_1").getJSONArray("events").length()==0;
   body.set("updated page");store.save(store.get("watch_1").put("nextRunAt",0));check.invoke(service);assert store.get("watch_1").getJSONArray("events").length()==1;
   store.save(store.get("watch_1").put("nextRunAt",0));check.invoke(service);assert store.get("watch_1").getJSONArray("events").length()==1;
   service.request(new JSONObject().put("op","control").put("taskId","watch_1").put("action","pause"));int count=reads.get();check.invoke(service);assert reads.get()==count;
   service.request(new JSONObject().put("op","control").put("taskId","watch_1").put("action","resume"));fail.set(true);for(int i=0;i<5;i++){store.save(store.get("watch_1").put("nextRunAt",0));check.invoke(service);}assert store.get("watch_1").getString("status").equals("paused");assert store.get("watch_1").getInt("failures")==5;
   TaskStore.atomic(parent.resolve("browser.json"),new JSONObject().put("url","http://192.168.1.1:8790").put("token",token).toString().getBytes());
   try{browser.request(new JSONObject().put("op","browserRead").put("sessionId",page.getString("sessionId")));throw new AssertionError("non-loopback worker accepted");}catch(IllegalStateException expected){}
   System.out.println("Browser/monitor: authenticated worker, real response/screenshot forwarding, input routing, baseline/change/dedup, pause/resume, failure backoff and worker boundary passed");
  }finally{server.stop(0);}
 }
}
