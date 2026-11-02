"""Transactional-outbox relay and durable-job recovery. Run as restricted queue role."""
import logging
import signal
import time
from uuid import uuid4

from app.config import settings
from app.db import transaction
from app.queue import celery_app

log = logging.getLogger(__name__)
running = True


def recover():
    cfg = settings()
    with transaction() as conn:
        rows = conn.execute("""SELECT * FROM ingestion_jobs WHERE
          (state IN ('PARSING','EMBEDDING','INDEXING') AND lease_until < now()) OR
          (state='RETRY_WAIT' AND next_attempt_at <= now()) OR
          (state='QUEUED' AND last_enqueued_at < now() - (%s * interval '1 second'))
          ORDER BY updated_at LIMIT 20 FOR UPDATE SKIP LOCKED""", (cfg.recovery_seconds,)).fetchall()
        for row in rows:
            if row["attempt"] >= cfg.max_attempts:
                conn.execute("""UPDATE ingestion_jobs SET state='FAILED',fence=fence+1,lease_until=NULL,
                  error_code='RETRIES_EXHAUSTED',error_message='Retry budget exhausted',updated_at=now()
                  WHERE tenant_id=%s AND id=%s""", (row["tenant_id"], row["id"]))
                continue
            conn.execute("""UPDATE ingestion_jobs SET state='QUEUED',fence=fence+1,lease_until=NULL,
              last_enqueued_at=now(),updated_at=now() WHERE tenant_id=%s AND id=%s""",
                         (row["tenant_id"], row["id"]))
            conn.execute("INSERT INTO outbox_events(id,tenant_id,job_id) VALUES(%s,%s,%s)",
                         (uuid4(), row["tenant_id"], row["id"]))


def dispatch_once():
    with transaction() as conn:
        row = conn.execute("""SELECT * FROM outbox_events WHERE sent_at IS NULL AND available_at<=now()
          ORDER BY available_at LIMIT 1 FOR UPDATE SKIP LOCKED""").fetchone()
        if not row:
            return False
        # A crash after confirm and before COMMIT duplicates delivery, never the business effect.
        celery_app.send_task("rag.ingest", args=[row["tenant_id"], str(row["job_id"])],
                             task_id=str(row["id"]), retry=False)
        conn.execute("UPDATE outbox_events SET sent_at=now() WHERE id=%s", (row["id"],))
    return True


def stop(*_):
    global running
    running = False


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    while running:
        try:
            recover()
            if not dispatch_once():
                time.sleep(1)
        except Exception as exc:
            log.error("relay_error type=%s", type(exc).__name__)
            time.sleep(3)

