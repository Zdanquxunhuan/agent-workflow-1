import difflib
import hashlib
import json
from pathlib import Path
from typing import Annotated
from urllib.parse import quote
from uuid import UUID, uuid4

from fastapi import APIRouter, Depends, File, Form, Header, Query, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel, ConfigDict, Field
from botocore.exceptions import ClientError

from app.auth import principal, Principal, document_visible, require_kb
from app.config import settings
from app.db import transaction
from app.errors import RagError
from app.lifecycle import audit, get_version, lock_document, manager, publish_version, require_retained
from app.parsing import validate_file
from app.storage import get_object, put_object

router = APIRouter(prefix='/api/v1', tags=['Document versions'])
User = Annotated[Principal, Depends(principal)]
Key = Annotated[str, Header(alias='Idempotency-Key', min_length=1, max_length=200)]


class Publication(BaseModel):
    model_config = ConfigDict(extra='forbid')
    expected_content_revision: int = Field(ge=0)
    reason: str = Field(min_length=1, max_length=1000)


class Hold(BaseModel):
    model_config = ConfigDict(extra='forbid')
    enabled: bool


def public_version(v, active):
    fields = ('id', 'version_number', 'filename', 'sha256', 'size_bytes', 'created_at', 'created_by',
              'change_note', 'base_revision', 'auto_publish', 'lifecycle', 'ready_at', 'first_published_at',
              'retired_at', 'purged_at', 'retention_hold', 'publication_note', 'embedding_profile', 'lexical_profile')
    return {key: v[key] for key in fields} | {'is_current': v['id'] == active}


@router.get('/documents/{doc_id}')
def detail(doc_id: UUID, user: User):
    with transaction(user.tenant_id) as conn:
        doc = document_visible(conn, user, doc_id)
        kb = require_kb(conn, user, doc['kb_id'])
        is_manager = manager(conn, user, doc['kb_id'])
        return {key: doc[key] for key in ('id', 'kb_id', 'title', 'active_version_id', 'revision',
                                          'content_revision', 'version_counter', 'created_at')} | {
            'can_manage': is_manager, 'can_update': is_manager or kb['kb_role'] == 'contributor'}


@router.post('/documents/{doc_id}/versions', status_code=202)
def upload_version(doc_id: UUID, user: User, idempotency_key: Key, file: UploadFile = File(),
                   expected_content_revision: int = Form(ge=0), change_note: str = Form('', max_length=1000),
                   auto_publish: bool = Form(False)):
    filename = Path((file.filename or 'document').replace('\\', '/')).name[:200]
    data = file.file.read(settings().upload_max_bytes + 1)
    validate_file(filename, data)
    checksum = hashlib.sha256(data).hexdigest()
    digest = hashlib.sha256(json.dumps(['version', str(doc_id), filename, checksum,
                                       expected_content_revision, change_note, auto_publish]).encode()).hexdigest()
    with transaction(user.tenant_id) as conn:
        conn.execute('SELECT pg_advisory_xact_lock(hashtextextended(%s,0))',
                     (user.tenant_id + ':' + user.subject + ':' + idempotency_key,))
        doc = lock_document(conn, user, doc_id, write=True)
        previous = conn.execute('SELECT * FROM ingestion_jobs WHERE tenant_id=%s AND actor=%s AND idempotency_key=%s',
                                (user.tenant_id, user.subject, idempotency_key)).fetchone()
        if previous:
            if previous['request_hash'] != digest:
                raise RagError('IDEMPOTENCY_CONFLICT', 'Key was used for another upload', 409)
            return {'document_id': doc_id, 'version_id': previous['version_id'], 'job_id': previous['id'], 'replayed': True}
        if doc['content_revision'] != expected_content_revision:
            raise RagError('CONTENT_REVISION_CONFLICT', 'Published content changed; reload before uploading', 409)
        version_id, job_id = uuid4(), uuid4()
        number = doc['version_counter'] + 1
        key = f'{user.tenant_id}/{doc_id}/{version_id}/original'
        put_object(key, data)
        conn.execute('UPDATE documents SET version_counter=%s WHERE tenant_id=%s AND id=%s',
                     (number, user.tenant_id, doc_id))
        conn.execute('''INSERT INTO document_versions(tenant_id,id,document_id,object_key,filename,sha256,
          size_bytes,version_number,base_revision,created_by,change_note,auto_publish)
          VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)''',
          (user.tenant_id, version_id, doc_id, key, filename, checksum, len(data), number,
           expected_content_revision, user.subject, change_note, auto_publish))
        conn.execute('''INSERT INTO ingestion_jobs(tenant_id,id,document_id,version_id,actor,idempotency_key,request_hash)
          VALUES(%s,%s,%s,%s,%s,%s,%s)''', (user.tenant_id, job_id, doc_id, version_id,
                                           user.subject, idempotency_key, digest))
        conn.execute('INSERT INTO outbox_events(id,tenant_id,job_id) VALUES(%s,%s,%s)', (uuid4(), user.tenant_id, job_id))
        audit(conn, user.tenant_id, user.subject, 'VERSION_UPLOADED', version_id)
        return {'document_id': doc_id, 'version_id': version_id, 'version_number': number,
                'job_id': job_id, 'replayed': False}


