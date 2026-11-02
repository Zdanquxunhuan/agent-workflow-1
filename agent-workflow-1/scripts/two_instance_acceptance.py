#!/usr/bin/env python3
"""Two-instance acceptance checks against a real MySQL database.

Starts two application JVMs against one isolated schema and verifies:
  * a token issued by one instance is accepted by the other;
  * concurrent idempotent task creation produces one task row;
  * concurrent approvals produce exactly one side effect and one outbox command;
  * approve-vs-cancel always ends in SUCCEEDED-with-one-write or CANCELLED-with-none;
  * outbox commands are settled exactly once across both dispatchers;
  * starting a third instance does not mark a live task as interrupted;
  * the prod profile refuses unsafe defaults.

The script recreates only the dedicated verification schema. It never touches
the demo database `workflow_agent`.
"""

from __future__ import annotations

import argparse
import atexit
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "target" / "agent-workflow-1-0.0.1-SNAPSHOT.jar"
EVIDENCE = ROOT / "target" / "two-instance"

MYSQL_CONTAINER = os.environ.get("MYSQL_CONTAINER", "agent-workflow-1-mysql-1")
MYSQL_ROOT_PASSWORD = os.environ.get("MYSQL_ROOT_PASSWORD", "root_dev_only")
VERIFY_DB = "workflow_agent_verify"
APP_USER = "workflow"
APP_PASSWORD = "workflow_dev_only"
MYSQL_URL = (
    f"jdbc:mysql://127.0.0.1:3307/{VERIFY_DB}"
    "?useUnicode=true&characterEncoding=utf8&connectionTimeZone=UTC"
    "&useSSL=false&allowPublicKeyRetrieval=true"
)
LOCAL_SECRET = "two-instance-verification-local-secret-change-me"
TOKEN_ISSUER = "agent-workflow-verify"
TOKEN_AUDIENCE = "agent-workflow-verify-api"
STALE_SECONDS = 60

PORT_A = 18081
PORT_B = 18082
PORT_C = 18083
PORT_GUARD = 18084

RESULTS: list[dict] = []
PROCESSES: list[dict] = []


class AcceptanceFailure(AssertionError):
    pass


def check(name: str, ok: bool, detail: str = "") -> bool:
    RESULTS.append({"name": name, "passed": bool(ok), "detail": detail})
    line = ("PASS " if ok else "FAIL ") + name
    if detail:
        line += " :: " + detail
    print(line, flush=True)
    return ok


def need(name: str, ok: bool, detail: str = "") -> None:
    if not check(name, ok, detail):
        raise AcceptanceFailure(f"{name}: {detail}")


def tail(path: Path, lines: int = 60) -> str:
    try:
        content = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return "<log unavailable>"
    return "\n".join(content[-lines:])


def resolve_java() -> tuple[str, str]:
    exe = "java.exe" if os.name == "nt" else "java"
    candidates: list[Path] = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]) / "bin" / exe)
    candidates.extend([
        Path("F:/ayd/jdk-17") / "bin" / exe,
        Path("C:/Program Files/Eclipse Adoptium") / exe,
    ])
    on_path = shutil.which("java")
    if on_path:
        candidates.append(Path(on_path))
    for candidate in candidates:
        if not candidate.is_file():
            continue
        probe = subprocess.run([str(candidate), "-version"], capture_output=True, text=True)
        match = re.search(r'version "(\d+)', probe.stderr + probe.stdout)
        if match and int(match.group(1)) >= 17:
            return str(candidate), str(candidate.parent.parent)
    raise AcceptanceFailure("Java 17+ was not found; set JAVA_HOME or install a JDK 17")


