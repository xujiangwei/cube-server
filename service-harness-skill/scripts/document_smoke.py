"""Real dispatcher/Cell/SHM PDF watermark verification with isolated Python environments and multiple workers."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import sqlite3
import subprocess
import sys
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

MODULE = Path(__file__).resolve().parents[1]
SERVER = MODULE.parent
HARNESS = SERVER / "service-harness"


def main():
    parser = argparse.ArgumentParser(__doc__)
    for name in ("java", "python-root", "pdf-python", "runtime", "evidence"):
        parser.add_argument("--" + name, required=True, type=Path)
    args = parser.parse_args()
    version = subprocess.run([str(args.java), "-version"], capture_output=True, check=True)
    if b'"1.8.' not in version.stderr:
        raise ValueError("Actual JDK 8 required")
    root, evidence = args.runtime.resolve(), args.evidence.resolve()
    root.mkdir(parents=True, exist_ok=False)
    evidence.mkdir(parents=True, exist_ok=False)
    cp = os.pathsep.join(map(str, [SERVER / "deploy/bin/cell.jar", SERVER / "build/cube-common-3.0.jar",
        SERVER / "deploy/libs/*", HARNESS / "build/cube-service-harness-3.0.jar", HARNESS / "build/test-classes",
        MODULE / "build/cube-service-harness-skill-3.0.jar", MODULE / "build/test-classes"]))
    python_root = args.python_root.resolve()
    spec = python_root / "harness/environments/pdf-watermark-v1/environment.json"
    subprocess.run([str(args.java), "-Dfile.encoding=UTF-8", "-cp", cp,
        "cube.service.harness.test.DocumentToolsTest", "prepare", str(root / "data"), str(MODULE / "build/packages"), str(spec)],
        check=True, timeout=60)
    fixture = json.loads((root / "data/document.fixture.json").read_bytes())
    sys.path.insert(0, str(python_root))
    from harness import lifecycle
    from harness.file_environment import FileEnvironment
    environment = FileEnvironment(dict(python=str(args.pdf_python.resolve()), manifest=str(spec)))
    sys.path.insert(0, str(HARNESS / "scripts"))
    from dispatcher_fixture import DispatcherFixture

    def port():
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            return sock.getsockname()[1]
    cell_port, http_port = port(), port()
    token, credential = secrets.token_hex(24), secrets.token_hex(24)
    stage = root / "cell"
    (stage / "cellets").mkdir(parents=True)
    shutil.copy2(HARNESS / "build/cube-service-harness-3.0.jar", stage / "cellets")
    xml = ET.parse(HARNESS / "config/standalone.xml")
    xml.find(".//listeners/class").text = "cube.service.harness.test.FixtureAuthCarpet"
    xml.find(".//server/port").text = str(cell_port)
    xml.find(".//cellet").set("port", str(cell_port))
    xml.write(stage / "cell.xml", encoding="utf-8", xml_declaration=True)
    properties = (root / "data/document.properties.server").read_text(encoding="utf-8") + f"""
