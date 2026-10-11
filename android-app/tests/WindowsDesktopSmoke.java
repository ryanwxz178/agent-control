package dev.orca.pocket;
import java.nio.file.*;import java.nio.charset.StandardCharsets;import java.util.*;import java.util.concurrent.atomic.AtomicBoolean;import org.json.*;
/** Real desktop test: creates one acknowledgement chat, never retries an uncertain send. */
public final class WindowsDesktopSmoke {
 public static void main(String[] args)throws Exception {
  if(args.length!=7)throw new IllegalArgumentException("host port user keyFile pinnedFingerprint app marker required");
  try(SshTransport ssh=new SshTransport()) {
   ssh.connect(args[0],Integer.parseInt(args[1]),args[2],"",Files.readAllBytes(Paths.get(args[3])),(h,f)->f.equals(args[4]));
   String prompt="这是 Orca Pocket Windows 云电脑链路验收。请不要使用工具，仅回复："+args[6];
   String request=new JSONObject().put("app",args[5]).put("prompt",prompt).toString();
   String script=new String(Files.readAllBytes(Paths.get("android-app/app/src/main/assets/desktop-windows-client.ps1")),StandardCharsets.UTF_8).replace("__REQUEST_BASE64__",Base64.getEncoder().encodeToString(request.getBytes(StandardCharsets.UTF_8)));
   AtomicBoolean complete=new AtomicBoolean();
   ssh.runWindowsScript(script,line->{System.out.println(line);if(line.startsWith("{")){JSONObject event=new JSONObject(line);if(event.optString("type").equals("error"))throw new java.io.IOException(event.optString("message"));if(event.optString("type").equals("desktop.done")){if(!event.optString("text").contains(args[6]))throw new AssertionError("Wrong reply marker");complete.set(true);}}},new AtomicBoolean(),650000);
   if(!complete.get())throw new AssertionError("No receiving-side completion");System.out.println("PASS actual "+args[5]+" receipt, reply and completion");
  }
 }
}
