// Launch only the bundled macOS CLI. Reuse DSH's credential service; copy no secrets.
const fs=require('fs'),path=require('path'),os=require('os'),cp=require('child_process');
const launcher=__LAUNCHER__;
const resources=path.resolve(path.dirname(launcher),'../../..');
const yaml=require(path.join(resources,'app.asar/dsh/node_modules/yaml'));
const base=process.env.DSH_HOME||path.join(os.homedir(),'.dsh');
function entries(profile){
 const file=path.join(base,'profiles',profile,'cordis.patch.yml');if(!fs.existsSync(file))return [];
 if(fs.statSync(file).size>1024*1024)throw Error('DSH 模型配置文件过大');
 const value=yaml.parse(fs.readFileSync(file,'utf8'));return Array.isArray(value)?value:[];
}
function desktopSelection(){
 // An explicit ACP selection is the user's CLI configuration and takes priority.
 if(entries('acp').some(e=>e.id==='acp'&&e.config&&(e.config.provider||e.config.model)))return null;
 const e=entries('desktop').find(e=>e.id==='agent-default-model'&&e.name==='@deepseek-ai/dsh-agent-default-model');
 if(!e||!e.config)return null;const {provider,model}=e.config;
 if(typeof provider!=='string'||typeof model!=='string'||provider.length>200||model.length>200||/[\x00-\x1f]/.test(provider+model))throw Error('DSH 桌面模型配置无效');
 return {provider,model};
}
let folder,child;
function clean(){if(folder){try{fs.unlinkSync(path.join(folder,'model.patch.yml'));fs.rmdirSync(folder);}catch(_){}folder=null;}}
try{
 const selected=desktopSelection(),args=['--profile','acp'];
 if(selected){
  folder=fs.mkdtempSync(path.join(os.tmpdir(),'orca-dsh-acp-'));
  const patch=path.join(folder,'model.patch.yml');
  fs.writeFileSync(patch,JSON.stringify([{id:'acp',name:'@deepseek-ai/dsh-acp',config:selected}]),{mode:0o600});args.push('--patch',patch);
  process.stdout.write(JSON.stringify({type:'status',message:'DSH CLI 使用桌面模型：'+selected.provider+' / '+selected.model})+'\n');
 }
 child=cp.spawn(launcher,args,{stdio:'inherit'});
 child.on('error',()=>{clean();process.stderr.write('DSH CLI 启动失败\n');process.exitCode=1;});
 child.on('exit',(code,signal)=>{clean();process.exitCode=code===null?1:code;});
 for(const signal of ['SIGTERM','SIGINT','SIGHUP'])process.on(signal,()=>{if(child)child.kill(signal);else clean();});
}catch(_){clean();process.stderr.write('无法读取 DSH 模型配置；请检查电脑的 desktop/acp profile\n');process.exitCode=1;}
