import hashlib
import logging
import time
from pathlib import Path
from contextlib import asynccontextmanager
from typing import Annotated
from uuid import UUID, uuid4

import httpx
import jwt
import psycopg
from fastapi import Depends, FastAPI, File, Header, Request, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, ConfigDict, Field
from botocore.exceptions import BotoCoreError, ClientError

from app.auth import Principal, document_visible, principal, require_kb
from app.config import settings
from app.db import transaction, pool
from app.errors import RagError
from app.parsing import validate_file
from app.storage import put_object, s3
from app.retrieval import pipeline
from app.retrieval.contracts import SearchResponse, SearchDebugResponse
from app.retrieval.database import create_pool
from app.retrieval.lexical import tokenizer
from app.retrieval.profiles import PROFILES
from app import lifecycle
from app.version_api import router as version_router, download_version
from app.retention import router as retention_router
from app.answers import router as answer_router
from app.memory_index import router as memory_router

@asynccontextmanager
async def lifespan(app):
    settings()  # Validate production gates before accepting requests.
    with transaction() as conn:
        role = conn.execute("SELECT rolsuper,rolbypassrls FROM pg_roles WHERE rolname=current_user").fetchone()
        if role["rolsuper"] or role["rolbypassrls"]:
            raise RuntimeError("API database role must not bypass row-level security")
        conn.execute("SELECT 1 FROM knowledge_bases LIMIT 1")
        conn.execute("SELECT 1 FROM chunk_lexical LIMIT 1")
        conn.execute("SELECT 1 FROM search_admissions LIMIT 1")
        conn.execute("SELECT content_revision FROM documents LIMIT 1")
        conn.execute("SELECT 1 FROM publication_events LIMIT 1")
        conn.execute("SELECT 1 FROM answer_runs LIMIT 1")
        if settings().memory_index_enabled:
            conn.execute("SELECT 1 FROM memory_vectors LIMIT 1")
            conn.execute("SELECT 1 FROM memory_index_admissions LIMIT 1")
    tokenizer()  # Warm up segmentation outside the request deadline.
    async with create_pool() as search_pool, httpx.AsyncClient(
            limits=httpx.Limits(max_connections=8, max_keepalive_connections=4), follow_redirects=False) as model_http:
        app.state.search_pool = search_pool
        app.state.model_http = model_http
        try:
            yield
        finally:
            pool().close()
            pool.cache_clear()


app = FastAPI(title="Workflow RAG", version="0.4.0", description="Grounded answers with current authorization and versioned evidence",
              lifespan=lifespan)
app.include_router(version_router)
app.include_router(retention_router)
app.include_router(answer_router)
app.include_router(memory_router)

log = logging.getLogger(__name__)
logging.basicConfig(level=logging.INFO)
User = Annotated[Principal, Depends(principal)]


@app.middleware("http")
async def boundary(request: Request, call_next):
    request.state.request_id = str(uuid4())
    # Enforce a transport bound before the multipart parser spools the file.
    if request.method in {"POST", "PUT", "PATCH"}:
        length = request.headers.get("content-length")
        try:
            if length is None:
                return JSONResponse({"code": "LENGTH_REQUIRED", "message": "Content-Length required"}, 411)
            if int(length) < 0 or int(length) > settings().upload_max_bytes + 65536:
                return JSONResponse({"code": "BODY_TOO_LARGE", "message": "Request too large"}, 413)
        except ValueError:
            return JSONResponse({"code": "INVALID_LENGTH", "message": "Invalid Content-Length"}, 400)
    started = time.monotonic()
    response = await call_next(request)
    response.headers["X-Request-ID"] = request.state.request_id
    response.headers["X-Content-Type-Options"] = "nosniff"
    response.headers["Cache-Control"] = "no-store"
    response.headers["Content-Security-Policy"] = "default-src 'self'; script-src 'self'; style-src 'self'; frame-ancestors 'none'"
    log.info("request id=%s method=%s status=%s duration_ms=%s", request.state.request_id,
             request.method, response.status_code, int((time.monotonic() - started) * 1000))
    return response


