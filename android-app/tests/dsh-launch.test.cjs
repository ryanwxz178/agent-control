const fs=require('fs'),vm=require('vm'),assert=require('assert/strict');
const source=fs.readFileSync('app/src/main/assets/dsh-acp-launch.js','utf8').replace('__LAUNCHER__',JSON.stringify('/Applications/DeepSeek Harness.app/Contents/Resources/runtime/cli/bin/dsh'));
function run(profiles){let patch,args,spawned,removed=0;const listeners={};
const fake={existsSync:()=>true,statSync:()=>({size:100}),readFileSync:p=>p.includes('/desktop/')?'desktop':'acp',mkdtempSync:()=>'/tmp/owned-orca',writeFileSync:(p,data,opts)=>{patch=JSON.parse(data);assert.equal(opts.mode,0o600)},unlinkSync:()=>removed++,rmdirSync:()=>removed++};
const context={require:n=>n==='fs'?fake:n==='path'?require('path'):n==='os'?{homedir:()=>'/home/user',tmpdir:()=>'/tmp'}:n==='child_process'?{spawn:(exe,a,opt)=>{args=a;spawned=exe;assert.equal(opt.stdio,'inherit');return{on:(event,fn)=>listeners[event]=fn,kill(){}}}}:{parse:p=>profiles[p]},process:{env:{},stdout:{write(){}},stderr:{write(){}},on(){}}};
vm.runInNewContext(source,context);if(listeners.exit)listeners.exit(0);return{patch,args,spawned,removed};}
const desktop=[{id:'agent-default-model',name:'@deepseek-ai/dsh-agent-default-model',config:{provider:'deepseek-account',model:'deepseek-flash',unrelatedSecret:'must-not-copy'}}];
let result=run({desktop,acp:[]});assert.equal(result.patch[0].id,'acp');assert.deepEqual(Object.keys(result.patch[0].config).sort(),['model','provider']);assert.equal(result.patch[0].config.provider,'deepseek-account');assert.equal(result.removed,2);
result=run({desktop,acp:[{id:'acp',config:{provider:'custom',model:'custom'}}]});assert.equal(result.patch,undefined);assert.deepEqual(Array.from(result.args),['--profile','acp']);
result=run({desktop:[],acp:[]});assert.equal(result.patch,undefined);
console.log('PASS DSH desktop model selection, explicit CLI override priority, no credential copying, inherited stdio and temporary patch cleanup.');
