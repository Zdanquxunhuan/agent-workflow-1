"""Pinned official Hugging Face revision and blob hashes (metadata read 2026-11-02)."""
from pathlib import Path

MODEL = "BAAI/bge-reranker-v2-m3"
REVISION = "953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e"
PROFILE = "bge-reranker-v2-m3-953dc6f-fp32-512-v1"
ROOT = Path("/models/bge-reranker-v2-m3")
# Small files use the Git blob SHA1; LFS files use SHA256. Never derive expected hashes from the download.
FILES = {
    "config.json": (795, "git", "9f62673cb00ec41dcec8947b9ed16f6f2eb23ba2"),
    "special_tokens_map.json": (964, "git", "b1879d702821e753ffe4245048eee415d54a9385"),
    "tokenizer_config.json": (1173, "git", "328a00a9a560aadcf2a3064f917517359eb3cc26"),
    "sentencepiece.bpe.model": (5069051, "sha256", "cfc8146abe2a0488e9e2a0c56de7952f7c11ab059eca145a0a727afce0db2865"),
    "tokenizer.json": (17098273, "sha256", "69564b696052886ed0ac63fa393e928384e0f8caada38c1f4864a9bfbf379c15"),
    "model.safetensors": (2271071852, "sha256", "d9e3e081faff1eefb84019509b2f5558fd74c1a05a2c7db22f74174fcedb5286"),
}


def verify(path, spec):
    import hashlib
    size, algorithm, expected = spec
    if not path.exists() or path.stat().st_size != size:
        return False
    with path.open("rb") as stream:
        if algorithm == "git":
            actual = hashlib.sha1(b"blob " + str(size).encode() + b"\0" + stream.read()).hexdigest()
        else:
            actual = hashlib.file_digest(stream, "sha256").hexdigest()
    return actual == expected
