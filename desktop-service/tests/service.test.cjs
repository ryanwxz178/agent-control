const assert=require('node:assert/strict');
const fs=require('node:fs');const os=require('node:os');const path=require('node:path');const {spawn}=require('node:child_process');
const {test}=require('node:test');
const java=process.env.JAVA_HOME?path.join(process.env.JAVA_HOME,'bin/java'):path.resolve('.toolchain/jdk-17.0.20.1+1/Contents/Home/bin/java');
const jar=path.resolve('android-app/app/src/main/assets/agent-control-service.jar');
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function until(check,timeout=18000){const end=Date.now()+timeout;while(Date.now()<end){const value=check();if(value)return value;await sleep(80);}throw Error('test timeout');}
test('computer service keeps working without client, preserves permissions, deduplicates requests and never replays interrupted runs',async()=>{
 const dir=fs.mkdtempSync(path.join(os.tmpdir(),'agent-control-service-'));const data=path.join(dir,'data');const assets=path.join(dir,'assets');fs.mkdirSync(assets);const log=path.join(dir,'executions.jsonl');const children=path.join(dir,'children');fs.mkdirSync(children);
 const agent=path.join(dir,'agent.cjs');
 fs.writeFileSync(agent,`const fs=require('node:fs');const readline=require('node:readline');let prompt='',sandbox='',request;
 fs.writeFileSync(${JSON.stringify(children)}+'/'+process.pid,String(process.pid));
 const send=value=>process.stdout.write(JSON.stringify(value)+'\\n');
 const finish=choice=>{send({method:'item/completed',params:{threadId:'fixture',item:{id:'answer',type:'agentMessage',text:'FIXTURE_RESULT '+(choice||prompt.slice(0,60))}}});send({method:'turn/completed',params:{threadId:'fixture',turn:{status:'completed'}}});};
 readline.createInterface({input:process.stdin}).on('line',line=>{const m=JSON.parse(line);
 if(m.method==='initialize')send({id:m.id,result:{}});
 else if(m.method==='thread/start'){sandbox=m.params.sandbox;send({id:m.id,result:{thread:{id:'fixture'}}});}
 else if(m.method==='turn/start'){prompt=m.params.input[0].text;fs.appendFileSync(${JSON.stringify(log)},JSON.stringify({prompt,sandbox})+'\\n');send({id:m.id,result:{}});
 if(prompt.includes('APPROVAL'))send({id:87,method:'item/commandExecution/requestApproval',params:{threadId:'fixture',reason:'fixture review',command:'fixture-command',cwd:${JSON.stringify(dir)}}});
 else if(prompt.includes('ATTACHMENT')){const p=${JSON.stringify(path.join(dir,'result.txt'))};fs.writeFileSync(p,'ORIGINAL_ATTACHMENT_BYTES');send({method:'item/completed',params:{threadId:'fixture',item:{id:'file',type:'resource_link',uri:'file://'+p}}});finish();}
 else if(prompt.includes('DELAY'))setTimeout(()=>finish(),1600);else finish();
 }else if(m.id===87&&m.result){fs.appendFileSync(${JSON.stringify(log)},JSON.stringify({approval:m.result.decision})+'\\n');finish(m.result.decision);}
 });`);
 fs.writeFileSync(path.join(assets,'detect.sh'),`printf 'ORCA_OS|Darwin\\nORCA_HOME|${dir}\\nORCA_AGENT|codex|codex|${process.execPath}|${agent}\\nORCA_DETECTION_DONE\\n'`);
 let daemon;const errors=[];
 async function start(){daemon=spawn(java,['-Dagent.control.data='+data,'-Dagent.control.assets='+assets,'-cp',jar,'dev.orca.pocket.TaskService','serve'],{stdio:['ignore','ignore','pipe']});daemon.stderr.on('data',d=>errors.push(String(d)));await until(()=>{try{return JSON.parse(fs.readFileSync(path.join(data,'heartbeat.json'))).pid===daemon.pid;}catch{return false;}});}
 async function rpc(value){const id=crypto.randomUUID()+'.json';const response=path.join(data,'responses',id);const temp=path.join(data,'requests',id+'.tmp');fs.writeFileSync(temp,JSON.stringify(value));fs.renameSync(temp,path.join(data,'requests',id));await until(()=>fs.existsSync(response));const result=JSON.parse(fs.readFileSync(response));fs.unlinkSync(response);return result;}
 const task=id=>{try{return JSON.parse(fs.readFileSync(path.join(data,'tasks',id+'.json')));}catch{return undefined;}};
 const request=(id,prompt,conversation=id)=>({op:'submit',request:{messageId:id,prompt,conversationId:conversation,agent:'codex',directory:dir,write:false}});
 try{
  await start();const first=request('dedupe','TEXT');assert.ok(!(await rpc(first)).error);await until(()=>task('dedupe')?.status==='succeeded');assert.ok(!(await rpc(first)).error);
  assert.match((await rpc(request('dedupe','CHANGED'))).error,/不同内容/);assert.equal(fs.readFileSync(log,'utf8').trim().split('\n').length,1);
  await rpc(request('offline','DELAY'));await sleep(2100);assert.equal(task('offline').status,'succeeded');assert.ok(task('offline').events.some(e=>e.type==='update'&&e.data.text.includes('FIXTURE_RESULT')));
  await rpc(request('files','ATTACHMENT'));await until(()=>task('files')?.status==='succeeded');const attachment=task('files').events.find(e=>e.type==='attachment');assert.ok(attachment);assert.equal(fs.readFileSync(attachment.data.durablePath,'utf8'),'ORIGINAL_ATTACHMENT_BYTES');
  await rpc(request('approval','APPROVAL'));await until(()=>task('approval')?.events.some(e=>e.type==='interaction'));const permission=task('approval').events.find(e=>e.type==='interaction').data;
  assert.ok((await rpc({op:'snapshot'})).tasks.some(t=>t.id==='approval'&&t.status==='waiting_approval'));
  await rpc({op:'respond',taskId:'approval',token:permission.token,answer:{choice:'decline'}});await until(()=>task('approval')?.status==='succeeded');
  await rpc({op:'respond',taskId:'approval',token:permission.token,answer:{choice:'accept'}});await sleep(250);assert.equal(fs.readFileSync(log,'utf8').split('\n').filter(l=>l.includes('"approval"')).length,1);
  await rpc(request('blocker','DELAY','fifo'));await rpc(request('queued','NEXT','fifo'));assert.equal(task('queued').status,'queued');await rpc({op:'control',taskId:'queued',action:'pause'});assert.equal(task('queued').status,'paused');await until(()=>task('blocker')?.status==='succeeded');await rpc({op:'control',taskId:'queued',action:'resume'});await until(()=>task('queued')?.status==='succeeded');
  await rpc(request('restart','APPROVAL'));await until(()=>task('restart')?.status==='waiting_approval');const count=fs.readFileSync(log,'utf8').split('\n').length;
  daemon.kill('SIGKILL');await new Promise(r=>daemon.once('exit',r));await start();assert.equal(task('restart').status,'unverifiable');assert.equal(task('restart').events.find(e=>e.type==='interaction').data.state,'closed');await sleep(300);assert.equal(fs.readFileSync(log,'utf8').split('\n').length,count);
  assert.match((await rpc({op:'control',taskId:'restart',action:'resume'})).error,/不可自动重发/);
  assert.equal(errors.length,0,errors.join(''));console.log('Verified offline completion, duplicate/conflict protection, original attachment, persisted approval/one-shot rejection, queued pause/resume, restart uncertainty and no replay');
 }finally{daemon?.kill('SIGTERM');for(const id of fs.readdirSync(children)){try{process.kill(Number(id),'SIGTERM');}catch{}}}
});
