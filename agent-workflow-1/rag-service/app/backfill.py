"""Idempotently index existing chunks without re-uploading originals or recomputing vectors."""
import argparse
import json

import psycopg

from app.config import settings
from app.db import transaction
from app.retrieval.lexical import PROFILE, index_payload

UPSERT = """INSERT INTO chunk_lexical(tenant_id,chunk_id,profile,content_hash,lexemes,identifiers)
  SELECT tenant_id,id,%s,content_hash,%s,%s FROM chunks WHERE tenant_id=%s AND id=%s AND content_hash=%s
  ON CONFLICT(tenant_id,chunk_id,profile) DO UPDATE SET content_hash=EXCLUDED.content_hash,
  lexemes=EXCLUDED.lexemes,identifiers=EXCLUDED.identifiers"""


def backfill(tenant, batch_size=100):
    count = 0
    while True:
        with transaction(tenant) as conn:
            rows = conn.execute("""SELECT c.id,c.content,c.content_hash FROM chunks c
              JOIN documents d ON d.tenant_id=c.tenant_id AND d.id=c.document_id
              LEFT JOIN chunk_lexical l ON l.tenant_id=c.tenant_id AND l.chunk_id=c.id AND l.profile=%s
              WHERE c.tenant_id=%s AND d.deleted_at IS NULL AND d.active_version_id=c.version_id
              AND (l.chunk_id IS NULL OR l.content_hash<>c.content_hash)
              ORDER BY c.id LIMIT %s""", (PROFILE, tenant, batch_size)).fetchall()
        if not rows:
            return count
        values = []
        for row in rows:
            lexemes, identifiers = index_payload(row["content"])
            values.append((PROFILE, lexemes, identifiers, tenant, row["id"], row["content_hash"]))
        with transaction(tenant) as conn:
            with conn.cursor() as cur:
                cur.executemany(UPSERT, values)
        count += len(rows)


def main():
    parser = argparse.ArgumentParser()
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--tenant")
    group.add_argument("--all-tenants", action="store_true")
    args = parser.parse_args()
    if args.all_tenants:
        # Provisioning role used only to enumerate tenants, not for runtime retrieval.
        with psycopg.connect(settings().migration_url.replace("postgresql+psycopg://", "postgresql://")) as conn:
            tenants = [row[0] for row in conn.execute("SELECT id FROM tenants ORDER BY id")]
    else:
        tenants = [args.tenant]
    for tenant in tenants:
        print(json.dumps({"tenant": tenant, "indexed": backfill(tenant), "lexical_profile": PROFILE}))


if __name__ == "__main__":
    main()
