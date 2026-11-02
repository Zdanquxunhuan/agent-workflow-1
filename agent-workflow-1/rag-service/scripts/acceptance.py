"""Run against the live Docker stack. No model or queue substitution."""
import json
import os
import time
from uuid import uuid4
import httpx

base = os.environ.get("RAG_ACCEPTANCE_URL", "http://api:8091")
client = httpx.Client(base_url=base, timeout=150)


def check(response, code=200):
    assert response.status_code == code, (response.status_code, response.text)
    return response.json()


def token(subject="admin", tenant="demo"):
    result = check(client.post("/api/v1/auth/dev-token", json={"subject": subject, "tenant_id": tenant}))
    return {"Authorization": "Bearer " + result["token"]}


def main():
    ready = check(client.get("/health/ready"))
    auth, outsider = token(), token("admin", "other")
    kb = check(client.post("/api/v1/knowledge-bases", headers=auth,
                           json={"name": "真实 BGE 验收 " + str(uuid4())[:8]}), 201)["id"]
    source = "# 售后退款政策\n已发货订单退款必须经过主管审批，审批通过后才能提交退款。\n未发货订单可以申请修改收货地址。\n"
    key = str(uuid4())
    payload = {"file": ("售后政策.md", source.encode(), "text/markdown")}
    job = check(client.post(f"/api/v1/knowledge-bases/{kb}/documents", headers=auth | {"Idempotency-Key": key}, files=payload), 202)
    replay = check(client.post(f"/api/v1/knowledge-bases/{kb}/documents", headers=auth | {"Idempotency-Key": key}, files=payload), 202)
    assert replay["job_id"] == job["job_id"]
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        state = check(client.get(f"/api/v1/jobs/{job['job_id']}", headers=auth))
        if state["state"] == "SUCCEEDED":
            break
        assert state["state"] not in {"FAILED", "CANCELLED"}, state
        time.sleep(2)
    else:
        raise AssertionError("ingestion did not finish within 300 seconds")
    question = {"query": "包裹已经寄出了，退款要找谁审核？", "knowledge_base_ids": [kb], "top_k": 3}
    found = check(client.post("/api/v1/search", headers=auth, json=question))
    assert found["hits"] and "主管审批" in found["hits"][0]["text"], found
    cite = found["hits"][0]["citation"]["url"]
    check(client.get(cite, headers=auth))
    check(client.post("/api/v1/search", headers=outsider, json=question), 404)
    check(client.get(cite, headers=outsider), 404)
    alice = token("alice")
    check(client.put(f"/api/v1/knowledge-bases/{kb}/acl", headers=auth, json={"subject": "alice", "role": "reader"}))
    assert check(client.post("/api/v1/search", headers=alice, json=question))["hits"]
    check(client.delete(f"/api/v1/knowledge-bases/{kb}/acl/alice", headers=auth))
    check(client.get(cite, headers=alice), 404)
    # Keep the successful document for manual exploration; deletion is covered in integration tests.
    print(json.dumps({"result": "passed", "model_profile": ready["embedding_profile"], "kb_id": kb,
                      "document_id": job["document_id"], "job_id": job["job_id"],
                      "checks": ["real BGE ingestion", "MQ dispatch", "idempotency", "semantic query",
                                 "citation", "tenant isolation", "KB grant and revoke"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
