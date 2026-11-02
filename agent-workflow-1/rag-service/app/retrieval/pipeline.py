import asyncio
import json
import logging
import time

import psycopg
from psycopg_pool import PoolTimeout

from app.config import settings
from app.db import vector_literal
from app.errors import RagError
from app.retrieval import admission, models
from app.retrieval.database import FIELDS, JOIN, SCOPE, TIE, authorize, parameters, remaining, transaction, visible
from app.retrieval.lexical import PROFILE as LEXICAL_PROFILE, identifiers, normalize, query_expression
from app.retrieval.profiles import resolve, fingerprint
from app.retrieval.ranking import assemble, fuse, tie_key

log = logging.getLogger(__name__)


async def fetch_channel(pool, user, params, channel, deadline):
    async with transaction(pool, user.tenant_id, deadline) as conn:
        if channel == "dense":
            await conn.execute("SET LOCAL enable_indexscan=off")
            sql = ("SELECT " + FIELDS + ",c.embedding <=> %(vector)s::vector AS distance,"
                   "1-(c.embedding <=> %(vector)s::vector) AS channel_score " + JOIN + " WHERE " + SCOPE +
                   " ORDER BY c.embedding <=> %(vector)s::vector," + TIE + " LIMIT %(candidate_k)s")
        else:
            join = JOIN + " JOIN chunk_lexical l ON l.tenant_id=c.tenant_id AND l.chunk_id=c.id"
            condition = " AND l.profile=%(lexical_profile)s AND l.content_hash=c.content_hash"
            if channel == "identifier":
                sql = ("SELECT " + FIELDS + ",NULL::float8 AS distance,1.0 AS channel_score " + join +
                       " WHERE " + SCOPE + condition + " AND l.identifiers @> %(identifiers)s::text[]"
                       " ORDER BY " + TIE + " LIMIT %(candidate_k)s")
            else:
                query = "to_tsquery('simple'::regconfig,%(tsquery)s)"
                sql = ("SELECT " + FIELDS + ",NULL::float8 AS distance,ts_rank_cd(l.search_vector," + query +
                       ") AS channel_score " + join + " WHERE " + SCOPE + condition +
                       " AND l.search_vector @@ " + query + " ORDER BY channel_score DESC," + TIE + " LIMIT %(candidate_k)s")
        return await (await conn.execute(sql, params)).fetchall()


async def coverage(conn, params):
    row = await (await conn.execute("SELECT count(*) AS missing " + JOIN + " WHERE " + SCOPE +
        " AND NOT EXISTS (SELECT 1 FROM chunk_lexical l WHERE l.tenant_id=c.tenant_id AND l.chunk_id=c.id"
        " AND l.profile=%(lexical_profile)s AND l.content_hash=c.content_hash)", params)).fetchone()
    return row["missing"]


