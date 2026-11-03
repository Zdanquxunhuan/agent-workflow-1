"""One real DeepSeek answer; real isolated PG/S3, explicitly synthetic indexing vectors.

Run only through scripts/run_tests.py. This is not a BGE/MQ/semantic-quality acceptance test.
"""
import hashlib
import json
import os
import sys
from pathlib import Path
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fastapi.testclient import TestClient
from app import worker
from app.config import settings
from app.main import app


def check(response, status=200):
    assert response.status_code == status, (response.status_code, response.text)
    return response.json()


def main():
    if os.environ.get('RAG_INTEGRATION') != '1' or 'rag_test_' not in settings().database_url:
        raise RuntimeError('Run through the isolated test harness')
    if not settings().answer_enabled or not settings().answer_api_key.get_secret_value():
        raise RuntimeError('A configured answer provider is required')
    source = '已发货订单退款需要主管审批。未发货订单可以申请修改收货地址。'
    # The only live inference in this small smoke test is the answer provider.
    worker.embed = lambda texts: [[1.0] + [0.0] * 1023 for _ in texts]
    worker.chunk_text = lambda text: [{'text': text, 'token_count': len(text),
                                      'content_hash': hashlib.sha256(text.encode()).hexdigest()}]
    with TestClient(app, raise_server_exceptions=False) as client:
        token = check(client.post('/api/v1/auth/dev-token', json={'tenant_id': 'demo', 'subject': 'admin'}))['token']
        auth = {'Authorization': 'Bearer ' + token}
        kb = check(client.post('/api/v1/knowledge-bases', headers=auth, json={'name': 'V4 single-policy smoke'}), 201)['id']
        doc = check(client.post(f'/api/v1/knowledge-bases/{kb}/documents',
            headers=auth | {'Idempotency-Key': str(uuid4())}, files={'file': ('policy.md', source.encode())}), 202)
        worker.ingest('demo', doc['job_id'])
        result = check(client.post('/api/v1/answers', headers=auth, json={
            'query': '已发货订单退款需要谁审批？', 'knowledge_base_ids': [kb], 'profile': 'lexical-v2'}))
        assert result['status'] == 'answered' and result['citations'], result
        assert '主管' in result['answer'], result
        for citation in result['citations']:
            assert citation['quote'] in source and citation['document_version_id'] == doc['version_id']
            check(client.get(citation['url'], headers=auth))
        check(client.post(f"/api/v1/answers/{result['answer_id']}/feedback", headers=auth, json={'rating': 'helpful'}))
    report = {'result': 'passed', 'provider': settings().answer_provider, 'model': result['model'],
              'live_answer_calls': 1, 'synthetic_documents': 1, 'retrieval_profile': 'lexical-v2',
              'indexing_vectors': 'explicit test double; not a BGE or MQ acceptance',
              'answer': result['answer'], 'quotes': [c['quote'] for c in result['citations']],
              'usage': result['usage'], 'timings_ms': result['timings_ms']}
    if len(sys.argv) > 1:
        Path(sys.argv[1]).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
