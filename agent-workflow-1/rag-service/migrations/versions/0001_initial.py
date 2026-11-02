"""Initial tenant-scoped ingestion schema. Runtime roles are created by provisioning."""
from alembic import op

revision = "0001"
down_revision = None


def upgrade():
    op.execute("CREATE EXTENSION IF NOT EXISTS vector")
    op.execute("""
    CREATE TABLE tenants (id text PRIMARY KEY, name text NOT NULL);
    CREATE TABLE tenant_memberships (
        tenant_id text NOT NULL REFERENCES tenants(id), subject text NOT NULL,
        role text NOT NULL CHECK(role IN ('admin','member')), enabled boolean NOT NULL DEFAULT true,
        PRIMARY KEY(tenant_id, subject)
    );
    CREATE TABLE knowledge_bases (
        tenant_id text NOT NULL REFERENCES tenants(id), id uuid NOT NULL, name text NOT NULL,
        owner text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
        PRIMARY KEY(tenant_id,id)
    );
    CREATE TABLE kb_acl (
        tenant_id text NOT NULL, kb_id uuid NOT NULL, subject text NOT NULL,
        role text NOT NULL CHECK(role IN ('reader','contributor','manager')),
        PRIMARY KEY(tenant_id,kb_id,subject),
        FOREIGN KEY(tenant_id,kb_id) REFERENCES knowledge_bases(tenant_id,id),
        FOREIGN KEY(tenant_id,subject) REFERENCES tenant_memberships(tenant_id,subject)
    );
    CREATE TABLE documents (
        tenant_id text NOT NULL, id uuid NOT NULL, kb_id uuid NOT NULL, title text NOT NULL,
        active_version_id uuid, allowed_subjects text[], deleted_at timestamptz,
        revision integer NOT NULL DEFAULT 1, created_at timestamptz NOT NULL DEFAULT now(),
        PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,kb_id) REFERENCES knowledge_bases(tenant_id,id)
    );
    CREATE TABLE document_versions (
        tenant_id text NOT NULL, id uuid NOT NULL, document_id uuid NOT NULL,
        object_key text NOT NULL, filename text NOT NULL, sha256 text NOT NULL,
        size_bytes bigint NOT NULL CHECK(size_bytes > 0), created_at timestamptz NOT NULL DEFAULT now(),
        PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,document_id,id),
        FOREIGN KEY(tenant_id,document_id) REFERENCES documents(tenant_id,id)
    );
    ALTER TABLE documents ADD CONSTRAINT document_active_version
        FOREIGN KEY(tenant_id,id,active_version_id) REFERENCES document_versions(tenant_id,document_id,id);
    CREATE TABLE ingestion_jobs (
        tenant_id text NOT NULL, id uuid NOT NULL, document_id uuid NOT NULL, version_id uuid NOT NULL,
        actor text NOT NULL, idempotency_key text NOT NULL, request_hash text NOT NULL,
        state text NOT NULL DEFAULT 'QUEUED' CHECK(state IN
          ('QUEUED','PARSING','EMBEDDING','INDEXING','RETRY_WAIT','SUCCEEDED','FAILED','CANCELLED')),
        attempt integer NOT NULL DEFAULT 0, fence bigint NOT NULL DEFAULT 0, lease_until timestamptz,
        next_attempt_at timestamptz NOT NULL DEFAULT now(), last_enqueued_at timestamptz NOT NULL DEFAULT now(),
        progress integer NOT NULL DEFAULT 0, error_code text, error_message text,
        created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
        PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,actor,idempotency_key),
        FOREIGN KEY(tenant_id,document_id,version_id) REFERENCES document_versions(tenant_id,document_id,id)
    );
    CREATE TABLE outbox_events (
        id uuid PRIMARY KEY, tenant_id text NOT NULL, job_id uuid NOT NULL,
        available_at timestamptz NOT NULL DEFAULT now(), sent_at timestamptz,
        FOREIGN KEY(tenant_id,job_id) REFERENCES ingestion_jobs(tenant_id,id)
    );
    CREATE INDEX outbox_pending ON outbox_events(available_at) WHERE sent_at IS NULL;
    CREATE INDEX jobs_recovery ON ingestion_jobs(state,lease_until,next_attempt_at);
    CREATE TABLE chunks (
        tenant_id text NOT NULL, id uuid NOT NULL, document_id uuid NOT NULL, version_id uuid NOT NULL,
        ordinal integer NOT NULL, content text NOT NULL, page integer, section text,
        token_count integer NOT NULL, content_hash text NOT NULL, embedding_profile text NOT NULL,
        embedding vector(1024) NOT NULL,
        PRIMARY KEY(tenant_id,id), UNIQUE(tenant_id,version_id,ordinal),
        FOREIGN KEY(tenant_id,document_id,version_id) REFERENCES document_versions(tenant_id,document_id,id)
    );
    CREATE INDEX chunks_document ON chunks(tenant_id,document_id,version_id);
    CREATE INDEX chunks_hnsw ON chunks USING hnsw(embedding vector_cosine_ops);
    CREATE TABLE audit_events (
        tenant_id text NOT NULL, id uuid NOT NULL, actor text NOT NULL, action text NOT NULL,
        resource_id text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
        PRIMARY KEY(tenant_id,id)
    );
    """)
    for table in ["tenants", "tenant_memberships", "knowledge_bases", "kb_acl", "documents",
                  "document_versions", "ingestion_jobs", "outbox_events", "chunks", "audit_events"]:
        column = "id" if table == "tenants" else "tenant_id"
        op.execute(f"ALTER TABLE {table} ENABLE ROW LEVEL SECURITY")
        op.execute(f"ALTER TABLE {table} FORCE ROW LEVEL SECURITY")
        op.execute(f"CREATE POLICY tenant_isolation ON {table} USING "
                   f"({column} = current_setting('app.tenant_id', true)) WITH CHECK "
                   f"({column} = current_setting('app.tenant_id', true))")
    op.execute("GRANT USAGE ON SCHEMA public TO rag_api, rag_worker, rag_dispatcher")
    op.execute("GRANT SELECT ON tenants,tenant_memberships TO rag_api,rag_worker")
    op.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON knowledge_bases,kb_acl,documents,document_versions,"
               "ingestion_jobs,outbox_events,chunks,audit_events TO rag_api,rag_worker")
    op.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON ingestion_jobs,outbox_events TO rag_dispatcher")
    for table in ["ingestion_jobs", "outbox_events"]:
        op.execute(f"CREATE POLICY queue_dispatch ON {table} TO rag_dispatcher USING(true) WITH CHECK(true)")


def downgrade():
    raise RuntimeError("Destructive downgrade disabled; use a reviewed restore/migration plan")

