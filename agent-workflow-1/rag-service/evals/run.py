"""Run through the real API/auth/RLS and real models in an isolated DB/bucket.

python scripts/run_tests.py python -m evals.run --output /reports/v2.json
Only transport is in-process ASGI. Ingestion invokes the real worker directly;
MQ is exercised separately by scripts/acceptance.py. No model substitution.
"""
import argparse
import hashlib
import json
import logging
import os
import platform
import time
from collections import defaultdict
from pathlib import Path

from fastapi.testclient import TestClient

from app import worker
from app.config import settings
from app.main import app
from app.retrieval.lexical import PROFILE as LEXICAL_PROFILE
from app.retrieval.profiles import PROFILES, fingerprint
from evals.metrics import ranking_metrics, summarize


def read_lines(path):
    return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines() if line.strip()]


def checked(response, expected=200):
    if response.status_code != expected:
        raise RuntimeError(f'HTTP {response.status_code}: {response.text[:500]}')
    return response.json()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--dataset', type=Path, default=Path(__file__).with_name('synthetic-v1'))
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--profiles', nargs='+', choices=list(PROFILES), default=list(PROFILES))
    args = parser.parse_args()
    if settings().environment != 'development' or not settings().database_url.rsplit('/', 1)[-1].startswith('rag_test_'):
        raise RuntimeError('Run via scripts/run_tests.py; evaluation must use an isolated random database')
    logging.getLogger('httpx').setLevel(logging.WARNING)
    logging.getLogger('app.main').setLevel(logging.WARNING)
    logging.getLogger('app.retrieval.pipeline').setLevel(logging.WARNING)
    documents, questions = read_lines(args.dataset / 'documents.jsonl'), read_lines(args.dataset / 'queries.jsonl')
    manifest = json.loads((args.dataset / 'manifest.json').read_text())
    for filename, expected in manifest['sha256'].items():
        if hashlib.sha256((args.dataset / filename).read_bytes()).hexdigest() != expected:
            raise RuntimeError('Frozen corpus hash mismatch: ' + filename)
    rows, identities, kbs, doc_ids = [], {}, {}, {}
    with TestClient(app, raise_server_exceptions=False) as client:
        for tenant in sorted({doc['tenant'] for doc in documents}):
            for subject in ('admin', 'alice'):
                token = checked(client.post('/api/v1/auth/dev-token', json={'tenant_id': tenant, 'subject': subject}))['token']
                identities[tenant, subject] = {'Authorization': 'Bearer ' + token}
            auth = identities[tenant, 'admin']
            kbs[tenant] = checked(client.post('/api/v1/knowledge-bases', headers=auth,
                                            json={'name': 'Synthetic retrieval benchmark'}), 201)['id']
            checked(client.put(f'/api/v1/knowledge-bases/{kbs[tenant]}/acl', headers=auth,
                               json={'subject': 'alice', 'role': 'manager'}))
        for document in documents:
            tenant, auth = document['tenant'], identities[document['tenant'], 'admin']
            upload = checked(client.post(f'/api/v1/knowledge-bases/{kbs[tenant]}/documents',
                headers=auth | {'Idempotency-Key': document['id']},
                files={'file': (document['id'] + '.md', (document['title'] + '\n' + document['text']).encode())}), 202)
            worker.ingest(tenant, upload['job_id'])
            state = checked(client.get('/api/v1/jobs/' + upload['job_id'], headers=auth))
            if state['state'] != 'SUCCEEDED':
                raise RuntimeError(f'Ingestion failed: {document["id"]} {state["state"]}')
            if document.get('allowed_subjects'):
                checked(client.put('/api/v1/documents/' + upload['document_id'] + '/acl', headers=auth,
                                   json={'subjects': document['allowed_subjects'], 'expected_revision': 1}))
            doc_ids[upload['document_id']] = document['id']
            print('indexed', document['id'], flush=True)
        # Rotate profile order per question to reduce systematic cold-start/order bias.
        for number, question in enumerate(questions):
            offset = number % len(args.profiles)
            for profile in args.profiles[offset:] + args.profiles[:offset]:
                start = time.monotonic()
                response = client.post('/api/v1/search/debug', headers=identities[question['tenant'], question['subject']],
                    json={'query': question['query'], 'knowledge_base_ids': [kbs[question.get('kb_tenant', question['tenant'])]],
                          'profile': profile, 'top_k': 20})
                duration = (time.monotonic() - start) * 1000
                result = response.json()
                retrieved = list(dict.fromkeys(doc_ids[hit['document_id']] for hit in result.get('hits', [])))
                candidates = list(dict.fromkeys(doc_ids[hit['document_id']] for hit in result.get('debug', {}).get('candidates', [])))
                forbidden = sorted(set(retrieved + candidates).intersection(question.get('forbidden', [])))
                rows.append({'id': question['id'], 'split': question['split'], 'kind': question['kind'],
                    'profile': profile, 'http_status': response.status_code, 'status': result.get('status'),
                    'expected_status': question.get('expected_status', 200),
                    'error': result.get('code'), 'degraded_components': result.get('degraded_components', []),
                    'latency_ms': round(duration, 2), 'timings_ms': result.get('timings_ms', {}),
                    'retrieved': retrieved, 'candidate_documents': candidates, 'forbidden_evidence': forbidden,
                    'metrics': ranking_metrics(retrieved, question['relevance'])})
            print('evaluated', number + 1, '/', len(questions), question['id'], flush=True)
    grouped = defaultdict(list)
    for row in rows:
        grouped[row['profile']].append(row)
    report = {'dataset': manifest, 'model_substitution': False, 'transport': 'TestClient ASGI; real PG/S3/models; serial requests',
              'ingestion': 'real worker invoked directly; MQ covered by separate acceptance',
              'environment': {'python': platform.python_version(), 'cpu_logical': os.cpu_count(),
                  'embedding_profile': settings().embedding_profile, 'lexical_profile': LEXICAL_PROFILE,
                  'reranker_profile': settings().reranker_profile, 'search_deadline_seconds': settings().search_deadline_seconds},
              'profiles': {p: {'config': PROFILES[p], 'fingerprint': fingerprint(PROFILES[p], settings().embedding_profile, settings().reranker_profile)} for p in args.profiles},
              'summary': {profile: summarize(values) for profile, values in grouped.items()},
              'by_split': {profile: {split: summarize([r for r in values if r['split'] == split])
                                    for split in ('development', 'holdout')} for profile, values in grouped.items()},
              'by_kind': {profile: {kind: summarize([r for r in values if r['kind'] == kind])
                                   for kind in sorted({r['kind'] for r in values})} for profile, values in grouped.items()},
              'rows': rows}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report['summary'], ensure_ascii=False, indent=2), flush=True)
    if any(row['forbidden_evidence'] or row['http_status'] != row['expected_status'] for row in rows):
        raise SystemExit('Evaluation contains access violations or unexpected HTTP status; report saved')


if __name__ == '__main__':
    main()
