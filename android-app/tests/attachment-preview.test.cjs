const assert=require('node:assert/strict'),path=require('node:path');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_PATH||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
 try{
  const page=await browser.newPage({viewport:{width:390,height:844}}),errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  await page.addInitScript(()=>{window.calls=[];window.Pocket=new Proxy({}, {get:(_,name)=>(...args)=>calls.push([name,...args])});});
  await page.goto('file://'+path.resolve('app/src/main/assets/index.html'));
  await page.evaluate(()=>receive('run',{id:'images',agent:'TeleAgent',label:'回复'}));
  const preview='data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/ScLbtAAAAABJRU5ErkJggg==';
  for(const [i,extension] of ['jpg','PNG','jpeg','webp','gif','bmp'].entries()){
   await page.evaluate(({i,extension})=>receive('attachment',{id:'images',attachmentId:'image-'+i,name:'call_result-'+i+'.'+extension,path:'C:/Users/steam/AppData/Local/Temp/result.'+extension}),{i,extension});
   assert.deepEqual(await page.evaluate(()=>calls.at(-1)),['previewAttachment','image-'+i]);
   await page.evaluate(({i,preview})=>receive('attachmentReady',{attachmentId:'image-'+i,name:'result.jpg',size:82,preview}),{i,preview});
   assert.equal(await page.locator('.attachment-thumbnail').count(),i+1);
   assert.equal(await page.locator('#attachmentViewer').evaluate(n=>n.open),false);
  }
  await page.evaluate(()=>render());
  assert.equal(await page.locator('.attachment-thumbnail').count(),6,'older images survive a render after download cache eviction');
  assert.equal(await page.locator('.attachment-button small').first().textContent(),'点按放大或保存');
  await page.locator('.attachment-button').first().click();
  assert.deepEqual(await page.evaluate(()=>calls.at(-1)),['requestAttachment','image-0'],'evicted original is downloaded again for saving');
  await page.evaluate(preview=>receive('attachmentReady',{attachmentId:'image-0',name:'result.jpg',size:82,preview}),preview);
  assert.equal(await page.locator('#attachmentViewer').evaluate(n=>n.open),true);
  await page.locator('#saveAttachment').click();
  assert.deepEqual(await page.evaluate(()=>calls.at(-1)),['exportAttachment','image-0']);
  await page.locator('[data-close="attachmentViewer"]').click();
  await page.evaluate(()=>receive('attachment',{id:'images',attachmentId:'failed',name:'missing.jpg',path:'C:/missing.jpg'}));
  await page.evaluate(()=>receive('attachmentError',{attachmentId:'failed',message:'图片读取失败'}));
  const failed=page.locator('.attachment-button').filter({hasText:'missing.jpg'});
  assert.equal(await failed.isDisabled(),false);assert.ok((await failed.textContent()).includes('图片读取失败'));
  await failed.click();assert.deepEqual(await page.evaluate(()=>calls.at(-1)),['requestAttachment','failed']);
  await page.evaluate(()=>receive('attachment',{id:'images',attachmentId:'pdf',name:'report.pdf',path:'C:/report.pdf'}));
  assert.equal(await page.locator('.attachment-button').filter({hasText:'report.pdf'}).isDisabled(),false);
  assert.equal(await page.evaluate(()=>calls.filter(c=>c[0]==='previewAttachment'&&c[1]==='pdf').length),0);
  assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth>innerWidth),false);
  await page.waitForTimeout(400);
  assert.equal(await page.evaluate(()=>calls.filter(c=>c[0]==='save').some(c=>c[1].includes('data:image/'))),false,'previews are not serialized into chat history');
  assert.deepEqual(errors,[]);
  console.log('PASS image attachments: automatic local previews, multiple images, rerender, enlargement, save, retry, ordinary files, mobile width and transient cache.');
 }finally{await browser.close();}
})().catch(error=>{console.error(error);process.exit(1);});
