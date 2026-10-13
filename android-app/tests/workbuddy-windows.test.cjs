'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),os=require('node:os');
const net=require('node:net'),crypto=require('node:crypto'),readline=require('node:readline'),cp=require('node:child_process');
if(process.platform!=='win32'){console.log('SKIP Windows named-pipe fixture');process.exit(0);}
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'agent-control-wb-')),ticket=crypto.randomBytes(32).toString('base64url');
const endpoint='\\\\.\\pipe\\wbipc-'+crypto.randomBytes(8).toString('hex');
let child,server,authorized=false,gzipReceived=false,rejectedUnauthenticated=false,boundaryReceived=false,forwarded=0;
const fake=path.join(dir,'resources/app.asar.unpacked/cli/bin/codebuddy');fs.mkdirSync(path.dirname(fake),{recursive:true});
fs.mkdirSync(path.join(dir,'wbipc'));fs.mkdirSync(path.join(dir,'cache'));
fs.writeFileSync(path.join(dir,'wbipc/endpoint.json'),JSON.stringify({endpoint,ticket}));
fs.writeFileSync(path.join(dir,'cache/acc-product-config-v3.json'),JSON.stringify({endpoint:'https://fixture.invalid',authentication:{type:'cli-external-link'}}));
fs.writeFileSync(fake,`const http=require('http'),zlib=require('zlib'),rl=require('readline');
const config=JSON.parse(process.env.ACC_PRODUCT_CONFIG_V3),key=process.env.CODEBUDDY_API_KEY;
if(process.env.CODEBUDDY_CREDENTIALS_IN_MEMORY!=='1')throw Error('Credentials must remain in memory');
const send=x=>console.log(JSON.stringify(x));
rl.createInterface({input:process.stdin}).on('line',async line=>{const j=JSON.parse(line);
 if(j.method==='initialize')send({jsonrpc:'2.0',id:j.id,result:{protocolVersion:1}});
 if(j.method==='session/new')send({jsonrpc:'2.0',id:j.id,result:{sessionId:'fixture'}});
 if(j.method==='session/prompt'){
  const denied=await fetch(config.endpoint+'/v2/chat/completions',{method:'POST',body:'{}'});if(denied.status!==401)throw Error('Loopback auth required');
  for(const compressed of [false,true]){
   const oversized='x'.repeat(655361);
   const blocked=await fetch(config.endpoint+'/v2/chat/completions',{method:'POST',headers:{authorization:'Bearer '+key,...compressed?{'content-encoding':'gzip'}:{}},body:compressed?zlib.gzipSync(oversized):oversized});
   if(blocked.status!==502)throw Error('Oversized actual model request must not reach broker');await blocked.text();
  }
  const boundary=await fetch(config.endpoint+'/v2/chat/completions',{method:'POST',headers:{authorization:'Bearer '+key},body:'x'.repeat(655360)});
  if(boundary.status!==200)throw Error('Exact 640KiB boundary must pass');await boundary.text();
  const response=await fetch(config.endpoint+'/v2/chat/completions',{method:'POST',headers:{authorization:'Bearer '+key,'content-type':'application/json','content-encoding':'gzip'},body:zlib.gzipSync(JSON.stringify({prompt:'fixture'}))});
  if(!response.ok)throw Error('Proxy failed');const text=await response.text();
  send({jsonrpc:'2.0',method:'session/update',params:{sessionId:'fixture',update:{sessionUpdate:'agent_message_chunk',content:{type:'text',text}}}});
  send({jsonrpc:'2.0',id:'permission',method:'session/request_permission',params:{sessionId:'fixture',options:[{kind:'allow_once',optionId:'once',name:'Allow once'}]}});
 }
 if(j.id==='permission'&&j.result?.outcome?.optionId==='once')send({jsonrpc:'2.0',id:3,result:{stopReason:'end_turn'}});
});`);
function proof(side,clientNonce,serverNonce){const parts=[];for(const value of [side==='server'?'wbipc-s':'wbipc-c','1',endpoint,clientNonce,serverNonce]){const b=Buffer.from(value),n=Buffer.alloc(4);n.writeUInt32BE(b.length);parts.push(n,b);}return crypto.createHmac('sha256',ticket).update(Buffer.concat(parts)).digest('base64url');}
server=net.createServer(socket=>{let clientNonce,serverNonce;
 readline.createInterface({input:socket}).on('line',line=>{const j=JSON.parse(line);const send=x=>socket.write(JSON.stringify(x)+'\n');
  if(j.type==='session_hello'){assert.equal(j.ticket_id,crypto.createHash('sha256').update(ticket).digest('hex').slice(0,16));clientNonce=j.client_nonce;serverNonce=crypto.randomBytes(32).toString('base64url');send({type:'session_challenge',server_nonce:serverNonce,server_proof:proof('server',clientNonce,serverNonce)});}
  else if(j.type==='session_prove'){assert.equal(j.client_proof,proof('client',clientNonce,serverNonce));authorized=true;send({type:'session_hello_ack',protocol:1,pipes:['wb.request']});}
  else if(j.method==='broker/GetPipe'){send({jsonrpc:'2.0',id:j.id,result:{channel:'c:wb.request',methods:['http.fetch']}});}
  else if(j.method==='c:wb.request/http.fetch'){
   forwarded++;
   assert.ok(authorized);assert.equal(j.mode,'call');assert.equal(j.params.path,'/v2/chat/completions');
   assert.equal(j.params.headers.authorization,undefined,'Desktop credential must stay inside broker');
   const body=Buffer.from(j.params.body_b64,'base64');
   if(body.length===655360){assert.equal(body.toString(),'x'.repeat(655360));boundaryReceived=true;}
   else{assert.deepEqual(JSON.parse(body),{prompt:'fixture'});gzipReceived=true;rejectedUnauthenticated=true;}
   send({jsonrpc:'2.0',id:j.id,result:{status:200,headers:{'content-type':'text/plain'},body_b64:Buffer.from('中文 WINDOWS_CLI_OK').toString('base64')}});
  }else throw Error('Unexpected broker call');
 });
});
(async()=>{try{
 await new Promise(r=>server.listen(endpoint,r));
 const source=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/workbuddy-windows-cli.js'),'utf8').replace('__COMMAND__',JSON.stringify([process.execPath,fake])).replace('__WRITE__','false');
 child=cp.spawn(process.execPath,['-e',source],{env:{...process.env,WORKBUDDY_CONFIG_DIR:dir},stdio:['pipe','pipe','pipe'],windowsHide:true});
 const send=(id,method,params)=>child.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n');
 let text='',permission=false;const done=new Promise((resolve,reject)=>{
 readline.createInterface({input:child.stdout}).on('line',line=>{try{const j=JSON.parse(line);if(j.error)throw Error(j.error.message);
 if(j.id===1)send(2,'session/new',{cwd:dir,mcpServers:[]});if(j.id===2)send(3,'session/prompt',{sessionId:'fixture',prompt:[{type:'text',text:'fixture'}]});
 if(j.method==='session/update')text+=j.params.update.content.text;
 if(j.method==='session/request_permission'){permission=true;child.stdin.write(JSON.stringify({jsonrpc:'2.0',id:j.id,result:{outcome:{outcome:'selected',optionId:'once'}}})+'\n');}
 if(j.id===3){assert.equal(j.result.stopReason,'end_turn');resolve();}
 }catch(e){reject(e);}});child.on('error',reject);child.on('exit',code=>{if(code)reject(Error('CLI wrapper exit '+code));});});
 send(1,'initialize',{protocolVersion:1,clientCapabilities:{}});
 await Promise.race([done,new Promise((_,reject)=>setTimeout(()=>reject(Error('Fixture timed out')),15000).unref())]);
 assert.equal(text,'中文 WINDOWS_CLI_OK');assert.ok(permission&&gzipReceived&&authorized&&rejectedUnauthenticated&&boundaryReceived);assert.equal(forwarded,2,'Neither oversized request is forwarded or retried');child.stdin.end();
 await new Promise(r=>child.once('exit',r));
 console.log('PASS Windows WorkBuddy: authenticated named pipe, private loopback proxy, gzip decoding, UTF-8 reply, duplex permission and owned-process cleanup');
 }finally{if(child&&child.exitCode===null)child.kill();await new Promise(r=>server.close(r));fs.rmSync(dir,{recursive:true,force:true});}})().catch(e=>{console.error(e);process.exitCode=1;});