def build_jar() -> None:
    env = os.environ.copy()
    env["JAVA_HOME"] = JAVA_HOME
    if os.name == "nt":
        command = ["cmd.exe", "/c", str(ROOT / "mvnw.cmd"), "-q", "-DskipTests", "package"]
    else:
        command = [str(ROOT / "mvnw"), "-q", "-DskipTests", "package"]
    print("Building application jar ...", flush=True)
    completed = subprocess.run(command, cwd=ROOT, env=env, capture_output=True,
                               text=True, errors="replace", timeout=600)
    if completed.returncode != 0:
        print(completed.stdout[-8000:], flush=True)
        print(completed.stderr[-8000:], flush=True)
        raise AcceptanceFailure(f"Maven package failed with exit code {completed.returncode}")
    if not JAR.is_file():
        raise AcceptanceFailure(f"Expected jar was not produced: {JAR}")


def mysql(sql: str, *, db: str | None = VERIFY_DB, root: bool = False) -> list[list[str]]:
    user = "root" if root else APP_USER
    password = MYSQL_ROOT_PASSWORD if root else APP_PASSWORD
    command = [
        "docker", "exec", MYSQL_CONTAINER, "mysql",
        f"-u{user}", f"-p{password}", "-N", "-B", "--default-character-set=utf8mb4",
    ]
    if db:
        command.extend(["-D", db])
    command.extend(["-e", sql])
    completed = subprocess.run(command, capture_output=True, text=True, errors="replace")
    if completed.returncode != 0:
        raise AcceptanceFailure(f"MySQL command failed: {completed.stderr.strip() or completed.stdout.strip()}")
    return [line.split("\t") for line in completed.stdout.splitlines() if line]


def sql_value(sql: str) -> str | None:
    rows = mysql(sql)
    return rows[0][0] if rows and rows[0] else None


def sql_list(sql: str, column: int = 0) -> list[str]:
    return [row[column] for row in mysql(sql) if len(row) > column]


def recreate_database() -> None:
    print(f"Recreating verification schema {VERIFY_DB} ...", flush=True)
    mysql(
        f"drop database if exists `{VERIFY_DB}`; "
        f"create database `{VERIFY_DB}` character set utf8mb4 collate utf8mb4_unicode_ci;",
        db=None, root=True,
    )
    mysql(
        f"grant all privileges on `{VERIFY_DB}`.* to '{APP_USER}'@'%'; flush privileges;",
        db=None, root=True,
    )


