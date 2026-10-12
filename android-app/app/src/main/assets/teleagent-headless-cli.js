'use strict';
// TeleAgent's Go program is an HTTP server, not an ACP/chat CLI. This bridge
// starts an isolated server with the current Windows user's protected login.
const fs=require('fs'),path=require('path'),os=require('os'),crypto=require('crypto'),net=require('net');
const {spawn}=require('child_process'),readline=require('readline');
const auth=require(process.env.TELEAGENT_BRIDGE_AUTH_MODULE||'./teleagent-auth.js');
const configuration=require(process.env.TELEAGENT_BRIDGE_CONFIG_MODULE||'./teleagent-config.js');
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
const MAX_JSON=8*1024*1024;
class Bridge {
 constructor(emit,options={}){this.emit=emit;this.options=options;this.session='';this.requests=new Map();this.finished=false;this.closing=false;this.submitted=false;this.active=false;this.lastText='';}
 event(type,fields={}){this.emit({type,...this.session?{sessionId:this.session}:{},...fields});}
 async request(route,method='GET',body,timeout=10000){
  auth.assertCurrentOwner(this.loaded);const timestamp=String(Date.now()),nonce=crypto.randomBytes(12).toString('hex');
  const headers={'X-SA-Sign-Version':'local-v1','X-SA-Timestamp':timestamp,'X-SA-Nonce':nonce,
   'X-SA-Signature':crypto.createHmac('sha256',this.key).update(['local-v1',method,route,timestamp,nonce].join('\n')).digest('base64url'),
   Authorization:'Basic '+Buffer.from('agent-control:'+this.password).toString('base64')};
  if(body!==undefined)headers['Content-Type']='application/json';
  let response;
  try{response=await fetch(this.base+route,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(timeout)});}
  catch{throw Error(method==='POST'?'TeleAgent 请求中断，提交状态未知；不会自动重发':'TeleAgent 后台连接中断');}
  if(!response.ok){await response.body?.cancel();throw Error('TeleAgent 后台接口 HTTP '+response.status);}
  const chunks=[];let bytes=0;for await(const chunk of response.body||[]){bytes+=chunk.length;if(bytes>MAX_JSON)throw Error('TeleAgent 回复超过大小限制');chunks.push(Buffer.from(chunk));}
  const data=Buffer.concat(chunks).toString('utf8');
  auth.assertCurrentOwner(this.loaded);
  try{return data?JSON.parse(data):null;}catch{throw Error('TeleAgent 返回无效 JSON');}
 }
 async start(input){
  if(this.active||this.closing)throw Error('TeleAgent 任务不能重复启动');this.active=true;
  if(process.platform!=='win32')throw Error('TeleAgent 后台 CLI 目前仅支持 Windows');
  if(typeof input.prompt!=='string'||!input.prompt.trim()||input.prompt.length>200000)throw Error('TeleAgent 消息无效');
  const directory=path.resolve(input.directory||process.cwd());if(!fs.statSync(directory).isDirectory())throw Error('TeleAgent 工作目录无效');
  this.loaded=await auth.loadCurrentAuth();
  if(this.closing){this.loaded.authState.token='';return;}
  const appPath=input.appPath||process.env.TELEAGENT_DESKTOP_PATH;
  if(typeof appPath!=='string'||!path.isAbsolute(appPath)||path.basename(appPath).toLowerCase()!=='teleagent.exe')throw Error('TeleAgent 桌面安装路径无效');
  const config=configuration.readTeleAgentConfig({asarPath:path.join(path.dirname(appPath),'resources','app.asar'),ownerRoot:this.loaded.ownerRoot,deviceRoot:this.loaded.root,readPolicy:'allow',writePolicy:input.write===true?'ask':'deny'});
  const permission={'*':input.write===true?'ask':'deny',read:'allow',glob:'allow',grep:'allow',list:'allow',question:'allow',external_directory:'deny',edit:input.write===true?'ask':'deny',write:input.write===true?'ask':'deny',powershell:input.write===true?'ask':'deny',bash:'deny'};
  config.agent[config.default_agent].permission=permission;
  const backendConfig={provider:config.provider,agent:config.agent,default_agent:config.default_agent,model:config.model.providerID+'/'+config.model.modelID,small_model:config.model.providerID+'/'+config.model.modelID,permission,mcp:{},command:{},autoupdate:false,formatter:false,lsp:false,instructions:[]};
  this.root=fs.mkdtempSync(path.join(os.tmpdir(),'agent-control-teleagent-'));this.ownedRoot=path.resolve(this.root);
  const dirs={};for(const name of ['data','config','cache','device','device-config']){dirs[name]=path.join(this.root,name);fs.mkdirSync(dirs[name]);}
  this.key=crypto.randomBytes(32).toString('base64url');this.password=crypto.randomBytes(32).toString('base64url');
  const iv=crypto.randomBytes(12),cipher=crypto.createCipheriv('aes-256-gcm',crypto.createHash('sha256').update(this.key).digest(),iv);
  const encrypted=Buffer.concat([cipher.update(JSON.stringify(this.loaded.authState),'utf8'),cipher.final()]);
  const port=await new Promise((resolve,reject)=>{const server=net.createServer();server.once('error',reject);server.listen(0,'127.0.0.1',()=>{const value=server.address().port;server.close(()=>resolve(value));});});
  if(this.closing)return;
  this.base='http://127.0.0.1:'+port;
  const systemEnv=Object.fromEntries(['SystemRoot','WINDIR','TEMP','TMP','USERPROFILE','HOMEDRIVE','HOMEPATH','LOCALAPPDATA','APPDATA','PROGRAMDATA','ProgramFiles','COMSPEC','ComSpec','PATH','Path','PATHEXT'].filter(k=>process.env[k]).map(k=>[k,process.env[k]]));
  const environment={...systemEnv,XDG_DATA_HOME:dirs.data,XDG_CONFIG_HOME:dirs.config,XDG_CACHE_HOME:dirs.cache,SUPER_AGENT_APP_DATA_DIR:dirs.device,SUPER_AGENT_APP_CONFIG_DIR:dirs['device-config'],SERVER_PORT:String(port),SUPER_AGENT_LOCAL_SESSION_KEY:this.key,OPENCODE_SERVER_USERNAME:'agent-control',OPENCODE_SERVER_PASSWORD:this.password,OPENCODE_CONFIG_CONTENT:JSON.stringify(backendConfig),SUPER_AGENT_AUTH_STATE:JSON.stringify({version:'v1',iv:iv.toString('base64url'),tag:cipher.getAuthTag().toString('base64url'),ciphertext:encrypted.toString('base64url')}),SUPER_AGENT_IGNORE_DOT_GIT:'1',OPENCODE_DISABLE_PROJECT_CONFIG:'true',OPENCODE_DISABLE_EXTERNAL_SKILLS:'true',OPENCODE_DISABLE_CLAUDE_CODE:'1',OPENCODE_DISABLE_AUTOUPDATE:'1',OPENCODE_DISABLE_LSP_DOWNLOAD:'1',SUPER_AGENT_SANDBOX_EXECUTION_MODE:'legacy',OPENCODE_CLIENT:'agent-control',SUPER_AGENT_APP_VERSION:config.metadata.packageVersion};
  const payload=Buffer.from(JSON.stringify(environment)),header=Buffer.alloc(4);header.writeUInt32BE(payload.length);
  const binary=path.join(this.loaded.root,'runtimes','super-agent-code','bin','TeleAgent.exe');
  this.child=spawn(binary,[],{cwd:directory,env:systemEnv,windowsHide:true,stdio:['pipe','pipe','pipe']});
  this.child.stdout.resume();this.child.stderr.resume();this.child.stdin.on('error',()=>{});
  this.exited=new Promise(resolve=>{this.child.once('exit',()=>{this.dead=true;resolve();});this.child.once('error',()=>{this.dead=true;resolve();});});
  this.child.stdin.end(Buffer.concat([header,payload]));payload.fill(0);encrypted.fill(0);
  let ready=false;for(let count=0;count<150&&!this.dead&&!this.closing;count++){try{const response=await fetch(this.base+'/ready',{signal:AbortSignal.timeout(1000)});await response.body?.cancel();if(response.ok){ready=true;break;}}catch{}await delay(200);}
  if(!ready){if(this.closing)return;throw Error('TeleAgent 独立后台启动失败');}
  this.query='?directory='+encodeURIComponent(directory);
  const session=await this.request('/session'+this.query,'POST',{title:'Agent Control CLI'});
  if(this.closing)return;
  if(typeof session?.id!=='string'||!/^ses_[a-zA-Z0-9]+$/.test(session.id))throw Error('TeleAgent 会话身份无效');
  this.session=session.id;this.event('teleagent.session');
  this.model=config.model;
  this.submitted=true;
  // Let the backend allocate its ordered message identifier. Arbitrary IDs can
  // reorder the transcript and make the backend repeat the same user turn.
  await this.request('/session/'+this.session+'/prompt_async'+this.query,'POST',{agent:config.default_agent,model:config.model,parts:[{type:'text',text:input.prompt}]});
  for(let attempt=0;attempt<20&&!this.closing;attempt++){
   const records=await this.request('/session/'+this.session+'/message'+this.query);
   const users=Array.isArray(records)?records.filter(record=>record.info?.sessionID===this.session&&record.info.role==='user'&&(record.parts||[]).filter(part=>part.type==='text').map(part=>part.text||'').join('')===input.prompt):[];
   if(users.length===1){this.messageId=users[0].info.id;break;}if(users.length>1)throw Error('TeleAgent 消息身份不唯一，未重新提交');await delay(100);
  }
  if(this.closing)return;
  if(!this.messageId)throw Error('TeleAgent 未确认消息身份，提交状态未知；不会自动重发');
  const deadline=Date.now()+850000;
  while(!this.closing&&!this.dead&&Date.now()<deadline){await this.poll();if(this.finished)return;await delay(300);}
  if(!this.closing)throw Error('TeleAgent 后台任务中断或超时，状态未知；不会自动重发');
 }
 async poll(){
  const records=await this.request('/session/'+this.session+'/message'+this.query);
  if(this.closing)return;
  if(!Array.isArray(records))throw Error('TeleAgent 消息记录格式无效');
  const own=records.filter(record=>record.info?.sessionID===this.session&&record.info?.role==='assistant'&&record.info?.parentID===this.messageId);
  let text='';for(const record of own){if(record.info.error)throw Error('TeleAgent 模型请求失败（'+String(record.info.error.name||'UnknownError')+'）');for(const part of this.parts(record)){if(part.type==='text')text+=part.text||'';if(part.type==='tool')this.tool(part);}}
  if(text!==this.lastText){this.lastText=text;this.event('teleagent.snapshot',{text});}
  for(const [route,type]of [['/permission','permission'],['/question','question']]){
   const items=await this.request(route+this.query);if(this.closing)return;if(!Array.isArray(items))throw Error('TeleAgent 待确认列表格式无效');
   const ownItems=items.filter(item=>item.sessionID===this.session),visible=new Set();
   for(const item of ownItems){const id=type+':'+item.id;visible.add(id);if(!this.requests.has(id)){this.requests.set(id,{type,request:item,state:'pending'});this.event('teleagent.'+type,{request:item});}}
   for(const [id,item]of this.requests)if(item.type===type&&!visible.has(id)){this.requests.delete(id);this.event('teleagent.interaction.closed',{requestId:item.request.id});}
  }
  const statuses=await this.request('/session/status'+this.query),status=statuses?.[this.session];
  if(this.closing)return;
  if(status?.type==='retry')this.event('teleagent.status',{message:'TeleAgent 正在重试模型请求'});
  // Tool steps can have completed timestamps. Completion needs idle status and
  // the assistant response bound to our submitted message, with no pending asks.
  if((!status||status.type==='idle')&&this.complete(own)&&!this.requests.size){
   const finalRecords=await this.request('/session/'+this.session+'/message'+this.query);
   if(this.closing)return;
   const final=finalRecords.filter(record=>record.info?.sessionID===this.session&&record.info?.role==='assistant'&&record.info?.parentID===this.messageId);
   if(final.some(record=>record.info.error))throw Error('TeleAgent 模型回复失败');
   if(!this.complete(final))return;
   const finalText=final.flatMap(record=>this.parts(record).filter(part=>part.type==='text').map(part=>part.text||'')).join('');
   if(!finalText.trim())throw Error('TeleAgent 没有返回文本回复');
   auth.assertCurrentOwner(this.loaded);this.finished=true;const cleanupError=await this.close();if(cleanupError)throw cleanupError;auth.assertCurrentOwner(this.loaded);this.event('teleagent.done',{text:finalText});
  }
 }
 parts(record){return (record.parts||[]).filter(part=>(!part.sessionID||part.sessionID===this.session)&&(!part.messageID||part.messageID===record.info.id));}
 complete(records){return records.length>0&&records.every(record=>record.info.time?.completed)&&!['tool-calls','tool_calls'].includes(records.at(-1).info.finish)&&!records.some(record=>this.parts(record).some(part=>part.type==='tool'&&['pending','running'].includes(part.state?.status)));}
 tool(part){
  if(!this.tools)this.tools=new Map();const state=part.state||{},id=part.id||part.callID,signature=JSON.stringify(state);
  if(this.tools.get(id)===signature)return;this.tools.set(id,signature);
  this.event('teleagent.tool',{title:state.title||part.tool||'正在处理',status:state.status,output:state.status==='completed'?state.output:undefined,attachments:state.status==='completed'?state.attachments:undefined});
 }
 async answer(input){
  if(input.sessionId!==this.session||this.closing)throw Error('TeleAgent 确认会话已失效');
  const type=input.op==='permission'?'permission':'question',id=type+':'+input.requestId,item=this.requests.get(id);
  if(!item||item.state!=='pending')throw Error('TeleAgent 确认已处理或已失效');
  if(type==='permission'&&!['once','reject'].includes(input.reply))throw Error('TeleAgent 权限选项无效');
  if(type==='question'&&input.op==='question'&&(!Array.isArray(input.answers)||input.answers.length!==item.request.questions.length||input.answers.some((answer,i)=>!Array.isArray(answer)||!answer.length||answer.some(value=>typeof value!=='string'||!value.trim()||value.length>10000)||(!item.request.questions[i].multiple&&answer.length!==1)||(item.request.questions[i].custom===false&&answer.some(value=>!item.request.questions[i].options.some(option=>option.label===value))))))throw Error('TeleAgent 问答内容无效');
  item.state='submitted';
  const route='/'+type+'/'+encodeURIComponent(input.requestId)+(input.op==='question-reject'?'/reject':'/reply')+this.query;
  await this.request(route,'POST',type==='permission'?{reply:input.reply}:input.op==='question-reject'?undefined:{answers:input.answers});
 }
 async close(){
  if(this.closePromise)return this.closePromise;
  this.closePromise=(async()=>{this.closing=true;let failed=false;
   try{
    if(this.child&&!this.dead){if(this.session&&this.submitted&&!this.finished){try{await this.request('/session/'+this.session+'/abort'+this.query,'POST',undefined,1500);}catch{}}
     const killedTree=await this.killOwnedTree();
     if(!killedTree&&!this.dead){try{this.child.kill();}catch{}}
     await Promise.race([this.exited,delay(3000)]);
     if(!this.dead){try{this.child.kill();}catch{}await Promise.race([this.exited,delay(1000)]);}
     if(!this.dead)failed=true;
    }
   }catch{failed=true;}
   finally{
    if(this.loaded?.authState)this.loaded.authState.token='';this.key='';this.password='';
    try{if(this.ownedRoot&&(!this.child||this.dead)){const resolved=path.resolve(this.root),parent=path.resolve(os.tmpdir());if(resolved===this.ownedRoot&&path.dirname(resolved)===parent&&path.basename(resolved).startsWith('agent-control-teleagent-'))fs.rmSync(resolved,{recursive:true,force:true,maxRetries:3,retryDelay:100});}}
    catch{failed=true;}
   }
   this.cleanupError=failed?Error('TeleAgent 后台清理失败，任务不会自动重发'):null;return this.cleanupError;
  })();return this.closePromise;
 }
 async killOwnedTree(){
  const pid=this.child?.pid;if(process.platform!=='win32'||!Number.isSafeInteger(pid)||pid<=0||this.dead)return false;
  return new Promise(resolve=>{
   let helper,timer,settled=false;
   const finish=value=>{if(settled)return;settled=true;clearTimeout(timer);resolve(value);};
   try{
    const systemRoot=process.env.SystemRoot||process.env.WINDIR||'C:\\Windows';
    helper=spawn(path.join(systemRoot,'System32','taskkill.exe'),['/PID',String(pid),'/T','/F'],{windowsHide:true,stdio:'ignore',env:{SystemRoot:systemRoot,WINDIR:systemRoot}});
    helper.once('error',()=>finish(false));helper.once('close',code=>finish(code===0));
    timer=setTimeout(()=>{finish(false);try{helper.kill();}catch{}},2000);
   }catch{finish(false);}
  });
 }
}
module.exports={Bridge};
if(require.main===module){
 const bridge=new Bridge(event=>process.stdout.write(JSON.stringify(event)+'\n'));
 let started=false,errorReported=false;
 const report=error=>{if(errorReported)return;errorReported=true;process.exitCode=1;try{bridge.event('teleagent.error',{submitted:bridge.submitted,message:error.code&&String(error.code).startsWith('teleagent_')?'Authentication required: '+error.code:error.message});}catch{}};
 const fail=async error=>{try{if(!bridge.closing||bridge.finished||bridge.cleanupError)report(error);const cleanupError=await bridge.close();if(cleanupError)report(cleanupError);}catch{report(Error('TeleAgent 后台清理失败，任务不会自动重发'));}finally{process.stdin.destroy();}};
 const stop=async()=>{try{const cleanupError=await bridge.close();if(cleanupError)report(cleanupError);}catch{report(Error('TeleAgent 后台清理失败，任务不会自动重发'));}finally{process.stdin.destroy();}};
 const lines=readline.createInterface({input:process.stdin});
 lines.on('line',line=>{try{if(Buffer.byteLength(line)>MAX_JSON)throw Error('TeleAgent 输入超过大小限制');const input=JSON.parse(line);if(input.op==='start'){if(started)throw Error('TeleAgent 任务不能重复启动');started=true;bridge.start(input).catch(fail);}else if(input.op==='cancel')void stop();else if(['permission','question','question-reject'].includes(input.op))bridge.answer(input).catch(fail);else throw Error('TeleAgent 输入操作无效');}catch(error){void fail(error);}});
 lines.on('close',()=>{void stop();});
 for(const signal of ['SIGINT','SIGTERM','SIGHUP'])process.on(signal,()=>{void stop();});
 process.on('uncaughtException',fail);process.on('unhandledRejection',fail);
}
