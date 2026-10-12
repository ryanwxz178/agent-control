const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync('app/src/main/assets/desktop-chat.js','utf8');
const functions=source.slice(source.indexOf('function title('),source.indexOf('if(!bundles[request.app])'));
const session=fs.readFileSync('app/src/main/assets/desktop-session.js','utf8');
const first={path:'/Users/test/project/.agent-control-input/12345678-1234-1234-1234-123456789abc-报告.pdf',name:'报告.pdf',size:10,mime:'application/pdf'};
const second={path:'/Users/test/project/.agent-control-input/22345678-1234-1234-1234-123456789abc-table.csv',name:'table.csv',size:12,mime:'text/csv'};
function node(role,name='',children=[],value=''){
 const n={role,name,description:'',value,children,parent:null};
 n.raw={node:n,isEqual(other){return this===other;},enabled:true,selected:false,focused:false};return n;
}
function link(n){for(const c of n.children){c.parent=n;link(c);}return n;}
function flatten(n){return [n,...n.children.flatMap(flatten)];}
function context(){const ctx={request:{app:'WorkBuddy'},JSON,fail(message){throw Error(message);}};vm.createContext(ctx);vm.runInContext(session+'\n'+functions,ctx);return ctx;}
const pure=context();
assert.equal(pure.macInputFiles([first,second]).length,2);
for(const invalid of [null,{}, {...first,path:'../report.pdf'}, {...first,path:'/Users/test/.agent-control-input/not-native.pdf'}, {...first,path:first.path.replace('/project/','/project/../')}, {...first,path:first.path+'\n'}, {...first,size:NaN}, {...first,size:20*1024*1024+1}]){
 if(invalid===null)continue;
 assert.throws(()=>pure.macInputFiles([invalid]),/附件路径无效|附件大小无效/);
}
assert.throws(()=>pure.macInputFiles([first,first]),/附件路径无效/);
assert.throws(()=>pure.macInputFiles(Array.from({length:5},()=>first)),/附件数量无效/);
assert.throws(()=>pure.macInputFiles([first,second,{...second,path:second.path.replace('22345678','32345678')}].map(f=>({...f,size:20*1024*1024}))),/40MB/);
assert.equal(pure.macInputFiles(undefined).length,0);

function scenario(options={}){
 const ctx=context(),actions=[];let now=0,front=true,fileIndex=0,currentFile=null,dialog=null,goSheet=null;
 const input=node('AXTextArea','',[],options.draft||''),entry=node('AXButton','Add files');
 const composer=node('AXGroup','',[input,entry]),web=node('AXWebArea','',[composer]),window=link(node('AXWindow','WorkBuddy',[web]));
 const cards=()=>composer.children.filter(n=>n!==input&&n!==entry);
 function addCard(file){
  const card=link(node('AXGroup','',[node('AXStaticText',file.path.split('/').pop()),node('AXButton','Remove attachment')]));
  const parent=options.inline?input:composer;card.parent=parent;parent.children.push(card);
  if(options.progress){const progress=node('AXProgressIndicator');progress.parent=card;card.children.push(progress);}
  if(options.rejected){const error=node('AXStaticText','上传失败');error.parent=card;card.children.push(error);}
 }
 if(options.existing)addCard(first);
 if(options.unsupported)entry.name='';
 if(options.duplicate){const duplicate=node('AXButton','上传文件');duplicate.parent=composer;composer.children.push(duplicate);}
 if(options.transcript){const transcript=node('AXGroup','',[node('AXButton','复制'),node('AXStaticText','[Agent Control request 12345678-1234-1234-1234-123456789abc]')]);transcript.parent=web;web.children.push(transcript);composer.children.splice(composer.children.indexOf(entry),1);entry.parent=web;web.children.push(entry);}
 function attachDialog(){
  currentFile=options.files?.[fileIndex]||[first,second][fileIndex];
  const browser=node('AXBrowser'),open=node('AXButton','Open');
  dialog=link(node('AXSheet','Open',[browser,open]));dialog.parent=window;window.children.push(dialog);
 }
 const native=x=>x;
 native.NSFileType='type';native.NSFileTypeRegular='regular';native.NSFileSize='size';
 native.NSFileManager={defaultManager:{attributesOfItemAtPathError:path=>({objectForKey:key=>key==='type'?(options.notRegular?'directory':'regular'):options.changedSize?999:(options.files||[first,second]).find(f=>f.path===path).size})}};
 native.AXUIElementSetAttributeValue=(raw,key,value)=>{
  actions.push(['set',key,value]);if(key==='AXFocused'){raw.focused=!options.noFocus;}if(key==='AXValue')raw.node.value=value;return 0;
 };
 native.AXUIElementPerformAction=(raw,action)=>{
  actions.push(['press',raw.node.name]);assert.equal(action,'AXPress');
  if(raw===entry.raw){attachDialog();if(options.frontChanges)front=false;return 0;}
  if(raw.node.name==='Go'){
   assert.equal(goSheet.children[0].value,currentFile.path);
   dialog.children.splice(dialog.children.indexOf(goSheet),1);goSheet=null;
   const selected=link(node('AXRow','',[node('AXStaticText',options.wrongSelected?'wrong-file.pdf':currentFile.path.split('/').pop())]));selected.raw.selected=true;selected.parent=dialog.children[0];dialog.children[0].children=[selected];
   return 0;
  }
  if(raw.node.name==='Open'){
   window.children.splice(window.children.indexOf(dialog),1);dialog=null;
   if(!options.noChip)addCard(currentFile);
   if(options.extraCard)addCard({...second,path:second.path.replace('22345678','42345678')});
   if(options.draftChanges)input.value='电脑的新草稿';
   fileIndex++;return 0;
  }
  throw Error('Unexpected native action: '+raw.node.name);
 };
 Object.assign(ctx,{$:native,ObjC:{unwrap:x=>x},application:{},Date:{now:()=>now},delay:()=>{now+=200;},snapshot:()=>flatten(window),isFrontmost:()=>front,emit:()=>{},string:(raw,key)=>key==='AXValue'?raw.node.value:key==='AXFocused'?String(raw.focused):key==='AXSelected'?String(raw.selected):key==='AXEnabled'?String(raw.enabled):'',Application:()=>({keystroke:(key,flags)=>{
  actions.push(['key',key]);assert.equal(key,'g');assert.deepEqual(Array.from(flags.using),['command down','shift down']);assert.ok(dialog);
  goSheet=link(node('AXSheet','Go to Folder',[node('AXTextField'),node('AXButton','Go')]));goSheet.parent=dialog;dialog.children.push(goSheet);
 }})});
 return {ctx,actions,input,window,composer,cards,files:ctx.macInputFiles(options.files||[first,second])};
}
let good=scenario();const resultingInput=good.ctx.attachMacInputFiles({},good.input,good.files);
assert.equal(resultingInput,good.input);assert.equal(good.cards().length,2);
assert.deepEqual(good.actions.filter(a=>a[0]==='press').map(a=>a[1]),['Add files','Go','Open','Add files','Go','Open']);
assert.deepEqual(good.actions.filter(a=>a[1]==='AXValue').map(a=>a[2]),[first.path,second.path]);
assert.equal(good.ctx.macAttachmentState(good.ctx.macComposerFileScope(good.input),good.files),'ready');
good.ctx.verifyMacInputFiles(flatten(good.window),good.input,good.files);
assert.equal(good.actions.some(a=>a[1]==='Send'),false,'file picker orchestration must never send a message');

