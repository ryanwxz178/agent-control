const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync('app/src/main/assets/desktop-chat.js','utf8');
const helpers=source.slice(source.indexOf('var dialogPublishedAt='),source.indexOf('function confirmDialog'));
const events=[];let view={owned:true,dialog:{fingerprint:'question-1',detail:'图片类型？',buttons:[{name:'艺术插画'},{name:'发送'}]}};
const c={checkpointDesktopConversation:()=>{},request:{prompt:'tagged request'},root:{},event:e=>events.push(e),title:n=>n.name,
 ownedView:n=>{if(!n.owned)throw Error('foreign view');},pendingDialog:n=>n.dialog,snapshot:()=>view};
vm.createContext(c);vm.runInContext(helpers,c);
let last=c.pollStoredDialog('');assert.equal(last,'question-1');assert.equal(events[0].type,'desktop.interaction');
assert.equal(c.pollStoredDialog(last),last);assert.equal(events.length,1);
c.dialogPublishedAt=Date.now()-120001;assert.equal(c.pollStoredDialog(last),last);assert.equal(events.length,2);events.pop();
view={owned:false,dialog:view.dialog};last=c.pollStoredDialog(last);assert.equal(last,'');assert.equal(events.at(-1).type,'desktop.interaction.closed');
c.pollStoredDialog(last);assert.equal(events.length,2);
view={owned:true,dialog:{fingerprint:'question-2',detail:'新问题',buttons:[{name:'提交'}]}};
last=c.pollStoredDialog(last);assert.equal(last,'question-2');
view.dialog=null;last=c.pollStoredDialog(last);assert.equal(last,'');assert.equal(events.at(-1).type,'desktop.interaction.closed');
// Execute the actual receive loop: pending structured store MUST still publish UI cards.
const receive=source.slice(source.indexOf("var last='',stable="),source.lastIndexOf('\n}'));
let tick=0;events.length=0;view={owned:true,dialog:{fingerprint:'question-live',detail:'图片类型？',buttons:[{name:'艺术插画'}]}};
c.storedCompletionAllowed=()=>!view.owned||!view.dialog;
Object.assign(c,{Date:{now:()=>tick*500},delay:()=>{tick++;if(tick===6)view.dialog=null;},ready:[{raw:{}}],emit:(type,text)=>events.push({type,text}),fail:m=>{throw Error(m);},storedSession:()=>({binding:{session:'own'},complete:tick>=2,text:tick>=2?'own reply':''})});
vm.runInContext(receive,c);
assert.equal(events.filter(e=>e.type==='desktop.interaction').length,1);
assert.equal(events.filter(e=>e.type==='status').length,1);
assert.equal(events.at(-2).type,'desktop.interaction.closed');assert.equal(events.at(-1).text,'own reply');
// Switching UI must close cards without stopping the original store response.
assert.ok(tick>=11,'intermediate completed message must wait until question resolves');
tick=0;events.length=0;view={owned:true,dialog:{fingerprint:'question-live',detail:'图片类型？',buttons:[{name:'艺术插画'}]}};c.delay=()=>{tick++;if(tick===2)view={owned:false,dialog:view.dialog};};
vm.runInContext(receive,c);assert.equal(events.filter(e=>e.type==='desktop.interaction').length,1);
assert.equal(events.filter(e=>e.type==='desktop.interaction.closed').length,1);assert.equal(events.at(-1).text,'own reply');
console.log('PASS actual structured receive loop publishes, deduplicates and closes owned confirmations; foreign UI never becomes a card and bound store reply continues.');

tick=0;events.length=0;view={owned:true,dialog:null};c.delay=()=>tick++;c.storedSession=()=>({binding:{session:'own'},complete:true,text:'image reply',attachments:[{path:'/tmp/generated.png',preview:true}]});
vm.runInContext(receive,c);assert.equal(events.find(e=>e.type==='attachment').preview,true);assert.equal(events.at(-1).type,'desktop.done');
console.log('PASS structured completion emits generated image attachment with auto-preview before final reply');
