"""Real Jetty -> Performer -> Cell -> HarnessCellet regression; isolated SQLite/files and no model hardware."""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import sqlite3
import subprocess
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from dispatcher_fixture import DispatcherFixture


def main():
    parser = argparse.ArgumentParser(__doc__)
    for name in ("java", "runtime", "libs", "cell-jar", "common-jar"):
        parser.add_argument("--" + name, required=True, type=Path)
    parser.add_argument("--max-file-bytes", type=int, default=1048576)
    args = parser.parse_args()
    root = args.runtime.resolve()
    root.mkdir(parents=True, exist_ok=False)
    module = Path(__file__).resolve().parents[1]
    (root / "data").mkdir()
    cp = os.pathsep.join(map(str, (args.cell_jar.resolve(), args.common_jar.resolve(), args.libs.resolve() / "*",
                                   module / "build/cube-service-harness-3.0.jar", module / "build/test-classes")))
    subprocess.run([str(args.java), "-Xmx1024m", "-cp", cp, "cube.service.harness.test.HarnessTransportFixture",
                    str(root / "data"), str(args.max_file_bytes)], check=True, timeout=120)
    fixture = json.loads((root / "data/fixture.json").read_bytes())
    def port():
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            return sock.getsockname()[1]
    cell_port, service_port = port(), port()
    api, approver, trace, worker, admin = (secrets.token_hex(24) for _ in range(5))
    stage = root / "cell"
    (stage / "cellets").mkdir(parents=True)
    shutil.copy2(module / "build/cube-service-harness-3.0.jar", stage / "cellets")
    xml = ET.parse(module / "config/standalone.xml")
    xml.find(".//server/port").text = str(cell_port)
    xml.find(".//cellet").set("port", str(cell_port))
    xml.write(stage / "cell.xml", encoding="utf-8", xml_declaration=True)
    config = stage / "harness.local.properties"
    config.write_text((root / "data/store.local.properties").read_text() + f"""
mode=development
http.port={service_port}
lease.millis=3000
api.credential={api}
worker.credential={worker}
control.approver.credential={approver}
control.approver.subject=independent-reviewer
trace.reader.credential={trace}
admission.admin.credential={admin}
store.enabled=true
execution.enabled=true
execution.max.active=100
execution.tenant={fixture['tenant']}
execution.subject={fixture['subject']}
execution.repository.namespace=runtime-test-repo
""", encoding="utf-8")
    gateway = None
    process = None
    log = None
    checks = []
    def record(name):
        checks.append(name)
        print("PASS:", name, flush=True)
    def wait(fn, timeout=25):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            try:
                value = fn()
                if value:
                    return value
            except (OSError, urllib.error.URLError):
                pass
            time.sleep(.1)
        raise AssertionError("Condition timed out")
    def request(path, body=None, method=None, token=None, headers=None, expected=200, direct=False):
        token = api if token is None else token
        h = {"Authorization": "Bearer " + token}
        h.update(headers or {})
        if isinstance(body, dict):
            h.setdefault("Content-Type", "application/json")
            body = json.dumps(body, ensure_ascii=False).encode()
        if body is not None:
            h.setdefault("Idempotency-Key", secrets.token_hex(16))
        url = "http://127.0.0.1:" + str(service_port) + path if direct else gateway.url(path, service_port)
        req = urllib.request.Request(url, data=body, headers=h, method=method)
        try:
            response = urllib.request.urlopen(req, timeout=60)
        except urllib.error.HTTPError as exc:
            response = exc
        with response:
            data = response.read()
            assert response.status == expected, (path, expected, response.status, data[:500])
            if response.headers.get_content_type() == "application/json":
                return json.loads(data)
            return data
    def launch():
        nonlocal process, log
        (stage / "stop").unlink(missing_ok=True)
        log = (root / ("cell-" + str(time.time_ns()) + ".log")).open("wb")
        process = subprocess.Popen([str(args.java), "-Xmx512m", "-Dharness.config=" + str(config), "-cp", cp,
                                    "cube.service.harness.HarnessCell", str(stage / "cell.xml"), str(stage / "stop")],
                                   cwd=stage, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                                   **({"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}))
        wait(lambda: request("/healthz", direct=True))
    def stop():
        nonlocal process
        (stage / "stop").touch()
        assert process.wait(timeout=20) == 0
        log.close()
        process = None
    def no_pins():
        with sqlite3.connect(root / "data/harness_execution.db", timeout=5) as db:
            return db.execute("SELECT COUNT(*) FROM harness_file_pin WHERE kind IN ('download','upload')").fetchone()[0] == 0
    def upload(data, sha=None):
        sha = sha or hashlib.sha256(data).hexdigest()
        metadata = dict(fileName="测试.bin", mediaType="application/octet-stream", size=len(data), sha256=sha)
        value = request("/harness/v1/inputs", metadata, expected=201)
        base = "/harness/v1/inputs/" + value["inputId"]
        result = request(base + "/content", data, method="PUT", headers={"Content-Type": "application/octet-stream"}, expected=200 if sha == hashlib.sha256(data).hexdigest() else 400)
        if "errorCode" not in result:
            request(base + "/complete", dict(sha256=sha))
            assert request(base)["state"] == "ready"
        return result
    try:
        launch()
        gateway = DispatcherFixture(args.java, cp, cell_port, api, root)
        gateway.ready()
        request("/harness/v1/capabilities", direct=True, expected=404)
        request("/harness/v1/runs", {}, direct=True, expected=404)
        request("/harness-admin/v1/admission", token=admin, direct=True)
        request("/harness-admin/v1/admission", token=admin, expected=200)  # health/admin URL stays on service
        request("/harness/v1/capabilities", token="invalid-credential-123456789", expected=401)
        request("/harness/v1/capabilities", token=api)  # Original Bearer is authenticated by service.
        request("/harness/v1/capabilities", token=worker, expected=401)
        request("/harness/v1/capabilities", token=admin, expected=401)
        request("/harness/v1/capabilities", headers={"Authorization": ""}, expected=401)
        profiles = request("/harness/v1/task-profiles")["profiles"]
        assert profiles
        request("/harness/v1/task-profiles/" + profiles[0]["profileId"])
        request("/harness/v1/task-profiles/missing", expected=404)
        request("/harness/v1/capabilities", {}, expected=405)
        request("/harness/v1/unknown", expected=404)
        record("business-http-closed-health-admin-retained-and-full-rest-routing")

        create = dict(taskProfileId="task", input=dict(instruction="SHM transport", attachmentIds=[]))
        key = secrets.token_hex(16)
        first = request("/harness/v1/runs", create, expected=202, headers={"Idempotency-Key": key})
        assert request("/harness/v1/runs", create, expected=202, headers={"Idempotency-Key": key}) == first
        request("/harness/v1/runs", dict(create, taskProfileId="one-round"), expected=409, headers={"Idempotency-Key": key})
        for raw in (b'{"taskProfileId":"task","taskProfileId":"other"}', b'{"x":"\xff"}'):
            request("/harness/v1/runs", raw, headers={"Content-Type": "application/json"}, expected=400)
        request("/harness/v1/runs", b"x" * 262145, headers={"Content-Type": "application/json"}, expected=413)
        ids = [fixture["slowRun"], fixture["artifactRun"], first["runId"]] * 8
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as executor:
            rows = list(executor.map(lambda run: request("/harness/v1/runs/" + run), ids))
        assert all(row["snapshot"]["runId"] == run for row, run in zip(rows, ids))
        record("real-cell-concurrent-correlated-replies-raw-json-and-durable-idempotency")

        events = "/harness/v1/runs/" + fixture["artifactRun"] + "/events"
        request(events + "?after=999999", expected=400)
        request(events + "?after=0&after=1", expected=400)
        request(events + "?limit=101", expected=400)
        request(events + "?after=0", headers={"Last-Event-ID": "1"}, expected=400)
        page = request(events)
        replay = request(events, headers={"Accept": "text/event-stream"})
        assert replay.startswith(b"retry: 500\n\n") and replay.endswith(b": replay-page-complete\n\n")
        assert len(page["events"]) == replay.count(b"\nevent: ")
        record("event-json-and-finite-sse-replay-with-cursor-validation")

        run = fixture["inputRun"]
        row = request("/harness/v1/runs/" + run)
        request("/harness/v1/runs/" + run + "/resume", dict(expectedVersion=row["snapshot"]["version"], waitId=fixture["inputWait"]["waitId"], input=dict(instruction="Continue", attachmentIds=[])), expected=202)
        run = fixture["approvalRun"]
        row = request("/harness/v1/runs/" + run, token=approver)
        decision = dict(expectedVersion=row["snapshot"]["version"], decision="approve", argumentsHash=fixture["approvalWait"]["argumentsHash"])
        path = "/harness/v1/runs/" + run + "/approvals/" + fixture["approvalWait"]["waitId"] + "/decision"
        request(path, decision, expected=403)
        request(path, decision, token=approver, expected=202)
        request("/harness/v1/task-profiles", token=approver, expected=403)
        run = fixture["artifactRun"]
        request("/harness/v1/runs/" + run + "/feedback", dict(rating="positive", reason="transport fixture"), expected=202)
        request("/harness/v1/runs/" + run + "/trace", expected=403)
        request("/harness/v1/runs/" + run + "/trace", token=trace)
        request("/harness/v1/runs/" + run, token=trace, expected=403)
        cancel = first["runId"]
        for _ in range(5):
            row = request("/harness/v1/runs/" + cancel)
            try:
                request("/harness/v1/runs/" + cancel + "/cancel", dict(expectedVersion=row["snapshot"]["version"]), expected=202)
                break
            except AssertionError as exc:
                if "409" not in str(exc):
                    raise
        else:
            raise AssertionError("cancel never accepted")
        record("resume-cancel-approval-feedback-trace-and-independent-authority")

        upload(b"")
        upload("中文\n".encode())
        upload(b"x" * args.max_file_bytes)
        assert upload(b"broken", "0" * 64)["errorCode"] == "FILE_DIGEST_MISMATCH"
        for name in ("artifact", "emptyArtifact"):
            artifact = fixture[name]
            path = "/harness/v1/artifacts/" + artifact["artifactId"]
            assert request(path)["sha256"] == artifact["sha256"]
            body = request(path + "/content")
            assert len(body) == artifact["size"] and hashlib.sha256(body).hexdigest() == artifact["sha256"]
            handle = request(path + "/download-handle")
            assert hashlib.sha256(request(handle["url"])).hexdigest() == artifact["sha256"]
            request(path + "/content?handle=invalid", expected=400)
        assert len(request("/harness/v1/runs/" + fixture["artifactRun"] + "/artifacts")["artifacts"]) == 2
        wait(no_pins)
        record("shm-stream-empty-unicode-maximum-file-digest-download-handle-and-pin-release")

        # A client that closes mid-upload must not leave an upload pin or exhaust admission.
        payload = b"y" * max(65536, args.max_file_bytes)
        value = request("/harness/v1/inputs", dict(fileName="abort.bin", mediaType="application/octet-stream", size=len(payload), sha256=hashlib.sha256(payload).hexdigest()), expected=201)
        sock = socket.create_connection(("127.0.0.1", gateway.port))
        sock.sendall(("PUT /harness/v1/inputs/" + value["inputId"] + "/content HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " + api + "\r\nIdempotency-Key: aborted-upload\r\nContent-Type: application/octet-stream\r\nContent-Length: " + str(len(payload)) + "\r\n\r\n").encode() + payload[:100])
        time.sleep(.2)
        sock.close()
        wait(no_pins)
        request("/harness/v1/capabilities")
        stop()
        launch()
        gateway.ready(35)
        request("/harness/v1/runs/" + fixture["artifactRun"])
        wait(no_pins)
        record("client-interruption-and-service-restart-reuse-dispatcher-without-http-fallback")
        session_cp = os.pathsep.join((str(module.parent / "build/cube-dispatcher-3.0.jar"),
                                     str(module / "build/transport-test-classes"), cp))
        subprocess.run([str(args.java), "-cp", session_cp, "cube.service.harness.test.HarnessSessionTest",
                        str(config), str(cell_port), fixture["emptyArtifact"]["artifactId"]], check=True, timeout=40)
        wait(no_pins)
        record("real-cell-cross-session-controls-and-stream-isolation-with-disconnect-cleanup")
    finally:
        if gateway is not None:
            gateway.close()
        if process is not None:
            (stage / "stop").touch()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.terminate(); process.wait(timeout=10)
        if log is not None:
            log.close()
        (root / "results.json").write_text(json.dumps(dict(checks=checks, maxFileBytes=args.max_file_bytes), indent=2) + "\n")


if __name__ == "__main__":
    main()