@router.get('/documents/{doc_id}/versions')
def list_versions(doc_id: UUID, user: User, before: int | None = Query(None, ge=1), limit: int = Query(30, ge=1, le=100)):
    with transaction(user.tenant_id) as conn:
        doc = document_visible(conn, user, doc_id)
        is_manager = manager(conn, user, doc['kb_id'])
        kb = require_kb(conn, user, doc['kb_id'])
        own_drafts = is_manager or kb['kb_role'] == 'contributor'
        rows = conn.execute('''SELECT v.*,j.id AS job_id,j.state AS job_state,j.progress,j.error_code,j.error_message
          FROM document_versions v LEFT JOIN LATERAL (SELECT * FROM ingestion_jobs j
            WHERE j.tenant_id=v.tenant_id AND j.version_id=v.id ORDER BY j.created_at DESC,j.id DESC LIMIT 1) j ON true
          WHERE v.tenant_id=%s AND v.document_id=%s AND (%s::integer IS NULL OR v.version_number<%s)
          AND (v.first_published_at IS NOT NULL OR %s OR (%s AND v.created_by=%s))
          ORDER BY v.version_number DESC LIMIT %s''',
          (user.tenant_id, doc_id, before, before, is_manager, own_drafts, user.subject, limit + 1)).fetchall()
        return {'content_revision': doc['content_revision'], 'active_version_id': doc['active_version_id'],
                'items': [public_version(v, doc['active_version_id']) | {
                    k: v[k] for k in ('job_id', 'job_state', 'progress', 'error_code', 'error_message')} for v in rows[:limit]],
                'next_before': rows[limit - 1]['version_number'] if len(rows) > limit else None}


@router.post('/documents/{doc_id}/versions/{version_id}/publish')
def publish(doc_id: UUID, version_id: UUID, body: Publication, user: User, idempotency_key: Key):
    with transaction(user.tenant_id) as conn:
        return publish_version(conn, user, doc_id, version_id, body.expected_content_revision, idempotency_key, body.reason)


@router.post('/documents/{doc_id}/versions/{version_id}/rollback')
def rollback(doc_id: UUID, version_id: UUID, body: Publication, user: User, idempotency_key: Key):
    with transaction(user.tenant_id) as conn:
        return publish_version(conn, user, doc_id, version_id, body.expected_content_revision, idempotency_key, body.reason, True)


@router.get('/documents/{doc_id}/publication-events')
def events(doc_id: UUID, user: User, before_revision: int | None = Query(None, ge=1), limit: int = Query(30, ge=1, le=100)):
    with transaction(user.tenant_id) as conn:
        document_visible(conn, user, doc_id)
        rows = conn.execute('''SELECT id,from_version_id,to_version_id,actor,action,reason,previous_revision,
          content_revision,created_at FROM publication_events WHERE tenant_id=%s AND document_id=%s
          AND (%s::bigint IS NULL OR content_revision<%s) ORDER BY content_revision DESC LIMIT %s''',
          (user.tenant_id, doc_id, before_revision, before_revision, limit + 1)).fetchall()
        return {'items': rows[:limit], 'next_before_revision': rows[limit - 1]['content_revision'] if len(rows) > limit else None}


