const assert=require('node:assert/strict'),path=require('node:path'),fs=require('node:fs');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
 try{
  const page=await browser.newPage({viewport:{width:390,height:844},deviceScaleFactor:2}),errors=[],remote=[];
  page.on('pageerror',e=>errors.push(e.message));page.on('request',r=>{if(/^https?:/.test(r.url()))remote.push(r.url());});
  await page.addInitScript(()=>{window.calls=[];window.Pocket=new Proxy({}, {get:(_,name)=>(...args)=>window.calls.push([name,...args])});});
  await page.goto('file://'+path.resolve('app/src/main/assets/index.html'));await page.evaluate(()=>document.fonts.ready);
  const output=path.resolve('test-output/reply-format');fs.mkdirSync(output,{recursive:true});
  const text='## 基本信息\n\n- **搜索关键词**：南匠惊鸿龙，完整商品信息\n- **价格**：¥6000（**托管价**），原价 ¥6800\n- **成色**：几乎全新\n  - 嵌套说明：包邮，附带配件\n\n### 综合判断\n\n重点是 ==保留完整正文==，支持 *斜体*、~~删除线~~ 与 `行内代码`。\n\n> 引用内容保留层级，**重点**清晰可见。\n\n1. 确认商品信息\n2. 联系卖家\n\n- [x] 已完成核对\n- [ ] 等待补充\n\n| 项目 | 当前值 | 备注 | 更多说明 |\n| --- | ---: | --- | --- |\n| 商品价格 | 6000 | 托管促销 | 保留完整表格内容，并可横向查看较宽的列 |\n| 浏览量 | 388 | 当前热度 | 手机端布局不会被撑宽 |\n\n```js\nconst price = 6000;\nconsole.log("保留代码缩进、颜色和完整原文", price);\n```\n\n[商品链接](https://example.com/item)\n\n![附件图片](https://example.com/private.png)';
  await page.evaluate(text=>{add({id:'rich-reply',role:'assistant',agent:'WorkBuddy',text,status:'已完成'});$('chat').scrollTop=0;},text);
  assert.equal(await page.locator('.markdown h2').textContent(),'基本信息');assert.ok((await page.locator('.markdown li').first().textContent()).includes('南匠惊鸿龙'));
  assert.equal(await page.locator('.markdown li').first().evaluate(n=>getComputedStyle(n).listStyleType),'disc');assert.equal(await page.locator('.markdown strong').first().evaluate(n=>getComputedStyle(n).fontWeight),'700');
  assert.equal(await page.locator('.markdown mark').textContent(),'保留完整正文');assert.equal(await page.locator('.task-checkbox:disabled').count(),2);assert.equal(await page.locator('.task-checkbox:checked').count(),1);
  assert.ok(await page.locator('pre .hljs-keyword').count());assert.equal(await page.locator('.code-language').textContent(),'js');
  assert.equal(await page.locator('.markdown img').count(),0);assert.deepEqual(remote,[],'formatting never loads remote scripts or reply images');
  await page.screenshot({path:path.join(output,'reply-light.png')});
  await page.locator('.code-copy').click();assert.equal(await page.evaluate(()=>calls.filter(c=>c[0]==='copyText').at(-1)[1]),'const price = 6000;\nconsole.log("保留代码缩进、颜色和完整原文", price);\n');
  await page.locator('.copy-button').click();assert.equal(await page.evaluate(()=>calls.filter(c=>c[0]==='copyText').at(-1)[1]),text,'whole reply copy retains Markdown source');
  await page.locator('#toast').waitFor({state:'hidden',timeout:8000});
  await page.evaluate(()=>{$('chat').scrollTop=$('chat').scrollHeight;});await page.screenshot({path:path.join(output,'reply-code-table.png')});
  await page.evaluate(()=>render());assert.equal(await page.locator('.code-copy').count(),1);assert.equal(await page.locator('.code-language').count(),1);assert.equal(await page.locator('.table-viewport').count(),1,'re-render does not duplicate table wrappers');
  await page.evaluate(()=>setTheme(true));await page.setViewportSize({width:320,height:660});await page.evaluate(()=>{$('chat').scrollTop=$('chat').scrollHeight;});
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);assert.ok(await page.locator('.table-viewport').evaluate(n=>n.scrollWidth>n.clientWidth),'wide table scrolls inside its viewport');
  await page.screenshot({path:path.join(output,'reply-dark-narrow.png')});
  for(const [filename,screenshot] of [['workbuddy-rich-reply.json','workbuddy-live-reply.png'],['workbuddy-original-markdown.json','workbuddy-original-reply.png']]){
   const liveFixture=path.resolve('../test-output',filename);if(!fs.existsSync(liveFixture))continue;
   const reply=JSON.parse(fs.readFileSync(liveFixture,'utf8').replace(/^\uFEFF/,''));assert.ok(reply.complete&&reply.sent);
   await page.setViewportSize({width:390,height:844});await page.evaluate(reply=>{setTheme(false);state.messages=[];render();add({id:'live-reply',role:'assistant',agent:'WorkBuddy',text:reply.text,status:'已完成'});$('chat').scrollTop=0;},reply);
   assert.ok((await page.locator('.markdown').textContent()).includes('南匠惊鸿龙'));assert.ok((await page.locator('.markdown').textContent()).includes('388'));assert.equal(await page.locator('.markdown li').count(),9);assert.ok(await page.locator('.markdown strong').count());
   await page.screenshot({path:path.join(output,screenshot)});
  }
  assert.deepEqual(errors,[]);console.log('reply-format-ui: lists, emphasis, highlighting, code copy, table overflow, re-render and offline safety passed');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exitCode=1;});
