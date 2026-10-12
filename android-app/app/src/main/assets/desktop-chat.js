// Transient macOS adapter. AX calls avoid the latency of per-element Apple Events.
ObjC.import('Foundation');ObjC.import('AppKit');ObjC.import('ApplicationServices');ObjC.import('CoreGraphics');
ObjC.bindFunction('CFCopyDescription',['id',['id']]);
ObjC.bindFunction('AXUIElementCreateApplication',['id',['int']]);
ObjC.bindFunction('AXUIElementCopyAttributeValue',['int',['id','id','id *']]);
ObjC.bindFunction('AXUIElementSetAttributeValue',['int',['id','id','id']]);
ObjC.bindFunction('AXUIElementPerformAction',['int',['id','id']]);
var request=__REQUEST__;
var bundles={TeleAgent:'com.teleai.superagent',Doubao:'com.bot.pc.doubao',WorkBuddy:'com.tencent.workbuddy.mac',Claude:'com.anthropic.claudefordesktop',Codex:'com.openai.codex','DeepSeek Harness':'com.deepseek.dsh'};
function event(value){$.NSFileHandle.fileHandleWithStandardOutput.writeData($(JSON.stringify(value)+'\n').dataUsingEncoding($.NSUTF8StringEncoding));}
function emit(type,text){var line=JSON.stringify({type:type,text:text,message:text})+'\n';$.NSFileHandle.fileHandleWithStandardOutput.writeData($(line).dataUsingEncoding($.NSUTF8StringEncoding));}
function fail(text){emit('error',text);throw new Error(text);}
function attr(raw,key){var out=Ref();return $.AXUIElementCopyAttributeValue(raw,$(key),out)===0?out[0]:null;}
function string(raw,key){try{var v=attr(raw,key);return v?String(ObjC.unwrap(key==='AXURL'&&v.absoluteString?v.absoluteString:v)):'';}catch(_){return '';}}
function array(raw,key){var v=attr(raw,key),out=[];if(v){for(var i=0;i<Number(v.count);i++)out.push(v.objectAtIndex(i));}return out;}
function snapshot(root,quiet){
 var allWindows=array(root,'AXWindows'),windows=allWindows;if(windows.length!==1){windows=windows.filter(function(w){return string(w,'AXMain')==='true'||string(w,'AXMain')==='1';});}
 if(windows.length!==1){if(quiet)throw new Error('无法唯一识别应用主窗口');fail('无法唯一识别应用主窗口；请关闭弹窗后重试。');}
 var flat=[],started=Date.now(),limit=request.app==='TeleAgent'?20000:8000;
 // Iterative traversal supports deeply nested Markdown without recursion/depth failures.
 // Embedded web previews are opaque: they are not the Agent's conversation or approval UI.
 function visit(window){
  var stack=[{raw:window,parent:null,webArea:false}];
  while(stack.length){
   if(flat.length>=limit)throw new Error('当前应用控件超过读取上限（'+limit+'），请收起电脑端展开的历史内容后重试；未操作其他会话。');
   if(Date.now()-started>20000)throw new Error('读取电脑当前界面超时；任务可能仍在执行，请在电脑查看。');
   var entry=stack.pop(),raw=entry.raw,parent=entry.parent,cycle=false;
   for(var ancestor=parent;ancestor;ancestor=ancestor.parent){if(raw.isEqual(ancestor.raw)){cycle=true;break;}}
   if(cycle)continue;
   var role=string(raw,'AXRole'),embedded=request.app==='TeleAgent'&&role==='AXWebArea'&&entry.webArea;
    var node={raw:raw,role:role,parent:parent,children:[],name:'',description:'',value:''};
   if(/AXButton|AXRadioButton|AXMenuItem|AXStaticText|AXTextArea|AXTextField|AXComboBox|AXRow|AXCell|AXGroup|AXWebArea|AXDialog|AXSheet|AXWindow|AXImage|AXLink/.test(role)){
    node.name=string(raw,'AXTitle');node.description=string(raw,'AXDescription');
    if(/AXStaticText|AXTextArea|AXTextField|AXComboBox|AXCell/.test(role))node.value=string(raw,'AXValue');
   }
   flat.push(node);if(parent)parent.children.push(node);
   if(embedded){node.opaque=true;continue;}
   var children=array(raw,'AXChildren');
   for(var i=children.length-1;i>=0;i--)stack.push({raw:children[i],parent:node,webArea:entry.webArea||role==='AXWebArea'});
  }
 }
 visit(windows[0]);allWindows.forEach(function(w){if(!w.isEqual(windows[0])&&string(w,'AXSubrole')==='AXDialog')visit(w);});return flat;
}
function title(n){return n.name||n.description;}
function button(n){return /AXButton|AXRadioButton/.test(n.role);}
function freshEntry(n){return button(n)&&/^(新对话|新建任务|新建会话|New chat|New task)(\s|$)/.test(title(n));}
function deepseekBlank(nodes){return nodes.some(function(n){return n.role==='AXTextArea'&&title(n)==='描述你想要构建的内容, / 调用指令, @ 文件或对话';})&&!nodes.some(function(n){return button(n)&&/^已完成，用时/.test(title(n));});}
function busy(nodes){return nodes.some(function(n){return button(n)&&(/^(停止|停止生成|停止回答|Stop|Stop generating)$/.test(title(n))||request.app==='TeleAgent'&&/^暂停（输入/.test(title(n)));});}
function teleagentBlank(nodes){var fields=nodes.filter(function(n){return n.role==='AXTextArea';});return fields.length===1&&!normalize(fields[0].value)&&text(nodes).some(function(t){return t==='你说我做，随时待命';})&&!nodes.some(function(n){return button(n)&&/^已完成本轮思考与执行/.test(title(n));});}
function text(nodes){return nodes.filter(function(n){return n.role==='AXStaticText';}).map(function(n){return n.value||n.name||n.description;}).filter(Boolean);}
function descendants(n){var out=[],stack=[n];while(stack.length){var x=stack.pop();out.push(x);for(var i=x.children.length-1;i>=0;i--)stack.push(x.children[i]);}return out;}
function isFrontmost(app){$.NSRunLoop.currentRunLoop.runUntilDate($.NSDate.dateWithTimeIntervalSinceNow(0.05));return Number($.NSWorkspace.sharedWorkspace.frontmostApplication.processIdentifier)===Number(app.processIdentifier);}
function parseBounds(position,size){
 var p=/x:([-+\d.]+) y:([-+\d.]+)/.exec(position),s=/w:([-+\d.]+) h:([-+\d.]+)/.exec(size);
 if(!p||!s)return null;
 var r={x:Number(p[1]),y:Number(p[2]),w:Number(s[1]),h:Number(s[2])};
 if(!isFinite(r.x)||!isFinite(r.y)||!isFinite(r.w)||!isFinite(r.h)||r.w<=0||r.h<=0||r.w>20000||r.h>20000)return null;
 return r;
}
function nativeClick(node,app){
 if(!isFrontmost(app))fail('目标应用不在前台，未点击控件。');
 var pos=attr(node.raw,'AXPosition'),size=attr(node.raw,'AXSize');
 if(!pos||!size)fail('无法读取控件位置，未点击。');
 var r=parseBounds(String(ObjC.unwrap($.CFCopyDescription(pos))),String(ObjC.unwrap($.CFCopyDescription(size))));
 if(!r)fail('控件位置无效，未点击。');
 var point=$.CGPointMake(r.x+r.w/2,r.y+r.h/2);
 var move=$.CGEventCreateMouseEvent(null,$.kCGEventMouseMoved,point,$.kCGMouseButtonLeft);
 var down=$.CGEventCreateMouseEvent(null,$.kCGEventLeftMouseDown,point,$.kCGMouseButtonLeft);
 var up=$.CGEventCreateMouseEvent(null,$.kCGEventLeftMouseUp,point,$.kCGMouseButtonLeft);
 if(!move||!down||!up)fail('无法创建原生点击事件。');
 $.CGEventSetFlags(move,0);$.CGEventSetFlags(down,0);$.CGEventSetFlags(up,0);
 $.CGEventPost($.kCGHIDEventTap,move);delay(0.05);
 $.CGEventPost($.kCGHIDEventTap,down);delay(0.05);$.CGEventPost($.kCGHIDEventTap,up);
}
function newTaskReady(nodes){return nodes.some(function(n){return n.role==='AXRadioButton'&&title(n)==='新建任务'&&/^(1|true)$/.test(string(n.raw,'AXValue'));});}
// AXPress may report success without changing an Electron window. Verify navigation,
// then focus and activate the button by keyboard before typing (never resend a task).
function keyboardActivate(node,application){
 if(!isFrontmost(application))fail('目标应用不在前台，未激活按钮。');
 if($.AXUIElementSetAttributeValue(node.raw,$('AXFocused'),$(true))!==0)fail('目标按钮无法获得焦点，未操作。');
 var until=Date.now()+2000;
 while(!/^(1|true)$/.test(string(node.raw,'AXFocused'))&&Date.now()<until){$.NSRunLoop.currentRunLoop.runUntilDate($.NSDate.dateWithTimeIntervalSinceNow(0.1));delay(0.1);}
 if(!/^(1|true)$/.test(string(node.raw,'AXFocused'))||!isFrontmost(application))fail('未确认目标按钮获得焦点，未操作。');
 Application('System Events').keyCode(49);
}
function waitDeepseekBlank(root,timeout){
 var until=Date.now()+timeout,nodes;
 do{
  $.NSRunLoop.currentRunLoop.runUntilDate($.NSDate.dateWithTimeIntervalSinceNow(0.1));
  nodes=snapshot(root);
  if(deepseekBlank(nodes))return nodes;
  delay(0.2);
 }while(Date.now()<until);
 return nodes;
}
function confirmDeepseekNavigation(root,application){
 var nodes=waitDeepseekBlank(root,2500);
 if(deepseekBlank(nodes))return nodes;
 if(busy(nodes))fail('DeepSeek 当前正在执行任务，未重试新建会话。');
 var entries=nodes.filter(freshEntry);
 if(entries.length!==1)fail('无法唯一识别 DeepSeek 新建会话按钮，未输入消息。');
 emit('status','正在重新打开 DeepSeek 新会话');
 keyboardActivate(entries[0],application);
 nodes=waitDeepseekBlank(root,10000);
 if(!deepseekBlank(nodes)){
  var fields=nodes.filter(function(n){return n.role==='AXTextArea';});
  fail('DeepSeek 新建会话未生效，未发送消息。请保持电脑解锁且窗口可见后重试。（输入框数量：'+fields.length+'；仍在已有会话：'+nodes.some(function(n){return button(n)&&/^已完成，用时/.test(title(n));})+'）');
 }
 return nodes;
}
function normalize(t){return String(t||'').replace(/\uFEFF/g,'').replace(/\s+/g,' ').trim();}
function messageKey(t){return normalize(t).replace(/[\s\u200B\u200C\u200D\u2060]/g,'');}
function inEditor(n){for(var p=n;p;p=p.parent)if(p.role==='AXTextArea'||p.role==='AXTextField')return true;return false;}
// Bind the unique request to its visible turn, not to whichever reply is newest.
function editorWebArea(node){for(var p=node;p;p=p.parent)if(p.role==='AXWebArea')return p;return null;}
function receivingEditor(nodes,original){
 var fields=nodes.filter(function(n){return n.role==='AXTextArea'&&n.raw.isEqual(original.raw);});
 if(fields.length===1)return fields[0];
 // Electron may replace the composer after send. Only rebind inside the same web area.
 var scope=editorWebArea(original);
 if(!scope)fail('电脑输入框归属不明确，已停止接收。');
 fields=nodes.filter(function(n){var area=editorWebArea(n);return n.role==='AXTextArea'&&area&&area.raw.isEqual(scope.raw);});
 if(fields.length!==1)fail('电脑输入框归属不明确，已停止接收。');
 return fields[0];
}
function editorDraft(node){
 var value=node.value||'';
 // Doubao exposes its empty-editor hint as AXValue and as a child text node.
 if(request.app==='Doubao'&&/^发消息或按住空格说话(?:\.\.\.|…)?$/.test(value.trim())&&text(descendants(node)).length===1&&text(descendants(node))[0].trim()===value.trim())return '';
 return value;
}
function ownedView(nodes,prompt,previous,quiet){
 function reject(message){if(quiet)throw new Error(message);fail(message);}
 DesktopSession.marker(prompt);var wanted=DesktopSession.key(prompt),matches=[];
 var eligible=nodes.filter(function(n){return !inEditor(n);});
 for(var start=0;start<eligible.length;start++){
  if(eligible[start].role!=='AXStaticText')continue;
  var collected='';
  for(var end=start;end<eligible.length;end++){
   if(button(eligible[end]))break;
   if(eligible[end].role==='AXStaticText')collected+=DesktopSession.key(eligible[end].value||eligible[end].name||eligible[end].description);
   if(collected===wanted){matches.push(eligible[end]);break;}
   if(collected.length>wanted.length||wanted.indexOf(collected)!==0)break;
  }
 }
 if(matches.length!==1)reject('当前对话缺少唯一请求标识或标识重复，已停止本轮。');
 var anchor=matches[0],turn=anchor.parent;
 if(request.app==='DeepSeek Harness'){
  while(turn&&!turn.children.some(function(n){return button(n)&&title(n)==='复制';}))turn=turn.parent;
  if(!turn||!turn.parent)reject('无法绑定当前回复轮次，已停止读取。');
  var siblings=turn.parent.children;
  if(siblings.slice(siblings.indexOf(turn)+1).some(function(n){return descendants(n).some(function(c){return button(c)&&title(c)==='复制';});}))reject('电脑插入了其他对话轮次，已停止本轮。');
 }else if(request.app==='Doubao'){
  var scopes=nodes.filter(function(n){return title(n)==='doc_editor';});
  if(scopes.length!==1)reject('无法绑定豆包聊天区域。');
  var doc=scopes[0];while(turn&&turn.parent!==doc)turn=turn.parent;
  if(!turn)reject('请求不属于当前聊天区域。');
  var following=doc.children.slice(doc.children.indexOf(turn)+1).filter(function(n){return n.role==='AXGroup'&&descendants(n).some(function(c){return c.role==='AXStaticText'||c.role==='AXImage';});});
  if(following.length>1)reject('电脑聊天区域增加了其他消息，已停止本轮。');
 }
 var identity={anchor:anchor.raw,turn:turn&&turn.raw,window:nodes[0].raw,scope:request.app==='Doubao'?doc.raw:null};
 if(previous){
  var sameWindow=identity.window.isEqual(previous.window);
  var sameTurn=identity.anchor.isEqual(previous.anchor)&&identity.turn&&identity.turn.isEqual(previous.turn);
  // Doubao replaces message text nodes during streaming; pin its transcript container instead.
  var sameTranscript=request.app==='Doubao'&&identity.scope&&previous.scope&&identity.scope.isEqual(previous.scope);
  if(!sameWindow||(!sameTurn&&!sameTranscript))reject('电脑消息或会话身份已变化，已停止本轮。');
 }
 return identity;
}
function extract(nodes,prompt,baseline){
 var scope=nodes;
 if(request.app==='Doubao'){
  var scopes=nodes.filter(function(n){return title(n)==='doc_editor';});
  if(scopes.length!==1)return {text:'',sawUser:false,complete:false};scope=descendants(scopes[0]);
 }
 scope=scope.filter(function(n){return !inEditor(n);});
 var userIndex=-1,wanted=messageKey(prompt);
 // WorkBuddy exposes multi-paragraph messages as several AXStaticText nodes.
 for(var start=0;start<scope.length;start++){
  if(scope[start].role!=='AXStaticText')continue;
  var collected='';
  for(var j=start;j<scope.length;j++){
   if(button(scope[j]))break;
   if(scope[j].role==='AXStaticText')collected=messageKey(collected+(scope[j].value||scope[j].name||scope[j].description));
   if(collected===wanted){userIndex=j;break;}
   if(collected.length>wanted.length||wanted.indexOf(collected)!==0)break;
  }
 }
 if(userIndex<0)return {text:'',sawUser:false,complete:false};
 if(request.app==='TeleAgent'){
  // A user bubble owns its timestamp and copy control. Its parent is the transcript.
  var user=scope[userIndex].parent;
  while(user&&!descendants(user).some(function(n){return button(n)&&title(n)==='复制';}))user=user.parent;
  if(!user||!user.parent)return {text:'',sawUser:false,complete:false};
  if(descendants(user).some(function(n){return n.role==='AXTextArea'||button(n)&&/^已完成本轮思考与执行/.test(title(n));}))return {text:'',sawUser:false,complete:false};
  var siblings=user.parent.children,startAt=siblings.indexOf(user),segment=[],finished=false,hasCopy=false,hasProgress=false,images=[];
  for(var ti=startAt+1;ti<siblings.length;ti++){
   var item=siblings[ti];
   if(descendants(item).some(function(n){return button(n)&&title(n)==='复制';}))break;
   if(item.role==='AXTextArea'||descendants(item).some(function(n){return n.role==='AXTextArea';}))break;
   if(button(item)&&/^已完成本轮思考与执行/.test(title(item))){segment=[];images=[];finished=true;hasCopy=false;continue;}
   var parts=descendants(item);
   if(parts.some(function(n){return button(n)&&/^(正在思考|思考中|正在执行|执行中)/.test(title(n));}))hasProgress=true;
   var toolbar=-1;
   for(var tc=0;tc<parts.length;tc++)if(button(parts[tc])&&title(parts[tc])==='仅复制文本'){toolbar=tc;hasCopy=true;break;}
   if(toolbar>=0)parts=parts.slice(0,toolbar);
   // Simple chat turns omit the execution marker; the final copy toolbar is their completion signal.
   // Thinking/tool turns still require their explicit completion marker.
   var previous=null;
   parts.forEach(function(n){
    if(n.role==='AXImage')images.push(n);
    if(n.role!=='AXStaticText'||inEditor(n))return;
    for(var ancestor=n.parent;ancestor&&ancestor!==user.parent;ancestor=ancestor.parent)if(button(ancestor))return;
    var value=n.value||n.name||n.description||'';if(!value||/^\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2}/.test(value))return;
    if(previous&&previous===n.parent&&segment.length)segment[segment.length-1]+=value;else segment.push(value);
    previous=n.parent;
   });
  }
  var completed=hasCopy&&(finished||!hasProgress)&&!busy(nodes);
  return {text:completed?segment.join('\n\n').trim():'',sawUser:true,complete:completed,media:images.length>0,images:images};
 }
 if(request.app==='DeepSeek Harness'){
  // The turn owns both the user's copy button and the assistant's final copy button.
  // Only accept a completed turn, and exclude timing, tool cards and footer metrics.
  var turn=scope[userIndex].parent;
  while(turn&&!turn.children.some(function(n){return button(n)&&title(n)==='复制';}))turn=turn.parent;
  if(!turn)return {text:'',sawUser:false,complete:false};
  var finish=-1,copy=-1;
  for(var di=0;di<turn.children.length;di++){
   var child=turn.children[di];
   if(button(child)&&/^已完成，用时/.test(title(child)))finish=di;
   if(finish>=0&&di>finish&&button(child)&&title(child)==='复制'){copy=di;break;}
  }
  if(finish<0||copy<0)return {text:'',sawUser:true,complete:false};
  var answer=[];
  turn.children.slice(finish+1,copy).forEach(function(n){
   var lastParent=null;
   descendants(n).forEach(function(part){
    if(part.role!=='AXStaticText'||inEditor(part))return;
    for(var ancestor=part.parent;ancestor&&ancestor!==turn;ancestor=ancestor.parent)if(button(ancestor))return;
    var value=part.value||part.name||'';
    if(lastParent===part.parent&&answer.length)answer[answer.length-1]+=value;else answer.push(value);
    lastParent=part.parent;
   });
  });
  return {text:answer.join('\n\n').trim(),sawUser:true,complete:true,media:descendants(turn).some(function(n){return n.role==='AXImage';}),images:turn.children.slice(finish+1,copy).reduce(function(a,n){return a.concat(descendants(n).filter(function(c){return c.role==='AXImage';}));},[]),copyNode:turn.children[copy]};
 }
 if(request.app==='WorkBuddy'){
  var marker=-1;
  for(var wi=userIndex+1;wi<scope.length;wi++)if(scope[wi].role==='AXStaticText'&&scope[wi].value==='WorkBuddy'){marker=wi;break;}
  if(marker<0)return {text:'',sawUser:true,complete:false};
  var bubble=scope[marker].parent;
  var response=bubble?descendants(bubble):scope.slice(marker);
  var output=[],done=false,hasCopy=false,lastParent=null;
  for(var ri=0;ri<response.length;ri++){
   var part=response[ri],v=part.value||part.name||'';
   if(button(part)&&/^(复制|Copy)$/.test(title(part))){hasCopy=true;break;}
   if(part.role!=='AXStaticText')continue;
   if(/^已完成(?:\s|$)/.test(v)){done=true;continue;}
   if(v==='WorkBuddy'||/^(已处理|准备中|等待模型响应|深度思考|生成回复中|过程消息)/.test(v))continue;
   if(part.parent&&part.parent===lastParent&&output.length)output[output.length-1]+=v;
   else output.push(v);
   lastParent=part.parent;
  }
  return {text:done&&hasCopy?output.join('\n\n').trim():'',sawUser:true,complete:done&&hasCopy,media:response.some(function(n){return n.role==='AXImage';}),files:outputFileNames(response),copyNode:response.filter(function(n){return button(n)&&/^(复制|Copy)$/.test(title(n));})[0]};
 }
 var end=scope.length,complete=false;
 for(var i=userIndex+1;i<scope.length;i++){
  var control=title(scope[i]);
  if(button(scope[i]) && (request.app==='Doubao' ? control==='朗读' : /^(复制|Copy)$/.test(control))){
   if(request.app==='Doubao'){end=i;complete=true;break;}
   if(text(scope.slice(userIndex+1,i)).some(function(t){return t!==prompt&&!/^(今天\s*)?\d{1,2}:\d{2}$/.test(t);}))complete=true;
  }
 }
 var seen={},parts=text(scope.slice(userIndex+1,end)).filter(function(t){
  if(messageKey(t)===messageKey(prompt)||/^(展开全部|收起全部|展开|收起)$/.test(t)||/^(今天\s*)?\d{1,2}:\d{2}$/.test(t)||/^(复制|重新生成|分享|内容由 AI)/.test(t)||seen[t])return false;seen[t]=true;return true;
 });
 var media=scope.slice(userIndex+1,end).some(function(n){return n.role==='AXImage';});
 if(media&&scope.slice(userIndex+1).some(function(n){return button(n)&&/^(下载|保存图片|下载图片|Download|复制图片)$/.test(title(n));}))complete=true;
 return {text:parts.join('\n\n'),sawUser:true,complete:complete,media:media,images:request.app==='Doubao'?scope.slice(userIndex+1,end).filter(function(n){return n.role==='AXImage';}):[]};
}
// File cards belong to the matched reply. Never infer a filename from ellipsized prose.
function outputFileNames(nodes){
 var names=[];nodes.forEach(function(n){var name=title(n);if(button(n)&&! /^(分享|预览|打开|下载|保存|Share|Preview|Open)\s/.test(name)&&/^[^/\\\n]+\.(png|jpe?g|webp|gif)$/i.test(name)&&name.indexOf('...')<0&&name.indexOf('…')<0&&name.length<255&&names.indexOf(name)<0)names.push(name);});return names.slice(0,12);
}
function dshImagePath(url){
 var match=/^dsh-app:\/\/app\/api\/file\?path=([^&#]+)$/.exec(url);if(!match)return '';
 try{var path=decodeURIComponent(match[1]);if(!/^\//.test(path)||/^\/\//.test(path)||/[\x00-\x1f]/.test(path)||!/\.(png|jpe?g|webp|gif)$/i.test(path))return '';return path;}catch(_){return '';}
}
function emitReplyImages(reply){
 if((request.app!=='Doubao'&&request.app!=='DeepSeek Harness')||!reply.complete)return 0;
 var seen={},count=0;(reply.images||[]).forEach(function(n){
  var value=attr(n.raw,'AXURL'),url='';try{url=value?String(ObjC.unwrap(value.absoluteString)):'';}catch(_){}
  if(request.app==='DeepSeek Harness'){
   var path=dshImagePath(url);if(!path||seen[path]||count>=8)return;seen[path]=true;count++;event({type:'attachment',path:path,preview:true});return;
  }
  if(!/^https:\/\/[a-z0-9.-]+\.byteimg\.com\//i.test(url)||seen[url])return;
  seen[url]=true;if(count>=8)return;count++;event({type:'attachment',url:url,name:'豆包回复图片 '+count+'.png'});
 });return count;
}
function emitReplyFiles(reply){
 if(request.app!=='WorkBuddy'||!reply.complete||!reply.files||!reply.files.length)return;
 var fm=$.NSFileManager.defaultManager,base=String(ObjC.unwrap($.NSHomeDirectory()))+'/WorkBuddy';
 var entries=fm.contentsOfDirectoryAtPathError($(base),null);if(!entries)return;
 var dirs=ObjC.deepUnwrap(entries).filter(function(n){return /^\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2}$/.test(n);});
 if(dirs.length>1000){emit('status','任务目录过多，请从电脑附件控件获取原文件');return;}
 reply.files.forEach(function(name){
  var matches=[];dirs.forEach(function(dir){var path=base+'/'+dir+'/generated-images/'+name;
   var attrs=ObjC.deepUnwrap(fm.attributesOfItemAtPathError($(path),null));
   if(attrs&&attrs.NSFileType==='NSFileTypeRegular'){
    var canonical=String(ObjC.unwrap($(path).stringByResolvingSymlinksInPath));
    if(canonical.indexOf(base+'/')===0)matches.push(canonical);
   }
  });
  if(matches.length===1)event({type:'attachment',path:matches[0]});
  else emit('status',matches.length?'同名图片不唯一，未自动选择原文件':'未找到回复图片的原文件，请使用电脑附件控件');
 });
}
// Rich replies often expose a button instead of the original file URI. Copy only the
// matched assistant's own reply, validate against its visible text, then restore clipboard.
function copyRichReply(reply){
 if(!reply.copyNode||!reply.text||!isFrontmost(application))return '';
 var board=$.NSPasteboard.generalPasteboard,saved=[],items=board.pasteboardItems,originalVersion=Number(board.changeCount),owned=-1;
 for(var i=0;i<Number(items.count);i++){var old=items.objectAtIndex(i),formats=[];for(var j=0;j<Number(old.types.count);j++){var format=old.types.objectAtIndex(j);formats.push([format,old.dataForType(format)]);}saved.push(formats);}
 try{
  $.AXUIElementPerformAction(reply.copyNode.raw,$('AXPress'));delay(0.2);
  if(Number(board.changeCount)===originalVersion){nativeClick(reply.copyNode,application);delay(0.2);}
  if(Number(board.changeCount)===originalVersion)return '';
  owned=Number(board.changeCount);var value=board.stringForType($.NSPasteboardTypeString);var copied=value?String(ObjC.unwrap(value)):'';
  if(copied.length>150000||messageKey(copied).indexOf(messageKey(reply.text.split('\n')[0]).slice(0,80))<0)return '';
  return copied;
 }finally{
  if(owned>=0&&Number(board.changeCount)===owned){board.clearContents;var restore=$.NSMutableArray.alloc.init;saved.forEach(function(formats){var item=$.NSPasteboardItem.alloc.init;formats.forEach(function(pair){item.setDataForType(pair[1],pair[0]);});restore.addObject(item);});if(Number(restore.count))board.writeObjects(restore);}
 }
}
// A question may be followed by explanatory parentheses in the same AX text node.
// A real dialog or card control is still required; punctuation alone is not authority.
function questionHeading(value){return /[?？](?:\s*[（(][\s\S]*[）)])?\s*$/.test(String(value||'').trim());}
function dialogOptions(dialog){return dialog.buttons.map(function(n){return {id:title(n),label:title(n),selected:n.role==='AXRadioButton'&&(string(n.raw,'AXValue')==='1'||string(n.raw,'AXSelected')==='1'||string(n.raw,'AXChecked')==='1')};});}
function teleagentProgressControl(label){return /^(?:暂不回答(?:[，,]\s*下一题|并提交)|下一题|提交(?:回答|答案|全部答案)?)\s*[（(]\d+\/\d+ 已回答[）)]$/.test(label);}
// Only actual accessibility dialogs/sheets are actionable. Ordinary chat text is not authority.
function pendingDialog(nodes){
 var candidates=nodes.filter(function(n){return n.role==='AXSheet'||n.role==='AXDialog'||string(n.raw,'AXSubrole')==='AXDialog'||string(n.raw,'AXARIARole')==='dialog';});
 // Input-method candidate windows may be AXDialog but have no question content.
 candidates=candidates.filter(function(n){return title(n)||text(descendants(n)).length;});
 candidates=candidates.filter(function(n){return !candidates.some(function(other){return other!==n&&descendants(other).indexOf(n)>0;});});
 if(candidates.length===0){
  var yes=/^(允许|允许一次|仅允许一次|批准|同意|Allow|Allow once|Approve|Accept)(\s|$)/i,no=/^(拒绝|不允许|取消|Deny|Reject|Decline|Cancel)(\s|$)/i;
  candidates=nodes.filter(function(n){if(n.role!=='AXGroup')return false;var parts=descendants(n),buttons=parts.filter(button);return parts.length<100&&buttons.length<=8&&buttons.some(function(b){return yes.test(title(b));})&&buttons.some(function(b){return no.test(title(b));});});
  candidates=candidates.filter(function(n){return !candidates.some(function(other){return other!==n&&descendants(n).indexOf(other)>0;});});
 }
 if(candidates.length===0){
  var marker=nodes.filter(function(n){return button(n)&&/^(全部跳过|放弃整组问题|收起问题卡片)$/.test(title(n));});
  marker.forEach(function(n){for(var p=n.parent;p;p=p.parent){var parts=descendants(p);if(parts.length>600)break;if(parts.filter(button).length<=12&&parts.filter(button).some(function(b){return /^(提交|发送|跳过|全部跳过)$/.test(title(b));})&&text(parts).some(function(t){return questionHeading(t);})){if(candidates.indexOf(p)<0)candidates.push(p);break;}}});
  candidates=candidates.filter(function(n){return !candidates.some(function(other){return other!==n&&descendants(n).indexOf(other)>0;});});
 }
 // TeleAgent renders an inline question card inside the transcript, not an AXDialog.
 // Require its real skip control, question marker, radio group and counted next/submit control.
 if(candidates.length===0&&request.app==='TeleAgent'){
  nodes.filter(function(n){return button(n)&&title(n)==='跳过问答';}).forEach(function(skip){
   for(var p=skip.parent;p;p=p.parent){
    var parts=descendants(p);if(parts.length>600)break;
    var marker=parts.filter(function(n){return n.role==='AXStaticText'&&/^交互式问答 · 需要确认[1-9][0-9]*个问题$/.test(n.value||n.name);});
    var submit=parts.filter(function(n){return button(n)&&teleagentProgressControl(title(n));});
    if(marker.length===1&&submit.length===1&&parts.some(function(n){return n.role==='AXRadioGroup';})){
     if(candidates.indexOf(p)<0)candidates.push(p);break;
    }
   }
  });
 }
 if(candidates.length!==1)return null;
 var scope=descendants(candidates[0]);
 if(request.app==='TeleAgent'&&scope.some(function(n){return button(n)&&title(n)==='跳过问答';})){
  var first=scope.findIndex(function(n){return n.role==='AXStaticText'&&/^交互式问答 · 需要确认[1-9][0-9]*个问题$/.test(n.value||n.name);});
  var last=scope.findIndex(function(n){return button(n)&&teleagentProgressControl(title(n));});
  if(first<0||last<=first)return null;
  // The common ancestor also contains the user bubble and old turns; never publish those.
  scope=scope.slice(first,last+1);
 }

 if(request.app==='WorkBuddy'&&scope.some(function(n){return button(n)&&title(n)==='全部跳过';})){
  var questionIndex=-1;scope.forEach(function(n,i){if(n.role==='AXStaticText'&&questionHeading(n.value||n.name||''))questionIndex=i;});
  if(questionIndex<0)return null;scope=scope.slice(questionIndex);
 }
 var buttons=scope.filter(function(n){return button(n)&&title(n)&&/^(1|true)$/.test(string(n.raw,'AXEnabled'));});
 // Free text remains available through the explicit app-scoped input panel.
 if(buttons.length<1||buttons.length>12)return null;
 var labels=buttons.map(title);if(labels.some(function(label,i){return labels.indexOf(label)!==i;}))return null;
 var detail=text(scope).join('\n');if(!detail.trim())detail=title(candidates[0]);if(!detail||detail.length>20000)return null;
 var inputs=request.app==='TeleAgent'?scope.filter(function(n){return /^(AXTextArea|AXTextField)$/.test(n.role)&&/^(1|true)$/.test(string(n.raw,'AXEnabled'));}).map(function(n,i){
  var option=n.parent;while(option&&option.role!=='AXRadioButton'&&scope.indexOf(option)>=0)option=option.parent;
  var required=!!(option&&/^其他/.test(title(option)))||/^(1|true)$/.test(string(n.raw,'AXRequired'));
  return {id:'question-text-'+i,label:required?'自定义回答（必填）':'补充说明（可选）',value:n.value||'',required:required,node:n};
 }):[];
 return {detail:detail,buttons:buttons,inputs:inputs,fingerprint:JSON.stringify({app:request.app,pid:Number(application.processIdentifier),detail:detail,labels:labels,states:buttons.map(function(n){return [string(n.raw,'AXValue'),string(n.raw,'AXSelected'),string(n.raw,'AXChecked')];}),inputs:inputs.map(function(f){return [f.id,f.value,f.required];})})};
}
// Storage remains authoritative for replies; UI is only authority for a visible owned card.
var dialogPublishedAt=0;
function syncOwnedDialog(nodes,previous){
 var dialog=null;
 try{ownedView(nodes,request.prompt,null,true);dialog=pendingDialog(nodes);}catch(_){/* Different visible session: invalidate card, keep receiving the bound store. */}
 var fingerprint=dialog?dialog.fingerprint:'';
 if(fingerprint!==previous||(dialog&&Date.now()-dialogPublishedAt>=120000)){
  if(dialog){dialogPublishedAt=Date.now();event({type:'desktop.interaction',fingerprint:fingerprint,detail:dialog.detail,options:dialogOptions(dialog),inputs:(dialog.inputs||[]).map(function(f){return {id:f.id,label:f.label,value:f.value,required:f.required};})});}
  else if(previous)event({type:'desktop.interaction.closed'});
 }
 return fingerprint;
}
function pollStoredDialog(previous){
 var nodes;try{nodes=snapshot(root,true);}catch(_){nodes=[];}
 return syncOwnedDialog(nodes,previous);
}
function requiresDesktopInput(nodes){
 return !!pendingDialog(nodes)||nodes.some(function(n){return button(n)&&/^(等待你的操作|收起问题卡片|放弃整组问题|全部跳过|跳过问答)$/.test(title(n));})||text(nodes).some(function(t){return /^等待你的(回答|操作)(?:[.。…]|$)/.test(t);});
}
function storedCompletionAllowed(){
 var nodes;try{nodes=snapshot(root,true);ownedView(nodes,request.prompt,null,true);}catch(_){return true;}
 // A completed text message can precede a still-running tool or a question card.
 return !requiresDesktopInput(nodes)&&(request.app!=='WorkBuddy'||extract(nodes,request.prompt,[]).complete===true);
}
function pasteQuestionText(node,value){
 if(typeof value!=='string'||value.length>10000||value.indexOf('\u0000')>=0)fail('问答文字无效或过长');
 if(string(node.raw,'AXValue')===value)return;
 if($.AXUIElementSetAttributeValue(node.raw,$('AXFocused'),$(true))!==0)fail('问答输入框未获得焦点');
 delay(0.2);if(!isFrontmost(application)||!/^(1|true)$/.test(string(node.raw,'AXFocused')))fail('问答输入框焦点已改变，未输入');
 var board=$.NSPasteboard.generalPasteboard,saved=[],items=board.pasteboardItems;
 for(var i=0;i<Number(items.count);i++){var old=items.objectAtIndex(i),formats=[];for(var j=0;j<Number(old.types.count);j++){var format=old.types.objectAtIndex(j);formats.push([format,old.dataForType(format)]);}saved.push(formats);}
 var version=-1;
 try{board.clearContents;board.setStringForType($(value),$.NSPasteboardTypeString);version=Number(board.changeCount);var sys=Application('System Events');sys.keystroke('a',{using:'command down'});
  if(!isFrontmost(application)||!/^(1|true)$/.test(string(node.raw,'AXFocused')))fail('问答输入框焦点已改变，未粘贴');
  if(value)sys.keystroke('v',{using:'command down'});else sys.keyCode(51);delay(0.3);
  if(string(node.raw,'AXValue')!==value)fail('电脑未确认问答文字，未提交');
 }finally{if(version>=0&&Number(board.changeCount)===version){board.clearContents;var restore=$.NSMutableArray.alloc.init;saved.forEach(function(formats){var item=$.NSPasteboardItem.alloc.init;formats.forEach(function(pair){item.setDataForType(pair[1],pair[0]);});restore.addObject(item);});if(Number(restore.count))board.writeObjects(restore);}}
}
function revealConfirmationButton(dialog,node){
 if(request.app!=='TeleAgent')return node;
 // Electron clips off-screen AX bounds to height 0 behind the fixed composer.
 // Scrolling is not activation: reacquire and verify the same card before any click.
 $.AXUIElementPerformAction(node.raw,$('AXScrollToVisible'));
 var label=title(node),until=Date.now()+2500;
 do{
  delay(0.15);var nodes=snapshot(root);ownedView(nodes,request.prompt,null,true);
  var current=pendingDialog(nodes);if(!current||current.fingerprint!==dialog.fingerprint)fail('滚动后电脑问答已改变，未点击');
  var matches=current.buttons.filter(function(n){return title(n)===label;});if(matches.length!==1)fail('滚动后无法唯一识别提交控件，未点击');
  var pos=attr(matches[0].raw,'AXPosition'),size=attr(matches[0].raw,'AXSize');
  var bounds=pos&&size?parseBounds(String(ObjC.unwrap($.CFCopyDescription(pos))),String(ObjC.unwrap($.CFCopyDescription(size)))):null;
  if(bounds&&bounds.h>=24)return matches[0];
 }while(Date.now()<until);
 fail('提交控件仍被遮挡，未点击；请在电脑滚动问答区域后重试。');
}
function confirmDialog(nodes){
 if(!extract(nodes,request.prompt,[]).sawUser)fail('当前电脑对话已改变，未操作确认。');
 ownedView(nodes,request.prompt,null);
 var stored=storedSession(request.prompt,null);if(stored===null)fail('未绑定本次请求的会话记录，未操作确认。');
 var dialog=pendingDialog(nodes);
 if(!dialog||dialog.fingerprint!==request.fingerprint)fail('电脑确认内容已改变，未操作。');
 var writing=request.choice==='__desktop_text__',chosen=dialog.buttons.filter(function(n){return title(n)===request.choice;});if(writing?!(dialog.inputs||[]).length:chosen.length!==1)fail('无法唯一识别所选操作。');
 var values=request.textValues||{};Object.keys(values).forEach(function(id){if(!(dialog.inputs||[]).some(function(f){return f.id===id;}))fail('问答输入框已失效');});
 application.activateWithOptions(3);var until=Date.now()+3000;
 while(!isFrontmost(application)&&Date.now()<until)delay(0.1);
 var changedText=(dialog.inputs||[]).some(function(f){return Object.prototype.hasOwnProperty.call(values,f.id)&&f.value!==values[f.id];});
 (dialog.inputs||[]).forEach(function(f){if(Object.prototype.hasOwnProperty.call(values,f.id))pasteQuestionText(f.node,values[f.id]);});
 if(writing||changedText){var updatedNodes=snapshot(root);ownedView(updatedNodes,request.prompt,null,true);var updated=pendingDialog(updatedNodes);if(!updated)fail('文字填写后问答界面已改变，未点击');chosen=updated.buttons.filter(function(n){return writing?request.app==='TeleAgent'&&teleagentProgressControl(title(n)):title(n)===request.choice;});if(chosen.length!==1)fail('文字填写后无法唯一识别下一题或提交操作，请使用更新后的卡片');dialog=updated;}
 chosen[0]=revealConfirmationButton(dialog,chosen[0]);
 if(request.app==='WorkBuddy'||request.app==='Doubao'||request.app==='TeleAgent')nativeClick(chosen[0],application);else keyboardActivate(chosen[0],application);
 until=Date.now()+5000;
 while(Date.now()<until){delay(0.2);var current=pendingDialog(snapshot(root));if(!current||current.fingerprint!==dialog.fingerprint){emit('desktop.action.done','电脑已更新确认界面');return;}}
 fail('点击后电脑界面没有确认变化，请在电脑查看，不会重复点击。');
}
function conversationNavigation(nodes){
 if(request.app==='Doubao'){
  var areas=nodes.filter(function(n){return n.role==='AXWebArea';}),urls=areas.map(function(n){return string(n.raw,'AXURL');}).filter(function(url){return /^chrome:\/\/doubao-chat\/chat\/[0-9]+$/.test(url);});
  if(urls.length===1)return {url:urls[0]};
 }
 // WorkBuddy exposes the active task title beside its in-conversation search button.
 var searches=nodes.filter(function(n){return button(n)&&/^对话内搜索/.test(title(n));});
 if(searches.length===1){var names=text(descendants(searches[0].parent));if(names.length===1)return {title:names[0]};}
 return null;
}
function navigationCandidates(nodes,navigation){
 if(!navigation)return [];
 if(navigation.url)return nodes.filter(function(n){return n.role==='AXLink'&&string(n.raw,'AXURL')===navigation.url;});
 if(navigation.title)return nodes.filter(function(n){return button(n)&&!freshEntry(n)&&normalize(title(n)).replace(/\s+(?:\d+分钟前|\d+小时前|刚刚|昨天|今天|\d+天前)$/,'')===navigation.title;});
 return [];
}
function resumeDesktopConversation(nodes,state){
 function verified(current){
  ownedView(current,state.prompt,null,true);
  var persisted=storedSession(state.prompt,state.binding||null);
  if(persisted!==undefined&&(!persisted||!persisted.complete))throw Error('原电脑会话尚未完成或身份已改变');
  if(state.navigation&&state.navigation.url){var actual=conversationNavigation(current);if(!actual||actual.url!==state.navigation.url)throw Error('原电脑对话地址已改变');}
  return current;
 }
 try{return verified(nodes);}catch(_){
  var candidates=navigationCandidates(nodes,state.navigation);
  if(candidates.length!==1)fail('未能唯一找回此手机会话的电脑对话，请在电脑打开原对话后重试；不会自动新建对话。');
  if(request.app==='WorkBuddy')nativeClick(candidates[0],application);
  else if($.AXUIElementPerformAction(candidates[0].raw,$('AXPress'))!==0)keyboardActivate(candidates[0],application);
  var until=Date.now()+7000;
  do{delay(0.3);nodes=snapshot(root);try{return verified(nodes);}catch(_){} }while(Date.now()<until);
  fail('电脑原对话的请求标识或会话记录未通过校验，未发送；不会新建对话。');
 }
}
// Uploads are current-turn native registry paths. A filename in ordinary chat text
// is never evidence that the desktop app accepted an attachment.
function macInputFiles(files){
 if(files===undefined||files===null)return [];
 if(!Array.isArray(files)||files.length>4)throw Error('桌面附件数量无效，未发送消息');
 var total=0,seen={};return files.map(function(file){
  var path=file&&file.path,size=file&&file.size;
  if(typeof path!=='string'||path[0]!=='/'||/[\u0000-\u001f]/.test(path)||/(?:^|\/)\.\.?(?:\/|$)/.test(path)||!(/\/\.agent-control-input\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}-[^/]+$/).test(path)||seen[path])throw Error('桌面附件路径无效，未发送消息');
  if(typeof size!=='number'||!isFinite(size)||Math.floor(size)!==size||size<0||size>20*1024*1024)throw Error('桌面附件大小无效，未发送消息');
  seen[path]=true;total+=size;if(total>40*1024*1024)throw Error('本条消息的附件不能超过 40MB，未发送消息');
  return {path:path,basename:path.slice(path.lastIndexOf('/')+1),size:size,name:file.name||path.slice(path.lastIndexOf('/')+1)};
 });
}
function macAttachLabel(n){return /^(上传文件|上传附件|添加文件|添加附件|添加文件和图片|添加图片和文件|附件|Attach files?|Add files?|Add attachment|Upload files?|Add photos and files)$/i.test(normalize(title(n)));}
function macRemoveFile(n){return button(n)&&/^(删除(?:文件|附件)?|移除(?:文件|附件)?|Remove(?: file| attachment)?|Delete(?: file| attachment)?)(?:[ :：].*)?$|^关闭附件$/i.test(normalize(title(n)));}
function macFileText(n,name){return [n.name,n.description,n.value].some(function(value){return typeof value==='string'&&value.trim()===name;});}
function macComposerFileScope(input){
 for(var scope=input.parent;scope;scope=scope.parent){
  if(/^(AXWindow|AXDialog|AXSheet)$/.test(scope.role))break;
  var parts=descendants(scope),fields=parts.filter(function(n){return n.role==='AXTextArea';});
  if(fields.length!==1||!fields[0].raw.isEqual(input.raw))break;
  // A transcript parent must not turn historical files into composer attachments.
  if(parts.some(function(n){return !inEditor(n)&&(button(n)&&/^(复制|Copy|朗读|仅复制文本|带格式复制)$/.test(title(n))||n.role==='AXStaticText'&&/\[Agent Control request /.test(n.value||n.name||''));}))break;
  var entries=parts.filter(function(n){return button(n)&&macAttachLabel(n);});
  if(entries.length===1)return {scope:scope,parts:parts,entry:entries[0]};
  if(entries.length>1)throw Error('电脑附件入口不唯一，未发送消息；请先在电脑检查附件草稿');
 }
 throw Error('该桌面 APP 未提供可识别的文件上传入口，未发送消息；可改用 CLI 接入');
}
function macAttachmentCards(composer,files){
 var cards=[];files.forEach(function(file){
  var names=composer.parts.filter(function(n){return !inEditor(n)&&macFileText(n,file.basename);}),found=[];
  names.forEach(function(n){for(var card=n;card&&card!==composer.scope;card=card.parent){
   if(descendants(card).some(macRemoveFile)){if(!found.some(function(c){return c.raw.isEqual(card.raw);}))found.push(card);break;}
  }});
  if(found.length!==1)return;
  // One broad wrapper containing all files cannot prove each file has its own card.
  if(files.some(function(other){return other!==file&&descendants(found[0]).some(function(n){return macFileText(n,other.basename);});}))return;
  cards.push({file:file,card:found[0]});
 });return cards;
}
function macAttachmentState(composer,files){
 var failed=composer.parts.some(function(n){return !inEditor(n)&&/上传失败|文件过大|文件类型不支持|不支持的文件|Upload failed|Unsupported file|File too large/i.test(n.value||n.name||n.description||'');});
 if(failed)return 'error';
 var pending=composer.parts.some(function(n){return n.role==='AXProgressIndicator'||!inEditor(n)&&/正在上传|上传中|正在处理文件|文件处理中|Uploading|Processing file/i.test(n.value||n.name||n.description||'');});
 var cards=macAttachmentCards(composer,files),removers=composer.parts.filter(macRemoveFile);
 // Reject additional PC attachments, including additions while our upload waits.
 if(removers.length>files.length||cards.length===files.length&&removers.length!==files.length)return 'changed';
 return !pending&&cards.length===files.length?'ready':'pending';
}
function macOpenFileDialog(nodes){
 var candidates=nodes.filter(function(n){return /^(AXSheet|AXDialog)$/.test(n.role)||n.role==='AXWindow'&&string(n.raw,'AXSubrole')==='AXDialog';}).map(function(scope){
  var parts=descendants(scope),open=parts.filter(function(n){return button(n)&&/^(打开|选择|选取|Open|Choose)$/i.test(normalize(title(n)));});
  return {scope:scope,parts:parts,open:open.length===1?open[0]:null};
 }).filter(function(dialog){return dialog.open&&dialog.parts.some(function(n){return /^(AXBrowser|AXOutline|AXTable)$/.test(n.role);});});
 candidates=candidates.filter(function(dialog){return !candidates.some(function(other){return other!==dialog&&descendants(dialog.scope).indexOf(other.scope)>=0;});});
 return candidates.length===1?candidates[0]:null;
}
function macGoToDialog(nodes){
 var candidates=nodes.filter(function(n){return /^(AXSheet|AXDialog)$/.test(n.role)||n.role==='AXWindow'&&string(n.raw,'AXSubrole')==='AXDialog';}).map(function(scope){
  var parts=descendants(scope),go=parts.filter(function(n){return button(n)&&/^(前往|Go)$/i.test(normalize(title(n)));}),fields=parts.filter(function(n){return /^(AXTextField|AXComboBox)$/.test(n.role);});
  // Some native Go to Folder fields expose both a combo and its child text field.
  fields=fields.filter(function(field){return !fields.some(function(other){return other!==field&&descendants(field).indexOf(other)>=0;});});
  return {scope:scope,go:go.length===1?go[0]:null,field:fields.length===1?fields[0]:null};
 }).filter(function(dialog){return dialog.go&&dialog.field;});
 candidates=candidates.filter(function(dialog){return !candidates.some(function(other){return other!==dialog&&descendants(dialog.scope).indexOf(other.scope)>=0;});});
 return candidates.length===1?candidates[0]:null;
}
function macChosenFile(dialog,file){
 var selected=dialog.parts.filter(function(n){return /^(AXRow|AXCell|AXImage)$/.test(n.role)&&/^(1|true)$/.test(string(n.raw,'AXSelected'));});
 selected=selected.filter(function(n){return !selected.some(function(parent){return parent!==n&&descendants(parent).indexOf(n)>=0;});});
 return selected.length===1&&descendants(selected[0]).some(function(n){return macFileText(n,file.basename);});
}
function macFileContext(root,input,window,existing){
 if(!isFrontmost(application))fail('目标应用焦点已改变，未发送消息；请先在电脑检查附件草稿');
 var nodes=snapshot(root);
 if(!nodes[0].raw.isEqual(window)||busy(nodes))fail('电脑窗口或任务状态已改变，未发送消息；请先在电脑检查附件草稿');
 input=receivingEditor(nodes,input);
 if(string(input.raw,'AXValue')!==existing)fail('电脑输入框被修改，未发送消息；请先在电脑检查附件草稿');
 return {nodes:nodes,input:input,composer:macComposerFileScope(input)};
}
function attachMacInputFiles(root,input,files){
 if(!files.length){
  var plainComposer;try{plainComposer=macComposerFileScope(input);}catch(_){return input;}
  if(plainComposer.parts.some(macRemoveFile))fail('电脑输入框已有附件草稿，未发送消息；请先在电脑处理已有附件');
  return input;
 }
 var window=snapshot(root)[0].raw,existing=string(input.raw,'AXValue'),state=macFileContext(root,input,window,existing);
 DesktopSession.checkDraft(editorDraft(state.input),'',false);
 if(state.composer.parts.some(macRemoveFile))fail('电脑输入框已有附件草稿，未追加或发送；请先在电脑处理已有附件');
 files.forEach(function(file,index){
  var attrs=$.NSFileManager.defaultManager.attributesOfItemAtPathError($(file.path),null);
  if(!attrs||String(ObjC.unwrap(attrs.objectForKey($.NSFileType)))!==String(ObjC.unwrap($.NSFileTypeRegular))||Number(attrs.objectForKey($.NSFileSize))!==file.size)fail('电脑上的上传文件已改变或无法读取，未发送消息；请重新上传');
  emit('status','正在向 '+request.app+' 添加附件：'+file.name);
  state=macFileContext(root,state.input,window,existing);
  if($.AXUIElementPerformAction(state.composer.entry.raw,$('AXPress'))!==0)fail('电脑附件入口无法操作，未发送消息');
  var until=Date.now()+6000,dialog=null;
  do{delay(0.2);if(!isFrontmost(application))fail('电脑焦点已改变，未操作文件选择框');dialog=macOpenFileDialog(snapshot(root));}while(!dialog&&Date.now()<until);
  if(!dialog)fail('未找到该 APP 的唯一系统文件选择框，未发送消息；请先在电脑检查附件草稿');
  Application('System Events').keystroke('g',{using:['command down','shift down']});
  var go=null;until=Date.now()+4000;
  do{delay(0.2);if(!isFrontmost(application))fail('电脑焦点已改变，未输入附件路径');go=macGoToDialog(snapshot(root));}while(!go&&Date.now()<until);
  if(!go)fail('无法识别系统文件选择框的路径输入区，未发送消息');
  if($.AXUIElementSetAttributeValue(go.field.raw,$('AXFocused'),$(true))!==0)fail('系统路径输入区无法确认焦点，未输入附件路径');
  var focusUntil=Date.now()+2000;while(!/^(1|true)$/.test(string(go.field.raw,'AXFocused'))&&Date.now()<focusUntil)delay(0.1);
  if(!/^(1|true)$/.test(string(go.field.raw,'AXFocused'))||!isFrontmost(application))fail('系统路径输入区无法确认焦点，未输入附件路径');
  if($.AXUIElementSetAttributeValue(go.field.raw,$('AXValue'),$(file.path))!==0||string(go.field.raw,'AXValue')!==file.path)fail('系统附件路径输入校验失败，未选择文件');
  if(!isFrontmost(application)||!/^(1|true)$/.test(string(go.field.raw,'AXFocused')))fail('电脑焦点已改变，未选择文件');
  if($.AXUIElementPerformAction(go.go.raw,$('AXPress'))!==0)fail('系统附件路径导航失败，未选择文件');
  until=Date.now()+6000;dialog=null;
  do{delay(0.2);if(!isFrontmost(application))fail('电脑焦点已改变，未确认文件选择');var selectedNodes=snapshot(root);if(!macGoToDialog(selectedNodes))dialog=macOpenFileDialog(selectedNodes);}while((!dialog||!macChosenFile(dialog,file))&&Date.now()<until);
  if(!dialog||!macChosenFile(dialog,file)||!/^(1|true)$/.test(string(dialog.open.raw,'AXEnabled')))fail('未确认系统选择了本次上传的唯一文件，未发送消息');
  if($.AXUIElementPerformAction(dialog.open.raw,$('AXPress'))!==0)fail('系统文件选择失败，未发送消息');
  until=Date.now()+30000;var attached=false;
  do{
   delay(0.3);if(!isFrontmost(application))fail('电脑焦点已改变，未发送消息；请先在电脑检查附件草稿');
   var current=snapshot(root);if(macOpenFileDialog(current)||macGoToDialog(current))continue;
   state=macFileContext(root,state.input,window,existing);var status=macAttachmentState(state.composer,files.slice(0,index+1));
   if(status==='error')fail('桌面 APP 拒绝了附件，未发送消息；请先在电脑检查文件类型、大小及附件草稿');
   if(status==='changed')fail('电脑附件草稿出现了其他文件或控件，未发送消息；请先在电脑检查附件草稿');
   if(status==='ready'){attached=true;break;}
  }while(Date.now()<until);
  if(!attached)fail('未确认附件在桌面输入框中准备完成，未发送消息；请先在电脑检查附件草稿');
 });
 return state.input;
}
function verifyMacInputFiles(nodes,input,files){
 var current=receivingEditor(nodes,input),composer;
 if(!files.length){
  try{composer=macComposerFileScope(current);}catch(_){return;}
  if(composer.parts.some(macRemoveFile))fail('电脑输入框出现附件草稿，未发送消息；请先在电脑处理已有附件');
  return;
 }
 composer=macComposerFileScope(current);
 if(macAttachmentState(composer,files)!=='ready')fail('发送前附件校验失败，未发送消息；请先在电脑检查附件草稿');
}
function checkpointDesktopConversation(status,binding){
 if(!conversationPath)return;
 conversationState.status=status;conversationState.binding=binding||conversationState.binding||null;
 try{var nodes=snapshot(root,true);ownedView(nodes,request.prompt,null,true);conversationState.navigation=conversationNavigation(nodes)||conversationState.navigation||null;}catch(_){/* Keep the accepted conversation, even when PC browses elsewhere. */}
 writeDesktopConversation(conversationPath,conversationState);
}
if(!bundles[request.app])fail('不支持的桌面应用');
if(!$.AXIsProcessTrusted())fail('电脑拒绝了桌面控制权限（SSH 仍可连接）。请在 macOS 隐私与安全 → 辅助功能（新版为设备控制和数据访问）启用 sshd-keygen-wrapper，授权后在手机重新连接，并保持电脑解锁。若仍失败，需要检查该 SSH 会话的权限归属。');
emit('status','正在连接电脑上的 '+request.app);
var running=$.NSRunningApplication.runningApplicationsWithBundleIdentifier($(bundles[request.app]));
if(!Number(running.count)){$.NSWorkspace.sharedWorkspace.launchApplication($(request.app));delay(1);running=$.NSRunningApplication.runningApplicationsWithBundleIdentifier($(bundles[request.app]));}
if(Number(running.count)!==1)fail('无法唯一识别应用进程');
var application=running.objectAtIndex(0),root=$.AXUIElementCreateApplication(application.processIdentifier);
DesktopSession.marker(request.prompt);
var initial=snapshot(root),conversationPath=null,conversationState=null;
if(request.action==='confirm'){confirmDialog(initial);}else{
if(busy(initial))fail('该应用当前有正在执行的任务，请等待完成后重试，避免打断电脑端任务。');
conversationPath=desktopConversationPath();
var previousConversation=readDesktopConversation(conversationPath);
application.activateWithOptions(3);
// Activation is asynchronous when switching from another desktop Agent.
var activateUntil=Date.now()+5000;
while(!isFrontmost(application)&&Date.now()<activateUntil)delay(0.1);
if(!isFrontmost(application))fail('目标应用未能切到前台，未输入消息。');
var blank;
if(previousConversation){
 emit('status','正在继续同一个电脑对话');
 blank=resumeDesktopConversation(snapshot(root),previousConversation);
}else{
var fresh=snapshot(root).filter(freshEntry);
if(fresh.length!==1)fail('无法唯一识别新对话入口，未发送消息。');
if(request.app==='Doubao'){
 // This version advertises Shift-Command-K; its AXPress returns success without navigating.
 if(!isFrontmost(application))fail('目标应用不在前台，未新建对话。');
 Application('System Events').keystroke('k',{using:['command down','shift down']});
}else if(request.app==='WorkBuddy'){nativeClick(fresh[0],application);}
else if($.AXUIElementPerformAction(fresh[0].raw,$('AXPress'))!==0)fail('新对话按钮无法操作，未发送消息。');
delay(1);
blank=snapshot(root);
if(request.app==='WorkBuddy'){
 var navigationUntil=Date.now()+5000;
 while(!newTaskReady(blank)&&Date.now()<navigationUntil){$.NSRunLoop.currentRunLoop.runUntilDate($.NSDate.dateWithTimeIntervalSinceNow(0.2));blank=snapshot(root);}
 if(!newTaskReady(blank))fail('新建任务未生效，未在已有会话输入消息。请先在 WorkBuddy 打开新建任务。');
}
if(request.app==='DeepSeek Harness')blank=confirmDeepseekNavigation(root,application);
if(request.app==='TeleAgent'){
 var teleUntil=Date.now()+7000;
 while(!teleagentBlank(blank)&&Date.now()<teleUntil){delay(0.2);blank=snapshot(root);}
 if(!teleagentBlank(blank)&&!busy(blank)){
  var teleFresh=blank.filter(freshEntry);
  if(teleFresh.length===1){keyboardActivate(teleFresh[0],application);teleUntil=Date.now()+5000;do{delay(0.2);blank=snapshot(root);}while(!teleagentBlank(blank)&&Date.now()<teleUntil);}
 }
 if(!teleagentBlank(blank))fail('TeleAgent 未进入空白新任务，未输入或覆盖已有对话。');
}
if(request.app==='Doubao'&&blank.some(function(n){return title(n)==='doc_editor'&&text(descendants(n)).length>0;}))fail('未确认已进入豆包新对话，未发送消息。');
}
var fields=blank.filter(function(n){return n.role==='AXTextArea';});
if(fields.length!==1)fail('无法唯一识别聊天输入框，未发送消息。');
var input=fields[0],existing=input.value;
if(existing&&existing.trim()&&!/发消息|今天帮你|有什么|Message|Ask/.test(existing))fail('输入框有草稿，未覆盖。');
var inputFiles;
try{inputFiles=macInputFiles(request.files);}catch(fileError){fail(String(fileError.message||fileError));}
input=attachMacInputFiles(root,input,inputFiles);
existing=string(input.raw,'AXValue');
var baseline=text(blank);
conversationState={version:1,app:request.app,conversationId:request.conversationId,prompt:request.prompt,status:'pending',navigation:previousConversation&&previousConversation.navigation||null};

// AXValue mutates Electron contenteditable visually but does not fire input events.
// Paste real input so the application's composer state and send button update.
var written='',system=Application('System Events');
{
 if(!isFrontmost(application))fail('目标应用不在前台，未输入消息。');
 var pasteboard=$.NSPasteboard.generalPasteboard,saved=[];
 if(!pasteboard||typeof pasteboard.setStringForType!=='function')fail('该 SSH 会话无法访问登录桌面的剪贴板，未输入或发送。请通过 macOS 系统远程登录连接，并保持同一用户桌面已登录且解锁。');
 var items=pasteboard.pasteboardItems;
 for(var pi=0;pi<(items?Number(items.count):0);pi++){var item=items.objectAtIndex(pi),formats=[],types=item.types;for(var ti=0;ti<Number(types.count);ti++){var format=types.objectAtIndex(ti);formats.push([format,item.dataForType(format)]);}saved.push(formats);}
 try{
  pasteboard.clearContents;pasteboard.setStringForType($(request.prompt),$.NSPasteboardTypeString);var clipboardVersion=Number(pasteboard.changeCount);
  $.AXUIElementSetAttributeValue(input.raw,$('AXFocused'),$(true));
  if(!isFrontmost(application)||!/^(1|true)$/.test(string(input.raw,'AXFocused'))||string(input.raw,'AXValue')!==existing)fail('电脑输入框或焦点已改变，未覆盖草稿。');
  system.keystroke('a',{using:'command down'});
  if(!isFrontmost(application)||!/^(1|true)$/.test(string(input.raw,'AXFocused')))fail('电脑焦点已改变，未粘贴或发送。');
  system.keystroke('v',{using:'command down'});delay(1);
  written=string(input.raw,'AXValue');
 }finally{
  if(Number(pasteboard.changeCount)===clipboardVersion){
   pasteboard.clearContents;var restore=$.NSMutableArray.alloc.init;
   saved.forEach(function(formats){var restored=$.NSPasteboardItem.alloc.init;formats.forEach(function(pair){restored.setDataForType(pair[1],pair[0]);});restore.addObject(restored);});
   if(Number(restore.count))pasteboard.writeObjects(restore);
  }
 }
}
if(!isFrontmost(application)||!/^(1|true)$/.test(string(input.raw,'AXFocused')))fail('电脑焦点已改变，未发送消息。');
if(normalize(written)!==normalize(request.prompt))fail('输入内容校验失败，未发送消息。');
var ready=snapshot(root),sendButtons=ready.filter(function(n){return button(n)&&(request.app==='TeleAgent'?title(n)==='运行':/^(发送|Send|发送消息)$/.test(title(n)));});
verifyMacInputFiles(ready,input,inputFiles);
// Persist uncertainty immediately before sending, after draft/content/file checks.
writeDesktopConversation(conversationPath,conversationState);
if(sendButtons.length===1){
 if(!/^(1|true)$/.test(string(sendButtons[0].raw,'AXEnabled')))fail('消息已填入，但发送按钮未启用。未发送，请检查电脑端模型或输入框。');
 if(request.app==='DeepSeek Harness')keyboardActivate(sendButtons[0],application);
 else if(request.app==='WorkBuddy'||request.app==='TeleAgent')nativeClick(sendButtons[0],application);
 else if($.AXUIElementPerformAction(sendButtons[0].raw,$('AXPress'))!==0)fail('发送按钮操作失败，请在电脑上确认草稿。');
}else{
 if(request.app==='WorkBuddy'||request.app==='TeleAgent')fail('无法唯一识别发送按钮，未发送消息。');
 if(!isFrontmost(application))fail('目标应用焦点已改变，未发送消息。');
 $.AXUIElementSetAttributeValue(input.raw,$('AXFocused'),$(true));system.keyCode(36);
}
emit('status','正在确认电脑 '+request.app+' 是否收到消息');
var accepted=false,verifyStart=Date.now();
while(Date.now()-verifyStart<15000){
 delay(0.5);var persistedAcceptance;
 try{persistedAcceptance=storedSession(request.prompt,null);}catch(acceptError){fail(String(acceptError.message||acceptError));}
 if(persistedAcceptance){accepted=true;break;}
 if(persistedAcceptance===undefined){var verification=extract(snapshot(root),request.prompt,baseline);if(verification.sawUser){accepted=true;break;}}
}
if(accepted)checkpointDesktopConversation('accepted',persistedAcceptance&&persistedAcceptance.binding);
if(!accepted)fail('未确认消息进入电脑对话，已停止等待，不会重复发送。请检查电脑输入框和网络。');
emit('status','电脑 '+request.app+' 已确认收到消息，正在读取回复');
var last='',stable=0,sawBusy=false,finished=false,started=Date.now(),lastDialog='',storeStatusSent=false,storeCandidate='',storeCandidateSince=0,binding=null,viewIdentity=null,windowIdentity=ready[0].raw;
while(Date.now()-started<600000){
 delay(0.5);
 // Once a structured store owns the request, receiving no longer depends on the visible window.
 // PC may browse other sessions; another user message in this same session still invalidates it.
 var storeOnly;
 try{storeOnly=storedSession(request.prompt,binding);}catch(storeError){fail(String(storeError.message||storeError));}
 if(storeOnly!==undefined){
  if(!storeOnly){if(Date.now()-started>15000)fail('未找到本次请求的唯一会话记录，已停止接收；不会用其他记录替代。');continue;}
  binding=storeOnly.binding;
  if(!storeStatusSent){emit('status','正在读取本次请求的会话记录；电脑需要确认时会自动显示操作卡片。');storeStatusSent=true;}
  lastDialog=pollStoredDialog(lastDialog);
  if(lastDialog||!storeOnly.complete){storeCandidate='';continue;}
  if(storeOnly.complete&&storeOnly.text&&storedCompletionAllowed()){
   var candidate=JSON.stringify([storeOnly.text,storeOnly.attachments||[]]);
   if(candidate!==storeCandidate){storeCandidate=candidate;storeCandidateSince=Date.now();continue;}
   if(Date.now()-storeCandidateSince<2500)continue;
   var finalStored=storedSession(request.prompt,binding);
   if(!finalStored||!finalStored.complete||finalStored.text!==storeOnly.text||!storedCompletionAllowed())continue;
   (finalStored.attachments||[]).forEach(function(part){if(part.path||part.uri||part.url)event({type:'attachment',path:part.path,uri:part.uri,url:part.url,name:part.filename||part.name,preview:part.preview===true});});
   if(lastDialog)event({type:'desktop.interaction.closed'});
   checkpointDesktopConversation('complete',finalStored.binding);emit('desktop.done',finalStored.text);finished=true;break;
  }
  continue;
 }
 var nodes=snapshot(root),working=busy(nodes),reply=extract(nodes,request.prompt,baseline);sawBusy=sawBusy||working;
 if(!nodes[0].raw.isEqual(windowIdentity)||!reply.sawUser)fail('电脑已切换窗口或对话，已停止本轮；不会读取其他会话或自动重发。');
 viewIdentity=ownedView(nodes,request.prompt,viewIdentity);
 var editor=receivingEditor(nodes,input);
 try{DesktopSession.checkDraft(editorDraft(editor),request.prompt,Date.now()-started<3000);
 var persisted=storedSession(request.prompt,binding);
 if(persisted===null){if(Date.now()-started>15000)fail('未找到本次请求的唯一会话记录，已停止接收；不会用其他记录替代。');reply.text='';reply.complete=false;}
 else if(persisted!==undefined){binding=persisted.binding;reply.text=persisted.text;reply.complete=persisted.complete;}
 }catch(ownershipError){fail(String(ownershipError.message||ownershipError));}
 var requiresInput=requiresDesktopInput(nodes);
 if(requiresInput&&!lastDialog){emit('status','等待你操作：点击“查看电脑与操作”，选择问题卡片中的选项并提交。');}
 var dialog=(request.app==='TeleAgent'||request.app==='WorkBuddy')&&!binding?null:pendingDialog(nodes);
 if(dialog){
  if(dialog.fingerprint!==lastDialog){lastDialog=dialog.fingerprint;event({type:'desktop.interaction',fingerprint:dialog.fingerprint,detail:dialog.detail,options:dialogOptions(dialog),inputs:(dialog.inputs||[]).map(function(f){return {id:f.id,label:f.label,value:f.value,required:f.required};})});}
  continue;
 }else if(lastDialog){lastDialog='';event({type:'desktop.interaction.closed'});}
 if(requiresInput)continue;
 if(reply.media&&!reply.text&&reply.complete)reply.text='已返回图片或文件。点按“查看电脑与操作”查看，原文件链接会显示在下方。';
 if(reply.text.length>150000)fail('回复过长，请在电脑端查看。');
 if(reply.text!==last){last=reply.text;stable=0;// Unverified UI text is never streamed into phone history.
}else stable++;
 if(reply.sawUser&&last&&stable>=3&&!working&&reply.complete){ownedView(snapshot(root),request.prompt,viewIdentity);
 if(binding){var finalStore=storedSession(request.prompt,binding);if(!finalStore||!finalStore.complete)fail('会话记录尚未确认完成，未提交回复。');last=finalStore.text;}
 // Global clipboard text is not authoritative.
emitReplyFiles(reply);var imageCount=emitReplyImages(reply);if(reply.media&&!imageCount)event({type:'desktop.media'});checkpointDesktopConversation('complete',binding);emit('desktop.done',last);finished=true;break;}
}
if(!finished)fail('未收到可确认的完成状态；电脑任务可能仍在运行，请检查电脑端。');

}