@app.exception_handler(RagError)
async def rag_error(request, exc):
    return JSONResponse({"code": exc.code, "message": exc.message, "retryable": exc.retryable,
                         "request_id": getattr(request.state, "request_id", None)}, exc.status,
                        headers={"Retry-After": "2"} if exc.status == 429 else None)


@app.exception_handler(RequestValidationError)
async def validation_error(request, exc):
    return JSONResponse({"code": "INVALID_ARGUMENT", "message": "Invalid request fields",
                         "request_id": request.state.request_id, "retryable": False}, 422)


@app.exception_handler(Exception)
async def unexpected(request, exc):
    log.error("request_failed id=%s type=%s", getattr(request.state, "request_id", ""), type(exc).__name__)
    unavailable = isinstance(exc, (psycopg.Error, BotoCoreError, ClientError))
    return JSONResponse({"code": "DEPENDENCY_UNAVAILABLE" if unavailable else "INTERNAL_ERROR",
                         "message": "Service temporarily unavailable", "retryable": True,
                         "request_id": getattr(request.state, "request_id", None)}, 503 if unavailable else 500)


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class KBCreate(StrictModel):
    name: str = Field(min_length=1, max_length=120)


class Grant(StrictModel):
    subject: str = Field(min_length=1, max_length=200)
    role: str = Field(pattern="^(reader|contributor|manager)$")


class DocumentACL(StrictModel):
    subjects: list[str] | None = Field(default=None, max_length=100)
    expected_revision: int = Field(ge=1)


class Search(StrictModel):
    query: str = Field(min_length=1, max_length=2000)
    knowledge_base_ids: list[UUID] = Field(min_length=1, max_length=5)
    top_k: int = Field(default=5, ge=1, le=20)
    profile: str | None = Field(default=None, max_length=80)


class DevLogin(StrictModel):
    tenant_id: str = Field(default="demo", pattern="^(demo|other)$")
    subject: str = Field(default="admin", pattern="^(admin|alice|bob)$")


def audit(conn, user, action, resource_id):
    conn.execute("INSERT INTO audit_events(tenant_id,id,actor,action,resource_id) VALUES(%s,%s,%s,%s,%s)",
                 (user.tenant_id, uuid4(), user.subject, action, str(resource_id)))


@app.get("/health/live")
def live():
    return {"status": "alive"}


@app.get("/health/ready")
def ready():
    with transaction() as conn:
        conn.execute("SELECT 1").fetchone()
    try:
        s3().head_bucket(Bucket=settings().s3_bucket)
        response = httpx.get(settings().embedding_url + "/health/ready", timeout=3)
        response.raise_for_status()
        if response.json()["profile"] != settings().embedding_profile:
            raise ValueError("profile mismatch")
    except Exception as exc:
        raise RagError("NOT_READY", "Object storage or embedding service not ready", 503, True) from exc
    return {"status": "ready", "embedding_profile": settings().embedding_profile}


@app.get("/api/v1/config")
def public_config():
    return {"dev_login_enabled": settings().environment == "development" and settings().dev_login_enabled,
            "max_upload_bytes": settings().upload_max_bytes, "formats": ["txt", "md", "pdf", "docx"],
            "retrieval_profiles": list(PROFILES), "default_retrieval_profile": settings().retrieval_profile,
            "answers_enabled": settings().answer_enabled}


@app.post("/api/v1/auth/dev-token")
def dev_token(body: DevLogin):
    cfg = settings()
    if cfg.environment != "development" or not cfg.dev_login_enabled or cfg.auth_mode != "local":
        raise RagError("NOT_FOUND", "Not found", 404)
    now = int(time.time())
    token = jwt.encode({"sub": body.subject, "tenant_id": body.tenant_id, "iss": cfg.auth_issuer,
                        "aud": [cfg.auth_audience, "workflow-agent"], "iat": now, "exp": now + 3600,
                        "roles": ["ADMIN" if body.subject == "admin" else "CUSTOMER_SERVICE"]},
                       cfg.auth_secret, algorithm="HS256")
    return {"token": token, "expires_in": 3600}