for(const [options,error,presses] of [
 [{unsupported:true},/未提供可识别/,0],
 [{duplicate:true},/入口不唯一/,0],
 [{transcript:true},/未提供可识别/,0],
 [{existing:true},/已有附件草稿/,0],
 [{draft:'不能覆盖电脑草稿'},/输入框被修改/,0],
 [{changedSize:true},/文件已改变/,0],
 [{notRegular:true},/文件已改变/,0],
 [{frontChanges:true},/焦点已改变/,1],
 [{noFocus:true},/无法确认焦点/,1],
 [{wrongSelected:true},/唯一文件/,2],
 [{noChip:true},/准备完成/,3],
 [{inline:true},/准备完成/,3],
 [{extraCard:true},/出现了其他文件/,3],
 [{progress:true},/准备完成/,3],
 [{rejected:true},/拒绝了附件/,3],
 [{draftChanges:true},/输入框被修改/,3],
 ]){
 const s=scenario(options);assert.throws(()=>s.ctx.attachMacInputFiles({},s.input,s.files),error,JSON.stringify(options));
 assert.equal(s.actions.filter(a=>a[0]==='press').length,presses,JSON.stringify(options));
 assert.equal(s.actions.some(a=>a[1]==='Send'),false);
 if(options.wrongSelected)assert.ok(!s.actions.some(a=>a[1]==='Open'),'wrong selected file must never be opened/uploaded');
}

good=scenario({files:[first]});good.ctx.attachMacInputFiles({},good.input,good.files);
const composer=good.ctx.macComposerFileScope(good.input);
composer.parts.push(node('AXStaticText','正在上传'));
assert.equal(good.ctx.macAttachmentState(composer,good.files),'pending');
// Exact native basename and a dedicated remove control are both necessary.
good.cards()[0].children[0].name='报告.pdf';
assert.throws(()=>good.ctx.verifyMacInputFiles(flatten(good.window),good.input,good.files),/附件校验失败/);
good.cards()[0].children[0].name=first.path.split('/').pop();good.cards()[0].children[1].name='';
assert.throws(()=>good.ctx.verifyMacInputFiles(flatten(good.window),good.input,good.files),/附件校验失败/);

const textOnly=scenario({unsupported:true});assert.equal(textOnly.ctx.attachMacInputFiles({},textOnly.input,[]),textOnly.input);assert.equal(textOnly.actions.length,0);
const draftAttachments=scenario({existing:true});assert.throws(()=>draftAttachments.ctx.attachMacInputFiles({},draftAttachments.input,[]),/已有附件草稿/);assert.equal(draftAttachments.actions.length,0);
assert.throws(()=>draftAttachments.ctx.verifyMacInputFiles(flatten(draftAttachments.window),draftAttachments.input,[]),/出现附件草稿/);
const gate=source.slice(source.indexOf('// Paste real input'),source.indexOf("emit('status','正在确认电脑"));
assert.ok(source.indexOf('input=attachMacInputFiles(root,input,inputFiles)')<source.indexOf('// Paste real input'));
assert.ok(gate.indexOf('verifyMacInputFiles(ready,input,inputFiles)')<gate.indexOf('writeDesktopConversation(conversationPath,conversationState)'));
assert.ok(gate.indexOf('verifyMacInputFiles(ready,input,inputFiles)')<gate.indexOf('if(sendButtons.length===1)'));
console.log('PASS macOS desktop files: same-app native picker and exact path input, distinct ready cards, final send gate, limits, file mutation, foreground/draft protection, unsupported/ambiguous controls, wrong selection, upload failure/progress and missing chips block send. Mocked AX fixtures; no macOS device validation.');
