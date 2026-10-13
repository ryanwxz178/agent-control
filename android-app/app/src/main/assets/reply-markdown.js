'use strict';
/* Offline reply formatting shared by CLI and desktop messages. */
(function(root){
 function createReplyMarkdown(markdownit,highlighter){
  const markdown=markdownit({html:false,linkify:true,breaks:true,typographer:false,highlight:(text,language)=>{
   // Explicit grammars keep streaming updates predictable. Unknown/large code stays intact.
   if(text.length>60000||!language||!highlighter?.getLanguage(language))return '';
   try{return highlighter.highlight(text,{language,ignoreIllegals:true}).value;}catch(_){return '';}
  }});
  markdown.renderer.rules.image=(tokens,index)=>markdown.utils.escapeHtml(tokens[index].content||'图片请从附件查看');
  const defaultLink=markdown.renderer.rules.link_open||((tokens,index,options,env,self)=>self.renderToken(tokens,index,options));
  markdown.renderer.rules.link_open=(tokens,index,options,env,self)=>{
   const token=tokens[index],url=token.attrGet('href');
   try{if(!['http:','https:'].includes(new URL(url).protocol))token.attrSet('href','#');}catch(_){token.attrSet('href','#');}
   token.attrSet('rel','noopener noreferrer');return defaultLink(tokens,index,options,env,self);
  };
  markdown.inline.ruler.before('emphasis','reply_mark',(state,silent)=>{
   if(state.src.slice(state.pos,state.pos+2)!=='==')return false;
   const end=state.src.indexOf('==',state.pos+2),inside=end<0?'':state.src.slice(state.pos+2,end);
   if(!inside||inside.trim()!==inside||inside.includes('\n'))return false;
   if(!silent){
    const token=state.push('reply_mark','mark',0);token.children=[];state.md.inline.parse(inside,state.md,state.env,token.children);
    // An inline mark inside a link must not create a second clickable anchor.
    if(state.linkLevel>0)token.children=token.children.filter(child=>!['link_open','link_close'].includes(child.type));
   }
   state.pos=end+2;return true;
  });
  markdown.renderer.rules.reply_mark=(tokens,index,options,env,self)=>'<mark>'+self.renderInline(tokens[index].children,options,env)+'</mark>';
  markdown.core.ruler.after('inline','reply_tasks',state=>{
   const items=[];
   for(const token of state.tokens){
    if(token.type==='list_item_open')items.push({token,first:true});
    else if(token.type==='list_item_close')items.pop();
    else if(token.type==='inline'&&items.length&&items.at(-1).first){
     const item=items.at(-1);item.first=false;const first=token.children?.[0];
     const match=first?.type==='text'&&/^\[([ xX])\]\s+/.exec(first.content);if(!match)continue;
     first.content=first.content.slice(match[0].length);item.token.attrJoin('class','task-list-item');
     const checkbox=new state.Token('reply_checkbox','input',0);checkbox.meta={checked:match[1].toLowerCase()==='x'};token.children.unshift(checkbox);
    }
   }
  });
  markdown.renderer.rules.reply_checkbox=(tokens,index)=>'<input class="task-checkbox" type="checkbox" disabled aria-label="'+(tokens[index].meta.checked?'已完成':'未完成')+'"'+(tokens[index].meta.checked?' checked':'')+'> ';
  return markdown;
 }
 root.createReplyMarkdown=createReplyMarkdown;
 if(typeof module==='object'&&module.exports)module.exports=createReplyMarkdown;
})(typeof window==='object'?window:globalThis);
