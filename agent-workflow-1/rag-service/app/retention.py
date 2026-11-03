from typing import Annotated
from uuid import UUID

from fastapi import APIRouter, Depends
from pydantic import BaseModel, ConfigDict, Field

from app.auth import Principal, principal
from app.config import settings
from app.db import transaction
from app.errors import RagError
from app.lifecycle import audit, lock_document

router = APIRouter(prefix='/api/v1/documents', tags=['Retention'])
User = Annotated[Principal, Depends(principal)]


class Purge(BaseModel):
    model_config = ConfigDict(extra='forbid')
    expected_content_revision: int = Field(ge=0)
    version_ids: list[UUID] = Field(min_length=1, max_length=20)


def candidates(conn, doc):
    cfg = settings()
    rows = conn.execute('''SELECT v.id,v.version_number,v.filename,v.lifecycle,v.retention_hold,
      v.id=d.active_version_id AS is_current, g.state AS gc_state,g.error_code,
      (greatest(coalesce(v.retired_at,v.ready_at,v.created_at),coalesce(d.deleted_at,v.created_at))
        < now()-(%s*interval '1 day')) AS old_enough,
      row_number() OVER (ORDER BY v.version_number DESC)-1 AS newer,
      EXISTS(SELECT 1 FROM ingestion_jobs j WHERE j.tenant_id=v.tenant_id AND j.version_id=v.id
        AND j.state NOT IN ('SUCCEEDED','FAILED','CANCELLED')) AS in_flight
      FROM document_versions v JOIN documents d ON d.tenant_id=v.tenant_id AND d.id=v.document_id
      LEFT JOIN version_gc_tasks g ON g.tenant_id=v.tenant_id AND g.version_id=v.id
      WHERE v.tenant_id=%s AND v.document_id=%s AND v.lifecycle<>'PURGED'
      ORDER BY v.version_number ASC LIMIT 500''',
      (cfg.version_retention_days, doc['tenant_id'], doc['id'])).fetchall()
    for v in rows:
        reasons = []
        if v['is_current']: reasons.append('current_version')
        if v['retention_hold']: reasons.append('retention_hold')
        if v['in_flight']: reasons.append('in_flight_job')
        if not v['old_enough']: reasons.append('retention_period')
        if not doc['deleted_at'] and v['newer'] < cfg.version_keep_latest: reasons.append('recent_version')
        if v['lifecycle'] in ('PURGING', 'PURGED'): reasons.append(v['lifecycle'].lower())
        v['eligible'], v['blocked_by'] = not reasons, reasons
    return rows


@router.post('/{doc_id}/retention/preview')
def preview(doc_id: UUID, user: User):
    with transaction(user.tenant_id) as conn:
        doc = lock_document(conn, user, doc_id, manage=True, deleted=True)
        return {'content_revision': doc['content_revision'], 'deleted': doc['deleted_at'] is not None,
                'retention_days': settings().version_retention_days, 'keep_latest': settings().version_keep_latest,
                'preview_limit': 500,
                'items': candidates(conn, doc)}


@router.post('/{doc_id}/retention/purge', status_code=202)
def enqueue(doc_id: UUID, body: Purge, user: User):
    with transaction(user.tenant_id) as conn:
        doc = lock_document(conn, user, doc_id, manage=True, deleted=True)
        if doc['content_revision'] != body.expected_content_revision:
            raise RagError('CONTENT_REVISION_CONFLICT', 'Document changed; preview retention again', 409)
        versions = {v['id']: v for v in candidates(conn, doc)}
        selected = list(dict.fromkeys(body.version_ids))
        # Completed requests remain replayable even though previews omit purged versions.
        completed = conn.execute("SELECT id,lifecycle FROM document_versions WHERE tenant_id=%s "
                                 "AND document_id=%s AND id=ANY(%s::uuid[]) AND lifecycle='PURGED'",
                                 (user.tenant_id, doc_id, selected)).fetchall()
        versions.update({v['id']: v for v in completed})
        for identifier in selected:
            version = versions.get(identifier)
            if not version:
                raise RagError('NOT_FOUND', 'Version not found', 404)
            if version['lifecycle'] in ('PURGING', 'PURGED'):
                continue
            if not version['eligible']:
                raise RagError('RETENTION_PROTECTED', 'Selected version is protected by retention policy', 409)
        for identifier in selected:
            if versions[identifier]['lifecycle'] == 'PURGED':
                continue
            conn.execute("UPDATE document_versions SET lifecycle='PURGING' WHERE tenant_id=%s AND id=%s",
                         (user.tenant_id, identifier))
            # Re-enqueue is explicit and fenced; an administrator can take over a revoked actor's request.
            conn.execute('''INSERT INTO version_gc_tasks(tenant_id,version_id,document_id,actor)
              VALUES(%s,%s,%s,%s) ON CONFLICT(tenant_id,version_id) DO UPDATE SET actor=EXCLUDED.actor,
              state='QUEUED',fence=version_gc_tasks.fence+1,lease_until=NULL,next_attempt_at=now(),error_code=NULL
              WHERE version_gc_tasks.state<>'DONE' ''', (user.tenant_id, identifier, doc_id, user.subject))
            audit(conn, user.tenant_id, user.subject, 'VERSION_PURGE_REQUESTED', identifier)
    return {'status': 'queued', 'version_ids': selected,
            'already_purged': [identifier for identifier in selected if versions[identifier]['lifecycle'] == 'PURGED']}
