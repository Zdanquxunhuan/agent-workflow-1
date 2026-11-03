"""V3 API/transaction tests with real PostgreSQL/S3 and explicitly stubbed inference."""
import os
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
from uuid import uuid4

import pytest
from botocore.exceptions import ClientError

from app import worker, version_gc, version_api
from app.config import settings
from app.db import transaction
from app.storage import s3
from test_integration import client, headers, setup_document

pytestmark = pytest.mark.skipif(os.environ.get('RAG_INTEGRATION') != '1', reason='requires isolated infrastructure')


def checked(response, status=200):
    assert response.status_code == status, response.text
    return response.json()


def initial(client):
    auth, kb, first, _ = setup_document(client)
    worker.ingest('demo', first['job_id'])
    assert checked(client.get('/api/v1/jobs/' + first['job_id'], headers=auth))['state'] == 'SUCCEEDED'
    return auth, kb, first, '/api/v1/documents/' + first['document_id']


def upload(client, auth, root, revision=1, auto=False, key=None, text='新版退款政策：200 元需审批。'):
    response = client.post(root + '/versions', headers=auth | {'Idempotency-Key': key or str(uuid4())},
                           data={'expected_content_revision': str(revision), 'auto_publish': str(auto).lower(),
                                 'change_note': '更新审批门槛'}, files={'file': ('v2.md', text.encode())})
    return checked(response, 202)


def publish(client, auth, root, version, revision, action='publish', key=None):
    return client.post(root + f'/versions/{version}/{action}',
                       headers=auth | {'Idempotency-Key': key or str(uuid4())},
                       json={'expected_content_revision': revision, 'reason': '已复核政策'})


def search(client, auth, kb):
    return checked(client.post('/api/v1/search', headers=auth,
                               json={'query': '退款审批', 'knowledge_base_ids': [kb]}))['hits']


def age(version):
    with transaction('demo') as conn:
        conn.execute("UPDATE document_versions SET created_at=now()-interval '60 days',"
                     "ready_at=now()-interval '60 days',retired_at=now()-interval '40 days' "
                     "WHERE tenant_id='demo' AND id=%s", (version,))


def gc_setup(client, monkeypatch):
    monkeypatch.setattr(settings(), 'version_keep_latest', 1)
    auth, kb, first, root = initial(client)
    hit = search(client, auth, kb)[0]
    second = upload(client, auth, root)
    worker.ingest('demo', second['job_id'])
    checked(publish(client, auth, root, second['version_id'], 1))
    age(first['version_id'])
    return auth, kb, first, second, root, hit


def enqueue(client, auth, root, versions, revision=2):
    return client.post(root + '/retention/purge', headers=auth,
                       json={'expected_content_revision': revision, 'version_ids': versions})


def task_state(version):
    with transaction('demo') as conn:
        return conn.execute("SELECT * FROM version_gc_tasks WHERE tenant_id='demo' AND version_id=%s",
                            (version,)).fetchone()


def test_manual_publish_rollback_and_historical_evidence(client):
    auth, kb, first, root = initial(client)
    old_hit = search(client, auth, kb)[0]
    old_original = client.get(root + '/original', headers=auth).content
    second = upload(client, auth, root)
    assert search(client, auth, kb)[0]['document_version_id'] == first['version_id']
    worker.ingest('demo', second['job_id'])
    assert search(client, auth, kb)[0]['document_version_id'] == first['version_id']
    detail = checked(client.get(root, headers=auth))
    assert detail['content_revision'] == 1 and detail['version_counter'] == 2
    key = str(uuid4())
    result = checked(publish(client, auth, root, second['version_id'], 1, key=key))
    replay = checked(publish(client, auth, root, second['version_id'], 1, key=key))
    assert result['event_id'] == replay['event_id'] and replay['replayed']
    assert publish(client, auth, root, first['version_id'], 1, key=key).status_code == 409
    assert search(client, auth, kb)[0]['document_version_id'] == second['version_id']
    historical = checked(client.get(old_hit['citation']['url'], headers=auth))
    assert historical['status'] == 'historical' and historical['text'] == old_hit['text']
    original = client.get(root + f"/versions/{first['version_id']}/original", headers=auth)
    assert original.content == old_original and original.headers['X-Version-Current'] == 'false'
    diff = checked(client.get(root + '/versions/compare', headers=auth,
                             params={'from_version': first['version_id'], 'to_version': second['version_id']}))
    assert '200' in diff['diff'] and not diff['truncated']
    checked(publish(client, auth, root, first['version_id'], 2, 'rollback'))
    assert search(client, auth, kb)[0]['document_version_id'] == first['version_id']
    detail = checked(client.get(root, headers=auth))
    assert detail['content_revision'] == 3 and detail['revision'] == 1
    events = checked(client.get(root + '/publication-events?limit=2', headers=auth))
    assert [e['content_revision'] for e in events['items']] == [3, 2]
    assert len(checked(client.get(root + '/publication-events?before_revision=2', headers=auth))['items']) == 1


