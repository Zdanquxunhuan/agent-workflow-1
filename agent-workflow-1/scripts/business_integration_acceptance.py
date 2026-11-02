#!/usr/bin/env python3
"""Run against compose.business.yaml. Uses only synthetic data and never resets databases.

Evidence is written to target/business-integration/acceptance-report.json.
The optional --fault-drill briefly stops ONLY this compose project's business-system.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
AGENT = os.environ.get("INTEGRATION_AGENT_URL", "http://127.0.0.1:18090")
BUSINESS = os.environ.get("INTEGRATION_BUSINESS_URL", "http://127.0.0.1:8091")
SERVICE_KEY = os.environ.get("BUSINESS_API_KEY", "local-business-service-key-change-before-production")
OPS_KEY = os.environ.get("BUSINESS_OPERATIONS_KEY", "local-business-operations-key-separate-from-agent")
RUN = uuid.uuid4().hex[:12]
CHECKS: list[dict] = []
TASKS: dict[str, str] = {}
HOST_JVM = False


def request(base, path, *, method="GET", body=None, token=None, key=None, operations=False):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if key:
        headers["X-Operations-Key" if operations else "X-Api-Key"] = key
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def need(name, ok, detail=""):
    CHECKS.append({"name": name, "passed": bool(ok), "detail": str(detail)})
    print(("PASS " if ok else "FAIL ") + name, flush=True)
    if not ok:
        raise AssertionError(f"{name}: {detail}")


def wait_for(fn, predicate, timeout=45):
    end = time.monotonic() + timeout
    last = None
    while time.monotonic() < end:
        try:
            last = fn()
            if predicate(last):
                return last
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            pass
        time.sleep(0.4)
    raise AssertionError(f"Timed out waiting for expected state: {last}")


def compose(*args):
    if HOST_JVM and len(args) == 2 and args[0] in {"start", "stop"} and args[1] in {"agent-app", "business-system"}:
        import local_business_stack as runtime
        service = "agent" if args[1] == "agent-app" else "business"
        getattr(runtime, args[0])(service)
        return ""
    result = subprocess.run(["docker", "compose", "-p", "workflow-business", "-f", "compose.business.yaml", *args],
                            cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180)
    if result.returncode:
        raise RuntimeError(result.stderr[-3000:] or result.stdout[-3000:])
    return result.stdout


def sql(service, db, user, password, statement):
    return compose("exec", "-T", service, "mysql", "-N", "-B", "--default-character-set=utf8mb4",
                   "-u" + user, "-p" + password, "-D", db, "-e", statement).strip()


def business_sql(statement):
    return sql("business-db", "local_business", "business", "business_local_only", statement)


def agent_sql(statement):
    return sql("agent-db", "workflow_integration", "workflow", "integration_agent_local_only", statement)


def token(subject, role):
    status, body = request(AGENT, "/api/v1/auth/dev-token", method="POST", body={"subject": subject, "role": role})
    need("issue " + role + " development credential", status == 200)
    return body["data"]["token"]  # Never put credentials into the evidence report.


def task(task_id, auth):
    status, body = request(AGENT, "/api/v1/tasks/" + task_id, token=auth)
    if status != 200:
        raise AssertionError(body)
    return body["data"]


def create(text, auth, label):
    # Use urllib here to pass the standard task idempotency header.
    req = urllib.request.Request(AGENT + "/api/v1/tasks", data=json.dumps({"request": text}, ensure_ascii=False).encode(),
        headers={"Authorization": "Bearer " + auth, "Content-Type": "application/json", "Idempotency-Key": RUN + "-" + label}, method="POST")
    with urllib.request.urlopen(req, timeout=20) as response:
        result = json.load(response)["data"]
    TASKS[label] = result["id"]
    return result


def approve(task_id, manager):
    return request(AGENT, f"/api/v1/tasks/{task_id}/approve", method="POST", token=manager,
                   body={"reason": "Synthetic integration acceptance " + RUN})


def command(task_id, auth):
    status, body = request(AGENT, "/api/v1/integrations/commands?taskId=" + task_id, token=auth)
    if status != 200 or len(body["data"]) != 1:
        raise AssertionError(body)
    return body["data"][0]


def detail(command_id, auth):
    return request(AGENT, "/api/v1/integrations/commands/" + command_id, token=auth)[1]["data"]


def run(fault_drill):
    wait_for(lambda: request(BUSINESS, "/health"), lambda r: r[0] == 200, timeout=180)
    wait_for(lambda: request(AGENT, "/api/v1/health/readiness"), lambda r: r[0] == 200, timeout=180)
    need("50 synthetic orders are persisted in independent business MySQL", business_sql("select count(*) from orders where synthetic=1") == "50")
    need("agent has no seeded local order replicas", agent_sql("select count(*) from orders") == "0")
    need("business API rejects unauthenticated access", request(BUSINESS, "/business/v1/orders/ORD-3001")[0] == 401)
    need("agent service credential cannot operate provider ledger", request(BUSINESS, "/business/v1/operations/refunds/none/settle", method="POST", key=SERVICE_KEY,
        body={"status": "SUCCEEDED", "reason": "must be denied"})[0] == 401)
    alice = token("integration-alice-" + RUN, "CUSTOMER_SERVICE")
    manager = token("integration-manager-" + RUN, "SUPERVISOR")
    mallory = token("integration-outsider-" + RUN, "CUSTOMER_SERVICE")

    before = request(BUSINESS, "/business/v1/orders/ORD-3001", key=SERVICE_KEY)[1]
    read = create("查询订单 ORD-3001", alice, "read")
    data = read["steps"][0]["resultData"]
    need("read-only workflow uses HTTP business data", read["status"] == "SUCCEEDED" and data.get("source") == "http-business" and data.get("amount") == before.get("totalAmount"), read)
    reread = task(read["id"], alice)
    need("structured query results survive JDBC reload", reread["steps"][0]["resultData"]["items"] == data["items"])

    normal = create("为订单 ORD-3001 退款 1.25 元", alice, "normal")
    need("refund waits for approval", normal["status"] == "WAITING_APPROVAL")
    need("unapproved task has no external command", agent_sql(f"select count(*) from integration_commands where task_id='{normal['id']}'") == "0")
    need("customer service cannot approve", approve(normal["id"], alice)[0] == 403)
    status, accepted = approve(normal["id"], manager)
    need("approval reports WAITING_EXTERNAL instead of premature success", status == 200 and accepted["data"]["status"] == "WAITING_EXTERNAL")
    settled = wait_for(lambda: task(normal["id"], alice), lambda t: t["status"] == "SUCCEEDED")
    c = command(normal["id"], alice)
    need("normal refund command and task settle together", c["status"] == "ACKNOWLEDGED" and settled["sideEffectCount"] == 1)
    need("other customer service cannot inspect integration evidence", request(AGENT, "/api/v1/integrations/commands/" + c["id"], token=mallory)[0] == 403)
    after = request(BUSINESS, "/business/v1/orders/ORD-3001", key=SERVICE_KEY)[1]
    need("refund changes the external business ledger", Decimal(str(after["refundedAmount"])) == Decimal(str(before["refundedAmount"])) + Decimal("1.25"))
    need("duplicate approval is harmless", approve(normal["id"], manager)[0] == 200)
    replay = create("为订单 ORD-3001 退款 1.25 元", alice, "normal")
    need("duplicate task creation replays original task", replay["id"] == normal["id"])
    provider_request = {"idempotencyKey": c["idempotency_key"], "orderId": "ORD-3001", "amount": 1.25}
    with ThreadPoolExecutor(max_workers=4) as pool:
        results = list(pool.map(lambda _: request(BUSINESS, "/business/v1/refunds", method="POST", body=provider_request, key=SERVICE_KEY)[0], range(4)))
    need("concurrent provider retries reuse the same ledger operation", all(code == 202 for code in results) and business_sql(f"select count(*) from refunds where idempotency_key='{c['idempotency_key']}'") == "1")
    provider_request["amount"] = 2.50
    need("provider rejects reused key with changed amount", request(BUSINESS, "/business/v1/refunds", method="POST", body=provider_request, key=SERVICE_KEY)[0] == 409)

    timeout = create("为订单 ORD-3002 退款 2 元", alice, "timeout")
    approve(timeout["id"], manager)
    wait_for(lambda: task(timeout["id"], alice), lambda t: t["status"] == "SUCCEEDED")
    c = command(timeout["id"], alice)
    events = detail(c["id"], alice)["events"]
    need("accepted-then-timeout is reconciled by polling", any(e["action"] == "UNKNOWN" for e in events) and c["status"] == "ACKNOWLEDGED")
    need("timeout did not duplicate the external refund", business_sql(f"select count(*) from refunds where idempotency_key='{c['idempotency_key']}'") == "1")

    declined = create("为订单 ORD-3003 退款 1.20 元", alice, "decline")
    approve(declined["id"], manager)
    wait_for(lambda: task(declined["id"], alice), lambda t: t["status"] == "FAILED")
    c = command(declined["id"], manager)
    reviews = detail(c["id"], manager)["reviews"]
    need("provider rejection fails the task and creates a review", c["status"] == "FAILED" and len(reviews) == 1)
    need("review queue is restricted to operators", request(AGENT, "/api/v1/integrations/reviews", token=alice)[0] == 403)
    status, acknowledged = request(AGENT, f"/api/v1/integrations/reviews/{reviews[0]['id']}/acknowledge-failure", method="POST", token=manager,
                                   body={"reason": "Checked confirmed provider decline; customer will be contacted"})
    need("confirmed failure can be acknowledged with operator evidence", status == 200 and acknowledged["data"]["reviews"][0]["status"] == "RESOLVED")

    held = create("为订单 ORD-3004 退款 3 元", alice, "hold")
    approve(held["id"], manager)
    wait_for(lambda: task(held["id"], alice), lambda t: t["status"] == "UNKNOWN_SIDE_EFFECT")
    c = command(held["id"], manager)
    review = detail(c["id"], manager)["reviews"][0]
    need("long-pending refund enters bounded manual review", c["status"] == "MANUAL_REVIEW" and review["status"] == "OPEN")
    need("accepted external refund cannot be cancelled", request(AGENT, f"/api/v1/tasks/{held['id']}/cancel", method="POST", token=alice)[0] == 409)
    need("unknown outcome cannot be manually declared failed", request(AGENT, f"/api/v1/integrations/reviews/{review['id']}/acknowledge-failure", method="POST", token=manager,
        body={"reason": "This must require authoritative ledger evidence"})[0] == 409)
    need("ordinary users cannot trigger manual reconciliation", request(AGENT, f"/api/v1/integrations/reviews/{review['id']}/recheck", method="POST", token=alice,
        body={"reason": "Unauthorized reconciliation attempt"})[0] == 403)
    attempts = c["attempts"]
    time.sleep(1.3)
    need("manual-review commands stop automatic polling", command(held["id"], manager)["attempts"] == attempts)
    encoded = urllib.parse.quote(c["idempotency_key"], safe="")
    status, _ = request(BUSINESS, f"/business/v1/operations/refunds/{encoded}/settle", method="POST", key=OPS_KEY, operations=True,
                        body={"status": "SUCCEEDED", "reason": "Synthetic provider operator verified and completed held refund"})
    need("separate provider operator can settle held synthetic ledger entry", status == 200)
    status, checked = request(AGENT, f"/api/v1/integrations/reviews/{review['id']}/recheck", method="POST", token=manager,
                              body={"reason": "Provider confirmed settlement; re-read authoritative receipt"})
    need("manual recheck reconciles only authoritative ledger evidence", status == 200 and checked["data"]["status"] == "ACKNOWLEDGED")
    need("manual reconciliation closes task and review", task(held["id"], alice)["status"] == "SUCCEEDED" and checked["data"]["reviews"][0]["status"] == "RESOLVED")
    need("manual action retains operator and reason", any(e["action"] == "MANUAL_RECHECK" and e["actor"].startswith("integration-manager-") for e in checked["data"]["events"]))

    unpaid = create("为订单 ORD-3005 退款 1 元", alice, "unpaid")
    approve(unpaid["id"], manager)
    wait_for(lambda: task(unpaid["id"], alice), lambda t: t["status"] == "FAILED")
    need("unpaid order is rejected without an external refund row", business_sql("select count(*) from refunds where order_id='ORD-3005'") == "0")

    # Restart only this integration agent and verify durable task/results are preserved.
    compose("stop", "agent-app")
    compose("start", "agent-app")
    wait_for(lambda: request(AGENT, "/api/v1/health/readiness"), lambda r: r[0] == 200, timeout=180)
    need("task and structured result survive agent container restart", task(read["id"], alice)["steps"][0]["resultData"]["orderId"] == "ORD-3001")

    if fault_drill:
        try:
            compose("stop", "business-system")
            need("readiness fails when external business service is down", request(AGENT, "/api/v1/health/readiness")[0] == 503)
            need("direct external read reports service unavailable", request(AGENT, "/api/v1/commerce/orders/ORD-3001", token=alice)[0] == 503)
            status, body = request(AGENT, "/api/v1/tasks", method="POST", token=alice, body={"request": "查询订单 ORD-3001"})
            view = body.get("data", {})
            need("external read outage creates an explicitly failed task without demo fallback", status == 201 and view.get("status") == "FAILED"
                 and view["steps"][0]["resultCode"] == "DOWNSTREAM_UNAVAILABLE" and not view["steps"][0]["resultData"], body)
        finally:
            compose("start", "business-system")
            wait_for(lambda: request(AGENT, "/api/v1/health/readiness"), lambda r: r[0] == 200, timeout=180)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fault-drill", action="store_true")
    parser.add_argument("--host-jvm", action="store_true", help="Use Java services launched by local_business_stack.py; databases still run in Docker")
    parser.add_argument("--start-host", action="store_true", help="Start host JVMs against the already-running Docker databases before verification")
    options = parser.parse_args()
    HOST_JVM = options.host_jvm or options.start_host
    failure = None
    try:
        if options.start_host:
            import local_business_stack as runtime
            runtime.start("business")
            runtime.start("agent")
        run(options.fault_drill)
    except Exception as error:
        failure = str(error)
        print("ERROR " + failure, flush=True)
    finally:
        directory = ROOT / "target" / "business-integration"
        directory.mkdir(parents=True, exist_ok=True)
        report = {"generatedAt": datetime.now(timezone.utc).isoformat(), "runId": RUN, "synthetic": True,
                  "agentUrl": AGENT, "businessUrl": BUSINESS, "taskIds": TASKS,
                  "checks": CHECKS, "passed": sum(c["passed"] for c in CHECKS), "total": len(CHECKS), "error": failure}
        (directory / "acceptance-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"Evidence: {directory / 'acceptance-report.json'}", flush=True)
    raise SystemExit(1 if failure else 0)
