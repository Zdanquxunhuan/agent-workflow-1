"""Document lock is the serialization boundary for publication, deletion and retention."""
import hashlib
import json
from uuid import uuid4

from app.auth import require_kb
from app.config import settings
from app.errors import RagError
from app.retrieval.lexical import PROFILE


def audit(conn, tenant, actor, action, resource):
    conn.execute('INSERT INTO audit_events(tenant_id,id,actor,action,resource_id) VALUES(%s,%s,%s,%s,%s)',
                 (tenant, uuid4(), actor, action, str(resource)))


def lock_document(conn, user, doc_id, manage=False, write=False, deleted=False):
    doc = conn.execute('SELECT * FROM documents WHERE tenant_id=%s AND id=%s FOR UPDATE',
                       (user.tenant_id, doc_id)).fetchone()
    if not doc or (doc['deleted_at'] and not deleted):
        raise RagError('NOT_FOUND', 'Document not found', 404)
    require_kb(conn, user, doc['kb_id'], manage=manage, write=write)
    # Lifecycle APIs reveal content/history: even managers must satisfy document read ACL.
    if doc['allowed_subjects'] is not None and user.subject not in doc['allowed_subjects']:
        raise RagError('NOT_FOUND', 'Document not found', 404)
    return doc


def manager(conn, user, kb_id):
    row = conn.execute('SELECT m.role,a.role AS kb_role FROM tenant_memberships m LEFT JOIN kb_acl a '
                       'ON a.tenant_id=m.tenant_id AND a.subject=m.subject AND a.kb_id=%s '
                       'WHERE m.tenant_id=%s AND m.subject=%s AND m.enabled',
                       (kb_id, user.tenant_id, user.subject)).fetchone()
    return bool(row and (row['role'] == 'admin' or row['kb_role'] == 'manager'))


def get_version(conn, user, doc, version_id):
    version = conn.execute('SELECT * FROM document_versions WHERE tenant_id=%s AND document_id=%s AND id=%s',
                           (user.tenant_id, doc['id'], version_id)).fetchone()
    if not version:
        raise RagError('NOT_FOUND', 'Version not found', 404)
    if not version['first_published_at'] and not manager(conn, user, doc['kb_id']):
        require_kb(conn, user, doc['kb_id'], write=True)
        if version['created_by'] != user.subject:
            raise RagError('NOT_FOUND', 'Version not found', 404)
    return version


def require_retained(version):
    if version['lifecycle'] in ('PURGING', 'PURGED'):
        raise RagError('VERSION_GONE', 'Version content has been retired by retention policy', 410)


def check_indexes(conn, tenant, version):
    require_retained(version)
    if version['lifecycle'] != 'READY':
        raise RagError('VERSION_NOT_READY', 'Version is not ready for publication', 409)
    if version['embedding_profile'] != settings().embedding_profile:
        raise RagError('PROFILE_MISMATCH', 'Version embedding profile is incompatible', 409)
    row = conn.execute('''SELECT count(*) AS total, count(*) FILTER (WHERE
      c.embedding_profile=%s AND EXISTS (SELECT 1 FROM chunk_lexical l
      WHERE l.tenant_id=c.tenant_id AND l.chunk_id=c.id AND l.profile=%s
      AND l.content_hash=c.content_hash)) AS valid FROM chunks c WHERE c.tenant_id=%s AND c.version_id=%s''',
                       (settings().embedding_profile, PROFILE, tenant, version['id'])).fetchone()
    if not row['total'] or row['total'] != row['valid']:
        raise RagError('INDEX_INCOMPLETE', 'Version indexes are incomplete; rebuild before publishing', 409)


def activate(conn, doc, version, actor, action, reason='', key=None, request_hash=None):
    """Caller holds the document row lock and has checked the current revision/permissions."""
    check_indexes(conn, doc['tenant_id'], version)
    previous = doc['active_version_id']
    if previous and previous != version['id']:
        conn.execute('UPDATE document_versions SET retired_at=now() WHERE tenant_id=%s AND id=%s',
                     (doc['tenant_id'], previous))
    conn.execute('UPDATE document_versions SET first_published_at=coalesce(first_published_at,now()), '
                 'retired_at=NULL,publication_note=NULL,lexical_profile=%s WHERE tenant_id=%s AND id=%s',
                 (PROFILE, doc['tenant_id'], version['id']))
    row = conn.execute('UPDATE documents SET active_version_id=%s,content_revision=content_revision+1 '
                       'WHERE tenant_id=%s AND id=%s AND deleted_at IS NULL AND content_revision=%s '
                       'RETURNING content_revision',
                       (version['id'], doc['tenant_id'], doc['id'], doc['content_revision'])).fetchone()
    if not row:
        raise RagError('CONTENT_REVISION_CONFLICT', 'Published content changed; reload and review', 409)
    event = uuid4()
    conn.execute('''INSERT INTO publication_events(tenant_id,id,document_id,from_version_id,to_version_id,
      actor,action,reason,previous_revision,content_revision,idempotency_key,request_hash)
      VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)''',
      (doc['tenant_id'], event, doc['id'], previous, version['id'], actor, action, reason,
       doc['content_revision'], row['content_revision'], key, request_hash))
    audit(conn, doc['tenant_id'], actor, 'DOCUMENT_' + action.upper(), version['id'])
    return {'version_id': version['id'], 'content_revision': row['content_revision'], 'event_id': event}


def publish_version(conn, user, doc_id, version_id, expected, key, reason, rollback=False):
    if not reason.strip():
        raise RagError('INVALID_ARGUMENT', 'Publication reason cannot be blank', 422)
    conn.execute('SELECT pg_advisory_xact_lock(hashtextextended(%s,3))',
                 (user.tenant_id + ':' + user.subject + ':' + key,))
    doc = lock_document(conn, user, doc_id, manage=True)
    digest = hashlib.sha256(json.dumps([str(doc_id), str(version_id), expected, reason, rollback]).encode()).hexdigest()
    replay = conn.execute('SELECT * FROM publication_events WHERE tenant_id=%s AND actor=%s AND idempotency_key=%s',
                          (user.tenant_id, user.subject, key)).fetchone()
    if replay:
        if replay['request_hash'] != digest:
            raise RagError('IDEMPOTENCY_CONFLICT', 'Key was used for another publication', 409)
        return {'version_id': replay['to_version_id'], 'content_revision': replay['content_revision'],
                'event_id': replay['id'], 'replayed': True}
    if doc['content_revision'] != expected:
        raise RagError('CONTENT_REVISION_CONFLICT', 'Published content changed; reload and review', 409)
    version = get_version(conn, user, doc, version_id)
    if rollback and not version['first_published_at']:
        raise RagError('INVALID_ROLLBACK', 'Rollback requires a previously published version', 409)
    if doc['active_version_id'] == version_id:
        raise RagError('ALREADY_CURRENT', 'Version is already current', 409)
    return activate(conn, doc, version, user.subject, 'rollback' if rollback else 'publish', reason, key, digest) | {'replayed': False}
