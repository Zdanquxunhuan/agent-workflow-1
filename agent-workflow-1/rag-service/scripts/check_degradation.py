"""Observe a live reranker stop/start drill; does not stop infrastructure itself."""
import argparse
import json
import os

import httpx


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('expected', choices=['healthy', 'unavailable'])
    args = parser.parse_args()
    with httpx.Client(base_url=os.environ.get('RAG_ACCEPTANCE_URL', 'http://api:8091'), timeout=20) as client:
        response = client.post('/api/v1/auth/dev-token', json={'tenant_id': 'demo', 'subject': 'admin'})
        response.raise_for_status()
        client.headers['Authorization'] = 'Bearer ' + response.json()['token']
        kbs = client.get('/api/v1/knowledge-bases')
        kbs.raise_for_status()
        result = client.post('/api/v1/search', json={'query': '已发货订单退款审批',
            'knowledge_base_ids': [kbs.json()['items'][0]['id']], 'profile': 'hybrid-rerank-v2'})
        result.raise_for_status()
        data = result.json()
        assert data['hits'], data
        if args.expected == 'unavailable':
            assert data['status'] == 'degraded' and data['degraded_components'] == ['reranker'], data
            assert data['reranker_profile'] is None
            assert all(hit['score_type'] == 'rrf' for hit in data['hits'])
        else:
            assert data['status'] == 'ok' and data['reranker_profile'], data
            assert data['hits'][0]['score_type'] == 'reranker_logit'
        print(json.dumps({'expected': args.expected, 'status': data['status'],
                          'degraded': data['degraded_components'], 'timings_ms': data['timings_ms']}))


if __name__ == '__main__':
    main()
