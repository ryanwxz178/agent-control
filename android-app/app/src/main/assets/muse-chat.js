'use strict';
/* Adapted from OpenMuse conversation-queue.ts, chat.tsx and assistant-markdown.ts (MIT).
 * Native SSH and Agent protocols remain owned by Agent Control; no Intelligence account required. */
class ConversationQueue {
  constructor(){this.state={pending:[],running:false,paused:false};this.listeners=new Set();}
  getSnapshot=()=>this.state;
  subscribe=(listener)=>{this.listeners.add(listener);return()=>this.listeners.delete(listener);};
  update(patch){this.state={...this.state,...patch};for(const listener of this.listeners)listener();}
  enqueue(message){this.update({pending:[...this.state.pending,message]});}
  remove(id){this.update({pending:this.state.pending.filter(message=>message.id!==id)});}
  restore(message){this.update({pending:[message,...this.state.pending.filter(queued=>queued.id!==message.id)]});}
  pause(){this.update({paused:true});}
  resume(){this.update({paused:false});}
  async flush(sendMessage){
    if(this.state.running||this.state.paused)return;
    this.update({running:true});
    try{while(this.state.pending.length&&!this.state.paused){const [message,...pending]=this.state.pending;this.update({pending});await sendMessage(message);}}
    catch(error){this.update({paused:true});throw error;}
    finally{this.update({running:false});}
  }
}
const museQueue=new ConversationQueue();
const pendingSubmissions=new Map();
const pendingHere=()=>[...pendingSubmissions.values()].includes(state.conversationId);
let queueCompletion=null,serviceTasks=[],serviceReady=false,taskTab='conversations',browserSession='',browserWorking=false,pendingConversationStart=null,endConversationTimer;
const taskSeen=new Map();
const taskLabels={queued:'等待开始',running:'正在执行',waiting_approval:'等待确认',waiting_input:'等待补充',scheduled:'定期检查',paused:'已暂停',succeeded:'已完成',failed:'未完成',cancelled:'已取消',unverifiable:'状态待确认'};
const liveTask=t=>['queued','running','waiting_approval','waiting_input'].includes(t.status);
const currentTasks=()=>serviceTasks.filter(t=>t.request.conversationId===state.conversationId);
const markdown=window.markdownit({html:false,linkify:true,breaks:true,typographer:false});
markdown.renderer.rules.image=(tokens,index)=>markdown.utils.escapeHtml(tokens[index].content||'图片请从附件查看');
const defaultLink=markdown.renderer.rules.link_open||((tokens,index,options,env,self)=>self.renderToken(tokens,index,options));
markdown.renderer.rules.link_open=(tokens,index,options,env,self)=>{
  const token=tokens[index],url=token.attrGet('href');
  try{const parsed=new URL(url);if(!['http:','https:'].includes(parsed.protocol))token.attrSet('href','#');}
  catch(_){token.attrSet('href','#');}
  token.attrSet('rel','noopener noreferrer');return defaultLink(tokens,index,options,env,self);
};
const originalUpdate=updateMessage;
const originalExtras=renderExtras;
renderExtras=function(node,message){
  node.querySelectorAll('.browser-tool-card').forEach(card=>card.remove());originalExtras(node,message);
  for(const page of message.browsers||[]){
    const card=document.createElement('section');card.className='browser-tool-card';
    const label=document.createElement('small');label.textContent='浏览网页';const title=document.createElement('h3');title.textContent=page.title||page.url;
    const source=document.createElement('p');source.textContent=page.url;card.append(label,title,source);
    if(page.preview){const image=document.createElement('img');image.src=page.preview;image.alt='Agent 阅读的网页：'+(page.title||page.url);card.append(image);}
    const button=document.createElement('button');button.className='secondary';const running=serviceTasks.some(t=>t.id===message.taskId&&liveTask(t));button.disabled=running||!connected;button.textContent=running?'任务结束后可接管':'接管浏览器';button.onclick=()=>browserRequest({op:'browserRead',sessionId:page.sessionId});card.append(button);node.append(card);
  }
};
updateMessage=function(node,message){
  originalUpdate(node,message);
  if(!String(message.status||'').startsWith('失败'))node.querySelector('.content').innerHTML=markdown.render(message.text||'');
  const content=node.querySelector('.content');content.classList.add('markdown');
  for(const pre of content.querySelectorAll('pre')){
    const button=document.createElement('button');button.className='code-copy';button.textContent='复制';button.setAttribute('aria-label','复制代码');
    button.onclick=()=>copyReply(pre.querySelector('code')?.textContent||pre.textContent);pre.append(button);
  }
  const footer=node.querySelector('.message-footer');
  if(footer&&!footer.querySelector('.task-origin')&&message.taskId){const label=document.createElement('button');label.className='task-origin';label.textContent='查看任务';label.onclick=()=>openTaskSheet('tasks');footer.append(label);}
};
$('messages').addEventListener('click',event=>{const link=event.target.closest('a');if(!link)return;event.preventDefault();if(link.getAttribute('href')!=='#')call('openLink',link.href);});
syncComposer=function(){
  const draft=$('input').value.trim(),stopping=busy&&!draft;
  $('send').disabled=!busy&&!draft;$('send').setAttribute('aria-label',stopping?'停止任务':busy?'加入待发送消息':'发送消息');
  $('send').classList.toggle('stop-mode',stopping);$('send').replaceChildren(icon(stopping?'square':'arrow-up'));
};
const originalSetBusy=setBusy;
setBusy=function(value){
  const previous=busy;value=value||!!pendingConversationStart;originalSetBusy(value);
  if(state.profile.taskService){$('newChat').disabled=!!pendingConversationStart;$('agentButton').disabled=!!pendingConversationStart;$('accessMode').disabled=!!pendingConversationStart;}
  if(previous&&!value&&queueCompletion){const finish=queueCompletion;queueCompletion=null;finish();}
};
function saveDraft(){state.draft=$('input').value;persist();}
function drawQueue(){
  const snapshot=museQueue.getSnapshot(),panel=$('queuePanel');state.outbox=snapshot.pending;state.queuePaused=snapshot.paused;
  panel.hidden=!snapshot.pending.length;panel.replaceChildren();
  if(snapshot.pending.length){
    const label=document.createElement('p');label.textContent=(snapshot.paused?'待发送 · 已暂停':'接下来发送')+' · '+snapshot.pending.length;panel.append(label);
    for(const message of snapshot.pending){
      const row=document.createElement('div'),text=document.createElement('span');text.textContent=message.text;row.append(text);
      const edit=document.createElement('button');edit.textContent='编辑';edit.onclick=()=>{if($('input').value.trim()){toast('先处理当前输入框中的草稿');return;}museQueue.remove(message.id);$('input').value=message.text;$('input').oninput();$('input').focus();};
      const remove=document.createElement('button');remove.append(icon('x'));remove.setAttribute('aria-label','移除待发送消息');remove.onclick=()=>museQueue.remove(message.id);row.append(edit,remove);panel.append(row);
    }
    if(snapshot.paused){const resume=document.createElement('button');resume.className='text-button';resume.textContent='继续发送';resume.disabled=busy||!connected;resume.onclick=()=>{museQueue.resume();flushQueue();};panel.append(resume);}
  }persist();
}
museQueue.subscribe(drawQueue);
function flushQueue(){
  if(busy||!connected)return;
  void museQueue.flush(async message=>{
    if(message.conversationId!==state.conversationId)throw Error('会话已切换，消息保留在原会话');
    await new Promise((resolve,reject)=>{
      const previousIds=new Set(state.messages.map(m=>m.id));
      queueCompletion=()=>{const failed=currentTasks().some(t=>t.id===message.id&&['failed','unverifiable','cancelled'].includes(t.status))||state.messages.some(m=>!previousIds.has(m.id)&&String(m.status||'').startsWith('失败'));if(failed)reject(Error('本轮未正常完成，后续消息已暂停'));else resolve();};
      dispatchMessage(message.text,message.id);
    });
  }).catch(error=>toast(error.message));
}
function dispatchMessage(text,messageId=cryptoId()){
  const agent=state.agents.find(a=>a.id===state.selected);
  if(state.profile.accessMode==='cli'&&agent?.kind==='desktop'){toast(agent.name+' 当前通过桌面接入，请将接入方式改为「直接桌面」或「默认接入」');$('agentButton').click();if(!$('input').value.trim()){$('input').value=text;saveDraft();syncComposer();}museQueue.pause();if(queueCompletion){const finish=queueCompletion;queueCompletion=null;finish();}return;}
  const request={...state.profile,agent:state.selected,prompt:text,history:conversationHistory(),messageId,conversationId:state.conversationId,accessMode:state.profile.accessMode||'auto'};
  if(state.profile.taskService)pendingSubmissions.set(messageId,state.conversationId);
  add({id:messageId,role:'user',text,createdAt:Date.now()});setBusy(true);call('send',JSON.stringify(request));
}
send=function(){
  if(pendingConversationStart){toast('正在结束上一会话，请稍候');return;}
  if(state.conversationClosed){toast('该会话已结束，请新建对话');return;}
  const text=$('input').value.trim();if(!text){if(busy)stopTask();return;}
  if(!connected){openSettings();return;}if(state.profile.taskService&&!serviceReady){toast('电脑任务服务正在准备，请稍候再发送');return;}if(!bridge){toast('预览模式不能发送');return;}
  $('input').value='';$('input').style.height='auto';saveDraft();
  if(busy||museQueue.getSnapshot().pending.length){museQueue.enqueue({id:cryptoId(),text,conversationId:state.conversationId});syncComposer();flushQueue();}
  else dispatchMessage(text);
};
function stopTask(){
  museQueue.pause();
  if(state.profile.taskService){const task=[...currentTasks()].reverse().find(liveTask);if(task)call('taskRequest',JSON.stringify({op:'control',taskId:task.id,action:'cancel'}));else toast('正在核对提交状态，请等待任务列表同步');}
  else call('cancel');$('taskLabel').textContent='正在请求中断…';syncComposer();
}
$('send').onclick=send;$('stop').onclick=stopTask;$('composerAgent').onclick=()=>{$('agentButton').click();};
const originalInput=$('input').oninput;
$('input').oninput=()=>{originalInput();saveDraft();};
const originalApply=applySettings,originalCapture=captureSettings;
applySettings=function(){originalApply();$('taskService').checked=state.profile.taskService!==false;$('serviceJava').value=state.profile.serviceJava||'';};
captureSettings=function(){return{...originalCapture(),taskService:$('taskService').checked,serviceJava:$('serviceJava').value.trim()};};
state.profile.taskService=true;
const cleanMessages=messages=>messages.map(m=>({...m,browsers:(m.browsers||[]).map(({preview,text,...page})=>page),desktop:false,interactions:(m.interactions||[]).map(i=>({title:i.title,state:'closed',message:'等待电脑同步当前请求'})),attachments:(m.attachments||[]).map(a=>({...a,expired:true}))}));
persist=function(){
  clearTimeout(persistTimer);persistTimer=setTimeout(()=>{
    const conversations=(state.conversations||[]).slice(-10).map(c=>({...c,messages:cleanMessages((c.messages||[]).slice(-50))}));
    const snapshot={...state,draft:$('input').value,messages:cleanMessages(state.messages.slice(-80)),conversations};
    let raw=JSON.stringify(snapshot);while(raw.length>280000&&snapshot.conversations.length){snapshot.conversations.shift();raw=JSON.stringify(snapshot);}while(raw.length>280000&&snapshot.messages.length){snapshot.messages.shift();raw=JSON.stringify(snapshot);}call('save',raw);
  },350);
};
function archiveConversation(){
  const record={id:state.conversationId,closed:!!state.conversationClosed,selected:state.selected,messages:state.messages,draft:$('input').value,outbox:[...museQueue.getSnapshot().pending],title:state.messages.find(m=>m.role==='user')?.text.slice(0,40)||'新对话'};
  state.conversations=[...(state.conversations||[]).filter(c=>c.id!==record.id),record].slice(-10);
}
const originalStart=startConversation;
function finishConversationStart(nextAgent){
  clearTimeout(endConversationTimer);pendingConversationStart=null;state.conversationClosed=true;closeInteractions('会话已结束');archiveConversation();
  museQueue.update({pending:[],running:false,paused:false});taskSeen.clear();setBusy(false);state.conversationClosed=false;originalStart(nextAgent);state.draft='';agentList();updateSelection();persist();toast('已结束上一会话，开始新对话');
}
function requestConversationEnd(){
  if(!pendingConversationStart)return;
  call('taskRequest',JSON.stringify({op:'endConversation',conversationId:pendingConversationStart.id}));
}
startConversation=function(nextAgent=state.selected){
  if(pendingConversationStart)return;
  if(state.profile.taskService&&!connected&&(state.messages.length||currentTasks().length||pendingHere())){toast('请先连接电脑，确认上一会话结束后再开始');return;}
  if(state.profile.taskService&&connected&&(state.messages.length||currentTasks().length||pendingHere())){
    if(!serviceReady){toast('等待电脑任务服务就绪后再结束会话');return;}
    pendingConversationStart={id:state.conversationId,nextAgent};museQueue.pause();museQueue.update({pending:[]});setBusy(true);$('taskLabel').textContent='正在结束上一会话…';requestConversationEnd();return;
  }
  if(busy){museQueue.pause();museQueue.update({pending:[]});pendingConversationStart={id:state.conversationId,nextAgent};call('cancel');$('taskLabel').textContent='正在结束上一会话…';return;}
  finishConversationStart(nextAgent);
};
$('newChat').onclick=()=>startConversation();
function switchConversation(id){
  if(pendingConversationStart){toast('正在结束上一会话，请稍候');return;}
  if(id===state.conversationId){$('taskSheet').close();return;}
  if(!state.profile.taskService&&busy){toast('当前任务结束后再切换会话');return;}
  archiveConversation();const local=state.conversations.find(c=>c.id===id),remote=serviceTasks.find(t=>t.request.conversationId===id);
  state.conversationId=id;state.conversationClosed=!!local?.closed;state.messages=local?.messages||[];state.selected=local?.selected||remote?.request.agent||'auto';$('input').value=local?.draft||'';
  museQueue.update({pending:local?.outbox||[],running:false,paused:true});taskSeen.clear();render();setBusy(false);$('taskSheet').close();if(state.profile.taskService)applyTasks(serviceTasks);persist();syncComposer();
}
function taskControls(task){
  const row=document.createElement('div');row.className='task-controls';
  const choices=task.status==='paused'?[['resume','继续']]:['queued','scheduled'].includes(task.status)?[['pause','暂停'],['cancel','取消']]:liveTask(task)?[['cancel','请求中断']]:[];
  for(const [action,label]of choices){const button=document.createElement('button');button.className='secondary';button.textContent=label;button.disabled=!connected;button.onclick=()=>{button.disabled=true;call('taskRequest',JSON.stringify({op:'control',taskId:task.id,action}));};row.append(button);}return row;
}
function drawTaskList(){
  const list=$('taskList');list.replaceChildren();$('browserEntry').hidden=taskTab!=='browser';
  for(const [name,id]of [['conversations','conversationsTab'],['tasks','tasksTab'],['browser','browserTab']])$(id).setAttribute('aria-pressed',String(taskTab===name));
  if(taskTab==='browser')return;
  if(taskTab==='conversations'){
    const entries=new Map((state.conversations||[]).map(c=>[c.id,c]));entries.set(state.conversationId,{id:state.conversationId,messages:state.messages,title:state.messages.find(m=>m.role==='user')?.text||'当前对话'});
    for(const task of serviceTasks){const id=task.request.conversationId;if(id&&!entries.has(id))entries.set(id,{id,title:task.request.prompt});}
    for(const entry of [...entries.values()].reverse()){const button=document.createElement('button');button.className='conversation-row';const title=document.createElement('strong');title.textContent=entry.title||'新对话';const detail=document.createElement('small');const tasks=serviceTasks.filter(t=>t.request.conversationId===entry.id);const times=[...(entry.messages||[]).map(recordedTime),...tasks.map(recordedTime)].filter(t=>t!==null);const time=times.length?Math.max(...times):recordedTime(entry);detail.textContent=(entry.id===state.conversationId?'当前会话':tasks.some(liveTask)?'电脑正在执行':entry.closed?'已结束 · 查看记录':'点按继续对话')+' · '+formatTime(time);button.append(title,detail);button.onclick=()=>switchConversation(entry.id);list.append(button);}
  }else for(const task of [...serviceTasks].reverse()){
    const card=document.createElement('article');card.className='task-card';const status=document.createElement('small');status.textContent=taskLabels[task.status]||task.status;status.dataset.status=task.status;
    const title=document.createElement('h3');title.textContent=task.request.prompt.slice(0,180);const meta=document.createElement('p');meta.textContent=formatTime(recordedTime(task))+' · '+(state.agents.find(a=>a.id===task.request.agent)?.name||'自动调度');card.append(status,title,meta);
    if(task.error){const error=document.createElement('p');error.className='error';error.textContent=task.error;card.append(error);}
    const steps=(task.events||[]).filter(e=>e.type==='run');for(const step of steps){const line=document.createElement('p');const finished=task.events.find(e=>e.type==='update'&&e.data.id===step.data.id)?.data.status;line.textContent=step.data.agent+' · '+step.data.label+' · '+(['failed','unverifiable','cancelled'].includes(task.status)?taskLabels[task.status]:finished||'等待回复');card.append(line);}
    card.append(taskControls(task));const open=document.createElement('button');open.className='text-button';open.textContent='查看对话';open.onclick=()=>switchConversation(task.request.conversationId);card.append(open);list.append(card);
  }
  if(!list.children.length){const empty=document.createElement('p');empty.className='hint';empty.textContent='电脑上的任务会显示在这里。连接后发送一条消息开始。';list.append(empty);}
}
function openTaskSheet(tab='conversations'){taskTab=tab;drawTaskList();if(!$('taskSheet').open)$('taskSheet').showModal();}
$('historyButton').onclick=()=>openTaskSheet();$('conversationsTab').onclick=()=>{taskTab='conversations';drawTaskList();};$('tasksTab').onclick=()=>{taskTab='tasks';drawTaskList();};$('browserTab').onclick=()=>{taskTab='browser';drawTaskList();};
for(const id of ['taskSheet','browserViewer'])document.querySelector('[data-close="'+id+'"]').onclick=()=>$(id).close();
const originalReceive=window.receive;
function applyTasks(tasks){
  serviceTasks=[...tasks].sort((a,b)=>a.createdAt-b.createdAt);for(const task of serviceTasks)pendingSubmissions.delete(task.id);const following=$('chat').scrollHeight-$('chat').clientHeight-$('chat').scrollTop<140;
  for(const task of currentTasks()){
    pendingSubmissions.delete(task.id);
    if(task.request.kind==='monitor')continue;
    if(!state.messages.some(m=>m.id===task.id))add({id:task.id,role:'user',text:task.request.prompt,createdAt:task.createdAt});
    let seen=taskSeen.get(task.id)||0;
    for(const event of [...task.events].sort((a,b)=>a.seq-b.seq)){
      const data={...event.data};if(event.seq<=seen)continue;
      if(event.type==='run')data.createdAt=event.createdAt??data.createdAt??task.createdAt;
      if(event.type==='run'&&state.messages.some(m=>m.id===data.id)){seen=Math.max(seen,event.seq);continue;}
      if(event.type==='interaction'){
        const m=state.messages.find(m=>m.id===data.id);if(m)m.interactions=(m.interactions||[]).filter(i=>i.token!==data.token);
      }
      if(event.type==='attachment'){const m=state.messages.find(m=>m.id===data.id);if(m)m.attachments=(m.attachments||[]).filter(a=>a.attachmentId!==data.attachmentId);data.expired=false;}
      if(event.type==='browser'){const m=state.messages.find(m=>m.id===data.id);if(m){m.browsers=[...(m.browsers||[]).filter(page=>page.sessionId!==data.sessionId),data];const node=findNode(m.id);if(node)renderExtras(node,m);}seen=Math.max(seen,event.seq);continue;}
      originalReceive(event.type,data);const message=state.messages.find(m=>m.id===data.id);if(message){message.taskId=task.id;const node=findNode(message.id);if(node)updateMessage(node,message);}seen=Math.max(seen,event.seq);
    }
    taskSeen.set(task.id,seen);
    for(const message of state.messages.filter(m=>m.taskId===task.id&&m.browsers?.length)){const node=findNode(message.id);if(node)renderExtras(node,message);}
    if(['failed','unverifiable','cancelled'].includes(task.status)){
      if(!state.messages.some(m=>m.taskId===task.id&&m.role==='assistant'))add({id:task.id+'_outcome',role:'assistant',agent:state.agents.find(a=>a.id===task.request.agent)?.name||'电脑任务',text:task.error||taskLabels[task.status],status:task.status==='failed'?'失败：'+(task.error||'任务未开始执行'):taskLabels[task.status],taskId:task.id,createdAt:task.events.at(-1)?.createdAt??task.createdAt});
      if(task.id===currentTasks().filter(t=>t.request.kind!=='monitor').at(-1)?.id)museQueue.pause();
      for(const message of state.messages.filter(m=>m.taskId===task.id)){for(const interaction of message.interactions||[]){interaction.state='closed';interaction.message='任务已结束，原确认失效';}const node=findNode(message.id);if(node)renderExtras(node,message);}
      for(const message of state.messages.filter(m=>m.taskId===task.id&&m.role==='assistant'&&m.status!=='已完成')){message.status=task.status==='failed'?'失败：'+task.error:taskLabels[task.status];if(!message.text?.trim())message.text=task.error||'任务已中断，请在电脑确认执行结果。';const node=findNode(message.id);if(node)updateMessage(node,message);}
    }
  }
  const active=currentTasks().filter(liveTask);setBusy(active.length>0||pendingHere());
  if(active.length)$('taskLabel').textContent=(taskLabels[active.at(-1).status]||'电脑正在处理')+' · 可继续输入下一条消息';
  if(!busy&&!museQueue.getSnapshot().paused)flushQueue();if(following)scrollEnd();else $('jumpLatest').hidden=false;
  if($('taskSheet').open)drawTaskList();persist();
}
window.receive=(type,data)=>{
  if(type==='serviceResponse'&&data.conversationId===pendingConversationStart?.id){
    if(data.conversationEnded)finishConversationStart(pendingConversationStart.nextAgent);
    else{clearTimeout(endConversationTimer);endConversationTimer=setTimeout(requestConversationEnd,500);}
    return;
  }
  if(type==='serviceSnapshot'){if(serviceReady)$('serviceBanner').hidden=true;applyTasks(data.tasks||[]);$('footerHint').textContent='电脑持续执行 · 重连可查看结果';return;}
  if(type==='serviceReady'){serviceReady=true;$('serviceBanner').hidden=true;return;}
  if(type==='serviceError'){clearTimeout(endConversationTimer);pendingConversationStart=null;pendingSubmissions.clear();museQueue.pause();setBusy(currentTasks().some(liveTask));browserWorking=false;$('browserStatus').textContent=data.message;$('serviceBanner').hidden=false;$('serviceBanner').textContent=data.message;toast(data.message);return;}
  if(type==='serviceResponse'){if(data.browserClosed){browserWorking=false;$('browserViewer').close();browserSession='';toast('浏览器会话已结束');}if(data.browser)showBrowser(data.browser);return;}
  if(type==='busy'&&state.profile.taskService&&serviceReady){if(!data.value)setBusy(currentTasks().some(liveTask)||pendingHere());return;}
  originalReceive(type,data);
  if(type==='busy'&&!data.value&&!state.profile.taskService&&pendingConversationStart){finishConversationStart(pendingConversationStart.nextAgent);return;}
  if(type==='restore'){
    state.profile.taskService=data.profile?.taskService!==false;state.conversations=state.conversations||[];
    $('input').value=state.draft||'';museQueue.update({pending:state.outbox||[],paused:true,running:false});applySettings();syncComposer();
  }
  if(type==='connected'){state.profile.taskService=data.profile.taskService===true;serviceReady=false;taskSeen.clear();serviceTasks=[];$('serviceBanner').hidden=!state.profile.taskService;$('serviceBanner').textContent='正在连接电脑任务服务…';}
  if(type==='rejected')pendingSubmissions.delete(data.messageId);
  if(type==='connection'){pendingSubmissions.clear();serviceReady=false;museQueue.pause();}
  if(type==='error'){museQueue.pause();}
  if(type==='busy'&&!data.value&&!state.profile.taskService){if(!museQueue.getSnapshot().paused)flushQueue();}
};
const originalBack=window.back;window.back=()=>{if($('browserViewer').open)$('browserViewer').close();else if($('taskSheet').open)$('taskSheet').close();else originalBack();};
function browserRequest(operation){
  if(!connected||!serviceReady){toast('先连接电脑持久任务服务');return;}
  if(browserWorking)return;browserWorking=true;$('browserStatus').textContent='正在更新电脑浏览器…';call('taskRequest',JSON.stringify(operation));
}
function showBrowser(page){browserWorking=false;browserSession=page.sessionId;$('browserTitle').textContent=page.title||'浏览器';$('browserLocation').textContent=page.url;$('browserStatus').textContent='点击画面可接管操作';$('browserImage').src=page.preview;$('taskSheet').close();if(!$('browserViewer').open)$('browserViewer').showModal();}
$('browserOpen').onclick=()=>browserRequest({op:'browserOpen',url:$('browserUrl').value.trim()});
$('browserInstall').onclick=()=>{
  if(!serviceReady){toast('先连接电脑持久任务服务');return;}
  call('taskRequest',JSON.stringify({op:'submit',request:{messageId:cryptoId(),conversationId:state.conversationId,kind:'browser_setup',prompt:'安装 OpenMuse 浏览器',directory:state.profile.directory}}));taskTab='tasks';drawTaskList();
};
$('browserEnd').onclick=()=>browserRequest({op:'browserClose',sessionId:browserSession});
$('browserRefresh').onclick=()=>browserRequest({op:'browserRead',sessionId:browserSession});
$('browserUp').onclick=()=>browserRequest({op:'browserInput',sessionId:browserSession,input:{type:'scroll',deltaY:-600}});
$('browserDown').onclick=()=>browserRequest({op:'browserInput',sessionId:browserSession,input:{type:'scroll',deltaY:600}});
$('browserEnter').onclick=()=>browserRequest({op:'browserInput',sessionId:browserSession,input:{type:'key',key:'Enter'}});
$('browserType').onclick=()=>{const text=$('browserText').value;if(text)browserRequest({op:'browserInput',sessionId:browserSession,input:{type:'text',text}});};
$('browserImage').onclick=event=>{const r=event.currentTarget.getBoundingClientRect();browserRequest({op:'browserInput',sessionId:browserSession,input:{type:'click',x:Math.floor((event.clientX-r.left)*1280/r.width),y:Math.floor((event.clientY-r.top)*800/r.height)}});};
$('monitorCreate').onclick=()=>{
  const url=$('browserUrl').value.trim(),interval=Number($('monitorInterval').value);if(!url||!Number.isInteger(interval)||interval<1||interval>10080){toast('请输入网页地址和 1–10080 分钟的间隔');return;}
  if(!serviceReady){toast('先连接电脑持久任务服务');return;}
  call('taskRequest',JSON.stringify({op:'submit',request:{messageId:cryptoId(),conversationId:state.conversationId,kind:'monitor',prompt:'监控网页变化：'+url,url,intervalMinutes:interval,condition:'change'}}));taskTab='tasks';drawTaskList();
};
applySettings();syncComposer();
