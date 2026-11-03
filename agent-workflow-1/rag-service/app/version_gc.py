"""Explicit per-tenant retention runner. Only processes manager-enqueued tombstones."""
import argparse
import logging
import time

from app.auth import Principal
from app.config import settings
from app.db import transaction
from app.errors import RagError
from app.lifecycle import audit, lock_document
from app.storage import s3

log = logging.getLogger(__name__)


def process_one(tenant):
    # Candidate selection deliberately takes no task lock: lock order is always document -> task.
    with transaction(tenant) as conn:
        candidate = conn.execute("""SELECT version_id,document_id FROM version_gc_tasks WHERE tenant_id=%s AND
          ((state IN ('QUEUED','RETRY_WAIT') AND next_attempt_at<=now()) OR
           (state='RUNNING' AND lease_until<now())) ORDER BY next_attempt_at,version_id LIMIT 1""", (tenant,)).fetchone()
    if not candidate:
        return False
    with transaction(tenant) as conn:
        conn.execute('SELECT id FROM documents WHERE tenant_id=%s AND id=%s FOR UPDATE', (tenant, candidate['document_id']))
        task = conn.execute("""UPDATE version_gc_tasks SET state='RUNNING',attempt=attempt+1,fence=fence+1,
          lease_until=now()+interval '120 seconds' WHERE tenant_id=%s AND version_id=%s AND
          ((state IN ('QUEUED','RETRY_WAIT') AND next_attempt_at<=now()) OR
           (state='RUNNING' AND lease_until<now())) RETURNING *""", (tenant, candidate['version_id'])).fetchone()
        if not task:
            return True
    try:
        with transaction(tenant) as conn:
            doc = lock_document(conn, Principal(tenant, task['actor'], 'member'), task['document_id'], manage=True, deleted=True)
            current = conn.execute("SELECT 1 FROM version_gc_tasks WHERE tenant_id=%s AND version_id=%s "
                                   "AND state='RUNNING' AND fence=%s AND lease_until>now() FOR UPDATE",
                                   (tenant, task['version_id'], task['fence'])).fetchone()
            if not current:
                return True
            version = conn.execute('SELECT * FROM document_versions WHERE tenant_id=%s AND id=%s',
                                   (tenant, task['version_id'])).fetchone()
            if doc['active_version_id'] == version['id'] or version['retention_hold'] or version['lifecycle'] != 'PURGING':
                raise RagError('RETENTION_INVARIANT', 'Version cannot be collected', 409)
            if conn.execute("SELECT 1 FROM ingestion_jobs WHERE tenant_id=%s AND version_id=%s "
                            "AND state NOT IN ('SUCCEEDED','FAILED','CANCELLED')", (tenant, version['id'])).fetchone():
                raise RagError('RETENTION_INVARIANT', 'Version has an active job', 409)
            expected_key = f"{tenant}/{task['document_id']}/{task['version_id']}/original"
            if version['object_key'] != expected_key:
                raise RagError('RETENTION_OBJECT_MISMATCH', 'Unexpected object key', 409)
        # DELETE is idempotent. A crash after S3 deletion is resumed using the same tombstone.
        s3().delete_object(Bucket=settings().s3_bucket, Key=expected_key)
        with transaction(tenant) as conn:
            conn.execute('SELECT id FROM documents WHERE tenant_id=%s AND id=%s FOR UPDATE', (tenant, task['document_id']))
            current = conn.execute('SELECT * FROM version_gc_tasks WHERE tenant_id=%s AND version_id=%s FOR UPDATE',
                                   (tenant, task['version_id'])).fetchone()
            valid = conn.execute('SELECT %s>now() AS valid', (current['lease_until'],)).fetchone()['valid']
            if current['state'] != 'RUNNING' or current['fence'] != task['fence'] or not valid:
                return True
            conn.execute('''INSERT INTO citation_tombstones(tenant_id,chunk_id,document_id,version_id)
              SELECT tenant_id,id,document_id,version_id FROM chunks WHERE tenant_id=%s AND version_id=%s
              ON CONFLICT DO NOTHING''', (tenant, task['version_id']))
            conn.execute('DELETE FROM chunks WHERE tenant_id=%s AND version_id=%s', (tenant, task['version_id']))
            conn.execute("UPDATE document_versions SET lifecycle='PURGED',purged_at=now() WHERE tenant_id=%s AND id=%s",
                         (tenant, task['version_id']))
            conn.execute("UPDATE version_gc_tasks SET state='DONE',completed_at=now(),lease_until=NULL,error_code=NULL "
                         'WHERE tenant_id=%s AND version_id=%s', (tenant, task['version_id']))
            audit(conn, tenant, task['actor'], 'VERSION_PURGED', task['version_id'])
    except Exception as exc:
        code = exc.code if isinstance(exc, RagError) else 'GC_STORAGE_OR_DATABASE_ERROR'
        with transaction(tenant) as conn:
            conn.execute("UPDATE version_gc_tasks SET state='RETRY_WAIT',error_code=%s,lease_until=NULL,"
                         "next_attempt_at=now()+(%s*interval '1 second') WHERE tenant_id=%s AND version_id=%s "
                         "AND fence=%s AND state='RUNNING'", (code, min(3600, 30 * 2 ** min(task['attempt'], 7)),
                                                            tenant, task['version_id'], task['fence']))
        log.warning('version_gc_failed version=%s code=%s', task['version_id'], code)
    return True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--tenant', required=True)
    parser.add_argument('--once', action='store_true', help='Drain currently runnable requests and exit')
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO)
    while True:
        try:
            processed = process_one(args.tenant)
        except Exception:
            log.exception('version_gc_loop_failed')
            if args.once:
                raise
            processed = False
        if not processed:
            if args.once:
                break
            time.sleep(5)


if __name__ == '__main__':
    main()
