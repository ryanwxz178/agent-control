// Explicit live test: requires an already-running owned service and browser worker.
const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),assert=require('node:assert/strict');
const root=path.join(os.homedir(),'.agent-control/data'),id=process.env.AGENT_CONTROL_EXISTING_TASK||'browser-'+crypto.randomUUID();
const sleep=ms=>new Promise(r=>setTimeout(r,ms));
async function rpc(value){const file=crypto.randomUUID()+'.json',request=path.join(root,'requests',file),response=path.join(root,'responses',file);fs.writeFileSync(request+'.tmp',JSON.stringify(value),{mode:0o600});fs.renameSync(request+'.tmp',request);for(let n=0;n<600;n++){if(fs.existsSync(response)){const result=JSON.parse(fs.readFileSync(response));fs.unlinkSync(response);if(result.error)throw Error(result.error);return result;}await sleep(100);}throw Error('IPC timeout');}
(async()=>{
const request={messageId:id,conversationId:id,agent:'codex',directory:path.resolve('.'),write:false,prompt:'请只调用 agent_control_browser 的 read_public_page 工具读取 http://example.com 。不要使用 shell、其他联网工具或修改文件。根据该工具实际返回的页面标题和文字，回复 AGENT_CONTROL_BROWSER_OK 加上标题。'};
if(!process.env.AGENT_CONTROL_EXISTING_TASK)await rpc({op:'submit',request});
for(let n=0;n<900;n++){
 const task=JSON.parse(fs.readFileSync(path.join(root,'tasks',id+'.json')));
 if(['failed','unverifiable','waiting_approval','waiting_input'].includes(task.status))throw Error('Live task '+task.status+' '+(task.error||''));
 if(task.status==='succeeded'){
  const card=task.events.find(e=>e.type==='browser');assert.ok(card,'real tool must create a browser card');assert.match(card.data.title,/Example Domain/);assert.match(card.data.text,/documentation examples/);assert.ok(card.data.preview.startsWith('data:image/'));assert.ok(task.events.some(e=>e.type==='agentSession'),'native session ID must be recorded');assert.ok(task.events.some(e=>e.type==='update'&&e.data.status==='已完成'&&e.data.text.includes('AGENT_CONTROL_BROWSER_OK')));
  const read=await rpc({op:'browserRead',sessionId:card.data.sessionId});assert.ok(read.browser.preview);await rpc({op:'browserInput',sessionId:card.data.sessionId,input:{type:'key',key:'Escape'}});console.log('PASS live Codex MCP: actual webpage text, screenshot card, native session ID, completed response and post-task browser takeover');return;
 }await sleep(200);
}throw Error('Live Codex task timed out; execution state must be checked before retrying');
})().catch(error=>{console.error(error.message);process.exit(1);});
