from contextlib import asynccontextmanager
import time

from psycopg.rows import dict_row
from psycopg_pool import AsyncConnectionPool

from app.config import settings
from app.errors import RagError


def remaining(deadline):
    seconds = deadline - time.monotonic()
    if seconds <= 0:
        raise RagError("SEARCH_DEADLINE", "Search deadline exceeded", 504, True)
    return seconds


def create_pool():
    return AsyncConnectionPool(settings().database_url, min_size=1, max_size=8, open=False,
                               timeout=5, kwargs={"row_factory": dict_row})


@asynccontextmanager
async def transaction(pool, tenant, deadline):
    async with pool.connection(timeout=min(5.0, remaining(deadline))) as conn:
        async with conn.transaction():
            await conn.execute("SELECT set_config('app.tenant_id',%s,true)", (tenant,))
            await conn.execute("SELECT set_config('statement_timeout',%s,true)",
                               (str(max(1, int(min(10.0, remaining(deadline)) * 1000))),))
            await conn.execute("SET LOCAL lock_timeout='1000ms'")
            yield conn


async def authorize(conn, user, kb_ids, debug=False):
    member = await (await conn.execute("SELECT role FROM tenant_memberships WHERE tenant_id=%s "
                                     "AND subject=%s AND enabled", (user.tenant_id, user.subject))).fetchone()
    if not member:
        raise RagError("FORBIDDEN", "Active tenant membership required", 403)
    for kb_id in kb_ids:
        row = await (await conn.execute("""SELECT a.role FROM knowledge_bases k
          LEFT JOIN kb_acl a ON a.tenant_id=k.tenant_id AND a.kb_id=k.id AND a.subject=%s
          WHERE k.tenant_id=%s AND k.id=%s""", (user.subject, user.tenant_id, kb_id))).fetchone()
        if not row or (member["role"] != "admin" and row["role"] is None):
            raise RagError("NOT_FOUND", "Knowledge base not found", 404)
        if debug and member["role"] != "admin" and row["role"] != "manager":
            raise RagError("FORBIDDEN", "Knowledge base manager required for search diagnostics", 403)


# Every channel applies these SQL predicates before any candidate is returned to Python.
# The membership/KB conditions are also part of the statement, not just an earlier check.
SCOPE = """c.tenant_id=%(tenant)s AND d.kb_id=ANY(%(kbs)s) AND d.deleted_at IS NULL
  AND d.active_version_id=c.version_id AND c.embedding_profile=%(embedding_profile)s
  AND (d.allowed_subjects IS NULL OR %(subject)s=ANY(d.allowed_subjects))
  AND EXISTS (SELECT 1 FROM tenant_memberships m WHERE m.tenant_id=c.tenant_id
    AND m.subject=%(subject)s AND m.enabled AND (m.role='admin' OR EXISTS
      (SELECT 1 FROM kb_acl a WHERE a.tenant_id=c.tenant_id AND a.kb_id=d.kb_id AND a.subject=m.subject)))"""
FIELDS = """c.id AS chunk_id,c.document_id,c.version_id AS document_version_id,d.title,
  c.content AS text,c.page,c.section,c.content_hash,c.token_count,c.ordinal"""
JOIN = "FROM chunks c JOIN documents d ON d.tenant_id=c.tenant_id AND d.id=c.document_id"
TIE = "c.content_hash,d.title,c.ordinal,c.id"


def parameters(user, kb_ids, embedding_profile):
    return {"tenant": user.tenant_id, "subject": user.subject, "kbs": kb_ids,
            "embedding_profile": embedding_profile}


async def visible(conn, params, ids):
    if not ids:
        return set()
    rows = await (await conn.execute("SELECT c.id " + JOIN + " WHERE " + SCOPE + " AND c.id=ANY(%(ids)s)",
                                    params | {"ids": ids})).fetchall()
    return {str(row["id"]) for row in rows}
