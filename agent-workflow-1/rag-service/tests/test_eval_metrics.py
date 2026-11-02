import pytest

from evals.metrics import ranking_metrics, summarize, percentile


def test_metrics_use_graded_document_relevance_and_deduplicate_chunks():
    assert ranking_metrics(['a', 'a', 'b'], {'a': 2, 'b': 1}) == {'recall_at_20': 1, 'ndcg_at_10': 1, 'mrr': 1}
    result = ranking_metrics(['x', 'b'], {'a': 2, 'b': 1})
    assert result['recall_at_20'] == .5 and result['mrr'] == .5
    assert 0 < result['ndcg_at_10'] < .2
    assert ranking_metrics([], {}) == {'has_evidence': False}
    assert ranking_metrics(['x'], {}) == {'has_evidence': True}
    assert percentile([10, 20, 30], 95) == 29


def test_failed_answerable_requests_remain_in_metric_denominator():
    rows = [{'metrics': ranking_metrics([], {'a': 2}), 'kind': 'semantic', 'retrieved': [], 'http_status': 503,
             'forbidden_evidence': [], 'latency_ms': 10},
            {'metrics': ranking_metrics(['a'], {'a': 2}), 'kind': 'semantic', 'retrieved': ['a'], 'http_status': 200,
             'forbidden_evidence': [], 'latency_ms': 20}]
    assert summarize(rows)['recall_at_20'] == .5
    assert summarize(rows)['errors'] == 1
