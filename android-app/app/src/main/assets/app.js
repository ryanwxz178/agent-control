'use strict';
const $=id=>document.getElementById(id);
let state={profile:{host:'',port:22,user:'',directory:'',write:false,accessMode:'auto',hostPermissions:false},messages:[],selected:'auto',agents:[]};
state.conversationId=cryptoId();
let connected=false,busy=false,persistTimer,toastTimer;
const systemTheme=matchMedia("(prefers-color-scheme: dark)");
function setTheme(dark){document.documentElement.dataset.theme=dark?"dark":"light";}
setTheme(systemTheme.matches);systemTheme.addEventListener?.("change",e=>setTheme(e.matches));
const bridge=window.Pocket;
const call=(name,...args)=>{if(!bridge){toast('预览界面；请安装 APK 后连接电脑');return false;}bridge[name](...args);return true;};
function toast(text){$('toast').textContent=text;$('toast').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').hidden=true,6000);}
function persist(){clearTimeout(persistTimer);persistTimer=setTimeout(()=>{const snapshot={...state,messages:state.messages.slice(-80).map(m=>({...m,desktop:false,interactions:(m.interactions||[]).map(i=>({title:i.title,state:'closed',message:'历史确认不可再次操作'})),attachments:(m.attachments||[]).map(a=>({...a,expired:true}))}))};let raw=JSON.stringify(snapshot);while(raw.length>280000&&snapshot.messages.length){snapshot.messages.shift();raw=JSON.stringify(snapshot);}call('save',raw);},350);}
function scrollEnd(){const chat=$('chat');chat.scrollTop=chat.scrollHeight;$('jumpLatest').hidden=true;}
function icon(name){const img=document.createElement('img');img.className='icon';img.src='icons/'+name+'.svg';img.alt='';img.setAttribute('aria-hidden','true');return img;}
function syncComposer(){$('send').disabled=busy||!$('input').value.trim();}
function updateWelcome(){$('welcomeConnect').hidden=connected;$('welcomeText').textContent=connected?'电脑已连接，选择 Agent 或直接开始。':'连接电脑，在这里接着聊。';$('agentSubline').textContent=connected?'已连接 '+state.agents.length+' 个 Agent':'选择电脑上的 Agent';}
async function copyReply(text,label='回复'){try{if(bridge&&bridge.copyText)bridge.copyText(text);else if(navigator.clipboard)await navigator.clipboard.writeText(text);else throw Error('unavailable');toast('已复制'+label);}catch(_){toast('暂时无法复制，请长按'+label+'选择文字。');}}
function recordedTime(record){
 const value=record?.createdAt;
 if(value!==undefined&&value!==null&&value!==''&&Number.isFinite(new Date(value).getTime()))return new Date(value).getTime();
 const prefix=String(record?.id||'').match(/^([a-z0-9]+)-/);
 const inferred=prefix?parseInt(prefix[1],36):NaN;
 return inferred>=1577836800000&&inferred<=Date.now()?inferred:null;
}
function formatTime(value){
 if(value===null||value===undefined||!Number.isFinite(new Date(value).getTime()))return '时间未记录';
 const date=new Date(value),pad=n=>String(n).padStart(2,'0');
 return `${date.getFullYear()}-${pad(date.getMonth()+1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}
function messageTime(m){
 const time=document.createElement('time');time.className='message-time';const value=recordedTime(m);
 time.textContent=formatTime(value);if(value!==null)time.dateTime=new Date(value).toISOString();return time;
}
function updateMessage(node,m){
 const failed=String(m.status||'').startsWith('失败');node.classList.toggle('failed',failed);
 node.querySelector('.content').textContent=failed?'这次任务未完成。请查看详情，确认电脑状态后再发送。':m.text||'';
 const status=node.querySelector('.status');if(status){status.textContent=failed?'未完成':m.status||'等待回复';status.classList.toggle('failed',failed);}
 const copy=node.querySelector('.copy-button');if(copy)copy.hidden=m.status!=='已完成'||!m.text;
 let details=node.querySelector('.failure-details');
 if(failed){if(!details){details=document.createElement('details');details.className='failure-details';const summary=document.createElement('summary');summary.textContent='查看错误详情';details.append(summary,document.createElement('pre'));node.append(details);}details.querySelector('pre').textContent=(m.text&&m.status.indexOf(m.text)<0?m.text+'\n\n':'')+m.status.replace(/^失败[：:]?\s*/,'');}else if(details)details.remove();
}
function renderMessage(m){
  const node=document.createElement('article');node.className='message '+m.role;node.dataset.id=m.id;
  if(m.role==='notice'){node.className='notice'+(m.error?' error':'');node.textContent=m.text;node.append(messageTime(m));return node;}
  const meta=document.createElement('div');meta.className='meta';
  if(m.role!=='user'){const badge=document.createElement('span');badge.className='agent-badge';badge.textContent=(m.agent||'O').slice(0,1);meta.append(badge);const name=document.createElement('span');name.textContent=m.agent+' · '+(m.label||'回复');meta.append(name);node.append(meta);}
  const content=document.createElement('div');content.className='content';content.textContent=m.text||'';node.append(content);
  node.append(messageTime(m));
  if(m.role!=='user'){const foot=document.createElement('div');foot.className='message-footer';const status=document.createElement('div');status.className='status';const copy=document.createElement('button');copy.className='copy-button';copy.setAttribute('aria-label','复制回复');copy.append(icon('copy'));copy.onclick=()=>copyReply(m.text);foot.append(status,copy);node.append(foot);updateMessage(node,m);}
  if(m.role==='user'){const copy=document.createElement('button');copy.type='button';copy.className='user-copy-button';copy.setAttribute('aria-label','复制指令');copy.title='复制指令';copy.append(icon('copy'));copy.onclick=()=>copyReply(m.text,'指令');node.prepend(copy);}
  renderExtras(node,m);return node;
}
const automaticPreviews=new Set();const attachmentCache=new Map();let desktopView=null,desktopRun='';
function findNode(id){return Array.from($('messages').children).find(n=>n.dataset.id===id);}
function renderExtras(node,m){
 if(m.role!=='assistant')return;
 node.querySelectorAll('.interaction,.attachment-list,.desktop-button').forEach(n=>n.remove());
 for(const interaction of m.interactions||[]){
  const panel=document.createElement('section');panel.className='interaction';panel.dataset.token=interaction.token||'';
  const heading=document.createElement('h3');heading.textContent=interaction.title;panel.append(heading);
  if(interaction.detail){
   if(interaction.desktop){const question=interaction.detail.split('\n').find(line=>/[?？]$/.test(line.trim()));if(question){const p=document.createElement('p');p.className='interaction-question';p.textContent=question;panel.append(p);}}
   const detail=document.createElement('pre');detail.textContent=interaction.detail;
   const disclosure=document.createElement('details');disclosure.className='interaction-detail';const summary=document.createElement('summary');summary.textContent='查看电脑请求详情';disclosure.append(summary,detail);if(!interaction.desktop)disclosure.open=true;panel.append(disclosure);
  }
  const active=interaction.state==='pending'&&interaction.expiresAt>Date.now();
  const actions=document.createElement('div');actions.className='interaction-actions';
  const submit=answer=>{if(interaction.state!=='pending'||interaction.expiresAt<=Date.now())return;interaction.state='submitting';renderExtras(node,m);call('respond',interaction.token,JSON.stringify(answer));};
  const desktopInputs=[];
  if(interaction.desktop&&interaction.inputs?.length){
   interaction.textDrafts=interaction.textDrafts||{};
   for(const field of interaction.inputs){
    const label=document.createElement('label');label.textContent=field.label;
    const input=document.createElement('textarea');input.className='interaction-text-input';input.rows=3;input.maxLength=10000;input.disabled=!active;input.required=!!field.required;input.setAttribute('aria-label',field.label);input.dataset.fieldId=field.id;
    input.value=interaction.textDrafts[field.id]??field.value??'';input.placeholder=field.required?'填写你的具体回答':'可以补充细节，也可以留空';input.oninput=()=>{interaction.textDrafts[field.id]=input.value;};label.append(input);
    if(m.agent==='TeleAgent'&&!field.required){const extra=document.createElement('details');extra.className='teleagent-supplement';extra.open=!!interaction.expandSupplement;const summary=document.createElement('summary');summary.textContent=field.label;label.firstChild.textContent='';extra.append(summary,label);extra.ontoggle=()=>{if(extra.isConnected)interaction.expandSupplement=extra.open;};panel.append(extra);}else panel.append(label);
    desktopInputs.push([field,input]);
   }
   const save=document.createElement('button');save.className='secondary interaction-text-save';save.textContent='填写并继续';save.disabled=!active;desktopInputs.forEach(([f,i])=>{i.oninput=()=>{interaction.textDrafts[f.id]=i.value;save.disabled=!active;};});save.onclick=()=>{if(desktopInputs.some(([f,i])=>f.required&&!i.value.trim())){toast('请填写自定义回答');return;}interaction.selectedLabel='填写并继续';submit({choice:'__desktop_text__',textValues:Object.fromEntries(desktopInputs.map(([f,i])=>[f.id,i.value]))});};panel.append(save);
  }
  if(interaction.kind==='questions'){
   const inputs=[];
   for(const q of interaction.questions||[]){
    const label=document.createElement('label');label.textContent=q.question;
    const input=document.createElement('input');input.type=q.isSecret?'password':'text';input.autocomplete='off';input.maxLength=10000;input.disabled=!active;input.setAttribute('aria-label',q.question);label.append(input);inputs.push([q.id,input]);
    if(q.options?.length){const choices=document.createElement('div');choices.className='interaction-actions';for(const option of q.options){const b=document.createElement('button');b.className='secondary';b.type='button';b.textContent=option.label+(option.description?' · '+option.description:'');b.disabled=!active;b.onclick=()=>{input.value=option.label;};choices.append(b);}label.append(choices);}
    panel.append(label);
   }
   const send=document.createElement('button');send.className='primary';send.textContent='提交回答';send.disabled=!active;send.onclick=()=>{if(inputs.some(([id,input])=>!input.value.trim())){toast('请填写每个问题');return;}submit({answers:Object.fromEntries(inputs.map(([id,input])=>[id,input.value]))});};actions.append(send);
  }else for(const option of interaction.options||[]){const button=document.createElement('button');button.className='secondary';button.textContent=option.label;button.dataset.choice=option.id;button.classList.toggle('selected',option.selected===true);if(typeof option.selected==='boolean')button.setAttribute('aria-pressed',String(option.selected));button.disabled=!active;button.onclick=()=>{const progress=interaction.desktop&&/^(下一题|暂不回答|提交).*[（(]\d+\/\d+ 已回答[）)]$/.test(option.id);if(progress&&desktopInputs.some(([f,i])=>f.required&&!i.value.trim())){toast('请填写自定义回答');return;}interaction.selectedLabel=option.label;submit({choice:option.id,...(progress&&desktopInputs.length?{textValues:Object.fromEntries(desktopInputs.map(([f,i])=>[f.id,i.value]))}:{})});};actions.append(button);}
  if(!active&&interaction.state!=='submitting'){actions.hidden=true;const result=document.createElement('p');result.className='interaction-result';result.textContent=interaction.selectedLabel?'已选择：'+interaction.selectedLabel:'本次确认已结束';panel.append(result);}panel.append(actions);const status=document.createElement('p');status.className='interaction-state';status.textContent=active?'等待你选择，3 分钟后失效':interaction.state==='submitting'?'正在提交，请勿重复操作':interaction.message||'请求已失效，请在电脑查看';panel.classList.toggle('closed',!active);panel.append(status);node.append(panel);
 }
 if(m.desktop){const b=document.createElement('button');b.className='secondary desktop-button';b.textContent='查看电脑与操作';b.onclick=()=>openDesktop(m.id);node.append(b);}
 if(m.attachments?.length){const list=document.createElement('div');list.className='attachment-list';for(const attachment of m.attachments){const b=document.createElement('button');b.className='attachment-button';const title=document.createElement('strong');title.textContent=attachment.name;const sub=document.createElement('small');sub.textContent=attachment.expired?'历史附件 · 请从原电脑重新获取':attachment.loading?'正在读取…':attachment.error||'点按预览或保存 · '+attachment.path;const cached=attachmentCache.get(attachment.attachmentId);if(cached?.preview){const img=document.createElement('img');img.className='attachment-thumbnail';img.alt=attachment.name;const follow=$('chat').scrollHeight-$('chat').clientHeight-$('chat').scrollTop<140;img.onload=()=>{if(follow)scrollEnd();};img.src=cached.preview;b.append(img);}b.append(title,sub);b.disabled=!!attachment.expired||!!attachment.loading;b.onclick=()=>{if(attachmentCache.has(attachment.attachmentId))showAttachment(attachmentCache.get(attachment.attachmentId));else{attachment.loading=true;renderExtras(node,m);call('requestAttachment',attachment.attachmentId);}};list.append(b);}node.append(list);}
}
function closeInteractions(message){for(const m of state.messages){let changed=false;for(const i of m.interactions||[])if(i.state==='pending'||i.state==='submitting'){i.state='closed';i.message=message;changed=true;}const node=findNode(m.id);if(changed&&node)renderExtras(node,m);}}
function showAttachment(data){$('attachmentTitle').textContent=data.name;$('attachmentInfo').textContent=(data.size/1024).toFixed(1)+' KB'+(data.preview?' · 图片预览':' · 保存后可用手机应用打开');$('attachmentImage').hidden=!data.preview;if(data.preview)$('attachmentImage').src=data.preview;else $('attachmentImage').removeAttribute('src');$('saveAttachment').onclick=()=>call('exportAttachment',data.attachmentId);if(!$('attachmentViewer').open)$('attachmentViewer').showModal();}
function openDesktop(run){desktopRun=run;desktopView=null;$('desktopDownloads').replaceChildren();$('enableDesktopTap').checked=false;$('desktopStatus').textContent='正在读取当前 Agent 窗口…';$('desktopControl').replaceChildren(new Option('正在读取',''));$('performDesktop').disabled=true;$('desktopImage').hidden=true;$('desktopText').textContent='';if(!$('desktopViewer').open)$('desktopViewer').showModal();call('inspectDesktop',run);}
function receiveExtra(type,data){
 if(type==='desktopDownloads'){if(data.id!==desktopRun)return true;$('desktopDownloads').replaceChildren(...data.files.map(file=>{const b=document.createElement('button');b.className='attachment-button';b.textContent=file.name+' · '+(file.size/1024).toFixed(1)+' KB';b.onclick=()=>call('addDownload',desktopRun,file.path);return b;}));$('desktopStatus').textContent=data.files.length?'电脑下载目录最近的 '+data.files.length+' 个文件，点击添加到聊天附件':'电脑下载目录为空';return true;}

 if(type==='desktopAvailable'){const m=state.messages.find(m=>m.id===data.id);if(m){m.desktop=true;const node=findNode(m.id);if(node)renderExtras(node,m);}return true;}
 if(type==='desktopPanel'){
  if(data.id!==desktopRun)return true;desktopView=data;$('desktopStatus').textContent=data.warning||'这是电脑当前窗口；选择下面的控件来操作。';$('desktopText').textContent=data.text||'';
  $('desktopImage').hidden=!data.preview;if(data.preview)$('desktopImage').src=data.preview;else $('desktopImage').removeAttribute('src');
  $('desktopControl').replaceChildren(new Option('请选择电脑控件',''),...data.controls.map(c=>new Option((c.editable?'输入框：':'')+c.label,c.id)));$('performDesktop').disabled=true;$('desktopInputLabel').hidden=true;return true;
 }
 if(type==='desktopOperated'){desktopView=null;$('performDesktop').disabled=true;$('desktopStatus').textContent=data.message;return true;}
 if(type==='desktopError'){desktopView=null;$('performDesktop').disabled=true;$('desktopStatus').textContent=data.message;return true;}

 if(type==='interaction'||type==='interactionState'||type==='attachment'){
  const m=state.messages.find(m=>m.id===data.id);if(!m)return true;
  if(type==='interaction'){m.interactions=m.interactions||[];m.interactions.push(data);$('taskLabel').textContent=m.agent+' · 等待你的确认';}
  if(type==='interactionState'){const i=m.interactions?.find(i=>i.token===data.token);if(i)Object.assign(i,data);}
  if(type==='attachment'){m.attachments=m.attachments||[];if(!m.attachments.some(a=>a.attachmentId===data.attachmentId)){m.attachments.push(data);if(data.remote||data.preview){automaticPreviews.add(data.attachmentId);data.loading=true;call('previewAttachment',data.attachmentId);}}}
  const node=findNode(m.id);if(node)renderExtras(node,m);if(type==='interaction')scrollEnd();persist();return true;
 }
 if(type==='attachmentReady'||type==='attachmentError'){
  const follow=$('chat').scrollHeight-$('chat').clientHeight-$('chat').scrollTop<140;
  for(const m of state.messages){const a=m.attachments?.find(a=>a.attachmentId===data.attachmentId);if(a){a.loading=false;a.error=data.message||'';const node=findNode(m.id);if(node)renderExtras(node,m);}}
  if(type==='attachmentReady'){while(attachmentCache.size>=2)attachmentCache.delete(attachmentCache.keys().next().value);attachmentCache.set(data.attachmentId,data);if(automaticPreviews.delete(data.attachmentId)){for(const m of state.messages){const node=findNode(m.id);if(node&&m.attachments?.some(a=>a.attachmentId===data.attachmentId))renderExtras(node,m);}if(follow)scrollEnd();}else showAttachment(data);}else{automaticPreviews.delete(data.attachmentId);toast(data.message);}
  return true;
 }
 return false;
}
setInterval(()=>{for(const m of state.messages){let changed=false;for(const i of m.interactions||[])if(i.state==='pending'&&i.expiresAt<=Date.now()){i.state='closed';i.message='确认已过期，请在电脑查看';changed=true;}if(changed){const node=findNode(m.id);if(node)renderExtras(node,m);}}},1000);
function render(){ $('messages').replaceChildren(...state.messages.map(renderMessage));$('welcome').hidden=state.messages.length>0; updateSelection();syncComposer();updateWelcome(); }
function add(m){m.createdAt=recordedTime(m)??Date.now();m.conversationId=state.conversationId;state.messages.push(m);$('messages').append(renderMessage(m));$('welcome').hidden=true;scrollEnd();persist();}
function notice(text,error=false){add({id:cryptoId(),role:'notice',text,error});}
function cryptoId(){return Date.now().toString(36)+'-'+Math.random().toString(36).slice(2);}
function updateSelection(){const a=state.agents.find(a=>a.id===state.selected);const name=a?.name||(state.selected==='auto'?'自动调度':({workbuddy:'WorkBuddy',teleagent:'TeleAgent',codex:'Codex',claude:'Claude',doubao:'豆包',deepseek:'DeepSeek Harness'}[state.selected]||state.selected));$('agentLabel').textContent=name;if(state.selected!=='auto')$('agentIcon').textContent=name.slice(0,1);else $('agentIcon').replaceChildren(icon('bolt'));$('modeLabel').textContent=name+' · '+(state.profile.accessMode==='desktop'?'直接桌面':state.profile.accessMode==='cli'?'仅 CLI':'默认接入');}
function applySettings(){for(const k of ['host','port','user','directory'])$(k).value=state.profile[k]??'';for(const k of ['write','hostPermissions','autoConnect'])$(k).checked=!!state.profile[k];}
function captureSettings(){const p={accessMode:state.profile.accessMode||'auto'};for(const k of ['host','user','directory'])p[k]=$(k).value.trim();p.port=$('port').value.trim()===''?22:Number($('port').value);for(const k of ['write','hostPermissions','autoConnect'])p[k]=$(k).checked;return p;}
function setBusy(value){busy=value;$('taskbar').hidden=!value;syncComposer();$('connect').disabled=value;$('agentButton').disabled=value;$('newChat').disabled=value;$('accessMode').disabled=value;$('connect').textContent=value&&!connected?'正在连接…':'连接并检测 Agent';$('chat').setAttribute('aria-busy',String(value));}
function openSettings(){applySettings();$('settings').showModal();}
$('welcomeConnect').onclick=openSettings;
$('jumpLatest').onclick=scrollEnd;
const bottomObserver=new IntersectionObserver(entries=>{$('jumpLatest').hidden=entries[0].isIntersecting||!state.messages.length;},{root:$('chat'),threshold:0});bottomObserver.observe($('bottomAnchor'));
function agentList(){
  $('accessMode').value=['desktop','cli'].includes(state.profile.accessMode)?state.profile.accessMode:'auto';
  $('agentCount').textContent=connected?'来自当前电脑的 '+state.agents.length+' 个 Agent':'连接电脑后查看已安装的 Agent';
  const list=$('agentList');list.replaceChildren();
  const entries=[{id:'auto',name:'自动调度',detail:'系统选择合适的 Agent，可同时协作'},...state.agents];
  entries.forEach(a=>{const b=document.createElement('button');b.className='agent-option'+(state.selected===a.id?' selected':'');
    b.setAttribute('aria-pressed',String(state.selected===a.id));b.disabled=a.supported===false;
    const avatar=document.createElement('span');avatar.className='agent-avatar';if(a.id==='auto')avatar.append(icon('bolt'));else avatar.textContent=a.name.slice(0,1);
    const text=document.createElement('span'),title=document.createElement('strong'),desc=document.createElement('small');title.textContent=a.name;desc.textContent=a.detail;text.append(title,desc);
    const check=document.createElement('span');check.className='check';if(state.selected===a.id)check.append(icon('check'));check.setAttribute('aria-hidden','true');b.append(avatar,text,check);
    b.onclick=()=>{if(state.selected!==a.id)startConversation(a.id);};list.append(b);
  });
  if(!connected){const p=document.createElement('p');p.className='hint';p.textContent='连接电脑后，在这里显示检测到的 Agent。';list.append(p);}
}
window.receive=function(type,data){
  if(type==='appInfo'){$('appVersion').textContent='APP 版本：'+data.versionName+'（'+data.versionCode+'）';return;}
  if(type==='credentials'){$('credentialStatus').textContent=data.saved?'登录凭据已在手机加密保存，下次打开自动连接。关闭开关可清除。':'成功连接后在手机加密保存，下次打开自动连接。关闭即清除保存的连接凭据。';$('password').placeholder=data.saved?'已保存 · 留空使用该电脑的已保存密码':'输入电脑登录密码或私钥口令';return;}
  if(receiveExtra(type,data))return;
  if(type==='theme'){setTheme(data.dark);return;}
  if(type==='restore'){
    if(data.profile){state={...state,...data};state.agents=[];state.messages=(state.messages||[]).slice(-80);state.messages.forEach(m=>{if(m.role==='assistant'&&!['已完成','已自动切换桌面'].includes(m.status)&&!String(m.status).startsWith('失败'))m.status='连接已结束，执行状态待确认';});render();applySettings();}
  }else if(type==='connected'){
    const oldHost=state.profile.host+'|'+state.profile.port+'|'+state.profile.user;
    const newHost=data.profile.host+'|'+data.profile.port+'|'+data.profile.user;
    if(oldHost!==newHost){state.messages=[];state.conversationId=cryptoId();state.selected='auto';}
    state.profile={...state.profile,...data.profile,accessMode:state.profile.accessMode||'auto'};state.agents=data.agents;connected=true;
    if(state.selected!=='auto'&&!state.agents.some(a=>a.id===state.selected)){toast('当前会话的 Agent 暂未检测到，请检查电脑后重新连接');}
    $('connectionLabel').textContent=data.profile.host;$('dot').classList.add('online');if(!data.recovered)$('settings').close();$('password').value='';$('connectError').hidden=true;
    $('footerHint').textContent='已连接，请保持 APP 在前台';render();persist();toast(data.recovered?'连接已恢复；之前的任务不会自动重发':'已连接，检测到 '+data.agents.length+' 个 Agent');
  }else if(type==='connection'){
    closeInteractions('连接已断开，请在电脑确认');
    $('connectionLabel').textContent=data.status;
    connected=false;$('dot').classList.remove('online');$('footerHint').textContent=data.status+' · 不会自动重发任务';
  }else if(type==='rejected'){
    state.messages=state.messages.filter(m=>m.id!==data.messageId);
    if(!$('input').value.trim())$('input').value=data.prompt||'';
    else if(data.prompt)notice('尚未发送：'+data.prompt,true);
    render();persist();
  }else if(type==='busy'){setBusy(data.value);if(!data.value)closeInteractions('请求已结束');}
  else if(type==='run'){add({id:data.id,role:'assistant',agent:data.agent,label:data.label,text:'',status:'正在启动',createdAt:data.createdAt});$('taskLabel').textContent=data.agent+' · '+data.label;}
  else if(type==='update'){
    const m=state.messages.find(m=>m.id===data.id);if(!m)return;
    m.text=data.text;m.status=data.status;if(String(data.status).startsWith('等待'))$('taskLabel').textContent=m.agent+' · '+data.status;const node=Array.from($('messages').children).find(e=>e.dataset.id===m.id);
    const shouldScroll=$('chat').scrollHeight-$('chat').scrollTop-$('chat').clientHeight<100;
    if(node)updateMessage(node,m);
    if(shouldScroll)scrollEnd();persist();
  }else if(type==='notice'){notice(data.message);}
  else if(type==='error'){if($('settings').open){$('connectError').hidden=false;$('connectError').textContent=data.message;}else {const last=state.messages.filter(m=>m.role==='assistant').at(-1);if(!last||!String(last.status).startsWith('失败')||!String(last.status).includes(data.message))notice(data.message,true);}}
  else if(type==='key'){$('keyStatus').textContent=data.loaded?'私钥已加密保存':'可选 · 本机加密保存';$('removeKey').hidden=!data.loaded;}
  syncComposer();updateWelcome();
};
function startConversation(nextAgent=state.selected){if(busy)return;state.selected=nextAgent;state.messages=[];state.conversationId=cryptoId();$('input').value='';$('input').style.height='auto';render();persist();}
function conversationHistory(){
  const turns=[];let current=null;
  for(const m of state.messages){
    if(m.conversationId!==state.conversationId)continue;
    if(m.role==='user'){current={user:m,replies:[]};turns.push(current);}
    else if(current&&m.role==='assistant'&&m.status==='已完成'&&['回复','自动执行','汇总与执行'].includes(m.label))current.replies.push(m);
  }
  return turns.filter(t=>t.replies.length).slice(-8).map(t=>['用户：'+t.user.text,...t.replies.map(m=>m.agent+'：'+m.text)].join('\n\n')).join('\n\n').slice(-55000);
}
function send(){
  const text=$('input').value.trim();if(!text||busy)return;
  if(!connected){openSettings();return;}
  if(!bridge){toast('预览模式不能发送');return;}
  const history=conversationHistory();
  const messageId=cryptoId();
  const request={...state.profile,agent:state.selected,prompt:text,history,messageId,conversationId:state.conversationId,accessMode:state.profile.accessMode||'auto'};
  add({id:messageId,role:'user',text});$('input').value='';$('input').style.height='auto';setBusy(true);call('send',JSON.stringify(request));
}
$('settingsButton').onclick=openSettings;$('connectionButton').onclick=openSettings;
$('agentButton').onclick=()=>{agentList();$('agents').showModal();};
document.querySelectorAll('[data-close]').forEach(b=>b.onclick=()=>$(b.dataset.close).close());
$('connect').onclick=()=>{if(busy)return;const p=captureSettings();if(!p.host||!p.user){$('connectError').hidden=false;$('connectError').textContent='请填写电脑地址和用户名';const field=!p.host?$('host'):$('user');field.setAttribute('aria-invalid','true');field.focus();return;}if(!Number.isInteger(p.port)||p.port<1||p.port>65535){$('connectError').hidden=false;$('connectError').textContent='端口需要在 1 到 65535 之间';$('port').focus();return;}setBusy(true);$('connectError').hidden=true;call('connect',JSON.stringify({...p,password:$('password').value}));};
for(const k of ['write','hostPermissions','directory'])$(k).onchange=()=>{state.profile[k]=$(k).type==='checkbox'?$(k).checked:$(k).value.trim();persist();};
$('accessMode').onchange=()=>{state.profile.accessMode=$('accessMode').value;updateSelection();persist();};
$('send').onclick=send;$('stop').onclick=()=>{call('cancel');$('taskLabel').textContent='正在请求中断…';};
$('input').oninput=()=>{$('input').style.height='auto';$('input').style.height=Math.min($('input').scrollHeight,140)+'px';syncComposer();};
$('input').onkeydown=e=>{if(e.key==='Enter'&&(e.ctrlKey||e.metaKey)){e.preventDefault();send();}};
$('newChat').onclick=()=>{if(busy)return;startConversation();toast('已开始新对话');};
$('importKey').onclick=()=>call('importKey');$('removeKey').onclick=()=>call('removeKey');
$('resetTrust').onclick=()=>{const p=captureSettings();if(!p.host)return;const host=p.port===22?p.host:'['+p.host+']:'+p.port;call('resetTrust',host);};
document.querySelectorAll('[data-prompt]').forEach(b=>b.onclick=()=>{$('input').value=b.dataset.prompt;$('input').oninput();$('input').focus();});
window.back=()=>{if($('sshGuide').open)$('sshGuide').close();else if($('desktopViewer').open)$('desktopViewer').close();else if($('attachmentViewer').open)$('attachmentViewer').close();else if($('agents').open)$('agents').close();else if($('settings').open)$('settings').close();else toast('可使用系统手势返回桌面；任务期间请保持 APP 前台');};
render();

for(const id of ['host','user','port'])$(id).addEventListener('input',()=>{$(id).removeAttribute('aria-invalid');if($('host').value.trim()&&$('user').value.trim())$('connectError').hidden=true;});
for(const sheet of document.querySelectorAll('dialog'))sheet.addEventListener('click',e=>{if(e.target===sheet){const r=sheet.getBoundingClientRect();if(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom)sheet.close();}});

$('agentDone').onclick=()=>{$('agents').close();$('input').focus();};

$('refreshDesktop').onclick=()=>openDesktop(desktopRun);
$('desktopControl').onchange=()=>{const control=desktopView?.controls.find(c=>c.id===$('desktopControl').value);$('performDesktop').disabled=!control;$('desktopInputLabel').hidden=!control?.editable;$('desktopInput').value=control?.value||'';$('performDesktop').textContent=control?.editable?'填入电脑输入框':control?'在电脑点击「'+control.label+'」':'执行所选操作';};
$('performDesktop').onclick=()=>{const control=desktopView?.controls.find(c=>c.id===$('desktopControl').value);if(!control)return;$('performDesktop').disabled=true;$('desktopStatus').textContent='正在操作，请勿重复提交';const token=desktopView.token;desktopView=null;call('operateDesktop',token,control.id,control.editable?$('desktopInput').value:'');};

$('collectDesktop').onclick=()=>{call('collectDesktop',desktopRun);$('desktopStatus').textContent='正在提取电脑回复中的原始链接…';};

let desktopPointer=null;
$('desktopImage').addEventListener('pointerdown',e=>{if(!e.isPrimary){desktopPointer=null;return;}desktopPointer={x:e.clientX,y:e.clientY,id:e.pointerId};});
$('desktopImage').addEventListener('pointermove',e=>{if(desktopPointer&&Math.hypot(e.clientX-desktopPointer.x,e.clientY-desktopPointer.y)>8)desktopPointer=null;});
$('desktopImage').addEventListener('pointercancel',()=>desktopPointer=null);
$('desktopImage').addEventListener('pointerup',e=>{const point=desktopPointer;desktopPointer=null;if(!point||point.id!==e.pointerId||!desktopView||!$('enableDesktopTap').checked)return;const rect=e.currentTarget.getBoundingClientRect(),token=desktopView.token;desktopView=null;$('performDesktop').disabled=true;$('enableDesktopTap').checked=false;$('desktopStatus').textContent='正在点击电脑画面…';call('tapDesktop',token,(e.clientX-rect.left)/rect.width,(e.clientY-rect.top)/rect.height);});

$('listDownloads').onclick=()=>{call('listDownloads',desktopRun);$('desktopStatus').textContent='正在读取电脑下载目录…';};

$('autoConnect').onchange=()=>{state.profile.autoConnect=$('autoConnect').checked;if(!state.profile.autoConnect)call('forgetAutoConnect');persist();};

// The guide keeps the connection form underneath, preserving unsaved entries.
$('openSshGuide').onclick=()=>$('sshGuide').showModal();
$('guideDone').onclick=()=>$('sshGuide').close();
document.querySelectorAll('[data-platform]').forEach(button=>button.onclick=()=>{document.querySelectorAll('[data-platform]').forEach(b=>b.setAttribute('aria-pressed',String(b===button)));document.querySelectorAll('[data-guide]').forEach(p=>p.hidden=p.dataset.guide!==button.dataset.platform);$('sshGuide').querySelector('.sheet-body').scrollTop=0;});
document.querySelectorAll('[data-copy]').forEach(button=>button.onclick=()=>{if(call('copyText',$(button.dataset.copy).textContent))toast('命令已复制；请在对应电脑执行');});
