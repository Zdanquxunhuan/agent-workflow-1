"""Isolated real-infrastructure tests; never put synthetic vectors in the working knowledge base."""
import os
import subprocess
import sys
from pathlib import Path
from uuid import uuid4

import psycopg
from psycopg import sql
from sqlalchemy.engine import make_url

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


def main():
    if os.environ.get("RAG_ENVIRONMENT", "development") != "development":
        raise RuntimeError("Integration harness is restricted to development")
    database = "rag_test_" + uuid4().hex
    bucket = database.replace("_", "-")
    migration = make_url(os.environ["RAG_MIGRATION_URL"])
    runtime = make_url(os.environ["RAG_DATABASE_URL"])
    admin_url = migration.set(drivername="postgresql").render_as_string(hide_password=False)
    with psycopg.connect(admin_url, autocommit=True) as admin:
        admin.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(database)))
    env = os.environ | {
        "RAG_MIGRATION_URL": migration.set(database=database).render_as_string(hide_password=False),
        "RAG_DATABASE_URL": runtime.set(database=database).render_as_string(hide_password=False),
        "RAG_S3_BUCKET": bucket,
        "RAG_INTEGRATION": "1",
        "RAG_SEARCH_REQUESTS_PER_MINUTE": "10000",
    }
    try:
        subprocess.run(["alembic", "upgrade", "head"], env=env, check=True)
        subprocess.run([sys.executable, "-m", "app.bootstrap"], env=env, check=True)
        command = sys.argv[1:] or ["pytest", "-q", "-p", "no:cacheprovider"]
        subprocess.run(command, env=env, check=True)
    finally:
        # Exact random resources created above; never truncate shared application data.
        os.environ["RAG_S3_BUCKET"] = bucket
        try:
            from app.storage import s3
            for page in s3().get_paginator("list_objects_v2").paginate(Bucket=bucket):
                objects = [{"Key": row["Key"]} for row in page.get("Contents", [])]
                if objects:
                    s3().delete_objects(Bucket=bucket, Delete={"Objects": objects})
            s3().delete_bucket(Bucket=bucket)
        finally:
            with psycopg.connect(admin_url, autocommit=True) as admin:
                admin.execute(sql.SQL("DROP DATABASE {} WITH (FORCE)").format(sql.Identifier(database)))


if __name__ == "__main__":
    main()
