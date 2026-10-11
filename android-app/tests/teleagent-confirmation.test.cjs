const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync('app/src/main/assets/desktop-chat.js','utf8');
let rows=JSON.parse(fs.readFileSync('tests/teleagent-question-ax.json','utf8'));
function fixture(){const root={role:'AXGroup',children:[]},nodes=rows.map(r=>({...r,raw:r,children:[]}));nodes.forEach((n,i)=>{n.parent=rows[i].parent<0?root:nodes[rows[i].parent];n.parent.children.push(n);});return [root,...nodes];}
const c={request:{app:'TeleAgent'},application:{processIdentifier:1},string:(r,k)=>k==='AXEnabled'?r.enabled:k==='AXValue'?r.checked:'',title:n=>n.name||n.description,button:n=>/AXButton|AXRadioButton/.test(n.role),text:ns=>ns.filter(n=>n.role==='AXStaticText').map(n=>n.value||n.name).filter(Boolean),descendants:n=>[n,...n.children.flatMap(c.descendants)]};
vm.createContext(c);vm.runInContext(source.slice(source.indexOf('function questionHeading'),source.indexOf('// Storage remains authoritative')),c);
let nodes=fixture(),card=c.pendingDialog(nodes);assert.ok(card);assert.equal(card.buttons.filter(n=>n.role==='AXRadioButton').length,5);assert.ok(card.detail.includes('不再反复确认'));assert.ok(!card.detail.includes('已完成本轮'));
const before=card.fingerprint;nodes.find(n=>(n.name||'').startsWith('风景插画')).raw.checked='1';assert.notEqual(c.pendingDialog(nodes).fingerprint,before,'selection must publish a fresh card');assert.ok(c.dialogOptions(c.pendingDialog(nodes)).some(option=>option.selected&&option.label.startsWith('风景插画')),'only native checked radio is marked selected');
assert.ok(card.buttons.some(n=>n.name==='暂不回答并提交（0/1 已回答）'));
for(const missing of ['跳过问答','交互式问答 · 需要确认1个问题','暂不回答并提交（0/1 已回答）']){nodes=fixture();const n=nodes.find(n=>n.name===missing||n.value===missing);n.name='';n.value='';assert.equal(c.pendingDialog(nodes),null,missing+' is required');}
nodes=fixture();const root={role:'AXGroup',children:[nodes[0],fixture()[0]]};root.children.forEach(n=>n.parent=root);assert.equal(c.pendingDialog(c.descendants(root)),null,'multiple cards must not be guessed');
c.request.app='Doubao';assert.equal(c.pendingDialog(fixture()),null,'TeleAgent marker cannot authorize another client');
console.log('PASS real TeleAgent AX card recognition, question punctuation, counted submit, changed selection, missing/ambiguous controls and client isolation');

c.request.app="TeleAgent";rows=JSON.parse(fs.readFileSync("tests/teleagent-question-selected-ax.json","utf8"));card=c.pendingDialog(fixture());assert.ok(card);assert.ok(card.buttons.some(n=>n.name==="提交全部答案（1/1 已回答）"));console.log("PASS actual selected TeleAgent radio with supplementary text input and renamed submit-all control");

const ime={role:'AXDialog',name:'',children:[{role:'AXButton',name:'',description:'com.sogou.inputmethod.sogou.pinyin',raw:{enabled:'true'},children:[]}],raw:{}};assert.ok(c.pendingDialog([...fixture(),ime,...ime.children]));console.log('PASS input-method candidate dialog cannot hide the active TeleAgent card');

rows=JSON.parse(fs.readFileSync('tests/teleagent-question-multi-ax.json','utf8'));card=c.pendingDialog(fixture());assert.ok(card);assert.ok(card.buttons.some(n=>n.name==='暂不回答，下一题（0/2 已回答）'));assert.ok(card.buttons.some(n=>n.name==='画面风格'));
for(const label of ['暂不回答，下一题（0/2 已回答）','下一题（1/2 已回答）','暂不回答并提交（1/2 已回答）','提交全部答案（2/2 已回答）'])assert.ok(c.teleagentProgressControl(label));
for(const label of ['下一题','提交全部答案（2/2 已回答）其他内容','确认图片吗？'])assert.ok(!c.teleagentProgressControl(label));
console.log('PASS observed TeleAgent two-question card, counted next/skip-next/submit and question tabs');

