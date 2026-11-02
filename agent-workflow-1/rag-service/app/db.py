from contextlib import contextmanager
from functools import lru_cache

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from app.config import settings


@lru_cache
def pool():
    return ConnectionPool(settings().database_url, min_size=1, max_size=8,
                          timeout=5, kwargs={"row_factory": dict_row}, open=True)


@contextmanager
def transaction(tenant_id: str | None = None):
    with pool().connection() as conn:
        with conn.transaction():
            conn.execute("SELECT set_config('app.tenant_id', %s, true)", (tenant_id or "",))
            conn.execute("SET LOCAL statement_timeout = '10s'")
            yield conn


def vector_literal(vector):
    import math
    if len(vector) != 1024 or not all(math.isfinite(float(x)) for x in vector):
        raise ValueError("invalid embedding dimensions or non-finite value")
    if not any(float(x) != 0 for x in vector):
        raise ValueError("zero embedding")
    return "[" + ",".join(str(float(x)) for x in vector) + "]"

