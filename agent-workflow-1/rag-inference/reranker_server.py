import os
import secrets
import threading
from contextlib import asynccontextmanager

import torch
from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from transformers import AutoModelForSequenceClassification, AutoTokenizer

from reranker_config import MODEL, REVISION, PROFILE, ROOT, FILES, verify

key = os.environ["RERANKER_KEY"]
tokenizer, model = None, None
lock = threading.Lock()


@asynccontextmanager
async def lifespan(app):
    global tokenizer, model
    if not all(verify(ROOT / name, spec) for name, spec in FILES.items()):
        raise RuntimeError("Pinned reranker artifacts missing/corrupt; run download_reranker.py first")
    torch.set_num_threads(int(os.environ.get("OMP_NUM_THREADS", "2")))
    tokenizer = AutoTokenizer.from_pretrained(str(ROOT), local_files_only=True, trust_remote_code=False)
    model = AutoModelForSequenceClassification.from_pretrained(
        str(ROOT), local_files_only=True, trust_remote_code=False, torch_dtype=torch.float32)
    model.eval()
    with torch.inference_mode():
        model(**tokenizer("退款", "退款政策", return_tensors="pt"))
    yield


app = FastAPI(title="BGE reranker", lifespan=lifespan, docs_url=None, redoc_url=None)


def auth(authorization: str = Header(default="")):
    if not secrets.compare_digest(authorization, "Bearer " + key):
        raise HTTPException(401, "authentication required")


class Rerank(BaseModel):
    query: str = Field(min_length=1, max_length=2000)
    passages: list[str] = Field(min_length=1, max_length=20)


@app.get("/health/ready")
def ready():
    return {"status": "ready", "model": MODEL, "revision": REVISION, "profile": PROFILE,
            "device": "cpu", "precision": "float32", "max_pair_tokens": 512}


@app.post("/rerank", dependencies=[Depends(auth)])
def rerank(body: Rerank):
    if (not body.query.strip() or any(not value.strip() or len(value) > 12000 for value in body.passages)
            or len(tokenizer.encode(body.query, add_special_tokens=False)) > 256):
        raise HTTPException(422, "empty or excessive input")
    if not lock.acquire(timeout=0.1):
        raise HTTPException(429, "reranker busy", headers={"Retry-After": "2"})
    try:
        scores = []
        for start in range(0, len(body.passages), 4):
            pairs = [(body.query, value) for value in body.passages[start:start + 4]]
            encoded = tokenizer(pairs, padding=True, truncation="only_second", max_length=512, return_tensors="pt")
            with torch.inference_mode():
                scores.extend(model(**encoded).logits.view(-1).float().tolist())
        return {"profile": PROFILE, "scores": scores, "score_type": "logit", "max_pair_tokens": 512}
    finally:
        lock.release()