def port_in_use(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as probe:
        probe.settimeout(0.3)
        return probe.connect_ex(("127.0.0.1", port)) == 0


def clean_environment() -> dict[str, str]:
    env = os.environ.copy()
    for key in list(env):
        if key.startswith(("AGENT_", "MYSQL_", "COMMERCE_", "DEEPSEEK_", "AUDIT_")):
            env.pop(key, None)
    env.pop("SPRING_PROFILES_ACTIVE", None)
    env["JAVA_HOME"] = JAVA_HOME
    return env


def start_instance(name: str, port: int, *, seed: bool, dispatcher: bool) -> dict:
    if port_in_use(port):
        raise AcceptanceFailure(f"Port {port} is already in use; stop the process before running acceptance")
    env = clean_environment()
    env.update({
        "SERVER_PORT": str(port),
        "AGENT_PERSISTENCE": "jdbc",
        "MYSQL_URL": MYSQL_URL,
        "MYSQL_USER": APP_USER,
        "MYSQL_PASSWORD": APP_PASSWORD,
        "MYSQL_POOL_MAX_SIZE": "6",
        "MYSQL_POOL_MIN_IDLE": "1",
        "AGENT_AUTH_MODE": "local",
        "AGENT_AUTH_ISSUER": TOKEN_ISSUER,
        "AGENT_AUTH_AUDIENCE": TOKEN_AUDIENCE,
        "AGENT_AUTH_LOCAL_SECRET": LOCAL_SECRET,
        "AGENT_AUTH_DEV_TOKEN_ENABLED": "true",
        "AGENT_RECOVERY_STALE_SECONDS": str(STALE_SECONDS),
        "COMMERCE_SEED_DEMO_DATA": "true" if seed else "false",
        "COMMERCE_DISPATCHER_ENABLED": "true" if dispatcher else "false",
        "COMMERCE_DISPATCHER_FIXED_DELAY_MS": "500",
        "COMMERCE_DISPATCHER_LEASE_SECONDS": "10",
        "COMMERCE_DISPATCHER_RETRY_DELAY_SECONDS": "2",
        "COMMERCE_DISPATCHER_MAX_ATTEMPTS": "3",
        "AUDIT_OUTBOX_PUBLISHER_ENABLED": "false",
        "DEEPSEEK_ENABLED": "false",
    })
    log_path = EVIDENCE / f"{name}.log"
    log = log_path.open("w", encoding="utf-8")
    creation_flags = subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
    proc = subprocess.Popen(
        [JAVA, "-jar", str(JAR)],
        cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
        creationflags=creation_flags,
    )
    instance = {"name": name, "port": port, "proc": proc, "log": log_path, "handle": log}
    PROCESSES.append(instance)
    print(f"Started {name} on port {port} (pid={proc.pid}, log={log_path})", flush=True)
    return instance


def stop_instance(instance: dict) -> None:
    proc = instance["proc"]
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(timeout=20)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(timeout=10)
    try:
        instance["handle"].close()
    except Exception:
        pass
    print(f"Stopped {instance['name']}", flush=True)


def stop_all() -> None:
    for instance in list(PROCESSES):
        try:
            stop_instance(instance)
        except Exception:
            pass


atexit.register(stop_all)


def http_call(port: int, method: str, path: str, *, token: str | None = None,
              body: dict | None = None, headers: dict | None = None,
              timeout: int = 30) -> tuple[int, dict | None]:
    payload = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=payload, method=method)
    request.add_header("Accept", "application/json")
    if payload is not None:
        request.add_header("Content-Type", "application/json")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    for key, value in (headers or {}).items():
        request.add_header(key, value)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            raw = response.read().decode("utf-8")
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as exc:
        raw = exc.read().decode("utf-8")
        try:
            parsed = json.loads(raw) if raw else None
        except json.JSONDecodeError:
            parsed = {"raw": raw}
        return exc.code, parsed


def data_of(payload: dict | None) -> dict:
    return (payload or {}).get("data") or {}


def wait_ready(instance: dict, timeout: float = 180) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if instance["proc"].poll() is not None:
            raise AcceptanceFailure(
                f"{instance['name']} exited with {instance['proc'].returncode}\n{tail(instance['log'])}"
            )
        try:
            status, _ = http_call(instance["port"], "GET", "/api/v1/health/readiness", timeout=3)
            if status == 200:
                print(f"{instance['name']} is ready", flush=True)
                return
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(0.4)
    raise AcceptanceFailure(f"{instance['name']} did not become ready\n{tail(instance['log'])}")


def dev_token(port: int, subject: str, role: str) -> str:
    status, body = http_call(port, "POST", "/api/v1/auth/dev-token",
                             body={"subject": subject, "role": role})
    need(f"dev token issued for {subject}/{role}", status == 200, f"HTTP {status}; token omitted from evidence")
    return data_of(body)["token"]


def create_task(port: int, token: str, request_text: str, key: str) -> tuple[int, dict | None]:
    return http_call(port, "POST", "/api/v1/tasks", token=token,
                     body={"request": request_text}, headers={"Idempotency-Key": key})


def wait_task(port: int, token: str, task_id: str, statuses: set[str], timeout: float = 30) -> dict:
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        status, body = http_call(port, "GET", f"/api/v1/tasks/{task_id}", token=token)
        if status == 200:
            view = data_of(body)
            last = view.get("status")
            if last in statuses:
                return view
        time.sleep(0.2)
    raise AcceptanceFailure(f"Task {task_id} did not reach {sorted(statuses)} on port {port}; last={last}")


