"""Answer admission and metadata only: no prompts, model text or evidence excerpts persisted."""
from alembic import op

revision = '0004'
down_revision = '0003'


def upgrade():
    op.execute("""
    CREATE TABLE answer_runs (
      tenant_id text NOT NULL REFERENCES tenants(id), id uuid NOT NULL, subject text NOT NULL,
      state text NOT NULL DEFAULT 'RUNNING'
        CHECK(state IN ('RUNNING','answered','insufficient_evidence','conflicting_evidence','FAILED')),
      kb_ids uuid[] NOT NULL, sources jsonb NOT NULL DEFAULT '[]'::jsonb,
      model text NOT NULL, prompt_version text NOT NULL, retrieval_profile text,
      prompt_bytes integer, prompt_tokens integer, completion_tokens integer,
      created_at timestamptz NOT NULL DEFAULT clock_timestamp(), finished_at timestamptz,
      lease_until timestamptz NOT NULL, duration_ms double precision, error_code text,
      PRIMARY KEY(tenant_id,id)
    );
    CREATE INDEX answer_admission_window ON answer_runs(tenant_id,created_at,lease_until);
    CREATE TABLE answer_feedback (
      tenant_id text NOT NULL, answer_id uuid NOT NULL, subject text NOT NULL,
      rating text NOT NULL CHECK(rating IN ('helpful','unhelpful')),
      reason text CHECK(reason IN ('inaccurate','incomplete','outdated','other')),
      updated_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,answer_id,subject),
      FOREIGN KEY(tenant_id,answer_id) REFERENCES answer_runs(tenant_id,id)
    );
    """)
    for table in ('answer_runs', 'answer_feedback'):
        op.execute(f'ALTER TABLE {table} ENABLE ROW LEVEL SECURITY')
        op.execute(f'ALTER TABLE {table} FORCE ROW LEVEL SECURITY')
        op.execute(f"CREATE POLICY tenant_isolation ON {table} USING "
                   "(tenant_id=current_setting('app.tenant_id',true)) WITH CHECK "
                   "(tenant_id=current_setting('app.tenant_id',true))")
        op.execute(f'GRANT SELECT,INSERT,UPDATE,DELETE ON {table} TO rag_api')


def downgrade():
    raise RuntimeError('Retain answer audit metadata; use a reviewed migration plan')
