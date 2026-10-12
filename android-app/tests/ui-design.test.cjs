const assert=require('node:assert/strict'),path=require('node:path');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
 const page=await browser.newPage({viewport:{width:390,height:844},deviceScaleFactor:2});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{window.calls=[];window.Pocket={connect:r=>calls.push(['connect',JSON.parse(r)]),send:r=>calls.push(['send',JSON.parse(r)]),save(){},cancel(){},importKey(){},removeKey(){},resetTrust(){},copyText:t=>calls.push(['copy',t])};});
 await page.goto('file://'+path.resolve('app/src/main/assets/index.html'));await page.evaluate(()=>document.fonts.ready);
 const shot=async name=>page.screenshot({path:'test-output/ui-016/'+name+'.png'});
 const bounds=async()=>assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true,'no horizontal overflow');
 await bounds();assert.equal(await page.locator('#send').isDisabled(),true);await shot('welcome-light');
 await page.locator('#welcomeConnect').click();await page.locator('#connect').click();assert.equal(await page.locator('#host').getAttribute('aria-invalid'),'true');
 await page.locator('#host').fill('192.168.43.4');await page.locator('#user').fill('ryan');
 await shot('connection-light');await page.locator('#advancedSettings summary').click();assert.equal(await page.locator('#directory').isVisible(),true);await page.locator('#write').check();assert.equal(await page.locator('#write').isChecked(),true);
 await page.locator('.close[data-close="settings"]').click();
 await page.evaluate(()=>{receive('connected',{profile:{host:'192.168.43.4',port:22,user:'ryan',directory:'/Users/ryan/workspace'},agents:[{id:'codex',name:'Codex',detail:'后台会话'},{id:'deepseek',name:'DeepSeek Harness',detail:'后台会话'},{id:'workbuddy',name:'WorkBuddy',detail:'后台会话'},{id:'doubao',name:'豆包',detail:'桌面会话'}]});receive('busy',{value:false});$('toast').hidden=true;});
 await page.locator('#agentButton').click();await shot('agents-light');await page.locator('.agent-option').nth(2).click();assert.equal(await page.locator('.agent-option').nth(2).getAttribute('aria-pressed'),'true');await page.locator('#agentDone').click();
 await page.locator('#input').fill('帮我梳理这个项目，先说说你的建议。');await page.locator('#send').click();
 assert.equal(await page.locator('#input').inputValue(),'');assert.equal(await page.locator('#newChat').isDisabled(),true);
 await page.evaluate(()=>{receive('run',{id:'design-run',agent:'DeepSeek Harness',label:'回复'});receive('update',{id:'design-run',text:'可以先从这三个方面入手：\n\n1. 理清入口\n确认应用从哪里启动，以及主要模块之间的关系。\n\n2. 跑通核心流程\n从连接电脑到发送消息，检查每一步的状态。\n\n3. 缩小修改范围\n先解决影响使用的问题，再逐步完善细节。\n\n你想先看哪一部分？',status:'已完成'});receive('busy',{value:false});$('toast').hidden=true;scrollEnd();});
 await shot('chat-light');await page.locator('.copy-button').click();assert.equal(await page.evaluate(()=>calls.at(-1)[0]),'copy');
 await page.emulateMedia({colorScheme:'dark'});await page.waitForFunction(()=>document.documentElement.dataset.theme==='dark');await page.evaluate(()=>$('toast').hidden=true);await shot('chat-dark');
 const luminance=c=>{const a=c.match(/[\d.]+/g).slice(0,3).map(Number).map(v=>{v/=255;return v<=.04045?v/12.92:((v+.055)/1.055)**2.4});return a[0]*.2126+a[1]*.7152+a[2]*.0722};
 for(const theme of ['light','dark']){
  await page.emulateMedia({colorScheme:theme});await page.waitForFunction(t=>document.documentElement.dataset.theme===t,theme);
  const pairs=await page.evaluate(()=>{const root=getComputedStyle(document.documentElement);return [['--ink','--background'],['--secondary','--background'],['--secondary','--surface'],['--on-primary','--primary'],['--error','--background']].map(([f,b])=>{const a=document.createElement('div');a.style.color=root.getPropertyValue(f);a.style.backgroundColor=root.getPropertyValue(b);document.body.append(a);const st=getComputedStyle(a),r=[st.color,st.backgroundColor,f];a.remove();return r;});});
  for(const [f,b,n] of pairs){const a=luminance(f),c=luminance(b);assert.ok((Math.max(a,c)+.05)/(Math.min(a,c)+.05)>=4.5,theme+' '+n+' contrast');}
 }
 await page.evaluate(()=>{receive('run',{id:'design-fail',agent:'DeepSeek Harness',label:'回复'});receive('update',{id:'design-fail',text:'未确认已进入新会话',status:'失败：未确认已进入新会话'});receive('error',{message:'未确认已进入新会话'});scrollEnd();});
 assert.equal(await page.locator('.notice.error').count(),0);assert.equal(await page.locator('.failure-details').getAttribute('open'),null);await shot('error-dark');
 await page.locator('.failure-details summary').click();assert.ok((await page.locator('.failure-details pre').textContent()).includes('未确认'));
 await page.evaluate(()=>{scrollEnd();for(let i=0;i<15;i++)add({id:'long-'+i,role:'assistant',agent:'Codex',label:'回复',status:'已完成',text:'长对话阅读测试。\n'.repeat(4)});});
 await page.waitForFunction(()=>document.getElementById('jumpLatest').hidden);
 await page.evaluate(()=>$('chat').scrollTop=0);
 await page.waitForFunction(()=>!document.getElementById('jumpLatest').hidden);await page.locator('#jumpLatest').click();await page.waitForFunction(()=>document.getElementById('jumpLatest').hidden);
 for(const width of [320,390,600,820]){await page.setViewportSize({width,height:700});await bounds();}
 await page.setViewportSize({width:360,height:400});await page.locator('#input').fill('键盘弹出时仍然能看到输入与发送');await page.locator('#input').focus();await shot('keyboard-sized-dark');
 assert.ok(await page.locator('#send').evaluate(e=>{const r=e.getBoundingClientRect();return r.bottom<=innerHeight&&r.top>=0;}));
 await page.locator('#connectionButton').click();await page.locator('#password').fill('test');await page.locator('#connect').scrollIntoViewIfNeeded();assert.equal(await page.locator('#connect').isVisible(),true);
 await page.emulateMedia({reducedMotion:'reduce'});await page.locator('.close[data-close="settings"]').click();
 assert.deepEqual(errors,[]);console.log('PASS responsive 320/390/600/820; keyboard-sized layout; light/dark AA text contrast; copy; latest-message navigation; inline validation; collapsed settings; error deduplication; pressed agent state; busy guards; no browser errors.');
 await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
