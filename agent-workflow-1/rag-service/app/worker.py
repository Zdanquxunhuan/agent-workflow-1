import hashlib
import logging
import threading
from uuid import UUID, uuid4

from app.config import settings
from app.db import transaction, vector_literal
from app.embedding import chunk_text, embed
from app.errors import RagError
from app.parsing import parse
from app.queue import celery_app
from app.storage import get_object
from app.retrieval.lexical import PROFILE as LEXICAL_PROFILE, index_payload

log = logging.getLogger(__name__)


class LostLease(Exception):
    pass


def claim(tenant, job_id):
    with transaction(tenant) as conn:
        row = conn.execute("""UPDATE ingestion_jobs SET state='PARSING',attempt=attempt+1,
          fence=fence+1,lease_until=now()+(%s*interval '1 second'),updated_at=now(),
          error_code=NULL,error_message=NULL WHERE tenant_id=%s AND id=%s AND state='QUEUED'
          AND attempt<%s RETURNING *""", (settings().lease_seconds, tenant, job_id,
                                         settings().max_attempts)).fetchone()
        return row


def checkpoint(tenant, job_id, fence, state, progress):
    with transaction(tenant) as conn:
        changed = conn.execute("""UPDATE ingestion_jobs SET state=%s,progress=%s,updated_at=now()
          WHERE tenant_id=%s AND id=%s AND fence=%s AND state NOT IN ('CANCELLED','FAILED','SUCCEEDED')
          AND lease_until>now() RETURNING id""", (state, progress, tenant, job_id, fence)).fetchone()
        if not changed:
            raise LostLease()


