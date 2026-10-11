'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const net=require('node:net'),http=require('node:http'),crypto=require('node:crypto'),readline=require('node:readline');
const {spawn}=require('node:child_process');
// Isolated local fixture: verifies duplex ACP permissions and lifecycle, no real desktop or account.
const sha=(s,n)=>crypto.createHash('sha1').update(s).digest('hex').slice(0,n);
const config=fs.mkdtempSync(path.join(os.tmpdir(),'orca-managed-test-'));
const runtimeRoot=path.join(os.tmpdir().trim(),'wb-'+sha(String(process.getuid()),6),sha(config,12));
fs.mkdirSync(runtimeRoot,{recursive:true,mode:0o700});
let owned,runtime,password,promptResponse,permissionReceived=false,imageReceived=false,killed=false,child;
const gateway=http.createServer((req,res)=>{
 let body='';req.setEncoding('utf8');req.on('data',s=>body+=s);req.on('end',()=>{
  assert.equal(req.headers.authorization,'Bearer '+password);
  assert.equal(req.headers['x-codebuddy-request'],'1');
  if(req.url==='/api/v1/acp/connect'){res.end(JSON.stringify({connectionId:'fixture-connection'}));return;}
  assert.equal(req.headers['acp-connection-id'],'fixture-connection');
  const message=JSON.parse(body);
  const send=msg=>res.write('data: '+JSON.stringify(msg)+'\n\n');
  if(message.method==='initialize'){res.setHeader('Content-Type','text/event-stream');send({jsonrpc:'2.0',id:message.id,result:{protocolVersion:1}});res.end();}
  else if(message.method==='session/new'){send({jsonrpc:'2.0',id:message.id,result:{sessionId:'fixture-session'}});res.end();}
  else if(message.method==='session/prompt'){
   promptResponse=res;
   send({jsonrpc:'2.0',id:'permission-1',method:'session/request_permission',params:{sessionId:'fixture-session',options:[{kind:'allow_once',optionId:'once',name:'Only once'}]}});
  }else{
   assert.equal(message.id,'permission-1');assert.equal(message.result.outcome.optionId,'once');
   permissionReceived=true;res.writeHead(202);res.end();
   promptResponse.write('data: '+JSON.stringify({jsonrpc:'2.0',method:'session/update',params:{sessionId:'fixture-session',update:{sessionUpdate:'agent_message_chunk',content:{type:'image',mimeType:'image/png',data:'A'.repeat(5*1024*1024)}}}})+'\n\n');
   promptResponse.write('data: '+JSON.stringify({jsonrpc:'2.0',method:'session/update',params:{sessionId:'fixture-session',update:{sessionUpdate:'agent_message_chunk',content:{type:'text',text:'FIXTURE-PERMISSION-OK'}}}})+'\n\n');
   promptResponse.end('data: '+JSON.stringify({jsonrpc:'2.0',id:3,result:{stopReason:'end_turn'}})+'\n\n');
  }
 });
});
const sidecar=net.createServer(socket=>{
 readline.createInterface({input:socket}).on('line',line=>{
  const msg=JSON.parse(line);let result;
  if(msg.method==='sidecar.ping')result={version:6};
  else if(msg.method==='session.create'){
   owned=msg.params.sessionId;runtime='fixture-runtime';password=msg.params.env.CODEBUDDY_GATEWAY_PASSWORD;
   assert.equal(msg.params.port,0);assert.equal(msg.params.env.CODEBUDDY_GATEWAY_AUTH,'password');
   assert.ok(msg.params.args.includes('Read,Glob,Grep'));assert.ok(!msg.params.args.includes('--dangerously-skip-permissions'));
   result={runtimeId:runtime,acpEndpoint:'http://127.0.0.1:'+gateway.address().port+'/api/v1/acp'};
  }else if(msg.method==='session.kill'){
   assert.equal(msg.params.sessionId,owned);assert.equal(msg.params.expectedRuntimeId,runtime);killed=true;result={ok:true};
  }else throw Error('Unexpected sidecar operation '+msg.method);
  socket.write(JSON.stringify({jsonrpc:'2.0',id:msg.id,result})+'\n');
 });
});
(async()=>{
 try{
  await new Promise(r=>gateway.listen(0,'127.0.0.1',r));
  await new Promise(r=>sidecar.listen(path.join(runtimeRoot,'sidecar-aabb.sock'),r));
  const source=fs.readFileSync(path.join(__dirname,'../app/src/main/assets/workbuddy-acp-launch.js'),'utf8').replace('__COMMAND__',JSON.stringify([process.execPath,'/Applications/WorkBuddy.app/Contents/Resources/app.asar.unpacked/cli/bin/codebuddy'])).replace('__WRITE__','false');
  child=spawn(process.execPath,['-e',source],{env:{...process.env,WORKBUDDY_CONFIG_DIR:config},stdio:['pipe','pipe','pipe']});
  let stderr='';child.stderr.on('data',s=>stderr+=s);
  const request=(id,method,params)=>child.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n');
  const complete=new Promise((resolve,reject)=>{
   readline.createInterface({input:child.stdout}).on('line',line=>{
    try{
     const msg=JSON.parse(line);if(msg.error)throw Error(msg.error.message);
     if(msg.id===1)request(2,'session/new',{cwd:config,mcpServers:[]});
     if(msg.id===2)request(3,'session/prompt',{sessionId:'fixture-session',prompt:[{type:'text',text:'fixture'}]});
     if(msg.method==='session/request_permission')child.stdin.write(JSON.stringify({jsonrpc:'2.0',id:msg.id,result:{outcome:{outcome:'selected',optionId:'once'}}})+'\n');
     if(msg.method==='session/update'&&msg.params.update.content?.type==='image'){assert.equal(msg.params.update.content.data.length,5*1024*1024);imageReceived=true;}
     if(msg.id===3){assert.equal(msg.result.stopReason,'end_turn');resolve();}
    }catch(e){reject(e);}
   });
   child.on('error',reject);child.on('exit',code=>{if(code!==0)reject(Error(stderr||'Adapter failed'));});
  });
  request(1,'initialize',{protocolVersion:1,clientCapabilities:{}});
  await Promise.race([complete,new Promise((_,reject)=>setTimeout(()=>reject(Error('fixture timed out')),10000).unref())]);
  assert.ok(permissionReceived);assert.ok(imageReceived);child.stdin.end();
  await new Promise((resolve,reject)=>child.once('exit',code=>code===0?resolve():reject(Error(stderr))));
  assert.ok(killed);console.log('PASS managed CLI: password gateway, duplex permission response HTTP 202, 5MiB image and completion, owned-runtime cleanup');
 }finally{
  if(child&&child.exitCode===null)child.kill('SIGTERM');
  gateway.close();sidecar.close();fs.rmSync(runtimeRoot,{recursive:true,force:true});fs.rmSync(config,{recursive:true,force:true});
 }
})().catch(error=>{console.error(error);process.exitCode=1;});
