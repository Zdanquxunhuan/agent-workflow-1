from alembic import context
from sqlalchemy import create_engine, pool
from app.config import settings

connectable = create_engine(settings().migration_url, poolclass=pool.NullPool)
with connectable.connect() as connection:
    context.configure(connection=connection)
    with context.begin_transaction():
        context.run_migrations()