@app.get("/api/v1/me")
def me(user: User):
    return user


@app.post("/api/v1/knowledge-bases", status_code=201)
def create_kb(body: KBCreate, user: User):
    if not body.name.strip():
        raise RagError("INVALID_ARGUMENT", "Knowledge base name cannot be blank", 422)
    if user.role != "admin":
        raise RagError("FORBIDDEN", "Tenant administrator required to create knowledge bases", 403)
    kb_id = uuid4()
    with transaction(user.tenant_id) as conn:
        conn.execute("INSERT INTO knowledge_bases(tenant_id,id,name,owner) VALUES(%s,%s,%s,%s)",
                     (user.tenant_id, kb_id, body.name.strip(), user.subject))
        conn.execute("INSERT INTO kb_acl(tenant_id,kb_id,subject,role) VALUES(%s,%s,%s,'manager')",
                     (user.tenant_id, kb_id, user.subject))
        audit(conn, user, "KB_CREATED", kb_id)
    return {"id": kb_id, "name": body.name.strip()}


@app.get("/api/v1/knowledge-bases")
def list_kbs(user: User):
    with transaction(user.tenant_id) as conn:
        rows = conn.execute("""SELECT k.id,k.name,k.owner,k.created_at FROM knowledge_bases k
          WHERE k.tenant_id=%s AND (%s OR EXISTS (SELECT 1 FROM kb_acl a
          WHERE a.tenant_id=k.tenant_id AND a.kb_id=k.id AND a.subject=%s))
          ORDER BY k.created_at DESC LIMIT 100""", (user.tenant_id, user.role == "admin", user.subject)).fetchall()
    return {"items": rows}


@app.put("/api/v1/knowledge-bases/{kb_id}/acl")
def grant_kb(kb_id: UUID, body: Grant, user: User):
    with transaction(user.tenant_id) as conn:
        require_kb(conn, user, kb_id, manage=True)
        if not conn.execute("SELECT 1 FROM tenant_memberships WHERE tenant_id=%s AND subject=%s AND enabled",
                            (user.tenant_id, body.subject)).fetchone():
            raise RagError("INVALID_MEMBER", "Active member of this tenant required", 422)
        conn.execute("""INSERT INTO kb_acl(tenant_id,kb_id,subject,role) VALUES(%s,%s,%s,%s)
          ON CONFLICT(tenant_id,kb_id,subject) DO UPDATE SET role=EXCLUDED.role""",
                     (user.tenant_id, kb_id, body.subject, body.role))
        audit(conn, user, "KB_ACL_CHANGED", kb_id)
    return {"status": "updated"}


@app.delete("/api/v1/knowledge-bases/{kb_id}/acl/{subject}")
def revoke_kb(kb_id: UUID, subject: str, user: User):
    with transaction(user.tenant_id) as conn:
        require_kb(conn, user, kb_id, manage=True)
        conn.execute("DELETE FROM kb_acl WHERE tenant_id=%s AND kb_id=%s AND subject=%s",
                     (user.tenant_id, kb_id, subject))
        audit(conn, user, "KB_ACL_REVOKED", kb_id)
    return {"status": "revoked"}