def test_upload_replay_and_automatic_publish_conflict(client):
    auth, kb, first, root = initial(client)
    key = str(uuid4())
    second = upload(client, auth, root, auto=True, key=key)
    third = upload(client, auth, root, auto=True)
    assert upload(client, auth, root, auto=True, key=key)['version_id'] == second['version_id']
    worker.ingest('demo', second['job_id'])
    worker.ingest('demo', third['job_id'])
    worker.ingest('demo', third['job_id'])
    versions = checked(client.get(root + '/versions', headers=auth))
    assert versions['active_version_id'] == second['version_id'] and versions['content_revision'] == 2
    assert versions['items'][0]['publication_note'] == 'CONTENT_REVISION_CONFLICT'
    assert versions['items'][0]['job_state'] == 'SUCCEEDED'
    assert checked(client.get(root, headers=auth))['version_counter'] == 3
    checked(publish(client, auth, root, third['version_id'], 2))
    assert search(client, auth, kb)[0]['document_version_id'] == third['version_id']


@pytest.mark.parametrize('same_key', [False, True])
def test_concurrent_publication_cas_and_idempotency(client, same_key):
    auth, _, _, root = initial(client)
    versions = [upload(client, auth, root) for _ in range(2)]
    for version in versions:
        worker.ingest('demo', version['job_id'])
    barrier, key = Barrier(2), str(uuid4())
    def request(index):
        barrier.wait(timeout=10)
        return publish(client, auth, root, versions[0 if same_key else index]['version_id'], 1,
                       key=key if same_key else str(uuid4()))
    with ThreadPoolExecutor(max_workers=2) as executor:
        responses = list(executor.map(request, range(2)))
    assert sorted(r.status_code for r in responses) == ([200, 200] if same_key else [200, 409])
    if same_key:
        assert responses[0].json()['event_id'] == responses[1].json()['event_id']
    assert checked(client.get(root, headers=auth))['content_revision'] == 2
    assert len(checked(client.get(root + '/publication-events', headers=auth))['items']) == 2


def test_draft_permissions_and_current_authorization(client):
    auth, kb, first, root = initial(client)
    for subject in ('alice', 'bob'):
        checked(client.put(f'/api/v1/knowledge-bases/{kb}/acl', headers=auth,
                           json={'subject': subject, 'role': 'contributor'}))
    alice, bob = headers(client, 'alice'), headers(client, 'bob')
    draft = upload(client, alice, root)
    worker.ingest('demo', draft['job_id'])
    original = root + f"/versions/{draft['version_id']}/original"
    assert client.get(original, headers=alice).status_code == 200
    assert client.get(original, headers=bob).status_code == 404
    assert client.get('/api/v1/jobs/' + draft['job_id'], headers=bob).status_code == 404
    assert len(checked(client.get(root + '/versions', headers=bob))['items']) == 1
    assert publish(client, alice, root, draft['version_id'], 1).status_code == 403
    checked(client.put(f'/api/v1/knowledge-bases/{kb}/acl', headers=auth,
                       json={'subject': 'alice', 'role': 'reader'}))
    assert client.get(original, headers=alice).status_code == 403
    other = headers(client, tenant='other')
    for path in (root, root + '/versions', original, root + '/publication-events'):
        assert client.get(path, headers=other).status_code == 404
    assert enqueue(client, other, root, [first['version_id']], 1).status_code == 404
    checked(client.put(root + '/acl', headers=auth, json={'subjects': ['bob'], 'expected_revision': 1}))
    assert client.get(original, headers=auth).status_code == 404
    assert publish(client, auth, root, draft['version_id'], 1).status_code == 404


@pytest.mark.parametrize('storage_error', [False, True])
def test_download_reauthorizes_after_io(client, monkeypatch, storage_error):
    auth, kb, first, root = initial(client)
    checked(client.put(f'/api/v1/knowledge-bases/{kb}/acl', headers=auth,
                       json={'subject': 'alice', 'role': 'reader'}))
    alice = headers(client, 'alice')
    real_get = version_api.get_object
    def revoke(key):
        data = real_get(key)
        with transaction('demo') as conn:
            conn.execute("DELETE FROM kb_acl WHERE tenant_id='demo' AND kb_id=%s AND subject='alice'", (kb,))
        if storage_error:
            raise ClientError({'Error': {'Code': 'NoSuchKey'}}, 'GetObject')
        return data
    monkeypatch.setattr(version_api, 'get_object', revoke)
    response = client.get(root + '/original', headers=alice)
    assert response.status_code == 404 and '主管审批' not in response.text


