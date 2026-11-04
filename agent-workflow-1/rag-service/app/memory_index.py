"""Internal, service-authenticated index; returns identifiers only, never authoritative memory text."""
import hashlib
import asyncio
import json
import time
import re
from uuid import UUID

import httpx
import jwt
from fastapi import APIRouter, Request
from pydantic import BaseModel, ConfigDict, Field
from app.config import settings
from app.db import transaction, vector_literal
from app.errors import RagError

router = APIRouter(prefix='/internal/memory-index', tags=['internal-memory-index'])


class Update(BaseModel):
    model_config = ConfigDict(extra='forbid')
    id: UUID
    revision: int = Field(gt=0)
    expiresAt: int = Field(ge=0)
    deleted: bool
    text: str = Field(max_length=1000)


class Query(BaseModel):
    model_config = ConfigDict(extra='forbid')
    query: str = Field(min_length=1, max_length=2000)
    limit: int = Field(default=8, ge=1, le=10)


async def identity(request: Request, operation: str):
    cfg = settings()
    if not cfg.memory_index_enabled:
        raise RagError('NOT_FOUND', 'Memory index disabled', 404)
    try:
        authorization = request.headers.get('authorization', '')
        if not authorization.startswith('Bearer '):
            raise ValueError('missing token')
        claims = jwt.decode(authorization[7:], cfg.memory_index_secret.get_secret_value(), algorithms=['HS256'],
                            issuer='workflow-memory', audience='memory-index',
                            options={'require': ['exp', 'iat', 'sub', 'tenant_id', 'operation', 'body_hash']})
        if (claims['exp'] - claims['iat'] > 60 or claims['operation'] != operation
                or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', claims['tenant_id'])
                or not isinstance(claims['sub'], str) or not 1 <= len(claims['sub']) <= 200
                or claims['body_hash'] != hashlib.sha256(await request.body()).hexdigest()):
            raise ValueError('invalid scope')
    except (jwt.PyJWTError, ValueError, TypeError, KeyError) as exc:
        raise RagError('UNAUTHORIZED', 'Valid memory service credential required', 401) from exc
    return claims['tenant_id'], claims['sub']


