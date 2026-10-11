const fs=require('node:fs'),vm=require('node:vm'),assert=require('node:assert/strict');
const source=fs.readFileSync('app/src/main/assets/desktop-chat.js','utf8');
// Exercise the same extraction function against the observed AX hierarchy.
const functions=source.slice(source.indexOf('function title('),source.indexOf('if(!bundles[request.app])'));
const context={request:{app:'Doubao'},JSON};vm.createContext(context);vm.runInContext(functions,context);
const node=(role,value='',name='',children=[])=>({role,value,name,description:'',children});
const prompt='请回复测试成功';
const doc=node('AXGroup','','doc_editor',[
 node('AXStaticText',prompt),node('AXStaticText','今天 13:31'),node('AXStaticText','测试成功'),
 node('AXButton','','朗读'),node('AXStaticText','这是推荐问题')]);
const all=[node('AXStaticText','侧栏私密历史'),doc,...doc.children];
let result=context.extract(all,prompt,[]);assert.equal(result.text,'测试成功');assert.equal(result.complete,true);assert.equal(result.sawUser,true);
assert.equal(context.extract(all,'不是这条消息',[]).text,'');
assert.equal(context.extract(all,prompt,['测试成功']).text,'测试成功');
assert.equal(context.extract([node('AXStaticText','孤立文本')],prompt,[]).text,'');
assert.equal(context.busy([node('AXButton','','停止')]),true);
assert.equal(context.busy([node('AXButton','','新对话')]),false);
context.request.app='WorkBuddy';
const link=n=>{for(const c of n.children){c.parent=n;link(c);}return n;};
const flatten=n=>[n,...n.children.flatMap(flatten)];
const work=link(node('AXGroup','','',[
 node('AXStaticText','当前任务：'),node('AXStaticText',prompt),node('AXButton','','复制'),
 node('AXGroup','','',[node('AXStaticText','WorkBuddy'),node('AXStaticText','已完成 7s'),
 node('AXGroup','','',[node('AXStaticText','测试'),node('AXStaticText','成功')]),node('AXButton','','复制'),node('AXStaticText','共消耗 3')]),
 node('AXStaticText','侧栏与项目名'),node('AXTextArea','','',[node('AXStaticText','不要匹配草稿')])]));
result=context.extract(flatten(work),'当前任务：\n'+prompt,[]);
assert.equal(result.text,'测试成功');assert.equal(result.complete,true);
work.children[3].children[1].value='已处理 3s';
assert.equal(context.extract(flatten(work),'当前任务：\n'+prompt,[]).text,'');
work.children[3].children[1].value='已完成 7s';
assert.equal(context.extract(flatten(work),'不要匹配草稿',[]).sawUser,false);
context.request.app='Doubao';
const draft=link(node('AXGroup','','doc_editor',[node('AXTextArea','','',[node('AXStaticText',prompt)])]));
assert.equal(context.extract(flatten(draft),prompt,[]).sawUser,false);
console.log('PASS native AX fixtures: multiline prompts, editor exclusion, WorkBuddy completion and fragmented reply grouping, no sidebar metadata, Doubao reply boundaries.');

