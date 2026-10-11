import { mkdirSync, readFileSync, writeFileSync, openSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomBytes } from 'node:crypto';
import { spawn, spawnSync } from 'node:child_process';

// OpenMuse worker source is vendored under src/ with its MIT license. No model or cloud account.
const directory=dirname(fileURLToPath(import.meta.url));
const root=join(homedir(),'.agent-control');mkdirSync(root,{recursive:true,mode:0o700});
const configFile=join(root,'browser.json');let config;
try{config=JSON.parse(readFileSync(configFile,'utf8'));}catch{config={url:'http://127.0.0.1:8790',token:randomBytes(32).toString('hex')};}
writeFileSync(configFile,JSON.stringify(config),{mode:0o600});
try{const response=await fetch(config.url+'/health',{signal:AbortSignal.timeout(1500)});if(response.ok){console.log('Browser worker is already running.');process.exit(0);}}catch{}
for(const args of [['ci','--omit=dev'],['exec','--','playwright','install','chromium']]){
  const result=spawnSync(process.platform==='win32'?'npm.cmd':'npm',args,{cwd:directory,stdio:'inherit',shell:process.platform==='win32'});
  if(result.status!==0)process.exit(result.status||1);
}
const log=openSync(join(root,'browser.log'),'a',0o600);
const child=spawn(process.execPath,['--experimental-strip-types','src/index.ts'],{cwd:directory,detached:true,stdio:['ignore',log,log],env:{...process.env,WORKER_HOST:'127.0.0.1',WORKER_TOKEN:config.token,WORKER_DATA_DIR:join(root,'browser-profiles')}});
child.unref();
for(let attempt=0;attempt<30;attempt++){
  await new Promise(resolve=>setTimeout(resolve,500));
  try{const response=await fetch(config.url+'/sessions',{headers:{Authorization:'Bearer '+config.token},signal:AbortSignal.timeout(1000)});if(response.ok){console.log('Browser worker ready on computer loopback. Open the browser tab in Agent Control.');process.exit(0);}}catch{}
}
console.error('Browser worker did not start; inspect ~/.agent-control/browser.log');process.exit(1);
