// App-scoped remote view and explicit user operations, injected after shared AX helpers.
ObjC.bindFunction('CGWindowListCopyWindowInfo',['id',['uint32','uint32']]);
ObjC.bindFunction('CGPreflightScreenCaptureAccess',['bool',[]]);
function bounds(node){var p=attr(node.raw,'AXPosition'),s=attr(node.raw,'AXSize');return p&&s?parseBounds(String(ObjC.unwrap($.CFCopyDescription(p))),String(ObjC.unwrap($.CFCopyDescription(s)))):null;}
function panelState(nodes){
 var win=nodes[0],frame=bounds(win);if(!frame)fail('无法读取应用窗口位置');
 var controls=[];
 nodes.forEach(function(n,index){
  if(!/^(AXButton|AXRadioButton|AXCheckBox|AXTextField|AXTextArea|AXPopUpButton)$/.test(n.role)||/^(已完成，用时|深度求索中，用时)/.test(title(n)))return;
  var r=bounds(n);if(!r||r.x<frame.x||r.y<frame.y||r.x+r.w>frame.x+frame.w+2||r.y+r.h>frame.y+frame.h+2)return;
  if(!/^(1|true)$/.test(string(n.raw,'AXEnabled')))return;
  var editable=/AXTextField|AXTextArea/.test(n.role);if(!title(n)&&!editable)return;
  if(string(n.raw,'AXSubrole')==='AXSecureTextField')return;
  controls.push({id:String(index),label:title(n)||string(n.raw,'AXPlaceholderValue')||'输入框 '+index,editable:editable,value:editable?n.value:'',bounds:r});
 });
 if(controls.length>120)fail('当前控件过多，请在电脑收起无关面板后再查看');
 // Exact comparison across processes, including geometry, text, draft and app process.
 var fingerprint=JSON.stringify({pid:Number(application.processIdentifier),frame:frame,questions:text(nodes).filter(function(t){return /[?？]$/.test(t.trim());}).slice(-8),controls:controls});
 if(fingerprint.length>350000)fail('电脑画面内容过多，暂不能远程操作');
 return {controls:controls,fingerprint:fingerprint,frame:frame};
}
function task(executable,args){
 var t=$.NSTask.alloc.init;t.launchPath=$(executable);t.arguments=$(args);var pipe=$.NSPipe.pipe;t.standardError=pipe;t.standardOutput=$.NSFileHandle.fileHandleWithNullDevice;
 t.launch;t.waitUntilExit;if(Number(t.terminationStatus)!==0)throw new Error('截图命令失败，请检查屏幕录制权限');
}
function capturePanel(){
 if(!$.CGPreflightScreenCaptureAccess())throw new Error('需要在电脑的隐私与安全 → 屏幕与系统音频录制中授权当前 SSH/osascript 会话，才能查看图片画面。');
 var infos=ObjC.deepUnwrap($.CGWindowListCopyWindowInfo(1,0)),targetFrame=bounds(nodes[0]);
 var matches=infos.filter(function(row){var b=row.kCGWindowBounds;return row.kCGWindowOwnerPID===Number(application.processIdentifier)&&row.kCGWindowLayer===0&&b&&targetFrame&&Math.abs(b.X-targetFrame.x)<3&&Math.abs(b.Y-targetFrame.y)<3&&Math.abs(b.Width-targetFrame.w)<3&&Math.abs(b.Height-targetFrame.h)<3;});
 if(matches.length!==1)throw new Error('无法唯一匹配当前对话窗口，未截图');
 var path=String(ObjC.unwrap($.NSTemporaryDirectory()))+'orca-pocket-'+String(ObjC.unwrap($.NSUUID.UUID.UUIDString))+'.jpg';
 try{
  task('/usr/sbin/screencapture',['-x','-o','-t','jpg','-l',String(matches[0].kCGWindowNumber),path]);
  task('/usr/bin/sips',['-s','formatOptions','55','-Z','1280',path]);
  var data=$.NSData.dataWithContentsOfFile($(path));if(!data||Number(data.length)>500000)throw new Error('电脑截图超过显示限制');
  return String(ObjC.unwrap(data.base64EncodedStringWithOptions(0)));
 }finally{$.NSFileManager.defaultManager.removeItemAtPathError($(path),null);}
}
function pasteField(node,value){
 if(value.length>20000)fail('输入内容过长');
 if($.AXUIElementSetAttributeValue(node.raw,$('AXFocused'),$(true))!==0)fail('电脑输入框未获得焦点');
 delay(0.2);if(!isFrontmost(application)||!/^(1|true)$/.test(string(node.raw,'AXFocused')))fail('未确认输入框焦点，未输入');
 var board=$.NSPasteboard.generalPasteboard,saved=[],items=board.pasteboardItems;
 for(var i=0;i<Number(items.count);i++){var old=items.objectAtIndex(i),formats=[];for(var j=0;j<Number(old.types.count);j++){var format=old.types.objectAtIndex(j);formats.push([format,old.dataForType(format)]);}saved.push(formats);}
 var version=-1;
 try{board.clearContents;board.setStringForType($(value),$.NSPasteboardTypeString);version=Number(board.changeCount);var sys=Application('System Events');sys.keystroke('a',{using:'command down'});sys.keystroke('v',{using:'command down'});delay(0.3);
  if(normalize(string(node.raw,'AXValue'))!==normalize(value))fail('电脑未确认填入内容，请刷新画面检查');
 }finally{if(Number(board.changeCount)===version){board.clearContents;var restore=$.NSMutableArray.alloc.init;saved.forEach(function(formats){var item=$.NSPasteboardItem.alloc.init;formats.forEach(function(pair){item.setDataForType(pair[1],pair[0]);});restore.addObject(item);});if(Number(restore.count))board.writeObjects(restore);}}
}
if(!bundles[request.app])fail('未知的桌面应用');
if(!$.AXIsProcessTrusted())fail('电脑尚未授予辅助功能权限');
var running=$.NSRunningApplication.runningApplicationsWithBundleIdentifier($(bundles[request.app]));
if(Number(running.count)!==1)fail('目标应用未运行或存在多个进程');
var application=running.objectAtIndex(0),root=$.AXUIElementCreateApplication(application.processIdentifier),nodes=snapshot(root);
DesktopSession.marker(request.prompt);
if(!extract(nodes,request.prompt,[]).sawUser)fail('电脑已切换对话，未读取或操作其他会话');
ownedView(nodes,request.prompt,null);
var boundStore=storedSession(request.prompt,null);if(boundStore===null)fail('未绑定本次请求的会话记录，未读取或操作。');
var panel=panelState(nodes);
if(request.action==='point'){
 if(panel.fingerprint!==request.fingerprint)fail('电脑界面已变化，请先刷新画面');
 var x=Number(request.x),y=Number(request.y);if(!isFinite(x)||!isFinite(y)||x<0||x>1||y<0||y>1)fail('点击位置无效');
 application.activateWithOptions(3);var until=Date.now()+3000;while(!isFrontmost(application)&&Date.now()<until)delay(0.1);
 if(!isFrontmost(application))fail('目标应用没有获得焦点');
 var point=$.CGPointMake(panel.frame.x+x*panel.frame.w,panel.frame.y+y*panel.frame.h);
 var down=$.CGEventCreateMouseEvent(null,$.kCGEventLeftMouseDown,point,$.kCGMouseButtonLeft),up=$.CGEventCreateMouseEvent(null,$.kCGEventLeftMouseUp,point,$.kCGMouseButtonLeft);
 $.CGEventSetFlags(down,0);$.CGEventSetFlags(up,0);$.CGEventPost($.kCGHIDEventTap,down);delay(0.05);$.CGEventPost($.kCGHIDEventTap,up);
 event({type:'desktop.operated',message:'已点击电脑画面，请刷新核对结果'});
}else if(request.action==='collect'){
 var reply=extract(nodes,request.prompt,[]);if(!reply.complete||busy(nodes))fail('请等待电脑完成回复后再提取附件');
 application.activateWithOptions(3);var until=Date.now()+3000;while(!isFrontmost(application)&&Date.now()<until)delay(0.1);
 var rich=boundStore!==undefined?boundStore.text:reply.text;
 if(boundStore!==undefined&&!boundStore.complete)fail('本次请求尚未完成，未提取其他回复。');
 emitReplyFiles(reply);emitReplyImages(reply);event({type:'desktop.reply',text:rich});
}else if(request.action==='operate'){
 if(panel.fingerprint!==request.fingerprint)fail('电脑界面已变化，请刷新后重新选择操作');
 var control=panel.controls.filter(function(c){return c.id===request.control;});if(control.length!==1)fail('操作控件已失效');
 var target=nodes[Number(request.control)];
 application.activateWithOptions(3);var until=Date.now()+3000;while(!isFrontmost(application)&&Date.now()<until)delay(0.1);
 if(control[0].editable)pasteField(target,String(request.value||''));else nativeClick(target,application);
 delay(0.3);event({type:'desktop.operated',message:control[0].editable?'电脑已确认输入内容':'已发出点击，请刷新画面核对结果'});
}else{
 var preview='',warning='';try{preview=capturePanel();}catch(e){warning=String(e.message||e);}
 event({type:'desktop.panel',fingerprint:panel.fingerprint,controls:panel.controls,preview:preview?('data:image/jpeg;base64,'+preview):'',warning:warning,text:text(nodes).join('\n').slice(-20000)});
}
