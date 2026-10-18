"""Real Cell + three Python v3 workers + same-JVM AIGCService/registered Unit fixtures.

The Unit transport simulates hardware only. No live model, credentials or weights are used.
"""
import argparse
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
import urllib.request
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(__doc__)
    for name in ("java", "python-root", "runtime", "libs", "cell-jar", "service-classes"):
        parser.add_argument("--" + name, type=Path, required=True)
    args = parser.parse_args()
    module = Path(__file__).resolve().parents[1]
    root = args.runtime.resolve()
    root.mkdir(parents=True, exist_ok=False)
    cp = os.pathsep.join(map(str, (args.cell_jar.resolve(), module.parent / "build/cube-common-3.0.jar",
        args.libs.resolve() / "*", args.service_classes.resolve(), module / "build/cube-service-harness-3.0.jar",
        module / "build/test-classes")))
    subprocess.run([str(args.java), "-cp", cp, "cube.service.harness.test.InferenceTest", "prepare", str(root / "data")], check=True)
    fixture = json.loads((root / "data/fixture.json").read_text())
    sys.path.insert(0, str(args.python_root.resolve()))
    from harness import lifecycle

    def port():
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            return sock.getsockname()[1]

    cell_port, http_port = port(), port()
    credential, api = secrets.token_hex(24), secrets.token_hex(24)
    stage = root / "cell"
    (stage / "cellets").mkdir(parents=True)
    shutil.copy2(module / "build/cube-service-harness-3.0.jar", stage / "cellets")
    xml = ET.parse(module / "config/standalone.xml")
    xml.find(".//listeners/class").text = "cube.service.test.HarnessInferenceCarpet"
    xml.find(".//server/port").text = str(cell_port)
    xml.find(".//cellet").set("port", str(cell_port))
    xml.write(stage / "cell.xml", encoding="utf-8", xml_declaration=True)
    workers = [("a", "Baize"), ("b", "Baize2"), ("c", "Baize2")]
    config = stage / "harness.local.properties"
    config.write_text((root / "data/store.local.properties").read_text() + f"""
mode=development
http.host=127.0.0.1
http.port={http_port}
lease.millis=1000
worker.credential={credential}
api.credential={api}
store.enabled=true
execution.enabled=true
execution.tenant={fixture['tenant']}
execution.subject={fixture['subject']}
execution.repository={root.as_posix()}/data/repository
execution.repository.namespace=runtime-test-repo
backend.shared.capacity=2
backend.shared.token=token
inference.node.id=offline-smoke
""" + "".join(f"worker.{w}.role=model\nworker.{w}.model={model}\nworker.{w}.backend=shared\n" for w, model in workers))
    configs = []
    for worker, model in workers:
        path = root / (worker + ".json")
        path.write_text(json.dumps(dict(host="127.0.0.1", port=cell_port, workerId=worker, role="model",
            workerCredential=credential, runtimeDir=str(root / ("worker-" + worker)), requestTimeout=4,
            startupTimeout=20, model=dict(model=model, nativeTools=model == "Baize2", maxWireBytes=131072))))
        configs.append(path)

    def http(path, body=None):
        headers = dict(Authorization="Bearer " + api)
        if body is not None:
            headers.update({"Content-Type": "application/json", "Idempotency-Key": secrets.token_hex(16)})
        request = urllib.request.Request("http://127.0.0.1:" + str(http_port) + path,
            data=None if body is None else json.dumps(body).encode(), headers=headers)
        with urllib.request.urlopen(request, timeout=5) as response:
            return json.load(response)

    def wait(condition):
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            try:
                if condition():
                    return
            except (OSError, TimeoutError):
                pass
            time.sleep(.1)
        raise AssertionError("Inference smoke timed out")

    hidden = {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}
    with (root / "cell.log").open("w") as log:
        host = subprocess.Popen([str(args.java), "-Dharness.config=" + str(config), "-cp", cp,
            "cube.service.harness.HarnessCell", str(stage / "cell.xml"), str(stage / "stop")],
            cwd=stage, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, **hidden)
        try:
            wait(lambda: http("/healthz"))
            for path in configs:
                lifecycle.start(path)
            runs = []
            for model in ("Baize", "Baize2", "Baize2", "Baize", "Baize2", "Baize2"):
                runs.append(http("/harness/v1/runs", dict(taskProfileId="inference-" + model,
                    input=dict(instruction="Return a final answer", attachmentIds=[])))["runId"])
            wait(lambda: all(http("/harness/v1/runs/" + run)["snapshot"]["state"] == "succeeded" for run in runs))
            caps = http("/harness/v1/capabilities")
            assert len(caps["registeredWorkers"]) == 3 and all(w["ready"] for w in caps["registeredWorkers"]), caps
            with sqlite3.connect(root / "data/harness_execution.db") as db:
                assert db.execute("SELECT COUNT(*) FROM harness_inference WHERE actual_finished=1").fetchone()[0] == 6
                assert db.execute("SELECT COUNT(DISTINCT worker_id) FROM harness_model_job WHERE execution_protocol=3").fetchone()[0] == 3
            report = dict(test="Cell v3 -> same-JVM AIGCService -> registered Baize/Baize2 Unit fixture", runs=6,
                pythonWorkers=3, physicalCapacity=2, realModel=False, heartbeat="lease retained during 2.2s inference with 1s lease")
            (root / "report.json").write_text(json.dumps(report, indent=2))
            print("PASS:", json.dumps(report), flush=True)
        finally:
            for path in configs:
                try:
                    lifecycle.stop(path)
                except Exception:
                    pass
            (stage / "stop").touch()
            host.wait(timeout=20)


if __name__ == "__main__":
    main()