rows=JSON.parse(fs.readFileSync('tests/teleagent-question-text-ax.json','utf8'));card=c.pendingDialog(fixture());assert.equal(card.inputs.length,1);assert.equal(card.inputs[0].required,false);let ns=fixture();let field=ns.find(n=>n.role==='AXTextArea');field.value='来自手机的补充说明';assert.notEqual(c.pendingDialog(ns).fingerprint,card.fingerprint);let radio=field.parent;while(radio&&radio.role!=='AXRadioButton')radio=radio.parent;radio.name='其他（自定义输入）';assert.equal(c.pendingDialog(ns).inputs[0].required,true);console.log('PASS real TeleAgent selected-option text input is scoped, optional/custom required, and text changes invalidate stale card');

ns=fixture();const composer={role:'AXTextArea',value:'unrelated composer draft',name:'',description:'',raw:{enabled:'true'},children:[],parent:ns[0]};ns[0].children.push(composer);ns.push(composer);assert.equal(c.pendingDialog(ns).inputs.length,1);assert.ok(!c.pendingDialog(ns).inputs.some(f=>f.value==='unrelated composer draft'));console.log('PASS question text capability never includes the chat composer outside the card');

// Text readback alone must not acknowledge continuation.
const confirmCode=source.slice(source.indexOf('function confirmDialog('),source.indexOf('function conversationNavigation('));
for(const label of ['下一题（1/2 已回答）','提交全部答案（1/1 已回答）']){
 let clicked=0,pasted=0,acked=0;
 const initial={fingerprint:'before',inputs:[{id:'question-text-0',value:'old',node:{}}],buttons:[]};
 const refreshed={fingerprint:'after-text',inputs:initial.inputs,buttons:[{name:label}]};
 const action={request:{app:'TeleAgent',prompt:'owned',fingerprint:'before',choice:'__desktop_text__',textValues:{'question-text-0':'new'}},root:{},revealConfirmationButton:(d,n)=>n,application:{activateWithOptions(){}},Date,delay(){},extract:()=>({sawUser:true}),ownedView(){},storedSession:()=>({}),isFrontmost:()=>true,title:n=>n.name,teleagentProgressControl:c.teleagentProgressControl,fail:m=>{throw Error(m)},pasteQuestionText(){pasted++},snapshot:()=>({}),pendingDialog:()=>clicked?null:pasted?refreshed:initial,nativeClick:n=>{assert.equal(n.name,label);clicked++},emit:()=>acked++};
 vm.createContext(action);vm.runInContext(confirmCode,action);action.confirmDialog({});assert.equal(clicked,1);assert.equal(acked,1);assert.equal(pasted,1);
 pasted=0;clicked=0;acked=0;action.request.textValues={};action.pendingDialog=()=>clicked?null:refreshed;action.request.fingerprint='after-text';action.confirmDialog({});assert.equal(clicked,1);assert.equal(acked,1);
 refreshed.buttons.push({name:label});clicked=0;acked=0;assert.throws(()=>action.confirmDialog({}),/无法唯一识别/);assert.equal(clicked,0);assert.equal(acked,0);
}
console.log('PASS fill-and-continue reacquires Next/Submit after text, advances unchanged text exactly once, and rejects ambiguous progress');

const revealCode=source.slice(source.indexOf('function revealConfirmationButton('),source.indexOf('function confirmDialog('));
for(const foreign of [false,true]){
 let tick=0,scrolls=0;const old={raw:{},name:'提交全部答案（1/1 已回答）'},fresh={raw:{},name:old.name};
 const cx={request:{app:'TeleAgent'},root:{},$:Object.assign(x=>x,{AXUIElementPerformAction:(n,a)=>{assert.equal(a,'AXScrollToVisible');scrolls++;},CFCopyDescription:x=>x}),ObjC:{unwrap:x=>x},Date:{now:()=>tick*100},delay:()=>tick++,snapshot:()=>({}),ownedView(){},pendingDialog:()=>({fingerprint:foreign?'foreign':'same',buttons:[fresh]}),title:n=>n.name,attr:(n,k)=>k==='AXPosition'?'x:604 y:854':tick<2?'w:742 h:0':'w:742 h:36',parseBounds:null,fail:m=>{throw Error(m)}};
 vm.createContext(cx);vm.runInContext(source.slice(source.indexOf('function parseBounds('),source.indexOf('function nativeClick('))+revealCode,cx);
 if(foreign)assert.throws(()=>cx.revealConfirmationButton({fingerprint:'same'},old),/问答已改变/);else assert.equal(cx.revealConfirmationButton({fingerprint:'same'},old),fresh);
 assert.equal(scrolls,1);
}
console.log('PASS clipped zero-height submit scrolls into view, reacquires fresh bounds, and rejects a changed question before clicking');