@router.put('/documents/{doc_id}/versions/{version_id}/retention-hold')
def hold(doc_id: UUID, version_id: UUID, body: Hold, user: User):
    with transaction(user.tenant_id) as conn:
        doc = lock_document(conn, user, doc_id, manage=True, deleted=True)
        version = get_version(conn, user, doc, version_id)
        require_retained(version)
        conn.execute('UPDATE document_versions SET retention_hold=%s WHERE tenant_id=%s AND id=%s',
                     (body.enabled, user.tenant_id, version_id))
        audit(conn, user.tenant_id, user.subject, 'VERSION_HELD' if body.enabled else 'VERSION_HOLD_RELEASED', version_id)
    return {'retention_hold': body.enabled}


def version_text(conn, version):
    require_retained(version)
    if version['lifecycle'] != 'READY':
        raise RagError('VERSION_NOT_READY', 'Parsed text is not yet available', 409)
    rows = conn.execute('SELECT content FROM chunks WHERE tenant_id=%s AND version_id=%s ORDER BY ordinal LIMIT %s',
                        (version['tenant_id'], version['id'], settings().max_chunks)).fetchall()
    text = '\n\n'.join(row['content'] for row in rows)
    return text[:40000], len(text) > 40000


@router.get('/documents/{doc_id}/versions/compare')
def compare(doc_id: UUID, user: User, from_version: UUID, to_version: UUID):
    with transaction(user.tenant_id) as conn:
        doc = document_visible(conn, user, doc_id)
        a, b = (get_version(conn, user, doc, identifier) for identifier in (from_version, to_version))
        left, left_cut = version_text(conn, a)
        right, right_cut = version_text(conn, b)
        # This is a bounded diff of indexed text; overlapping chunks can repeat passages.
        left_lines, right_lines = left.splitlines(), right.splitlines()
        lines = list(difflib.unified_diff(left_lines[:2000], right_lines[:2000],
            fromfile=f'v{a["version_number"]}', tofile=f'v{b["version_number"]}', lineterm=''))
        doc = document_visible(conn, user, doc_id)
        for identifier in (from_version, to_version):
            require_retained(get_version(conn, user, doc, identifier))
        return {'from_version': from_version, 'to_version': to_version, 'diff': '\n'.join(lines[:2000]),
                'truncated': left_cut or right_cut or len(left_lines)>2000 or len(right_lines)>2000 or len(lines)>2000,
                'basis': 'indexed chunks; may contain overlap'}


def download_version(doc_id, version_id, user):
    with transaction(user.tenant_id) as conn:
        doc = document_visible(conn, user, doc_id)
        version = get_version(conn, user, doc, version_id or doc['active_version_id'])
        require_retained(version)
    try:
        data = get_object(version['object_key'])
    except ClientError:
        with transaction(user.tenant_id) as conn:
            doc = document_visible(conn, user, doc_id)
            require_retained(get_version(conn, user, doc, version['id']))
        raise
    if hashlib.sha256(data).hexdigest() != version['sha256']:
        raise RagError('CHECKSUM_MISMATCH', 'Stored original integrity check failed', 503, True)
    with transaction(user.tenant_id) as conn:
        doc = document_visible(conn, user, doc_id)
        version = get_version(conn, user, doc, version['id'])
        require_retained(version)
        audit(conn, user.tenant_id, user.subject, 'VERSION_ORIGINAL_DOWNLOADED', version['id'])
    return Response(data, media_type='application/octet-stream', headers={
        'Content-Disposition': "attachment; filename*=UTF-8''" + quote(version['filename']),
        'X-Document-Version': str(version['id']), 'X-Version-Current': str(version['id'] == doc['active_version_id']).lower()})


@router.get('/documents/{doc_id}/versions/{version_id}/original')
def original(doc_id: UUID, version_id: UUID, user: User):
    return download_version(doc_id, version_id, user)
