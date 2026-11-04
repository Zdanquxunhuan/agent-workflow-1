'use strict';
const $ = id => document.getElementById(id);
let current = null;
let sending = false;
let generation = 0;
let activeRun = null;
let streamAbort = null;
async function runtimeApi(path, method='GET', body, key, token=$('token').value.trim(), signal) {
  const headers={Authorization:'Bearer '+token};
  if(body!==undefined) headers['Content-Type']='application/json';
  if(key) headers['Idempotency-Key']=key;
  const response=await fetch(path,{method,headers,signal,body:body===undefined?undefined:JSON.stringify(body)});
  const value=await response.json();
  if(!response.ok) throw new Error(value.errorCode+': '+value.message);
  return value.data;
}
async function observeRun(run, epoch, token, timing) {
  const controller=new AbortController();streamAbort=controller;
  const timer=setTimeout(()=>controller.abort(),Math.max(1000,Math.min(130000,run.deadline_at-Date.now()+10000)));
  let cursor='0',reconnects=0;
  const names={queued:'等待处理',started:'开始处理',context_loading:'读取上下文',model_started:'正在生成',model_finished:'模型调用完成',
    tool_started:'正在查询工具',tool_finished:'工具查询完成',answer_validating:'正在核对答案',cancel_requested:'正在停止'};
  const terminal=value=>!['QUEUED','RUNNING'].includes(value.status);
  try {
    while(epoch===generation && !controller.signal.aborted) {
      if(terminal(run))return run;
      if(reconnects++<3) {
        try {
          const response=await fetch('/api/v1/agent/runs/'+run.id+'/events',{
            headers:{Authorization:'Bearer '+token,'Last-Event-ID':cursor},signal:controller.signal});
          if(!response.ok || !response.headers.get('content-type')?.includes('text/event-stream'))throw new Error('进度连接不可用');
          const reader=response.body.getReader(), decoder=new TextDecoder();let buffer='';
          try {
            while(epoch===generation) {
              const chunk=await reader.read();if(chunk.done)break;
              buffer+=decoder.decode(chunk.value,{stream:true}).replace(/\r/g,'');
              if(buffer.length>65536)throw new Error('进度数据超出限制');
              let split;
              while((split=buffer.indexOf('\n\n'))>=0) {
                const block=buffer.slice(0,split);buffer=buffer.slice(split+2);
                let kind='',data='';
                for(const line of block.split('\n')) {
                  if(line.startsWith('id:'))cursor=line.slice(3).trim();
                  if(line.startsWith('event:'))kind=line.slice(6).trim();
                  if(line.startsWith('data:'))data+=line.slice(5).trim();
                }
                if(epoch!==generation)break;
                if(kind && timing.firstProgress===null)timing.firstProgress=performance.now()-timing.started;
                if(names[kind])$('status').textContent=names[kind];
                if((kind==='terminal'||kind==='snapshot') && data) {
                  const value=JSON.parse(data),state=value.data||value;
                  if(state.status)run.status=state.status;
                }
              }
              if(terminal(run))break;
            }
          } finally {await reader.cancel().catch(()=>{});}
        } catch(ex) {if(controller.signal.aborted || epoch!==generation)throw ex;}
      } else await new Promise(resolve=>setTimeout(resolve,1000));
      if(epoch!==generation)return null;
      run=await runtimeApi('/api/v1/agent/runs/'+run.id,'GET',undefined,undefined,token,controller.signal);
    }
    return null;
  } finally {clearTimeout(timer);if(streamAbort===controller)streamAbort=null;controller.abort();}
}
async function api(path, method = 'GET', body, key) {
  const headers = {Authorization: 'Bearer ' + $('token').value.trim()};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (key) headers['Idempotency-Key'] = key;
  const response = await fetch('/api/v1/conversations' + path, {method, headers, body: body === undefined ? undefined : JSON.stringify(body)});
  const result = await response.json();
  if (!response.ok) throw new Error(result.errorCode + ': ' + result.message);
  return result.data;
}
function render(value) {
  current = value; $('history').replaceChildren();
  for (const turn of value.turns) {
    const article = document.createElement('article');
    article.textContent = '你：' + turn.message + '\n\n助手：' + (turn.answer || (turn.status === 'RUNNING' ? '处理中…' : turn.errorCode || ''));
    $('history').append(article);
  }
  $('status').textContent = '会话 ' + value.id + ' · 版本 ' + value.revision;
}
async function list() {
  const epoch = generation;
  const values = await api('');
  if (epoch !== generation) return [];
  $('conversations').replaceChildren(new Option('请选择', ''));
  for (const value of values) $('conversations').add(new Option(value.id, value.id));
  if (current) $('conversations').value = current.id;
  return values;
}
async function refresh() {
  streamAbort?.abort();activeRun=null;
  const id = $('conversations').value;
  if (!id) return;
  const version = ++generation;
  // Clear previously rendered restricted content before attempting reauthorization.
  current = null; $('history').replaceChildren();
  const value = await api('/' + encodeURIComponent(id));
  if (version === generation) render(value);
}
function action(id, work) { $(id).addEventListener('click', () => work().catch(ex => { $('status').textContent = ex.message; })); }
action('load', list);
action('create', async () => { const epoch = ++generation; const value = await api('', 'POST'); if (epoch === generation) { render(value); await list(); } });
action('refresh', refresh);
$('conversations').addEventListener('change', () => refresh().catch(ex => { $('status').textContent = ex.message; }));
$('token').addEventListener('input', () => { ++generation; current = null; activeRun=null;streamAbort?.abort();$('usage').textContent=''; $('history').replaceChildren(); $('conversations').replaceChildren(); });
action('cancel', async () => {
  if(activeRun && activeRun.epoch===generation) {
    await runtimeApi('/api/v1/agent/runs/'+activeRun.id+'/cancel','POST');
    $('status').textContent='已请求停止，正在等待执行链路收尾。';return;
  }
  const id = $('conversations').value;
  const features=await runtimeApi('/api/v1/agent/runtime');
  if(features.enabled) {
    const runs=await runtimeApi('/api/v1/agent/runs?active=true');
    const matching=runs.filter(r=>r.conversation_id===id);
    if(matching.length) {
      for(const run of matching)await runtimeApi('/api/v1/agent/runs/'+run.id+'/cancel','POST');
      $('status').textContent='已请求停止，稍后刷新会话。';return;
    }
  }
  const value = (await list()).find(v => v.id === id);
  if (!value) return;
  await api('/' + id + '/cancel', 'POST', {revision: value.revision}); await refresh();
});
action('remove', async () => {
  if (!current || !confirm('删除此会话？独立任务审计仍按原规则保留。')) return;
  await api('/' + current.id + '?revision=' + current.revision, 'DELETE');
  ++generation; current = null; $('history').replaceChildren(); await list();
});
$('form').addEventListener('submit', async event => {
  event.preventDefault(); if (!current || sending) return;
  const selected = current, epoch = generation; sending = true; $('send').disabled = true;
  const token=$('token').value.trim();
  const timing={started:performance.now(),firstProgress:null};
  try {
    $('status').textContent = '正在查询…';$('usage').textContent='';
    const features=await runtimeApi('/api/v1/agent/runtime','GET',undefined,undefined,token);
    if(epoch!==generation)return;
    if(features.enabled) {
      const key=crypto.randomUUID();
      let run=await runtimeApi('/api/v1/agent/runs','POST',{conversationId:selected.id,revision:selected.revision,message:$('message').value},key,token);
      if(epoch!==generation)return;
      activeRun={id:run.id,epoch};
      run=await observeRun(run,epoch,token,timing);
      if(epoch!==generation || !run)return;
      if(run.status!=='SUCCEEDED') {
        $('usage').textContent='已记账费用估算 '+(run.accounted_micros/1000000).toFixed(6)+' '+run.currency
          +' · 待结算预留 '+(run.reserved_micros/1000000).toFixed(6)+' '+run.currency+'；停止本地等待不代表供应商没有计费。';
        throw new Error((run.error_code||run.status)+'；执行已停止');
      }
      // Final text is retrieved through the normal source/permission checks, never from the progress stream.
      await runtimeApi('/api/v1/agent/runs/'+run.id+'/result','GET',undefined,undefined,token);
      if(epoch!==generation)return;
      $('message').value='';await refresh();
      if(current?.id===selected.id && $('token').value.trim()===token)
        $('usage').textContent='模型尝试 '+run.attempt_count+' 次 · 计入预算的费用估算 '+(run.accounted_micros/1000000).toFixed(6)+' '+run.currency
          +' · 首次进度 '+(timing.firstProgress===null?'未采集':Math.round(timing.firstProgress)+'ms')+' · 校验后答案 '+Math.round(performance.now()-timing.started)+'ms'
          +' · 用量未知时保守计入预留额度';
    } else {
      const result = await api('/' + selected.id + '/messages', 'POST', {revision: selected.revision, message: $('message').value}, crypto.randomUUID());
      if (epoch === generation) { $('message').value = ''; await refresh(); $('status').textContent += ' · 历史裁剪 ' + result.omittedTurns + ' 轮'; }
    }
  } catch (ex) { if (epoch === generation) $('status').textContent = ex.message + '；请刷新后继续。'; }
  finally { activeRun=null;sending = false; $('send').disabled = false; }
});
