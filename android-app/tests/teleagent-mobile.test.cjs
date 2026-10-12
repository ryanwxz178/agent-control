const assert=require('node:assert/strict'),path=require('node:path');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
 try{
 const page=await browser.newPage({viewport:{width:390,height:844}}),errors=[];page.on('pageerror',error=>errors.push(error.message));
 await page.addInitScript(()=>{window.calls=[];window.Pocket={save(){},respond:(...args)=>calls.push(args),copyText(){}};});
 await page.goto('file://'+path.resolve('app/src/main/assets/index.html'));
 await page.evaluate(()=>state.profile.taskService=false);
 const card={id:'tele-mobile',token:'answer-one',title:'TeleAgent 等待确认',desktop:true,kind:'approval',state:'pending',expiresAt:Date.now()+180000,detail:'交互式问答 · 需要确认2个问题\n你希望画面是什么形状？\n请从下面选择，也可以自定义。',inputs:[{id:'shape',label:'自定义回答（必填）',value:'',required:true}],options:[...['圆形','矩形','其他（自定义输入）'].map(label=>({id:label,label})),{id:'下一题（1/2 已回答）',label:'下一题（1/2 已回答）'}]};
 await page.evaluate(card=>{startConversation();$('input').value='保留我的聊天草稿';receive('run',{id:card.id,agent:'TeleAgent',label:'回复'});receive('interaction',card);},card);
 await page.waitForTimeout(50);
 assert.ok(await page.locator('#answerDock').isVisible());assert.ok(await page.locator('.composer').isHidden());assert.ok(await page.locator('#answerContinue').isDisabled());
 await page.locator('.interaction-text-input').fill('六边形');assert.ok(await page.locator('#answerContinue').isEnabled());assert.equal(await page.locator('#answerContinue').textContent(),'下一题');
 await page.locator('.interaction-text-input').evaluate(input=>{input.focus();input.setSelectionRange(1,2);});
 await page.evaluate(()=>{const message=state.messages.find(m=>m.id==='tele-mobile');renderExtras(findNode(message.id),message);});
 assert.equal(await page.locator('.interaction-text-input').inputValue(),'六边形');assert.deepEqual(await page.locator('.interaction-text-input').evaluate(input=>[document.activeElement===input,input.selectionStart,input.selectionEnd]),[true,1,2]);
 for(const height of [844,540,430])for(const width of [390,320]){
  await page.setViewportSize({width,height});
  for(const dark of [false,true]){
   await page.evaluate(d=>setTheme(d),dark);await page.locator('#answerQuestion').click();
   assert.ok(await page.locator('#answerContinue').evaluate(button=>{const r=button.getBoundingClientRect();return r.height>=44&&r.top>=0&&r.bottom<=innerHeight&&r.right<=innerWidth;}),'submit stays reachable with a short keyboard-sized viewport');
   assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);
  }
 }
 await page.screenshot({path:'test-output/teleagent-answer-keyboard-0516.png'});
 await page.locator('#answerCompose').click();assert.ok(await page.locator('.composer').isVisible());assert.equal(await page.locator('#input').inputValue(),'保留我的聊天草稿');
 await page.locator('#answerCompose').click();assert.ok(await page.locator('.composer').isHidden());assert.equal(await page.locator('.interaction-text-input').inputValue(),'六边形');
 await page.locator('#answerContinue').dblclick();assert.equal(await page.evaluate(()=>calls.length),1);assert.deepEqual(await page.evaluate(()=>JSON.parse(calls[0][1])),{choice:'__desktop_text__',textValues:{shape:'六边形'}});assert.equal(await page.evaluate(()=>calls[0][0]),'answer-one');assert.ok(await page.locator('#answerContinue').isDisabled());
 await page.evaluate(card=>{receive('interactionState',{id:card.id,token:card.token,state:'closed'});receive('interaction',{...card,token:'answer-two',inputs:[],options:[{id:'提交全部答案（2/2 已回答）',label:'提交全部答案（2/2 已回答）'}]});},card);
 await page.locator('#answerContinue').click();assert.equal(await page.evaluate(()=>calls.length),2);assert.deepEqual(await page.evaluate(()=>JSON.parse(calls[1][1])),{choice:'提交全部答案（2/2 已回答）'});
 await page.evaluate(()=>receive('interactionState',{id:'tele-mobile',token:'answer-two',state:'closed'}));assert.ok(await page.locator('#answerDock').isHidden());assert.ok(await page.locator('.composer').isVisible());
 await page.evaluate(card=>receive('interaction',{...card,token:'optional-answer',inputs:[{id:'note',label:'补充说明（可选）',value:'',required:false}],options:[{id:'风景插画 自然山水',label:'风景插画 自然山水',selected:true},{id:'提交全部答案（2/2 已回答）',label:'提交全部答案（2/2 已回答）'}]}),card);
 assert.equal(await page.locator('.teleagent-supplement').evaluate(details=>details.open),false);assert.ok(await page.locator('.interaction-actions .selected').isVisible());assert.ok((await page.locator('#answerTitle').textContent()).includes('风景插画'));
 await page.locator('.teleagent-supplement summary').click();await page.locator('.teleagent-supplement textarea').fill('浅蓝天空');await page.evaluate(()=>renderExtras(findNode('tele-mobile'),state.messages.find(m=>m.id==='tele-mobile')));assert.equal(await page.locator('.teleagent-supplement').evaluate(details=>details.open),true);assert.equal(await page.locator('.teleagent-supplement textarea').inputValue(),'浅蓝天空');
 await page.evaluate(()=>receive('interactionState',{id:'tele-mobile',token:'optional-answer',state:'closed'}));
 await page.evaluate(card=>receive('interaction',{...card,token:'expired-answer',expiresAt:Date.now()-1}),card);assert.ok(await page.locator('#answerDock').isHidden());
 await page.evaluate(card=>receive('interaction',{...card,token:'next-chat'}),card);assert.ok(await page.locator('#answerDock').isVisible());await page.evaluate(()=>startConversation());assert.ok(await page.locator('#answerDock').isHidden());
 await page.evaluate(()=>{receive('run',{id:'other',agent:'WorkBuddy',label:'回复'});receive('interaction',{id:'other',token:'other-token',desktop:true,title:'WorkBuddy 确认',kind:'approval',state:'pending',expiresAt:Date.now()+180000,options:[{id:'yes',label:'继续'}]});});assert.ok(await page.locator('#answerDock').isHidden());
 assert.deepEqual(errors,[]);
 console.log('PASS TeleAgent mobile answer dock: keyboard/narrow/dark layouts, required validation, draft/caret preservation, exact payload/token, double-tap guard, expiry and conversation isolation.');
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exit(1);});
