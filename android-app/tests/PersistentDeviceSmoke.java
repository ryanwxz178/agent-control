package dev.orca.pocket.tests;
import android.app.*;import android.content.*;import android.os.*;import android.webkit.WebView;
import java.io.*;import java.lang.reflect.*;import java.util.concurrent.*;
import org.json.*;

public final class PersistentDeviceSmoke extends Instrumentation {
    private WebView web;private Bundle options;
    @Override public void onCreate(Bundle args){super.onCreate(args);options=args;start();}
    private String js(String code)throws Exception{CountDownLatch latch=new CountDownLatch(1);String[] result={null};runOnMainSync(()->web.evaluateJavascript(code,value->{result[0]=value;latch.countDown();}));if(!latch.await(10,TimeUnit.SECONDS))throw new Exception("JS timeout");return result[0];}
    private void waitFor(String expression,int seconds)throws Exception{long until=System.currentTimeMillis()+seconds*1000L;while(System.currentTimeMillis()<until){if("true".equals(js(expression)))return;Thread.sleep(250);}throw new Exception("Timeout "+expression+"; "+js("document.body.innerText.slice(-2000)"));}
    private byte[] asset(String name)throws Exception{try(InputStream in=getContext().getAssets().open(name)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))>0)out.write(bytes,0,n);return out.toByteArray();}}
    private Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private Activity launch()throws Exception{Activity a=startActivitySync(new Intent().setClassName("dev.orca.pocket","dev.orca.pocket.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));web=(WebView)field(a,"web");waitFor("typeof window.receive==='function'",20);Thread.sleep(800);return a;}
    private void connect(Activity activity)throws Exception{
        Field key=activity.getClass().getDeclaredField("privateKey");key.setAccessible(true);key.set(activity,asset("client"));
        Object vault=field(activity,"store");Method put=vault.getClass().getDeclaredMethod("put",String.class,String.class);put.setAccessible(true);put.invoke(vault,"host:[10.0.2.2]:22992",new String(asset("fingerprint"),"UTF-8").trim());
        boolean direct="teleagent-offscreen-direct".equals(options.getString("mode"))||"teleagent-image-direct".equals(options.getString("mode"))||"teleagent-text-single-direct".equals(options.getString("mode"))||"teleagent-multi-direct".equals(options.getString("mode"))||"teleagent-text-direct".equals(options.getString("mode"))||"teleagent-text-inline-direct".equals(options.getString("mode"));
        String directory="java-discovery".equals(options.getString("mode"))?"/Users/ryan":options.getString("directory"),java="java-discovery".equals(options.getString("mode"))?"":options.getString("java");
        js("document.getElementById('connectionButton').click();document.getElementById('host').value='10.0.2.2';document.getElementById('port').value='22992';document.getElementById('user').value='ryan';document.getElementById('directory').value="+JSONObject.quote(directory)+";document.getElementById('serviceJava').value="+JSONObject.quote(java)+";document.getElementById('taskService').checked="+(!direct)+";document.getElementById('connect').click();");
        waitFor(direct?"connected":"connected&&serviceReady",100);
    }
    private void capture(String name)throws Exception{android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();File file=new File(getTargetContext().getExternalFilesDir(null),name+".png");try(FileOutputStream out=new FileOutputStream(file)){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}shot.recycle();if((name.endsWith("0512")||name.endsWith("0513")||name.endsWith("0514"))){String data=new JSONArray("["+js("JSON.stringify(state.messages)")+"]").getString(0);try(FileOutputStream out=new FileOutputStream(new File(getTargetContext().getExternalFilesDir(null),name+".json"))){out.write(data.getBytes("UTF-8"));}}}
    @Override public void onStart(){Bundle report=new Bundle();try{
        Activity activity=launch();
        if("teleagent-mobile-ui".equals(options.getString("mode"))){
            Object interactions=field(field(activity,"engine"),"interactions");
            Class<?> reply=Class.forName("dev.orca.pocket.Interactions$Reply");
            java.util.concurrent.atomic.AtomicInteger count=new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<String> answer=new java.util.concurrent.atomic.AtomicReference<>("");
            Object callback=Proxy.newProxyInstance(reply.getClassLoader(),new Class<?>[]{reply},(proxy,method,args)->{if(method.getName().equals("send")){count.incrementAndGet();answer.set(((JSONObject)args[0]).toString());}return null;});
            Method add=interactions.getClass().getDeclaredMethod("add",String.class,JSONObject.class,reply);add.setAccessible(true);
            js("startConversation();state.selected='teleagent';updateSelection();receive('run',{id:'mobile-ui',agent:'TeleAgent',label:'回复'});");
            JSONArray choices=new JSONArray();for(String label:new String[]{"圆形 圆润的边缘，用于头像和图标","矩形 适合海报和横向画面","其他（自定义输入） 填写你需要的形状","下一题（1/2 已回答）"})choices.put(new JSONObject().put("id",label).put("label",label));
            JSONObject card=new JSONObject().put("title","TeleAgent 等待确认").put("desktop",true).put("kind","approval").put("detail","交互式问答 · 需要确认2个问题\n你希望画面是什么形状？").put("options",choices).put("inputs",new JSONArray().put(new JSONObject().put("id","shape").put("label","自定义回答（必填）").put("required",true).put("value","")));
            add.invoke(interactions,"mobile-ui",card,callback);
            waitFor("!document.getElementById('answerDock').hidden&&document.getElementById('answerContinue').disabled",10);
            js("document.querySelector('.interaction-text-input').scrollIntoView({block:'center'})");Thread.sleep(700);capture("teleagent-mobile-ui-empty-0516");
            int beforeKeyboard=Integer.parseInt(js("innerHeight"));
            JSONArray position=new JSONArray(js("(()=>{const r=document.querySelector('.interaction-text-input').getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2]})()"));
            long tap=SystemClock.uptimeMillis();
            android.view.MotionEvent down=android.view.MotionEvent.obtain(tap,tap,android.view.MotionEvent.ACTION_DOWN,(float)position.getDouble(0)*getTargetContext().getResources().getDisplayMetrics().density,(float)position.getDouble(1)*getTargetContext().getResources().getDisplayMetrics().density+activity.getWindow().getDecorView().getRootWindowInsets().getSystemWindowInsetTop(),0);
            getUiAutomation().injectInputEvent(down,true);down.recycle();
            android.view.MotionEvent up=android.view.MotionEvent.obtain(tap,tap+100,android.view.MotionEvent.ACTION_UP,(float)position.getDouble(0)*getTargetContext().getResources().getDisplayMetrics().density,(float)position.getDouble(1)*getTargetContext().getResources().getDisplayMetrics().density+activity.getWindow().getDecorView().getRootWindowInsets().getSystemWindowInsetTop(),0);
            getUiAutomation().injectInputEvent(up,true);up.recycle();
            waitFor("innerHeight<"+beforeKeyboard,10);
            waitFor("document.getElementById('answerContinue').getBoundingClientRect().bottom<=innerHeight",10);
            waitFor("(()=>{const r=document.querySelector('.interaction-text-input').getBoundingClientRect(),c=document.getElementById('chat').getBoundingClientRect();return r.top>=c.top&&r.bottom<=c.bottom+2})()",10);
            Thread.sleep(500);capture("teleagent-mobile-ui-keyboard-0516");
            js("(()=>{const input=document.querySelector('.interaction-text-input');input.value='六边形';input.dispatchEvent(new Event('input',{bubbles:true}));})()");
            waitFor("!document.getElementById('answerContinue').disabled",10);Thread.sleep(700);capture("teleagent-mobile-ui-ready-0516");
            js("document.getElementById('answerContinue').click();document.getElementById('answerContinue').click();");
            long until=System.currentTimeMillis()+5000;while(count.get()==0&&System.currentTimeMillis()<until)Thread.sleep(100);
            JSONObject result=new JSONObject(answer.get());if(count.get()!=1||!result.getString("choice").equals("__desktop_text__")||!result.getJSONObject("textValues").getString("shape").equals("六边形"))throw new Exception("Native answer callback mismatch: "+answer.get());
            report.putString("result","PASS: native WebView fixed answer dock, required validation, exact answer callback and one submission");finish(Activity.RESULT_OK,report);return;
        }
        if("java-discovery".equals(options.getString("mode"))){
            connect(activity);
            waitFor("connected&&serviceReady&&document.getElementById('serviceBanner').hidden",10);Thread.sleep(1000);capture("java-discovery-0515");
            report.putString("result","PASS: blank Java path discovers bundled runtime over SSH and starts persistent service");finish(Activity.RESULT_OK,report);return;
        }
        if("workbuddy-reported-card".equals(options.getString("mode"))){
            JSONObject event=new JSONObject(new String(asset("reported-card.json"),"UTF-8"));
            Object engine=field(activity,"engine"),interactions=field(engine,"interactions");
            Class<?> reply=Class.forName("dev.orca.pocket.Interactions$Reply");
            java.util.concurrent.atomic.AtomicInteger count=new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.atomic.AtomicReference<String> selected=new java.util.concurrent.atomic.AtomicReference<>("");
            Object callback=Proxy.newProxyInstance(reply.getClassLoader(),new Class<?>[]{reply},(proxy,method,args)->{if(method.getName().equals("send")){count.incrementAndGet();selected.set(((JSONObject)args[0]).getString("choice"));}return null;});
            Method add=interactions.getClass().getDeclaredMethod("add",String.class,JSONObject.class,reply);add.setAccessible(true);
            js("startConversation();receive('run',{id:'reported-card',agent:'WorkBuddy',label:'回复'});");
            JSONObject card=new JSONObject().put("title","WorkBuddy 等待确认").put("kind","approval").put("desktop",true).put("detail",event.getString("detail")).put("options",event.getJSONArray("options"));
            String token=(String)add.invoke(interactions,"reported-card",card,callback);
            waitFor("state.messages.some(m=>m.interactions?.some(i=>i.state==='pending'&&i.detail.includes('这是唯一一次确认')))",10);
            waitFor("Array.from(document.querySelectorAll('.interaction-actions button')).some(b=>!b.disabled&&b.textContent==='3 赛博朋克城市')",10);
            js("document.querySelector('.interaction:not(.closed)').scrollIntoView({block:'center'});");capture("workbuddy-reported-card-058");
            js("Array.from(document.querySelectorAll('.interaction-actions button')).find(b=>!b.disabled&&b.textContent==='3 赛博朋克城市').click();");
            waitFor("state.messages.some(m=>m.interactions?.some(i=>i.state==='closed'))",10);
            if(count.get()!=1||!selected.get().equals("3 赛博朋克城市"))throw new Exception("Phone answer was not delivered exactly once");
            Method respond=interactions.getClass().getDeclaredMethod("respond",String.class,JSONObject.class);respond.setAccessible(true);
            try{respond.invoke(interactions,token,new JSONObject().put("choice","3 赛博朋克城市"));throw new Exception("Duplicate response accepted");}catch(InvocationTargetException expected){if(!expected.getCause().getMessage().contains("失效"))throw expected;}
            if(count.get()!=1)throw new Exception("Duplicate native callback");
            report.putString("stream","PASS reported WorkBuddy AX fixture -> production adapter event -> native Interactions -> installed Android WebView card -> phone selection -> native callback exactly once; no live PC option clicked.\n");finish(Activity.RESULT_OK,report);return;
        }
        connect(activity);
        if("teleagent-existing-card".equals(options.getString("mode"))){
            Object engine=field(activity,"engine");((JSONObject)field(engine,"profile")).put("taskService",false);
            Object ssh=field(engine,"ssh");Class<?> lineType=Class.forName("dev.orca.pocket.SshTransport$Line");
            Method run=ssh.getClass().getMethod("run",String.class,String.class,lineType,java.util.concurrent.atomic.AtomicBoolean.class,long.class);run.setAccessible(true);
            java.util.concurrent.atomic.AtomicReference<JSONObject> current=new java.util.concurrent.atomic.AtomicReference<>();
            Object lines=Proxy.newProxyInstance(lineType.getClassLoader(),new Class<?>[]{lineType},(proxy,method,args)->{if(method.getName().equals("accept")&&((String)args[0]).startsWith("{"))current.set(new JSONObject((String)args[0]));return null;});
            String script=new String(asset("live-card.js"),"UTF-8");
            run.invoke(ssh,"/usr/bin/osascript -l JavaScript -",script,lines,new java.util.concurrent.atomic.AtomicBoolean(),20000L);
            JSONObject event=current.get();if(event==null)throw new Exception("No live TeleAgent card");
            Class<?> agentType=Class.forName("dev.orca.pocket.Agent");Object agent=agentType.getConstructor(String.class,String.class,String.class,java.util.List.class,boolean.class).newInstance("teleagent","TeleAgent","desktop",java.util.Arrays.asList("TeleAgent"),true);
            Method card=engine.getClass().getDeclaredMethod("desktopInteraction",String.class,agentType,JSONObject.class,String.class);card.setAccessible(true);
            String prompt="帮我生成一张图片，只让我确认一次交互后生成\n\n[Agent Control request b193195c-0d8e-4efb-9f2e-b1f7a6e99dd3]";
            js("startConversation();state.selected='teleagent';state.profile.accessMode='desktop';updateSelection();receive('run',{id:'teleagent-live-card',agent:'TeleAgent',label:'回复'});");
            card.invoke(engine,"teleagent-live-card",agent,event,prompt);
            waitFor("Array.from(document.querySelectorAll('.interaction-actions button')).some(b=>!b.disabled&&b.textContent.startsWith('商务海报'))",15);
            js("document.querySelector('.interaction:not(.closed)').scrollIntoView({block:'center'});");Thread.sleep(500);capture("teleagent-native-card-059");
            js("Array.from(document.querySelectorAll('.interaction-actions button')).find(b=>!b.disabled&&b.textContent.startsWith('商务海报')).click();");
            waitFor("state.messages.some(m=>m.interactions?.some(i=>i.state==='closed'))",25);
            current.set(null);run.invoke(ssh,"/usr/bin/osascript -l JavaScript -",script,lines,new java.util.concurrent.atomic.AtomicBoolean(),20000L);
            JSONObject after=current.get();if(after==null||after.getString("fingerprint").equals(event.getString("fingerprint")))throw new Exception("Desktop selection did not change");
            boolean counted=false;for(int i=0;i<after.getJSONArray("options").length();i++)if(after.getJSONArray("options").getJSONObject(i).getString("label").contains("1/1 已回答"))counted=true;if(!counted)throw new Exception("Desktop answer count did not update: "+after);
            card.invoke(engine,"teleagent-live-card",agent,after,prompt);
            waitFor("Array.from(document.querySelectorAll('.interaction-actions button')).some(b=>!b.disabled&&b.textContent.includes('1/1 已回答'))",15);Thread.sleep(500);capture("teleagent-native-selected-059");
            report.putString("stream","PASS live request-owned TeleAgent AX -> SSH -> installed Android card -> phone radio choice -> production SSH confirm -> changed desktop answer count -> refreshed phone submit control. Existing question remains unsubmitted.\n");finish(Activity.RESULT_OK,report);return;
        }
        if("workbuddy-image-replay".equals(options.getString("mode"))){
            JSONObject image=new JSONObject(new String(asset("image-event.json"),"UTF-8"));
            Object engine=field(activity,"engine"),files=field(engine,"attachments");Method key=engine.getClass().getDeclaredMethod("computerKey");key.setAccessible(true);String host=(String)key.invoke(engine);
            for(String mode:new String[]{"desktop","cli"}){
                String run="image-replay-"+mode;
                js("startConversation();state.selected='workbuddy';state.profile.accessMode="+JSONObject.quote(mode)+";updateSelection();receive('run',{id:"+JSONObject.quote(run)+",agent:'WorkBuddy',label:"+JSONObject.quote(mode)+"});receive('update',{id:"+JSONObject.quote(run)+",text:'已有生成图片回传验证',status:'已完成'});");
                if(mode.equals("desktop")){Method content=files.getClass().getDeclaredMethod("content",String.class,JSONObject.class,String.class);content.setAccessible(true);content.invoke(files,run,image,host);}
                else{
                    Class<?> acpType=Class.forName("dev.orca.pocket.AcpSession"),decoderType=Class.forName("dev.orca.pocket.EventDecoder");
                    Class<?> eventsType=Class.forName("dev.orca.pocket.EventDecoder$Sink");
                    Object callback=java.lang.reflect.Proxy.newProxyInstance(eventsType.getClassLoader(),new Class<?>[]{eventsType},(proxy,method,args)->null);
                    Constructor<?> dc=decoderType.getDeclaredConstructor(eventsType);dc.setAccessible(true);Object decoder=dc.newInstance(callback);
                    Constructor<?> ac=acpType.getDeclaredConstructors()[0];ac.setAccessible(true);Object acp=ac.newInstance(run,"replay","/tmp",host,"WorkBuddy",decoder,field(engine,"interactions"),files);
                    Method start=acpType.getMethod("start",OutputStream.class),accept=acpType.getMethod("accept",String.class);start.setAccessible(true);accept.setAccessible(true);start.invoke(acp,new ByteArrayOutputStream());
                    accept.invoke(acp,"{\"id\":1,\"result\":{\"protocolVersion\":1}}");accept.invoke(acp,"{\"id\":2,\"result\":{\"sessionId\":\"image-session\"}}");
                    JSONObject raw=new JSONObject().put("type","image_gen_tool_result").put("status","completed").put("images",new JSONArray().put(new JSONObject().put("localPath",image.getString("path"))));
                    JSONObject update=new JSONObject().put("sessionUpdate","tool_call_update").put("status","completed").put("toolCallId","generated").put("rawOutput",raw);
                    accept.invoke(acp,new JSONObject().put("method","session/update").put("params",new JSONObject().put("sessionId","image-session").put("update",update)).toString());
                }
                waitFor("!!document.querySelector('.attachment-thumbnail')&&document.querySelector('.attachment-thumbnail').naturalWidth>0",30);Thread.sleep(1000);capture("workbuddy-image-"+mode+"-thumbnail");
                js("document.querySelector('.attachment-button').click()");waitFor("document.getElementById('attachmentViewer').open&&!document.getElementById('attachmentImage').hidden",10);Thread.sleep(1000);capture("workbuddy-image-"+mode+"-preview");
                java.util.Map<?,?> downloaded=(java.util.Map<?,?>)field(activity,"downloaded");byte[] original=(byte[])downloaded.values().toArray()[downloaded.size()-1];
                android.graphics.BitmapFactory.Options dimensions=new android.graphics.BitmapFactory.Options();dimensions.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeByteArray(original,0,original.length,dimensions);if(dimensions.outWidth!=1536||dimensions.outHeight!=1024)throw new Exception("Original dimensions wrong");
                Object ssh=field(engine,"ssh");Method download=ssh.getClass().getMethod("download",String.class,int.class);download.setAccessible(true);byte[] expected=(byte[])download.invoke(ssh,image.getString("path"),20*1024*1024);if(!java.util.Arrays.equals(expected,original))throw new Exception("Original image bytes differ");
                js("document.getElementById('attachmentViewer').close()");
            }
            report.putString("stream","PASS installed Android desktop store event and CLI ACP replay -> real SSH image download -> automatic thumbnail -> original 1536x1024 preview, source bytes matched.\n");finish(Activity.RESULT_OK,report);return;
        }
        if("teleagent-offscreen-direct".equals(options.getString("mode"))){
            String prompt="这是提交按钮遮挡回归测试。严格调用 question 工具一次，仅提出一道单选题，题目为选择测试方案，提供四个选项：蓝色、绿色、红色、黄色。每个选项必须有约六十字的详细说明，用于让选项卡片足够高以复现滚动遮挡；允许其他自定义输入。等待我选择其他并填写后，仅回复 AGENT_CONTROL_TELE_OFFSCREEN_OK，并复述自定义答案。不要生成图片，不要执行命令，不要修改文件，不要新增问题。";
            js("startConversation();state.selected='teleagent';state.profile.accessMode='desktop';updateSelection();document.getElementById('input').value="+JSONObject.quote(prompt)+";send();");
            waitFor("Array.from(document.querySelectorAll('.interaction-actions button')).some(b=>!b.disabled&&b.textContent.startsWith('其他'))",150);
            js("Array.from(document.querySelectorAll('.interaction-actions button')).find(b=>!b.disabled&&b.textContent.startsWith('其他')).click()");
            waitFor("!!document.querySelector('.interaction:not(.closed) .interaction-text-input[required]:not([disabled])')",30);
            Object engine=field(activity,"engine"),ssh=field(engine,"ssh");Class<?> lineType=Class.forName("dev.orca.pocket.SshTransport$Line");Method run=ssh.getClass().getMethod("run",String.class,String.class,lineType,java.util.concurrent.atomic.AtomicBoolean.class,long.class);run.setAccessible(true);
            java.util.concurrent.atomic.AtomicReference<JSONObject> result=new java.util.concurrent.atomic.AtomicReference<>();Object lines=Proxy.newProxyInstance(lineType.getClassLoader(),new Class<?>[]{lineType},(proxy,method,args)->{if(method.getName().equals("accept")&&((String)args[0]).startsWith("{"))result.set(new JSONObject((String)args[0]));return null;});
            run.invoke(ssh,"/usr/bin/osascript -l JavaScript -",new String(asset("live-card.js"),"UTF-8"),lines,new java.util.concurrent.atomic.AtomicBoolean(),20000L);
            if(result.get()==null||!result.get().optBoolean("clipped"))throw new Exception("Footer not actually clipped: "+result.get());
            js("(()=>{const i=document.querySelector('.interaction:not(.closed) .interaction-text-input');i.value='六边形';i.dispatchEvent(new Event('input'));i.scrollIntoView({block:'center'});})()");Thread.sleep(400);capture("teleagent-offscreen-before-0514");
            js("document.querySelector('.interaction:not(.closed) .interaction-text-save').click()");
            waitFor("state.messages.some(m=>m.status==='已完成'&&m.text.includes('AGENT_CONTROL_TELE_OFFSCREEN_OK')&&m.text.includes('六边形'))",150);
            js("document.querySelector('.message.assistant .content').scrollIntoView({block:'start'})");Thread.sleep(600);capture("teleagent-offscreen-final-0514");
            report.putString("stream","PASS installed Android -> real TeleAgent tall question -> bottom Other selection -> verified desktop submit AX height 0 -> phone fill-and-continue -> production AXScrollToVisible -> uniquely reacquired submit -> completed mobile reply contains 六边形.\n");finish(Activity.RESULT_OK,report);return;
        }
        if("teleagent-image-direct".equals(options.getString("mode"))){
            String path="/Users/ryan/image-20261010093859-01-407e0cd7.jpg";
            String prompt="这是图片附件回传测试。请直接调用 report_final_files 工具展示已经存在的文件："+path+"。完成后只回复 AGENT_CONTROL_TELE_IMAGE_OK。不要生成新图片，不要复制、修改或删除文件，不要调用 question。";
            js("startConversation();state.selected='teleagent';state.profile.accessMode='desktop';updateSelection();document.getElementById('input').value="+JSONObject.quote(prompt)+";send();");
            waitFor("state.messages.some(m=>m.status==='已完成'&&m.text.includes('AGENT_CONTROL_TELE_IMAGE_OK'))",150);
            waitFor("!!document.querySelector('.attachment-thumbnail')&&document.querySelector('.attachment-thumbnail').naturalWidth>0",30);
            js("document.querySelector('.attachment-button').scrollIntoView({block:'center'})");Thread.sleep(500);capture("teleagent-image-thumbnail-0513");
            js("document.querySelector('.attachment-button').click()");waitFor("document.getElementById('attachmentViewer').open&&!document.getElementById('attachmentImage').hidden",15);Thread.sleep(600);capture("teleagent-image-preview-0513");
            Object engine=field(activity,"engine"),ssh=field(engine,"ssh");Method download=ssh.getClass().getMethod("download",String.class,int.class);download.setAccessible(true);byte[] expected=(byte[])download.invoke(ssh,path,20*1024*1024);
            java.util.Map<?,?> downloaded=(java.util.Map<?,?>)field(activity,"downloaded");byte[] original=(byte[])downloaded.values().toArray()[downloaded.size()-1];if(!java.util.Arrays.equals(expected,original))throw new Exception("Preview cache differs from original image");
            js("document.getElementById('saveAttachment').click()");Thread.sleep(1000);
            android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();java.util.ArrayList<android.view.accessibility.AccessibilityNodeInfo> all=new java.util.ArrayList<>();all.add(root);for(int i=0;i<all.size();i++){android.view.accessibility.AccessibilityNodeInfo n=all.get(i);for(int j=0;j<n.getChildCount();j++){android.view.accessibility.AccessibilityNodeInfo child=n.getChild(j);if(child!=null)all.add(child);}}
            boolean renamed=false;for(android.view.accessibility.AccessibilityNodeInfo n:all){if(String.valueOf(n.getClassName()).endsWith("EditText")){Bundle args=new Bundle();args.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"agent-control-image-0513.jpg");renamed=n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,args);break;}}if(!renamed)throw new Exception("Cannot set actual Android save filename");
            boolean saved=false;for(android.view.accessibility.AccessibilityNodeInfo n:all){if("保存".contentEquals(n.getText()==null?"":n.getText())||"Save".contentEquals(n.getText()==null?"":n.getText())||"SAVE".contentEquals(n.getText()==null?"":n.getText())){saved=n.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);if(saved)break;}}if(!saved)throw new Exception("Cannot click actual Android Save");Thread.sleep(1500);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(android.os.ParcelFileDescriptor fd=getUiAutomation().executeShellCommand("cat /sdcard/Download/agent-control-image-0513.jpg");InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){byte[] chunk=new byte[8192];int count;while((count=in.read(chunk))>0)bytes.write(chunk,0,count);}if(!java.util.Arrays.equals(expected,bytes.toByteArray()))throw new Exception("Saved Android file differs from original image");
            report.putString("stream","PASS installed Android normal send -> real TeleAgent report_final_files -> request-owned stored tool attachment -> SSH original download -> automatic thumbnail -> full preview -> native Save to Downloads; original and saved bytes match ("+expected.length+").\n");finish(Activity.RESULT_OK,report);return;
        }
        if("teleagent-text-single-direct".equals(options.getString("mode"))){
            String prompt="这是手机单题提交测试。严格只调用 question 工具一次，且只提出一道单选题：测试颜色，选项蓝色和绿色。补充说明使用选项下已有的输入框，不得新增问题。等待答案提交后，只回复 AGENT_CONTROL_TELE_SINGLE_OK 并完整复述颜色及补充说明。不要生成图片，不要执行命令，不要修改文件。";
            js("startConversation();state.selected='teleagent';state.profile.accessMode='desktop';updateSelection();document.getElementById('input').value="+JSONObject.quote(prompt)+";send();");
            waitFor("Array.from(document.querySelectorAll('.interaction-actions button')).some(b=>!b.disabled&&b.textContent.startsWith('蓝色'))",150);
            js("Array.from(document.querySelectorAll('.interaction-actions button')).find(b=>!b.disabled&&b.textContent.startsWith('蓝色')).click();");
            waitFor("!!document.querySelector('.interaction:not(.closed) .interaction-text-input:not([disabled])')",30);
            js("(()=>{const i=document.querySelector('.interaction:not(.closed) .interaction-text-input');i.value='浅蓝天空';i.dispatchEvent(new Event('input'));document.querySelector('.interaction:not(.closed) .interaction-text-save').click();})()");
            waitFor("state.messages.some(m=>m.status==='已完成'&&m.text.includes('AGENT_CONTROL_TELE_SINGLE_OK')&&m.text.includes('浅蓝天空'))",150);
            js("document.querySelector('.message.assistant .content').scrollIntoView({block:'start'});");Thread.sleep(700);capture("teleagent-single-final-0512");
            report.putString("stream","PASS installed Android single question -> select -> type -> one fill-and-continue click -> actual TeleAgent submission -> completed mobile reply contains 浅蓝天空.\n");finish(Activity.RESULT_OK,report);return;
        }
        if("teleagent-text-direct".equals(options.getString("mode"))||"teleagent-text-inline-direct".equals(options.getString("mode"))){
            boolean inlineText="teleagent-text-inline-direct".equals(options.getString("mode"));
            String prompt="这是手机文字输入链路测试。严格只调用 question 工具一次提出且仅提出以下两道单选题，不得增加第三题。补充说明由前端在选项下自带的说明输入框填写，绝对不要创建补充说明问题。以下是两道单选题：第一题测试颜色，选项蓝色和绿色；第二题测试形状，选项圆形和方形（允许其他自定义输入）。等待我在卡片填写补充说明、自定义答案并提交后，只回复 AGENT_CONTROL_TELE_TEXT_OK，并完整复述实际的颜色答案、补充说明和形状答案。不要生成图片，不要执行命令，不要修改文件。";
            js("startConversation();state.selected='teleagent';state.profile.accessMode='desktop';updateSelection();document.getElementById('input').value="+JSONObject.quote(prompt)+";send();");
            waitFor("Array.from(document.querySelectorAll('.interaction-actions button')).some(b=>!b.disabled&&b.textContent.startsWith('蓝色'))",150);
            js("Array.from(document.querySelectorAll('.interaction-actions button')).find(b=>!b.disabled&&b.textContent.startsWith('蓝色')).click();");
            waitFor("!!document.querySelector('.interaction:not(.closed) .interaction-text-input:not([disabled])')",30);
            js("(()=>{const i=document.querySelector('.interaction:not(.closed) .interaction-text-input');i.value='浅蓝天空';i.dispatchEvent(new Event('input'));i.scrollIntoView({block:'center'});})()");Thread.sleep(500);capture("teleagent-optional-input-0512");
            if(!inlineText){
                js("document.querySelector('.interaction:not(.closed) .interaction-text-save').click()");
            }else{
            waitFor("Array.from(document.querySelectorAll('.interaction-actions button')).some(b=>!b.disabled&&b.textContent.startsWith('下一题')&&b.textContent.includes('1/2'))",30);
            js("Array.from(document.querySelectorAll('.interaction-actions button')).find(b=>!b.disabled&&b.textContent.startsWith('下一题')&&b.textContent.includes('1/2')).click();");
            }
            waitFor("Array.from(document.querySelectorAll('.interaction-actions button')).some(b=>!b.disabled&&b.textContent.startsWith('圆形'))",30);
            js("Array.from(document.querySelectorAll('.interaction-actions button')).find(b=>!b.disabled&&b.textContent.startsWith('其他')).click();");
            waitFor("!!document.querySelector('.interaction:not(.closed) .interaction-text-input[required]:not([disabled])')",30);
            js("(()=>{const i=document.querySelector('.interaction:not(.closed) .interaction-text-input');i.value='六边形';i.dispatchEvent(new Event('input'));i.scrollIntoView({block:'center'});})()");Thread.sleep(500);capture("teleagent-custom-input-0512");
            js("document.querySelector('.interaction:not(.closed) .interaction-text-save').click()");
            waitFor("state.messages.some(m=>m.status==='已完成'&&m.text.includes('AGENT_CONTROL_TELE_TEXT_OK')&&m.text.includes('浅蓝天空')&&m.text.includes('六边形'))",150);
            js("document.querySelector('.message.assistant .content').scrollIntoView({block:'start'});");Thread.sleep(700);capture("teleagent-text-final-0512");
            report.putString("stream","PASS normal Android send -> automatic TeleAgent question cards -> optional text and continue over SSH -> next -> Other required custom text and continue over SSH -> submit -> actual completed reply contains 浅蓝天空 and 六边形.\n");finish(Activity.RESULT_OK,report);return;
        }
        if("teleagent-multi-direct".equals(options.getString("mode"))){
            String prompt="这是手机端交互链路测试。必须调用 question 工具一次提出两道单选题：第一题测试颜色，选项蓝色和绿色；第二题测试形状，选项圆形和方形。等待我在交互问答卡片选择并提交后，只回复 AGENT_CONTROL_TELE_MULTI_OK，并写出我选择的颜色和形状。不要生成图片，不要执行命令，不要修改文件。";
            js("startConversation();state.selected='teleagent';state.profile.accessMode='desktop';updateSelection();document.getElementById('input').value="+JSONObject.quote(prompt)+";send();");
            waitFor("state.messages.some(m=>m.interactions?.some(i=>i.state==='pending'&&i.detail.includes('颜色')))",150);
            if(!"false".equals(js("state.messages.some(m=>m.role==='assistant'&&m.status==='已完成')")))throw new Exception("Question completed before response");
            js("document.querySelector('.interaction:not(.closed)').scrollIntoView({block:'start'});");Thread.sleep(500);capture("teleagent-multi-native-auto-0510");
            js("Array.from(document.querySelectorAll('.interaction-actions button')).find(b=>!b.disabled&&b.textContent.startsWith('蓝色')).click();");
            waitFor("Array.from(document.querySelectorAll('.interaction-actions button')).some(b=>!b.disabled&&b.textContent.startsWith('下一题')&&b.textContent.includes('1/2'))",30);
            js("Array.from(document.querySelectorAll('.interaction-actions button')).find(b=>!b.disabled&&b.textContent.startsWith('下一题')&&b.textContent.includes('1/2')).click();");
            waitFor("Array.from(document.querySelectorAll('.interaction-actions button')).some(b=>!b.disabled&&b.textContent.startsWith('圆形'))",30);
            js("Array.from(document.querySelectorAll('.interaction-actions button')).find(b=>!b.disabled&&b.textContent.startsWith('圆形')).click();");
            waitFor("Array.from(document.querySelectorAll('.interaction-actions button')).some(b=>!b.disabled&&b.textContent.startsWith('提交全部答案')&&b.textContent.includes('2/2'))",30);
            js("document.querySelector('.interaction:not(.closed)').scrollIntoView({block:'end'});");Thread.sleep(500);capture("teleagent-multi-native-answered-0510");
            js("Array.from(document.querySelectorAll('.interaction-actions button')).find(b=>!b.disabled&&b.textContent.startsWith('提交全部答案')&&b.textContent.includes('2/2')).click();");
            waitFor("state.messages.some(m=>m.status==='已完成'&&m.text.includes('AGENT_CONTROL_TELE_MULTI_OK')&&m.text.includes('蓝色')&&m.text.includes('圆形'))",150);
            js("document.querySelector('.message.assistant .content').scrollIntoView({block:'start'});");Thread.sleep(700);capture("teleagent-multi-native-complete-0510");
            report.putString("stream","PASS normal installed Android send -> direct SSH -> real TeleAgent creates two questions -> receive loop automatically renders card -> phone blue -> next question -> circle -> submit all exactly once -> matching completed reply with both selected answers. No injected interaction event.\n");finish(Activity.RESULT_OK,report);return;
        }
        if("doubao-reply".equals(options.getString("mode"))){
            js("startConversation();state.selected='doubao';state.profile.accessMode='desktop';updateSelection();document.getElementById('input').value='请只回复 AGENT_CONTROL_DOUBAO_OK，不要运行工具。';send();");
            waitFor("state.messages.some(m=>m.role==='assistant'&&(m.status==='已完成'||m.status==='未完成'||m.status?.includes('失败')))",100);
            if(!"true".equals(js("state.messages.some(m=>m.role==='assistant'&&m.status==='已完成'&&m.text.includes('AGENT_CONTROL_DOUBAO_OK'))")))throw new Exception(js("document.body.innerText.slice(-1500)"));
            Thread.sleep(800);capture("doubao-native-reply");
            report.putString("stream","PASS installed Android -> SSH -> real Doubao desktop send -> rebuilt composer -> matching completed reply.\n");finish(Activity.RESULT_OK,report);return;
        }
        if("workbuddy-confirm".equals(options.getString("mode"))||"workbuddy-multi-confirm".equals(options.getString("mode"))){
            boolean multi="workbuddy-multi-confirm".equals(options.getString("mode"));
            String prompt=multi?"这是多题确认卡片回归测试。通过 AskUserQuestion 工具一次性提出三个问题：第一题是否继续手机测试，选项继续测试和取消测试；第二题使用哪种测试颜色，选项蓝色和绿色；第三题采用哪种测试形状，选项圆形和方形。必须使用工具问题卡片，不要仅在文字中提问。收齐我的答案后仅回复 AGENT_CONTROL_CONFIRM_OK。不要执行命令，不要修改文件。":"这是确认卡片回归测试。先通过问题卡片找我确认：是否继续手机测试？提供继续测试和取消测试两个选项。等我回答后只回复 AGENT_CONTROL_CONFIRM_OK。不要执行命令，不要修改文件。";
            js("startConversation();state.selected='workbuddy';state.profile.accessMode='desktop';document.getElementById('input').value="+JSONObject.quote(prompt)+";updateSelection();send();");
            waitFor("state.messages.some(m=>m.interactions?.some(i=>i.state==='pending'&&i.detail.includes('手机测试')))",100);waitFor("Array.from(document.querySelectorAll('.interaction-actions button')).some(b=>!b.disabled&&b.textContent.includes('继续测试'))",10);Thread.sleep(800);js("document.querySelector('.interaction:not(.closed)').scrollIntoView({block:'start'});");Thread.sleep(500);capture(multi?"workbuddy-native-multi-confirmation":"workbuddy-native-confirmation");
            if(multi&&!"false".equals(js("state.messages.some(m=>m.role==='assistant'&&m.status==='已完成')")))throw new Exception("Question was prematurely completed");
            js("Array.from(document.querySelectorAll('.interaction-actions button')).find(b=>!b.disabled&&b.textContent.includes('继续测试')).click();");
            if(multi){
                for(String choice:new String[]{"蓝色","圆形"}){
                    Thread.sleep(700);
                    js("(()=>{const buttons=Array.from(document.querySelectorAll('.interaction-actions button'));if(!buttons.some(b=>!b.disabled&&b.textContent.includes("+JSONObject.quote(choice)+"))){const next=buttons.find(b=>!b.disabled&&b.textContent==='下一题');if(next)next.click();}})()");
                    waitFor("Array.from(document.querySelectorAll('.interaction-actions button')).some(b=>!b.disabled&&b.textContent.includes("+JSONObject.quote(choice)+"))",25);
                    js("Array.from(document.querySelectorAll('.interaction-actions button')).find(b=>!b.disabled&&b.textContent.includes("+JSONObject.quote(choice)+")).click();");
                }
                Thread.sleep(1000);
            }else waitFor("state.messages.some(m=>m.interactions?.some(i=>i.state==='pending'))||state.messages.some(m=>m.status==='已完成'&&m.text.includes('AGENT_CONTROL_CONFIRM_OK'))",20);
            js("(()=>{const b=Array.from(document.querySelectorAll('.interaction-actions button')).find(b=>!b.disabled&&/^(发送|提交)$/.test(b.textContent));if(b)b.click();})()");
            waitFor("state.messages.some(m=>m.status==='已完成'&&m.text.includes('AGENT_CONTROL_CONFIRM_OK'))",100);Thread.sleep(800);capture(multi?"workbuddy-native-multi-confirmation-complete":"workbuddy-native-confirmation-complete");
            report.putString("stream","PASS installed Android -> SSH -> task service -> real WorkBuddy question card -> phone choice and continuation -> matching completed local-store reply.\n");finish(Activity.RESULT_OK,report);return;
        }
        String dispatched=js("(()=>{try{startConversation();state.selected='codex';document.getElementById('input').value='请运行只读命令 sleep 8，之后只回复 AGENT_CONTROL_DURABLE_ANDROID_OK。不要修改文件。';send();return {busy,connected,pending:pendingHere(),messages:state.messages.length,queue:museQueue.getSnapshot()};}catch(e){return e.stack;}})()");if(!dispatched.contains("messages\":1"))throw new Exception("Dispatch failed: "+dispatched);
        waitFor("serviceTasks.some(t=>t.request.conversationId===state.conversationId&&t.status==='running')",60);
        String conversation=new JSONArray("["+js("state.conversationId")+"]").getString(0);
        Thread.sleep(800);Activity previous=activity;runOnMainSync(previous::finish);Thread.sleep(12000);
        activity=launch();waitFor("state.conversationId==="+JSONObject.quote(conversation),10);connect(activity);
        waitFor("state.messages.some(m=>m.role==='assistant'&&m.status==='已完成'&&m.text.includes('AGENT_CONTROL_DURABLE_ANDROID_OK'))",140);
        if(!"true".equals(js("serviceTasks.filter(t=>t.request.conversationId===state.conversationId).length===1")))throw new Exception("duplicate task after Activity recreation");
        js("document.getElementById('input').value='重开后保留的草稿';document.getElementById('input').dispatchEvent(new Event('input'));document.getElementById('input').focus();");Thread.sleep(1200);
        waitFor("document.getElementById('send').getBoundingClientRect().bottom<=innerHeight",10);capture("persistent-native-chat");
        js("document.getElementById('historyButton').click();document.getElementById('tasksTab').click();");capture("persistent-native-tasks");
        report.putString("stream","PASS Android 15 installed APK -> real SSH -> computer task service -> actual Codex reply. Activity destroyed while running; task completed and same single task/reply returned after reopening. Draft and IME composer visibility verified.\n");finish(Activity.RESULT_OK,report);
    }catch(Throwable error){if(error instanceof InvocationTargetException)error=((InvocationTargetException)error).getCause();report.putString("stream","FAIL "+error+"\n");finish(Activity.RESULT_CANCELED,report);}}
}