def poll(label: str, callback, timeout: float = 30, interval: float = 0.25):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = callback()
        if last:
            return last
        time.sleep(interval)
    raise AcceptanceFailure(f"Timed out waiting for {label}; last={last!r}")


def verify_cross_instance_auth(instance_a: dict, instance_b: dict, alice: str, mallory: str) -> str:
    status, body = create_task(instance_a["port"], alice, "查询订单 ORD-1002", "cross-auth-1")
    need("cross-instance create accepted", status == 201, f"HTTP {status}, body={body}")
    task_id = data_of(body)["id"]
    wait_task(instance_b["port"], alice, task_id, {"SUCCEEDED"})

    status, body = http_call(instance_b["port"], "GET", f"/api/v1/tasks/{task_id}", token=alice)
    need("token issued by instance A accepted by instance B", status == 200, f"HTTP {status}, body={body}")

    status, _ = http_call(instance_b["port"], "GET", f"/api/v1/tasks/{task_id}")
    need("unauthenticated request rejected", status == 401, f"HTTP {status}")

    status, body = http_call(
        instance_b["port"], "GET", f"/api/v1/tasks/{task_id}", token=mallory,
        headers={"X-User-Id": "alice-cs", "X-Role": "SUPERVISOR"},
    )
    need("forged identity/role headers ignored", status == 403, f"HTTP {status}, body={body}")
    return task_id


def verify_idempotent_create(instance_a: dict, instance_b: dict, alice: str, count: int = 6) -> list[str]:
    task_ids: list[str] = []
    for index in range(count):
        key = f"race-create-{index}"
        barrier = threading.Barrier(2)

        def issue(port: int):
            barrier.wait(timeout=10)
            return create_task(port, alice, "查询订单 ORD-1002", key)

        with ThreadPoolExecutor(max_workers=2) as pool:
            responses = [
                pool.submit(issue, instance_a["port"]),
                pool.submit(issue, instance_b["port"]),
            ]
            results = [future.result(timeout=60) for future in responses]

        statuses = [result[0] for result in results]
        need(f"idempotent create {key} accepted by both instances",
             statuses == [201, 201], f"statuses={statuses}, bodies={[r[1] for r in results]}")
        ids = {data_of(result[1]).get("id") for result in results}
        need(f"idempotent create {key} returns one task id", len(ids) == 1, f"ids={ids}")
        task_ids.append(ids.pop())

    for task_id in task_ids:
        wait_task(instance_a["port"], alice, task_id, {"SUCCEEDED"})
        wait_task(instance_b["port"], alice, task_id, {"SUCCEEDED"})

    rows = mysql(
        "select idempotency_key, count(*), count(distinct id) from tasks "
        "where user_id = 'alice-cs' and idempotency_key like 'race-create-%' "
        "group by idempotency_key"
    )
    need(f"{count} idempotency keys each have exactly one task row",
         len(rows) == count and all(int(row[1]) == 1 and int(row[2]) == 1 for row in rows),
         f"rows={rows}")
    return task_ids


def wait_refund(task_id: str, timeout: float = 30) -> list[list[str]]:
    def query():
        rows = mysql(f"select id, status, amount from refunds where task_id = '{task_id}'")
        if rows and all(row[1] in {"SUCCEEDED", "FAILED", "UNKNOWN"} for row in rows):
            return rows
        return None

    return poll(f"refund settlement for task {task_id}", query, timeout)


def wait_command(refund_id: str, timeout: float = 30) -> list[list[str]]:
    def query():
        rows = mysql(
            "select id, status, attempts from integration_commands "
            f"where aggregate_id = '{refund_id}' and command_type = 'REFUND'"
        )
        if rows and all(row[1] in {"ACKNOWLEDGED", "FAILED", "UNKNOWN"} for row in rows):
            return rows
        return None

    return poll(f"outbox settlement for refund {refund_id}", query, timeout)


