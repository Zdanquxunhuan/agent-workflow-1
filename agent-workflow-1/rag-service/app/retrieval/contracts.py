from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, ConfigDict


class Contract(BaseModel):
    model_config = ConfigDict(extra='forbid', allow_inf_nan=False)


class Citation(Contract):
    id: UUID
    url: str
    page: int | None
    section: str | None


class Hit(Contract):
    chunk_id: UUID
    document_id: UUID
    document_version_id: UUID
    title: str
    text: str
    page: int | None
    section: str | None
    content_hash: str
    distance: float | None
    score: float
    score_type: Literal['rrf', 'reranker_logit']
    citation: Citation


class SearchResponse(Contract):
    request_id: str
    status: Literal['ok', 'degraded']
    hits: list[Hit]
    retrieval_profile_id: str
    profile_fingerprint: str
    embedding_profile: str
    lexical_profile: str
    reranker_profile: str | None
    degraded_components: list[str]
    context_tokens: int
    token_budget_basis: str
    timings_ms: dict[str, float]


class Candidate(Contract):
    chunk_id: UUID
    document_id: UUID
    title: str
    text: str
    channel_ranks: dict[str, int]
    channel_scores: dict[str, float]
    fusion_rank: int
    fusion_score: float
    rerank_score: float | None
    selected: bool


class Diagnostics(Contract):
    parameters: dict[str, Any]
    normalized_query: str
    lexical_terms: str
    identifiers: list[str]
    candidates: list[Candidate]


class SearchDebugResponse(SearchResponse):
    debug: Diagnostics