context.request.app='TeleAgent';
const telePrompt='计算 33 × 73，并写 ORCA-TELE-OK';
const tele=link(node('AXGroup','','',[
 node('AXGroup','','',[node('AXGroup','','',[node('AXStaticText',telePrompt)]),node('AXGroup','','',[node('AXStaticText','2026-10-09 21:05:00'),node('AXButton','','复制')])]),
 node('AXButton','','已完成本轮思考与执行 21:05 完成 耗时 3 秒'),
 node('AXGroup','','',[node('AXStaticText','结果：'),node('AXStaticText','2409'),node('AXStaticText',' ORCA-TELE-OK'),node('AXButton','','仅复制文本'),node('AXButton','','带格式复制'),node('AXStaticText','2026-10-09 21:05:03')]),
 node('AXGroup','','',[node('AXTextArea','','',[node('AXStaticText','不要匹配草稿')])]),
]));
result=context.extract(flatten(tele),telePrompt,[]);assert.equal(result.text,'结果：2409 ORCA-TELE-OK');assert.equal(result.complete,true);assert.equal(result.sawUser,true);
assert.equal(context.extract(flatten(tele),'不要匹配草稿',[]).sawUser,false);
assert.equal(context.busy([node('AXButton','','暂停（输入"继续"可恢复）')]),true);
assert.equal(context.extract([...flatten(tele),node('AXButton','','暂停（输入"继续"可恢复）')],telePrompt,[]).complete,false);
tele.children[1].name='正在思考';assert.equal(context.extract(flatten(tele),telePrompt,[]).complete,false);tele.children[1].name='已完成本轮思考与执行';
tele.children.splice(3,0,link(node('AXButton','','已完成本轮思考与执行')),link(node('AXGroup','','',[node('AXStaticText','最终回复'),node('AXButton','','仅复制文本')])));
tele.children.forEach(c=>c.parent=tele);result=context.extract(flatten(tele),telePrompt,[]);assert.equal(result.text,'最终回复');assert.equal(result.complete,true);
tele.children.splice(5,0,link(node('AXGroup','','',[node('AXStaticText','后续其他任务'),node('AXButton','','复制')])),node('AXStaticText','其他任务回复'));
tele.children.forEach(c=>c.parent=tele);assert.equal(context.extract(flatten(tele),telePrompt,[]).text,'最终回复');
assert.equal(context.teleagentBlank([node('AXStaticText','你说我做，随时待命'),node('AXTextArea','\uFEFF\n')]),true);
assert.equal(context.teleagentBlank([node('AXStaticText','你说我做，随时待命'),node('AXTextArea','未发送的草稿')]),false);
console.log('PASS TeleAgent: final completed response, inline fragments, timestamps excluded, busy protection, draft and foreign-turn exclusion, verified blank navigation.');
// Ordinary greeting replies have a final toolbar but no execution completion heading.
const greeting=link(node('AXGroup','','',[
 node('AXGroup','','',[node('AXGroup','','',[node('AXStaticText','你好')]),node('AXGroup','','',[node('AXStaticText','2026-10-09 21:15:17'),node('AXButton','','复制')])]),
 node('AXGroup','','',[node('AXGroup','','',[node('AXStaticText','你好！有什么可以帮你的吗？')]),node('AXGroup','','',[node('AXStaticText','可以协助处理日常办公任务。')]),node('AXGroup','','',[node('AXButton','','仅复制文本'),node('AXButton','','带格式复制'),node('AXStaticText','2026-10-09 21:15:19')])]),
 node('AXButton','','任务上下文')
]));
result=context.extract(flatten(greeting),'你好',[]);
assert.equal(result.text,'你好！有什么可以帮你的吗？\n\n可以协助处理日常办公任务。');assert.equal(result.complete,true);
assert.equal(context.extract([...flatten(greeting),node('AXButton','','暂停（输入继续可恢复）')],'你好',[]).complete,false);
greeting.children[1].children[2].children[0].name='';assert.equal(context.extract(flatten(greeting),'你好',[]).complete,false);
console.log('PASS TeleAgent ordinary greeting: no execution heading, final toolbar required, busy excluded, timestamp and task context excluded.');
context.request.app='Doubao';

assert.equal(context.parseBounds('<AXValue> {value = x:12.000000 y:144.000000 type = kAXValueCGPointType}', '<AXValue> {value = w:336.000000 h:31.000000 type = kAXValueCGSizeType}').x,12);
assert.equal(context.parseBounds('x:-1920 y:12','w:300 h:30').x,-1920);
assert.equal(context.parseBounds('x:12 y:12','w:0 h:30'),null);
assert.equal(context.parseBounds('unavailable','w:300 h:30'),null);
console.log('PASS live AX coordinate parser: standard and negative display positions; invalid and zero-size controls rejected.');

