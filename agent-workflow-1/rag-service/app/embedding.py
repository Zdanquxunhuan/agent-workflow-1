import httpx

from app.config import settings
from app.db import vector_literal
from app.errors import RagError


def call(path, payload):
    cfg = settings()
    try:
        response = httpx.post(cfg.embedding_url + path, json=payload,
                              headers={"Authorization": "Bearer " + cfg.embedding_key},
                              timeout=httpx.Timeout(cfg.embedding_timeout, connect=5))
        if response.status_code == 422:
            raise RagError("EMBEDDING_INPUT_INVALID", "Text exceeds model input constraints", 422)
        response.raise_for_status()
        result = response.json()
        if result["profile"] != cfg.embedding_profile:
            raise ValueError("embedding profile mismatch")
        return result
    except (httpx.HTTPError, ValueError, KeyError) as exc:
        raise RagError("EMBEDDING_UNAVAILABLE", "Embedding service unavailable or incompatible", 503, True) from exc


def embed(texts):
    result = call("/embed", {"texts": texts})
    vectors = result.get("vectors", [])
    if len(vectors) != len(texts):
        raise RagError("EMBEDDING_INVALID", "Embedding count mismatch", 503, True)
    for vector in vectors:
        vector_literal(vector)
    return vectors


def chunk_text(text):
    cfg = settings()
    return call("/chunk", {"text": text, "max_tokens": cfg.chunk_tokens,
                           "overlap": cfg.overlap_tokens})["chunks"]

