"""Real PostgreSQL/RLS/S3; only inference is stubbed in this suite."""
import asyncio
import os
import time
from uuid import UUID

import pytest

from app import worker
from app.backfill import backfill
from app.config import settings
from app.db import transaction
from app.errors import RagError
from app.main import Search
from app.retrieval import pipeline
from app.auth import Principal
from test_integration import client, headers, setup_document

pytestmark = pytest.mark.skipif(os.environ.get('RAG_INTEGRATION') != '1', reason='requires Docker infrastructure')


def request(client, auth, kb, profile, debug=False, query='已发货订单退款审批'):
    return client.post('/api/v1/search' + ('/debug' if debug else ''), headers=auth,
                       json={'knowledge_base_ids': [kb], 'query': query, 'profile': profile})


@pytest.mark.parametrize('profile', ['dense-v2', 'lexical-v2', 'hybrid-v2', 'hybrid-rerank-v2'])
def test_every_channel_filters_acl_before_rerank_and_debug(client, monkeypatch, profile):
    auth, kb, doc, _ = setup_document(client)
    worker.ingest('demo', doc['job_id'])
    captured = []
    async def rerank(_, query, candidates, deadline):
        captured.extend(candidates)
        return [2.0] * len(candidates)
    monkeypatch.setattr('app.retrieval.models.rerank', rerank)
    allowed = request(client, auth, kb, profile, True)
    assert allowed.status_code == 200, allowed.text
    assert allowed.json()['hits'] and allowed.json()['debug']['candidates']
    captured.clear()
    changed = client.put(f"/api/v1/documents/{doc['document_id']}/acl", headers=auth,
                         json={'subjects': ['bob'], 'expected_revision': 1})
    assert changed.status_code == 200
    denied = request(client, auth, kb, profile, True)
    assert denied.status_code == 200, denied.text
    assert denied.json()['hits'] == [] and denied.json()['debug']['candidates'] == []
    assert not captured
    assert request(client, headers(client, 'admin', 'other'), kb, profile).status_code == 404


def test_reader_cannot_request_diagnostics(client):
    auth, kb, _, _ = setup_document(client)
    client.put(f'/api/v1/knowledge-bases/{kb}/acl', headers=auth, json={'subject': 'alice', 'role': 'reader'})
    alice = headers(client, 'alice')
    assert request(client, alice, kb, 'lexical-v2').status_code == 200
    assert request(client, alice, kb, 'lexical-v2', True).status_code == 403
    client.put(f'/api/v1/knowledge-bases/{kb}/acl', headers=auth, json={'subject': 'alice', 'role': 'manager'})
    assert request(client, alice, kb, 'lexical-v2', True).status_code == 200


def test_lexical_works_without_model_and_hybrid_reports_embedding_degradation(client, monkeypatch):
    auth, kb, doc, _ = setup_document(client)
    worker.ingest('demo', doc['job_id'])
    async def fail(*args):
        raise RagError('EMBEDDING_UNAVAILABLE', 'test failure', 503, True)
    monkeypatch.setattr('app.retrieval.models.embed_query', fail)
    response = request(client, auth, kb, 'lexical-v2').json()
    assert response['status'] == 'ok' and response['hits'][0]['distance'] is None
    response = request(client, auth, kb, 'hybrid-v2').json()
    assert response['degraded_components'] == ['embedding'] and response['hits']
    assert request(client, auth, kb, 'dense-v2').status_code == 503


def test_reranker_fault_falls_back_but_revocation_during_reranking_removes_all_debug_evidence(client, monkeypatch):
    auth, kb, doc, _ = setup_document(client)
    worker.ingest('demo', doc['job_id'])
    async def fail(*args):
        raise RagError('RERANK_UNAVAILABLE', 'test failure', 503, True)
    monkeypatch.setattr('app.retrieval.models.rerank', fail)
    response = request(client, auth, kb, 'hybrid-rerank-v2', True).json()
    assert response['status'] == 'degraded' and response['degraded_components'] == ['reranker']
    assert response['hits'] and response['reranker_profile'] is None
    async def revoke(_, query, candidates, deadline):
        with transaction('demo') as conn:
            conn.execute("UPDATE documents SET allowed_subjects=ARRAY['bob'] WHERE id=%s", (doc['document_id'],))
        return [1.0] * len(candidates)
    monkeypatch.setattr('app.retrieval.models.rerank', revoke)
    response = request(client, auth, kb, 'hybrid-rerank-v2', True).json()
    assert response['hits'] == [] and response['debug']['candidates'] == []