def heartbeat(tenant, job_id, fence, stop):
    while not stop.wait(max(2, settings().lease_seconds // 3)):
        try:
            with transaction(tenant) as conn:
                conn.execute("""UPDATE ingestion_jobs SET lease_until=now()+(%s*interval '1 second')
                  WHERE tenant_id=%s AND id=%s AND fence=%s AND lease_until>now()
                  AND state IN ('PARSING','EMBEDDING','INDEXING')""",
                             (settings().lease_seconds, tenant, job_id, fence))
        except Exception:
            log.warning("heartbeat_failed job_id=%s", job_id)


def publish(tenant, job, chunks):
    prepared = [(uuid4(), c, index_payload(c["text"])) for c in chunks]
    with transaction(tenant) as conn:
        # Lock document before job: deletion uses the same lock order.
        doc = conn.execute("SELECT * FROM documents WHERE tenant_id=%s AND id=%s FOR UPDATE",
                           (tenant, job["document_id"])).fetchone()
        current = conn.execute("SELECT * FROM ingestion_jobs WHERE tenant_id=%s AND id=%s FOR UPDATE",
                               (tenant, job["id"])).fetchone()
        lease_valid = conn.execute("SELECT %s > now() AS valid", (current["lease_until"],)).fetchone()["valid"]
        if (doc["deleted_at"] is not None or current["fence"] != job["fence"] or not lease_valid
                or current["state"] != "INDEXING"):
            raise LostLease()
        member = conn.execute("SELECT 1 FROM tenant_memberships WHERE tenant_id=%s AND subject=%s AND enabled",
                              (tenant, job["actor"])).fetchone()
        permission = conn.execute("""SELECT 1 FROM tenant_memberships m WHERE m.tenant_id=%s
          AND m.subject=%s AND m.enabled AND (m.role='admin' OR EXISTS (
            SELECT 1 FROM kb_acl a WHERE a.tenant_id=m.tenant_id AND a.subject=m.subject
            AND a.kb_id=%s AND a.role IN ('contributor','manager')))""",
                                  (tenant, job["actor"], doc["kb_id"])).fetchone()
        if not member or not permission:
            raise RagError("PUBLISH_FORBIDDEN", "Upload permission revoked before publication", 403)
        conn.execute("DELETE FROM chunks WHERE tenant_id=%s AND version_id=%s", (tenant, job["version_id"]))
        with conn.cursor() as cur:
            cur.executemany("""INSERT INTO chunks(tenant_id,id,document_id,version_id,ordinal,content,
              page,section,token_count,content_hash,embedding_profile,embedding)
              VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s::vector)""", [
                (tenant, chunk_id, job["document_id"], job["version_id"], i, c["text"], c["page"],
                 c["section"], c["token_count"], c["content_hash"], settings().embedding_profile,
                 vector_literal(c["vector"])) for i, (chunk_id, c, _) in enumerate(prepared)])
            cur.executemany("""INSERT INTO chunk_lexical(tenant_id,chunk_id,profile,content_hash,lexemes,identifiers)
              VALUES(%s,%s,%s,%s,%s,%s)""", [
                (tenant, chunk_id, LEXICAL_PROFILE, c["content_hash"], lexical[0], lexical[1])
                for chunk_id, c, lexical in prepared])
        conn.execute("UPDATE documents SET active_version_id=%s WHERE tenant_id=%s AND id=%s",
                     (job["version_id"], tenant, job["document_id"]))
        conn.execute("""UPDATE ingestion_jobs SET state='SUCCEEDED',progress=100,lease_until=NULL,
          updated_at=now() WHERE tenant_id=%s AND id=%s""", (tenant, job["id"]))
        conn.execute("INSERT INTO audit_events(tenant_id,id,actor,action,resource_id) VALUES(%s,%s,%s,%s,%s)",
                     (tenant, uuid4(), job["actor"], "DOCUMENT_PUBLISHED", str(job["document_id"])))


@celery_app.task(name="rag.ingest")
def ingest(tenant: str, job_id: str):
    UUID(job_id)
    job = claim(tenant, job_id)
    if not job:
        return
    stop = threading.Event()
    thread = threading.Thread(target=heartbeat, args=(tenant, job_id, job["fence"], stop), daemon=True)
    thread.start()
    try:
        with transaction(tenant) as conn:
            version = conn.execute("SELECT * FROM document_versions WHERE tenant_id=%s AND id=%s",
                                   (tenant, job["version_id"])).fetchone()
        data = get_object(version["object_key"])
        if hashlib.sha256(data).hexdigest() != version["sha256"]:
            raise RagError("CHECKSUM_MISMATCH", "Stored object integrity check failed", 422)
        blocks = parse(version["filename"], data)
        checkpoint(tenant, job_id, job["fence"], "EMBEDDING", 15)
        chunks = []
        for block in blocks:
            checkpoint(tenant, job_id, job["fence"], "EMBEDDING", 20)
            for chunk in chunk_text(block["text"]):
                chunks.append({**chunk, "page": block["page"], "section": block["section"]})
            if len(chunks) > settings().max_chunks:
                raise RagError("CHUNK_LIMIT", "Document exceeds chunk limit", 422)
        if not chunks:
            raise RagError("NO_TEXT", "Document produced no chunks", 422)
        for start in range(0, len(chunks), 4):
            checkpoint(tenant, job_id, job["fence"], "EMBEDDING", 20 + int(65 * start / len(chunks)))
            batch = chunks[start:start + 4]
            vectors = embed([c["text"] for c in batch])
            for chunk, vector in zip(batch, vectors, strict=True):
                chunk["vector"] = vector
        checkpoint(tenant, job_id, job["fence"], "INDEXING", 90)
        publish(tenant, job, chunks)
        log.info("ingestion_succeeded job_id=%s chunks=%s", job_id, len(chunks))
    except LostLease:
        log.info("ingestion_discarded job_id=%s", job_id)
    except Exception as exc:
        retryable = not isinstance(exc, RagError) or exc.retryable
        state = "RETRY_WAIT" if retryable and job["attempt"] < settings().max_attempts else "FAILED"
        code = exc.code if isinstance(exc, RagError) else "INGESTION_ERROR"
        message = exc.message if isinstance(exc, RagError) else "Processing failed; consult worker diagnostics"
        with transaction(tenant) as conn:
            conn.execute("""UPDATE ingestion_jobs SET state=%s,error_code=%s,error_message=%s,
              lease_until=NULL,next_attempt_at=now()+(%s*interval '1 second'),updated_at=now()
              WHERE tenant_id=%s AND id=%s AND fence=%s
              AND state NOT IN ('CANCELLED','SUCCEEDED')""",
                         (state, code, message, min(60, 5 * 2 ** job["attempt"]), tenant, job_id, job["fence"]))
        log.error("ingestion_failed job_id=%s code=%s type=%s", job_id, code, type(exc).__name__)
    finally:
        stop.set()
        thread.join(timeout=2)