context.request.app='Doubao';
const longPrompt='当前任务：\n你好\n此前对话（仅供上下文）：\nWorkBuddy：你好——我在呢。';
const longDoc=link(node('AXGroup','','doc_editor',[
 node('AXGroup','','',[node('AXStaticText','当前任务：'),node('AXStaticText','你好 此前对话（仅供上下文）：'),node('AXStaticText','WorkBuddy：你好 —— 我在呢。'),node('AXStaticText','展开全部'),node('AXStaticText','今天 15:10'),node('AXButton')]),
 node('AXGroup','','',[node('AXStaticText','你好呀，有什么我可以帮你的吗？'),node('AXButton','','朗读')])]));
let longReply=context.extract(flatten(longDoc),longPrompt,[]);
assert.equal(longReply.sawUser,true);assert.equal(longReply.text,'你好呀，有什么我可以帮你的吗？');assert.equal(longReply.complete,true);
assert.equal(context.extract(flatten(longDoc),longPrompt+'另一个任务',[]).sawUser,false);
console.log('PASS Doubao expanded long-context fixture: ignores AX whitespace splits, excludes expand controls, rejects different prompts.');

context.request.app='DeepSeek Harness';
const ds=JSON.parse(fs.readFileSync('tests/deepseek-turn.json','utf8')).map(n=>({...n,children:[]}));
ds.forEach(n=>{const index=n.parent;n.parent=index<0?null:ds[index];if(n.parent)n.parent.children.push(n);});
result=context.extract(ds,'请只回复：测试成功',[]);
assert.equal(result.text,'测试成功');assert.equal(result.complete,true);
assert.equal(context.extract(ds,'别的任务',[]).sawUser,false);
const finish=ds.find(n=>context.button(n)&&context.title(n).startsWith('已完成，用时'));
finish.name='深度求索中，用时 30秒';
assert.equal(context.extract(ds,'请只回复：测试成功',[]).complete,false);
assert.equal(context.freshEntry(node('AXButton','','新建会话')),true);
assert.equal(context.freshEntry(node('AXButton','','在新对话中分支')),false);
assert.equal(context.deepseekBlank([node('AXTextArea','','描述你想要构建的内容, / 调用指令, @ 文件或对话')]),true);
assert.equal(context.deepseekBlank([node('AXTextArea','','发消息或创建任务, / 调用指令, @ 文件或对话')]),false);
console.log('PASS DeepSeek observed turn: exact reply, completion required, mismatched prompt rejected, new-session entry and composer validated.');

// Delayed rendering and AXPress-success-without-navigation must not become send attempts.
const oldNodes=[node('AXTextArea','','发消息或创建任务, / 调用指令, @ 文件或对话'),node('AXButton','','新建会话')];
const blankNodes=[node('AXTextArea','','描述你想要构建的内容, / 调用指令, @ 文件或对话')];
let now=0,clicks=0,reads=0;
context.Date={now:()=>now};
context.$={NSRunLoop:{currentRunLoop:{runUntilDate:()=>{now+=100;}}},NSDate:{dateWithTimeIntervalSinceNow:()=>0}};
context.delay=()=>{now+=200;};context.emit=()=>{};context.fail=m=>{throw Error(m);};
context.snapshot=()=>{reads++;return reads>4?blankNodes:oldNodes;};
context.keyboardActivate=()=>{clicks++;};
assert.equal(context.confirmDeepseekNavigation({},{}),blankNodes);assert.equal(clicks,0);
now=0;clicks=0;context.snapshot=()=>clicks?blankNodes:oldNodes;
assert.equal(context.confirmDeepseekNavigation({},{}),blankNodes);assert.equal(clicks,1);
now=0;clicks=0;context.snapshot=()=>oldNodes;
assert.throws(()=>context.confirmDeepseekNavigation({},{}),/新建会话未生效/);assert.equal(clicks,1);
now=0;clicks=0;context.snapshot=()=>[...oldNodes,node('AXButton','','停止生成')];
assert.throws(()=>context.confirmDeepseekNavigation({},{}),/正在执行任务/);assert.equal(clicks,0);
console.log('PASS DeepSeek navigation: delayed readiness, inert AXPress keyboard fallback, timeout stops before typing, busy task protected.');
const focusContext={request:{app:'DeepSeek Harness'},JSON};vm.createContext(focusContext);vm.runInContext(functions,focusContext);
let focusTime=0,keys=0,allowFocus=true;
const bridge=x=>x;
bridge.AXUIElementSetAttributeValue=()=>0;
bridge.NSRunLoop={currentRunLoop:{runUntilDate:()=>{focusTime+=100;}}};
bridge.NSDate={dateWithTimeIntervalSinceNow:()=>0};
Object.assign(focusContext,{$:bridge,Date:{now:()=>focusTime},delay:()=>{focusTime+=100;},string:()=>allowFocus&&focusTime>=300?'true':'false',isFrontmost:()=>true,Application:()=>({keyCode:()=>{assert.ok(focusTime>=300);keys++;}}),fail:m=>{throw Error(m);}});
focusContext.keyboardActivate({raw:{}},{});assert.equal(keys,1);
allowFocus=false;focusTime=0;keys=0;
assert.throws(()=>focusContext.keyboardActivate({raw:{}},{}),/未确认目标按钮获得焦点/);assert.equal(keys,0);
console.log('PASS keyboard activation waits for asynchronous AX focus and never presses a key when focus is unconfirmed.');

