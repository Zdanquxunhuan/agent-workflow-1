"""Real PostgreSQL/S3 tests. Inference is explicitly stubbed here; see acceptance.py for real BGE."""
import hashlib
import os
from uuid import uuid4
from contextlib import contextmanager

import psycopg
import pytest
from fastapi.testclient import TestClient

from app.config import settings
from app.db import transaction
from app.main import app
from app import worker
from app.errors import RagError

pytestmark = pytest.mark.skipif(os.environ.get("RAG_INTEGRATION") != "1", reason="requires Docker infrastructure")


@pytest.fixture
def client(monkeypatch):
    def vectors(texts):
        return [[1.0] + [0.0] * 1023 for _ in texts]
    monkeypatch.setattr("app.main.embed", vectors)
    monkeypatch.setattr(worker, "embed", vectors)
    monkeypatch.setattr(worker, "chunk_text", lambda text: [{"text": text, "token_count": len(text),
                        "content_hash": hashlib.sha256(text.encode()).hexdigest()}])
    with TestClient(app, raise_server_exceptions=False) as value:
        yield value


def headers(client, subject="admin", tenant="demo"):
    response = client.post("/api/v1/auth/dev-token", json={"subject": subject, "tenant_id": tenant})
    assert response.status_code == 200, response.text
    return {"Authorization": "Bearer " + response.json()["token"]}


def setup_document(client):
    auth = headers(client)
    kb = client.post("/api/v1/knowledge-bases", json={"name": "test-" + str(uuid4())}, headers=auth)
    assert kb.status_code == 201, kb.text
    kb_id = kb.json()["id"]
    key = str(uuid4())
    upload = client.post(f"/api/v1/knowledge-bases/{kb_id}/documents",
                         files={"file": ("policy.md", "已发货订单退款需要主管审批。".encode())},
                         headers=auth | {"Idempotency-Key": key})
    assert upload.status_code == 202, upload.text
    return auth, kb_id, upload.json(), key


def test_upload_replay_conflict_and_atomic_publication(client):
    auth, kb, result, key = setup_document(client)
    retry = client.post(f"/api/v1/knowledge-bases/{kb}/documents",
                        files={"file": ("policy.md", "已发货订单退款需要主管审批。".encode())},
                        headers=auth | {"Idempotency-Key": key})
    assert retry.json()["job_id"] == result["job_id"]
    conflict = client.post(f"/api/v1/knowledge-bases/{kb}/documents",
                           files={"file": ("policy.md", b"different")}, headers=auth | {"Idempotency-Key": key})
    assert conflict.status_code == 409
    body = {"query": "退款", "knowledge_base_ids": [kb]}
    assert client.post("/api/v1/search", json=body, headers=auth).json()["hits"] == []
    worker.ingest("demo", result["job_id"])
    worker.ingest("demo", result["job_id"])
    state = client.get(f"/api/v1/jobs/{result['job_id']}", headers=auth)
    assert state.json()["state"] == "SUCCEEDED", state.text
    found = client.post("/api/v1/search", json=body, headers=auth)
    assert found.status_code == 200, found.text
    assert len(found.json()["hits"]) == 1
    citation = found.json()["hits"][0]["citation"]["url"]
    assert client.get(citation, headers=auth).json()["text"] == "已发货订单退款需要主管审批。"


def test_tenant_kb_document_acl_and_revoke_cover_citations_and_originals(client):
    auth, kb, result, _ = setup_document(client)
    worker.ingest("demo", result["job_id"])
    alice, other = headers(client, "alice"), headers(client, "admin", "other")
    body = {"query": "退款", "knowledge_base_ids": [kb]}
    assert client.post("/api/v1/search", json=body, headers=other).status_code == 404
    assert client.post("/api/v1/search", json=body, headers=alice).status_code == 404
    assert client.get(f"/api/v1/jobs/{result['job_id']}", headers=other).status_code == 404
    client.put(f"/api/v1/knowledge-bases/{kb}/acl", json={"subject": "alice", "role": "reader"}, headers=auth)
    hit = client.post("/api/v1/search", json=body, headers=alice).json()["hits"][0]
    assert client.get(hit["citation"]["url"], headers=alice).status_code == 200
    doc_url = f"/api/v1/documents/{result['document_id']}"
    assert client.get(doc_url + "/original", headers=alice).status_code == 200
    changed = client.put(doc_url + "/acl", json={"subjects": ["admin"], "expected_revision": 1}, headers=auth)
    assert changed.status_code == 200, changed.text
    assert client.post("/api/v1/search", json=body, headers=alice).json()["hits"] == []
    assert client.get(hit["citation"]["url"], headers=alice).status_code == 404
    assert client.get(doc_url + "/original", headers=alice).status_code == 404
    assert client.put(doc_url + "/acl", json={"subjects": None, "expected_revision": 1}, headers=auth).status_code == 409
    client.put(doc_url + "/acl", json={"subjects": None, "expected_revision": 2}, headers=auth)
    client.delete(f"/api/v1/knowledge-bases/{kb}/acl/alice", headers=auth)
    assert client.post("/api/v1/search", json=body, headers=alice).status_code == 404


