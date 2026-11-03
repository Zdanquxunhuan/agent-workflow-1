"""Single-turn grounded answers. Retrieval and generation are separate failure domains."""
import asyncio
import logging
import time
from typing import Annotated
from uuid import UUID, uuid4

import psycopg
from psycopg.types.json import Jsonb
from psycopg_pool import PoolTimeout
from fastapi import APIRouter, Depends, Request

from app import answer_provider
from app.answer_contracts import AnswerRequest, AnswerResponse, Feedback, Usage
from app.auth import Principal, principal
from app.config import settings
from app.errors import RagError
from app.retrieval import pipeline
from app.retrieval.database import JOIN, SCOPE, authorize, parameters, transaction

router = APIRouter(prefix='/api/v1', tags=['Grounded answers'])
User = Annotated[Principal, Depends(principal)]
log = logging.getLogger(__name__)


async def acquire(pool, user, body, answer_id, deadline):
    cfg = settings()
    async with transaction(pool, user.tenant_id, deadline) as conn:
        await authorize(conn, user, body.knowledge_base_ids)
        if not cfg.answer_enabled:
            raise RagError('ANSWERS_DISABLED', 'Knowledge answers are not configured', 503)
        if user.tenant_id not in cfg.answer_allowed_tenants:
            raise RagError('ANSWER_PROVIDER_FORBIDDEN', 'Tenant has not enabled this answer provider', 403)
        await conn.execute('SELECT pg_advisory_xact_lock(hashtextextended(%s,10))', (user.tenant_id,))
        await conn.execute("UPDATE answer_runs SET state='FAILED',error_code='ANSWER_ABANDONED',"
                           "finished_at=clock_timestamp() WHERE tenant_id=%s AND state='RUNNING' "
                           "AND lease_until<clock_timestamp()", (user.tenant_id,))
        row = await (await conn.execute("""SELECT
          count(*) FILTER(WHERE created_at>clock_timestamp()-interval '60 seconds') AS requests,
          count(*) FILTER(WHERE state='RUNNING' AND lease_until>clock_timestamp()) AS active
          FROM answer_runs WHERE tenant_id=%s AND
          (created_at>clock_timestamp()-interval '60 seconds' OR state='RUNNING')""", (user.tenant_id,))).fetchone()
        if row['requests'] >= cfg.answer_requests_per_minute or row['active'] >= cfg.answer_tenant_concurrency:
            raise RagError('ANSWER_RATE_LIMITED', 'Tenant answer rate or concurrency limit reached', 429, True)
        await conn.execute('''INSERT INTO answer_runs(tenant_id,id,subject,kb_ids,model,prompt_version,lease_until)
          VALUES(%s,%s,%s,%s,%s,%s,clock_timestamp()+(%s*interval '1 second'))''',
          (user.tenant_id, answer_id, user.subject, body.knowledge_base_ids, cfg.answer_model,
           answer_provider.PROMPT_VERSION, cfg.answer_deadline_seconds + 5))


def select_sources(query, hits):
    selected = []
    for hit in hits:
        candidate = dict(hit, source_id=f'S{len(selected) + 1}')
        messages = answer_provider.messages_for(query, selected + [candidate])
        if answer_provider.prompt_bytes(messages) <= settings().answer_prompt_max_bytes:
            selected.append(candidate)
    if hits and not selected:
        raise RagError('ANSWER_CONTEXT_BUDGET', 'No complete evidence chunk fits the answer input budget', 422)
    return selected


async def evidence_snapshot(conn, user, kb_ids, sources):
    await authorize(conn, user, kb_ids)
    if not sources:
        return []
    params = parameters(user, kb_ids, settings().embedding_profile) | {
        'ids': [UUID(str(s['chunk_id'])) for s in sources]}
    rows = await (await conn.execute('SELECT c.id,c.document_id,c.version_id,c.content_hash,d.content_revision ' +
        JOIN + ' JOIN document_versions v ON v.tenant_id=c.tenant_id AND v.id=c.version_id WHERE ' + SCOPE +
        " AND c.id=ANY(%(ids)s) AND v.lifecycle='READY' AND v.first_published_at IS NOT NULL", params)).fetchall()
    current = {str(row['id']): row for row in rows}
    snapshot = []
    for source in sources:
        row = current.get(str(source['chunk_id']))
        if (not row or str(row['version_id']) != str(source['document_version_id'])
                or row['content_hash'] != source['content_hash']
                or ('content_revision' in source and row['content_revision'] != source['content_revision'])):
            raise RagError('ANSWER_EVIDENCE_CHANGED', 'Evidence or access changed; ask again using current sources', 409, True)
        snapshot.append({'chunk_id': str(row['id']), 'document_id': str(row['document_id']),
                         'document_version_id': str(row['version_id']), 'content_hash': row['content_hash'],
                         'content_revision': row['content_revision']})
    return snapshot