@app.post("/api/v1/knowledge-bases/{kb_id}/documents", status_code=202)
def upload(kb_id: UUID, user: User, file: UploadFile = File(),
           idempotency_key: str = Header(alias="Idempotency-Key", min_length=1, max_length=200)):
    filename = Path((file.filename or "document").replace("\\", "/")).name[:200]
    data = file.file.read(settings().upload_max_bytes + 1)
    validate_file(filename, data)
    checksum = hashlib.sha256(data).hexdigest()
    request_hash = hashlib.sha256((str(kb_id) + "\n" + filename + "\n" + checksum).encode()).hexdigest()
    # Serialize same-key uploads across API replicas; no object leak on a normal replay.
    with transaction(user.tenant_id) as conn:
        require_kb(conn, user, kb_id, write=True)
        conn.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s,0))",
                     (user.tenant_id + ":" + user.subject + ":" + idempotency_key,))
        existing = conn.execute("SELECT * FROM ingestion_jobs WHERE tenant_id=%s AND actor=%s AND idempotency_key=%s",
                                (user.tenant_id, user.subject, idempotency_key)).fetchone()
        if existing:
            if existing["request_hash"] != request_hash:
                raise RagError("IDEMPOTENCY_CONFLICT", "Key already used for a different upload", 409)
            return {"job_id": existing["id"], "document_id": existing["document_id"], "version_id": existing["version_id"], "replayed": True}
        doc_id, version_id, job_id = uuid4(), uuid4(), uuid4()
        key = f"{user.tenant_id}/{doc_id}/{version_id}/original"
        # Small-file proxy upload. Object store is not part of this DB transaction;
        # orphan cleanup is documented separately, and a job never references a partial PUT.
        put_object(key, data)
        conn.execute("INSERT INTO documents(tenant_id,id,kb_id,title,version_counter) VALUES(%s,%s,%s,%s,1)",
                     (user.tenant_id, doc_id, kb_id, filename))
        conn.execute("""INSERT INTO document_versions(tenant_id,id,document_id,object_key,filename,sha256,size_bytes,created_by)
          VALUES(%s,%s,%s,%s,%s,%s,%s,%s)""", (user.tenant_id, version_id, doc_id, key, filename, checksum, len(data), user.subject))
        conn.execute("""INSERT INTO ingestion_jobs(tenant_id,id,document_id,version_id,actor,idempotency_key,request_hash)
          VALUES(%s,%s,%s,%s,%s,%s,%s)""", (user.tenant_id, job_id, doc_id, version_id,
                                           user.subject, idempotency_key, request_hash))
        conn.execute("INSERT INTO outbox_events(id,tenant_id,job_id) VALUES(%s,%s,%s)",
                     (uuid4(), user.tenant_id, job_id))
        audit(conn, user, "DOCUMENT_UPLOADED", doc_id)
    return {"job_id": job_id, "document_id": doc_id, "version_id": version_id, "replayed": False}


@app.get("/api/v1/knowledge-bases/{kb_id}/documents")
def documents(kb_id: UUID, user: User):
    with transaction(user.tenant_id) as conn:
        kb_role = require_kb(conn, user, kb_id)
        manages = lifecycle.manager(conn, user, kb_id)
        writes = manages or kb_role['kb_role'] == 'contributor'
        rows = conn.execute("""SELECT d.id,d.title,d.active_version_id,d.revision,d.content_revision,d.version_counter,j.id AS job_id,j.state,
          j.progress,j.error_code,j.error_message FROM documents d
          LEFT JOIN LATERAL (SELECT x.* FROM ingestion_jobs x JOIN document_versions v
            ON v.tenant_id=x.tenant_id AND v.id=x.version_id WHERE x.tenant_id=d.tenant_id AND x.document_id=d.id
            AND (v.first_published_at IS NOT NULL OR %s OR (%s AND v.created_by=%s))
            ORDER BY x.created_at DESC,x.id DESC LIMIT 1) j ON true
          WHERE d.tenant_id=%s AND d.kb_id=%s AND d.deleted_at IS NULL
          AND (d.allowed_subjects IS NULL OR %s=ANY(d.allowed_subjects))
          ORDER BY d.created_at DESC LIMIT 100""", (manages, writes, user.subject, user.tenant_id, kb_id, user.subject)).fetchall()
    return {"items": rows}


@app.get("/api/v1/jobs/{job_id}")
def job_status(job_id: UUID, user: User):
    with transaction(user.tenant_id) as conn:
        row = conn.execute("""SELECT id,document_id,version_id,state,attempt,progress,error_code,error_message,
          created_at,updated_at FROM ingestion_jobs WHERE tenant_id=%s AND id=%s""",
                           (user.tenant_id, job_id)).fetchone()
        if not row:
            raise RagError("NOT_FOUND", "Job not found", 404)
        doc = document_visible(conn, user, row["document_id"])
        lifecycle.get_version(conn, user, doc, row['version_id'])
        return row


