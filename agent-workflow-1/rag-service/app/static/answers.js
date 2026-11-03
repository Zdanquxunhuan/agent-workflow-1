/* Single-turn answers: never replay a response across identities, libraries or publications. */
let answerEpoch=0, answerAbort=null, answerEnabled=false;
window.resetAnswer=function(clearQuestion=true){
  answerEpoch++;answerAbort?.abort();answerAbort=null;
  for(const id of ['answer-text','answer-citations','answer-meta','answer-feedback','answer-status'])$(id).replaceChildren();
  if(clearQuestion)$('answer-query').value='';
  $('answer-submit').disabled=!answerEnabled;
};
window.setAnswerAvailability=function(enabled){
  answerEnabled=enabled;$('answer-submit').disabled=!enabled;
  $('answer-availability').textContent=enabled?'基于当前知识库的可见资料回答，结论附原文引用。':'问答模型尚未启用，请先配置 DeepSeek；文档检索仍可使用。';
};
function currentAnswer(epoch,identity,library){return epoch===answerEpoch&&identity===identityGeneration&&library===kb?.id;}
$('answer-form').onsubmit=async e=>{
  e.preventDefault();if(!kb){notice('请先选择知识库');return;}
  const query=$('answer-query').value.trim();if(!query){notice('请输入问题');return;}
  window.resetAnswer(false);
  const epoch=answerEpoch,identity=identityGeneration,library=kb.id;
  const active=()=>currentAnswer(epoch,identity,library);
  answerAbort=new AbortController();$('answer-submit').disabled=true;
  $('answer-status').textContent='正在检索、生成并核对引用…';
  try{
    const result=await api('/answers',{method:'POST',signal:answerAbort.signal,
      body:JSON.stringify({query,knowledge_base_ids:[library],top_k:5,profile:$('profile').value})});
    if(!active())return;
    $('answer-status').textContent={answered:'已生成有引用的回答',insufficient_evidence:'证据不足，暂不作答',conflicting_evidence:'资料存在分歧，请核对适用条件'}[result.status];
    $('answer-text').textContent=result.answer;
    $('answer-meta').textContent=`${result.model||'未调用回答模型'} · ${result.timings_ms.total_ms} ms · ${result.usage.source==='provider'?`输入 ${result.usage.prompt_tokens} / 输出 ${result.usage.completion_tokens} tokens`:'模型用量未提供'}${result.degraded_components.length?' · 检索降级：'+result.degraded_components.join('、'):''}`;
    for(const citation of result.citations){
      const card=node('article',undefined,'answer-source');
      card.append(node('h3',`[${citation.id}] ${citation.title}`),node('p',citation.quote),
        node('div',`版本 ${citation.document_version_id}${citation.page?' · 第 '+citation.page+' 页':''}`,'meta'),
        button('查看引用原文',async()=>{
          const source=await api(`/citations/${citation.chunk_id}`);if(!active())return;
          $('citation-title').textContent=source.title+(source.status==='historical'?' · 历史版本（非当前生效）':'');
          $('citation-text').textContent=source.text;$('citation-dialog').showModal();
        }));
      $('answer-citations').append(card);
    }
    const feedbackStatus=node('span',undefined,'small');
    for(const [label,rating] of [['有帮助','helpful'],['无帮助','unhelpful']]){
      $('answer-feedback').append(button(label,async()=>{
        await api(`/answers/${result.answer_id}/feedback`,{method:'POST',body:JSON.stringify({rating})});
        if(active())feedbackStatus.textContent='反馈已保存';
      }));
    }
    $('answer-feedback').append(feedbackStatus);
  }catch(error){
    if(active()&&error.name!=='AbortError')$('answer-status').textContent='问答未完成：'+error.message;
  }finally{if(active()){$('answer-submit').disabled=!answerEnabled;answerAbort=null;}}
};
api('/config').then(c=>window.setAnswerAvailability(c.answers_enabled)).catch(()=>window.setAnswerAvailability(false));
