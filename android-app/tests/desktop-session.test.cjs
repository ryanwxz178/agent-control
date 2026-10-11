const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const c={JSON};vm.createContext(c);vm.runInContext(fs.readFileSync('app/src/main/assets/desktop-session.js','utf8'),c);
const s=c.DesktopSession,marker='[Agent Control request 12345678-1234-1234-1234-123456789abc]',prompt='相同问题\n\n'+marker;
const user={file:'store',session:'phone',id:'u1',order:1,role:'user',text:prompt,request:'r1'};
const answer={file:'store',session:'phone',id:'a1',order:2,role:'assistant',parent:'u1',text:'自己的回复',complete:true};
const foreign={...answer,session:'pc',id:'pc1',text:'电脑另一会话回复'};
let result=s.select([user,answer,foreign],prompt,null);assert.equal(result.text,'自己的回复');assert.equal(result.complete,true);
assert.equal(s.select([user,{...answer,parent:'other'}],prompt,null).text,'');
assert.equal(s.select([user,{...answer,complete:false}],prompt,null).complete,false);
assert.equal(s.select([user,{...answer,text:'旧快照'},{...answer,text:'新快照'}],prompt,null).text,'新快照');
assert.equal(s.select([user,{...answer,parent:'missing',request:'r1'}],prompt,null).text,'自己的回复');
assert.equal(s.select([user,{...answer,parent:'missing',request:'r2'}],prompt,null).text,'');
assert.throws(()=>s.select([user,answer,{...user,id:'pc-user',order:3,text:'电脑插入'}],prompt,result.binding),/插入了新消息/);
assert.throws(()=>s.select([user,{...user,session:'duplicate'}],prompt,result.binding),/会话记录已改变/);
assert.throws(()=>s.select([{...user,file:'other'},answer],prompt,result.binding),/身份已改变/);
assert.throws(()=>s.select([],prompt,result.binding),/会话记录已改变/);
assert.equal(s.select([{...user,text:'相同问题'}],prompt,null),null);
assert.throws(()=>s.checkDraft('电脑草稿',prompt,false),/输入框被修改/);
assert.doesNotThrow(()=>s.checkDraft('',prompt,false));assert.doesNotThrow(()=>s.checkDraft(prompt,prompt,true));
assert.throws(()=>s.marker('没有唯一标识'),/唯一身份/);assert.throws(()=>s.marker(marker+marker),/唯一身份/);
console.log('PASS stored-session ownership: exact nonce, session/parent/request IDs, partial and duplicate snapshots, foreign messages, missing/changed stores, draft interference and completion.');

assert.equal(s.select([{...user,wrapped:true,text:'<context>desktop</context>\n'+prompt+'\n<reminder>local context</reminder>'},answer],prompt,null).text,'自己的回复');
assert.equal(s.select([{...user,wrapped:true,text:prompt+marker},answer],prompt,null),null);
console.log('PASS wrapped WorkBuddy input_text: full request plus one unique nonce required; echoed/duplicated nonce rejected.');

const call={file:'store',session:'phone',id:'call-row',order:3,role:'tool_call',parent:'a1',request:'r1',callId:'call-1'};
assert.equal(s.select([user,answer,call],prompt,null).complete,false);
assert.equal(s.select([user,answer,call],prompt,null).pendingTools,1);
const toolResult={...call,id:'result-row',order:4,role:'tool_result',complete:true};
assert.equal(s.select([user,answer,call,toolResult],prompt,null).complete,false,'tool resolution still requires subsequent assistant completion');
const final={...answer,id:'final',order:5,text:'确认后实际完成'};
assert.equal(s.select([user,answer,call,toolResult,final],prompt,null).complete,true);
assert.equal(s.select([user,answer,{...call,request:'foreign',parent:'foreign'}],prompt,null).complete,true);
assert.equal(s.select([user,answer,call,{...toolResult,callId:'foreign'},final],prompt,null).complete,false);
console.log('PASS completed assistant text before AskUserQuestion is not end of turn; matching tool result and subsequent final response required, foreign results cannot resolve it.');

const imageResult={type:'function_call_result',status:'completed',output:{type:'text',text:JSON.stringify({type:'image_gen_tool_result',status:'completed',images:[{localPath:'/tmp/generated.png'},{localPath:'relative.png'},{localPath:'/tmp/../bad.png'}]})}};
const images=c.workBuddyImageAttachments(imageResult);assert.equal(images.length,1);assert.equal(images[0].preview,true);
assert.equal(c.workBuddyImageAttachments({...imageResult,status:'failed'}).length,0);
const imageTool={...toolResult,attachments:images};
assert.equal(s.select([user,answer,call,imageTool,final],prompt,null).attachments[0].path,'/tmp/generated.png');
assert.equal(s.select([user,answer,call,{...imageTool,session:'foreign'},final],prompt,null).attachments.length,0);
assert.equal(s.select([user,answer,call,{...imageTool,callId:'unmatched'},final],prompt,null).attachments.length,0);
console.log('PASS generated image tool results: request-bound completed calls, explicit safe paths, preview and foreign-call exclusion');

const recorded=JSON.parse(fs.readFileSync('tests/teleagent-image-store.json','utf8'));
const teleRows=c.teleagentSessionRows(recorded,'tele-db');const teleUser=teleRows.find(r=>r.role==='user');
const teleResult=s.select(teleRows,teleUser.text,null);assert.equal(teleResult.complete,true);assert.equal(teleResult.attachments.length,1);assert.equal(teleResult.attachments[0].path,'/Users/ryan/image-20261010093859-01-407e0cd7.jpg');assert.equal(teleResult.attachments[0].preview,true);
const running=JSON.parse(JSON.stringify(recorded));for(const r of running){let parts=JSON.parse(r.parts);parts.forEach(p=>{if(p.tool==='ImageGen'){p.state.status='running';delete p.state.time.end;}});r.parts=JSON.stringify(parts);}
assert.equal(s.select(c.teleagentSessionRows(running,'tele-db'),teleUser.text,null).complete,false,'unresolved ImageGen must block completion even with completed intermediate text');
const parts=recorded.flatMap(r=>JSON.parse(r.parts));const gen=parts.find(p=>p.tool==='ImageGen');assert.equal(c.teleagentToolAttachments({...gen,state:{...gen.state,status:'error'}}).length,0);
assert.equal(c.teleagentToolAttachments({...gen,state:{...gen.state,metadata:{filepaths:['relative.jpg','/tmp/../secret.jpg','/tmp/\u0000bad.jpg']}}}).length,0);
assert.equal(c.teleagentToolAttachments({...gen,tool:'unknown'}).length,0);assert.equal(c.teleagentToolAttachments({tool:'report_final_files',state:{status:'completed',output:JSON.stringify({items:[{resolvedPath:'/tmp/no.jpg',exists:false,displayable:true,kind:'file',reason:'ok'}]})}}).length,0);
assert.equal(s.select(teleRows.map(r=>r.role==='tool_result'?{...r,session:'foreign'}:r),teleUser.text,null).attachments.length,0);
console.log('PASS actual TeleAgent ImageGen/report_final_files results: original path, dedup, preview, running tools, unsafe paths and foreign-session exclusion');
