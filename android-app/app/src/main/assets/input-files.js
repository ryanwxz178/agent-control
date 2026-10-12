'use strict';
window.inputFilesUploading=false;
let inputUploadOperation='',inputUploadMessage='';
const inputUploadDialog=document.createElement('dialog');inputUploadDialog.id='inputUploadDialog';inputUploadDialog.className='file-upload-dialog';inputUploadDialog.setAttribute('aria-labelledby','inputUploadTitle');inputUploadDialog.setAttribute('aria-describedby','inputUploadDetail');inputUploadDialog.setAttribute('aria-busy','true');
const inputUploadSpinner=document.createElement('span');inputUploadSpinner.className='file-upload-spinner';inputUploadSpinner.setAttribute('aria-hidden','true');
const inputUploadTitle=document.createElement('h2');inputUploadTitle.id='inputUploadTitle';inputUploadTitle.textContent='正在上传文件';
const inputUploadDetail=document.createElement('p');inputUploadDetail.id='inputUploadDetail';inputUploadDetail.setAttribute('role','status');
const inputUploadHint=document.createElement('small');inputUploadHint.textContent='请等待上传完成，消息尚未发送。';inputUploadDialog.append(inputUploadSpinner,inputUploadTitle,inputUploadDetail,inputUploadHint);document.body.append(inputUploadDialog);
inputUploadDialog.addEventListener('cancel',event=>event.preventDefault());
new MutationObserver(records=>{if(window.inputFilesUploading)for(const record of records){const dialog=record.target;if(dialog instanceof HTMLDialogElement&&dialog!==inputUploadDialog&&dialog.open)dialog.close();}}).observe(document.body,{subtree:true,attributes:true,attributeFilter:['open']});
const inputBack=window.back;window.back=function(...args){if(window.inputFilesUploading){toast('正在上传文件，请等待上传完成');return;}return inputBack(...args);};
function syncInputUploadDialog(){
 if(window.inputFilesUploading){inputUploadDetail.textContent=inputUploadMessage||'正在上传文件…';if(!inputUploadDialog.open){document.activeElement?.blur();inputUploadDialog.showModal();}}
 else if(inputUploadDialog.open)inputUploadDialog.close();
}
function fileSize(size){return size>=1024*1024?(size/1024/1024).toFixed(1)+' MB':Math.max(1,Math.ceil(size/1024))+' KB';}
function drawInputUploadStatus(){
 syncInputUploadDialog();
 const status=$('inputFileStatus'),failure=state.inputUploadFailure;
 if(failure&&failure.conversationId!==state.conversationId)delete state.inputUploadFailure;
 status.replaceChildren();
 if(window.inputFilesUploading){status.hidden=false;status.textContent=inputUploadMessage||'正在上传文件…';return;}
 if(!state.inputUploadFailure){status.hidden=true;return;}
 status.hidden=false;status.append(document.createTextNode(failure.message+(failure.retryable?'':' 请重新选择文件。')));
 if(failure.retryable){const retry=document.createElement('button');retry.className='text-button';retry.textContent='重试上传';retry.disabled=!connected;retry.onclick=()=>{if(connected&&!window.inputFilesUploading)call('retryInputFiles');};status.append(retry);}
 const discard=document.createElement('button');discard.className='text-button';discard.textContent='取消待上传文件';discard.onclick=()=>{call('discardInputRetry');delete state.inputUploadFailure;drawInputFiles();syncComposer();persist();};status.append(discard);
}
function drawInputFiles(){
 const files=state.draftUploads||[],list=$('inputFiles');list.hidden=!files.length;list.replaceChildren();
 for(const file of files){const row=document.createElement('div');row.className='input-file';const name=document.createElement('span');name.textContent=file.name;name.title=file.name;const size=document.createElement('small');size.textContent=fileSize(file.size);const remove=document.createElement('button');remove.append(icon('x'));remove.setAttribute('aria-label','移除 '+file.name);remove.onclick=()=>{state.draftUploads=files.filter(f=>f.id!==file.id);call('removeInputFile',file.id);drawInputFiles();syncComposer();persist();};row.append(name,size,remove);list.append(row);}
 $('attachFile').disabled=!connected||window.inputFilesUploading||files.length>=4;
 drawInputUploadStatus();
}
$('attachFile').onclick=()=>{if(!connected){openSettings();return;}call('pickFiles',4-(state.draftUploads||[]).length);};
const inputSync=syncComposer;syncComposer=function(){inputSync();if(state.inputUploadFailure&&($('input').value.trim()||(state.draftUploads||[]).length))$('send').disabled=true;};
const inputSend=send;send=function(...args){if(state.inputUploadFailure&&($('input').value.trim()||(state.draftUploads||[]).length)){toast('文件尚未上传，请先重试上传或取消待上传文件');return;}return inputSend(...args);};$('send').onclick=send;
const inputRender=render;render=function(){if(state.inputUploadFailure&&state.inputUploadFailure.conversationId!==state.conversationId)delete state.inputUploadFailure;inputRender();drawInputFiles();};
const inputStart=startConversation;startConversation=function(...args){if(window.inputFilesUploading||state.inputUploadFailure){toast('请先完成文件上传，或取消待上传文件，再切换会话');return;}return inputStart(...args);};
const inputSwitch=switchConversation;switchConversation=function(...args){if(window.inputFilesUploading||state.inputUploadFailure){toast('请先完成文件上传，或取消待上传文件，再切换会话');return;}return inputSwitch(...args);};
const inputMessage=renderMessage;renderMessage=function(message){
 const node=inputMessage(message);
 if(message.role==='user'&&message.uploadedFiles?.length){const list=document.createElement('div');list.className='sent-files';for(const file of message.uploadedFiles){const label=document.createElement('span');label.textContent=file.name+' · '+fileSize(file.size);list.append(label);}node.insertBefore(list,node.querySelector('.message-time'));}
 return node;
};
const inputReceive=window.receive;window.receive=function(type,data){
 if(type==='inputFilesBusy'){
  if(data.value){if(window.inputFilesUploading&&data.operationId&&inputUploadOperation&&data.operationId!==inputUploadOperation)return;inputUploadOperation=data.operationId||'';}
  else if(data.operationId&&inputUploadOperation&&data.operationId!==inputUploadOperation)return;
  window.inputFilesUploading=!!data.value;inputUploadMessage=data.message||'';if(!data.value&&data.complete)delete state.inputUploadFailure;drawInputFiles();syncComposer();if(!data.value)persist();return;
 }
 if(type==='inputFilesFailed'){
  if(data.operationId&&inputUploadOperation&&data.operationId!==inputUploadOperation)return;
  window.inputFilesUploading=false;inputUploadMessage='';state.inputUploadFailure={message:data.message||'文件上传未完成，消息尚未发送。',retryable:!!data.retryable,conversationId:state.conversationId};drawInputFiles();syncComposer();persist();return;
 }
 if(type==='inputFileReady'){if(data.operationId&&inputUploadOperation&&data.operationId!==inputUploadOperation)return;state.draftUploads=state.draftUploads||[];if(!state.draftUploads.some(f=>f.id===data.id)&&state.draftUploads.length<4)state.draftUploads.push(data);drawInputFiles();syncComposer();persist();return;}
 if(type==='rejected'){
  const message=state.messages.find(m=>m.id===data.messageId);
  if(message?.uploadedFiles?.length)state.draftUploads=[...(state.draftUploads||[]),...message.uploadedFiles].filter((f,i,all)=>all.findIndex(other=>other.id===f.id)===i);
 }
 inputReceive(type,data);if(type==='restore'&&state.inputUploadFailure){state.inputUploadFailure.retryable=false;state.inputUploadFailure.message=state.inputUploadFailure.message.replace(/连接恢复后可重试上传。?$/,'');}
 if(type==='connection'&&window.inputFilesUploading)inputUploadMessage='连接已断开，正在结束文件上传…';
 if(['restore','connected','connection','rejected'].includes(type)){drawInputFiles();syncComposer();}
};
drawInputFiles();
