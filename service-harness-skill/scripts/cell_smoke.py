"""Isolated real Cell + two Python tool Runners; local search and page fixtures, scripted model."""
import argparse
import hashlib
import http.server
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
HARNESS = ROOT.parent / "service-harness"


def main():
    parser = argparse.ArgumentParser(__doc__)
    for name in ("java", "python-root", "runtime", "evidence"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    root, evidence = args.runtime.resolve(), args.evidence.resolve()
    root.mkdir(parents=True, exist_ok=False)
    evidence.mkdir(parents=True, exist_ok=False)
    cp = os.pathsep.join(map(str, [ROOT.parent / "deploy/bin/cell.jar", ROOT.parent / "build/cube-common-3.0.jar",
                                  ROOT.parent / "deploy/libs/*", HARNESS / "build/cube-service-harness-3.0.jar",
                                  HARNESS / "build/test-classes", ROOT / "build/cube-service-harness-skill-3.0.jar",
                                  ROOT / "build/test-classes"]))
    requests = []

    class Search(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            requests.append(self.path)
            time.sleep(0.15)
            body = json.dumps(dict(results=[dict(title="Fixture modeling source", url="https://fixture.invalid/modeling",
                                                 content="Fixture search evidence 中文", engine="fixture")]), ensure_ascii=False).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_):
            pass

    upstream = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Search)
    threading.Thread(target=upstream.serve_forever, daemon=True).start()
    data = root / "data"
    subprocess.run([str(args.java), "-cp", cp, "cube.service.harness.test.CommonToolsTest", "prepare", str(data),
                    str(ROOT / "build/packages"), "http://127.0.0.1:" + str(upstream.server_port) + "/search"], check=True, timeout=60)
    fixture = json.loads((data / "common.fixture.json").read_text(encoding="utf-8"))
    sys.path.insert(0, str(args.python_root.resolve()))
    from harness import lifecycle
    sys.path.insert(0, str(HARNESS / "scripts"))
    from dispatcher_fixture import DispatcherFixture

    def port():
        with socket.socket() as s:
            s.bind(("127.0.0.1", 0))
            return s.getsockname()[1]

    cell_port, http_port = port(), port()
    api, credential, trace_token = secrets.token_hex(24), secrets.token_hex(24), secrets.token_hex(24)
    stage = root / "cell"
    (stage / "cellets").mkdir(parents=True)
    shutil.copy2(HARNESS / "build/cube-service-harness-3.0.jar", stage / "cellets")
    xml = ET.parse(HARNESS / "config/standalone.xml")
    xml.find(".//listeners/class").text = "cube.service.harness.test.FixtureAuthCarpet"
    xml.find(".//server/port").text = str(cell_port)
    xml.find(".//cellet").set("port", str(cell_port))
    xml.write(stage / "cell.xml", encoding="utf-8", xml_declaration=True)
    config = stage / "harness.properties"
    config.write_text((data / "common.properties").read_text(encoding="utf-8") + f"""
http.host=127.0.0.1
http.port={http_port}
lease.millis=3000
worker.credential={credential}
fixture.user.token={api}
fixture.user.domain={fixture['tenant']}
fixture.user.contact={fixture['subject']}
trace.enabled=true
trace.reader.subjects={fixture['tenant']}/10003
fixture.trace.token={trace_token}
fixture.trace.domain={fixture['tenant']}
fixture.trace.contact=10003
store.enabled=true
execution.enabled=true
execution.tools.enabled=true
execution.permissions={','.join(fixture['permissions'])}
execution.tenant.templates={HARNESS.as_posix()}/config/harness-tenant
backend.shared.capacity=1
worker.model.role=model
worker.model.model=scripted
worker.model.backend=shared
worker.a.role=runner
worker.a.capacity=1
worker.b.role=runner
worker.b.capacity=1
files.enabled=true
""", encoding="utf-8")
    gateway = None

    def api_call(path, body=None, binary=False, method=None, trace=False):
        headers = {"Authorization": "Bearer " + (trace_token if trace else api), "Accept": "application/json"}
        if body is not None:
            headers.update({"Content-Type": "application/octet-stream" if binary else "application/json",
                            "Idempotency-Key": secrets.token_hex(16)})
        url = gateway.url(path, http_port) if gateway else "http://127.0.0.1:" + str(http_port) + path
        request = urllib.request.Request(url,
                                         data=body if binary else None if body is None else json.dumps(body).encode(),
                                         headers=headers, method=method)
        with urllib.request.urlopen(request, timeout=5) as response:
            return json.loads(response.read())

    def wait(fn, timeout=60):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            try:
                value = fn()
                if value:
                    return value
            except (urllib.error.URLError, ConnectionError, TimeoutError):
                pass
            time.sleep(0.1)
        raise AssertionError("condition timed out")

    def db(sql, *values):
        with sqlite3.connect(data / "harness_execution.db", timeout=10) as connection:
            return connection.execute(sql, values).fetchall()

    workers, checks, runs = [], [], []
    process = None
    log_path = root / "cell.log"
    log = log_path.open("w", encoding="utf-8")

    def worker(name, script=None):
        path = root / (name + ".json")
        value = dict(host="127.0.0.1", port=cell_port, workerId=name, role="model" if script else "runner",
                     workerCredential=credential, runtimeDir=str(root / ("worker-" + name)), requestTimeout=3, startupTimeout=30)
        if script:
            value["model"] = dict(model="scripted", backendId="shared", backendCapacity=1, script=script)
        else:
            value["runner"] = dict(type="tool", runtime="trusted-python-v1", capacity=1)
        path.write_text(json.dumps(value), encoding="utf-8")
        workers.append(path)
        lifecycle.start(path)

    def completed(run):
        value = api_call("/harness/v1/runs/" + run)
        state = value["snapshot"]["state"]
        if state in ("failed", "cancelled", "timed_out", "waiting_input", "waiting_approval"):
            raise AssertionError("run failed: " + json.dumps(value, ensure_ascii=False))
        return value if state == "succeeded" else None

    try:
        process = subprocess.Popen([str(args.java), "-Dharness.config=" + str(config), "-cp", cp,
                                    "cube.service.harness.HarnessCell", str(stage / "cell.xml"), str(stage / "stop")],
                                   cwd=stage, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                   **({"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}))
        wait(lambda: api_call("/healthz"), 30)
        gateway = DispatcherFixture(args.java, cp, cell_port, api, root)
        gateway.ready()
        worker("a")
        worker("b")
        content = "中文附件 bound through real Cell".encode()
        digest = hashlib.sha256(content).hexdigest()
        input_id = api_call("/harness/v1/inputs", dict(fileName="fixture.txt", mediaType="text/plain", size=len(content), sha256=digest))["inputId"]
        api_call("/harness/v1/inputs/" + input_id + "/content", content, binary=True, method="PUT")
        api_call("/harness/v1/inputs/" + input_id + "/complete", dict(sha256=digest))
        calls = [("common.search.query", dict(query="主流3D建模软件")),
                 ("common.web.read", dict(url="https://fixture.invalid/modeling")),
                 ("common.knowledge.search", dict(query="Harness")),
                 ("common.attachment.read", dict(inputId=input_id)),
                 ("common.time.query", dict(action="convert", instant="2026-10-25T00:00:00Z")),
                 ("common.calculator.evaluate", dict(expression="0.1+0.2"))]
        decisions = [dict(kind="tool_calls", calls=[dict(proposalId="tool" + str(i), tool=name,
                                                          argumentsJson=json.dumps(arguments, ensure_ascii=False))
                                                     for i, (name, arguments) in enumerate(calls)]),
                     dict(kind="final", text="Fixture evidence returned: https://fixture.invalid/modeling")]
        worker("model", decisions)

        def create():
            run = api_call("/harness/v1/runs", dict(input=dict(instruction="全网搜索主流的3D建模软件；验证六项工具", attachmentIds=[input_id]),
                                               skills=["common/" + name for name in fixture["releases"]]))["runId"]
            runs.append(run)
            return run

        initial = [create() for _ in range(4)]
        for run in initial:
            wait(lambda: completed(run))
        assert {r[0] for r in db("SELECT DISTINCT worker_id FROM harness_tool_job")} == {"a", "b"}
        checks.append("six actual JAR providers, real dispatcher/Cell/SHM, two independent tool Runners, Chinese SDK output")
        lifecycle.stop(root / "a.json")
        # Simulate a lost ACK in this stopped, disposable Runner's journal only.
        # Preserve its identity and original result; restart must resend, never execute again.
        journal = root / "worker-a/runner-journal.sqlite"
        with sqlite3.connect(journal, timeout=10) as connection:
            replay = next(row for row in connection.execute(
                "SELECT job_id,job_json,report_json FROM calls WHERE state='finished' AND acked=1")
                if json.loads(row[1])["tool"] == "common.search.query")
            connection.execute("UPDATE calls SET acked=0 WHERE job_id=?", (replay[0],))
        replay_ledger = db("SELECT result_json,report_hash FROM harness_tool_job WHERE job_id=?", replay[0])
        run = create()
        wait(lambda: completed(run))
        assert {r[0] for r in db("SELECT worker_id FROM harness_tool_job WHERE run_id=?", run)} == {"b"}
        before_replay = len(requests)
        lifecycle.start(root / "a.json")

        def reacknowledged():
            with sqlite3.connect(journal, timeout=10) as connection:
                return connection.execute("SELECT acked FROM calls WHERE job_id=?", (replay[0],)).fetchone()[0] == 1

        wait(reacknowledged)
        with sqlite3.connect(journal, timeout=10) as connection:
            assert connection.execute("SELECT report_json FROM calls WHERE job_id=?", (replay[0],)).fetchone()[0] == replay[2]
        assert db("SELECT result_json,report_hash FROM harness_tool_job WHERE job_id=?", replay[0]) == replay_ledger
        assert len(requests) == before_replay
        ack_replay = dict(jobId=replay[0], originalReportSha256=hashlib.sha256(replay[2].encode()).hexdigest(),
                          reacknowledged=True, ledgerUnchanged=True, upstreamRequestsUnchanged=True,
                          simulation="reset acked=0 in stopped fixture Runner journal; original report preserved")
        checks.append("lost ACK simulation: original report resent through real SHM, re-ACKed, identical ledger, no search re-execution")
        lifecycle.stop(root / "b.json")
        run = create()
        wait(lambda: completed(run))
        assert {r[0] for r in db("SELECT worker_id FROM harness_tool_job WHERE run_id=?", run)} == {"a"}
        checks.append("one JVM, independent worker stop/restart and continued dispatch")
        results = [json.loads(r[0]) for r in db("SELECT result_json FROM harness_tool_job")]
        assert len(results) == len(runs) * 6
        assert all(r["status"] == "success" for r in results)
        assert not any("SECRET" in json.dumps(r) for r in results)
        assert len(requests) == len(runs)
        tools = {r[0] for r in db("SELECT tool_name FROM harness_tool_job")}
        assert tools == {name for name, _ in calls}
        checks.append("persisted provider evidence, knowledge ACL, bound HTTP upload, no duplicate search requests")
        from search_smoke import verify
        trace = api_call("/harness/v1/runs/" + run + "/trace", trace=True)
        rows = [dict(run_id=r[0], intent_id=r[1], attempt=r[2], result_json=r[3]) for r in db(
            "SELECT run_id,intent_id,attempt,result_json FROM harness_tool_call WHERE run_id=?", run)]
        verified = verify(completed(run), trace, rows)
        assert verified["checks"]["realModel"] is False
        assert all(value for name, value in verified["checks"].items() if name != "realModel"), verified
        checks.append("redacted Trace commitments match actual ledger search results and cited URL; scripted model rejected for online acceptance")
        (evidence / "trace.json").write_text(json.dumps(trace, ensure_ascii=False, indent=2), encoding="utf-8")
        (evidence / "results.json").write_text(json.dumps(dict(result="PASS", checks=checks, runs=runs,
                                                             upstreamRequests=len(requests), toolResults=results,
                                                             ackReplay=ack_replay,
                                                             model="scripted", search="local HTTP fixture",
                                                             webpage="test-classpath-only transport fixture",
                                                             realSearXNG="NOT_RUN"), ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(dict(result="PASS", checks=checks), ensure_ascii=False), flush=True)
    finally:
        for path in reversed(workers):
            try:
                lifecycle.stop(path)
            except (OSError, ValueError, RuntimeError):
                pass
        if gateway:
            gateway.close()
        if process:
            (stage / "stop").touch()
            try:
                process.wait(timeout=25)
            except subprocess.TimeoutExpired:
                process.terminate()
                process.wait(timeout=10)
        log.close()
        upstream.shutdown()
        upstream.server_close()
        text = log_path.read_text(encoding="utf-8", errors="replace")
        for secret in (api, credential, trace_token):
            text = text.replace(secret, "[REDACTED]")
        (evidence / "cell.log").write_text(text, encoding="utf-8")


if __name__ == "__main__":
    main()