def test_failed_or_cancelled_update_preserves_published_version(client, monkeypatch):
    auth, kb, first, root = initial(client)
    failed = upload(client, auth, root)
    from app.errors import RagError
    real_embed = worker.embed
    def fail(_):
        raise RagError('INVALID_CONTENT', 'Invalid content', 422)
    monkeypatch.setattr(worker, 'embed', fail)
    worker.ingest('demo', failed['job_id'])
    assert checked(client.get('/api/v1/jobs/' + failed['job_id'], headers=auth))['state'] == 'FAILED'
    assert search(client, auth, kb)[0]['document_version_id'] == first['version_id']
    checked(client.post('/api/v1/jobs/' + failed['job_id'] + '/retry', headers=auth))
    monkeypatch.setattr(worker, 'embed', real_embed)
    worker.ingest('demo', failed['job_id'])
    assert client.post('/api/v1/jobs/' + failed['job_id'] + '/retry', headers=auth).status_code == 409
    cancelled = upload(client, auth, root)
    job = worker.claim('demo', cancelled['job_id'])
    checked(client.post('/api/v1/jobs/' + cancelled['job_id'] + '/cancel', headers=auth))
    with pytest.raises(worker.LostLease):
        worker.publish('demo', job, [])
    assert checked(client.get(root, headers=auth))['active_version_id'] == first['version_id']


@pytest.mark.parametrize('damage,code', [('profile', 'PROFILE_MISMATCH'), ('lexical', 'INDEX_INCOMPLETE')])
def test_publication_requires_compatible_complete_indexes(client, damage, code):
    auth, _, _, root = initial(client)
    draft = upload(client, auth, root)
    assert publish(client, auth, root, draft['version_id'], 1).json()['code'] == 'VERSION_NOT_READY'
    worker.ingest('demo', draft['job_id'])
    assert publish(client, auth, root, draft['version_id'], 1, 'rollback').json()['code'] == 'INVALID_ROLLBACK'
    with transaction('demo') as conn:
        if damage == 'profile':
            conn.execute('UPDATE document_versions SET embedding_profile=%s WHERE id=%s', ('other-profile', draft['version_id']))
        else:
            conn.execute('DELETE FROM chunk_lexical WHERE chunk_id IN (SELECT id FROM chunks WHERE version_id=%s)',
                         (draft['version_id'],))
    assert publish(client, auth, root, draft['version_id'], 1).json()['code'] == code
    assert checked(client.get(root, headers=auth))['content_revision'] == 1


def test_gc_protection_atomic_batch_tombstone_and_replay(client, monkeypatch):
    auth, kb, first, second, root, hit = gc_setup(client, monkeypatch)
    preview = checked(client.post(root + '/retention/preview', headers=auth))
    assert preview['items'][0]['eligible']
    assert 'current_version' in preview['items'][1]['blocked_by']
    assert enqueue(client, auth, root, [first['version_id'], second['version_id']]).status_code == 409
    assert task_state(first['version_id']) is None
    hold_url = root + f"/versions/{first['version_id']}/retention-hold"
    checked(client.put(hold_url, headers=auth, json={'enabled': True}))
    assert enqueue(client, auth, root, [first['version_id']]).status_code == 409
    checked(client.put(hold_url, headers=auth, json={'enabled': False}))
    checked(enqueue(client, auth, root, [first['version_id'], first['version_id']]), 202)
    assert client.get(hit['citation']['url'], headers=auth).status_code == 410
    assert publish(client, auth, root, first['version_id'], 2, 'rollback').status_code == 410
    assert version_gc.process_one('demo')
    assert task_state(first['version_id'])['state'] == 'DONE'
    assert client.get(hit['citation']['url'], headers=auth).json()['code'] == 'CITATION_GONE'
    replay = checked(enqueue(client, auth, root, [first['version_id']]), 202)
    assert replay['already_purged'] == [first['version_id']]
    with transaction('demo') as conn:
        assert conn.execute('SELECT count(*) AS n FROM chunks WHERE version_id=%s', (first['version_id'],)).fetchone()['n'] == 0
        key = conn.execute('SELECT object_key FROM document_versions WHERE id=%s', (first['version_id'],)).fetchone()['object_key']
    with pytest.raises(ClientError):
        s3().head_object(Bucket=settings().s3_bucket, Key=key)
    assert search(client, auth, kb)[0]['document_version_id'] == second['version_id']


