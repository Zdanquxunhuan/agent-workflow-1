const $ = (id) => document.getElementById(id);
let token = '', kb = null, polling = null, searchGeneration = 0, identityGeneration = 0;
const profileNames = {'dense-v2':'语义检索', 'lexical-v2':'中文关键词与编号', 'hybrid-v2':'混合检索', 'hybrid-rerank-v2':'混合检索 + 模型重排'};
function resetSearch(){ searchGeneration++; $('results').replaceChildren(); $('search-status').textContent=''; $('debug-panel').hidden=true; $('debug-rows').replaceChildren(); $('debug-summary').textContent=''; $('search-button').disabled=false; $('citation-dialog').close(); $('citation-text').textContent=''; }
const states = {QUEUED:'等待处理',PARSING:'解析文档',EMBEDDING:'向量化',INDEXING:'写入索引',SUCCEEDED:'已发布',FAILED:'处理失败',RETRY_WAIT:'等待重试',CANCELLED:'已取消'};
function notice(message) { $('notice').textContent = message; $('notice').hidden = !message; }
async function api(path, options={}) {
  const headers = new Headers(options.headers || {});
  if(token) headers.set('Authorization',`Bearer ${token}`);
  if(options.body && !(options.body instanceof FormData)) headers.set('Content-Type','application/json');
  const response = await fetch(`/api/v1${path}`, {...options, headers});
  const result = await response.json();
  if(!response.ok) throw new Error(`${result.message || '请求失败'} (${result.code || response.status})`);
  return result;
}
async function run(action) { notice(''); try { await action(); } catch(error) { notice(error.message); } }
function node(tag, text, cls) { const e=document.createElement(tag); if(text!==undefined) e.textContent=text; if(cls)e.className=cls; return e; }
function button(text, action) { const e=node('button',text,'quiet'); e.type='button'; e.onclick=()=>run(action); return e; }
async function login() {
  const generation=++identityGeneration;
  resetSearch(); kb=null; $('documents').replaceChildren(); $('kb-list').replaceChildren();
  $('identity').textContent='正在验证身份';
  if(polling)clearInterval(polling);
  const user=await api('/me'); if(generation!==identityGeneration)return; $('identity').textContent=`${user.tenant_id} / ${user.subject}`;
  await loadKBs();
  if(polling) clearInterval(polling);
  polling=setInterval(()=>{if(kb) loadDocuments().catch(()=>{});},4000);
}
async function loadKBs(){
  const generation=identityGeneration, data=await api('/knowledge-bases'); if(generation!==identityGeneration)return; $('kb-list').replaceChildren();
  for(const item of data.items){ const b=button(item.name,async()=>{resetSearch();kb=item;await loadKBs();await loadDocuments();});
    if(item.id===kb?.id)b.classList.add('active'); $('kb-list').append(b); }
  $('kb-title').textContent=kb?.name || '让知识成为可追溯的证据';
  if(!kb)$('documents').textContent='选择或创建一个知识库开始上传';
}
async function loadDocuments(){
  if(!kb)return;
  const generation=identityGeneration, selected=kb.id, data=await api(`/knowledge-bases/${selected}/documents`);
  if(generation!==identityGeneration||selected!==kb?.id)return;
  $('documents').replaceChildren();
  if(!data.items.length){$('documents').textContent='还没有文档，上传第一份资料开始建立知识库';return;}
  for(const doc of data.items){
    const row=node('div',undefined,'doc'), info=node('div',undefined,'doc-info'), actions=node('div',undefined,'doc-actions');
    info.append(node('div',doc.title,'doc-name'),node('div',`${states[doc.state]||doc.state} · ${doc.progress||0}%${doc.error_message?' · '+doc.error_message:''}`,'doc-state'));
    if(doc.state==='FAILED')actions.append(button('重试',async()=>{await api(`/jobs/${doc.job_id}/retry`,{method:'POST'});await loadDocuments();}));
    if(['QUEUED','PARSING','EMBEDDING','INDEXING','RETRY_WAIT'].includes(doc.state))actions.append(button('取消',async()=>{await api(`/jobs/${doc.job_id}/cancel`,{method:'POST'});await loadDocuments();}));
    actions.append(button('删除',async()=>{if(confirm(`删除文档「${doc.title}」？它将立即退出检索。`)){await api(`/documents/${doc.id}`,{method:'DELETE'});await loadDocuments();}}));
    row.append(info,actions);$('documents').append(row);
  }
}
$('login').onclick=()=>run(async()=>{const data=await api('/auth/dev-token',{method:'POST',body:JSON.stringify({tenant_id:$('tenant').value,subject:$('subject').value})});token=data.token;await login();});
$('token-login').onclick=()=>run(async()=>{token=$('token').value.trim();$('token').value='';await login();});
$('create-kb').onsubmit=e=>{e.preventDefault();run(async()=>{kb=await api('/knowledge-bases',{method:'POST',body:JSON.stringify({name:$('kb-name').value})});$('kb-name').value='';await loadKBs();await loadDocuments();});};
$('upload').onsubmit=e=>{e.preventDefault();run(async()=>{if(!kb)throw new Error('请先选择知识库');const file=$('file').files[0];if(!file)throw new Error('请选择文件');if(file.size>10*1024*1024)throw new Error('文件不能超过 10 MiB');
  $('upload-button').disabled=true;try{const form=new FormData();form.append('file',file);await api(`/knowledge-bases/${kb.id}/documents`,{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:form});$('file').value='';await loadDocuments();notice('已接收入库任务，可在处理记录中查看进度。');}finally{$('upload-button').disabled=false;}});};
