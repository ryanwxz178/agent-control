const assert=require('node:assert/strict'),path=require('node:path'),fs=require('node:fs');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
 try{
  const page=await browser.newPage({viewport:{width:390,height:700}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.addInitScript(()=>{window.calls=[];window.Pocket=new Proxy({}, {get:(_,name)=>(...args)=>calls.push([name,...args.map(a=>{try{return JSON.parse(a);}catch{return a;}})])});});
  await page.goto('file://'+path.resolve('app/src/main/assets/index.html'));
  await page.evaluate(()=>{receive('connected',{profile:{host:'test',directory:'D:/project',taskService:false,accessMode:'cli'},agents:[{id:'codex',name:'Codex',kind:'codex'}]});state.selected='codex';render();});
  await page.locator('#attachFile').click();assert.deepEqual(await page.evaluate(()=>calls.at(-1)),['pickFiles',4]);
  await page.evaluate(()=>receive('inputFilesBusy',{value:true,message:'正在上传：报告.pdf'}));assert.equal(await page.locator('#send').isDisabled(),true);
  const old=await page.evaluate(()=>state.conversationId);await page.locator('#newChat').click();assert.equal(await page.evaluate(()=>state.conversationId),old,'upload stays bound to the conversation');
  await page.evaluate(()=>{receive('inputFileReady',{id:'file-1',name:'报告.pdf',size:2048,mime:'application/pdf'});receive('inputFileReady',{id:'file-2',name:'截图.png',size:4096,mime:'image/png'});receive('inputFilesBusy',{value:false});});
  assert.equal(await page.locator('.input-file').count(),2);assert.equal(await page.locator('#send').isDisabled(),false,'files can be sent without text');
  await page.getByRole('button',{name:'移除 截图.png'}).click();assert.deepEqual(await page.evaluate(()=>calls.at(-1)),['removeInputFile','file-2']);
  await page.locator('#input').fill('请总结文件');await page.locator('#send').click();
  const sent=await page.evaluate(()=>calls.filter(c=>c[0]==='send').at(-1)[1]);assert.deepEqual(sent.uploads,['file-1']);assert.equal(sent.prompt,'请总结文件');assert.equal(await page.locator('.sent-files').textContent(),'报告.pdf · 2 KB');
  await page.evaluate(sent=>{receive('rejected',{messageId:sent.messageId,prompt:sent.prompt,uploads:sent.uploads});receive('busy',{value:false});},sent);
  assert.equal(await page.locator('#input').inputValue(),'请总结文件');assert.equal(await page.locator('.input-file').count(),1,'rejected message restores its file');
  await page.evaluate(()=>{state.draftUploads=[];drawInputFiles();$('input').value='';syncComposer();receive('busy',{value:true});receive('inputFileReady',{id:'queued-file',name:'数据.csv',size:30,mime:'text/csv'});});
  await page.locator('#send').click();assert.equal(await page.locator('#queuePanel').isVisible(),true);assert.ok((await page.locator('#queuePanel').textContent()).includes('数据.csv'));
  assert.equal(await page.evaluate(()=>museQueue.getSnapshot().pending[0].files[0].id),'queued-file');
  await page.getByRole('button',{name:'编辑',exact:true}).click();assert.equal(await page.locator('.input-file').count(),1,'queue editing restores files');
  await page.evaluate(()=>{receive('busy',{value:false});state.profile.accessMode='desktop';});await page.locator('#send').click();assert.equal(await page.locator('.input-file').count(),1,'unsupported desktop mode keeps files in draft');
  await page.evaluate(()=>{state.profile.accessMode='cli';state.draftUploads=[{id:'one',name:'1.pdf',size:20*1024*1024},{id:'two',name:'2.pdf',size:20*1024*1024},{id:'three',name:'3.pdf',size:1}];drawInputFiles();});
  const count=await page.evaluate(()=>calls.filter(c=>c[0]==='send').length);await page.locator('#send').click();assert.equal(await page.evaluate(()=>calls.filter(c=>c[0]==='send').length),count);
  await page.evaluate(()=>{state.draftUploads=[{id:'safe',name:'<img onerror=alert(1)>.txt',size:100}];drawInputFiles();});assert.equal(await page.locator('.input-file img').count(),1,'only the trusted remove icon is an image');
  for(const width of [320,390,440]){await page.setViewportSize({width,height:640});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);}
  await page.setViewportSize({width:320,height:400});await page.evaluate(()=>{state.draftUploads=Array.from({length:4},(_,i)=>({id:'keyboard-'+i,name:'文件 '+i+'.pdf',size:100}));drawInputFiles();});
  assert.ok(await page.locator('#send').evaluate(button=>button.getBoundingClientRect().bottom<=innerHeight),'multiple files keep send visible above the keyboard');
  await page.setViewportSize({width:390,height:700});await page.evaluate(()=>{state.draftUploads=[{id:'safe',name:'报告.pdf',size:100}];drawInputFiles();});
  await page.waitForTimeout(400);const saved=await page.evaluate(()=>calls.filter(c=>c[0]==='save').at(-1)[1]);await page.reload();await page.evaluate(saved=>receive('restore',saved),saved);assert.equal(await page.locator('.input-file').count(),1,'uploaded draft metadata survives restart');
  fs.mkdirSync('test-output/input-files',{recursive:true});await page.screenshot({path:'test-output/input-files/draft.png'});assert.deepEqual(errors,[]);
  console.log('PASS file composer: choose/upload status, remove, file-only send, caption, sent metadata, rejection recovery, queue/edit, desktop guard, total size, safe labels, narrow widths and restart.');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1);});
