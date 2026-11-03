from typing import Literal
from uuid import UUID

from pydantic import Field, model_validator
from app.retrieval.contracts import Contract


class AnswerRequest(Contract):
    query: str = Field(min_length=1, max_length=2000)
    knowledge_base_ids: list[UUID] = Field(min_length=1, max_length=5)
    top_k: int = Field(default=5, ge=1, le=8)
    profile: str | None = Field(default=None, max_length=80)


class Evidence(Contract):
    source_id: str = Field(pattern=r'^S[1-8]$')
    quote: str = Field(min_length=4, max_length=500)


class Claim(Contract):
    text: str = Field(min_length=1, max_length=1000)
    evidence: list[Evidence] = Field(min_length=1, max_length=3)


class ModelAnswer(Contract):
    status: Literal['answered', 'insufficient_evidence', 'conflicting_evidence']
    claims: list[Claim] = Field(max_length=6)

    @model_validator(mode='after')
    def check_claims(self):
        if self.status == 'insufficient_evidence' and self.claims:
            raise ValueError('Refusals must not contain unsupported claims')
        if self.status != 'insufficient_evidence' and not self.claims:
            raise ValueError('An answer requires cited claims')
        if any(not c.text.strip() for c in self.claims) or sum(len(c.text) for c in self.claims) > 4000:
            raise ValueError('Empty or oversized answer')
        if self.status == 'conflicting_evidence' and len({e.source_id for c in self.claims for e in c.evidence}) < 2:
            raise ValueError('Conflict requires at least two sources')
        return self


class AnswerCitation(Contract):
    id: str
    source_id: str
    chunk_id: UUID
    document_id: UUID
    document_version_id: UUID
    content_revision: int
    content_hash: str
    title: str
    page: int | None
    section: str | None
    quote: str
    url: str


class Statement(Contract):
    text: str
    citation_ids: list[str]


class Usage(Contract):
    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    source: Literal['provider', 'unavailable'] = 'unavailable'


class AnswerResponse(Contract):
    request_id: str
    answer_id: UUID
    status: Literal['answered', 'insufficient_evidence', 'conflicting_evidence']
    reason: Literal['no_retrieved_evidence', 'model_insufficient_evidence', 'source_conflict'] | None = None
    answer: str
    statements: list[Statement]
    citations: list[AnswerCitation]
    model: str | None
    prompt_version: str
    retrieval_profile_id: str
    degraded_components: list[str]
    prompt_bytes: int
    usage: Usage
    timings_ms: dict[str, float]


class Feedback(Contract):
    rating: Literal['helpful', 'unhelpful']
    reason: Literal['inaccurate', 'incomplete', 'outdated', 'other'] | None = None
