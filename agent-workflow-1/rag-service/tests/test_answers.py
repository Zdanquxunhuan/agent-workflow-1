"""Small V4 suite: short synthetic policies, real isolated PG/S3, explicit model doubles."""
import asyncio
import json
import os
from uuid import uuid4

import httpx
import pytest
from pydantic import SecretStr

from app import answer_provider, worker
from app.answer_contracts import ModelAnswer, Usage
from app.answer_provider import Completion
from app.config import settings
from app.db import transaction
from app.errors import RagError
from test_integration import client, headers, setup_document

pytestmark = pytest.mark.skipif(os.environ.get('RAG_INTEGRATION') != '1', reason='isolated PG/S3 required')


@pytest.fixture(autouse=True)
def enabled(monkeypatch):
    cfg = settings()
    monkeypatch.setattr(cfg, 'answer_enabled', True)
    monkeypatch.setattr(cfg, 'answer_allowed_tenants', ['demo'])
    monkeypatch.setattr(cfg, 'answer_api_key', SecretStr('test-only-no-real-key'))
    monkeypatch.setattr(cfg, 'answer_requests_per_minute', 1000)


def policy(client):
    auth, kb, doc, _ = setup_document(client)
    worker.ingest('demo', doc['job_id'])
    return auth, kb, doc


def ask(client, auth, kb):
    return client.post('/api/v1/answers', headers=auth,
                       json={'query': '已发货订单退款谁审批？', 'knowledge_base_ids': [kb]})


def generated(messages, status='answered'):
    data = json.loads(messages[1]['content'])
    source = data['SOURCES'][0]
    return Completion(ModelAnswer(status=status, claims=[] if status == 'insufficient_evidence' else [
        {'text': '已发货订单退款需要主管审批。',
         'evidence': [{'source_id': source['source_id'], 'quote': source['text']}]}]),
        Usage(prompt_tokens=123, completion_tokens=45, source='provider'))


def test_answer_citations_feedback_and_metadata_privacy(client, monkeypatch):
    auth, kb, doc = policy(client)
    async def complete(_, messages, deadline):
        assert 'tools' not in json.loads(messages[1]['content'])
        return generated(messages)
    monkeypatch.setattr(answer_provider, 'complete', complete)
    response = ask(client, auth, kb)
    assert response.status_code == 200, response.text
    result = response.json()
    assert result['status'] == 'answered' and '[C1]' in result['answer']
    citation = result['citations'][0]
    assert citation['document_version_id'] == doc['version_id'] and citation['content_revision'] == 1
    assert client.get(citation['url'], headers=auth).json()['text'] == citation['quote']
    feedback_url = f"/api/v1/answers/{result['answer_id']}/feedback"
    for rating in ('helpful', 'unhelpful'):
        assert client.post(feedback_url, headers=auth, json={'rating': rating}).status_code == 200
    assert client.post(feedback_url, headers=headers(client, 'alice'), json={'rating': 'helpful'}).status_code == 404
    assert client.post(feedback_url, headers=headers(client, tenant='other'), json={'rating': 'helpful'}).status_code == 404
    with transaction('demo') as conn:
        run = conn.execute('SELECT * FROM answer_runs WHERE id=%s', (result['answer_id'],)).fetchone()
        assert run['state'] == 'answered' and run['prompt_tokens'] == 123
        assert '主管审批' not in json.dumps(run, default=str, ensure_ascii=False)
        assert conn.execute('SELECT count(*) AS n FROM answer_feedback WHERE answer_id=%s',
                            (result['answer_id'],)).fetchone()['n'] == 1
    client.put(f"/api/v1/documents/{doc['document_id']}/acl", headers=auth,
               json={'subjects': ['bob'], 'expected_revision': 1})
    assert client.post(feedback_url, headers=auth, json={'rating': 'helpful'}).status_code == 404


def test_no_evidence_skips_model_and_disabled_provider_is_distinct(client, monkeypatch):
    auth = headers(client)
    kb = client.post('/api/v1/knowledge-bases', headers=auth, json={'name': 'empty-' + str(uuid4())}).json()['id']
    async def forbidden(*args):
        raise AssertionError('No model call without evidence')
    monkeypatch.setattr(answer_provider, 'complete', forbidden)
    result = ask(client, auth, kb)
    assert result.status_code == 200, result.text
    assert result.json()['reason'] == 'no_retrieved_evidence' and result.json()['model'] is None
    monkeypatch.setattr(settings(), 'answer_enabled', False)
    assert ask(client, auth, kb).json()['code'] == 'ANSWERS_DISABLED'
    assert ask(client, headers(client, tenant='other'), kb).status_code == 404


@pytest.mark.parametrize('mode', ['refusal', 'bad_citation'])
def test_model_refusal_and_invalid_citation_are_distinct(client, monkeypatch, mode):
    auth, kb, _ = policy(client)
    async def complete(_, messages, deadline):
        result = generated(messages, 'insufficient_evidence' if mode == 'refusal' else 'answered')
        if mode == 'bad_citation':
            result.answer.claims[0].evidence[0].quote = '无需任何审批可以退款'
        return result
    monkeypatch.setattr(answer_provider, 'complete', complete)
    result = ask(client, auth, kb)
    if mode == 'refusal':
        assert result.status_code == 200 and result.json()['reason'] == 'model_insufficient_evidence'
        assert not result.json()['citations']
    else:
        assert result.status_code == 502 and result.json()['code'] == 'ANSWER_INVALID_CITATION'
        assert '无需任何审批' not in result.text