def create_refund_task(instance: dict, token: str, order_id: str, amount: str, key: str) -> str:
    status, body = create_task(instance["port"], token, f"为订单 {order_id} 退款 {amount} 元", key)
    need(f"refund task {key} created", status == 201, f"HTTP {status}, body={body}")
    view = data_of(body)
    need(f"refund task {key} waits for approval", view.get("status") == "WAITING_APPROVAL",
         f"status={view.get('status')}")
    return view["id"]


def verify_concurrent_approval(instance_a: dict, instance_b: dict, alice: str,
                               supervisor_a: str, supervisor_b: str, mallory: str) -> str:
    task_id = create_refund_task(instance_a, alice, "ORD-1003", "2", "approval-race-1")

    status, body = http_call(
        instance_b["port"], "POST", f"/api/v1/tasks/{task_id}/approve", token=mallory,
        body={"reason": "forged"}, headers={"X-User-Id": "supervisor-a", "X-Role": "SUPERVISOR"},
    )
    need("forged role header cannot approve", status == 403, f"HTTP {status}, body={body}")
    wait_task(instance_a["port"], alice, task_id, {"WAITING_APPROVAL"}, timeout=5)

    barrier = threading.Barrier(2)

    def approve(port: int, token: str):
        barrier.wait(timeout=10)
        return http_call(port, "POST", f"/api/v1/tasks/{task_id}/approve", token=token,
                         body={"reason": "two-instance acceptance"})

    with ThreadPoolExecutor(max_workers=2) as pool:
        results = [
            pool.submit(approve, instance_a["port"], supervisor_a),
            pool.submit(approve, instance_b["port"], supervisor_b),
        ]
        responses = [future.result(timeout=60) for future in results]

    statuses = [response[0] for response in responses]
    need("both concurrent approvals return 200", statuses == [200, 200],
         f"statuses={statuses}, bodies={[r[1] for r in responses]}")
    for index, response in enumerate(responses):
        view = data_of(response[1])
        need(f"approval response {index} observes accepted or confirmed execution with one side effect",
             view.get("status") in {"WAITING_EXTERNAL", "SUCCEEDED"} and view.get("sideEffectCount") == 1,
             f"status={view.get('status')}, sideEffectCount={view.get('sideEffectCount')}")

    wait_task(instance_a["port"], alice, task_id, {"SUCCEEDED"})
    refunds = wait_refund(task_id)
    need("exactly one refund row for the task", len(refunds) == 1, f"refunds={refunds}")
    refund = refunds[0]
    need("refund is settled exactly once", refund[1] == "SUCCEEDED" and Decimal(refund[2]) == Decimal("2.00"),
         f"refund={refund}")
    commands = wait_command(refund[0])
    need("exactly one payment command for the refund", len(commands) == 1, f"commands={commands}")
    need("payment command is acknowledged", commands[0][1] == "ACKNOWLEDGED", f"command={commands[0]}")
    side_effect_count = sql_value(f"select side_effect_count from tasks where id = '{task_id}'")
    need("task records one side effect", side_effect_count == "1", f"side_effect_count={side_effect_count}")
    executed = sql_value(
        f"select count(*) from audit_events where task_id = '{task_id}' and event_type = 'TOOL_EXECUTED'"
    )
    need("tool executed once across both instances", executed == "1", f"TOOL_EXECUTED={executed}")
    order = mysql("select status, refunded_amount from orders where id = 'ORD-1003'")[0]
    need("order reflects the single refund", order[0] == "PARTIALLY_REFUNDED" and Decimal(order[1]) == Decimal("2.00"),
         f"order={order}")
    return task_id