@pytest.mark.parametrize('crash_after_delete', [False, True])
def test_gc_recovers_storage_failure_or_crash_after_delete(client, monkeypatch, crash_after_delete):
    auth, _, first, _, root, _ = gc_setup(client, monkeypatch)
    checked(enqueue(client, auth, root, [first['version_id']]), 202)
    storage, real_delete = s3(), s3().delete_object
    class PowerLoss(BaseException):
        pass
    def fail(**kwargs):
        if crash_after_delete:
            real_delete(**kwargs)
            raise PowerLoss()
        raise ClientError({'Error': {'Code': 'ServiceUnavailable'}}, 'DeleteObject')
    monkeypatch.setattr(storage, 'delete_object', fail)
    if crash_after_delete:
        with pytest.raises(PowerLoss):
            version_gc.process_one('demo')
        assert task_state(first['version_id'])['state'] == 'RUNNING'
    else:
        version_gc.process_one('demo')
        assert task_state(first['version_id'])['state'] == 'RETRY_WAIT'
    with transaction('demo') as conn:
        conn.execute("UPDATE version_gc_tasks SET lease_until=now()-interval '1 second',next_attempt_at=now() "
                     "WHERE version_id=%s", (first['version_id'],))
    monkeypatch.setattr(storage, 'delete_object', real_delete)
    version_gc.process_one('demo')
    assert task_state(first['version_id'])['state'] == 'DONE'


def test_deleted_document_hold_and_retention_period(client, monkeypatch):
    auth, kb, first, root = initial(client)
    hit = search(client, auth, kb)[0]
    hold_url = root + f"/versions/{first['version_id']}/retention-hold"
    checked(client.put(hold_url, headers=auth, json={'enabled': True}))
    checked(client.delete(root, headers=auth))
    assert client.get(hit['citation']['url'], headers=auth).status_code == 404
    assert publish(client, auth, root, first['version_id'], 2, 'rollback').status_code == 404
    age(first['version_id'])
    checked(client.put(hold_url, headers=auth, json={'enabled': False}))
    assert enqueue(client, auth, root, [first['version_id']]).status_code == 409
    with transaction('demo') as conn:
        conn.execute("UPDATE documents SET deleted_at=now()-interval '40 days' WHERE id=%s", (first['document_id'],))
    checked(enqueue(client, auth, root, [first['version_id']]), 202)
    version_gc.process_one('demo')
    assert task_state(first['version_id'])['state'] == 'DONE'
    assert client.get(hit['citation']['url'], headers=auth).status_code == 404


def test_gc_revoked_actor_takeover_and_fence(client, monkeypatch):
    auth, kb, first, _, root, _ = gc_setup(client, monkeypatch)
    checked(client.put(f'/api/v1/knowledge-bases/{kb}/acl', headers=auth,
                       json={'subject': 'alice', 'role': 'manager'}))
    alice = headers(client, 'alice')
    checked(enqueue(client, alice, root, [first['version_id']]), 202)
    checked(client.delete(f'/api/v1/knowledge-bases/{kb}/acl/alice', headers=auth))
    version_gc.process_one('demo')
    old = task_state(first['version_id'])
    assert old['state'] == 'RETRY_WAIT' and old['error_code'] == 'NOT_FOUND'
    checked(enqueue(client, auth, root, [first['version_id']]), 202)
    real_delete = s3().delete_object
    def supersede(**kwargs):
        # Re-enqueue between object deletion and DB commit must fence the old runner.
        result = real_delete(**kwargs)
        checked(enqueue(client, auth, root, [first['version_id']]), 202)
        return result
    monkeypatch.setattr(s3(), 'delete_object', supersede)
    version_gc.process_one('demo')
    current = task_state(first['version_id'])
    assert current['state'] == 'QUEUED' and current['fence'] > old['fence']
    monkeypatch.setattr(s3(), 'delete_object', real_delete)
    version_gc.process_one('demo')
    assert task_state(first['version_id'])['state'] == 'DONE'


def test_version_pagination_and_comparison(client):
    auth, _, first, root = initial(client)
    second = upload(client, auth, root, text='退款门槛更新为 200 元。\n需要主管审批。')
    worker.ingest('demo', second['job_id'])
    page = checked(client.get(root + '/versions?limit=1', headers=auth))
    assert page['items'][0]['id'] == second['version_id'] and page['next_before'] == 2
    older = checked(client.get(root + '/versions?before=2&limit=1', headers=auth))
    assert older['items'][0]['id'] == first['version_id'] and older['next_before'] is None
    compared = checked(client.get(root + '/versions/compare', headers=auth,
                                  params={'from_version': first['version_id'], 'to_version': second['version_id']}))
    assert not compared['truncated'] and '200' in compared['diff']
