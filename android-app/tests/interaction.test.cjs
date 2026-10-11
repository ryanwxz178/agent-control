const assert=require('node:assert/strict'),path=require('node:path');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
 const page=await browser.newPage({viewport:{width:390,height:844}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.addInitScript(()=>{window.calls=[];window.Pocket={save(){},previewAttachment:id=>calls.push(['preview',id]),respond:(...a)=>calls.push(a),inspectDesktop:id=>calls.push(['inspect',id]),operateDesktop:(...a)=>calls.push(['operate',...a]),tapDesktop:(...a)=>calls.push(['tap',...a]),requestAttachment:id=>calls.push(['file',id]),exportAttachment:id=>calls.push(['save',id])};});
 await page.goto('file://'+path.resolve('app/src/main/assets/index.html'));
 await page.evaluate(()=>{receive('run',{id:'r',agent:'Codex',label:'回复'});receive('interaction',{id:'r',token:'t',kind:'approval',title:'Codex 需要你的确认',detail:'执行命令前需要确认\n工作目录：/tmp/项目\nprintf ORCA_APPROVAL_OK',state:'pending',expiresAt:Date.now()+180000,options:[{id:'accept',label:'仅允许这次'},{id:'decline',label:'拒绝'}]});});
 await page.screenshot({path:'test-output/interaction-light.png'});
 await page.getByRole('button',{name:'拒绝',exact:true}).click();assert.equal(await page.evaluate(()=>calls.length),1);assert.ok(await page.getByRole('button',{name:'拒绝',exact:true}).isDisabled());
 await page.evaluate(()=>{receive('interactionState',{id:'r',token:'t',state:'closed',message:'电脑已结束该请求'});receive('interaction',{id:'r',token:'q',kind:'questions',title:'需要补充信息',questions:[{id:'q1',question:'文件保存在哪个目录？',options:[{label:'Downloads',description:'保存到下载文件夹'}]}],state:'pending',expiresAt:Date.now()+180000});});
 await page.getByRole('button',{name:'提交回答'}).click();assert.equal(await page.evaluate(()=>calls.length),1);
 await page.getByRole('textbox',{name:'文件保存在哪个目录？'}).fill('/tmp/中文');await page.getByRole('button',{name:'提交回答'}).click();assert.equal(await page.evaluate(()=>JSON.parse(calls[1][1]).answers.q1),'/tmp/中文');
 await page.evaluate(()=>{receive('attachment',{id:'r',attachmentId:'f',name:'方案.png',path:'/tmp/方案.png'});});await page.locator('.attachment-button').click();assert.equal(await page.evaluate(()=>calls.at(-1)[0]),'file');
 await page.evaluate(()=>receive('attachmentReady',{attachmentId:'f',name:'方案.png',size:82,preview:'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg=='}));
 assert.ok(await page.locator('#attachmentViewer').evaluate(n=>n.open));await page.getByRole('button',{name:'保存到手机'}).click();assert.equal(await page.evaluate(()=>calls.at(-1)[0]),'save');
 await page.locator('[data-close="attachmentViewer"]').click();await page.evaluate(()=>{setTheme(true);receive('interaction',{id:'r',token:'expired',title:'已过期确认',kind:'approval',state:'pending',expiresAt:Date.now()-1,options:[{id:'accept',label:'过期操作'}]});});assert.equal(await page.getByRole('button',{name:'过期操作'}).count(),0);
 await page.screenshot({path:'test-output/interaction-dark.png'});
 await page.evaluate(()=>{receive('desktopAvailable',{id:'r'});openDesktop('r');receive('desktopPanel',{id:'r',token:'view',controls:[{id:'c',label:'继续测试',editable:false}],preview:'',warning:'',text:'这是电脑实际问题卡片'});});
 await page.locator('#desktopControl').selectOption('c');await page.locator('#performDesktop').click();assert.equal(await page.evaluate(()=>calls.at(-1)[0]),'operate');assert.ok(await page.locator('#performDesktop').isDisabled());
 await page.evaluate(()=>receive('desktopError',{message:'电脑界面已变化，请刷新'}));assert.ok((await page.locator('#desktopStatus').textContent()).includes('刷新'));
 await page.locator('[data-close="desktopViewer"]').click();
 assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);assert.deepEqual(errors,[]);

 await page.evaluate(()=>{startConversation();receive('run',{id:'wb',agent:'WorkBuddy',label:'回复'});receive('interaction',{id:'wb',token:'wb-token',desktop:true,kind:'approval',title:'WorkBuddy 等待确认',detail:'你想生成什么风格的图片？\n自然风光\n1\n科技未来感\n2\n可爱插画\n3\n中国风水墨\n4',state:'pending',expiresAt:Date.now()+180000,options:['全部跳过','1 自然风光','2 科技未来感','3 可爱插画','4 中国风水墨','发送'].map(label=>({id:label,label}))});});
 for(const width of [320,390]){await page.setViewportSize({width,height:844});for(const dark of [false,true]){await page.evaluate(d=>setTheme(d),dark);assert.equal(await page.locator('.interaction-detail').evaluate(n=>n.open),false);const rects=await page.locator('[data-token="wb-token"] button').evaluateAll(ns=>ns.map(n=>{const r=n.getBoundingClientRect();return {x:r.x,right:r.right,top:r.top,bottom:r.bottom,height:r.height};}));assert.ok(rects.every(r=>r.x>=0&&r.right<=width&&r.height>=44));assert.ok(rects.every((r,i)=>!i||r.top>=rects[i-1].bottom));await page.screenshot({path:'test-output/workbuddy-confirm-'+width+'-'+dark+'.png'});}}
 await page.locator('[data-token="wb-token"] button').filter({hasText:'1 自然风光'}).click();await page.evaluate(()=>receive('interactionState',{id:'wb',token:'wb-token',state:'closed',message:'已发送选择，等待电脑更新'}));assert.equal(await page.locator('[data-token="wb-token"] button:visible').count(),0);assert.ok(await page.locator('.interaction-result').filter({hasText:'已选择：1 自然风光'}).isVisible());

 await page.evaluate(()=>receive('attachment',{id:'wb',attachmentId:'remote-image',name:'豆包生成图片.png',path:'豆包回复图片',remote:true}));assert.deepEqual(await page.evaluate(()=>calls.at(-1)),['preview','remote-image']);
 await page.evaluate(()=>receive('attachmentReady',{attachmentId:'remote-image',name:'豆包生成图片.png',size:82,preview:'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg=='}));
 assert.equal(await page.locator('.attachment-thumbnail').count(),1);assert.equal(await page.locator('#attachmentViewer').evaluate(n=>n.open),false);
 await page.locator('.attachment-button').click();assert.ok(await page.locator('#attachmentViewer').evaluate(n=>n.open));
 await page.locator('[data-close="attachmentViewer"]').click();
 await page.evaluate(()=>{startConversation();receive('run',{id:'tele-text',agent:'TeleAgent',label:'回复'});receive('interaction',{id:'tele-text',token:'text-token',title:'TeleAgent 等待确认',desktop:true,kind:'approval',state:'pending',expiresAt:Date.now()+180000,inputs:[{id:'question-text-0',label:'自定义回答（必填）',value:'',required:true}],options:[{id:'提交全部答案（1/1 已回答）',label:'提交全部答案（1/1 已回答）'}]});});
 const count=await page.evaluate(()=>calls.length);await page.locator('#answerContinue').evaluate(button=>button.click());assert.equal(await page.evaluate(()=>calls.length),count,'empty required answer cannot submit');
 await page.getByRole('textbox',{name:'自定义回答（必填）'}).fill('手机自定义答案');await page.locator('#answerContinue').click();assert.deepEqual(await page.evaluate(()=>JSON.parse(calls.at(-1)[1])),{choice:'__desktop_text__',textValues:{'question-text-0':'手机自定义答案'}});
 await page.evaluate(()=>{receive('interactionState',{id:'tele-text',token:'text-token',state:'closed'});receive('interaction',{id:'tele-text',token:'continue-token',title:'TeleAgent 等待确认',desktop:true,kind:'approval',state:'pending',expiresAt:Date.now()+180000,inputs:[{id:'question-text-0',label:'补充说明（可选）',value:'已有文字',required:false}],options:[{id:'提交全部答案（1/1 已回答）',label:'提交全部答案（1/1 已回答）'}]});});
 assert.ok(await page.locator('[data-token="continue-token"] .interaction-text-save').isEnabled(),'unchanged desktop text can still continue');
 await page.locator('#answerContinue').click();assert.deepEqual(await page.evaluate(()=>JSON.parse(calls.at(-1)[1])),{choice:'__desktop_text__',textValues:{'question-text-0':'已有文字'}});assert.ok(await page.locator('[data-token="continue-token"] .interaction-text-save').isDisabled(),'continue cannot be sent twice');
 assert.deepEqual(errors,[]);console.log('PASS desktop required input validation and text-bearing submit payload');
 console.log('PASS mobile confirmation, duplicate tap guard, free-text validation, attachment preview/save, expiry, light/dark and narrow viewport');await browser.close();
})().catch(e=>{console.error(e);process.exit(1)});
