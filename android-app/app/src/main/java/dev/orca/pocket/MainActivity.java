package dev.orca.pocket;

import android.app.*;
import android.os.*;
import android.content.*;
import android.webkit.*;
import android.view.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.json.*;

public final class MainActivity extends Activity implements PocketEngine.Host {
    private WebView web;
    private android.widget.FrameLayout frame;
    private PocketEngine engine;
    private SecureStore store;
    private final InputFiles inputFiles=new InputFiles();
    private volatile boolean selectingFiles;
    private String pickerComputer="";
    private int pickerLimit=4;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private byte[] privateKey;
    private volatile JSONObject pendingConnection;
    private volatile boolean autoStarted;
    private final java.util.LinkedHashMap<String,byte[]> downloaded=new java.util.LinkedHashMap<>();
    private final java.util.Map<String,String> downloadNames=new java.util.HashMap<>();
    private byte[] exportBytes;
    private String exportName;
    private volatile boolean exporting;
    public byte[] previewImage(byte[] bytes,int maxBytes)throws Exception{
        if(bytes==null||bytes.length==0||maxBytes<1)throw new IOException("图片预览参数无效");
        if(bytes.length<=maxBytes)return bytes;
        if(bytes.length>InputFiles.LIMIT)throw new IOException("图片超过 20MB，无法生成预览");
        android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();options.inJustDecodeBounds=true;
        android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
        int width=options.outWidth,height=options.outHeight;
        if(width<1||height<1)throw new IOException("无法解码图片，请上传有效的图片文件");
        if(width>32768||height>32768||(long)width*height>100000000L)throw new IOException("图片原始尺寸过大，请缩小图片后重试");
        int sample=1;while((width+sample-1)/sample>1280||(height+sample-1)/sample>1280)sample*=2;
        options.inJustDecodeBounds=false;options.inSampleSize=sample;options.inScaled=false;options.inPreferredConfig=android.graphics.Bitmap.Config.ARGB_8888;
        android.graphics.Bitmap decoded=null,preview=null;
        try{
            decoded=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
            if(decoded==null)throw new IOException("无法解码图片，请重新上传有效的图片文件");
            double scale=Math.min(1d,1280d/Math.max(decoded.getWidth(),decoded.getHeight()));
            int previewWidth=Math.max(1,(int)Math.round(decoded.getWidth()*scale)),previewHeight=Math.max(1,(int)Math.round(decoded.getHeight()*scale));
            preview=android.graphics.Bitmap.createBitmap(previewWidth,previewHeight,android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas=new android.graphics.Canvas(preview);canvas.drawColor(android.graphics.Color.WHITE);
            canvas.drawBitmap(decoded,null,new android.graphics.Rect(0,0,previewWidth,previewHeight),new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
            decoded.recycle();decoded=null;
            while(true){
                for(int quality:new int[]{85,70,55,40}){
                    ByteArrayOutputStream output=new ByteArrayOutputStream(Math.min(maxBytes,65536));
                    if(!preview.compress(android.graphics.Bitmap.CompressFormat.JPEG,quality,output))throw new IOException("图片预览编码失败");
                    if(output.size()<=maxBytes)return output.toByteArray();
                }
                int edge=Math.max(preview.getWidth(),preview.getHeight());if(edge<=1)throw new IOException("图片预览无法压缩到传输大小限制");
                double shrink=Math.max(1,edge*3/4)/(double)edge;
                android.graphics.Bitmap smaller=android.graphics.Bitmap.createScaledBitmap(preview,Math.max(1,(int)Math.round(preview.getWidth()*shrink)),Math.max(1,(int)Math.round(preview.getHeight()*shrink)),true);
                preview.recycle();preview=smaller;
            }
        }catch(OutOfMemoryError memory){throw new IOException("图片预览内存不足，请缩小图片后重试",memory);}
        catch(RuntimeException invalid){throw new IOException("图片预览生成失败，请检查图片是否有效",invalid);}
        finally{if(decoded!=null)decoded.recycle();if(preview!=null)preview.recycle();}
    }
    public void file(String id,String name,byte[] bytes)throws Exception{
        synchronized(downloaded){while(downloaded.size()>=2){String old=downloaded.keySet().iterator().next();downloaded.remove(old);downloadNames.remove(old);}downloaded.put(id,bytes);downloadNames.put(id,name);}
        JSONObject result=new JSONObject().put("attachmentId",id).put("name",name).put("size",bytes.length);
        android.graphics.BitmapFactory.Options options=new android.graphics.BitmapFactory.Options();options.inJustDecodeBounds=true;
        android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
        if(options.outWidth>0&&options.outHeight>0&&(long)options.outWidth*options.outHeight<=100000000){
            int sample=1;while(options.outWidth/sample>1600||options.outHeight/sample>1600)sample*=2;
            options.inJustDecodeBounds=false;options.inSampleSize=sample;
            android.graphics.Bitmap bitmap=android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
            if(bitmap!=null){ByteArrayOutputStream out=new ByteArrayOutputStream();bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,80,out);if(out.size()>450000){android.graphics.Bitmap small=android.graphics.Bitmap.createScaledBitmap(bitmap,Math.max(1,bitmap.getWidth()/2),Math.max(1,bitmap.getHeight()/2),true);out.reset();small.compress(android.graphics.Bitmap.CompressFormat.JPEG,65,out);small.recycle();}bitmap.recycle();result.put("preview","data:image/jpeg;base64,"+android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP));}
        }
        event("attachmentReady",result);
    }
    private void export(String id){
        if(exporting){error("请先完成当前文件的保存");return;}
        synchronized(downloaded){exportBytes=downloaded.get(id);exportName=downloadNames.get(id);}
        if(exportBytes==null){error("附件缓存已失效，请重新下载");return;}
        exporting=true;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(mime(exportName));intent.putExtra(Intent.EXTRA_TITLE,exportName);startActivityForResult(intent,20);
    }
    private static String mime(String name){String extension=android.webkit.MimeTypeMap.getFileExtensionFromUrl(name.replace(" ","_"));String mime=android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase(java.util.Locale.ROOT));return mime==null?"application/octet-stream":mime;}

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        java.security.Security.removeProvider("BC");
        java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        store=new SecureStore(this);engine=new PocketEngine(this);
        web=new WebView(this);web.setBackgroundColor(0xfffafafa);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);
        web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        web.addJavascriptInterface(new Bridge(),"Pocket");
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
            @Override public void onPageFinished(WebView v,String url){
                if(!url.equals("file:///android_asset/index.html"))return;
                applySystemTheme();
                io.execute(()->{try{
                    inputFiles.restore(store.get("input-files"));
                    String data=store.get("state");event("restore",data.isEmpty()?new JSONObject():new JSONObject(data));
                    android.content.pm.PackageInfo info=getPackageManager().getPackageInfo(getPackageName(),0);
                    event("appInfo",new JSONObject().put("versionName",info.versionName).put("versionCode",info.versionCode));
                    String key=store.get("private-key");if(!key.isEmpty()){privateKey=android.util.Base64.decode(key,android.util.Base64.NO_WRAP);event("key",new JSONObject().put("loaded",true));}
                String automatic=store.get("auto-connect");
                    event("credentials",new JSONObject().put("saved",!automatic.isEmpty()));
                    if(!autoStarted&&!automatic.isEmpty()){autoStarted=true;JSONObject saved=new JSONObject(automatic);pendingConnection=saved;engine.connect(saved,privateKey);}
                }catch(Exception e){error("本地记录无法解密："+e.getMessage());}});
            }
        });
        frame=new android.widget.FrameLayout(this);
        frame.setBackgroundColor(0xfffafafa);
        frame.addView(web,new android.widget.FrameLayout.LayoutParams(-1,-1));
        frame.setOnApplyWindowInsetsListener((v,insets)->{
            if(Build.VERSION.SDK_INT>=30){
                android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.ime());
                v.setPadding(bars.left,bars.top,bars.right,bars.bottom);
            }else v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets.consumeSystemWindowInsets();
        });
        setContentView(frame);applySystemTheme();frame.requestApplyInsets();web.loadUrl("file:///android_asset/index.html");
    }
    private void applySystemTheme(){
        boolean dark=(getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int background=dark?0xff101716:0xfff7f9f9;
        if(web!=null)web.setBackgroundColor(background);
        if(frame!=null)frame.setBackgroundColor(background);
        getWindow().setStatusBarColor(background);getWindow().setNavigationBarColor(background);
        View decor=getWindow().getDecorView();int flags=decor.getSystemUiVisibility();
        int light=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        decor.setSystemUiVisibility(dark?flags&~light:flags|light);
        try{event("theme",new JSONObject().put("dark",dark));}catch(Exception ignored){}
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config){super.onConfigurationChanged(config);applySystemTheme();}
    public String asset(String name)throws Exception{
        try(InputStream in=getAssets().open(name)){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] chunk=new byte[8192];int n;while((n=in.read(chunk))>0)b.write(chunk,0,n);return b.toString("UTF-8");}
    }
    public byte[] bytes(String name)throws Exception{
        try(InputStream in=getAssets().open(name)){ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))>0)out.write(buffer,0,n);return out.toByteArray();}
    }
    public void event(String type,JSONObject data){
        if(type.equals("connected")){
            JSONObject pending=pendingConnection,confirmed=data.optJSONObject("profile");pendingConnection=null;
            if(pending!=null&&confirmed!=null&&pending.optString("host").trim().equals(confirmed.optString("host").trim())&&pending.optString("user").trim().equals(confirmed.optString("user").trim())&&pending.optInt("port",22)==confirmed.optInt("port",22)){
                io.execute(()->{try{boolean save=pending.optBoolean("autoConnect");store.put("auto-connect",save?pending.toString():"");event("credentials",new JSONObject().put("saved",save));}catch(Exception e){error("自动连接凭据保存失败");}});
            }
        }

        runOnUiThread(()->{if(type.equals("busy")){
            if(data.optBoolean("value"))getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }if(!isDestroyed())web.evaluateJavascript("window.receive("+JSONObject.quote(type)+","+data.toString()+")",null);});
    }
    void error(String text){try{event("error",new JSONObject().put("message",text));}catch(Exception ignored){}}
    public boolean trust(String host,String fingerprint)throws Exception{
        String key="host:"+host.toLowerCase(java.util.Locale.ROOT),known=store.get(key);
        if(!known.isEmpty()){
            if(!known.equals(fingerprint))error("电脑 SSH 指纹已变化，连接已拒绝。请在电脑核验后，用设置中的“重置配对”清除旧指纹。");
            return known.equals(fingerprint);
        }
        CountDownLatch latch=new CountDownLatch(1);boolean[] accepted={false};
        runOnUiThread(()->new AlertDialog.Builder(this).setTitle("首次连接这台电脑")
            .setMessage(host+"\n\n"+fingerprint+"\n\n请与电脑 SSH 主机公钥指纹核对，确认后会记住这台电脑。")
            .setPositiveButton("信任并连接",(d,w)->{accepted[0]=true;latch.countDown();})
            .setNegativeButton("取消",(d,w)->latch.countDown()).setOnCancelListener(d->latch.countDown()).show());
        if(!latch.await(90,TimeUnit.SECONDS))return false;
        if(accepted[0])store.put(key,fingerprint);
        return accepted[0];
    }
    private final class Bridge {
        @JavascriptInterface public void openLink(String raw){
            try{android.net.Uri uri=android.net.Uri.parse(raw);if(!"https".equals(uri.getScheme())&&!"http".equals(uri.getScheme()))return;
                runOnUiThread(()->{try{startActivity(new Intent(Intent.ACTION_VIEW,uri));}catch(Exception e){error("无法打开链接");}});
            }catch(Exception ignored){}
        }
        @JavascriptInterface public void taskRequest(String raw){engine.taskRequest(raw);}
        @JavascriptInterface public void forgetAutoConnect(){pendingConnection=null;io.execute(()->{try{store.put("auto-connect","");event("credentials",new JSONObject().put("saved",false));}catch(Exception e){error("清除自动连接凭据失败");}});}
        @JavascriptInterface public void connect(String raw){if(selectingFiles){error("请等待文件上传完成再切换电脑");return;}io.execute(()->{try{JSONObject p=new JSONObject(raw);String saved=store.get("auto-connect");if(p.optBoolean("autoConnect")&&p.optString("password").isEmpty()&&!saved.isEmpty()){JSONObject previous=new JSONObject(saved);if(p.optString("host").trim().equals(previous.optString("host").trim())&&p.optString("user").trim().equals(previous.optString("user").trim())&&p.optInt("port",22)==previous.optInt("port",22))p.put("password",previous.optString("password"));}pendingConnection=p;if(!p.optBoolean("autoConnect"))store.put("auto-connect","");engine.connect(p,privateKey);}catch(Exception e){error(e.getMessage());}});}
        @JavascriptInterface public void send(String raw){
            JSONObject request;try{request=new JSONObject(raw);}catch(Exception e){error("消息格式无效");return;}
            io.execute(()->{try{inputFiles.attach(request,engine.computerKey());engine.send(request);}catch(Exception e){try{event("rejected",new JSONObject().put("messageId",request.optString("messageId")).put("prompt",request.optString("displayPrompt",request.optString("prompt"))).put("uploads",request.optJSONArray("uploads")));event("busy",new JSONObject().put("value",false));}catch(Exception ignored){}error(e.getMessage());}});
        }
        @JavascriptInterface public void pickFiles(int available){
            runOnUiThread(()->{if(selectingFiles)return;if(!engine.ssh.connected()){error("请先连接电脑再上传文件");return;}if(available<1||available>4){error("每条消息最多上传 4 个文件");return;}
                selectingFiles=true;pickerComputer=engine.computerKey();pickerLimit=available;
                Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("*/*");intent.addCategory(Intent.CATEGORY_OPENABLE);intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
                try{event("inputFilesBusy",new JSONObject().put("value",true).put("message","正在选择文件…"));startActivityForResult(intent,30);}catch(Exception e){selectingFiles=false;error("无法打开文件选择器");try{event("inputFilesBusy",new JSONObject().put("value",false));}catch(Exception ignored){}}
            });
        }
        @JavascriptInterface public void removeInputFile(String id){io.execute(()->{try{inputFiles.remove(id);store.put("input-files",inputFiles.save());}catch(Exception e){error("移除附件失败");}});}
        @JavascriptInterface public void copyText(String text){
            if(text==null||text.length()>150000)return;
            runOnUiThread(()->((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Agent 回复",text)));
        }
        @JavascriptInterface public void listDownloads(String run){engine.listDownloads(run);}
        @JavascriptInterface public void addDownload(String run,String path){engine.addDownload(run,path);}
        @JavascriptInterface public void tapDesktop(String token,double x,double y){engine.tapDesktop(token,x,y);}
        @JavascriptInterface public void collectDesktop(String run){engine.collectDesktop(run);}
        @JavascriptInterface public void inspectDesktop(String run){engine.inspectDesktop(run);}
        @JavascriptInterface public void operateDesktop(String token,String control,String value){engine.operateDesktop(token,control,value);}
        @JavascriptInterface public void respond(String token,String raw){try{if(raw.length()>40000)return;engine.respond(token,new JSONObject(raw));}catch(Exception e){error(e.getMessage());}}
        @JavascriptInterface public void previewAttachment(String id){
            Attachments.FileRef file=engine.attachments.get(id);if(file!=null&&(file.preview||RemoteImages.allowed(file.path)||file.name.matches("(?i).*\\.(png|jpe?g|webp|gif|bmp)$")))engine.download(id);
        }
        @JavascriptInterface public void requestAttachment(String id){
            Attachments.FileRef file=engine.attachments.get(id);if(file==null){error("附件链接已失效，请重新获取回复");return;}
            runOnUiThread(()->new AlertDialog.Builder(MainActivity.this).setTitle("读取这个附件？").setMessage(file.name+"\n\n"+(RemoteImages.allowed(file.path)?"从豆包图片服务读取":file.inline==null?"从已连接的电脑读取：\n"+file.path:"预览 Agent 返回的图片")+"\n\n最大 20MB，保存位置由你选择。").setNegativeButton("取消",(d,w)->{try{event("attachmentError",new JSONObject().put("attachmentId",id).put("message","已取消读取"));}catch(Exception ignored){}}).setPositiveButton("读取附件",(d,w)->engine.download(id)).setOnCancelListener(d->{try{event("attachmentError",new JSONObject().put("attachmentId",id).put("message","已取消读取"));}catch(Exception ignored){}}).show());
        }
        @JavascriptInterface public void exportAttachment(String id){runOnUiThread(()->export(id));}
        @JavascriptInterface public void cancel(){engine.cancel();}
        @JavascriptInterface public void save(String raw){
            if(raw.length()>300000)return;
            io.execute(()->{try{JSONObject j=new JSONObject(raw);if(j.has("profile"))j.getJSONObject("profile").remove("password");store.put("state",j.toString());}catch(Exception e){error("保存失败："+e.getMessage());}});
        }
        @JavascriptInterface public void importKey(){runOnUiThread(()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,10);});}
        @JavascriptInterface public void removeKey(){privateKey=null;io.execute(()->{try{store.put("private-key","");event("key",new JSONObject().put("loaded",false));}catch(Exception e){error(e.getMessage());}});}
        @JavascriptInterface public void resetTrust(String host){
            if(engine.busy.get()){error("任务执行中不能重置配对");return;}
            runOnUiThread(()->new AlertDialog.Builder(MainActivity.this).setTitle("重置电脑配对？").setMessage("仅清除本机记录的主机指纹。下次连接必须重新核验电脑指纹。").setNegativeButton("取消",null).setPositiveButton("重置",(d,w)->io.execute(()->{try{store.put("host:"+host.toLowerCase(java.util.Locale.ROOT),"");engine.disconnect();event("connection",new JSONObject().put("status","未连接"));}catch(Exception e){error(e.getMessage());}})).show());
        }
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==30){
            if(result!=RESULT_OK||data==null){selectingFiles=false;try{event("inputFilesBusy",new JSONObject().put("value",false));}catch(Exception ignored){}return;}
            java.util.List<android.net.Uri> selected=new java.util.ArrayList<>();
            if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)selected.add(data.getClipData().getItemAt(i).getUri());else if(data.getData()!=null)selected.add(data.getData());
            io.execute(()->{try{
                if(selected.size()>pickerLimit)throw new IOException("本次最多选择 "+pickerLimit+" 个文件");
                for(android.net.Uri uri:selected){
                    if(!pickerComputer.equals(engine.computerKey()))throw new IOException("电脑连接已改变，请重新选择文件");
                    String name="文件";long declared=-1;
                    try(android.database.Cursor cursor=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME,android.provider.OpenableColumns.SIZE},null,null,null)){
                        if(cursor!=null&&cursor.moveToFirst()){int n=cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME),s=cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);if(n>=0&&!cursor.isNull(n))name=cursor.getString(n);if(s>=0&&!cursor.isNull(s))declared=cursor.getLong(s);}
                    }
                    if(declared>InputFiles.LIMIT)throw new IOException(name+" 超过 20MB");
                    event("inputFilesBusy",new JSONObject().put("value",true).put("message","正在上传："+name));
                    byte[] bytes;try(InputStream input=getContentResolver().openInputStream(uri)){bytes=InputFiles.read(input);}
                    String mime=getContentResolver().getType(uri);JSONObject uploaded=engine.uploadInput(name,mime==null?"application/octet-stream":mime,bytes);
                    JSONObject file=inputFiles.add(uploaded);store.put("input-files",inputFiles.save());event("inputFileReady",file);
                }
            }catch(Exception e){error("上传文件失败："+e.getMessage());}finally{selectingFiles=false;try{event("inputFilesBusy",new JSONObject().put("value",false));}catch(Exception ignored){}}});return;
        }
        if(request==20){
            byte[] bytes=exportBytes;String name=exportName;exportBytes=null;exportName=null;exporting=false;
            if(result!=RESULT_OK||data==null||bytes==null)return;
            android.net.Uri uri=data.getData();
            io.execute(()->{try{try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException("无法写入所选位置");out.write(bytes);}
                runOnUiThread(()->new AlertDialog.Builder(this).setTitle("已保存 "+name).setMessage("可使用手机上的应用打开文件。").setNegativeButton("完成",null).setPositiveButton("打开",(d,w)->{try{Intent open=new Intent(Intent.ACTION_VIEW);open.setDataAndType(uri,mime(name));open.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);open.setClipData(ClipData.newRawUri("附件",uri));startActivity(Intent.createChooser(open,"打开附件"));}catch(Exception e){error("文件已保存，手机没有可打开该格式的应用");}}).show());
            }catch(Exception e){error("保存附件失败："+e.getMessage());}});return;
        }
        if(request!=10||result!=RESULT_OK||data==null)return;
        io.execute(()->{try(InputStream in=getContentResolver().openInputStream(data.getData())){
            ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] chunk=new byte[4096];int n;while((n=in.read(chunk))>0){b.write(chunk,0,n);if(b.size()>65536)throw new IOException("密钥文件超过 64KB");}
            byte[] bytes=b.toByteArray();if(!new String(bytes,StandardCharsets.UTF_8).contains("PRIVATE KEY"))throw new IOException("请选择 PEM 或 OpenSSH 私钥文件");
            store.put("private-key",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP));privateKey=bytes;event("key",new JSONObject().put("loaded",true));
        }catch(Exception e){error("导入失败："+e.getMessage());}});
    }
    @Override public void onBackPressed(){web.evaluateJavascript("window.back()",null);}
    @Override protected void onResume(){super.onResume();if(engine!=null)engine.checkConnection();}
    @Override protected void onDestroy(){engine.close();web.removeJavascriptInterface("Pocket");web.destroy();io.shutdown();super.onDestroy();}
}
