"""One ephemeral policy for the Java V5 smoke test. Run only via run_tests.py.

Actual PG/S3/API/lexical retrieval; ingestion embeddings are explicit test doubles.
No real business documents. The parent harness drops the random DB and bucket.
"""
import hashlib
import os
import sys
import threading
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
if os.environ.get('RAG_INTEGRATION') != '1':
    raise RuntimeError('isolated test harness required')

from fastapi.testclient import TestClient
import uvicorn
from app.config import settings
from app import worker
from app.main import app

settings().retrieval_profile = 'lexical-v2'
worker.embed = lambda texts: [[1.0] + [0.0] * 1023 for _ in texts]
worker.chunk_text = lambda text: [{'text': text, 'token_count': len(text),
                                  'content_hash': hashlib.sha256(text.encode()).hexdigest()}]
with TestClient(app) as client:
    token = client.post('/api/v1/auth/dev-token', json={'subject': 'admin', 'tenant_id': 'demo'}).json()['token']
    auth = {'Authorization': 'Bearer ' + token}
    kb = client.post('/api/v1/knowledge-bases', json={'name': 'V5 synthetic refund policy'}, headers=auth)
    kb.raise_for_status()
    upload = client.post(f"/api/v1/knowledge-bases/{kb.json()['id']}/documents",
                         files={'file': ('v5-policy.md', '已发货订单退款需要主管审批。'.encode())},
                         headers=auth | {'Idempotency-Key': 'v5-policy'})
    upload.raise_for_status()
    worker.ingest('demo', upload.json()['job_id'])

server = uvicorn.Server(uvicorn.Config(app, host='0.0.0.0', port=8091, log_level='warning'))
marker = Path('/handoff/v5-fixture-stop')
def stop_when_requested():
    deadline = time.monotonic() + 600
    while time.monotonic() < deadline and not marker.exists():
        time.sleep(1)
    server.should_exit = True
threading.Thread(target=stop_when_requested, daemon=True).start()
print('V5 fixture ready: one synthetic policy; lexical retrieval; embedding double', flush=True)
server.run()