def admission(tenant, owner, deleting=False):
    now = int(time.time())
    with transaction(tenant) as conn:
        # Deletion remains possible after account revocation.
        if not deleting and not conn.execute('SELECT 1 FROM tenant_memberships WHERE tenant_id=%s AND subject=%s AND enabled',
                                              (tenant, owner)).fetchone():
            raise RagError('FORBIDDEN', 'Active owner membership required', 403)
        row = conn.execute("""INSERT INTO memory_index_admissions VALUES (%s,%s,1)
          ON CONFLICT(tenant_id) DO UPDATE SET
            requests=CASE WHEN memory_index_admissions.window_start<>EXCLUDED.window_start THEN 1 ELSE memory_index_admissions.requests+1 END,
            window_start=EXCLUDED.window_start
          RETURNING requests""", (tenant, now // 60)).fetchone()
        if row['requests'] > 120:
            raise RagError('RATE_LIMITED', 'Memory index tenant budget exceeded', 429, True)
        conn.execute('UPDATE memory_vectors SET embedding=NULL,deleted=true WHERE ctid IN '
                     '(SELECT ctid FROM memory_vectors WHERE tenant_id=%s AND NOT deleted AND expires_at<=%s LIMIT 1000)', (tenant, now))


async def embed(text):
    cfg = settings()
    try:
        async with asyncio.timeout(6):
            async with httpx.AsyncClient(timeout=httpx.Timeout(6, connect=2), follow_redirects=False) as client:
                async with client.stream('POST', cfg.embedding_url + '/embed', json={'texts': [text]},
                                         headers={'Authorization': 'Bearer ' + cfg.embedding_key}) as response:
                    response.raise_for_status()
                    body = bytearray()
                    async for chunk in response.aiter_bytes():
                        if len(body) + len(chunk) > 131072:
                            raise ValueError('embedding response too large')
                        body.extend(chunk)
                    result = json.loads(body)
        if result['profile'] != cfg.embedding_profile or len(result['vectors']) != 1:
            raise ValueError('profile mismatch')
        return vector_literal(result['vectors'][0])
    except (httpx.HTTPError, ValueError, KeyError, TypeError, TimeoutError) as exc:
        raise RagError('EMBEDDING_UNAVAILABLE', 'Memory embedding unavailable', 503, True) from exc


@router.post('/upsert')
async def upsert(payload: Update, request: Request):
    tenant, owner = await identity(request, 'upsert')
    deleted = payload.deleted or payload.expiresAt <= int(time.time())
    if not deleted and not payload.text.strip():
        raise RagError('INVALID_ARGUMENT', 'Active index content required', 422)
    await asyncio.to_thread(admission, tenant, owner, deleted)
    digest = hashlib.sha256((str(payload.expiresAt) + ':' + str(payload.deleted) + ':' + payload.text).encode()).hexdigest()
    old = await asyncio.to_thread(read_version, tenant, owner, payload.id)
    if old and old['revision'] >= payload.revision:
        if old['revision'] == payload.revision and old['payload_hash'] != digest:
            raise RagError('CONFLICT', 'Same revision has different content', 409)
        return {'revision': old['revision']}
    vector = None if deleted else await embed(payload.text)
    current = await asyncio.to_thread(write_vector, tenant, owner, payload, deleted, digest, vector)
    return {'revision': current['revision']}


def read_version(tenant, owner, memory_id):
    with transaction(tenant) as conn:
        return conn.execute('SELECT revision,payload_hash FROM memory_vectors WHERE tenant_id=%s AND owner_id=%s AND memory_id=%s',
                            (tenant, owner, memory_id)).fetchone()


def write_vector(tenant, owner, payload, deleted, digest, vector):
    with transaction(tenant) as conn:
        conn.execute("""INSERT INTO memory_vectors(tenant_id,owner_id,memory_id,revision,expires_at,deleted,profile,payload_hash,embedding)
          VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s::vector)
          ON CONFLICT(tenant_id,owner_id,memory_id) DO UPDATE SET revision=EXCLUDED.revision,
            expires_at=EXCLUDED.expires_at,deleted=EXCLUDED.deleted,profile=EXCLUDED.profile,
            payload_hash=EXCLUDED.payload_hash,embedding=EXCLUDED.embedding,updated_at=now()
          WHERE memory_vectors.revision<EXCLUDED.revision""",
          (tenant, owner, payload.id, payload.revision, payload.expiresAt, deleted, settings().embedding_profile, digest, vector))
        current = conn.execute('SELECT revision,payload_hash FROM memory_vectors WHERE tenant_id=%s AND owner_id=%s AND memory_id=%s',
                               (tenant, owner, payload.id)).fetchone()
        if current['revision'] == payload.revision and current['payload_hash'] != digest:
            raise RagError('CONFLICT', 'Conflicting index payload', 409)
    return current


@router.post('/query')
async def query(payload: Query, request: Request):
    tenant, owner = await identity(request, 'query')
    await asyncio.to_thread(admission, tenant, owner)
    vector = await embed(payload.query)
    rows = await asyncio.to_thread(query_vectors, tenant, owner, payload.limit, vector)
    return {'hits': [{'id': str(r['memory_id']), 'revision': r['revision'], 'distance': r['distance']} for r in rows]}


def query_vectors(tenant, owner, limit, vector):
    with transaction(tenant) as conn:
        rows = conn.execute("""SELECT memory_id,revision,embedding <=> %s::vector AS distance
          FROM memory_vectors WHERE tenant_id=%s AND owner_id=%s AND NOT deleted AND expires_at>%s
          AND profile=%s AND embedding IS NOT NULL ORDER BY distance,memory_id LIMIT %s""",
          (vector, tenant, owner, int(time.time()), settings().embedding_profile, limit)).fetchall()
    return rows
