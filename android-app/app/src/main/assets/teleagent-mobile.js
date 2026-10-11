'use strict';
/* Mobile answer controls reuse the current card's guarded callbacks and token. */
(()=>{
  const progress=/^(?:暂不回答(?:[，,]\s*下一题|并提交)|下一题|提交(?:回答|答案|全部答案)?)\s*[（(]\d+\/\d+ 已回答[）)]$/;
  let current=null,showComposer=false,visibleToken='';
  const dock=$('answerDock'),go=$('answerContinue'),jump=$('answerQuestion'),toggle=$('answerCompose');
  function locate(){
    for(const message of [...state.messages].reverse()){
      if(message.agent!=='TeleAgent'||message.role!=='assistant')continue;
      for(const interaction of [...(message.interactions||[])].reverse()){
        if(!interaction.desktop||!['pending','submitting'].includes(interaction.state)||interaction.expiresAt<=Date.now())continue;
        const panel=Array.from(findNode(message.id)?.querySelectorAll('.interaction')||[]).find(n=>n.dataset.token===interaction.token);
        if(panel)return {message,interaction,panel};
      }
    }return null;
  }
  function viewQuestion(){
    if(!current)return;
    current.panel.scrollIntoView({block:'start',behavior:'auto'});
  }
  function sync(){
    current=locate();dock.hidden=!current;
    if(!current){$('app').classList.remove('answering','answer-composing');visibleToken='';showComposer=false;return;}
    const {interaction,panel}=current;
    panel.classList.add('teleagent-answer');
    if(visibleToken!==interaction.token){visibleToken=interaction.token;showComposer=false;}
    $('app').classList.add('answering');$('app').classList.toggle('answer-composing',showComposer);
    toggle.textContent=showComposer?'返回回答':'发消息';toggle.setAttribute('aria-expanded',String(showComposer));
    const controls=Array.from(panel.querySelectorAll('.interaction-actions>button'));
    const next=controls.find(button=>progress.test(button.textContent));
    const save=panel.querySelector('.interaction-text-save');
    controls.filter(button=>progress.test(button.textContent)).forEach(button=>button.classList.add('teleagent-progress'));
    if(save)save.classList.add('teleagent-progress');
    const target=save||next;
    const waiting=interaction.state==='submitting';
    const missing=(interaction.inputs||[]).some(field=>field.required&&!String(interaction.textDrafts?.[field.id]??field.value??'').trim());
    const nextLabel=next?.textContent||'';
    const selected=(interaction.options||[]).find(option=>option.selected===true);
    const skipping=!save&&nextLabel.startsWith('暂不回答');
    const count=nextLabel.match(/[（(](\d+\/\d+) 已回答[）)]/);
    $('answerTitle').textContent=waiting?'正在提交回答…':missing?'填写你的回答':selected?'已选择 '+selected.label.split(/\s/)[0]:skipping?'选择一个选项':'回答准备好了';
    $('answerHint').textContent=waiting?'电脑确认后会继续':count?count[1]+' 已回答 · '+(skipping?'也可跳过本题':save?'补充内容会一起提交':'可继续下一步'):'在问题卡片中选择或填写';
    go.textContent=waiting?'提交中…':nextLabel.includes('下一题')?(skipping?'跳过本题':'下一题'):skipping?'跳过并提交':'提交回答';
    go.disabled=waiting||missing||!target||target.disabled;
    go.classList.toggle('skip-answer',skipping);
    go.onclick=()=>{const fresh=locate();if(!fresh||fresh.interaction.token!==interaction.token)return;target.click();sync();};
    jump.disabled=waiting;jump.onclick=viewQuestion;
    const status=panel.querySelector('.interaction-state');
    if(status&&interaction.state==='pending')status.textContent='等待回答 · 剩余 '+Math.max(1,Math.ceil((interaction.expiresAt-Date.now())/60000))+' 分钟';
  }
  toggle.onclick=()=>{showComposer=!showComposer;sync();if(showComposer)$('input').focus();else{$('input').blur();viewQuestion();}};
  $('messages').addEventListener('input',event=>{if(event.target.matches('.interaction-text-input'))sync();});
  function revealFocusedAnswer(){
    const input=document.activeElement;
    if(current&&input?.matches('.interaction-text-input'))requestAnimationFrame(()=>{if(input.isConnected)input.scrollIntoView({block:'center',behavior:'auto'});});
  }
  $('messages').addEventListener('focusin',event=>{if(event.target.matches('.interaction-text-input')&&locate()){showComposer=false;sync();revealFocusedAnswer();}});
  window.addEventListener('resize',revealFocusedAnswer);
  window.visualViewport?.addEventListener('resize',revealFocusedAnswer);
  const extras=renderExtras;
  renderExtras=function(node,message){
    const focused=document.activeElement;
    const field=focused?.matches('.interaction-text-input')&&node.contains(focused)?{token:focused.closest('.interaction').dataset.token,id:focused.dataset.fieldId,start:focused.selectionStart,end:focused.selectionEnd}:null;
    const disclosure=Array.from(node.querySelectorAll('.interaction-detail[open]')).map(n=>n.closest('.interaction').dataset.token);
    for(const panel of node.querySelectorAll('.interaction')){const extra=panel.querySelector('.teleagent-supplement');const interaction=message.interactions?.find(i=>i.token===panel.dataset.token);if(extra&&interaction)interaction.expandSupplement=extra.open;}
    extras(node,message);
    for(const panel of node.querySelectorAll('.interaction')){
      if(disclosure.includes(panel.dataset.token)){const detail=panel.querySelector('.interaction-detail');if(detail)detail.open=true;}
      if(field&&panel.dataset.token===field.token){const input=Array.from(panel.querySelectorAll('.interaction-text-input')).find(n=>n.dataset.fieldId===field.id);if(input&&!input.disabled){input.focus({preventScroll:true});input.setSelectionRange(field.start,field.end);}}
    }
    sync();
  };
  const draw=render;render=function(){draw();sync();};
  const receiveMessage=window.receive;
  window.receive=function(type,data){
    const before=visibleToken;receiveMessage(type,data);sync();
    if(current&&visibleToken!==before&&current.interaction.state==='pending')requestAnimationFrame(()=>{sync();viewQuestion();});
  };
  setInterval(sync,1000);
})();
