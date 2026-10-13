// Windows WorkBuddy CLI. The desktop WBIPC broker authenticates upstream requests.
// Desktop account tokens never leave WorkBuddy; only a per-run loopback key is used.
'use strict';
const fs=require('fs'),path=require('path'),os=require('os'),net=require('net');
const http=require('http'),crypto=require('crypto'),zlib=require('zlib'),readline=require('readline'),cp=require('child_process');
const launch=__COMMAND__,write=__WRITE__;
const root=process.env.WORKBUDDY_CONFIG_DIR||path.join(os.homedir(),'.workbuddy');
const pending=new Map(),requests=new Set();
let socket,server,child,sequence=0,closing=false,channel,handshakeTimer;
const MAX_BODY=655360,secret=crypto.randomBytes(32).toString('base64url');
function emit(value){process.stdout.write(JSON.stringify(value)+'\n');}
function fail(message){emit({jsonrpc:'2.0',id:1,error:{code:-32603,message}});shutdown(1);}
function readJson(file,limit){const stat=fs.lstatSync(file);if(!stat.isFile()||stat.isSymbolicLink()||stat.size>limit)throw Error('WorkBuddy 托管配置无效');return JSON.parse(fs.readFileSync(file,'utf8'));}
function rpc(method,params,mode){return new Promise((resolve,reject)=>{
 const id=++sequence,timer=setTimeout(()=>{pending.delete(id);reject(Error('WorkBuddy 托管请求超时，任务状态未知；不会自动重发'));},65000);
 pending.set(id,{resolve,reject,timer});socket.write(JSON.stringify({jsonrpc:'2.0',id,method,params,...mode?{mode}:{}})+'\n');
});}
async function connect(endpoint){
 if(!/^\\\\\.\\pipe\\wbipc-[a-f0-9]{16}$/.test(endpoint.endpoint)||!/^[-_A-Za-z0-9]{43}$/.test(endpoint.ticket))throw Error('WorkBuddy 托管入口不符合预期');
 const nonce=crypto.randomBytes(32).toString('base64url');let serverNonce;
 const proof=side=>{const fields=[side==='server'?'wbipc-s':'wbipc-c','1',endpoint.endpoint,nonce,serverNonce],parts=[];
  for(const field of fields){const value=Buffer.from(field),length=Buffer.alloc(4);length.writeUInt32BE(value.length);parts.push(length,value);}
  return crypto.createHmac('sha256',endpoint.ticket).update(Buffer.concat(parts)).digest();
 };
 socket=net.connect(endpoint.endpoint);
 await new Promise((resolve,reject)=>{
  handshakeTimer=setTimeout(()=>reject(Error('WorkBuddy 托管连接超时，请保持桌面应用已登录并运行')),10000);
  socket.once('error',()=>reject(Error('WorkBuddy 托管入口不可用，请启动并登录桌面应用')));
  socket.once('close',()=>reject(Error('WorkBuddy 托管连接断开')));
  socket.on('connect',()=>socket.write(JSON.stringify({type:'session_hello',protocol_min:1,protocol_max:1,client_nonce:nonce,
   ticket_id:crypto.createHash('sha256').update(endpoint.ticket).digest('hex').slice(0,16),client:{kind:'cli',id:'agent-control',version:'0.5.21'}})+'\n'));
  let buffer='';socket.on('data',chunk=>{
   buffer+=chunk.toString('utf8');if(Buffer.byteLength(buffer)>2000000){reject(Error('WorkBuddy 托管响应过大'));shutdown(1);return;}
   let at;while((at=buffer.indexOf('\n'))>=0){const line=buffer.slice(0,at);buffer=buffer.slice(at+1);let value;try{value=JSON.parse(line);}catch{reject(Error('WorkBuddy 托管响应无效'));continue;}
    if(value.type==='session_challenge'){
     serverNonce=value.server_nonce;const received=Buffer.from(value.server_proof||'','base64url'),expected=proof('server');
     if(received.length!==expected.length||!crypto.timingSafeEqual(received,expected)){reject(Error('WorkBuddy 托管身份校验失败'));continue;}
     socket.write(JSON.stringify({type:'session_prove',client_proof:proof('client').toString('base64url')})+'\n');
    }else if(value.type==='session_hello_ack'){
     if(value.protocol!==1||!value.pipes?.includes('wb.request')){reject(Error('WorkBuddy 尚未登录或托管协议不兼容'));continue;}
     clearTimeout(handshakeTimer);resolve();
    }else if(value.type==='session_hello_error'||value.type==='pipe_revoked'){reject(Error('WorkBuddy 登录已失效，请在电脑重新登录'));if(child)shutdown(1);}
    else if(value.id){const task=pending.get(value.id);if(task){pending.delete(value.id);clearTimeout(task.timer);
     value.error?task.reject(Error('WorkBuddy 托管请求失败（'+value.error.code+'），不会自动重发')):task.resolve(value.result);}}
   }
  });
 });
 socket.on('error',()=>shutdown(1));socket.on('close',()=>{if(!closing)shutdown(1);});
 channel=(await rpc('broker/GetPipe',{pipe:'wb.request'})).channel;
 if(typeof channel!=='string'||!/^c:[A-Za-z0-9._-]+$/.test(channel))throw Error('WorkBuddy 托管通道无效');
}
async function relay(req,res){
 let timer;requests.add(res);
 try{
  // A fresh loopback key protects each CLI process; the desktop token stays in its broker.
  if(req.headers.authorization!=='Bearer '+secret){res.writeHead(401);res.end();return;}
  const url=new URL(req.url,'http://127.0.0.1');if(!['GET','HEAD','POST','PUT','PATCH','DELETE'].includes(req.method))throw Error('不支持的请求方法');
  const pieces=[];let size=0;for await(const piece of req){size+=piece.length;if(size>MAX_BODY)throw Error('WorkBuddy CLI 完整请求超过托管接口 640KiB 限制；尚未转发本次请求，请减少附件或新建对话');pieces.push(piece);}
  let body=Buffer.concat(pieces);if(req.headers['content-encoding']==='gzip')body=zlib.gunzipSync(body,{maxOutputLength:MAX_BODY});
  else if(req.headers['content-encoding']&&req.headers['content-encoding']!=='identity')throw Error('WorkBuddy CLI 压缩格式未适配');
  const result=await rpc(channel+'/http.fetch',{method:req.method,path:url.pathname,query:Object.fromEntries(url.searchParams),
   headers:{'content-type':req.headers['content-type']||'application/json',accept:req.headers.accept||'*/*'},...(body.length?{body_b64:body.toString('base64')}:{})},'call');
  if(!Number.isInteger(result.status)||typeof result.body_b64!=='string')throw Error('WorkBuddy 托管回复无效');
  const response=Buffer.from(result.body_b64,'base64');if(response.length>MAX_BODY)throw Error('WorkBuddy CLI 回复超过托管接口 640KiB 限制');
  const headers={};for(const name of ['content-type','etag','last-modified','retry-after','location','x-request-id'])if(typeof result.headers?.[name]==='string')headers[name]=result.headers[name];
  res.writeHead(result.status,headers);res.end(response);
 }catch(error){if(!res.headersSent)res.writeHead(502,{'Content-Type':'application/json'});res.end(JSON.stringify({error:{message:error.message}}));}
 finally{clearTimeout(timer);requests.delete(res);}
}
function shutdown(code=0){
 if(closing)return;closing=true;clearTimeout(handshakeTimer);
 for(const task of pending.values()){clearTimeout(task.timer);task.reject(Error('WorkBuddy 托管连接结束'));}pending.clear();
 if(child&&!child.killed)child.kill();for(const response of requests)response.destroy();
 if(server){server.closeAllConnections();server.close();}if(socket)socket.destroy();
 process.exitCode=code;setTimeout(()=>process.exit(code),1000).unref();
}
async function start(){
 if(process.platform!=='win32'||launch.length!==2||!launch[1].replace(/\\/g,'/').endsWith('/resources/app.asar.unpacked/cli/bin/codebuddy'))throw Error('Windows WorkBuddy CLI 入口无效');
 const endpoint=readJson(path.join(root,'wbipc/endpoint.json'),4096);
 const config=readJson(path.join(root,'cache/acc-product-config-v3.json'),4000000);
 await connect(endpoint);
 server=http.createServer((req,res)=>relay(req,res));
 await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',resolve);});
 config.endpoint='http://127.0.0.1:'+server.address().port;
 config.authentication={id:'agent-control-workbuddy',type:'custom-token',attributes:{token:secret}};
 const args=[launch[1],'--acp','--permission-mode','default'];if(!write)args.push('--tools','Read,Glob,Grep');
 const env={...process.env,ACC_PRODUCT_CONFIG_V3:JSON.stringify(config),CODEBUDDY_API_KEY:secret,CODEBUDDY_CREDENTIALS_IN_MEMORY:'1',
  CODEBUDDY_CONFIG_DIR:root,WORKBUDDY_CONFIG_DIR:root,CODEBUDDY_HOST:'workbuddy-desktop',CODEBUDDY_FORCE_HEADLESS_BUNDLE:'1',CODEBUDDY_API_KEY_HELPER_DISABLED:'1'};
 delete env.ACC_PRODUCT_CONFIG_PATH;
 child=cp.spawn(launch[0],args,{env,stdio:['pipe','pipe','pipe'],windowsHide:true});
 child.stdout.pipe(process.stdout);child.stderr.resume();
 child.on('error',()=>fail('WorkBuddy CLI 启动失败，请检查 Node 和安装目录'));
 child.on('exit',code=>shutdown(code||0));
 process.stdin.pipe(child.stdin);process.stdin.on('end',()=>shutdown());child.stdin.on('error',()=>shutdown(1));
}
start().catch(()=>fail('WorkBuddy 托管启动失败，请保持桌面应用已登录并运行，或检查托管协议版本'));
for(const signal of ['SIGTERM','SIGINT','SIGHUP'])process.on(signal,()=>shutdown());
