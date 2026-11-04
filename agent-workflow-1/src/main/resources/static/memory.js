'use strict';
const $ = id => document.getElementById(id);
let generation = 0, cursor = '';
const options = {response_style: [['concise','简短'],['detailed','详细']], language: [['zh','中文'],['en','英文']], format: [['paragraph','段落'],['bullets','列表'],['conclusion_first','先给结论']]};
function values(){ $('value').replaceChildren(...options[$('slot').value].map(([value,label]) => new Option(label,value))); }
values(); $('slot').addEventListener('change',values);
$('token').addEventListener('input',()=>{ generation++; cursor=''; $('list').replaceChildren(); $('detail').textContent=''; $('status').textContent=''; });
async function api(path, method='GET', body, key){
  const epoch=generation;
  const headers={Authorization:'Bearer '+$('token').value.trim()};
  if(body!==undefined)headers['Content-Type']='application/json';
  if(key)headers['Idempotency-Key']=key;
  const response=await fetch('/api/v1/memories'+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body)});
  const result=await response.json();
  if(epoch!==generation)throw new Error('身份已变化，请重新加载。');
  if(!response.ok)throw new Error(result.errorCode+': '+result.message);
  return result.data;
}
function run(work){return async()=>{try{await work();}catch(ex){$('status').textContent=ex.message;}};}
function button(label,work){const b=document.createElement('button');b.textContent=label;b.onclick=run(work);return b;}
async function load(after=''){
  $('detail').textContent='';$('list').replaceChildren();
  const rows=await api('?after='+encodeURIComponent(after));
  cursor=rows.length===100?rows[rows.length-1].id:'';
  for(const m of rows){
    const card=document.createElement('article'), title=document.createElement('p');
    title.textContent=`${m.kind} · ${m.slot} · ${m.state} · 版本 ${m.revision} · ${m.id}`;
    card.append(title);
    if(!['DELETED','EXPIRED'].includes(m.state)){
      card.append(button('查看内容',async()=>{ $('detail').textContent=''; const value=await api('/'+m.id);$('detail').textContent=value.content; }));
      if(m.state==='CANDIDATE')card.append(button('查看并确认',async()=>{
        const value=await api('/'+m.id);
        if(confirm('确认记住以下内容？\n\n'+value.content)){await api('/'+m.id+'/confirm','POST',{revision:value.revision});await load();}
      }));
      card.append(button('修改',async()=>{
        const value=await api('/'+m.id);
        const content=prompt('修改记忆（偏好使用原枚举值），保存后需重新确认：',value.content);
        if(content!==null){await api('/'+m.id,'PUT',{revision:value.revision,content,days:Number($('days').value)});await load();}
      }));
      if(m.sourceTaskId)card.append(button('刷新来源授权',async()=>{await api('/'+m.id+'/reauthorize','POST');$('status').textContent='来源授权已刷新；已撤销或变更的证据不会恢复。';}));
    }
    if(m.state!=='DELETED')card.append(button('删除',async()=>{
      if(confirm('删除此记忆？使用它的历史答案也可能停止展示。')){await api('/'+m.id+'?revision='+m.revision,'DELETE');await load();}
    }));
    if(m.kind==='TASK_SUMMARY')card.append(button('重建索引',async()=>{if(confirm('重建会产生新版本，依赖旧版本的历史答案将停止展示。继续？')){await api('/'+m.id+'/reindex','POST');await load();$('status').textContent='已排队重建索引。';}}));
    card.append(button('变更记录',async()=>{const events=await api('/'+m.id+'/events');$('detail').textContent=JSON.stringify(events,null,2);}));
    $('list').append(card);
  }
  $('status').textContent=rows.length+' 条；内容读取时会重新验证来源权限。';
}
$('load').onclick=run(()=>load());$('next').onclick=run(async()=>{if(cursor)await load(cursor);});
$('jobs').onclick=run(async()=>{$('detail').textContent='';$('detail').textContent=JSON.stringify(await api('/jobs'),null,2);});
$('settings').onclick=run(async()=>{$('detail').textContent=JSON.stringify(await api('/settings'),null,2);});
$('optin').onclick=run(async()=>{if(confirm('允许已完成任务在后台提取候选摘要？内容仍需你确认后才会用于回答。'))$('detail').textContent=JSON.stringify(await api('/settings','PUT',{automaticExtraction:true}),null,2);});
$('optout').onclick=run(async()=>{$('detail').textContent=JSON.stringify(await api('/settings','PUT',{automaticExtraction:false}),null,2);});
$('create').onclick=run(async()=>{await api('','POST',{slot:$('slot').value,value:$('value').value,days:Number($('days').value)},crypto.randomUUID());await load();});
$('extract').onclick=run(async()=>{const result=await api('/extractions','POST',{taskId:$('task').value.trim()});$('status').textContent='提取已排队：'+result.jobId;});