def test_deleted_document_cannot_be_published_by_late_worker(client):
    auth, kb, result, _ = setup_document(client)
    job = worker.claim("demo", result["job_id"])
    response = client.delete(f"/api/v1/documents/{result['document_id']}", headers=auth)
    assert response.status_code == 200
    with pytest.raises(worker.LostLease):
        worker.publish("demo", job, [])
    assert client.post("/api/v1/search", json={"query": "退款", "knowledge_base_ids": [kb]}, headers=auth).json()["hits"] == []


def test_fencing_rejects_stale_worker_after_reclaim(client):
    _, _, result, _ = setup_document(client)
    old = worker.claim("demo", result["job_id"])
    with transaction("demo") as conn:
        conn.execute("UPDATE ingestion_jobs SET state='QUEUED',lease_until=NULL WHERE tenant_id='demo' AND id=%s",
                     (result["job_id"],))
    new = worker.claim("demo", result["job_id"])
    assert new["fence"] > old["fence"]
    with pytest.raises(worker.LostLease):
        worker.checkpoint("demo", old["id"], old["fence"], "INDEXING", 90)


def test_pool_does_not_retain_tenant_and_rls_rejects_cross_tenant_write(client):
    _, kb, _, _ = setup_document(client)
    with transaction("other") as conn:
        assert conn.execute("SELECT * FROM knowledge_bases WHERE id=%s", (kb,)).fetchone() is None
    with transaction() as conn:
        assert conn.execute("SELECT * FROM knowledge_bases LIMIT 1").fetchone() is None
    with pytest.raises(psycopg.errors.InsufficientPrivilege):
        with transaction("other") as conn:
            conn.execute("INSERT INTO knowledge_bases(tenant_id,id,name,owner) VALUES('demo',%s,'bad','admin')", (uuid4(),))


def test_embedding_outage_is_not_reported_as_empty_evidence(client, monkeypatch):
    auth, kb, _, _ = setup_document(client)
    def fail(_):
        raise RagError("EMBEDDING_UNAVAILABLE", "unavailable", 503, True)
    monkeypatch.setattr("app.main.embed", fail)
    response = client.post("/api/v1/search", json={"query": "退款", "knowledge_base_ids": [kb]}, headers=auth)
    assert response.status_code == 503
    assert response.json()["code"] == "EMBEDDING_UNAVAILABLE"


def test_recovery_requeues_expired_lease_and_outbox_publish_can_replay(client, monkeypatch):
    from app import dispatcher
    from psycopg.rows import dict_row
    auth, kb, result, _ = setup_document(client)
    stale = worker.claim("demo", result["job_id"])
    with transaction("demo") as conn:
        conn.execute("UPDATE ingestion_jobs SET lease_until=now()-interval '1 second' WHERE id=%s",
                     (result["job_id"],))
        conn.execute("UPDATE outbox_events SET sent_at=now()")

    @contextmanager
    def queue_transaction():
        url = settings().database_url.replace("rag_api:", "rag_dispatcher:")
        with psycopg.connect(url, row_factory=dict_row) as conn:
            yield conn

    monkeypatch.setattr(dispatcher, "transaction", queue_transaction)
    dispatcher.recover()
    with transaction("demo") as conn:
        row = conn.execute("SELECT state,fence FROM ingestion_jobs WHERE id=%s", (result["job_id"],)).fetchone()
        assert row["state"] == "QUEUED" and row["fence"] > stale["fence"]
    with pytest.raises(worker.LostLease):
        worker.checkpoint("demo", stale["id"], stale["fence"], "INDEXING", 90)

    def unavailable(*args, **kwargs):
        raise OSError("broker unavailable")
    monkeypatch.setattr(dispatcher.celery_app, "send_task", unavailable)
    with pytest.raises(OSError):
        dispatcher.dispatch_once()
    delivered = []
    monkeypatch.setattr(dispatcher.celery_app, "send_task", lambda *a, **kw: delivered.append(kw))
    assert dispatcher.dispatch_once()
    assert delivered[0]["args"] == ["demo", result["job_id"]]
    worker.ingest(*delivered[0]["args"])
    worker.ingest(*delivered[0]["args"])
    assert client.get(f"/api/v1/jobs/{result['job_id']}", headers=auth).json()["state"] == "SUCCEEDED"


def test_revocation_during_query_encoding_prevents_evidence_return(client, monkeypatch):
    auth, kb, result, _ = setup_document(client)
    worker.ingest("demo", result["job_id"])
    client.put(f"/api/v1/knowledge-bases/{kb}/acl", json={"subject": "alice", "role": "reader"}, headers=auth)
    alice = headers(client, "alice")
    def revoke_then_embed(texts):
        with transaction("demo") as conn:
            conn.execute("DELETE FROM kb_acl WHERE kb_id=%s AND subject='alice'", (kb,))
        return [[1.0] + [0.0] * 1023]
    monkeypatch.setattr("app.main.embed", revoke_then_embed)
    response = client.post("/api/v1/search", headers=alice, json={"query": "退款", "knowledge_base_ids": [kb]})
    assert response.status_code == 404