def render_answer(model, sources):
    indexed = {s['source_id']: s for s in sources}
    citations, statements, ids = [], [], {}
    for claim in model.claims:
        cited = []
        for evidence in claim.evidence:
            source, quote = indexed.get(evidence.source_id), evidence.quote.strip()
            if not source or len(quote) < 4 or quote not in source['text']:
                raise RagError('ANSWER_INVALID_CITATION', 'Model citation does not match the supplied evidence', 502)
            key = (evidence.source_id, quote)
            if key not in ids:
                identifier = f'C{len(citations) + 1}'
                ids[key] = identifier
                citations.append({k: source[k] for k in ('chunk_id', 'document_id', 'document_version_id',
                    'content_revision', 'content_hash', 'title', 'page', 'section')} |
                    {'id': identifier, 'source_id': evidence.source_id, 'quote': quote,
                     'url': f"/api/v1/citations/{source['chunk_id']}"})
            if ids[key] not in cited:
                cited.append(ids[key])
        statements.append({'text': claim.text.strip(), 'citation_ids': cited})
    if model.status == 'insufficient_evidence':
        return '当前可用资料不足以回答这个问题，请补充相关资料或缩小问题范围。', [], []
    text = '\n\n'.join(s['text'] + ' ' + ''.join(f'[{c}]' for c in s['citation_ids']) for s in statements)
    if model.status == 'conflicting_evidence':
        text = '检索到的资料存在分歧，暂不能给出统一结论，请核对适用条件：\n\n' + text
    return text, statements, citations


async def record_failure(pool, user, answer_id, code, started, usage, prompt_size):
    try:
        async with asyncio.timeout(2):
            async with transaction(pool, user.tenant_id, time.monotonic() + 2) as conn:
                await conn.execute("UPDATE answer_runs SET state='FAILED',error_code=%s,finished_at=clock_timestamp(),"
                    "lease_until=clock_timestamp(),duration_ms=%s,prompt_tokens=%s,completion_tokens=%s,prompt_bytes=%s "
                    "WHERE tenant_id=%s AND id=%s AND state='RUNNING'",
                    (code, (time.monotonic() - started) * 1000, usage.prompt_tokens, usage.completion_tokens,
                     prompt_size, user.tenant_id, answer_id))
    except Exception:
        log.warning('answer_failure_record_unavailable answer_id=%s', answer_id)


