'use strict';
const $=id=>document.getElementById(id);
let run=null,epoch=0,observer=null,pending=null,busy=false;
const active=r=>r && ['QUEUED','RUNNING'].includes(r.status);
const names={QUEUED:'等待执行',RUNNING:'正在查询',WAITING_INPUT:'等待你补充信息',SUCCEEDED:'查询完成',FAILED:'查询已停止',CANCELLED:'已取消',EXPIRED:'已到期',INTERRUPTED:'执行中断'};
async function api(path,method='GET',body,key,signal) {
  const token=$('token').value.trim();
  const headers={Authorization:'Bearer '+token};
  if(body!==undefined)headers['Content-Type']='application/json';
  if(key)headers['Idempotency-Key']=key;
  const response=await fetch('/api/v1/agent/'+path,{method,headers,body:body===undefined?undefined:JSON.stringify(body),signal,cache:'no-store'});
  const value=await response.json();
  if(!response.ok)throw new Error(value.errorCode+': '+value.message);
  return value.data;
}
function clearView() {
  observer?.abort();run=null;pending=null;$('retry').hidden=true;$('answer').textContent='';$('answer').hidden=true;
  $('usage').textContent='';$('plans').textContent='尚未开始';$('prompt').textContent='你的售后问题';$('message').value='';$('send').disabled=false;
}
function render(value) {
  run=value;
  $('status').textContent=(names[run.status]||run.status)+' · '+run.id+(run.error_code?' · '+run.error_code:'');
  $('usage').textContent='累计模型尝试 '+run.attempt_count+' 次 · 工具调用 '+run.tool_count+' 次 · 自动重规划 '+run.replan_count+'/'+run.max_replans
    +' · 已记账费用估算 '+(run.accounted_micros/1000000).toFixed(6)+' '+run.currency+' · 待结算预留 '+(run.reserved_micros/1000000).toFixed(6);
  $('prompt').textContent=run.status==='WAITING_INPUT'?run.question:'本次查询已提交；可选择新查询';
  $('send').disabled=run.status!=='WAITING_INPUT';
}
async function details(id,version,signal) {
  const plans=await api('dialogues/'+id+'/plans','GET',undefined,undefined,signal);
  if(version!==epoch)return;
  const labels={USER_REQUEST:'收到查询',USER_INPUT:'补充信息后重新校验',MISSING_INFORMATION:'需要补充信息',NO_EVIDENCE:'没有授权知识证据',NOT_FOUND:'请核对业务编号',READ_ONLY:'只读查询',VERIFY_CITATIONS:'校验引用',OK:'已取得结果'};
  $('plans').textContent=plans.map(event=>{
    const data=JSON.parse(event.payload_json);
    return new Date(event.created_at).toLocaleTimeString()+' · 计划 v'+(data.version||'—')+' · '
      +(labels[data.reason]||data.decision||event.event_type)+(data.tool?' · '+data.tool:'')
      +(data.failedTool?' · '+data.failedTool:'')+(data.field?' · 待补 '+data.field:'')+(data.code?' · '+data.code:'');
  }).join('\n');
  if(run?.status==='SUCCEEDED') {
    // Clear stale text first; the result endpoint reauthorizes sources on every read.
    $('answer').textContent='';$('answer').hidden=true;
    const result=await api('runs/'+id+'/result','GET',undefined,undefined,signal);
    if(version===epoch){$('answer').textContent=result.answer;$('answer').hidden=false;}
  }
}
async function observe(id,version) {
  observer?.abort();const controller=new AbortController();observer=controller;
  const timer=setTimeout(()=>controller.abort(),130000);
  try {
    do {
      const value=await api('runs/'+id,'GET',undefined,undefined,controller.signal);
      if(version!==epoch)return;
      if(!value.dialogue_enabled)throw new Error('请选择澄清查询');
      render(value);await details(id,version,controller.signal);
      if(!active(value))return; // No polling/connection is held while waiting for user input.
      await new Promise(resolve=>setTimeout(resolve,1000));
    }while(version===epoch);
  }finally{clearTimeout(timer);controller.abort();if(observer===controller)observer=null;}
}
function action(id,work){$(id).addEventListener('click',async()=>{const version=epoch;try{await work();}catch(ex){if(version===epoch)$('status').textContent=ex.message+'；可刷新当前查询。';}});}
async function submitPending() {
  if(!pending || busy)return;busy=true;$('send').disabled=true;$('retry').hidden=true;
  const request=pending,version=epoch;
  try {
    const value=await api(request.path,'POST',request.body,request.key);
    if(version!==epoch)return;
    pending=null;$('message').value='';render(value);
    await observe(value.id,version);
  }catch(ex){if(version===epoch){$('status').textContent=ex.message;$('retry').hidden=!pending;}}
  finally{busy=false;if(version===epoch)$('send').disabled=!!pending || (run && run.status!=='WAITING_INPUT');}
}
$('form').addEventListener('submit',event=>{
  event.preventDefault();if(busy || pending || run && run.status!=='WAITING_INPUT')return;
  const value=$('message').value.trim();if(!value)return;
  pending=run?{path:'dialogues/'+run.id+'/inputs',body:{revision:run.dialogue_revision,field:run.clarification_field,value},key:crypto.randomUUID()}
    :{path:'dialogues',body:{message:value},key:crypto.randomUUID()};
  submitPending();
});
action('retry',submitPending);
action('list',async()=>{
  const version=epoch,values=await api('runs');if(version!==epoch)return;
  $('runs').replaceChildren(new Option('请选择',''));
  for(const value of values.filter(r=>r.dialogue_enabled))$('runs').add(new Option(value.id+' · '+(names[value.status]||value.status),value.id));
  if(run)$('runs').value=run.id;
});
action('refresh',async()=>{
  const id=run?.id||$('runs').value;if(!id)return;
  const version=++epoch;clearView();
  try{await observe(id,version);}catch(ex){if(version===epoch)$('status').textContent=ex.message+'；请再次刷新。';}
});
$('runs').addEventListener('change',async()=>{
  const id=$('runs').value,version=++epoch;clearView();
  if(id)try{await observe(id,version);}catch(ex){if(version===epoch)$('status').textContent=ex.message;}
});
action('cancel',async()=>{
  if(!run)return;const id=run.id,version=epoch;
  await api('runs/'+id+'/cancel','POST');if(version===epoch)await observe(id,version);
});
action('new',()=>{++epoch;clearView();$('status').textContent='请填写新问题。';});
$('token').addEventListener('input',()=>{++epoch;clearView();$('runs').replaceChildren(new Option('请选择',''));$('status').textContent='身份已更新，请重新读取查询。';});