@app.post("/api/v1/jobs/{job_id}/retry")
def retry_job(job_id: UUID, user: User):
    with transaction(user.tenant_id) as conn:
        job = conn.execute("SELECT * FROM ingestion_jobs WHERE tenant_id=%s AND id=%s",
                           (user.tenant_id, job_id)).fetchone()
        if not job:
            raise RagError("NOT_FOUND", "Job not found", 404)
        lifecycle.lock_document(conn, user, job["document_id"], manage=True)
        job = conn.execute('SELECT * FROM ingestion_jobs WHERE tenant_id=%s AND id=%s FOR UPDATE',
                           (user.tenant_id, job_id)).fetchone()
        version = conn.execute('SELECT lifecycle FROM document_versions WHERE tenant_id=%s AND id=%s',
                               (user.tenant_id, job['version_id'])).fetchone()
        if version['lifecycle'] != 'PROCESSING':
            raise RagError('INVALID_STATE', 'Completed or retiring versions cannot be retried/cancelled', 409)
        if job["state"] != "FAILED":
            raise RagError("INVALID_STATE", "Only failed jobs can be retried", 409)
        conn.execute("""UPDATE ingestion_jobs SET state='QUEUED',attempt=0,fence=fence+1,
          lease_until=NULL,last_enqueued_at=now(),error_code=NULL,error_message=NULL,updated_at=now()
          WHERE tenant_id=%s AND id=%s""", (user.tenant_id, job_id))
        conn.execute("INSERT INTO outbox_events(id,tenant_id,job_id) VALUES(%s,%s,%s)",
                     (uuid4(), user.tenant_id, job_id))
        audit(conn, user, "JOB_RETRIED", job_id)
    return {"status": "queued"}


@app.post("/api/v1/jobs/{job_id}/cancel")
def cancel_job(job_id: UUID, user: User):
    with transaction(user.tenant_id) as conn:
        job = conn.execute("SELECT * FROM ingestion_jobs WHERE tenant_id=%s AND id=%s", (user.tenant_id, job_id)).fetchone()
        if not job:
            raise RagError("NOT_FOUND", "Job not found", 404)
        lifecycle.lock_document(conn, user, job["document_id"], manage=True)
        job = conn.execute('SELECT * FROM ingestion_jobs WHERE tenant_id=%s AND id=%s FOR UPDATE',
                           (user.tenant_id, job_id)).fetchone()
        version = conn.execute('SELECT lifecycle FROM document_versions WHERE tenant_id=%s AND id=%s',
                               (user.tenant_id, job['version_id'])).fetchone()
        if version['lifecycle'] != 'PROCESSING':
            raise RagError('INVALID_STATE', 'Completed or retiring versions cannot be retried/cancelled', 409)
        changed = conn.execute("""UPDATE ingestion_jobs SET state='CANCELLED',fence=fence+1,
          lease_until=NULL,updated_at=now() WHERE tenant_id=%s AND id=%s AND state<>'SUCCEEDED' RETURNING id""",
                               (user.tenant_id, job_id)).fetchone()
        if not changed:
            raise RagError("INVALID_STATE", "Published job cannot be cancelled", 409)
        audit(conn, user, "JOB_CANCELLED", job_id)
    return {"status": "cancelled"}


@app.put("/api/v1/documents/{doc_id}/acl")
def document_acl(doc_id: UUID, body: DocumentACL, user: User):
    with transaction(user.tenant_id) as conn:
        document_visible(conn, user, doc_id, manage=True)
        if body.subjects:
            rows = conn.execute("SELECT subject FROM tenant_memberships WHERE tenant_id=%s AND enabled AND subject=ANY(%s)",
                                (user.tenant_id, body.subjects)).fetchall()
            if {r["subject"] for r in rows} != set(body.subjects):
                raise RagError("INVALID_MEMBER", "All document readers must belong to this tenant", 422)
        row = conn.execute("""UPDATE documents SET allowed_subjects=%s,revision=revision+1
          WHERE tenant_id=%s AND id=%s AND revision=%s RETURNING revision""",
                           (body.subjects, user.tenant_id, doc_id, body.expected_revision)).fetchone()
        if not row:
            raise RagError("REVISION_CONFLICT", "Document changed; reload before editing", 409)
        audit(conn, user, "DOCUMENT_ACL_CHANGED", doc_id)
    return row