context.application={processIdentifier:123};
context.string=(raw,key)=>key==='AXEnabled'?(raw.enabled===false?'false':'true'):'';
const question=link(node('AXGroup','','',[
 node('AXGroup','','',[node('AXStaticText','是否继续手机测试？'),node('AXButton','','收起问题卡片'),node('AXButton','','放弃整组问题')]),
 node('AXButton','','继续测试'),node('AXButton','','取消测试'),node('AXButton','','跳过'),node('AXButton','','提交')
]));
flatten(question).forEach(n=>n.raw=n);question.children.at(-1).enabled=false;
context.request.app='DeepSeek Harness';
let confirmation=context.pendingDialog(flatten(question));
assert.ok(confirmation.buttons.some(b=>b.name==='继续测试'));
assert.ok(!confirmation.buttons.some(b=>b.name==='提交'));
const previousFingerprint=confirmation.fingerprint;question.children.at(-1).enabled=true;
confirmation=context.pendingDialog(flatten(question));assert.ok(confirmation.buttons.some(b=>b.name==='提交'));assert.notEqual(confirmation.fingerprint,previousFingerprint);
assert.equal(context.pendingDialog([node('AXStaticText','允许一次，拒绝')]),null);
console.log('PASS observed DSH question hierarchy includes options outside the header; disabled submit excluded, changed request invalidates fingerprint, plain text cannot become an actionable approval.');
context.request.app='WorkBuddy';
const buddyQuestion=link(node('AXGroup','','',[
 node('AXStaticText','这是原始任务'),node('AXStaticText','已处理 12s'),node('AXButton','','复制'),node('AXButton','','全部跳过'),
 node('AXStaticText','是否继续手机测试？'),node('AXButton','','1 继续测试'),node('AXButton','','2 取消测试'),node('AXButton','','发送')
]));flatten(buddyQuestion).forEach(n=>n.raw=n);
let buddyPending=context.pendingDialog(flatten(buddyQuestion));assert.ok(buddyPending.detail.startsWith('是否继续手机测试？'));assert.ok(!buddyPending.buttons.some(b=>b.name==='复制'));
const buddyFingerprint=buddyPending.fingerprint;buddyQuestion.children[1].value='已处理 13s';assert.equal(context.pendingDialog(flatten(buddyQuestion)).fingerprint,buddyFingerprint);
console.log('PASS observed WorkBuddy flat question scope excludes task text, copy controls and live elapsed timers; unchanged approval remains usable.');
const imageCards=[node('AXButton','','完整的雪山图片.png'),node('AXButton','','分享 完整的雪山图片.png'),node('AXButton','','截断...png'),node('AXButton','','../other.png'),node('AXStaticText','伪造正文.png'),node('AXButton','','完整的雪山图片.png')];
assert.deepEqual(Array.from(context.outputFileNames(imageCards)),['完整的雪山图片.png']);
console.log('PASS original image cards: exact full filename only; ignores sharing controls, truncated paths, traversal, body text and duplicates.');

