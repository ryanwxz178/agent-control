'use strict';
window.inputFilesUploading=false;
function fileSize(size){return size>=1024*1024?(size/1024/1024).toFixed(1)+' MB':Math.max(1,Math.ceil(size/1024))+' KB';}
function drawInputFiles(){
 const files=state.draftUploads||[],list=$('inputFiles');list.hidden=!files.length;list.replaceChildren();
 for(const file of files){const row=document.createElement('div');row.className='input-file';const name=document.createElement('span');name.textContent=file.name;name.title=file.name;const size=document.createElement('small');size.textContent=fileSize(file.size);const remove=document.createElement('button');remove.append(icon('x'));remove.setAttribute('aria-label','移除 '+file.name);remove.onclick=()=>{state.draftUploads=files.filter(f=>f.id!==file.id);call('removeInputFile',file.id);drawInputFiles();syncComposer();persist();};row.append(name,size,remove);list.append(row);}
 $('attachFile').disabled=!connected||window.inputFilesUploading||files.length>=4;
}
$('attachFile').onclick=()=>{if(!connected){openSettings();return;}call('pickFiles',4-(state.draftUploads||[]).length);};
const inputRender=render;render=function(){inputRender();drawInputFiles();};
const inputStart=startConversation;startConversation=function(...args){if(window.inputFilesUploading){toast('请等待文件上传完成再切换会话');return;}return inputStart(...args);};
const inputSwitch=switchConversation;switchConversation=function(...args){if(window.inputFilesUploading){toast('请等待文件上传完成再切换会话');return;}return inputSwitch(...args);};
const inputMessage=renderMessage;renderMessage=function(message){
 const node=inputMessage(message);
 if(message.role==='user'&&message.uploadedFiles?.length){const list=document.createElement('div');list.className='sent-files';for(const file of message.uploadedFiles){const label=document.createElement('span');label.textContent=file.name+' · '+fileSize(file.size);list.append(label);}node.insertBefore(list,node.querySelector('.message-time'));}
 return node;
};
const inputReceive=window.receive;window.receive=function(type,data){
 if(type==='inputFilesBusy'){window.inputFilesUploading=!!data.value;$('inputFileStatus').hidden=!data.value;$('inputFileStatus').textContent=data.message||'正在上传文件…';drawInputFiles();syncComposer();return;}
 if(type==='inputFileReady'){state.draftUploads=state.draftUploads||[];if(!state.draftUploads.some(f=>f.id===data.id)&&state.draftUploads.length<4)state.draftUploads.push(data);drawInputFiles();syncComposer();persist();return;}
 if(type==='rejected'){
  const message=state.messages.find(m=>m.id===data.messageId);
  if(message?.uploadedFiles?.length)state.draftUploads=[...(state.draftUploads||[]),...message.uploadedFiles].filter((f,i,all)=>all.findIndex(other=>other.id===f.id)===i);
 }
 inputReceive(type,data);if(['restore','connected','connection','rejected'].includes(type)){drawInputFiles();syncComposer();}
};
drawInputFiles();