@router.post('/answers', response_model=AnswerResponse)
async def answer(body: AnswerRequest, user: User, request: Request):
    cfg, started, answer_id = settings(), time.monotonic(), uuid4()
    deadline = started + cfg.answer_deadline_seconds
    pool = request.app.state.search_pool
    admitted, usage, prompt_size = False, Usage(), 0
    try:
        async with asyncio.timeout(cfg.answer_deadline_seconds):
            await acquire(pool, user, body, answer_id, deadline)
            admitted = True
            found = await pipeline.search(request.app, user, body, request.state.request_id)
            sources = select_sources(body.query, found['hits'])
            async with transaction(pool, user.tenant_id, deadline) as conn:
                snapshot = await evidence_snapshot(conn, user, body.knowledge_base_ids, sources)
            sources = [s | snap for s, snap in zip(sources, snapshot, strict=True)]
            model_ms = 0.0
            if sources:
                messages = answer_provider.messages_for(body.query, sources)
                prompt_size = answer_provider.prompt_bytes(messages)
                begin = time.monotonic()
                completion = await answer_provider.complete(request.app.state.model_http, messages, deadline - 1)
                model_ms = (time.monotonic() - begin) * 1000
                usage = completion.usage
                status = completion.answer.status
                text, statements, citations = render_answer(completion.answer, sources)
                reason = {'insufficient_evidence': 'model_insufficient_evidence',
                          'conflicting_evidence': 'source_conflict'}.get(status)
            else:
                status, reason = 'insufficient_evidence', 'no_retrieved_evidence'
                text, statements, citations = '没有找到当前身份可访问的相关证据，请补充资料或调整问题。', [], []
            async with transaction(pool, user.tenant_id, deadline) as conn:
                # Check ALL model inputs, not only the citations the model chose to show.
                snapshot = await evidence_snapshot(conn, user, body.knowledge_base_ids, sources)
                saved = await (await conn.execute('''UPDATE answer_runs SET state=%s,sources=%s,
                  retrieval_profile=%s,prompt_bytes=%s,prompt_tokens=%s,completion_tokens=%s,
                  duration_ms=%s,finished_at=clock_timestamp(),lease_until=clock_timestamp()
                  WHERE tenant_id=%s AND id=%s AND state='RUNNING' AND lease_until>clock_timestamp() RETURNING id''',
                  (status, Jsonb(snapshot), found['retrieval_profile_id'], prompt_size, usage.prompt_tokens,
                   usage.completion_tokens, (time.monotonic() - started) * 1000, user.tenant_id, answer_id))).fetchone()
                if not saved:
                    raise RagError('ANSWER_DEADLINE', 'Answer lease expired', 504, True)
            result = AnswerResponse(request_id=request.state.request_id, answer_id=answer_id, status=status,
                reason=reason, answer=text, statements=statements, citations=citations,
                model=cfg.answer_model if sources else None, prompt_version=answer_provider.PROMPT_VERSION,
                retrieval_profile_id=found['retrieval_profile_id'], degraded_components=found['degraded_components'],
                prompt_bytes=prompt_size, usage=usage, timings_ms={'retrieval_ms': found['timings_ms']['total_ms'],
                    'generation_ms': round(model_ms, 2), 'total_ms': round((time.monotonic() - started) * 1000, 2)})
        log.info('answer_completed answer_id=%s status=%s model=%s duration_ms=%s',
                 answer_id, status, result.model, result.timings_ms['total_ms'])
        return result
    except (TimeoutError, psycopg.errors.QueryCanceled, PoolTimeout) as exc:
        if admitted:
            await record_failure(pool, user, answer_id, 'ANSWER_DEADLINE', started, usage, prompt_size)
        raise RagError('ANSWER_DEADLINE', 'Answer deadline exceeded', 504, True) from exc
    except asyncio.CancelledError:
        # A cancelled request releases admission, or the durable lease expires after a crash.
        if admitted:
            await record_failure(pool, user, answer_id, 'ANSWER_CANCELLED', started, usage, prompt_size)
        raise
    except Exception as exc:
        if admitted:
            await record_failure(pool, user, answer_id, exc.code if isinstance(exc, RagError) else 'ANSWER_FAILED',
                                 started, usage, prompt_size)
        raise


@router.post('/answers/{answer_id}/feedback')
async def feedback(answer_id: UUID, body: Feedback, user: User, request: Request):
    async with transaction(request.app.state.search_pool, user.tenant_id, time.monotonic() + 5) as conn:
        run = await (await conn.execute('SELECT * FROM answer_runs WHERE tenant_id=%s AND id=%s AND subject=%s',
                                       (user.tenant_id, answer_id, user.subject))).fetchone()
        if not run or run['state'] in ('RUNNING', 'FAILED'):
            raise RagError('NOT_FOUND', 'Answer not found', 404)
        await authorize(conn, user, run['kb_ids'])
        # Feedback may concern an older answer, but current read permissions still apply.
        for source in run['sources']:
            row = await (await conn.execute('''SELECT v.lifecycle FROM documents d JOIN document_versions v
              ON v.tenant_id=d.tenant_id AND v.document_id=d.id WHERE d.tenant_id=%s AND d.id=%s
              AND v.id=%s AND d.deleted_at IS NULL
              AND (d.allowed_subjects IS NULL OR %s=ANY(d.allowed_subjects))''',
              (user.tenant_id, source['document_id'], source['document_version_id'], user.subject))).fetchone()
            if not row:
                raise RagError('NOT_FOUND', 'Answer evidence is no longer accessible', 404)
            if row['lifecycle'] in ('PURGING', 'PURGED'):
                raise RagError('VERSION_GONE', 'Answer evidence has been removed', 410)
        await conn.execute('''INSERT INTO answer_feedback(tenant_id,answer_id,subject,rating,reason)
          VALUES(%s,%s,%s,%s,%s) ON CONFLICT(tenant_id,answer_id,subject) DO UPDATE
          SET rating=EXCLUDED.rating,reason=EXCLUDED.reason,updated_at=now()''',
          (user.tenant_id, answer_id, user.subject, body.rating, body.reason))
    return {'status': 'saved'}
