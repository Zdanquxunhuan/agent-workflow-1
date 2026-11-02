"""Real BGE-M3 dense inference. No synthetic-vector fallback is provided."""
import hashlib
import os
import secrets
import threading
from pathlib import Path
from contextlib import asynccontextmanager

import torch
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from transformers import AutoModel, AutoTokenizer

MODEL = "BAAI/bge-m3"
REVISION = "5617a9f61b028005a4858fdac845db406aefb181"
PROFILE = "bge-m3-dense-v1"
key = os.environ["EMBEDDING_KEY"]
tokenizer = None
model = None
lock = threading.Lock()


@asynccontextmanager
async def lifespan(app):
    global tokenizer, model
    torch.set_num_threads(int(os.environ.get("OMP_NUM_THREADS", "4")))
    cached = Path("/models/bge-m3")
    local = (cached / ".complete").exists() and (cached / ".complete").read_text().strip() == REVISION
    source = str(cached) if local else MODEL
    tokenizer = AutoTokenizer.from_pretrained(source, revision=REVISION, trust_remote_code=False,
                                             local_files_only=local)
    model = AutoModel.from_pretrained(source, revision=REVISION, trust_remote_code=False,
                                     local_files_only=local)
    model.eval()
    with torch.inference_mode():
        model(**tokenizer("预热", return_tensors="pt"))
    yield


app = FastAPI(title="BGE-M3 dense inference", lifespan=lifespan, docs_url=None, redoc_url=None)


def authenticate(authorization: str = Header(default="")):
    if not secrets.compare_digest(authorization, "Bearer " + key):
        raise HTTPException(401, "authentication required")


class Batch(BaseModel):
    texts: list[str] = Field(min_length=1, max_length=8)


class ChunkRequest(BaseModel):
    text: str = Field(min_length=1, max_length=300000)
    max_tokens: int = Field(default=480, ge=32, le=768)
    overlap: int = Field(default=64, ge=0, le=128)


@app.get("/health/ready")
def ready():
    return {"status": "ready", "model": MODEL, "revision": REVISION, "profile": PROFILE,
            "dimensions": 1024, "pooling": "cls", "normalized": True, "device": "cpu"}


@app.post("/embed", dependencies=[Depends(authenticate)])
def embed(body: Batch):
    if any(not text.strip() or len(text) > 12000 for text in body.texts):
        raise HTTPException(422, "empty or excessive input")
    if not lock.acquire(timeout=1):
        raise HTTPException(429, "embedding service busy", headers={"Retry-After": "2"})
    try:
        encoded = tokenizer(body.texts, padding=True, truncation=False, return_tensors="pt")
        if encoded["input_ids"].shape[1] > 1024:
            raise HTTPException(422, "input exceeds configured 1024-token limit")
        with torch.inference_mode():
            vectors = model(**encoded).last_hidden_state[:, 0]
            vectors = torch.nn.functional.normalize(vectors, p=2, dim=1)
        return {"profile": PROFILE, "vectors": vectors.tolist()}
    finally:
        lock.release()


@app.post("/chunk", dependencies=[Depends(authenticate)])
def chunk(body: ChunkRequest):
    if body.overlap >= body.max_tokens:
        raise HTTPException(422, "overlap must be smaller than max_tokens")
    # Fast-tokenizer offsets retain exact original text for citations.
    tokens = tokenizer(body.text, add_special_tokens=False, return_offsets_mapping=True,
                       truncation=False)["offset_mapping"]
    chunks = []
    for start in range(0, len(tokens), body.max_tokens - body.overlap):
        end = min(start + body.max_tokens, len(tokens))
        text = body.text[tokens[start][0]:tokens[end - 1][1]].strip()
        if text:
            chunks.append({"text": text, "token_count": end - start,
                           "content_hash": hashlib.sha256(text.encode()).hexdigest()})
        if end == len(tokens):
            break
    return {"profile": PROFILE, "chunks": chunks}

