"""Add lexical sidecar and database-backed tenant query admission; keep V1 vectors intact."""
from alembic import op

revision = "0002"
down_revision = "0001"


def upgrade():
    op.execute("""
    CREATE TABLE chunk_lexical (
      tenant_id text NOT NULL, chunk_id uuid NOT NULL, profile text NOT NULL,
      content_hash text NOT NULL, lexemes text NOT NULL, identifiers text[] NOT NULL,
      search_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple'::regconfig,lexemes)) STORED,
      PRIMARY KEY(tenant_id,chunk_id,profile),
      FOREIGN KEY(tenant_id,chunk_id) REFERENCES chunks(tenant_id,id) ON DELETE CASCADE
    );
    CREATE INDEX chunk_lexical_gin ON chunk_lexical USING gin(search_vector);
    CREATE INDEX chunk_identifiers_gin ON chunk_lexical USING gin(identifiers);
    CREATE TABLE search_admissions (
      tenant_id text NOT NULL REFERENCES tenants(id), id uuid NOT NULL, subject text NOT NULL,
      created_at timestamptz NOT NULL DEFAULT clock_timestamp(), lease_until timestamptz NOT NULL,
      PRIMARY KEY(tenant_id,id)
    );
    CREATE INDEX search_admissions_window ON search_admissions(tenant_id,created_at);
    """)
    for table in ["chunk_lexical", "search_admissions"]:
        op.execute(f"ALTER TABLE {table} ENABLE ROW LEVEL SECURITY")
        op.execute(f"ALTER TABLE {table} FORCE ROW LEVEL SECURITY")
        op.execute(f"CREATE POLICY tenant_isolation ON {table} USING "
                   "(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK "
                   "(tenant_id=current_setting('app.tenant_id',true))")
    op.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON chunk_lexical TO rag_api,rag_worker")
    op.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON search_admissions TO rag_api")


def downgrade():
    raise RuntimeError("Keep the additive V2 schema for rollback; switch the retrieval profile instead")