def verify_approve_cancel(instance_a: dict, instance_b: dict, alice: str,
                          supervisor_a: str, supervisor_b: str) -> None:
    for index in range(3):
        task_id = create_refund_task(instance_a, alice, "ORD-1002", "1", f"cancel-race-{index}")
        barrier = threading.Barrier(2)

        def approve():
            barrier.wait(timeout=10)
            return http_call(instance_a["port"], "POST", f"/api/v1/tasks/{task_id}/approve",
                             token=supervisor_a, body={"reason": "race"})

        def cancel():
            barrier.wait(timeout=10)
            return http_call(instance_b["port"], "POST", f"/api/v1/tasks/{task_id}/cancel", token=alice)

        with ThreadPoolExecutor(max_workers=2) as pool:
            approve_future = pool.submit(approve)
            cancel_future = pool.submit(cancel)
            approve_response = approve_future.result(timeout=60)
            cancel_response = cancel_future.result(timeout=60)

        approve_status = approve_response[0]
        cancel_status = cancel_response[0]
        need(f"cancel-race {index} cancel returns 200 or rejects an accepted external operation", cancel_status in {200, 409},
             f"HTTP {cancel_status}, body={cancel_response[1]}")
        need(f"cancel-race {index} approve returns 200 or 409", approve_status in {200, 409},
             f"HTTP {approve_status}, body={approve_response[1]}")

        expected = "SUCCEEDED" if approve_status == 200 else "CANCELLED"
        view_a = wait_task(instance_a["port"], alice, task_id, {expected})
        view_b = wait_task(instance_b["port"], alice, task_id, {expected})
        side_effect_count = int(sql_value(f"select side_effect_count from tasks where id = '{task_id}'"))
        refund_count = int(sql_value(f"select count(*) from refunds where task_id = '{task_id}'"))
        print(f"  cancel-race {index}: approve={approve_status}, cancel={cancel_status}, final={expected}", flush=True)

        need(f"cancel-race {index} instances agree on the final state",
             view_a.get("status") == expected and view_b.get("status") == expected,
             f"a={view_a.get('status')}, b={view_b.get('status')}")
        if expected == "SUCCEEDED":
            need(f"cancel-race {index} approve winner has one write",
                 side_effect_count == 1 and refund_count == 1,
                 f"side_effect_count={side_effect_count}, refunds={refund_count}")
            wait_refund(task_id)
        else:
            need(f"cancel-race {index} cancel winner has no write",
                 side_effect_count == 0 and refund_count == 0,
                 f"side_effect_count={side_effect_count}, refunds={refund_count}")

    task_id = create_refund_task(instance_a, alice, "ORD-1004", "1", "cancel-after-approve")
    status, body = http_call(instance_a["port"], "POST", f"/api/v1/tasks/{task_id}/approve",
                             token=supervisor_a, body={"reason": "approved first"})
    need("approved task is accepted for external confirmation", status == 200 and data_of(body).get("status") in {"WAITING_EXTERNAL", "SUCCEEDED"},
         f"HTTP {status}, body={body}")
    wait_task(instance_a["port"], alice, task_id, {"SUCCEEDED"})
    status, body = http_call(instance_b["port"], "POST", f"/api/v1/tasks/{task_id}/cancel", token=alice)
    need("cancel after approval is a no-op", status == 200 and data_of(body).get("status") == "SUCCEEDED"
         and data_of(body).get("sideEffectCount") == 1,
         f"HTTP {status}, body={body}")

    task_id = create_refund_task(instance_a, alice, "ORD-1010", "1", "approve-after-cancel")
    status, body = http_call(instance_a["port"], "POST", f"/api/v1/tasks/{task_id}/cancel", token=alice)
    need("task cancelled before approval", status == 200 and data_of(body).get("status") == "CANCELLED",
         f"HTTP {status}, body={body}")
    status, body = http_call(instance_b["port"], "POST", f"/api/v1/tasks/{task_id}/approve",
                             token=supervisor_b, body={"reason": "too late"})
    need("approval after cancellation is rejected", status == 409,
         f"HTTP {status}, body={body}")
    refund_count = int(sql_value(f"select count(*) from refunds where task_id = '{task_id}'"))
    side_effect_count = int(sql_value(f"select side_effect_count from tasks where id = '{task_id}'"))
    need("rejected approval has no side effect", refund_count == 0 and side_effect_count == 0,
         f"refunds={refund_count}, side_effect_count={side_effect_count}")

    duplicates = sql_value(
        "select count(*) from (select idempotency_key from integration_commands "
        "group by idempotency_key having count(*) > 1) duplicated"
    )
    need("no duplicated outbox idempotency keys", duplicates == "0", f"duplicates={duplicates}")