http.host=127.0.0.1
http.port={http_port}
lease.millis=3000
worker.credential={credential}
fixture.user.token={token}
fixture.user.domain={fixture['tenant']}
fixture.user.contact={fixture['subject']}
store.enabled=true
execution.enabled=true
execution.tools.enabled=true
execution.permissions={','.join(fixture['permissions'])}
execution.tenant.templates={HARNESS.as_posix()}/config/harness-tenant
backend.shared.capacity=1
worker.model.role=model
worker.model.model=scripted
worker.model.backend=shared
worker.plain.role=runner
worker.plain.capacity=1
files.enabled=true
"""
    for name in ("pdf-a", "pdf-b"):
        properties += f"\nworker.{name}.role=runner\nworker.{name}.capacity=1\nworker.{name}.file.environment.{fixture['environmentId']}={fixture['environmentDigest']}\n"
    config = stage / "harness.properties"
    config.write_text(properties, encoding="utf-8")
    gateway, process, workers = None, None, []
    log = (root / "cell.log").open("wb")

    def api(path, body=None, method=None, raw=False, download=False):
        url = gateway.url(path, http_port) if gateway else "http://127.0.0.1:" + str(http_port) + path
        headers = {"Authorization": "Bearer " + token, "Idempotency-Key": secrets.token_hex(16)}
        if body is not None:
            headers["Content-Type"] = "application/octet-stream" if raw else "application/json"
        request = urllib.request.Request(url, headers=headers, method=method,
            data=body if raw else None if body is None else json.dumps(body, ensure_ascii=False).encode())
        with urllib.request.urlopen(request, timeout=10) as response:
            content = response.read()
            return content if download else json.loads(content)

    def wait(predicate, timeout=60):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            try:
                result = predicate()
                if result:
                    return result
            except (urllib.error.URLError, TimeoutError, ConnectionError):
                pass
            time.sleep(.1)
        raise AssertionError("condition timed out")

    def worker(name, script=None, pdf=False):
        value = dict(host="127.0.0.1", port=cell_port, workerId=name, role="model" if script else "runner",
                     workerCredential=credential, runtimeDir=str(root / ("worker-" + name)), requestTimeout=3, startupTimeout=30)
        if script:
            value["model"] = dict(model="scripted", backendId="shared", backendCapacity=1, script=script)
        else:
            value["runner"] = dict(type="tool", runtime="trusted-python-v1", capacity=1, providerAwait=pdf)
            if pdf:
                value["runner"]["fileEnvironments"] = [dict(python=str(args.pdf_python.resolve()), manifest=str(spec))]
        path = root / (name + ".json")
        path.write_text(json.dumps(value), encoding="utf-8")
        workers.append(path)
        return lifecycle.start(path)

    def db(sql, *values):
        with sqlite3.connect(root / "data/harness_execution.db", timeout=10) as connection:
            return connection.execute(sql, values).fetchall()

    def completed(run):
        result = api("/harness/v1/runs/" + run)
        state = result["snapshot"]["state"]
        if state in ("failed", "cancelled", "timed_out", "waiting_input", "waiting_approval"):
            raise AssertionError(json.dumps(result, ensure_ascii=False))
        return result if state == "succeeded" else None

    try:
        process = subprocess.Popen([str(args.java), "-Dfile.encoding=UTF-8", "-Dharness.config=" + str(config), "-cp", cp,
            "cube.service.harness.HarnessCell", str(stage / "cell.xml"), str(stage / "stop")],
            cwd=stage, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
            **({"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}))
        wait(lambda: api("/healthz"), 30)
        gateway = DispatcherFixture(args.java, cp, cell_port, token, root)
        gateway.ready()
        worker("plain")
        def document_status():
            return next(t for t in api("/harness/v1/task-profiles")["tools"] if t["name"] == "common.document.watermark")
        plain_status = document_status()
        assert not plain_status["ready"] and plain_status["capacity"] == 0, plain_status
        worker("pdf-a", pdf=True)
        worker("pdf-b", pdf=True)
        wait(lambda: document_status()["ready"] and document_status()["capacity"] == 2)
        source = root / "source.pdf"
        generator = """from reportlab.pdfgen.canvas import Canvas