$('refresh').onclick=()=>run(loadDocuments);
$('search').onsubmit=e=>{e.preventDefault();run(async()=>{if(!kb)throw new Error('请先选择知识库');resetSearch();const generation=searchGeneration;$('search-button').disabled=true;$('results').textContent='正在检索…';
  try{const data=await api($('debug-enabled').checked?'/search/debug':'/search',{method:'POST',body:JSON.stringify({query:$('query').value,knowledge_base_ids:[kb.id],top_k:5,profile:$('profile').value})});
    if(generation!==searchGeneration)return;
    $('results').replaceChildren();
    $('search-status').textContent=`${profileNames[data.retrieval_profile_id]} · ${data.timings_ms.total_ms} ms · ${data.hits.length} 条证据${data.status==='degraded'?' · 已降级：'+data.degraded_components.join('、'):''}`;
    if(!data.hits.length)$('results').textContent='没有找到当前身份可访问的已发布证据。';
    for(const hit of data.hits){const el=node('article',undefined,'hit');el.append(node('h3',hit.title),node('p',hit.text),node('div',`版本 ${hit.document_version_id}${hit.page?' · 第 '+hit.page+' 页':''}`,'meta'),button('查看引用',async()=>{const c=await api(`/citations/${hit.citation.id}`);if(generation!==searchGeneration)return;$('citation-title').textContent=c.title;$('citation-text').textContent=c.text;$('citation-dialog').showModal();}));$('results').append(el);}
    if(data.debug){$('debug-panel').hidden=false;$('debug-panel').open=true;
      $('debug-summary').textContent=JSON.stringify({profile:data.retrieval_profile_id,fingerprint:data.profile_fingerprint,query:data.debug.normalized_query,terms:data.debug.lexical_terms,identifiers:data.debug.identifiers,timings_ms:data.timings_ms,context_tokens:data.context_tokens,parameters:data.debug.parameters},null,2);
      for(const row of data.debug.candidates){const tr=node('tr');tr.append(node('td',`${row.title}\n${row.chunk_id}`),node('td',JSON.stringify(row.channel_ranks)),node('td',`${row.fusion_rank} (${row.fusion_score.toFixed(5)})`),node('td',row.rerank_score==null?'未重排':row.rerank_score.toFixed(4)),node('td',row.selected?'是':'否'));$('debug-rows').append(tr);}}
  }catch(error){if(generation===searchGeneration){$('results').textContent='检索失败，请查看错误提示。';throw error;}}
  finally{if(generation===searchGeneration)$('search-button').disabled=false;}});};
$('grant').onsubmit=e=>{e.preventDefault();run(async()=>{if(!kb)throw new Error('请选择知识库');await api(`/knowledge-bases/${kb.id}/acl`,{method:'PUT',body:JSON.stringify({subject:$('grant-subject').value,role:$('grant-role').value})});notice('权限已更新。');});};
$('revoke').onclick=()=>run(async()=>{if(!kb||!$('grant-subject').value)throw new Error('请选择知识库并填写成员');await api(`/knowledge-bases/${kb.id}/acl/${encodeURIComponent($('grant-subject').value)}`,{method:'DELETE'});notice('权限已撤销。');});
$('close-dialog').onclick=()=>$('citation-dialog').close();
api('/config').then(c=>{$('dev-login').hidden=!c.dev_login_enabled;for(const profile of c.retrieval_profiles){const option=node('option',profileNames[profile]||profile);option.value=profile;$('profile').append(option);}$('profile').value=c.default_retrieval_profile;}).catch(e=>notice(e.message));
fetch('/health/ready').then(r=>{$('service-state').textContent=r.ok?'服务就绪':'模型或存储准备中';}).catch(()=>{$('service-state').textContent='服务暂不可用';});