def verify_recovery_isolation(instance_b: dict, alice: str) -> None:
    unfinished = sql_value("select count(*) from tasks where status in ('CREATED','PLANNING','RUNNING')")
    need("no unfinished tasks before recovery check", unfinished == "0", f"unfinished={unfinished}")

    mysql(
        "insert into tasks (id, user_id, role, request, status, idempotency_key, created_at, updated_at, "
        "result_message, side_effect_count, plan_json, approval_json) values "
        "('verify-live-running','verify-user','CUSTOMER_SERVICE','verification live task','RUNNING',null,"
        "utc_timestamp(6),utc_timestamp(6),null,0,null,null),"
        "('verify-stale-running','verify-user','CUSTOMER_SERVICE','verification stale task','RUNNING',null,"
        "date_sub(utc_timestamp(6), interval 10 minute),"
        "date_sub(utc_timestamp(6), interval 10 minute),null,0,null,null)"
    )

    instance_c = start_instance("instance-c", PORT_C, seed=False, dispatcher=False)
    wait_ready(instance_c)
    poll("stale task recovery",
         lambda: sql_value("select status from tasks where id = 'verify-stale-running'") == "RECOVERY_REQUIRED",
         timeout=30)
    time.sleep(1.5)
    live_status = sql_value("select status from tasks where id = 'verify-live-running'")
    need("live task is not marked as interrupted by another instance", live_status == "RUNNING",
         f"live_status={live_status}")
    stale_events = sql_value(
        "select count(*) from audit_events where task_id = 'verify-stale-running' "
        "and event_type = 'RECOVERY_REQUIRED'"
    )
    live_events = sql_value(
        "select count(*) from audit_events where task_id = 'verify-live-running' "
        "and event_type = 'RECOVERY_REQUIRED'"
    )
    need("stale task has exactly one recovery event", stale_events == "1", f"events={stale_events}")
    need("live task has no recovery event", live_events == "0", f"events={live_events}")
    status, _ = http_call(instance_b["port"], "GET", "/api/v1/health/readiness")
    need("instance B stays ready while instance C starts", status == 200, f"HTTP {status}")
    stop_instance(instance_c)
    PROCESSES.remove(instance_c)


def verify_prod_guard() -> None:
    log_path = EVIDENCE / "prod-guard.log"
    env = clean_environment()
    env["SPRING_PROFILES_ACTIVE"] = "prod"
    env["SERVER_PORT"] = str(PORT_GUARD)
    with log_path.open("w", encoding="utf-8") as log:
        try:
            completed = subprocess.run(
                [JAVA, "-jar", str(JAR)], cwd=ROOT, env=env,
                stdout=log, stderr=subprocess.STDOUT, timeout=120,
            )
            returncode = completed.returncode
        except subprocess.TimeoutExpired:
            returncode = None
    text = log_path.read_text(encoding="utf-8", errors="replace")
    need("prod profile refuses unsafe defaults", returncode not in (0, None),
         f"exit={returncode}, log={log_path}")
    need("prod guard reports the unsafe configuration",
         "Unsafe production configuration" in text,
         f"log tail:\n{tail(log_path)}")
    need("prod guard names persistence and authentication violations",
         "persistence must be jdbc" in text and "auth.mode must be oidc" in text,
         f"log tail:\n{tail(log_path)}")


