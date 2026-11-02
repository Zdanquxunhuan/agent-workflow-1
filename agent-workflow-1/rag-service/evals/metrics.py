import math


def ranking_metrics(retrieved, relevance):
    # Document-level qrels: repeated chunks must not inflate recall/DCG.
    ids = list(dict.fromkeys(retrieved))
    relevant = {key for key, grade in relevance.items() if grade > 0}
    if not relevant:
        return {'has_evidence': bool(ids)}  # Retrieval alone has no answer/refusal classifier.
    dcg = sum((2 ** relevance.get(key, 0) - 1) / math.log2(rank + 1)
              for rank, key in enumerate(ids[:10], 1))
    ideal = sum((2 ** grade - 1) / math.log2(rank + 1)
                for rank, grade in enumerate(sorted(relevance.values(), reverse=True)[:10], 1))
    return {'recall_at_20': len(relevant.intersection(ids[:20])) / len(relevant),
            'ndcg_at_10': dcg / ideal,
            'mrr': next((1 / rank for rank, key in enumerate(ids, 1) if key in relevant), 0.0)}


def percentile(values, percent):
    if not values:
        return None
    values = sorted(values)
    position = (len(values) - 1) * percent / 100
    lo, hi = math.floor(position), math.ceil(position)
    return round(values[lo] + (values[hi] - values[lo]) * (position - lo), 2)


def summarize(rows):
    answered = [row for row in rows if 'recall_at_20' in row['metrics']]
    no_answer = [row for row in rows if row['kind'] == 'no_answer']
    return {'queries': len(rows), 'errors': sum(row['http_status'] != row.get('expected_status', 200) for row in rows),
            'degraded': sum(row.get('status') == 'degraded' for row in rows),
            'forbidden_evidence': sum(len(row['forbidden_evidence']) for row in rows),
            'answerable_queries': len(answered),
            **{metric: round(sum(row['metrics'][metric] for row in answered) / len(answered), 4)
               if answered else None for metric in ('recall_at_20', 'ndcg_at_10', 'mrr')},
            'no_answer_evidence_rate': round(sum(bool(row['retrieved']) for row in no_answer) / len(no_answer), 4)
            if no_answer else None,
            'latency_ms': {f'p{p}': percentile([row['latency_ms'] for row in rows], p) for p in (50, 95, 99)}}