async def pipeline(pool, client, user, body, deadline, debug):
    cfg = settings()
    profile_id = body.profile or cfg.retrieval_profile
    profile = resolve(profile_id)
    kb_ids = list(dict.fromkeys(body.knowledge_base_ids))
    query = normalize(body.query)
    if not query:
        raise RagError("INVALID_ARGUMENT", "Query cannot be blank", 422)
    params = parameters(user, kb_ids, cfg.embedding_profile) | {
        "candidate_k": profile["candidate_k"], "lexical_profile": LEXICAL_PROFILE,
        "tsquery": query_expression(query), "identifiers": identifiers(query)}
    timings, degraded, channels = {}, [], {}
    started = time.monotonic()

    async def timed(name, operation):
        begin = time.monotonic()
        try:
            return await operation
        finally:
            timings[name] = round((time.monotonic() - begin) * 1000, 2)

    async with transaction(pool, user.tenant_id, deadline) as conn:
        await authorize(conn, user, kb_ids, debug)
        missing = await coverage(conn, params) if "lexical" in profile["channels"] else 0
    if missing:
        if "dense" not in profile["channels"]:
            raise RagError("LEXICAL_INDEX_NOT_READY", "Lexical backfill required for this scope", 503, True)
        degraded.append("lexical_index_incomplete")

    async def dense():
        try:
            vector = await timed("embedding_ms", models.embed_query(client, query, deadline))
        except RagError as exc:
            if exc.status != 503 or "lexical" not in profile["channels"] or missing:
                raise
            degraded.append("embedding")
            return []
        return await timed("dense_ms", fetch_channel(pool, user, params | {"vector": vector_literal(vector)},
                                                     "dense", deadline))

    tasks = {}
    if "dense" in profile["channels"]:
        tasks["dense"] = asyncio.create_task(dense())
    if "lexical" in profile["channels"] and params["tsquery"]:
        tasks["lexical"] = asyncio.create_task(timed("lexical_ms", fetch_channel(pool, user, params, "lexical", deadline)))
    if "identifier" in profile["channels"] and params["identifiers"]:
        tasks["identifier"] = asyncio.create_task(timed("identifier_ms", fetch_channel(pool, user, params, "identifier", deadline)))
    try:
        values = await asyncio.gather(*tasks.values())
        channels = dict(zip(tasks, values, strict=True))
    finally:
        for task in tasks.values():
            if not task.done():
                task.cancel()
        await asyncio.gather(*tasks.values(), return_exceptions=True)

    fused = fuse(channels, profile["rrf_k"])
    # Re-check current permissions before sending passages to the reranker.
    async with transaction(pool, user.tenant_id, deadline) as conn:
        await authorize(conn, user, kb_ids, debug)
        allowed = await visible(conn, params, [row["chunk_id"] for row in fused])
    fused = [row for row in fused if str(row["chunk_id"]) in allowed]
    ranked = fused
    rerank_applied = False
    if profile["rerank_k"] and fused:
        # The requested output size must not silently increase model work.
        candidates = fused[:profile["rerank_k"]]
        if remaining(deadline) < 0.5:
            degraded.append("reranker_budget")
        else:
            try:
                scores = await timed("rerank_ms", models.rerank(client, query, candidates, deadline - 0.25))
                ranked = sorted([dict(row, rerank_score=score) for row, score in zip(candidates, scores, strict=True)],
                                key=lambda row: (-row["rerank_score"], -row["fusion_score"], tie_key(row)))
                ranked += fused[len(candidates):]
                rerank_applied = True
            except RagError as exc:
                if exc.status not in {422, 503, 504}:
                    raise
                degraded.append("reranker")

    # A revocation/delete/version change during inference must affect debug output too.
    async with transaction(pool, user.tenant_id, deadline) as conn:
        await authorize(conn, user, kb_ids, debug)
        allowed = await visible(conn, params, [row["chunk_id"] for row in fused])
    ranked = [row for row in ranked if str(row["chunk_id"]) in allowed]
    hits, used = assemble(ranked, body.top_k, profile["context_tokens"], profile["max_per_document"])
    result = {"status": "degraded" if degraded else "ok", "hits": [], "retrieval_profile_id": profile_id,
              "profile_fingerprint": fingerprint(profile, cfg.embedding_profile, cfg.reranker_profile),
              "embedding_profile": cfg.embedding_profile, "lexical_profile": LEXICAL_PROFILE,
              "reranker_profile": cfg.reranker_profile if rerank_applied else None,
              "degraded_components": sorted(set(degraded)), "context_tokens": used,
              "token_budget_basis": "bge-m3 + 24 tokens per citation (not an LLM tokenizer)",
              "timings_ms": timings}
    for row in hits:
        result["hits"].append({key: row[key] for key in ["chunk_id", "document_id", "document_version_id", "title",
                                                       "text", "page", "section", "content_hash", "distance"]} |
                             {"score": row.get("rerank_score", row["fusion_score"]),
                              "score_type": "reranker_logit" if "rerank_score" in row else "rrf",
                              "citation": {"id": str(row["chunk_id"]), "url": f"/api/v1/citations/{row['chunk_id']}",
                                           "page": row["page"], "section": row["section"]}})
    if debug:
        reranked = {str(row["chunk_id"]): row.get("rerank_score") for row in ranked}
        # Rebuild diagnostic ranks on the final visible set: revocation must not
        # leave rank gaps that reveal the number/position of removed candidates.
        fused = fuse({channel: [row for row in rows if str(row["chunk_id"]) in allowed]
                      for channel, rows in channels.items()}, profile["rrf_k"])
        fused_ranks = {str(row["chunk_id"]): i for i, row in enumerate(fused, 1)}
        rows = [dict(row, rerank_score=reranked.get(str(row["chunk_id"])))
                for row in fused if str(row["chunk_id"]) in allowed]
        # No scores/counts/IDs from revoked candidates are exposed by diagnostics.
        result["debug"] = {"parameters": profile, "normalized_query": query,
                           "lexical_terms": params["tsquery"], "identifiers": params["identifiers"],
                           "candidates": [{"chunk_id": str(row["chunk_id"]), "document_id": str(row["document_id"]),
                              "title": row["title"], "text": row["text"], "channel_ranks": row["channel_ranks"],
                              "channel_scores": row["channel_scores"], "fusion_rank": fused_ranks[str(row["chunk_id"])],
                              "fusion_score": row["fusion_score"], "rerank_score": row.get("rerank_score"),
                              "selected": any(hit["chunk_id"] == row["chunk_id"] for hit in hits)} for row in rows]}
    timings["pipeline_ms"] = round((time.monotonic() - started) * 1000, 2)
    return result


async def search(app, user, body, request_id, debug=False):
    cfg, started, lease = settings(), time.monotonic(), None
    deadline = started + cfg.search_deadline_seconds
    try:
        async with asyncio.timeout(cfg.search_deadline_seconds):
            lease = await admission.acquire(app.state.search_pool, user, body.knowledge_base_ids, deadline, debug)
            result = await pipeline(app.state.search_pool, app.state.model_http, user, body, deadline, debug)
        result["request_id"] = request_id
        result["timings_ms"]["total_ms"] = round((time.monotonic() - started) * 1000, 2)
        log.info(json.dumps({"event": "retrieval", "request_id": request_id, "profile": result["retrieval_profile_id"],
                             "status": result["status"], "hits": len(result["hits"]),
                             "degraded": result["degraded_components"], "timings_ms": result["timings_ms"]}))
        return result
    except (TimeoutError, psycopg.errors.QueryCanceled, PoolTimeout) as exc:
        raise RagError("SEARCH_DEADLINE", "Search deadline exceeded", 504, True) from exc
    finally:
        if lease is not None and time.monotonic() < deadline:
            try:
                async with asyncio.timeout(min(2.0, deadline - time.monotonic())):
                    await admission.release(app.state.search_pool, user, lease, deadline)
            except Exception as exc:
                # A crash/cancel/timeout expires the database lease; no unbounded cleanup task.
                log.warning("search_admission_release_failed request_id=%s type=%s", request_id, type(exc).__name__)
