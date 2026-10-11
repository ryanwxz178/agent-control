'use strict';
const fs=require('node:fs'),vm=require('node:vm'),http=require('node:http'),assert=require('node:assert/strict');
const source=fs.readFileSync(__dirname+'/../app/src/main/assets/workbuddy-acp-launch.js','utf8');
const implementation=source.slice(source.indexOf('const MAX_EVENT_BYTES'),source.indexOf('async function startup'));
(async()=>{
 let calls=0,scenario='image';
 const server=http.createServer((req,res)=>{
  calls++;req.resume();req.on('end',()=>{
   if(scenario==='disconnect'){req.socket.destroy();return;}
   res.writeHead(200,{'Content-Type':'text/event-stream'});
   const data='A'.repeat(scenario==='image'?5*1024*1024:8_000_001);
   const wire='data: '+JSON.stringify({jsonrpc:'2.0',method:'session/update',params:{content:{type:'image',data}}})+'\n\n';
   let offset=0;function pump(){while(offset<wire.length){const next=wire.slice(offset,offset+32768);offset+=next.length;if(!res.write(next)){res.once('drain',pump);return;}}res.end();}pump();
  });
 });
 try{
  await new Promise(r=>server.listen(0,'127.0.0.1',r));
  const c={http,Buffer,Error,JSON,Set,password:'fixture',connectionId:'fixture',endpoint:{port:server.address().port},requests:new Set()};
  vm.createContext(c);vm.runInContext(implementation,c);
  const messages=[];await c.post('/api/v1/acp',{id:3,method:'session/prompt'},m=>messages.push(m));
  assert.equal(messages.length,1);assert.equal(messages[0].params.content.data.length,5*1024*1024);
  scenario='oversize';await assert.rejects(c.post('/api/v1/acp',{id:4,method:'session/prompt'},()=>{}),/单条消息超过 8MB/);
  scenario='disconnect';await assert.rejects(c.post('/api/v1/acp',{id:5,method:'session/prompt'},()=>{}),/ECONNRESET.*不会自动重发/);
  assert.equal(calls,3);
  console.log('PASS real HTTP fragmented 5MiB image, explicit oversized-event cause, reset diagnostics and no automatic request replay.');
 }finally{server.closeAllConnections();await new Promise(r=>server.close(r));}
})().catch(e=>{console.error(e);process.exitCode=1;});
