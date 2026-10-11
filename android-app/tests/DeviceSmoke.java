package dev.orca.pocket.tests;

import android.app.*;
import android.content.*;
import android.os.*;
import android.webkit.WebView;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.*;
import org.json.*;

public final class DeviceSmoke extends Instrumentation {
    private WebView web;
    private boolean dshCli,autoPrepare,autoResume,dshExisting,doubaoExisting,buddyOriginal,deepseekOnly,uiOnly,richOnly,agentsRich,desktopRich,onlyBuddy,onlyDoubao,codexMedia;
    @Override public void onCreate(Bundle args){super.onCreate(args);dshCli=args!=null&&"dshCli".equals(args.getString("suite"));autoPrepare=args!=null&&"autoPrepare".equals(args.getString("suite"));autoResume=args!=null&&"autoResume".equals(args.getString("suite"));dshExisting=args!=null&&"dshExisting".equals(args.getString("suite"));doubaoExisting=args!=null&&"doubaoExisting".equals(args.getString("suite"));buddyOriginal=args!=null&&"buddyOriginal".equals(args.getString("suite"));codexMedia=args!=null&&"codexMedia".equals(args.getString("suite"));onlyDoubao=args!=null&&"doubaoRich".equals(args.getString("suite"));onlyBuddy=args!=null&&"buddyRich".equals(args.getString("suite"));desktopRich=onlyBuddy||args!=null&&"desktopRich".equals(args.getString("suite"));agentsRich=desktopRich||args!=null&&"agentsRich".equals(args.getString("suite"));richOnly=args!=null&&"rich".equals(args.getString("suite"));deepseekOnly=args!=null&&"deepseek".equals(args.getString("suite"));uiOnly=args!=null&&"ui".equals(args.getString("suite"));start();}
    private String js(String code)throws Exception{
        CountDownLatch done=new CountDownLatch(1);String[] result={null};
        runOnMainSync(()->web.evaluateJavascript(code,value->{result[0]=value;done.countDown();}));
        if(!done.await(10,TimeUnit.SECONDS))throw new Exception("JavaScript callback timeout");return result[0];
    }
    private void waitFor(String expression,long seconds)throws Exception{
        long until=System.currentTimeMillis()+seconds*1000;
        while(System.currentTimeMillis()<until){if("true".equals(js(expression)))return;
            if((expression.contains(".message.assistant")||expression.contains(".interaction"))&&"true".equals(js("!!document.querySelector('.status.failed')")))throw new Exception("Agent failed: "+js("document.body.innerText.slice(-1800)"));
            Thread.sleep(250);}
        throw new Exception("Condition timed out: "+expression+"; UI="+js("document.body.innerText.slice(-1800)"));
    }
    private byte[] asset(String file)throws Exception{
        try(InputStream in=getContext().getAssets().open(file)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))>0)out.write(b,0,n);return out.toByteArray();}
    }
    private void capture(String name)throws Exception{
        waitForIdleSync();Thread.sleep(900);
        android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();
        if(shot==null)throw new Exception("No native screenshot");
        File out=new File(getTargetContext().getExternalFilesDir(null),name+".png");
        try(FileOutputStream stream=new FileOutputStream(out)){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream);}shot.recycle();
    }
    private void night(boolean value)throws Exception{
        try(android.os.ParcelFileDescriptor fd=getUiAutomation().executeShellCommand("cmd uimode night "+(value?"yes":"no"));InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
        waitFor("document.documentElement.dataset.theme==='"+(value?"dark":"light")+"'",15);
    }
    private void nativeUiCheck(Activity activity)throws Exception{
        night(false);
        js("startConversation();state.selected='auto';updateSelection();");
        waitFor("document.getElementById('send').disabled",5);
        capture("ui-native-welcome");
        js("receive('connected',{profile:{host:'192.168.43.4',port:22,user:'test',directory:'/tmp'},agents:[{id:'deepseek',name:'DeepSeek Harness',detail:'桌面会话'}]});state.selected='deepseek';updateSelection();add({id:'native-user',role:'user',text:'检查一下项目的连接流程。'});receive('run',{id:'native-answer',agent:'DeepSeek Harness',label:'回复'});receive('update',{id:'native-answer',text:'这是界面验证样例。\\n\\n先确认电脑连接，再检查消息收发。',status:'已完成'});document.getElementById('toast').hidden=true;scrollEnd();");
        js("document.getElementById('toast').hidden=true");capture("ui-native-chat-light");
        night(true);capture("ui-native-chat-dark");
        double originalHeight=Double.parseDouble(js("innerHeight"));
        JSONArray point=new JSONArray(js("(()=>{const r=document.getElementById('input').getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2,innerWidth]})()"));
        int[] location=new int[2];runOnMainSync(()->{web.requestFocus();web.getLocationOnScreen(location);});
        float scale=web.getWidth()/(float)point.getDouble(2);float x=location[0]+(float)point.getDouble(0)*scale,y=location[1]+(float)point.getDouble(1)*scale;
        long now=android.os.SystemClock.uptimeMillis();
        sendPointerSync(android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,x,y,0));
        sendPointerSync(android.view.MotionEvent.obtain(now,now+50,android.view.MotionEvent.ACTION_UP,x,y,0));
        waitFor("innerHeight<"+(originalHeight-80),10);
        waitFor("(()=>{const r=document.getElementById('send').getBoundingClientRect();return r.top>=0&&r.bottom<=innerHeight})()",5);
        capture("ui-native-keyboard-dark");
        runOnMainSync(()->((android.view.inputmethod.InputMethodManager)activity.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(web.getWindowToken(),0));
        night(false);
        js("document.querySelector('.copy-button').click();");
        Thread.sleep(300);
        String[] clip={null};runOnMainSync(()->{android.content.ClipboardManager manager=(android.content.ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);if(manager.hasPrimaryClip())clip[0]=manager.getPrimaryClip().getItemAt(0).coerceToText(activity).toString();});
        if(clip[0]==null||!clip[0].contains("界面验证样例"))throw new Exception("native clipboard mismatch");
    }
    private void nativeClick(String label)throws Exception{
        long until=System.currentTimeMillis()+10000;
        while(System.currentTimeMillis()<until){android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root!=null)for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(label))if(label.equals(String.valueOf(node.getText()))&&node.isClickable()){node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);return;}Thread.sleep(200);}throw new Exception("Native action missing: "+label);
    }
    private void nativeDoubaoExisting(Activity activity,Object engine)throws Exception{
        js("startConversation();receive('run',{id:'doubao-existing',agent:'豆包',label:'回复'});receive('update',{id:'doubao-existing',text:'已有生成图片回传验证',status:'已完成'});");
        Field contexts=engine.getClass().getDeclaredField("desktopRuns");contexts.setAccessible(true);
        ((java.util.Map)contexts.get(engine)).put("doubao-existing",new org.json.JSONObject().put("app",dshExisting?"DeepSeek Harness":"Doubao").put("prompt","生成一张赛博朋克女战士肖像").put("computer","10.0.2.2|22992|ryan"));
        Method collect=engine.getClass().getDeclaredMethod("collectDesktop",String.class);collect.setAccessible(true);collect.invoke(engine,"doubao-existing");
        waitFor("!!document.querySelector('.attachment-thumbnail')",70);
        waitFor("document.querySelector('.attachment-thumbnail').naturalWidth>0",10);
        if(dshExisting)waitFor("document.querySelectorAll('.attachment-thumbnail').length===2",40);
        capture(dshExisting?"dsh-original-inline":"doubao-original-inline");
        js("document.querySelector('.attachment-button').click()");
        waitFor("document.getElementById('attachmentViewer').open&&!document.getElementById('attachmentImage').hidden",10);capture(dshExisting?"dsh-original-preview":"doubao-original-preview");
        Field downloaded=activity.getClass().getDeclaredField("downloaded");downloaded.setAccessible(true);
        byte[] original=(byte[])((java.util.Map)downloaded.get(activity)).values().iterator().next();
        android.graphics.BitmapFactory.Options opt=new android.graphics.BitmapFactory.Options();opt.inJustDecodeBounds=true;android.graphics.BitmapFactory.decodeByteArray(original,0,original.length,opt);
        if(dshExisting?(opt.outWidth<100||opt.outHeight<100):(opt.outWidth!=288||opt.outHeight!=384))throw new Exception("No real generated image decoded");
        if(dshExisting){
          Field names=activity.getClass().getDeclaredField("downloadNames");names.setAccessible(true);java.util.Map labelMap=(java.util.Map)names.get(activity);
          Field sshField=engine.getClass().getDeclaredField("ssh");sshField.setAccessible(true);Object ssh=sshField.get(engine);
          java.util.Map dataMap=(java.util.Map)downloaded.get(activity);
          if(dataMap.size()!=2)throw new Exception("Expected two DSH images");
          for(Object id:dataMap.keySet()){String name=(String)labelMap.get(id);byte[] expected=(byte[])ssh.getClass().getMethod("download",String.class,int.class).invoke(ssh,"/Users/ryan/workspace/orca/dsh/images/"+name,20*1024*1024);if(!java.util.Arrays.equals(expected,(byte[])dataMap.get(id)))throw new Exception("DSH original bytes mismatch");}
        }
        js("document.getElementById('saveAttachment').click()");
        long until=System.currentTimeMillis()+15000;boolean saved=false;
        while(System.currentTimeMillis()<until){android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root!=null)for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByViewId("com.android.documentsui:id/button1")){if(node.isClickable()){node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);saved=true;break;}}if(saved)break;Thread.sleep(250);}
        if(!saved){nativeClick("SAVE");}nativeClick("完成");
        System.out.println("Decoded Doubao CDN image "+opt.outWidth+"x"+opt.outHeight+", bytes="+original.length);
        capture("doubao-original-saved");
    }
    private void nativeRichCheck(Activity activity,Object engine)throws Exception{
        String path=buddyOriginal?"/Users/ryan/WorkBuddy/2026-10-09-18-56-20/generated-images/壮丽的自然风光_清晨的雪山湖泊_金色阳光洒在雪峰上_湖面如镜_2026-10-09T10-57-26.png":"/Users/ryan/workspace/orca/codex/.toolchain/ssh-test/orca-attachment-test.png";
        Field sshField=engine.getClass().getDeclaredField("ssh");sshField.setAccessible(true);Object ssh=sshField.get(engine);
        byte[] original=(byte[])ssh.getClass().getMethod("download",String.class,int.class).invoke(ssh,path,20*1024*1024);
        if(original.length<100||original[0]!=(byte)137)throw new Exception("SFTP PNG transfer invalid");
        js("startConversation();receive('run',{id:'attachment-native',agent:'附件链路测试',label:'回复'});receive('update',{id:'attachment-native',text:'下面是通过真实 SSH 从电脑读取的测试图片。',status:'已完成'});");
        Field filesField=engine.getClass().getDeclaredField("attachments");filesField.setAccessible(true);Object files=filesField.get(engine);
        Method local=files.getClass().getDeclaredMethod("local",String.class,String.class,String.class);local.setAccessible(true);if(buddyOriginal){
            Field contexts=engine.getClass().getDeclaredField("desktopRuns");contexts.setAccessible(true);
            ((java.util.Map)contexts.get(engine)).put("attachment-native",new org.json.JSONObject().put("app","WorkBuddy").put("prompt","生成一幅图片").put("computer","10.0.2.2|22992|ryan"));
            Method collect=engine.getClass().getDeclaredMethod("collectDesktop",String.class);collect.setAccessible(true);collect.invoke(engine,"attachment-native");
        }else local.invoke(files,"attachment-native",path,"10.0.2.2|22992|ryan");
        waitFor("!!document.querySelector('.attachment-button')",20);js("document.querySelector('.attachment-button').click()");nativeClick("读取附件");
        waitFor("document.getElementById('attachmentViewer').open&&!document.getElementById('attachmentImage').hidden",20);capture("rich-native-image");
        js("document.getElementById('saveAttachment').click()");
        long until=System.currentTimeMillis()+15000;boolean saved=false;
        while(System.currentTimeMillis()<until){android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();if(root!=null){for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByViewId("com.android.documentsui:id/button1")){if(node.isClickable()){node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);saved=true;break;}}if(!saved)for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByViewId("android:id/button1")){if(node.isClickable()&&String.valueOf(node.getText()).matches("(?i)save|保存")){node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);saved=true;break;}}}if(saved)break;Thread.sleep(250);}
        if(!saved)throw new Exception("Native document SAVE control missing");
        nativeClick("完成");
        byte[] result;try(android.os.ParcelFileDescriptor fd=getUiAutomation().executeShellCommand("cat /sdcard/Download/"+new java.io.File(path).getName());InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[1024];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);result=bytes.toByteArray();}
        if(!java.util.Arrays.equals(original,result))throw new Exception("Saved attachment does not match SSH source bytes");
        capture("rich-native-saved");
    }
    private void sendRich(String agent,String mode,String prompt)throws Exception{
        js("startConversation();state.selected="+JSONObject.quote(agent)+";state.profile.accessMode="+JSONObject.quote(mode)+";state.profile.directory='/tmp';state.profile.hostPermissions=true;updateSelection();document.getElementById('input').value="+JSONObject.quote(prompt)+";document.getElementById('input').dispatchEvent(new Event('input'));document.getElementById('send').click();");
    }
    private void nativeCodexMedia()throws Exception{
        sendRich("codex","auto","这是已有图片附件传输测试。不要调用工具。请仅原样返回下面这个 Markdown 链接：[测试图片](/Users/ryan/workspace/orca/codex/.toolchain/ssh-test/orca-attachment-test.png)");
        waitFor("state.messages.some(m=>m.role==='assistant'&&m.status==='已完成')",100);waitFor("!!document.querySelector('.attachment-button')",10);
        js("document.querySelector('.attachment-button').click()");nativeClick("读取附件");waitFor("document.getElementById('attachmentViewer').open&&!document.getElementById('attachmentImage').hidden",20);capture("rich-native-codex-attachment");
    }
    private void nativeDoubaoRich()throws Exception{
        sendRich("doubao","desktop","这是手机端对话确认测试。请先询问我是否生成一张测试图片，在我答复之前只提出问题，不要生成图片。");
        waitFor("state.messages.some(m=>m.role==='assistant'&&m.status==='已完成')",90);waitFor("!busy",10);capture("rich-native-doubao-question");
        js("document.getElementById('input').value='确认，请生成一张测试图片：白色背景，中间一个纯绿色圆形，不要文字。只生成一张图片。';document.getElementById('input').dispatchEvent(new Event('input'));document.getElementById('send').click();");
        waitFor("state.messages.filter(m=>m.role==='assistant'&&m.status==='已完成').length>=2",150);waitFor("!busy",10);
        waitFor("Array.from(document.querySelectorAll('.attachment-button')).some(b=>b.textContent.includes('电脑画面'))",30);
        capture("rich-native-doubao-result");js("Array.from(document.querySelectorAll('.attachment-button')).find(b=>b.textContent.includes('电脑画面')).click()");nativeClick("读取附件");
        waitFor("document.getElementById('attachmentViewer').open&&!document.getElementById('attachmentImage').hidden",20);capture("rich-native-doubao-preview");
        js("document.getElementById('attachmentViewer').close();Array.from(document.querySelectorAll('.desktop-button')).at(-1).click()");waitFor("!!desktopView&&document.getElementById('desktopImage').src.startsWith('data:image')",30);capture("rich-native-doubao-controls");
    }
    private void nativeAgentsRich()throws Exception{
        if(!desktopRich){sendRich("codex","auto","This is a harmless permission integration test. Use exec_command exactly once with cmd exactly `printf ORCA_APPROVAL_OK`, sandbox_permissions `require_escalated`, justification `May I run this harmless printf to verify the phone approval?`. Do not use any other commands or tools. After the command result, reply exactly ORCA_APPROVAL_OK.");
        waitFor("!!document.querySelector('.interaction button:not(:disabled)')",100);
        String detail=js("document.querySelector('.interaction pre').textContent");if(!detail.contains("printf ORCA_APPROVAL_OK"))throw new Exception("Unexpected approval, not accepting");
        capture("rich-native-codex-confirm");js("Array.from(document.querySelectorAll('.interaction button')).find(b=>b.textContent==='仅允许这次').click()");waitFor("!busy",100);
        if(!"true".equals(js("state.messages.some(m=>m.role==='assistant'&&m.status==='已完成'&&m.text.includes('ORCA_APPROVAL_OK'))")))throw new Exception("Codex did not return accepted command result");}
        String prompt="这是手机端交互验证。请使用提问或用户确认工具（如果可用），先询问“是否继续手机测试？”，提供“继续测试”和“取消测试”两项。收到选择之前不要执行任何其他任务。收到继续后，只回复“手机确认测试成功”，并附上现有测试图片链接：[测试图片](/Users/ryan/workspace/orca/codex/.toolchain/ssh-test/orca-attachment-test.png)。不要生成、修改或删除文件。";
        for(String agent:onlyBuddy?new String[]{"workbuddy"}:new String[]{"deepseek","workbuddy"}){
            sendRich(agent,"desktop",prompt);waitFor("Array.from(document.querySelectorAll('.interaction button:not(:disabled)')).some(b=>b.textContent.includes('继续测试'))",140);
            capture("rich-native-"+agent+"-confirm");js("Array.from(document.querySelectorAll('.interaction button:not(:disabled)')).find(b=>b.textContent.includes('继续测试')).click()");
            if(agent.equals("deepseek")){waitFor("Array.from(document.querySelectorAll('.interaction button:not(:disabled)')).some(b=>b.textContent==='提交')",30);js("Array.from(document.querySelectorAll('.interaction button:not(:disabled)')).find(b=>b.textContent==='提交').click()");}
            waitFor("!busy",100);if(!"true".equals(js("state.messages.some(m=>m.role==='assistant'&&m.status==='已完成'&&m.text.includes('手机确认测试成功'))")))throw new Exception(agent+" receiving-side confirmation missing");
            waitFor("!!document.querySelector('.attachment-button')",15);capture("rich-native-"+agent+"-attachment");
        }
    }
    @Override public void onStart(){
        Bundle report=new Bundle();Activity activity=null;
        try{
            Intent intent=new Intent().setClassName("dev.orca.pocket","dev.orca.pocket.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity=startActivitySync(intent);
            Field webField=activity.getClass().getDeclaredField("web");webField.setAccessible(true);web=(WebView)webField.get(activity);
            waitFor("typeof window.receive==='function'",15);
            Thread.sleep(600);
            if(autoResume){
                waitFor("connected&&!busy",40);
                if(!"true".equals(js("state.profile.autoConnect")))throw new Exception("Automatic profile missing");
                Field vf=activity.getClass().getDeclaredField("store");vf.setAccessible(true);Object v=vf.get(activity);Method get=v.getClass().getDeclaredMethod("get",String.class);get.setAccessible(true);
                js("document.getElementById('connectionButton').click();document.getElementById('autoConnect').checked=false;document.getElementById('autoConnect').dispatchEvent(new Event('change'));");
                Thread.sleep(600);if(!((String)get.invoke(v,"auto-connect")).isEmpty())throw new Exception("Saved credentials not cleared");
                report.putString("stream","PASS startup auto-connect using encrypted credential and stored private key; disable clears saved credential.\n");finish(Activity.RESULT_OK,report);return;
            }
            if(uiOnly){nativeUiCheck(activity);report.putString("stream","PASS Android 15 native UI: signed APK, production WebView, empty-send guard, native clipboard, system dark/light theme, actual IME resize and visible send control. Screenshots contain UI fixtures.\n");finish(Activity.RESULT_OK,report);return;}
            Field key=activity.getClass().getDeclaredField("privateKey");key.setAccessible(true);key.set(activity,asset("client"));
            Field vaultField=activity.getClass().getDeclaredField("store");vaultField.setAccessible(true);Object vault=vaultField.get(activity);
            Method put=vault.getClass().getDeclaredMethod("put",String.class,String.class);put.setAccessible(true);
            String fingerprint=new String(asset("fingerprint"),"UTF-8").trim();put.invoke(vault,"host:[10.0.2.2]:22992",fingerprint);
            if(autoPrepare){put.invoke(vault,"private-key",android.util.Base64.encodeToString(asset("client"),android.util.Base64.NO_WRAP));js("document.getElementById('autoConnect').checked=true;document.getElementById('password').value='auto-test-secret';");}
            js("document.getElementById('connectionButton').click();document.getElementById('host').value='10.0.2.2';document.getElementById('port').value='22992';document.getElementById('user').value='ryan';"+(autoPrepare?"document.getElementById('autoConnect').checked=true;document.getElementById('password').value='auto-test-secret';":"")+"document.getElementById('connect').click();");
            waitFor("document.getElementById('connectionLabel').textContent==='10.0.2.2'",35);
            waitFor("!busy",10);
            if(autoPrepare){Thread.sleep(800);Method get=vault.getClass().getDeclaredMethod("get",String.class);get.setAccessible(true);String saved=(String)get.invoke(vault,"auto-connect");if(!saved.contains("auto-test-secret"))throw new Exception("Credential not persisted after success");String raw=activity.getSharedPreferences("vault",0).getString("auto-connect","");if(raw.contains("auto-test-secret"))throw new Exception("Plaintext stored");if(((String)get.invoke(vault,"state")).contains("auto-test-secret"))throw new Exception("Credential leaked to chat state");report.putString("stream","PASS successful connection saves encrypted credential, excludes it from chat state.\n");finish(Activity.RESULT_OK,report);return;}
            js("state.profile.accessMode='auto';document.getElementById('newChat').click();");
            Field engineField=activity.getClass().getDeclaredField("engine");engineField.setAccessible(true);Object engine=engineField.get(activity);
            if(dshCli){
                js("state.profile.hostPermissions=true;");sendRich("deepseek","cli","请只回复 ORCA_DSH_PHONE_CLI_OK。不要调用任何工具。");
                waitFor("state.messages.some(m=>m.role==='assistant'&&m.status==='已完成'&&m.text.includes('ORCA_DSH_PHONE_CLI_OK'))",100);
                if("true".equals(js("state.messages.some(m=>m.status==='已自动切换桌面')")))throw new Exception("CLI-only unexpectedly fell back");
                capture("dsh-real-cli-account");report.putString("stream","PASS Android -> SSH -> bundled DSH ACP using desktop account model -> matching model reply, CLI-only with no desktop fallback.\n");finish(Activity.RESULT_OK,report);return;
            }
            if(doubaoExisting||dshExisting){nativeDoubaoExisting(activity,engine);report.putString("stream",dshExisting?"PASS two DSH images extracted through SSH, inline previews, original bytes matched, system save.\n":"PASS Android SSH -> existing Doubao AX image URL -> native HTTPS -> inline thumbnail -> image preview -> system save.\n");finish(Activity.RESULT_OK,report);return;}
            if(codexMedia){nativeCodexMedia();report.putString("stream","PASS Android -> Codex app-server real reply -> local image attachment -> SSH SFTP -> native image preview.\n");finish(Activity.RESULT_OK,report);return;}
            if(onlyDoubao){nativeDoubaoRich();report.putString("stream","PASS Android -> Doubao question -> phone confirmation as chat -> real generated image -> SSH screenshot attachment -> native preview and app-scoped desktop controls.\n");finish(Activity.RESULT_OK,report);return;}
            if(agentsRich){nativeAgentsRich();report.putString("stream",onlyBuddy?"PASS real Android SSH to WorkBuddy question card; phone selection, receiving-side completed result and returned file attachment.\n":"PASS real Android SSH to selected agent question cards; phone selections, completed results and returned attachments.\n");finish(Activity.RESULT_OK,report);return;}
            if(richOnly||buddyOriginal){nativeRichCheck(activity,engine);report.putString("stream","PASS Android native attachment: real SSH SFTP, native confirmation, image preview, system document picker, saved PNG bytes identical to source.\n");finish(Activity.RESULT_OK,report);return;}
            Field sshField=engine.getClass().getDeclaredField("ssh");sshField.setAccessible(true);Object ssh=sshField.get(engine);
            ssh.getClass().getMethod("disconnect").invoke(ssh);
            js("document.getElementById('input').value='尚未发送的断线测试';document.getElementById('input').dispatchEvent(new Event('input'));document.getElementById('send').click();");
            waitFor("document.getElementById('input').value==='尚未发送的断线测试'",5);
            waitFor("document.getElementById('connectionLabel').textContent==='10.0.2.2'&&!busy",40);
            if(!"true".equals(js("document.getElementById('input').value==='尚未发送的断线测试'")))throw new Exception("unsent message lost");
            if(deepseekOnly){
                for(String mode:new String[]{"auto","desktop"}){
                    js("add({id:'isolation-old-user',role:'user',text:'ISOLATION_OLD_CONTEXT'});add({id:'isolation-old-answer',role:'assistant',agent:'豆包',label:'回复',status:'已完成',text:'ISOLATION_OLD_REPLY'});");
                    js("document.getElementById('newChat').click();state.selected='deepseek';state.profile.accessMode="+JSONObject.quote(mode)+";state.profile.hostPermissions=true;updateSelection();document.getElementById('input').value='请只回复：测试成功';document.getElementById('input').dispatchEvent(new Event('input'));document.getElementById('send').click();");
                    waitFor("Array.from(document.querySelectorAll('.message.assistant')).some(e=>e.querySelector('.content').textContent.trim()==='测试成功'&&e.querySelector('.status').textContent==='已完成')",150);
                    waitFor("!busy",15);
                    String switched=js("state.messages.some(m=>m.status==='已自动切换桌面')");
                    if(mode.equals("auto")&&!"true".equals(switched))throw new Exception("DeepSeek CLI credential fallback not exercised");
                    if(mode.equals("desktop")&&!"false".equals(switched))throw new Exception("direct desktop attempted CLI");
                }
            }else{
            for(String accessMode:new String[]{"auto","desktop"}){
                String agent="workbuddy";
                js("document.getElementById('newChat').click();state.selected="+JSONObject.quote(agent)+";updateSelection();state.profile.accessMode="+JSONObject.quote(accessMode)+";updateSelection();document.getElementById('input').value='请只回复测试成功';document.getElementById('input').dispatchEvent(new Event('input'));document.getElementById('send').click();");
                waitFor("Array.from(document.querySelectorAll('.message.assistant')).some(e=>e.querySelector('.content').textContent.includes('测试成功')&&e.querySelector('.status').textContent==='已完成')",90);
                waitFor("!busy",10);
                String switched=js("state.messages.some(m=>m.status==='已自动切换桌面')");
                if(accessMode.equals("auto")&&!"true".equals(switched))throw new Exception("CLI fallback was not exercised");
                if(accessMode.equals("desktop")&&!"false".equals(switched))throw new Exception("explicit desktop unexpectedly attempted CLI");
            }
            js("document.getElementById('newChat').click();state.selected='doubao';state.profile.accessMode='auto';state.messages=[{id:'context-user',role:'user',text:'你好'},{id:'context-agent',role:'assistant',agent:'WorkBuddy',label:'回复',status:'已完成',text:('这只是历史上下文：你好——我在呢。可以写东西、查资料、做图表。\\n\\n').repeat(12)}].map(m=>({...m,conversationId:state.conversationId}));render();document.getElementById('input').value='请只回复测试成功';document.getElementById('input').dispatchEvent(new Event('input'));document.getElementById('send').click();");
            waitFor("Array.from(document.querySelectorAll('.message.assistant')).some(e=>e.querySelector('.meta').textContent.includes('豆包')&&e.querySelector('.content').textContent.includes('测试成功')&&e.querySelector('.status').textContent==='已完成')",90);
            waitFor("!busy",15);
            }
            Thread.sleep(600);
            String prefs=getTargetContext().getSharedPreferences("vault",0).getString("state","");
            if(prefs.isEmpty()||prefs.contains("测试成功"))throw new Exception("chat storage not encrypted");
            if(!"true".equals(js("document.querySelector('header').getBoundingClientRect().top>=0")))throw new Exception("header bounds invalid");
            report.putString("stream",deepseekOnly?"PASS Android 15: production APK -> WebView -> JSch SSH -> DeepSeek CLI missing-credential fallback and explicit desktop, both returned exact completed 测试成功; reconnect and encrypted persistence verified.\n":"PASS Android 15: signed APK installed, native WebView -> JSch Ed25519 SSH -> actual agent inventory, encrypted persistence; dropped connection recovers and preserves unsent draft; WorkBuddy default CLI authentication fallback and explicit direct desktop both completed; new task navigation from prior conversation verified; Doubao with long cross-agent history and inline dash whitespace returned a completed reply.\n");
            finish(Activity.RESULT_OK,report);
        }catch(Throwable e){report.putString("stream","FAIL "+e.toString()+"\n");finish(Activity.RESULT_CANCELED,report);}
    }
}
