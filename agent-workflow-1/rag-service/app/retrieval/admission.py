"""Per-tenant rate/concurrency admission shared by API replicas, with crash-expiring leases."""
from uuid import uuid4

from app.config import settings
from app.errors import RagError
from app.retrieval.database import transaction, authorize


async def acquire(pool, user, kb_ids, deadline, debug):
    cfg, admission = settings(), uuid4()
    async with transaction(pool, user.tenant_id, deadline) as conn:
        await authorize(conn, user, kb_ids, debug)
        await conn.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s,9))", (user.tenant_id,))
        await conn.execute("DELETE FROM search_admissions WHERE tenant_id=%s "
                           "AND created_at<clock_timestamp()-interval '60 seconds' AND lease_until<clock_timestamp()",
                           (user.tenant_id,))
        counts = await (await conn.execute("""SELECT
          count(*) FILTER(WHERE created_at>clock_timestamp()-interval '60 seconds') AS requests,
          count(*) FILTER(WHERE lease_until>clock_timestamp()) AS active
          FROM search_admissions WHERE tenant_id=%s""", (user.tenant_id,))).fetchone()
        if counts["requests"] >= cfg.search_requests_per_minute or counts["active"] >= cfg.search_tenant_concurrency:
            raise RagError("SEARCH_RATE_LIMITED", "Tenant search rate or concurrency limit reached", 429, True)
        await conn.execute("INSERT INTO search_admissions(tenant_id,id,subject,lease_until) "
                           "VALUES(%s,%s,%s,clock_timestamp()+(%s*interval '1 second'))",
                           (user.tenant_id, admission, user.subject, cfg.search_deadline_seconds + 5))
    return admission


async def release(pool, user, admission, deadline):
    async with transaction(pool, user.tenant_id, deadline) as conn:
        await conn.execute("UPDATE search_admissions SET lease_until=clock_timestamp() WHERE tenant_id=%s AND id=%s",
                           (user.tenant_id, admission))