@app.delete("/api/v1/documents/{doc_id}")
def delete_document(doc_id: UUID, user: User):
    with transaction(user.tenant_id) as conn:
        document_visible(conn, user, doc_id, manage=True)
        # Document update acquires the same lock used by Worker publication and retention.
        doc = conn.execute('SELECT * FROM documents WHERE tenant_id=%s AND id=%s FOR UPDATE',
                           (user.tenant_id, doc_id)).fetchone()
        document_visible(conn, user, doc_id, manage=True)
        conn.execute('UPDATE document_versions SET retired_at=now() WHERE tenant_id=%s AND id=%s',
                     (user.tenant_id, doc['active_version_id']))
        conn.execute('UPDATE documents SET deleted_at=now(),revision=revision+1,content_revision=content_revision+1,'
                     'active_version_id=NULL WHERE tenant_id=%s AND id=%s', (user.tenant_id, doc_id))
        conn.execute("""UPDATE ingestion_jobs SET state='CANCELLED',fence=fence+1,lease_until=NULL,updated_at=now()
          WHERE tenant_id=%s AND document_id=%s AND state<>'SUCCEEDED'""", (user.tenant_id, doc_id))
        audit(conn, user, "DOCUMENT_DELETED", doc_id)
    return {"status": "deleted", "physical_cleanup": "retained_until_explicit_retention_request"}


@app.post("/api/v1/search", response_model=SearchResponse)
async def search(body: Search, user: User, request: Request):
    return await pipeline.search(app, user, body, request.state.request_id)


@app.post("/api/v1/search/debug", response_model=SearchDebugResponse)
async def search_debug(body: Search, user: User, request: Request):
    return await pipeline.search(app, user, body, request.state.request_id, debug=True)


@app.get("/api/v1/citations/{chunk_id}")
def citation(chunk_id: UUID, user: User):
    with transaction(user.tenant_id) as conn:
        row = conn.execute('SELECT * FROM chunks WHERE tenant_id=%s AND id=%s', (user.tenant_id, chunk_id)).fetchone()
        if not row:
            tombstone = conn.execute('SELECT document_id,version_id FROM citation_tombstones WHERE tenant_id=%s AND chunk_id=%s',
                                     (user.tenant_id, chunk_id)).fetchone()
            if tombstone:
                doc = document_visible(conn, user, tombstone['document_id'])
                version = lifecycle.get_version(conn, user, doc, tombstone['version_id'])
                if not version['first_published_at']:
                    raise RagError('NOT_FOUND', 'Citation is not published', 404)
                raise RagError('CITATION_GONE', 'Original cited version was removed by retention policy', 410)
            raise RagError('NOT_FOUND', 'Citation not found', 404)
        doc = document_visible(conn, user, row['document_id'])
        version = lifecycle.get_version(conn, user, doc, row['version_id'])
        lifecycle.require_retained(version)
        if not version['first_published_at']:
            raise RagError('NOT_FOUND', 'Citation is not published', 404)
        return {'chunk_id': row['id'], 'document_id': doc['id'], 'version_id': version['id'],
                'version_number': version['version_number'], 'is_current': doc['active_version_id'] == version['id'],
                'status': 'current' if doc['active_version_id'] == version['id'] else 'historical',
                'title': version['filename'], 'text': row['content'], 'page': row['page'], 'section': row['section'],
                'content_hash': row['content_hash'], 'content_revision': doc['content_revision']}


@app.get('/api/v1/documents/{doc_id}/original')
def original(doc_id: UUID, user: User):
    return download_version(doc_id, None, user)


static = Path(__file__).parent / "static"
app.mount("/static", StaticFiles(directory=static), name="static")


@app.get("/", include_in_schema=False)
def index():
    return FileResponse(static / "index.html")

