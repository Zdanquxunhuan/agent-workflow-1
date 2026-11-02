"""Idempotent local bootstrap; never executed in production."""
import time
import psycopg
from app.config import settings
from app.storage import s3


def main():
    cfg = settings()
    if cfg.environment != "development" or not cfg.dev_login_enabled:
        raise RuntimeError("development bootstrap disabled")
    with psycopg.connect(cfg.migration_url.replace("postgresql+psycopg://", "postgresql://")) as conn:
        for tenant in ["demo", "other"]:
            conn.execute("INSERT INTO tenants(id,name) VALUES(%s,%s) ON CONFLICT DO NOTHING", (tenant, tenant))
            for subject, role in [("admin", "admin"), ("alice", "member"), ("bob", "member")]:
                conn.execute("INSERT INTO tenant_memberships(tenant_id,subject,role) VALUES(%s,%s,%s) "
                             "ON CONFLICT DO NOTHING", (tenant, subject, role))
    for attempt in range(20):
        try:
            buckets = s3().list_buckets()["Buckets"]
            if cfg.s3_bucket not in {b["Name"] for b in buckets}:
                s3().create_bucket(Bucket=cfg.s3_bucket)
            print("Local tenants and private bucket initialized")
            return
        except Exception:
            if attempt == 19:
                raise
            time.sleep(2)


if __name__ == "__main__":
    main()