def write_report(error: str | None) -> Path:
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    report_path = EVIDENCE / "acceptance-report.json"
    report = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "database": VERIFY_DB,
        "jar": str(JAR),
        "checks": RESULTS,
        "passed": error is None and all(item["passed"] for item in RESULTS),
        "error": error,
    }
    report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    return report_path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true", help="Reuse the existing target jar")
    parser.add_argument("--startup-timeout", type=float, default=300,
                        help="Initial database migration/startup allowance in seconds; business race assertions use their own limits")
    args = parser.parse_args()

    global JAVA, JAVA_HOME
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    JAVA, JAVA_HOME = resolve_java()
    print(f"Java: {JAVA}\nJar:  {JAR}\nDatabase: {VERIFY_DB}\n", flush=True)

    error = None
    instance_a = instance_b = None
    try:
        probe = subprocess.run(
            ["docker", "inspect", "-f", "{{.State.Health.Status}}", MYSQL_CONTAINER],
            capture_output=True, text=True, errors="replace",
        )
        need("Docker MySQL container is healthy",
             probe.returncode == 0 and probe.stdout.strip() == "healthy",
             f"stdout={probe.stdout.strip()}, stderr={probe.stderr.strip()}")

        if not args.skip_build:
            build_jar()
        need("application jar exists", JAR.is_file(), str(JAR))

        recreate_database()

        instance_a = start_instance("instance-a", PORT_A, seed=True, dispatcher=True)
        wait_ready(instance_a, timeout=args.startup_timeout)
        instance_b = start_instance("instance-b", PORT_B, seed=False, dispatcher=True)
        wait_ready(instance_b, timeout=args.startup_timeout)

        alice = dev_token(PORT_A, "alice-cs", "CUSTOMER_SERVICE")
        mallory = dev_token(PORT_B, "mallory-cs", "CUSTOMER_SERVICE")
        supervisor_a = dev_token(PORT_A, "supervisor-a", "SUPERVISOR")
        supervisor_b = dev_token(PORT_B, "supervisor-b", "SUPERVISOR")

        verify_cross_instance_auth(instance_a, instance_b, alice, mallory)
        verify_idempotent_create(instance_a, instance_b, alice)
        verify_concurrent_approval(instance_a, instance_b, alice, supervisor_a, supervisor_b, mallory)
        verify_approve_cancel(instance_a, instance_b, alice, supervisor_a, supervisor_b)

        stop_instance(instance_a)
        PROCESSES.remove(instance_a)
        instance_a = None
        status, _ = http_call(instance_b["port"], "GET", "/api/v1/health/readiness")
        need("instance B keeps serving after instance A stops", status == 200, f"HTTP {status}")

        verify_recovery_isolation(instance_b, alice)
        verify_prod_guard()
    except AcceptanceFailure as exc:
        error = str(exc)
        print(f"\nACCEPTANCE FAILED: {error}", flush=True)
        if instance_a:
            print(f"\ninstance-a log:\n{tail(instance_a['log'])}", flush=True)
        if instance_b:
            print(f"\ninstance-b log:\n{tail(instance_b['log'])}", flush=True)
    except Exception as exc:  # noqa: BLE001 - report unexpected failures with context
        error = f"{type(exc).__name__}: {exc}"
        print(f"\nACCEPTANCE ERROR: {error}", flush=True)
    finally:
        stop_all()
        PROCESSES.clear()

    report_path = write_report(error)
    passed = error is None and bool(RESULTS) and all(item["passed"] for item in RESULTS)
    print(f"\n{'ACCEPTANCE PASSED' if passed else 'ACCEPTANCE FAILED'} "
          f"({sum(1 for item in RESULTS if item['passed'])}/{len(RESULTS)} checks)", flush=True)
    print(f"Report: {report_path}", flush=True)
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