context.request.app='Doubao';const emitted=[];context.event=value=>emitted.push(value);context.attr=(raw,key)=>raw;context.ObjC={unwrap:v=>v};
context.emitReplyImages({complete:true,images:[{raw:{absoluteString:'https://p3.byteimg.com/generated.png'}},{raw:{absoluteString:'https://p3.byteimg.com/generated.png'}},{raw:{absoluteString:'https://other.test/photo.png'}}]});
assert.equal(emitted.length,1);assert.equal(emitted[0].type,'attachment');
context.emitReplyImages({complete:false,images:[{raw:{absoluteString:'https://p3.byteimg.com/in-progress.png'}}]});assert.equal(emitted.length,1);
console.log('PASS Doubao completed reply emits unique CDN image attachment, excludes unrelated hosts and incomplete output.');
assert.equal(context.dshImagePath('dsh-app://app/api/file?path=%2Ftmp%2F%E5%9B%BE%20%E7%89%87.jpg'),'/tmp/图 片.jpg');
for(const url of ['dsh-app://evil/api/file?path=%2Ftmp%2Fa.jpg','dsh-app://app/api/file?path=relative.jpg','dsh-app://app/api/file?path=%2Ftmp%2Fa.txt','dsh-app://app/api/file?path=%2Ftmp%2Fa.jpg&extra=1','dsh-app://app/api/file?path=%00.jpg'])assert.equal(context.dshImagePath(url),'');
context.request.app='DeepSeek Harness';emitted.length=0;
context.emitReplyImages({complete:true,images:[{raw:{absoluteString:'dsh-app://app/api/file?path=%2Ftmp%2Fa.jpg'}},{raw:{absoluteString:'dsh-app://app/api/file?path=%2Ftmp%2Fb.jpg'}}]});
assert.equal(emitted.length,2);assert.equal(emitted[0].path,'/tmp/a.jpg');assert.equal(emitted[0].preview,true);
console.log('PASS DSH app-local images: exact endpoint, decoded absolute paths, two distinct preview attachments.');

const ownContext={request:{app:'Doubao'},JSON};vm.createContext(ownContext);
vm.runInContext(fs.readFileSync('app/src/main/assets/desktop-session.js','utf8'),ownContext);
vm.runInContext(functions,ownContext);ownContext.fail=m=>{throw Error(m);};
const ownMarker='[Agent Control request 12345678-1234-1234-1234-123456789abc]';
const ownedPrompt='请回复测试成功\n\n'+ownMarker;
const identity=n=>{n.raw={isEqual:other=>other===n.raw};n.children.forEach(identity);return n;};
const ownedDoc=identity(link(node('AXGroup','','doc_editor',[
 node('AXGroup','','',[node('AXStaticText',ownedPrompt)]),
 node('AXGroup','','',[node('AXStaticText','自己的回复'),node('AXButton','','朗读')])
])));
const bound=ownContext.ownedView(flatten(ownedDoc),ownedPrompt,null);
assert.doesNotThrow(()=>ownContext.ownedView(flatten(ownedDoc),ownedPrompt,bound));
assert.throws(()=>ownContext.ownedView(flatten(doc),ownedPrompt,bound),/请求标识/);
ownedDoc.children.push(identity(link(node('AXGroup','','',[node('AXStaticText','电脑新消息')]))));ownedDoc.children.at(-1).parent=ownedDoc;
assert.throws(()=>ownContext.ownedView(flatten(ownedDoc),ownedPrompt,bound),/其他消息/);
ownedDoc.children.pop();const cloned=identity(link(node('AXGroup','','doc_editor',[
 node('AXGroup','','',[node('AXStaticText',ownedPrompt)]),node('AXGroup','','',[node('AXStaticText','别的分支回复')])
])));
assert.throws(()=>ownContext.ownedView(flatten(cloned),ownedPrompt,bound),/身份已变化/);
cloned.raw=ownedDoc.raw;assert.doesNotThrow(()=>ownContext.ownedView(flatten(cloned),ownedPrompt,bound),'Doubao may rebuild turn nodes within the same transcript');
console.log('PASS desktop ownership: unique request, switched conversation, appended PC message, cloned branch and changed window stop before reply delivery.');

