import hashlib
import json
from pathlib import Path

from app.errors import RagError
from app.retrieval.lexical import PROFILE as LEXICAL_PROFILE

PROFILES = json.loads(Path(__file__).with_name("profiles.json").read_text())


def resolve(name):
    if name not in PROFILES:
        raise RagError("INVALID_PROFILE", "Unknown retrieval profile", 422)
    return dict(PROFILES[name])


def fingerprint(profile, embedding_profile, reranker_profile):
    config = {"parameters": profile, "lexical": LEXICAL_PROFILE,
              "embedding": embedding_profile, "reranker": reranker_profile}
    return hashlib.sha256(json.dumps(config, sort_keys=True).encode()).hexdigest()
