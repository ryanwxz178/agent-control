const assert=require('node:assert/strict'),path=require('node:path');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
 try{
  const page=await browser.newPage({viewport:{width:390,height:700}}),errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  await page.addInitScript(()=>{window.calls=[];window.Pocket=new Proxy({}, {get:(_,name)=>(...args)=>calls.push([name,...args])});});
  const assets=path.resolve(process.env.CHAT_ASSET_DIR||'app/src/main/assets');
  await page.route('http://scroll-preview.test/**',route=>route.fulfill({path:path.join(assets,new URL(route.request().url()).pathname.slice(1))}));
  await page.goto('http://scroll-preview.test/index.html');
  await page.evaluate(()=>{
   receive('connected',{profile:{host:'scroll-test',user:'test',port:22,taskService:true,accessMode:'cli'},agents:[{id:'codex',name:'Codex',kind:'codex'},{id:'teleagent',name:'TeleAgent',kind:'desktop'}]});
   state.selected='codex';receive('serviceReady',{value:true});
   window.scrollTasks=Array.from({length:14},(_,i)=>({id:'task-'+i,status:'succeeded',createdAt:Date.now()+i,request:{conversationId:state.conversationId,agent:'codex',prompt:'历史消息 '+i},events:[{seq:1,type:'run',data:{id:'reply-'+i,agent:'Codex',label:'回复'}},{seq:2,type:'update',data:{id:'reply-'+i,status:'已完成',text:Array.from({length:8},(_,j)=>'历史段落 '+i+' / '+j+'：保留正在阅读的位置。').join('\n\n')}}]}));
   receive('serviceSnapshot',{tasks:scrollTasks});
  });
  const position=()=>page.evaluate(()=>{const chat=$('chat'),top=chat.getBoundingClientRect().top,node=Array.from($('messages').children).find(n=>n.getBoundingClientRect().bottom>top);return {top:chat.scrollTop,gap:chat.scrollHeight-chat.clientHeight-chat.scrollTop,id:node?.dataset.id,offset:node?node.getBoundingClientRect().top-top:0};});
  const sameReading=async(before,label)=>{const after=await position();assert.equal(after.id,before.id,label+' preserves the visible message');assert.ok(Math.abs(after.offset-before.offset)<=2,label+' preserves its position');assert.ok(after.gap>100,label+' does not jump to the end');};
  assert.ok((await position()).gap<=2,'initial conversation loads at the latest message');
  await page.locator('#chat').evaluate(chat=>chat.scrollTop=chat.scrollHeight/3);await page.waitForTimeout(50);
  let before=await position();
  for(let i=0;i<8;i++)await page.evaluate(()=>receive('serviceSnapshot',{tasks:scrollTasks}));
  await sameReading(before,'unchanged polling');
  await page.evaluate(()=>{scrollTasks.push({id:'remote-new',status:'running',createdAt:Date.now()+100,request:{conversationId:state.conversationId,agent:'codex',prompt:'从电脑同步的新消息'},events:[{seq:1,type:'run',data:{id:'remote-reply',agent:'Codex',label:'回复'}},{seq:2,type:'update',data:{id:'remote-reply',status:'正在回复',text:'新回复开始。'}}]});receive('serviceSnapshot',{tasks:scrollTasks});});
  await sameReading(before,'snapshot with newly added messages');
  await page.evaluate(()=>{scrollTasks[0].events.push({seq:3,type:'update',data:{id:'reply-0',status:'已完成',text:'上方消息新增内容。\n\n'.repeat(40)}});receive('serviceSnapshot',{tasks:scrollTasks});});
  await sameReading(before,'reply growth above the viewport');
  await page.evaluate(()=>{receive('run',{id:'confirmation-reply',agent:'TeleAgent',label:'回复'});receive('interaction',{id:'confirmation-reply',token:'scroll-confirm',desktop:true,state:'pending',expiresAt:Date.now()+180000,title:'选择一个选项',options:[{id:'下一题（0/1 已回答）',label:'下一题（0/1 已回答）'}]});});
  await page.waitForTimeout(80);await sameReading(before,'new confirmation card');
  await page.evaluate(()=>receive('interactionState',{id:'confirmation-reply',token:'scroll-confirm',state:'closed',message:'确认已结束'}));
  await page.evaluate(()=>render());await sameReading(before,'full conversation redraw');
  await page.locator('#jumpLatest').click();assert.ok((await position()).gap<=2,'latest button explicitly resumes following');
  await page.evaluate(()=>receive('update',{id:'remote-reply',status:'正在回复',text:'流式内容。\n\n'.repeat(30)}));
  assert.ok((await position()).gap<=2,'streaming follows when already at the end');
  let releaseImage,requested;
  const imageRequested=new Promise(resolve=>requested=resolve);
  await page.route('http://scroll-preview.test/image.png',route=>{releaseImage=()=>route.fulfill({contentType:'image/png',body:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==','base64')});requested();});
  await page.evaluate(()=>{receive('attachment',{id:'remote-reply',attachmentId:'late-image',name:'image.png',path:'C:/image.png'});receive('attachmentReady',{attachmentId:'late-image',name:'image.png',size:82,preview:'http://scroll-preview.test/image.png'});});
  let imageTimeout;
  try{await Promise.race([imageRequested,new Promise((_,reject)=>imageTimeout=setTimeout(()=>reject(Error('preview request was not observed')),5000))]);}finally{clearTimeout(imageTimeout);}
  await page.locator('#chat').evaluate(chat=>chat.scrollTop=chat.scrollHeight/2);await page.waitForTimeout(50);before=await position();
  await releaseImage();await page.waitForFunction(()=>document.querySelector('.attachment-thumbnail')?.complete);await page.waitForTimeout(80);
  await sameReading(before,'delayed image load after scrolling upward');
  for(const following of [true,false]){
   if(following)await page.locator('#jumpLatest').click();
   const imageName=following?'browser-follow.png':'browser-reading.png',routes=[];
   let observed,imageDeadline;
   const browserImageRequested=new Promise(resolve=>observed=resolve);
   await page.route('http://scroll-preview.test/'+imageName,route=>{routes.push(route);observed();});
   await page.evaluate(imageName=>{const message=state.messages.find(m=>m.id==='remote-reply');message.browsers=[{sessionId:imageName,url:'https://example.test',title:'延迟网页预览',preview:'http://scroll-preview.test/'+imageName}];renderExtras(findNode(message.id),message);},imageName);
   try{await Promise.race([browserImageRequested,new Promise((_,reject)=>imageDeadline=setTimeout(()=>reject(Error('browser preview request was not observed')),5000))]);}finally{clearTimeout(imageDeadline);}
   if(!following){await page.locator('#chat').evaluate(chat=>chat.scrollTop=chat.scrollHeight/2);await page.waitForTimeout(50);}
   before=await position();
   await Promise.all(routes.map(route=>route.fulfill({contentType:'image/png',body:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==','base64')})));
   await page.waitForFunction(()=>document.querySelector('.browser-tool-card img')?.complete);await page.waitForTimeout(80);
   if(following){
    assert.ok((await position()).gap<=2,'delayed browser preview keeps the list at the end');
    await page.evaluate(()=>receive('update',{id:'remote-reply',status:'正在回复',text:'预览加载后的流式内容。\n\n'.repeat(40)}));
    assert.ok((await position()).gap<=2,'streaming still follows after browser preview growth');
   }else await sameReading(before,'delayed browser preview while reading history');
  }
  await page.evaluate(()=>{scrollTasks.at(-1).status='succeeded';receive('serviceSnapshot',{tasks:scrollTasks});museQueue.resume();});
  await page.locator('#input').fill('主动发送后查看最新消息');await page.locator('#send').click();
  assert.ok((await position()).gap<=2,'sending a message explicitly moves to the latest message');
  assert.ok(await page.evaluate(()=>calls.some(call=>call[0]==='send')),'send reaches the mocked native bridge');
  await page.evaluate(()=>{receive('connected',{profile:{...state.profile,taskService:false},agents:state.agents});receive('busy',{value:false});window.previousScrollConversation=state.conversationId;startConversation();});
  assert.equal((await position()).top,0,'new empty conversation starts at the top');
  await page.evaluate(()=>switchConversation(previousScrollConversation));
  assert.ok((await position()).gap<=2,'switching conversations opens the latest message');
  await page.reload();
  await page.evaluate(()=>{
   receive('connected',{profile:{host:'scroll-test',user:'test',port:22,taskService:false,accessMode:'desktop'},agents:[{id:'teleagent',name:'TeleAgent',kind:'desktop'}]});
   window.scrollQuestion=(id,token)=>{receive('run',{id,agent:'TeleAgent',label:'回复'});receive('interaction',{id,token,desktop:true,state:'pending',expiresAt:Date.now()+180000,title:'等待回答',options:[{id:'下一题（0/1 已回答）',label:'下一题（0/1 已回答）'}]});};
   scrollQuestion('destination-question','destination-token');
   for(let i=0;i<30;i++)add({id:'destination-tail-'+i,role:'assistant',agent:'TeleAgent',label:'回复',status:'已完成',text:'目标会话较新的消息 '+i+'。\n\n'.repeat(3)});
   scrollEnd();window.scrollRaceDestinationId=state.conversationId;
  });
  await page.waitForTimeout(50);
  await page.evaluate(()=>{
   switchConversation('scroll-race-source');window.scrollFrames=[];window.savedScrollFrame=requestAnimationFrame;window.requestAnimationFrame=callback=>{scrollFrames.push(callback);return scrollFrames.length;};
   scrollQuestion('source-question','source-token');
   switchConversation(scrollRaceDestinationId);
  });
  before=await position();assert.ok(before.gap<=2,'destination conversation initially opens at the end');
  assert.ok(await page.evaluate(()=>scrollFrames.length>0),'source conversation has a queued reveal callback');
  await page.evaluate(()=>{for(const callback of scrollFrames.splice(0))callback(performance.now());window.requestAnimationFrame=savedScrollFrame;});
  assert.ok((await position()).gap<=2,'old conversation callback cannot reveal a question in another conversation');
  await page.evaluate(()=>{
   window.scrollFrames=[];window.savedScrollFrame=requestAnimationFrame;window.requestAnimationFrame=callback=>{scrollFrames.push(callback);return scrollFrames.length;};
   scrollQuestion('closed-question','closed-token');receive('interactionState',{id:'closed-question',token:'closed-token',state:'closed'});
  });
  await page.evaluate(()=>{for(const callback of scrollFrames.splice(0))callback(performance.now());window.requestAnimationFrame=savedScrollFrame;});
  assert.ok((await position()).gap<=2,'closed question callback cannot reveal an earlier pending question');
  assert.deepEqual(errors,[]);
  console.log('PASS chat scrolling: polling, replayed messages, growth above the viewport, confirmations, redraws and delayed images preserve reading; latest button, streaming, sending and conversation switches follow appropriately.');
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exit(1);});