context.request.app='WorkBuddy';const originalString=context.string;context.string=(raw,key)=>key==='AXSelected'?(raw.selected?'true':'false'):originalString(raw,key);
const beforeSelection=context.pendingDialog(flatten(buddyQuestion)).fingerprint;buddyQuestion.children[5].selected=true;assert.notEqual(context.pendingDialog(flatten(buddyQuestion)).fingerprint,beforeSelection);context.string=originalString;
console.log('PASS selecting an option changes confirmation identity even when labels and question stay unchanged.');

context.request.app='WorkBuddy';const multiple=link(node('AXGroup','','',[
 ...Array.from({length:220},()=>node('AXGroup')),
 node('AXStaticText','原始任务'),node('AXButton','','复制'),node('AXStaticText','已处理 3m'),
 node('AXStaticText','等待你的回答...'),node('AXStaticText','您想要哪种风景类型？'),
 node('AXButton','','上一题'),node('AXButton','','下一题'),node('AXStaticText','1 / 3'),node('AXButton','','全部跳过'),
 node('AXButton','','1 自然风景'),node('AXButton','','2 海滩海景'),node('AXButton','','跳过')
]));flatten(multiple).forEach(n=>n.raw=n);const multiPending=context.pendingDialog(flatten(multiple));
assert.ok(multiPending);assert.ok(multiPending.buttons.some(b=>b.name==='下一题'));assert.ok(multiPending.buttons.some(b=>b.name==='1 自然风景'));assert.ok(!multiPending.detail.includes('原始任务'));
console.log('PASS observed large WorkBuddy multi-question card: >180 AX nodes, next/previous navigation, question counter, options and skip; no transcript controls.');

for(const app of ['Doubao','DeepSeek Harness','TeleAgent']){
 context.request.app=app;
 const large=link(node('AXGroup','','',[
  ...Array.from({length:220},()=>node('AXGroup')),node('AXButton','','收起问题卡片'),
  node('AXStaticText','是否继续测试？'),node('AXButton','','继续'),node('AXButton','','提交')
 ]));flatten(large).forEach(n=>n.raw=n);
 assert.ok(context.pendingDialog(flatten(large)),app+' large question');
 assert.equal(context.requiresDesktopInput(flatten(large)),true);
 assert.equal(context.requiresDesktopInput([node('AXStaticText','等待你的回答...')]),true);
 assert.equal(context.requiresDesktopInput([node('AXStaticText','已经完成')]),false);
}
console.log('PASS Doubao, DSH and TeleAgent share large-card detection and pending-input completion guard.');

