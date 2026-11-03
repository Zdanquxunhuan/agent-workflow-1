"""Immutable document versions, compare-and-swap publication and resumable retention."""
from alembic import op

revision = '0003'
down_revision = '0002'


def upgrade():
    op.execute("""
    ALTER TABLE documents ADD COLUMN content_revision bigint NOT NULL DEFAULT 0;
    ALTER TABLE documents ADD COLUMN version_counter integer NOT NULL DEFAULT 0;
    ALTER TABLE document_versions ADD COLUMN version_number integer;
    ALTER TABLE document_versions ADD COLUMN base_revision bigint NOT NULL DEFAULT 0;
    ALTER TABLE document_versions ADD COLUMN created_by text NOT NULL DEFAULT 'legacy';
    ALTER TABLE document_versions ADD COLUMN change_note text NOT NULL DEFAULT '';
    ALTER TABLE document_versions ADD COLUMN auto_publish boolean NOT NULL DEFAULT true;
    ALTER TABLE document_versions ADD COLUMN lifecycle text NOT NULL DEFAULT 'PROCESSING'
      CHECK(lifecycle IN ('PROCESSING','READY','PURGING','PURGED'));
    ALTER TABLE document_versions ADD COLUMN ready_at timestamptz;
    ALTER TABLE document_versions ADD COLUMN first_published_at timestamptz;
    ALTER TABLE document_versions ADD COLUMN retired_at timestamptz;
    ALTER TABLE document_versions ADD COLUMN purged_at timestamptz;
    ALTER TABLE document_versions ADD COLUMN retention_hold boolean NOT NULL DEFAULT false;
    ALTER TABLE document_versions ADD COLUMN publication_note text;
    ALTER TABLE document_versions ADD COLUMN embedding_profile text;
    ALTER TABLE document_versions ADD COLUMN lexical_profile text;
    WITH ranked AS (SELECT tenant_id,id,row_number() OVER
      (PARTITION BY tenant_id,document_id ORDER BY created_at,id) AS number FROM document_versions)
    UPDATE document_versions v SET version_number=r.number FROM ranked r
      WHERE v.tenant_id=r.tenant_id AND v.id=r.id;
    ALTER TABLE document_versions ALTER COLUMN version_number SET NOT NULL;
    ALTER TABLE document_versions ALTER COLUMN version_number SET DEFAULT 1;
    ALTER TABLE document_versions ADD CONSTRAINT version_number_unique UNIQUE(tenant_id,document_id,version_number);
    UPDATE documents d SET version_counter=(SELECT count(*) FROM document_versions v
      WHERE v.tenant_id=d.tenant_id AND v.document_id=d.id),
      content_revision=CASE WHEN active_version_id IS NULL THEN 0 ELSE 1 END;
    UPDATE document_versions v SET created_by=j.actor FROM ingestion_jobs j
      WHERE j.tenant_id=v.tenant_id AND j.version_id=v.id;
    UPDATE document_versions v SET lifecycle='READY',ready_at=v.created_at,
      first_published_at=v.created_at,embedding_profile=(SELECT c.embedding_profile FROM chunks c
        WHERE c.tenant_id=v.tenant_id AND c.version_id=v.id LIMIT 1),
      lexical_profile=(SELECT l.profile FROM chunk_lexical l JOIN chunks c
        ON c.tenant_id=l.tenant_id AND c.id=l.chunk_id WHERE c.tenant_id=v.tenant_id
        AND c.version_id=v.id ORDER BY l.profile LIMIT 1)
      FROM documents d WHERE d.tenant_id=v.tenant_id AND d.active_version_id=v.id;
    UPDATE document_versions v SET retired_at=d.deleted_at FROM documents d
      WHERE d.tenant_id=v.tenant_id AND d.active_version_id=v.id AND d.deleted_at IS NOT NULL;
    UPDATE documents SET active_version_id=NULL,content_revision=content_revision+1 WHERE deleted_at IS NOT NULL;
    CREATE INDEX versions_history ON document_versions(tenant_id,document_id,version_number DESC);
    CREATE TABLE publication_events (
      tenant_id text NOT NULL, id uuid NOT NULL, document_id uuid NOT NULL,
      from_version_id uuid, to_version_id uuid NOT NULL, actor text NOT NULL,
      action text NOT NULL CHECK(action IN ('publish','rollback','auto_publish')),
      reason text NOT NULL DEFAULT '', previous_revision bigint NOT NULL, content_revision bigint NOT NULL,
      idempotency_key text, request_hash text, created_at timestamptz NOT NULL DEFAULT now(),
      PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,actor,idempotency_key),
      FOREIGN KEY(tenant_id,document_id) REFERENCES documents(tenant_id,id),
      FOREIGN KEY(tenant_id,document_id,to_version_id) REFERENCES document_versions(tenant_id,document_id,id),
      FOREIGN KEY(tenant_id,document_id,from_version_id) REFERENCES document_versions(tenant_id,document_id,id)
    );
    CREATE TABLE version_gc_tasks (
      tenant_id text NOT NULL, version_id uuid NOT NULL, document_id uuid NOT NULL, actor text NOT NULL,
      state text NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','RUNNING','RETRY_WAIT','DONE')),
      attempt integer NOT NULL DEFAULT 0, fence bigint NOT NULL DEFAULT 0,
      lease_until timestamptz, next_attempt_at timestamptz NOT NULL DEFAULT now(),
      error_code text, created_at timestamptz NOT NULL DEFAULT now(), completed_at timestamptz,
      PRIMARY KEY(tenant_id,version_id),
      FOREIGN KEY(tenant_id,document_id,version_id) REFERENCES document_versions(tenant_id,document_id,id)
    );
    CREATE INDEX gc_pending ON version_gc_tasks(tenant_id,state,next_attempt_at,lease_until);
    CREATE TABLE citation_tombstones (
      tenant_id text NOT NULL, chunk_id uuid NOT NULL, document_id uuid NOT NULL, version_id uuid NOT NULL,
      purged_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,chunk_id),
      FOREIGN KEY(tenant_id,document_id,version_id) REFERENCES document_versions(tenant_id,document_id,id)
    );
    """)
    for table in ('publication_events', 'version_gc_tasks', 'citation_tombstones'):
        op.execute(f'ALTER TABLE {table} ENABLE ROW LEVEL SECURITY')
        op.execute(f'ALTER TABLE {table} FORCE ROW LEVEL SECURITY')
        op.execute(f"CREATE POLICY tenant_isolation ON {table} USING "
                   "(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK "
                   "(tenant_id=current_setting('app.tenant_id',true))")
        op.execute(f'GRANT SELECT,INSERT,UPDATE,DELETE ON {table} TO rag_api,rag_worker')


def downgrade():
    raise RuntimeError('V3 version history is retained; restore using a reviewed migration plan')
