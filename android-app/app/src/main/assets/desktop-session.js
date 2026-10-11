// Shared macOS ownership checks. Persistent stores are read-only and request-scoped.
var DesktopSession = {
 conversationKey:function(app,id){if(!/^[a-zA-Z0-9_-]{1,160}$/.test(id||''))throw Error('手机会话标识无效，未操作桌面');return app.replace(/[^a-zA-Z0-9_-]/g,'_')+'-'+id;},
 continuation:function(state,app,id){if(!state)return false;if(state.version!==1||state.app!==app||state.conversationId!==id||!state.prompt)throw Error('手机与电脑的会话绑定损坏，未发送消息');if(state.status!=='complete')throw Error('上一条电脑消息尚未确认完成，不能新建对话或重复发送；请先在电脑检查');this.marker(state.prompt);return true;},
 key:function(s){return String(s||'').replace(/[\s\uFEFF\u200B\u200C\u200D\u2060]/g,'');},
 select:function(rows,prompt,binding){
  var wanted=this.key(prompt),token=this.marker(prompt),users=rows.filter(function(r){
   if(r.role!=='user')return false;
   var value=DesktopSession.key(r.text);
   if(value===wanted)return true;
   // WorkBuddy desktop adds input_text context around the exact submitted request.
   if(!r.wrapped||value.indexOf(wanted)<0)return false;
   try{return DesktopSession.marker(r.text)===token;}catch(e){return false;}
  });
  if(users.length!==1){if(binding)throw Error('电脑会话记录已改变，已停止接收；不会自动重发任务');return null;}
  var user=users[0];
  if(!user.session||!user.id)throw Error('会话记录缺少身份，不能安全关联回复');
  if(binding&&(binding.session!==user.session||binding.id!==user.id||binding.file!==user.file))throw Error('电脑会话身份已改变，已停止接收');
  var foreign=rows.some(function(r){return r.role==='user'&&r.file===user.file&&r.session===user.session&&r.id!==user.id&&r.order>user.order;});
  if(foreign)throw Error('电脑在手机会话插入了新消息，已停止本轮；请确认电脑状态后重新发送');
  var answers=rows.filter(function(r){return r.role==='assistant'&&r.file===user.file&&r.session===user.session&&r.order>user.order&&(r.parent===user.id||(user.request&&r.request===user.request));});
  // Stores may append repeated snapshots for the same message. Keep its latest snapshot.
  var byId={};answers.forEach(function(r){if(!r.id)throw Error('回复缺少消息身份');byId[r.id]=r;});
  answers=Object.keys(byId).map(function(id){return byId[id];}).sort(function(a,b){return a.order-b.order;});
  var last=answers.length?answers[answers.length-1]:null;
  var tools=rows.filter(function(r){return (r.role==='tool_call'||r.role==='tool_result')&&r.file===user.file&&r.session===user.session&&r.order>user.order&&((user.request&&r.request===user.request)||answers.some(function(a){return r.parent===a.id;}));});
  var calls={},results={},lastToolOrder=-1;
  tools.forEach(function(r){if(!r.callId)throw Error('工具记录缺少调用身份');lastToolOrder=Math.max(lastToolOrder,r.order);if(r.role==='tool_call')calls[r.callId]=r;else if(r.complete)results[r.callId]=r;});
  var pendingTools=Object.keys(calls).filter(function(id){return !results[id];});
  return {binding:{session:user.session,id:user.id,file:user.file},text:answers.map(function(r){return r.text||'';}).filter(Boolean).join('\n\n'),complete:!!last&&last.complete===true&&pendingTools.length===0&&last.order>lastToolOrder,pendingTools:pendingTools.length,attachments:answers.concat(tools.filter(function(r){return r.role==='tool_result'&&r.complete&&calls[r.callId];})).reduce(function(out,r){return out.concat(r.attachments||[]);},[]).filter(function(part,index,all){return all.findIndex(function(p){return (p.path||p.uri||p.url)===(part.path||part.uri||part.url);})===index;})};
 },
 checkDraft:function(value,prompt,allowOwn){var v=this.key(value);if(v&&!(allowOwn&&v===this.key(prompt)))throw Error('电脑输入框被修改，已停止本轮，未覆盖或重发消息');},
 marker:function(prompt){var matches=String(prompt).match(/\[Agent Control request [0-9a-f-]{36}\]/g);if(!matches||matches.length!==1)throw Error('请求缺少唯一身份，未操作桌面');return matches[0];}
};
// Only explicit, completed ImageGen results supply paths; prose is never a file authority.
function workBuddyImageAttachments(record){
 if(record.type!=='function_call_result'||record.status!=='completed')return [];
 var tool=record.providerData&&record.providerData.toolResult,data=tool&&tool.rawResponse;
 if(!data||data.type!=='image_gen_tool_result'){
  try{data=JSON.parse(record.output&&record.output.type==='text'?record.output.text:tool&&tool.content||'');}catch(_){return [];}
 }
 if(!data||data.type!=='image_gen_tool_result'||data.status!=='completed'||!Array.isArray(data.images))return [];
 return data.images.filter(function(image){var path=image.localPath;return typeof path==='string'&&path[0]==='/'&&!/[\u0000-\u001f]/.test(path)&&!/(?:^|\/)\.\.?(?:\/|$)/.test(path)&&/\.(png|jpe?g|webp|gif)$/i.test(path);}).map(function(image){return {type:'image',path:image.localPath,name:image.localPath.split('/').pop(),preview:true};});
}
function sessionReadFile(path){var data=$.NSData.dataWithContentsOfFile($(path));if(!data)return '';if(Number(data.length)>20000000)throw Error('会话文件过大，无法安全读取');return String(ObjC.unwrap($.NSString.alloc.initWithDataEncoding(data,$.NSUTF8StringEncoding)));}
function sessionFiles(directory,suffix){
 var manager=$.NSFileManager.defaultManager,iterator=manager.enumeratorAtPath($(directory)),out=[],name;
 if(!iterator||iterator.isNil())return out;
 while((name=iterator.nextObject)&&!name.isNil()){var relative=String(ObjC.unwrap(name));if(relative.slice(-suffix.length)===suffix){out.push(directory+'/'+relative);if(out.length>6000)throw Error('会话文件过多，已停止查找');}}
 return out;
}
function sessionSqlite(path,binding){
 var sessionFilter=binding?"m.session_id = '"+String(binding.session).replace(/'/g,"''")+"'":"m.time_created >= "+(Date.now()-1200000);
 var query="SELECT m.id,m.session_id,m.time_created,m.data,(SELECT json_group_array(json(x.data)) FROM (SELECT data FROM part WHERE message_id=m.id AND session_id=m.session_id ORDER BY time_created,id) x) AS parts FROM message m WHERE "+sessionFilter+" ORDER BY m.time_created DESC,m.id DESC LIMIT 2000;";
 var task=$.NSTask.alloc.init,output=$.NSPipe.pipe;task.launchPath=$('/usr/bin/sqlite3');task.arguments=$(['-readonly','-json',path,query]);task.standardOutput=output;task.standardError=$.NSFileHandle.fileHandleWithNullDevice;
 task.launch;var data=output.fileHandleForReading.readDataToEndOfFile;task.waitUntilExit;
 if(Number(task.terminationStatus)!==0)throw Error('无法只读访问 TeleAgent 会话数据库，未使用界面文字替代');
 return JSON.parse(String(ObjC.unwrap($.NSString.alloc.initWithDataEncoding(data,$.NSUTF8StringEncoding)))||'[]');
}
// TeleAgent stores generated files inside completed tool states, not message file parts.
function teleagentToolAttachments(part){
 var state=part.state||{};if(state.status!=='completed')return [];
 var paths=[];
 if(part.tool==='ImageGen'){
  var meta=state.metadata||{};paths=Array.isArray(meta.filepaths)?meta.filepaths:meta.filepath?[meta.filepath]:[];
  if(!paths.length)(Array.isArray(state.attachments)?state.attachments:[]).forEach(function(a){if(typeof a.url==='string'&&/^file:\/\/\//.test(a.url)){try{paths.push(decodeURIComponent(a.url.slice(7)));}catch(_){}}});
 }else if(part.tool==='report_final_files'){
  var output;try{output=JSON.parse(state.output);}catch(_){return [];}
  (Array.isArray(output.items)?output.items:[]).forEach(function(item){if(item&&item.exists===true&&item.kind==='file'&&item.displayable===true&&item.reason==='ok')paths.push(item.resolvedPath);});
 }
 return paths.filter(function(path){return typeof path==='string'&&path[0]==='/'&&!/[\u0000-\u001f]/.test(path)&&!/(?:^|\/)\.\.?(?:\/|$)/.test(path);}).map(function(path){return {type:'file',path:path,name:path.split('/').pop(),preview:/\.(png|jpe?g|webp|gif)$/i.test(path)};});
}
function teleagentSessionRows(records,file){
 var rows=[];records.forEach(function(r){
  var data=JSON.parse(r.data),parts=JSON.parse(r.parts||'[]').filter(Boolean);
  rows.push({file:file,session:r.session_id,id:r.id,order:Number(r.time_created),role:data.role,parent:data.parentID,complete:!!(data.time&&data.time.completed)&&/^(stop|end_turn)$/.test(data.finish||''),attachments:parts.filter(function(p){return p.type==='file'||p.type==='image'||p.type==='resource_link';}),text:parts.filter(function(p){return p.type==='text'||p.type==='input_text'||p.type==='output_text';}).map(function(p){return p.text||'';}).join('\n\n')});
  if(data.role!=='assistant')return;
  parts.filter(function(p){return p.type==='tool';}).forEach(function(p){
   if(!p.callID)throw Error('TeleAgent 工具记录缺少调用身份');var state=p.state||{},time=state.time||{},base={file:file,session:r.session_id,parent:r.id,callId:p.callID};
   rows.push(Object.assign({},base,{id:r.id+':'+p.callID+':call',order:Number(time.start||r.time_created),role:'tool_call'}));
   if(/^(completed|error)$/.test(state.status||''))rows.push(Object.assign({},base,{id:r.id+':'+p.callID+':result',order:Number(time.end||time.start||r.time_created),role:'tool_result',complete:true,attachments:teleagentToolAttachments(p)}));
  });
 });return rows;
}
function storedSession(prompt,binding){
 var home=String(ObjC.unwrap($.NSHomeDirectory())),files=[],rows=[];
 if(request.app==='TeleAgent'){
  files=binding?[binding.file]:sessionFiles(home+'/.local/share/TeleAgent/users','.db').filter(function(p){return /\/teleagent\.db$/.test(p);});
  files.forEach(function(file){rows=rows.concat(teleagentSessionRows(sessionSqlite(file,binding),file));});
 }else if(request.app==='WorkBuddy'){
  files=binding?[binding.file]:sessionFiles(home+'/.workbuddy/projects','.jsonl').concat(sessionFiles(home+'/.codebuddy/projects','.jsonl'));
  // Search metadata first so old histories do not incur unbounded content reads.
  if(!binding)files=files.filter(function(file){var attrs=$.NSFileManager.defaultManager.attributesOfItemAtPathError($(file),null);return attrs&&Number(attrs.objectForKey($.NSFileModificationDate).timeIntervalSince1970)*1000>Date.now()-1200000;});
  files.forEach(function(file){var source=sessionReadFile(file),lines=source.split('\n');lines.forEach(function(line,index){if(!line.trim())return;var r;try{r=JSON.parse(line);}catch(e){if(index===lines.length-1)return;throw Error('会话日志损坏，未读取不确定消息');}if(r.type==='function_call'||r.type==='function_call_result'){rows.push({file:file,session:r.sessionId,id:r.id,order:index,role:r.type==='function_call'?'tool_call':'tool_result',callId:r.callId,attachments:workBuddyImageAttachments(r),parent:r.parentId,request:r.providerData&&r.providerData.conversationRequestId,complete:r.type==='function_call_result'&&/^(completed|failed|cancelled)$/.test(r.status||'')});return;}if(r.type!=='message')return;var parts=Array.isArray(r.content)?r.content:[];rows.push({wrapped:r.role==='user'&&parts.some(function(p){return p.type==='input_text';}),file:file,session:r.sessionId,id:r.id,order:index,role:r.role,parent:r.parentId,request:r.providerData&&r.providerData.conversationRequestId,complete:r.status==='completed',attachments:parts.filter(function(p){return p.type==='file'||p.type==='image'||p.type==='resource_link';}),text:parts.filter(function(p){return p.type==='text'||p.type==='input_text'||p.type==='output_text';}).map(function(p){return p.text||'';}).join('\n\n')});});});
 }else return undefined;
 return DesktopSession.select(rows,prompt,binding);
}

// Saved on the controlled computer, independently of per-message engines and SSH lifetimes.
function desktopConversationPath(){
 if(!request.conversationId)return null;
 return String(ObjC.unwrap($.NSHomeDirectory()))+'/.agent-control/desktop-conversations/'+DesktopSession.conversationKey(request.app,request.conversationId)+'.json';
}
function readDesktopConversation(path){
 if(!path)return null;
 if(!$.NSFileManager.defaultManager.fileExistsAtPath($(path)))return null;
 var state=JSON.parse(sessionReadFile(path));DesktopSession.continuation(state,request.app,request.conversationId);return state;
}
function writeDesktopConversation(path,state){
 if(!path)return;
 var folder=path.slice(0,path.lastIndexOf('/')),manager=$.NSFileManager.defaultManager;
 if(!manager.createDirectoryAtPathWithIntermediateDirectoriesAttributesError($(folder),true,$({NSFilePosixPermissions:448}),null))throw Error('无法保存电脑会话绑定，未继续发送');
 if(!$(JSON.stringify(state)).dataUsingEncoding($.NSUTF8StringEncoding).writeToFileAtomically($(path),true))throw Error('电脑会话绑定写入失败，已停止');
 manager.setAttributesOfItemAtPathError($({NSFilePosixPermissions:384}),$(path),null);
}