def test_backfill_is_idempotent_preserves_vectors_citations_and_detects_missing_index(client):
    auth, kb, doc, _ = setup_document(client)
    worker.ingest('demo', doc['job_id'])
    with transaction('demo') as conn:
        snapshot = conn.execute('SELECT id,content_hash,embedding::text FROM chunks WHERE document_id=%s', (doc['document_id'],)).fetchall()
        conn.execute('DELETE FROM chunk_lexical WHERE chunk_id=%s', (snapshot[0]['id'],))
    assert request(client, auth, kb, 'lexical-v2').status_code == 503
    response = request(client, auth, kb, 'hybrid-v2').json()
    assert response['degraded_components'] == ['lexical_index_incomplete']
    assert backfill('demo') >= 1
    assert backfill('demo') == 0
    with transaction('demo') as conn:
        assert snapshot == conn.execute('SELECT id,content_hash,embedding::text FROM chunks WHERE document_id=%s', (doc['document_id'],)).fetchall()
    assert request(client, auth, kb, 'lexical-v2').json()['status'] == 'ok'
    assert client.get('/api/v1/citations/' + str(snapshot[0]['id']), headers=auth).status_code == 200
    client.delete('/api/v1/documents/' + doc['document_id'], headers=auth)
    backfill('demo')
    assert request(client, auth, kb, 'hybrid-v2').json()['hits'] == []


def test_request_deadline_is_bounded_and_lease_expires(client, monkeypatch):
    auth, kb, _, _ = setup_document(client)
    monkeypatch.setattr(settings(), 'search_deadline_seconds', .08)
    async def slow(*args):
        await asyncio.sleep(10)
    monkeypatch.setattr('app.retrieval.models.embed_query', slow)
    start = time.monotonic()
    response = request(client, auth, kb, 'dense-v2')
    assert response.status_code == 504 and response.json()['code'] == 'SEARCH_DEADLINE'
    assert time.monotonic() - start < 1
    # Cleanup our deliberately expired search so subsequent tests aren't capacity-limited.
    with transaction('demo') as conn:
        conn.execute("UPDATE search_admissions SET lease_until=clock_timestamp() WHERE tenant_id='demo'")


def test_parallel_requests_share_tenant_admission_and_release_capacity(client, monkeypatch):
    _, kb, _, _ = setup_document(client)
    monkeypatch.setattr(settings(), 'search_tenant_concurrency', 1)
    async def run():
        entered, finish = asyncio.Event(), asyncio.Event()
        async def hold(*args):
            entered.set()
            await finish.wait()
            return [1.0] + [0.0] * 1023
        monkeypatch.setattr('app.retrieval.models.embed_query', hold)
        # Use the TestClient's event loop, where its async pool/client live.
        user = Principal('demo', 'admin', 'admin')
        body = Search(query='退款', knowledge_base_ids=[UUID(kb)], profile='dense-v2')
        first = asyncio.create_task(pipeline.search(client.app, user, body, 'test-first'))
        await entered.wait()
        try:
            with pytest.raises(RagError) as exc:
                await pipeline.search(client.app, user, body, 'test-second')
            assert exc.value.status == 429
        finally:
            finish.set()
            await first
        assert (await pipeline.search(client.app, user, body, 'test-third'))['status'] == 'ok'
    client.portal.call(run)


def test_identifier_channel_matches_full_normalized_code(client):
    auth, kb, doc, _ = setup_document(client)
    # A second real upload includes a punctuation-bearing identifier.
    upload = client.post(f'/api/v1/knowledge-bases/{kb}/documents', headers=auth | {'Idempotency-Key': 'code-test'},
                         files={'file': ('error.md', '错误 ERR-1042：支付令牌已过期。'.encode())})
    assert upload.status_code == 202, upload.text
    worker.ingest('demo', upload.json()['job_id'])
    response = request(client, auth, kb, 'lexical-v2', True, '如何修复ｅｒｒ－１０４２？')
    assert response.status_code == 200, response.text
    assert response.json()['debug']['identifiers'] == ['err-1042']
    assert response.json()['debug']['candidates'][0]['channel_ranks']['identifier'] == 1


def test_output_top_k_cannot_increase_reranker_budget_and_tail_is_not_given_fake_logits(client, monkeypatch):
    auth, kb, doc, _ = setup_document(client)
    worker.ingest('demo', doc['job_id'])
    for i in range(12):
        response = client.post(f'/api/v1/knowledge-bases/{kb}/documents',
            headers=auth | {'Idempotency-Key': f'budget-{i}'},
            files={'file': (f'policy-{i}.md', f'退款审批政策第{i}条：需要审批并记录。'.encode())})
        worker.ingest('demo', response.json()['job_id'])
    seen = []
    async def rerank(_, query, candidates, deadline):
        seen.append(len(candidates))
        return list(range(len(candidates)))
    monkeypatch.setattr('app.retrieval.models.rerank', rerank)
    response = client.post('/api/v1/search', headers=auth, json={
        'query': '退款审批', 'knowledge_base_ids': [kb], 'profile': 'hybrid-rerank-v2', 'top_k': 20})
    assert response.status_code == 200, response.text
    assert seen == [10]
    hits = response.json()['hits']
    assert len(hits) == 13
    assert all(hit['score_type'] == 'reranker_logit' for hit in hits[:10])
    assert all(hit['score_type'] == 'rrf' for hit in hits[10:])
