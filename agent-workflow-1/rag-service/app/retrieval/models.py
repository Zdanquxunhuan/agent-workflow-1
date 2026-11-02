import asyncio
import json
import math

import httpx

from app.config import settings
from app.db import vector_literal
from app.errors import RagError
from app.retrieval.database import remaining


async def post(client, url, key, payload, deadline, budget, component):
    try:
        seconds = min(remaining(deadline), budget)
        async with asyncio.timeout(seconds):
            async with client.stream("POST", url, json=payload,
                                     headers={"Authorization": "Bearer " + key}, timeout=seconds) as response:
                if response.status_code == 422:
                    raise RagError(component + "_INPUT_INVALID", "Model input exceeds configured limits", 422)
                response.raise_for_status()
                body = bytearray()
                async for part in response.aiter_bytes():
                    body.extend(part)
                    if len(body) > 262144:
                        raise ValueError("model response too large")
                return json.loads(body)
    except (TimeoutError, httpx.HTTPError, ValueError, KeyError) as exc:
        raise RagError(component + "_UNAVAILABLE", component.lower() + " service unavailable", 503, True) from exc


async def embed_query(client, query, deadline):
    cfg = settings()
    result = await post(client, cfg.embedding_url + "/embed", cfg.embedding_key, {"texts": [query]},
                        deadline, cfg.query_embedding_timeout, "EMBEDDING")
    try:
        if result["profile"] != cfg.embedding_profile or len(result["vectors"]) != 1:
            raise ValueError("embedding profile/count mismatch")
        vector_literal(result["vectors"][0])
        return result["vectors"][0]
    except (ValueError, KeyError, TypeError) as exc:
        raise RagError("EMBEDDING_INVALID", "Embedding response incompatible", 503, True) from exc


async def rerank(client, query, candidates, deadline):
    cfg = settings()
    result = await post(client, cfg.reranker_url + "/rerank", cfg.reranker_key,
                        {"query": query, "passages": [row["text"] for row in candidates]},
                        deadline, cfg.rerank_timeout, "RERANK")
    try:
        scores = result["scores"]
        if (result["profile"] != cfg.reranker_profile or len(scores) != len(candidates)
                or not all(type(score) in (int, float) and math.isfinite(score) for score in scores)):
            raise ValueError("invalid reranking result")
        return scores
    except (ValueError, KeyError, TypeError) as exc:
        raise RagError("RERANK_INVALID", "Reranker response incompatible", 503, True) from exc
