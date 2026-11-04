'use strict';
const $ = id => document.getElementById(id);
let current = null;
let sending = false;
let generation = 0;
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
$('token').addEventListener('input', () => { ++generation; current = null; $('history').replaceChildren(); $('conversations').replaceChildren(); });
action('cancel', async () => {
  const id = $('conversations').value;
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
  try {
    $('status').textContent = '正在查询…';
    const result = await api('/' + selected.id + '/messages', 'POST', {revision: selected.revision, message: $('message').value}, crypto.randomUUID());
    if (epoch === generation) { $('message').value = ''; await refresh(); $('status').textContent += ' · 历史裁剪 ' + result.omittedTurns + ' 轮'; }
  } catch (ex) { if (epoch === generation) $('status').textContent = ex.message + '；请刷新后继续。'; }
  finally { sending = false; $('send').disabled = false; }
});
