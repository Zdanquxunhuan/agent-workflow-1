"""Snapshot/verify exact old chunk IDs, text hashes and vectors across the additive V2 upgrade."""
import argparse
import hashlib
import json
import sys
from pathlib import Path

import psycopg

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config import settings
from app.retrieval.lexical import PROFILE


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('mode', choices=['before', 'after'])
    parser.add_argument('snapshot', type=Path)
    args = parser.parse_args()
    with psycopg.connect(settings().migration_url.replace('postgresql+psycopg://', 'postgresql://')) as conn:
        rows = conn.execute('SELECT tenant_id,id,content_hash,embedding::text FROM chunks ORDER BY tenant_id,id').fetchall()
        snapshot = {tenant + '/' + str(chunk): {'content_hash': content_hash,
                    'vector_sha256': hashlib.sha256(vector.encode()).hexdigest()} for tenant, chunk, content_hash, vector in rows}
        if args.mode == 'before':
            if args.snapshot.exists():
                raise RuntimeError('Refusing to overwrite the original snapshot')
            args.snapshot.write_text(json.dumps(snapshot, indent=2))
            print(json.dumps({'snapshot_chunks': len(snapshot)}))
        else:
            original = json.loads(args.snapshot.read_text())
            assert all(snapshot.get(key) == value for key, value in original.items()), 'Old chunk/vector changed'
            missing = conn.execute('''SELECT count(*) FROM chunks c JOIN documents d
                ON d.tenant_id=c.tenant_id AND d.id=c.document_id WHERE d.deleted_at IS NULL
                AND d.active_version_id=c.version_id AND NOT EXISTS
                (SELECT 1 FROM chunk_lexical l WHERE l.tenant_id=c.tenant_id AND l.chunk_id=c.id
                 AND l.profile=%s AND l.content_hash=c.content_hash)''', (PROFILE,)).fetchone()[0]
            assert missing == 0, 'Incomplete lexical backfill'
            print(json.dumps({'unchanged_old_chunks': len(original), 'missing_live_lexical': missing}))


if __name__ == '__main__':
    main()
