/* Version actions always use the last reviewed content revision; conflicts require reload. */
let versionPanel = null, versionEpoch = 0, versionRefresh = 0;
const lifecycleNames = {PROCESSING:'处理中',READY:'可发布',PURGING:'回收中',PURGED:'已回收'};
window.closeVersionPanel = function(){
  versionEpoch++; versionPanel=null; $('version-panel').hidden=true;
  for(const id of ['version-list','version-diff','publication-list','retention-list','compare-from','compare-to','version-notice'])$(id).replaceChildren();
  $('version-upload').reset();
};
function currentPanel(epoch){return versionPanel&&epoch===versionEpoch&&versionPanel.identity===identityGeneration;}
async function versionAction(action){
  const epoch=versionEpoch;
  $('version-notice').textContent='';
  try{await action(epoch);}catch(e){if(currentPanel(epoch))$('version-notice').textContent=e.message;}
}
window.openVersionPanel=async function(doc){
  window.closeVersionPanel();
  versionPanel={id:doc.id,identity:identityGeneration,versions:[],before:null,eventBefore:null};
  $('version-panel').hidden=false;
  await versionAction(()=>reloadVersionPanel());
  $('version-panel').scrollIntoView({behavior:'smooth',block:'start'});
};
async function reloadVersionPanel(){
  const epoch=versionEpoch,id=versionPanel.id,refresh=++versionRefresh;
  const [detail,history,events]=await Promise.all([api(`/documents/${id}`),api(`/documents/${id}/versions`),api(`/documents/${id}/publication-events`)]);
  if(!currentPanel(epoch)||refresh!==versionRefresh)return;
  Object.assign(versionPanel,{detail,versions:history.items,before:history.next_before,eventBefore:events.next_before_revision});
  $('version-title').textContent=detail.title+' · 版本管理';
  $('version-info').textContent=`内容修订号 ${detail.content_revision} · ${detail.active_version_id?'已有生效版本，新版处理中仍可检索':'尚无生效版本'} · 修改后请刷新查看状态`;
  $('version-upload').hidden=!detail.can_update;
  $('version-retention').hidden=!detail.can_manage;
  $('retention-list').replaceChildren();$('retention-submit').hidden=true;
  renderVersions();$('publication-list').replaceChildren();renderEvents(events.items);
}
function renderEvents(items){
  for(const e of items)$('publication-list').append(node('p',`${new Date(e.created_at).toLocaleString()} · ${e.actor} · ${e.action} · 修订 ${e.previous_revision} → ${e.content_revision} · ${e.reason}`,'small'));
  $('publication-more').hidden=!versionPanel.eventBefore;
}
function renderVersions(){
  $('version-list').replaceChildren();$('compare-from').replaceChildren();$('compare-to').replaceChildren();
  const detail=versionPanel.detail;
  for(const v of versionPanel.versions){
    const row=node('article',undefined,'version-row'),info=node('div'),actions=node('div',undefined,'doc-actions');
    info.append(node('h3',`v${v.version_number} · ${v.filename}${v.is_current?' · 当前生效':''}`));
    info.append(node('p',`${lifecycleNames[v.lifecycle]} · ${v.created_by} · ${new Date(v.created_at).toLocaleString()}${v.retention_hold?' · 保留保护':''}`,'muted small'));
    info.append(node('p',v.change_note||'未填写修改说明'));
    if(v.job_state&&v.job_state!=='SUCCEEDED')info.append(node('p',`${states[v.job_state]||v.job_state} · ${v.progress||0}%${v.error_message?' · '+v.error_message:''}`,'small'));
    if(v.publication_note)info.append(node('p','自动发布未执行：生效内容已变化，请比较新版后由管理员确认发布。','version-warning'));
    const retained=!['PURGING','PURGED'].includes(v.lifecycle);
    if(retained)actions.append(button('下载原件',()=>versionAction(epoch=>downloadOriginal(v,epoch))));
    if(detail.can_manage&&retained){
      if(v.lifecycle==='READY'&&!v.is_current){
        actions.append(button(v.first_published_at?'回滚到此版':'发布此版',()=>versionAction(epoch=>activateVersion(v,epoch))));
      }
      actions.append(button(v.retention_hold?'解除保留':'保留此版',()=>versionAction(async epoch=>{
        await api(`/documents/${versionPanel.id}/versions/${v.id}/retention-hold`,{method:'PUT',body:JSON.stringify({enabled:!v.retention_hold})});
        if(currentPanel(epoch))await reloadVersionPanel();
      })));
      if(v.job_state==='FAILED'&&v.lifecycle==='PROCESSING')actions.append(button('重试处理',()=>jobAction(v,'retry')));
      if(['QUEUED','PARSING','EMBEDDING','INDEXING','RETRY_WAIT'].includes(v.job_state))actions.append(button('取消处理',()=>jobAction(v,'cancel')));
    }
    row.append(info,actions);$('version-list').append(row);
    if(v.lifecycle==='READY')for(const id of ['compare-from','compare-to']){
      const option=node('option',`v${v.version_number} · ${v.filename}`);option.value=v.id;$(id).append(option);
    }
  }
  if($('compare-from').options.length>1)$('compare-from').selectedIndex=1;
  $('version-more').hidden=!versionPanel.before;
}
async function jobAction(v,action){await versionAction(async epoch=>{
  await api(`/jobs/${v.job_id}/${action}`,{method:'POST'});if(currentPanel(epoch))await reloadVersionPanel();
});}
async function activateVersion(v,epoch){
  const reason=prompt(`说明${v.first_published_at?'回滚':'发布'} v${v.version_number} 的原因：`);
  if(!reason?.trim())return;
  await api(`/documents/${versionPanel.id}/versions/${v.id}/${v.first_published_at?'rollback':'publish'}`,{
    method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},
    body:JSON.stringify({expected_content_revision:versionPanel.detail.content_revision,reason:reason.trim()})});
  if(currentPanel(epoch)){
    window.resetAnswer?.();
    searchGeneration++;$('search-button').disabled=false;$('results').replaceChildren();$('debug-panel').hidden=true;
    $('debug-rows').replaceChildren();$('debug-summary').textContent='';$('citation-dialog').close();$('citation-text').textContent='';
    $('search-status').textContent='版本已切换，请重新检索最新证据。';
    await reloadVersionPanel();if(currentPanel(epoch))await loadDocuments();
  }
}
async function downloadOriginal(v,epoch){
  const response=await fetch(`/api/v1/documents/${versionPanel.id}/versions/${v.id}/original`,{headers:{Authorization:`Bearer ${token}`}});
  if(!response.ok){const e=await response.json();throw new Error(e.message||'下载失败');}
  const blob=await response.blob();if(!currentPanel(epoch))return;
  const url=URL.createObjectURL(blob),a=node('a');a.href=url;a.download=v.filename;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
}
$('version-close').onclick=window.closeVersionPanel;
$('version-refresh').onclick=()=>versionAction(reloadVersionPanel);
$('version-upload').onsubmit=e=>{e.preventDefault();versionAction(async epoch=>{
  const file=$('version-file').files[0];if(!file)throw new Error('请选择文件');if(file.size>10*1024*1024)throw new Error('文件不能超过 10 MiB');
  const form=new FormData();form.append('file',file);form.append('expected_content_revision',versionPanel.detail.content_revision);
  form.append('change_note',$('version-note').value);form.append('auto_publish',$('version-auto').checked);
  $('version-submit').disabled=true;
  try{await api(`/documents/${versionPanel.id}/versions`,{method:'POST',headers:{'Idempotency-Key':crypto.randomUUID()},body:form});
    if(currentPanel(epoch)){$('version-upload').reset();await reloadVersionPanel();await loadDocuments();}
  }finally{$('version-submit').disabled=false;}
});};
$('version-more').onclick=()=>versionAction(async epoch=>{
  const before=versionPanel.before,refresh=versionRefresh;if(!before)return;
  const result=await api(`/documents/${versionPanel.id}/versions?before=${before}`);
  if(currentPanel(epoch)&&refresh===versionRefresh&&before===versionPanel.before){versionPanel.versions.push(...result.items);versionPanel.before=result.next_before;renderVersions();}
});
$('publication-more').onclick=()=>versionAction(async epoch=>{
  const before=versionPanel.eventBefore,refresh=versionRefresh;if(!before)return;
  const result=await api(`/documents/${versionPanel.id}/publication-events?before_revision=${before}`);
  if(currentPanel(epoch)&&refresh===versionRefresh&&before===versionPanel.eventBefore){versionPanel.eventBefore=result.next_before_revision;renderEvents(result.items);}
});
$('version-compare').onclick=()=>versionAction(async epoch=>{
  const a=$('compare-from').value,b=$('compare-to').value;if(!a||!b)throw new Error('请选择两个就绪版本');
  const result=await api(`/documents/${versionPanel.id}/versions/compare?from_version=${a}&to_version=${b}`);
  if(currentPanel(epoch))$('version-diff').textContent=(result.truncated?'⚠ 内容过长，下面仅展示部分差异。\n':'')+(result.diff||'索引正文没有差异。');
});
$('retention-preview').onclick=()=>versionAction(async epoch=>{
  const result=await api(`/documents/${versionPanel.id}/retention/preview`,{method:'POST'});if(!currentPanel(epoch))return;
  versionPanel.retentionRevision=result.content_revision;$('retention-list').replaceChildren();
  $('retention-list').append(node('p',`至少保留 ${result.retention_days} 天和最近 ${result.keep_latest} 个版本；每次显示最早 ${result.preview_limit} 个未回收版本。`,'small'));
  for(const v of result.items){const label=node('label',undefined,'retention-item'),input=document.createElement('input');input.type='checkbox';input.value=v.id;
    input.disabled=!v.eligible&&v.lifecycle!=='PURGING';label.append(input,node('span',`v${v.version_number} · ${v.filename} · ${v.eligible?'可回收':v.blocked_by.join(' / ')}${v.gc_state?' · '+v.gc_state:''}${v.error_code?' · '+v.error_code:''}`));$('retention-list').append(label);}
  $('retention-submit').hidden=false;
});
$('retention-submit').onclick=()=>versionAction(async epoch=>{
  const ids=[...$('retention-list').querySelectorAll('input:checked')].map(e=>e.value);if(!ids.length||ids.length>20)throw new Error('每次选择 1–20 个版本');
  if(!confirm('确认永久回收所选版本的原件和索引？进入回收后不能再发布或回滚这些版本。'))return;
  await api(`/documents/${versionPanel.id}/retention/purge`,{method:'POST',body:JSON.stringify({expected_content_revision:versionPanel.retentionRevision,version_ids:ids})});
  if(currentPanel(epoch)){await reloadVersionPanel();if(currentPanel(epoch))$('version-notice').textContent='已排队回收；由配置的租户回收进程执行。';}
});
