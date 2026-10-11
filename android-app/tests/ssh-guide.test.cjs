const assert=require('node:assert/strict'),path=require('node:path');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
(async()=>{const browser=await chromium.launch({headless:true,executablePath:'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});try{
const page=await browser.newPage({viewport:{width:390,height:844}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
await page.addInitScript(()=>{window.copied=[];window.Pocket={save(){},copyText:t=>copied.push(t)};});
await page.goto('file://'+path.resolve('app/src/main/assets/index.html'));await page.locator('#connectionButton').click();await page.locator('#host').fill('100.80.1.2');await page.locator('#user').fill('tester');
assert.equal(await page.locator('#autoConnect').evaluate(e=>e.getBoundingClientRect().height),28);
assert.equal(await page.locator('#connect').evaluate(e=>{const r=e.getBoundingClientRect();return r.bottom<=innerHeight&&r.height>=44;}),true);
await page.locator('#openSshGuide').click();assert.equal(await page.locator('[data-guide=mac]').isVisible(),true);
await page.locator('[data-copy=macIdentity]').click();assert.equal(await page.evaluate(()=>copied.at(-1)),'whoami');
await page.locator('[data-platform=windows]').click();assert.equal(await page.locator('[data-guide=mac]').isVisible(),false);assert.equal(await page.locator('[data-guide=windows]').isVisible(),true);
await page.locator('[data-copy=winStart]').click();assert.ok((await page.evaluate(()=>copied.at(-1))).includes('Start-Service sshd'));assert.ok((await page.evaluate(()=>copied.at(-1))).includes('LocalSubnet'));
await page.evaluate(()=>document.querySelector('#sshGuide .sheet-body').scrollTop=0);await page.screenshot({path:'test-output/ssh-guide-windows.png'});
for(const width of [320,390,600]){await page.setViewportSize({width,height:700});assert.equal(await page.evaluate(()=>{const s=document.querySelector('#sshGuide .sheet-body');return s.scrollWidth<=s.clientWidth;}),true,'guide has no horizontal overflow');}
await page.locator('[data-platform=linux]').click();assert.equal(await page.locator('[data-guide=linux]').isVisible(),true);
await page.evaluate(()=>back());assert.equal(await page.locator('#sshGuide').evaluate(e=>e.open),false);assert.equal(await page.locator('#settings').evaluate(e=>e.open),true);assert.equal(await page.locator('#host').inputValue(),'100.80.1.2');assert.equal(await page.locator('#user').inputValue(),'tester');
await page.setViewportSize({width:390,height:844});await page.screenshot({path:'test-output/ssh-settings-044.png'});assert.deepEqual(errors,[]);console.log('PASS SSH guide: platform selection, exact command copy, 320/390/600 overflow, Android back and draft preservation.');
}finally{await browser.close();}})().catch(e=>{console.error(e);process.exit(1)});
