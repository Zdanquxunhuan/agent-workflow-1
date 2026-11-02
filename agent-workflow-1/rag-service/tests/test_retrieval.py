import asyncio
import time

import httpx
import pytest

from app.config import settings
from app.errors import RagError
from app.retrieval import lexical, models
from app.retrieval.ranking import assemble, fuse


def test_chinese_terms_preserve_negation_and_normalize_identifiers():
    words = lexical.terms('未发货订单不需要审批，已发货订单需要主管审批。')
    assert '未发货' in words and '已发货' in words and '不' in words
    assert lexical.identifiers('编号ＡＢＣ－１２３和 err_42，ABC-123') == ['abc-123', 'err_42']
    assert lexical.query_expression("退款 | !删除 & 'abc'") == lexical.query_expression('退款 删除 abc')


def test_rrf_agreement_beats_single_channel_and_keeps_original_distance():
    a = dict(chunk_id='a', distance=0.2, channel_score=0.8)
    b = dict(chunk_id='b', distance=0.3, channel_score=0.7)
    result = fuse({'dense': [a, b], 'lexical': [dict(b, distance=None, channel_score=90)]})
    assert [r['chunk_id'] for r in result] == ['b', 'a']
    assert result[0]['distance'] == 0.3
    assert result[0]['fusion_score'] == pytest.approx(1 / 62 + 1 / 61)
    assert fuse({'dense': [a, a]})[0]['fusion_score'] == pytest.approx(1 / 61)


def test_context_dedup_document_cap_and_token_budget_do_not_trim_citations():
    rows = [dict(chunk_id=str(i), document_id=d, content_hash=h, token_count=t)
            for i, (d, h, t) in enumerate([('a', 'same', 80), ('b', 'same', 80),
                                          ('a', 'next', 80), ('c', 'huge', 200), ('d', 'small', 20)])]
    hits, used = assemble(rows, 10, 150, 1)
    assert [r['chunk_id'] for r in hits] == ['0', '4']
    assert used == 148


def test_rrf_ties_use_content_before_random_ingestion_ids():
    left = dict(chunk_id='z', content_hash='a', title='source', ordinal=0)
    right = dict(chunk_id='a', content_hash='z', title='source', ordinal=0)
    assert fuse({'dense': [left], 'lexical': [right]})[0]['content_hash'] == 'a'


@pytest.mark.parametrize('body', [
    {'profile': 'wrong', 'scores': [1]},
    {'profile': settings().reranker_profile, 'scores': []},
    {'profile': settings().reranker_profile, 'scores': ['1']},
    {'profile': settings().reranker_profile, 'scores': [True]},
])
def test_reranker_rejects_incompatible_model_contract(body):
    async def run():
        async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(200, json=body))) as client:
            with pytest.raises(RagError) as error:
                await models.rerank(client, 'q', [{'text': 'x'}], time.monotonic() + 1)
            assert error.value.code == 'RERANK_INVALID'
    asyncio.run(run())


def test_model_http_deadline_and_payload_limit(monkeypatch):
    async def run():
        async def slow(_):
            await asyncio.sleep(2)
            return httpx.Response(200, json={})
        async with httpx.AsyncClient(transport=httpx.MockTransport(slow)) as client:
            start = time.monotonic()
            with pytest.raises(RagError):
                await models.post(client, 'http://model', 'key', {}, start + 1, .02, 'RERANK')
            assert time.monotonic() - start < .5
        async with httpx.AsyncClient(transport=httpx.MockTransport(lambda _: httpx.Response(200, content=b'x' * 262145))) as client:
            with pytest.raises(RagError):
                await models.post(client, 'http://model', 'key', {}, time.monotonic() + 1, 1, 'RERANK')
    asyncio.run(run())
