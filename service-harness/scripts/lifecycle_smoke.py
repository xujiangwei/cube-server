"""Real XML/Cell lifecycle regression; no production endpoints are used.

Run after `ant test`. Every subprocess and configuration belongs to --runtime.
Credentials stay there; only sanitized assertions and logs go to --evidence.
"""
import argparse
import json
import os
from pathlib import Path
import queue
import secrets
import shutil
import socket
import statistics
import subprocess
import sys
import time
import urllib.error
import urllib.request
from unittest.mock import patch
import xml.etree.ElementTree as ET
from dispatcher_fixture import DispatcherFixture


def free_port(bind_host="127.0.0.1"):
    with socket.socket() as sock:
        sock.bind((bind_host, 0))
        return sock.getsockname()[1]


def wait(predicate, timeout=15):
    deadline = time.monotonic()+timeout
    while time.monotonic() < deadline:
        result = predicate()
        if result:
            return result
        time.sleep(.1)
    raise AssertionError("Condition timed out")


def main():
    parser = argparse.ArgumentParser()
    for name in ("java", "common-jar", "cell-jar", "libs", "python-root", "runtime", "evidence"):
        parser.add_argument("--"+name, required=True)
    parser.add_argument("--cell-bind-host", default="127.0.0.1",
                        help="Address to bind the isolated local Java Cell (e.g. 0.0.0.0)")
    parser.add_argument("--cell-host", default="127.0.0.1",
                        help="Client address of that Cell (e.g. this test machine's LAN IPv4)")
    args = parser.parse_args()
    module = Path(__file__).resolve().parent.parent
    runtime, evidence = Path(args.runtime).resolve(), Path(args.evidence).resolve()
    runtime.mkdir(parents=True, exist_ok=True)
    evidence.mkdir(parents=True, exist_ok=True)
    sys.path.insert(0, str(Path(args.python_root).resolve()))
    from harness.service import HarnessService, HarnessSpeaker
    from harness.protocol import ROOT, encode, parse, verify_contracts
    from cell.core import LiteralBase, Primitive, Stuff
    from cell.talk import TalkListener
    from harness import lifecycle

    results, clients, configs = [], [], []
    host = None
    gateway = None
    host_log = None
    token, api_token = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    cell_port, http_port = free_port(args.cell_bind_host), free_port()
    def record(name, data=True):
        results.append(dict(name=name, result="PASS", evidence=data))
        print("PASS", name, flush=True)
    def check(value, message):
        if not value:
            raise AssertionError(message)
    def http(path, method="GET", value=None, authorized=True, headers=None):
        h = {"Content-Type": "application/json"}
        if authorized:
            h["Authorization"] = "Bearer " + api_token
        h.update(headers or {})
        request = urllib.request.Request(gateway.url(path, http_port) if gateway else "http://127.0.0.1:"+str(http_port)+path,
                                        data=encode(value) if value is not None else None, method=method, headers=h)
        try:
            response = urllib.request.urlopen(request, timeout=3)
        except urllib.error.HTTPError as exc:
            response = exc
        with response:
            return response.status, json.loads(response.read())
    def healthy():
        try:
            return http("/healthz")[0] == 200
        except OSError:
            return False
    def client(worker="model-1", role="model", credential=None):
        path = runtime/("client-"+str(len(clients))+".json")
        path.write_bytes(encode(dict(host=args.cell_host, port=cell_port, workerId=worker, role=role,
                                    workerCredential=credential or token, requestTimeout=3,
                                    runtimeDir=str(runtime/("client-"+str(len(clients)))))))
        c = HarnessService(lifecycle.load_config(path))
        clients.append(c)
        return c
    def expect_error(callback, code):
        try:
            callback()
        except RuntimeError as exc:
            check(str(exc) == code, "Expected "+code+", got "+str(exc))
        else:
            raise AssertionError("Expected error: "+code)
    def launch(cohost):
        nonlocal host, host_log, gateway
        stage = runtime/("cohost" if cohost else "standalone")
        (stage/"cellets").mkdir(parents=True, exist_ok=True)
        shutil.copy2(module/"build/cube-service-harness-3.0.jar", stage/"cellets")
        xml = ET.parse(module/"config/standalone.xml")
        xml.find(".//listeners/class").text = "cube.service.harness.test.FixtureAuthCarpet"
        xml.find(".//server/host").text = args.cell_bind_host
        xml.find(".//server/port").text = str(cell_port)
        xml.find(".//log/level").text = "DEBUG"
        xml.find(".//cellet").set("port", str(cell_port))
        if cohost:
            cellet = ET.SubElement(xml.find("cellets"), "cellet", {"port": str(cell_port)})
            ET.SubElement(cellet, "class").text = "cube.service.harness.test.SentinelCellet"
        xml.write(stage/"cell.xml", encoding="utf-8", xml_declaration=True)
        (stage/"config").mkdir(exist_ok=True)
        config = stage/"config/harness.properties"
        config.write_text("\n".join(["http.host=127.0.0.1", "http.port="+str(http_port), "lease.millis=3000", "worker.credential="+token, "fixture.user.token="+api_token, "worker.model-1.role=model", "worker.model-2.role=model", "worker.runner-1.role=runner"]), encoding="utf-8")
        stop = stage/"stop"
        stop.unlink(missing_ok=True)
        cp = os.pathsep.join([str(Path(args.cell_jar).resolve()), str(Path(args.common_jar).resolve()), str(Path(args.libs).resolve()/"*"),
                              str(stage/"cellets/cube-service-harness-3.0.jar"), str(module/"build/test-classes")])
        host_log = (evidence/("cohost.log" if cohost else "standalone.log")).open("w", encoding="utf-8")
        host = subprocess.Popen([args.java, "-cp", cp,
                                 "cube.service.harness.HarnessCell", str(stage/"cell.xml"), str(stop)],
                                cwd=stage, stdout=host_log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                **({"creationflags": subprocess.CREATE_NO_WINDOW} if os.name=="nt" else {}))
        wait(healthy)
        # Cell 3.0's NonblockingAcceptor refuses connections during its initial
        # three-second safe lock. HTTP health starts before this window ends.
        time.sleep(3.3)
        if gateway is None: gateway = DispatcherFixture(args.java, cp, cell_port, api_token, runtime,
                                                       cell_host=args.cell_host)
        gateway.ready()
        return stage
    def stop_host(stage):
        nonlocal host, host_log
        (stage/"stop").touch()
        check(host.wait(timeout=15) == 0, "Cell did not exit cleanly")
        host_log.close()
        host = None

    class Sentinel(TalkListener):
        def __init__(self):
            self.contact = False
            self.values = queue.Queue(4)
        def onContacted(self, speaker):
            self.contact = True
        def onListened(self, speaker, cellet, primitive):
            self.values.put_nowait(primitive.getStuff(0).getValueAsString())
        def probe(self, speaker, text="probe"):
            p = Primitive()
            p.commit(Stuff(LiteralBase.STRING, text))
            check(speaker.speak("Sentinel", p), "Sentinel send")
            check(self.values.get(timeout=3) == "sentinel-alive", "Sentinel response")

    try:
        java_manifest = (module/"contracts/manifest.json").read_bytes()
        check(java_manifest == (ROOT/"manifest.json").read_bytes(), "Contract mirror drift")
        for name in json.loads(java_manifest):
            check((module/"contracts"/name).read_bytes() == (ROOT/name).read_bytes(), "Contract bytes drift")
        verify_contracts()
        record("shared-contract-integrity")
        import zipfile
        with zipfile.ZipFile(module/"build/cube-service-harness-3.0.jar") as jar:
            check(not any("/test/" in n or "Sentinel" in n for n in jar.namelist()), "Tests leaked into artifact")
            for cls in ("HarnessCellet", "HarnessService", "HarnessCarpet"):
                check("cube/service/harness/"+cls+".class" in jar.namelist(), "Missing class")
        record("production-jar-layout")
        stage = launch(False)
        record("standalone-xml-start", {"cellBindHost":args.cell_bind_host,"cellHost":args.cell_host,
                                        "healthHost":"127.0.0.1"})
        check(http("/readyz")[0] == 503, "empty registry should not be ready")
        parse("capabilities-response",encode(http("/harness/v1/capabilities")[1]))
        check(http("/harness/v1/capabilities", authorized=False)[0] == 401, "missing identity")
        check(http("/harness-admin/v1/certifications")[0] == 403, "public identity cannot administer")
        check(http("/harness/v1/runs", "POST", {"channelCode":"chat"}, headers={"Idempotency-Key":"execution-disabled"})[0] == 400, "legacy fields accepted")
        status, response = http("/harness/v1/runs", "POST", {"input":{"instruction":"test","attachmentIds":[]}}, headers={"Idempotency-Key":"execution-disabled"})
        check(status == 503 and response["errorCode"] == "HARNESS_NOT_READY", "not ready")
        record("new-api-auth-schema-and-not-ready")
        bad = client(credential="incorrect-private-credential")
        expect_error(bad.connect, "UNAUTHORIZED")
        bad.close()
        record("bad-credentials-rejected")
        for worker, role in (("not-authorized", "model"), ("model-1", "runner")):
            bad = client(worker, role)
            expect_error(bad.connect, "FORBIDDEN")
            bad.close()
        record("unauthorized-worker-and-wrong-role-rejected")
        model, runner = client(), client("runner-1", "runner")
        # This key exercises Java's negative-multiple-of-eight remainder;
        # legacy Python Cell decrypted its handshake with the wrong mask.
        with patch("harness.service.secrets.token_hex", return_value="0c000000"):
            model.connect()
        record("java-cell-negative-remainder-handshake")
        runner.connect()
        check(http("/readyz")[0] == 200, "two roles not ready")
        check(model.request("heartbeat")["registered"], "heartbeat")
        check(model.request("echo", {"text":"鍙屽悜 Cell echo"})["payload"]["text"] == "鍙屽悜 Cell echo", "echo corrupted")
        record("real-registration-heartbeat-and-bidirectional-cell")
        second = client("model-2")
        second.connect()
        snapshot = http("/harness/v1/capabilities")[1]["registeredWorkers"]
        check({w["workerId"] for w in snapshot} == {"model-1", "model-2", "runner-1"}, "missing independent node")
        check(len({w["instanceId"] for w in snapshot}) == 3, "nodes share an instance")
        check(len({c.session_id for c in (model, second, runner)}) == 3, "nodes share a lease nonce")
        def second_expired():
            model.request("heartbeat")
            runner.request("heartbeat")
            return {w["workerId"] for w in http("/harness/v1/capabilities")[1]["registeredWorkers"]} == {"model-1", "runner-1"}
        wait(second_expired)
        expect_error(lambda: second.request("heartbeat"), "STALE_SESSION")
        second.close()
        record("three-node-registration-and-independent-lease-expiry")
        expect_error(lambda: model.request("heartbeat", role="runner"), "FORBIDDEN")
        for raw, literal, expected in [(b'{"a":1,"a":2}', LiteralBase.STRING, "INVALID_JSON"),
                                       (b'{"a":"\xff"}', LiteralBase.STRING, "INVALID_JSON"),
                                       (b'{}', LiteralBase.JSON, "INVALID_ENVELOPE")]:
            p=Primitive();p.commit(Stuff(literal,raw));model.speaker.speak("Harness",p)
            check(model.responses.get(timeout=3).get("errorCode")==expected,"raw boundary changed")
        record("raw-cell-payload-rejection-and-worker-scope")
        duplicate = client()
        expect_error(duplicate.connect, "DUPLICATE_WORKER")
        duplicate.close()
        expect_error(lambda: model.request("heartbeat", protocolVersion=2), "PROTOCOL_MISMATCH")
        expect_error(lambda: model.request("heartbeat", sessionId="old-session"), "STALE_SESSION")
        old = model.session_id
        model.close()
        time.sleep(.2)
        model.connect()
        check(model.session_id != old, "reconnect reused session")
        expect_error(lambda: model.request("heartbeat", sessionId=old), "STALE_SESSION")
        record("duplicate-version-stale-and-reconnect")
        check(not model.request("drain")["ready"], "drain remains ready")
        expect_error(lambda: model.request("echo", {"text":"x"}), "WORKER_NOT_READY")
        check(not model.request("unregister")["registered"], "unregister failed")
        model.close()
        check(runner.shutdown(), "runner unregister failed")
        wait(lambda: len(http("/harness/v1/capabilities")[1]["registeredWorkers"]) == 0)
        record("drain-unregister-and-empty-registry")
        stop_host(stage)

        stage = launch(True)
        sentinel = Sentinel()
        speaker = HarnessSpeaker(args.cell_host, cell_port, sentinel)
        speaker.start()
        wait(lambda: sentinel.contact)
        def probes():
            times=[]
            for _ in range(20):
                start=time.perf_counter();sentinel.probe(speaker);times.append((time.perf_counter()-start)*1000)
            return {"medianMs": statistics.median(times), "maxMs": max(times)}
        before = probes()
        wrappers = [("PowerShell", shutil.which("pwsh") or shutil.which("powershell"))]
        bash = shutil.which("bash")
        if not bash and os.name == "nt" and Path("C:/Program Files/Git/bin/bash.exe").exists():
            bash = "C:/Program Files/Git/bin/bash.exe"
        if bash:
            wrappers.append(("Shell", bash))
        def command(action, config, wrapper, expected=0):
            kind, exe = wrapper
            script = Path(args.python_root).resolve()/"harness/scripts"/(action+(".ps1" if kind=="PowerShell" else ".sh"))
            cmd = [exe, "-NoProfile", "-File", str(script), "-Config", str(config), "-Python", sys.executable] if kind=="PowerShell" else [exe, str(script).replace("\\", "/"), str(config).replace("\\", "/")]
            env = dict(os.environ, HARNESS_PYTHON=sys.executable.replace("\\", "/"), PYTHONIOENCODING="utf-8")
            p = subprocess.run(cmd, env=env, capture_output=True, text=True, encoding="utf-8", timeout=35)
            if expected != 0:
                check(p.returncode != 0, "start incorrectly succeeded")
                return p.returncode
            if p.returncode:
                raise AssertionError(action+" wrapper failed: "+p.stdout+p.stderr)
            return json.loads(p.stdout.strip().splitlines()[-1])
        for wrapper in wrappers:
            if not wrapper[1]:
                continue
            configs=[]
            for role in ("model", "runner"):
                config = runtime/(wrapper[0]+"-"+role+".local.json")
                config.write_bytes(encode(dict(host=args.cell_host, port=cell_port, workerId=role+"-1", role=role, workerCredential=token,
                                              runtimeDir=str(runtime/(wrapper[0]+"-"+role)), startupTimeout=15, requestTimeout=2)))
                configs.append(config)
                check(command("start", config, wrapper)["registered"], "start did not wait for registration")
            check(command("status", configs[0], wrapper)["ready"], "status")
            extra = runtime/(wrapper[0]+"-model-2.local.json")
            value = json.loads(configs[0].read_text())
            value.update(workerId="model-2", runtimeDir=str(runtime/(wrapper[0]+"-model-2")))
            extra.write_bytes(encode(value))
            configs.append(extra)
            check(command("start", extra, wrapper)["registered"], "second model process did not register")
            snapshot = http("/harness/v1/capabilities")[1]["registeredWorkers"]
            check({w["workerId"] for w in snapshot} == {"model-1", "model-2", "runner-1"}, "missing worker process")
            check(len({w["instanceId"] for w in snapshot}) == 3, "worker processes share identity")
            states = [command("status", config, wrapper) for config in configs]
            check(len({s["pid"] for s in states}) == 3, "workers share a process")
            check(command("stop", extra, wrapper)["cleanUnregister"], "second model did not unregister")
            configs.remove(extra)
            check(all(command("status", config, wrapper)["ready"] for config in configs), "stop affected another worker")
            check({w["workerId"] for w in http("/harness/v1/capabilities")[1]["registeredWorkers"]} == {"model-1", "runner-1"}, "stopped worker retained registration")
            record(wrapper[0]+"-three-process-registration-and-stop-isolation")
            if wrapper[0] == "PowerShell" or os.name != "nt":
                command("start",configs[0],wrapper,expected=1)
                for case in ("wrong-credential", "duplicate-worker"):
                    invalid=json.loads(configs[0].read_text())
                    invalid["runtimeDir"]=str(runtime/(wrapper[0]+"-"+case))
                    if case=="wrong-credential":invalid["workerCredential"]="invalid-private-worker-credential"
                    bad_config=runtime/(wrapper[0]+"-"+case+".local.json")
                    bad_config.write_bytes(encode(invalid))
                    command("start",bad_config,wrapper,expected=1)
                    check(not lifecycle.running(Path(invalid["runtimeDir"])),"failed start retained process")
                check(command("status",configs[1],wrapper)["ready"],"failed start affected another instance")
                record("script-start-failures-return-nonzero-without-leaks")
                # Interrupt a real Cell process while both independent worker
                # processes are alive. Reconnect must not reuse a lease nonce.
                first = lifecycle.read_state(Path(json.loads(configs[0].read_text())["runtimeDir"]))["instanceId"]
                stop_host(stage)
                for config in configs:
                    wait(lambda config=config: lifecycle.read_state(Path(json.loads(config.read_text())["runtimeDir"])).get("state") == "reconnecting")
                stage = launch(True)
                for config in configs:
                    wait(lambda config=config: (lambda state: state.get("state")=="registered" and state.get("reconnects",0)>=1)(lifecycle.read_state(Path(json.loads(config.read_text())["runtimeDir"]))))
                check(command("status",configs[0],wrapper)["instanceId"] == first, "worker process was replaced")
                record("automatic-reconnect-after-real-cell-interruption")
                stop_host(stage)
                wait(lambda: lifecycle.read_state(Path(json.loads(configs[0].read_text())["runtimeDir"])).get("state") == "reconnecting")
                command("stop", configs[0], wrapper)
                stage = launch(True)
                wait(lambda: (lambda state: state.get("registered") and state.get("state")=="registered")(lifecycle.read_state(Path(json.loads(configs[1].read_text())["runtimeDir"]))))
                check(not command("status",configs[0],wrapper)["processRunning"], "stopped worker restarted")
                check([w["workerId"] for w in http("/harness/v1/capabilities")[1]["registeredWorkers"]] == ["runner-1"], "stopped worker re-registered")
                record("stop-during-reconnect-prevents-registration")
                # Original sentinel transport belongs to the interrupted host.
                speaker.close_owned()
                sentinel=Sentinel()
                speaker=HarnessSpeaker(args.cell_host,cell_port,sentinel)
                speaker.start()
                wait(lambda: sentinel.contact)
                command("stop", configs[1], wrapper)
                configs=[]
                # Validate a second normal start/stop cycle as well.
                for role in ("model", "runner"):
                    config=runtime/(wrapper[0]+"-"+role+".local.json")
                    configs.append(config)
                    command("start",config,wrapper)
            check(command("stop", configs[0], wrapper)["cleanUnregister"], "stop not graceful")
            check(command("status", configs[1], wrapper)["processRunning"], "stopped another instance")
            command("stop", configs[1], wrapper)
            record(wrapper[0]+"-start-status-stop-isolation", {"platform": sys.platform, "executable": wrapper[1]})
            configs=[]
        after=probes()
        sentinel.probe(speaker,"stopHarness")
        sentinel.probe(speaker)
        record("cohost-stop-preserves-other-cellet", {"before":before,"after":after,"productionServices":False})
        speaker.close_owned()
        stop_host(stage)
        record("same-artifact-standalone-and-cohost-xml")
    finally:
        for c in clients:
            c.shutdown()
        for config in configs:
            try:
                lifecycle.stop(config)
            except Exception:
                pass
        if gateway is not None:
            gateway.close()
        if host is not None:
            host.terminate()
            host.wait(timeout=15)
        if host_log is not None and not host_log.closed:
            host_log.close()
        (evidence/"smoke-results.json").write_text(json.dumps(results,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")


if __name__ == "__main__":
    main()