from pypdf import PdfReader,PdfWriter
import io,sys
b=io.BytesIO();c=Canvas(b,pagesize=(595,842));c.setFont('Helvetica',18);c.drawString(60,750,'Original document - page 1');c.showPage();c.drawString(60,750,'Original document - page 2');c.save();r=PdfReader(io.BytesIO(b.getvalue()));w=PdfWriter(clone_from=r);w.pages[1].rotate(90);w.write(sys.argv[1])
"""
        subprocess.run([str(args.pdf_python), "-I", "-B", "-c", generator, str(source)], check=True, capture_output=True)
        data = source.read_bytes()
        sha = hashlib.sha256(data).hexdigest()
        input_id = api("/harness/v1/inputs", dict(fileName="source.pdf", mediaType="application/pdf", size=len(data), sha256=sha))["inputId"]
        api("/harness/v1/inputs/" + input_id + "/content", data, method="PUT", raw=True)
        api("/harness/v1/inputs/" + input_id + "/complete", dict(sha256=sha))
        script = [dict(kind="tool_calls", calls=[dict(proposalId="watermark", tool="common.document.watermark",
                   argumentsJson=json.dumps(dict(inputId=input_id, text="内部资料", pages=[2]), ensure_ascii=False))]),
                  dict(kind="final", text="PDF watermark artifact is ready.")]
        worker("model", script)
        runs = [api("/harness/v1/runs", dict(input=dict(instruction="请给第二页添加内部资料水印", attachmentIds=[input_id]),
                                            skills=["common/document"]))["runId"] for _ in range(4)]
        for run in runs:
            wait(lambda: completed(run))
        assigned = {r[0] for r in db("SELECT DISTINCT worker_id FROM harness_tool_job")}
        assert assigned == {"pdf-a", "pdf-b"}, assigned
        assert db("SELECT COUNT(*) FROM harness_tool_job WHERE worker_id='plain'") == [(0,)]
        for run in runs:
            artifacts = api("/harness/v1/runs/" + run + "/artifacts")["artifacts"]
            assert len(artifacts) == 1, artifacts
            metadata = artifacts[0]
            body = api("/harness/v1/artifacts/" + metadata["artifactId"] + "/content", download=True)
            assert hashlib.sha256(body).hexdigest() == metadata["sha256"]
            output = evidence / "watermarked.pdf"
            output.write_bytes(body)
            inspect = "from pypdf import PdfReader;import sys,json;r=PdfReader(sys.argv[1]);print(json.dumps([p.extract_text() for p in r.pages]))"
            text = json.loads(subprocess.run([str(args.pdf_python), "-I", "-B", "-c", inspect, str(output)], check=True, capture_output=True).stdout)
            assert "内部资料" not in text[0] and "内部资料" in text[1]
            assert "Original document" in text[0] and "Original document" in text[1]
        assert hashlib.sha256(source.read_bytes()).hexdigest() == sha
        # Restart with an unacknowledged original report: resend evidence without processing a second PDF.
        lifecycle.stop(root / "pdf-a.json")
        journal = root / "worker-pdf-a/runner-journal.sqlite"
        with sqlite3.connect(journal) as connection:
            job = connection.execute("SELECT job_id,report_json FROM calls WHERE state='finished' AND acked=1 LIMIT 1").fetchone()
            connection.execute("UPDATE calls SET acked=0 WHERE job_id=?", (job[0],))
        count = db("SELECT COUNT(*) FROM harness_file WHERE kind='artifact'")
        lifecycle.start(root / "pdf-a.json")
        def acknowledged():
            with sqlite3.connect(journal) as connection:
                return connection.execute("SELECT acked,report_json FROM calls WHERE job_id=?", (job[0],)).fetchone() == (1, job[1])
        wait(acknowledged)
        assert db("SELECT COUNT(*) FROM harness_file WHERE kind='artifact'") == count
        result = dict(result="PASS", runs=len(runs), workers=sorted(assigned), environment=environment.descriptor(),
                      checks=["real dispatcher/Cell/SHM binary upload and download", "two PDF workers and excluded ordinary runner",
                              "Chinese watermark on selected rotated page with original text preserved", "one artifact per call and original input unchanged",
                              "lost ACK report replay without PDF re-execution"], liveModels=0)
        (evidence / "results.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(result, ensure_ascii=False), flush=True)
    finally:
        for path in reversed(workers):
            try:
                lifecycle.stop(path)
            except Exception:
                pass
        if gateway:
            gateway.close()
        if process and process.poll() is None:
            (stage / "stop").touch()
            try:
                process.wait(timeout=20)
            finally:
                if process.poll() is None:
                    process.terminate()
                    process.wait(timeout=10)
        log.close()


if __name__ == "__main__":
    main()
