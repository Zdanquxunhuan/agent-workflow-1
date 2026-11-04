"""Derived private memory vectors. MySQL remains the authority; keep revision tombstones."""
from alembic import op

revision = '0005'
down_revision = '0004'


def upgrade():
    op.execute("""
    CREATE TABLE memory_vectors (
      tenant_id text NOT NULL, owner_id text NOT NULL, memory_id uuid NOT NULL,
      revision bigint NOT NULL CHECK(revision>0), expires_at bigint NOT NULL,
      deleted boolean NOT NULL, profile text NOT NULL, payload_hash text NOT NULL,
      embedding vector(1024), updated_at timestamptz NOT NULL DEFAULT now(),
      PRIMARY KEY(tenant_id,owner_id,memory_id)
    );
    CREATE INDEX memory_vector_active ON memory_vectors(tenant_id,owner_id,expires_at) WHERE NOT deleted;
    CREATE TABLE memory_index_admissions (
      tenant_id text PRIMARY KEY, window_start bigint NOT NULL, requests integer NOT NULL
    );
    """)
    for table in ('memory_vectors', 'memory_index_admissions'):
        op.execute(f'ALTER TABLE {table} ENABLE ROW LEVEL SECURITY')
        op.execute(f'ALTER TABLE {table} FORCE ROW LEVEL SECURITY')
        op.execute(f"CREATE POLICY tenant_isolation ON {table} USING "
                   "(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK "
                   "(tenant_id=current_setting('app.tenant_id',true))")
        op.execute(f'GRANT SELECT,INSERT,UPDATE,DELETE ON {table} TO rag_api')


def downgrade():
    raise RuntimeError('Memory tombstones prevent resurrection; use a reviewed migration')
