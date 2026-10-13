const assert=require('node:assert/strict'),path=require('node:path');
const assets=path.resolve(__dirname,'../app/src/main/assets');
const md=require(path.join(assets,'reply-markdown.js'))(require(path.join(assets,'markdown-it.min.js')),require(path.join(assets,'highlight.min.js')));
const source='# 回复标题\n\n**重点**、*斜体*、~~删除~~、==标记重点== 与 `inline`。\n\n- **卖家**：完整正文不能消失\n  - 嵌套正文\n\n1. 有序列表\n2. 第二项\n\n- [x] 完成任务\n- [ ] 保留待办\n\n> 引用 **重点**\n\n| 字段 | 值 |\n| --- | ---: |\n| 价格 | 6000 |\n\n```js\nconst value = "保留完整代码";\n```\n\n---';
const html=md.render(source);
for(const tag of ['h1','strong','em','s','mark','ul','ol','blockquote','table','pre','hr'])assert.ok(html.includes('<'+tag),'renders '+tag);
assert.ok(html.includes('<s>删除</s>'));assert.ok(html.includes('完整正文不能消失'));assert.ok(html.includes('嵌套正文'));
assert.ok(html.includes('hljs-keyword'));assert.ok(html.includes('hljs-string'));
assert.match(html,/type="checkbox" disabled[^>]+checked/);assert.equal((html.match(/class="task-checkbox"/g)||[]).length,2);
assert.ok(md.render('==**标记中的粗体**==').includes('<mark><strong>'));
for(const nested of ['[outer ==[inner](https://b.example)==](https://a.example)','[==https://b.example==](https://a.example)']){
 assert.equal((md.render(nested).match(/<a /g)||[]).length,1,'highlighted text cannot create nested links');
}
assert.ok(!md.render('`==原文==`').includes('<mark>'));
assert.ok(!md.render('== 不应标记 ==').includes('<mark>'));
assert.ok(!md.render('未闭合 ==普通文本').includes('<mark>'));
const hostile=md.render('<img src=x onerror=alert(1)>\n\n[危险](javascript:alert(1))\n\n[附件](file:///C:/private)\n\n[普通链接](https://example.com)\n\n![远程图片](https://example.com/private.jpg)\n\n```html\n<script>alert("x")</script>\n```');
assert.ok(!hostile.includes('<img'));assert.ok(!hostile.includes('<script>'));assert.ok(!hostile.includes('href="javascript:'));assert.ok(!hostile.includes('href="file:'));assert.ok(hostile.includes('href="https://example.com"'));assert.ok(hostile.includes('noopener noreferrer'));
assert.ok(md.render('```unknown\n<script>& 完整文字\n```').includes('&lt;script&gt;&amp; 完整文字'));
const big='a'.repeat(60001);assert.ok(md.render('```js\n'+big+'\n```').includes(big),'large code stays intact without expensive highlighting');
for(const end of [5,27,source.length])assert.doesNotThrow(()=>md.render(source.slice(0,end)),'partial streaming content renders');
console.log('reply-markdown: formatting, code, completeness, streaming and escaping passed');
