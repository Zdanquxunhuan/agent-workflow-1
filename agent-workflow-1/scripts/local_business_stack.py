#!/usr/bin/env python3
"""Host Java 17 fallback with Docker MySQL when a JRE image cannot be downloaded.

Start/stop only processes recorded by this launcher. Docker volumes are retained.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import signal
import socket
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
STATE = ROOT / "target" / "business-integration" / "runtime"
SPECS = {
    "business": (8091, ROOT / "business-system" / "target" / "local-business-system-0.0.1-SNAPSHOT.jar"),
    "agent": (18090, ROOT / "target" / "agent-workflow-1-0.0.1-SNAPSHOT.jar"),
}


def java():
    candidates = [Path(os.environ.get("JAVA_HOME", "")) / "bin" / "java.exe", Path("F:/ayd/jdk-17/bin/java.exe")]
    if shutil.which("java"):
        candidates.append(Path(shutil.which("java")))
    for path in candidates:
        if path.is_file():
            probe = subprocess.run([str(path), "-version"], capture_output=True, text=True)
            match = re.search(r'version "(\d+)', probe.stderr + probe.stdout)
            if match and int(match.group(1)) >= 17:
                return path
    raise RuntimeError("Java 17+ not found; set JAVA_HOME")


def ready(service):
    port, _ = SPECS[service]
    path = "/health" if service == "business" else "/api/v1/health/readiness"
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}{path}", timeout=4) as response:
            return response.status == 200
    except (urllib.error.URLError, TimeoutError):
        return False


def start(service):
    port, jar = SPECS[service]
    STATE.mkdir(parents=True, exist_ok=True)
    if not jar.is_file():
        raise RuntimeError(f"Build this jar first: {jar}")
    with socket.socket() as probe:
        if probe.connect_ex(("127.0.0.1", port)) == 0:
            if (STATE / f"{service}.json").is_file() and ready(service):
                return
            raise RuntimeError(f"Port {port} is already in use; will not replace its process")
    env = os.environ.copy()
    for key in list(env):
        if key.startswith(("AGENT_", "MYSQL_", "COMMERCE_", "DEEPSEEK_", "BUSINESS_", "AUDIT_")):
            env.pop(key)
    env.pop("SPRING_PROFILES_ACTIVE", None)
    env.pop("JAVA_TOOL_OPTIONS", None)
    if service == "business":
        env.update({"BUSINESS_PORT": "8091", "BUSINESS_DB_URL": "jdbc:mysql://127.0.0.1:3308/local_business?connectionTimeZone=UTC&useSSL=false&allowPublicKeyRetrieval=true",
                    "BUSINESS_API_KEY": "local-business-service-key-change-before-production",
                    "BUSINESS_OPERATIONS_KEY": "local-business-operations-key-separate-from-agent", "BUSINESS_SEED": "true"})
    else:
        env.update({"SERVER_PORT": "18090", "AGENT_PERSISTENCE": "jdbc",
                    "MYSQL_URL": "jdbc:mysql://127.0.0.1:3310/workflow_integration?connectionTimeZone=UTC&useSSL=false&allowPublicKeyRetrieval=true",
                    "MYSQL_USER": "workflow", "MYSQL_PASSWORD": "integration_agent_local_only",
                    "COMMERCE_GATEWAY": "http", "COMMERCE_HTTP_BASE_URL": "http://127.0.0.1:8091",
                    "COMMERCE_HTTP_API_KEY": "local-business-service-key-change-before-production", "COMMERCE_HTTP_WRITES_ENABLED": "true",
                    "COMMERCE_HTTP_TIMEOUT_SECONDS": "2", "COMMERCE_DISPATCHER_FIXED_DELAY_MS": "500",
                    "COMMERCE_DISPATCHER_RETRY_DELAY_SECONDS": "1", "COMMERCE_DISPATCHER_MAX_ATTEMPTS": "6",
                    "AGENT_AUTH_LOCAL_SECRET": "integration-local-jwt-secret-never-for-production", "AGENT_AUTH_DEV_TOKEN_ENABLED": "true"})
    flags = subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
    with (STATE / f"{service}.log").open("ab") as log:
        process = subprocess.Popen([str(java()), "-Xmx256m", "-jar", str(jar)], cwd=ROOT, env=env,
                                   stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                   creationflags=flags, start_new_session=os.name != "nt")
    (STATE / f"{service}.json").write_text(json.dumps({"pid": process.pid, "jar": str(jar.resolve()), "port": port}), encoding="utf-8")
    end = time.monotonic() + 240
    while time.monotonic() < end:
        if ready(service):
            print(f"{service} ready on 127.0.0.1:{port} (PID {process.pid})", flush=True)
            return
        if process.poll() is not None:
            raise RuntimeError(f"{service} exited; see {STATE / (service + '.log')}")
        time.sleep(0.5)
    raise RuntimeError(f"{service} startup timed out; see runtime log")


def stop(service):
    file = STATE / f"{service}.json"
    if not file.exists():
        return
    state = json.loads(file.read_text(encoding="utf-8"))
    expected = str(SPECS[service][1].resolve())
    if state.get("jar") != expected:
        raise RuntimeError("Recorded jar is outside this launcher's expected target")
    pid = int(state["pid"])
    if os.name == "nt":
        # Verify the live command line before using a persisted PID (PID reuse must not kill another process).
        quoted = expected.replace("'", "''")
        script = f"$p = Get-CimInstance Win32_Process -Filter 'ProcessId={pid}'; if ($null -ne $p) {{ if ($p.Name -ne 'java.exe' -or -not $p.CommandLine.Contains('{quoted}')) {{ throw 'PID no longer belongs to the expected Java service' }}; Stop-Process -Id {pid} -ErrorAction Stop }}"
        result = subprocess.run(["powershell", "-NoProfile", "-Command", script], capture_output=True, text=True)
        if result.returncode:
            raise RuntimeError(result.stderr)
    else:
        try:
            command = Path(f"/proc/{pid}/cmdline").read_bytes().decode().replace("\x00", " ")
            if expected not in command:
                raise RuntimeError("PID no longer belongs to the expected Java service")
            os.kill(pid, signal.SIGTERM)
        except FileNotFoundError:
            pass
    end = time.monotonic() + 20
    while time.monotonic() < end:
        with socket.socket() as probe:
            if probe.connect_ex(("127.0.0.1", SPECS[service][0])) != 0:
                return
        time.sleep(0.2)
    raise RuntimeError(f"{service} did not stop")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["start", "stop", "status"])
    args = parser.parse_args()
    if args.action == "start":
        subprocess.run(["docker", "compose", "-p", "workflow-business", "-f", "compose.business.yaml", "up", "-d", "--wait", "agent-db", "business-db"], cwd=ROOT, check=True)
        start("business")
        start("agent")
    elif args.action == "stop":
        stop("agent")
        stop("business")
    else:
        print(json.dumps({service: {"ready": ready(service), "port": spec[0]} for service, spec in SPECS.items()}, indent=2))