@pytest.mark.parametrize('change', ['acl', 'revision'])
def test_inflight_change_discards_complete_answer(client, monkeypatch, change):
    auth, kb, doc = policy(client)
    async def complete(_, messages, deadline):
        with transaction('demo') as conn:
            if change == 'acl':
                conn.execute("UPDATE documents SET allowed_subjects=ARRAY['bob'] WHERE id=%s", (doc['document_id'],))
            else:
                conn.execute('UPDATE documents SET content_revision=content_revision+1 WHERE id=%s', (doc['document_id'],))
        return generated(messages)
    monkeypatch.setattr(answer_provider, 'complete', complete)
    result = ask(client, auth, kb)
    assert result.status_code == 409 and result.json()['code'] == 'ANSWER_EVIDENCE_CHANGED'
    assert '主管审批' not in result.text


def test_provider_wire_contract_timeout_and_bad_json():
    messages = answer_provider.messages_for('谁审批？', [{'source_id': 'S1', 'title': '政策', 'text': '主管审批'}])
    async def run():
        async def valid(request):
            body = json.loads(request.content)
            assert body['stream'] is False and body['response_format'] == {'type': 'json_object'}
            assert body['thinking'] == {'type': 'disabled'} and body['max_tokens'] == 1200
            assert not body.get('tools')
            return httpx.Response(200, json={'choices': [{'finish_reason': 'stop', 'message': {
                'content': generated(messages).answer.model_dump_json()}}],
                'usage': {'prompt_tokens': 17, 'completion_tokens': 9}})
        import time
        async with httpx.AsyncClient(transport=httpx.MockTransport(valid)) as transport:
            result = await answer_provider.complete(transport, messages, time.monotonic() + 5)
            assert result.usage.prompt_tokens == 17
        async def timeout(request):
            raise httpx.ReadTimeout('simulated')
        async with httpx.AsyncClient(transport=httpx.MockTransport(timeout)) as transport:
            with pytest.raises(RagError) as caught:
                await answer_provider.complete(transport, messages, time.monotonic() + 5)
            assert caught.value.code == 'ANSWER_MODEL_TIMEOUT'
        async def exhausted(request):
            return httpx.Response(402, json={'error': {'message': 'Insufficient Balance'}})
        async with httpx.AsyncClient(transport=httpx.MockTransport(exhausted)) as transport:
            with pytest.raises(RagError) as caught:
                await answer_provider.complete(transport, messages, time.monotonic() + 5)
            assert caught.value.code == 'ANSWER_PROVIDER_QUOTA' and not caught.value.retryable
        for content in ('', '{bad-json', '{"status":"answered","claims":[]}'):
            with pytest.raises(RagError) as caught:
                answer_provider.parse_completion(json.dumps({'choices': [{'finish_reason': 'stop',
                    'message': {'content': content}}]}))
            assert caught.value.code == 'ANSWER_INVALID_OUTPUT'
    asyncio.run(run())


def test_timeout_releases_lease_and_tenant_budget_blocks_next_call(client, monkeypatch):
    auth, kb, _ = policy(client)
    async def timeout(*args):
        raise RagError('ANSWER_MODEL_TIMEOUT', 'Answer model timed out', 504, True)
    monkeypatch.setattr(answer_provider, 'complete', timeout)
    result = ask(client, auth, kb)
    assert result.status_code == 504 and result.json()['code'] == 'ANSWER_MODEL_TIMEOUT'
    with transaction('demo') as conn:
        row = conn.execute("SELECT state,error_code,lease_until<=clock_timestamp() AS released FROM answer_runs "
                           "WHERE kb_ids @> %s::uuid[] ORDER BY created_at DESC LIMIT 1", ([kb],)).fetchone()
        assert row['state'] == 'FAILED' and row['released']
    monkeypatch.setattr(settings(), 'answer_requests_per_minute', 1)
    assert ask(client, auth, kb).status_code == 429


def test_conflicting_sources_return_cited_disagreement(client, monkeypatch):
    auth, kb, _ = policy(client)
    doc = client.post(f'/api/v1/knowledge-bases/{kb}/documents', headers=auth | {'Idempotency-Key': str(uuid4())},
                      files={'file': ('alternative.md', '已发货订单退款需要经理审批。'.encode())}).json()
    worker.ingest('demo', doc['job_id'])
    async def conflict(_, messages, deadline):
        sources = json.loads(messages[1]['content'])['SOURCES']
        assert len(sources) == 2
        return Completion(ModelAnswer(status='conflicting_evidence', claims=[
            {'text': s['text'], 'evidence': [{'source_id': s['source_id'], 'quote': s['text']}]} for s in sources]), Usage())
    monkeypatch.setattr(answer_provider, 'complete', conflict)
    result = ask(client, auth, kb)
    assert result.status_code == 200, result.text
    assert result.json()['status'] == 'conflicting_evidence' and len(result.json()['citations']) == 2