context.request.app='Doubao';context.fail=m=>{throw Error(m);};
const oldComposer=identity(link(node('AXWebArea','','',[node('AXTextArea','')]))),oldInput=oldComposer.children[0];
const replacement=identity(link(node('AXWebArea','','',[node('AXTextArea','发消息或按住空格说话...\n','',[node('AXStaticText','发消息或按住空格说话...')])])));replacement.raw=oldComposer.raw;
assert.equal(context.receivingEditor(flatten(replacement),oldInput),replacement.children[0]);
assert.equal(context.editorDraft(replacement.children[0]),'');
replacement.children[0].value='电脑未发送的新草稿';assert.equal(context.editorDraft(replacement.children[0]),'电脑未发送的新草稿');
const another=identity(link(node('AXWebArea','','',[node('AXTextArea','')])));
assert.throws(()=>context.receivingEditor(flatten(another),oldInput),/归属不明确/);
replacement.children.push(identity(link(node('AXTextArea',''))));replacement.children[1].parent=replacement;
assert.throws(()=>context.receivingEditor(flatten(replacement),oldInput),/归属不明确/);
console.log('PASS rebuilt composer rebound only to unique same web area; foreign/duplicate inputs rejected, Doubao placeholder empty and actual PC draft preserved.');
// WorkBuddy 5.7.7 live panel: the question has a trailing explanation.
context.request.app='WorkBuddy';context.application={processIdentifier:123};
context.string=(raw,key)=>key==='AXEnabled'?(raw.enabled===false?'false':'true'):'';
const reportedHeading='您想生成什么内容的图片？（这是唯一一次确认，选定后我将直接生成，不再打扰）';
const reported=link(node('AXGroup','','',[
 node('AXGroup','','',[node('AXStaticText','帮我生成一张图片，只让我确认一次交互后生成'),node('AXButton','','复制'),node('AXStaticText','WorkBuddy 预计消耗 3.56 ~ 124.71 已处理 1m29s'),node('AXStaticText','等待你的回答... 思考中 | 已消耗 3.56')]),
 node('AXStaticText',reportedHeading),node('AXButton','','上一题'),node('AXStaticText','1 / 1'),node('AXButton','','下一题'),node('AXButton','','全部跳过'),
 ...['1 可爱治愈系插画','2 风景摄影风','3 赛博朋克城市','4 简约几何壁纸'].map(label=>node('AXButton','',label,[node('AXStaticText',label.slice(0,1)),node('AXStaticText',label.slice(2))])),
 node('AXTextField','','其他（输入你的答案）'),node('AXButton','','发送'),node('AXStaticText','内容由 AI 生成，请核实重要信息')
]));flatten(reported).forEach(n=>n.raw=n);reported.children[2].enabled=false;reported.children[4].enabled=false;
const reportedCard=context.pendingDialog(flatten(reported));assert.ok(reportedCard,'visible question ending with explanation must reach mobile');
assert.ok(reportedCard.detail.startsWith(reportedHeading));
assert.deepEqual(Array.from(reportedCard.buttons.map(b=>b.name)),['全部跳过','1 可爱治愈系插画','2 风景摄影风','3 赛博朋克城市','4 简约几何壁纸','发送']);
const stableReported=reportedCard.fingerprint;reported.children[0].children[2].value='WorkBuddy 预计消耗 3.56 ~ 124.71 已处理 2m10s';assert.equal(context.pendingDialog(flatten(reported)).fingerprint,stableReported);
console.log('PASS reported WorkBuddy panel: trailing explanation, four options and submit, no transcript/copy controls, disabled navigation excluded, timer stable.');
// Produce the actual adapter event for the Android receiving-side regression.
vm.runInContext(fs.readFileSync('app/src/main/assets/desktop-session.js','utf8'),context);
const reportedPrompt='帮我生成一张图片，只让我确认一次交互后生成\n\n[Agent Control request b41fdfbc-9ce6-4a7a-b6ea-de3fc0424d31]';
reported.children[0].children[0].value=reportedPrompt;context.request.prompt=reportedPrompt;
const reportedEvents=[];context.event=e=>reportedEvents.push(e);context.dialogPublishedAt=0;
const published=context.syncOwnedDialog(flatten(reported),'');assert.ok(published);assert.equal(reportedEvents.length,1);assert.equal(reportedEvents[0].options.length,6);
context.syncOwnedDialog(flatten(reported),published);assert.equal(reportedEvents.length,1,'unchanged panel never duplicates its card');
fs.mkdirSync('test-output/confirmation-058',{recursive:true});fs.writeFileSync('test-output/confirmation-058/reported-card.json',JSON.stringify(reportedEvents[0],null,2));
console.log('PASS request-owned real adapter event created for mobile rendering, six exact controls, unchanged panel deduplicated.');
