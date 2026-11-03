from functools import lru_cache

from pydantic import model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="RAG_", extra="ignore")
    environment: str = "development"
    database_url: str = "postgresql://rag_api:rag_dev_only@localhost:5438/rag"
    migration_url: str = "postgresql+psycopg://postgres:postgres_dev_only@localhost:5438/rag"
    broker_url: str = "amqp://rag:rag_dev_only@localhost:5678//"
    s3_endpoint: str = "http://localhost:9010"
    s3_access_key: str = "rag_local"
    s3_secret_key: str = "rag_object_dev_only"
    s3_bucket: str = "rag-documents"
    auth_mode: str = "local"
    auth_secret: str = "rag-local-development-secret-at-least-32-bytes"
    auth_issuer: str = "rag-local"
    auth_audience: str = "rag-api"
    auth_jwks_url: str = ""
    dev_login_enabled: bool = False
    embedding_url: str = "http://localhost:8092"
    embedding_key: str = "rag-embedding-local-development-key"
    embedding_profile: str = "bge-m3-dense-v1"
    embedding_timeout: float = 120
    upload_max_bytes: int = 10 * 1024 * 1024
    max_pages: int = 100
    max_chars: int = 300000
    chunk_tokens: int = 480
    overlap_tokens: int = 64
    max_chunks: int = 1000
    max_attempts: int = 3
    lease_seconds: int = 180
    recovery_seconds: int = 30
    retrieval_profile: str = "hybrid-rerank-v2"
    search_deadline_seconds: float = 12.0
    query_embedding_timeout: float = 4.0
    rerank_timeout: float = 6.0
    reranker_url: str = "http://localhost:8093"
    reranker_key: str = "rag-reranker-local-development-key"
    reranker_profile: str = "bge-reranker-v2-m3-953dc6f-fp32-512-v1"
    search_requests_per_minute: int = 120
    search_tenant_concurrency: int = 2
    version_retention_days: int = 30
    version_keep_latest: int = 3

    @model_validator(mode="after")
    def validate_config(self):
        if self.version_retention_days < 1 or self.version_keep_latest < 1:
            raise ValueError('Version retention must protect at least one day and one version')
        if self.auth_mode not in {"local", "oidc"}:
            raise ValueError("RAG_AUTH_MODE must be local or oidc")
        if self.auth_mode == "local" and len(self.auth_secret) < 32:
            raise ValueError("local signing secret must contain at least 32 characters")
        if self.auth_mode == "oidc" and not self.auth_jwks_url.startswith("https://"):
            raise ValueError("OIDC requires an HTTPS JWKS URL")
        if not 0 <= self.overlap_tokens < self.chunk_tokens:
            raise ValueError("overlap must be smaller than chunk size")
        if self.retrieval_profile not in {"dense-v2", "lexical-v2", "hybrid-v2", "hybrid-rerank-v2"}:
            raise ValueError("unknown retrieval profile")
        if not (1 <= self.search_deadline_seconds <= 120 and 0 < self.query_embedding_timeout <= 60
                and 0 < self.rerank_timeout <= 60 and 1 <= self.search_tenant_concurrency <= 32
                and 1 <= self.search_requests_per_minute <= 10000):
            raise ValueError("invalid retrieval resource limits")
        if self.environment == "production":
            if self.auth_mode != "oidc" or self.dev_login_enabled:
                raise ValueError("production requires OIDC with development login disabled")
            if any("dev_only" in v or "development" in v for v in
                   [self.database_url, self.s3_secret_key, self.broker_url, self.embedding_key, self.reranker_key]):
                raise ValueError("development credentials are forbidden in production")
            if not self.s3_endpoint.startswith("https://"):
                raise ValueError("production object storage requires TLS")
        return self


@lru_cache
def settings() -> Settings:
    return Settings()

