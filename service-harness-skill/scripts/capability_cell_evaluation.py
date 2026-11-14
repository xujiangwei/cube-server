"""Public diagnostic: isolated JDK8 Cell/dispatcher, real models, real Broker, two workers per role.

All tokens and mutable ledgers stay under --runtime (outside published evidence). The
local proxy serializes physical inference and buffers SSE without changing its bytes.
Search/business data are explicitly controlled fixtures; visual inference is real.
"""
import argparse
import base64
import hashlib
import hmac
import http.server
import io
import json
import os
import re
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
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SERVER = ROOT.parent
HARNESS = SERVER / "service-harness"
WORKSPACE = SERVER.parent
sys.path.insert(0, str(HARNESS / "scripts"))
from capability_evaluation import Remote, Journal, canonical, collect_sse, redact, save, sha
from dispatcher_fixture import DispatcherFixture

SOURCE_SECRET = b"evaluation-source-private-fixture-key-123456"
TERMINAL = {"succeeded", "failed", "cancelled", "timed_out", "waiting_input", "waiting_approval"}


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def wait(fn, timeout=60):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            v = fn()
            if v:
                return v
        except (OSError, urllib.error.URLError):
            pass
        time.sleep(.15)
    raise TimeoutError("LOCAL_CONDITION_TIMEOUT")


def record(input_id=None, suffix="1"):
    media = dict(id="evaluation-photo-" + suffix, version="1", mediaType="image/jpeg")
    if input_id:
        media["inputId"] = input_id
    return dict(id="evaluation-record-" + suffix, version="1", capturedAt=None,
        device=dict(id="evaluation-device", name="公开样本设备（合成）"),
        region=dict(id="evaluation-region", name="公开样本区域（合成）"), media=media)


def scenarios():
    return [
        dict(id="E01", title="HTTP 至规范报告", region="single"),
        dict(id="E02", title="上传快照至规范报告", region="snapshot", snapshot=True),
        dict(id="E03", title="多页与重复记录", region="paged-duplicate", count=1),
        dict(id="E04", title="真实空数据", region="empty", count=0),
        dict(id="E05", title="缺页及部分失败", region="partial", fault="second page HTTP 503", partial=True),
        dict(id="E06", title="提前提交后补齐", region="single", skip=True,
             fault="user requests premature final; guard must prevent an unsupported success"),
        dict(id="E07", title="报告来源、规则、未知拍摄时间", region="single"),
        dict(id="E08", title="多 worker 排队、取消、停止恢复", region="empty", count=0, workers=True),
    ]


class LocalServer:
    def __init__(self, remote, image, evidence, controlled=False):
        self.remote, self.image = remote, image.read_bytes()
        self.journal = Journal(evidence / "fixture-http.jsonl", remote.token)
        self.intents = Journal(evidence / "http-intents.jsonl", remote.token)
        owner = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def send(self, code, raw, content_type="application/json; charset=utf-8"):
                if not isinstance(raw, bytes):
                    raw = canonical(raw).encode()
                try:
                    self.send_response(code)
                    self.send_header("Content-Type", content_type)
                    self.send_header("Content-Length", str(len(raw)))
                    self.end_headers()
                    self.wfile.write(raw)
                except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError) as exc:
                    owner.journal.append(dict(kind="client-disconnected", proxyRequestId=getattr(self,"proxy_request_id",None), error=type(exc).__name__))

            def do_GET(self):
                url = urllib.parse.urlsplit(self.path)
                query = {k: v[0] for k, v in urllib.parse.parse_qs(url.query).items()}
                if url.path == "/search":
                    self.send(200, dict(results=[dict(title="Fixture source", url="https://fixture.invalid/evaluation", content="Fixture 中文证据", engine="fixture")]))
                    return
                text = "\n".join(["GET", self.path] + [self.headers.get(k, "") for k in
                    ("X-Harness-Source", "X-Harness-Tenant", "X-Harness-Subject", "X-Harness-Issued-At", "X-Harness-Nonce")])
                signature = hmac.new(SOURCE_SECRET, text.encode(), hashlib.sha256).hexdigest()
                authorized = hmac.compare_digest(signature, self.headers.get("X-Harness-Signature", ""))
                owner.journal.append(dict(method="GET", path=self.path, authorized=authorized))
                if not authorized:
                    self.send(403, {})
                elif url.path == "/api/media/content":
                    self.send(200, owner.image, "image/jpeg")
                elif url.path == "/api/patrol/records":
                    region, cursor = query.get("regionId", ""), query.get("cursor")
                    if cursor and query.get("snapshotId") != "evaluation-snapshot":
                        self.send(409, {})
                    elif cursor and region == "partial":
                        self.send(503, dict(error="injected second-page failure"))
                    else:
                        items = [] if region == "empty" else [record()]
                        if region == "duplicate":
                            items *= 2
                            if owner.filter_test:
                                excluded = record(suffix="excluded")
                                excluded["region"]["id"] = "outside"
                                items.append(excluded)
                        paged = region in ("paged", "paged-duplicate", "partial")
                        self.send(200, dict(data=dict(items=items, snapshotId="evaluation-snapshot", nextCursor="next" if paged and not cursor else "")))
                else:
                    self.send(404, {})

            def do_POST(self):
                raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                body = json.loads(raw)
                if self.path == "/media/invoke":
                    execution = body["execution"]
                    request = execution.get("request", execution)
                    # The persisted request is nested in the media execution reservation.
                    if "requestJson" in execution:
                        request = json.loads(execution["requestJson"])
                    owner.journal.append(dict(kind="media-reservation", execution=execution))
                    if controlled:
                        detection = request['unit'] == 'SpecializedDetection'
                        payload = dict(profile='thing', refs=[dict(content='person', boxes=[dict(x1=10,y1=10,x2=30,y2=60,confidence=.9)])]) if detection else {}
                        self.send(200, dict(requestId=execution['requestId'], instanceId=execution['instanceId'],
                            actualFinished=True, success=True, payload=dict(response='' if detection else '可控夹具：人员出现在场景中，授权与异常状态未核实。', resultPayload=payload)))
                        return
                    try:
                        response = remote.visual(base64.b64decode(body["imageBase64"]), request["unit"],
                            request["content"], request.get("option"), identity=dict(kind="local-media", requestId=execution["requestId"]))
                        payload = dict(response)
                        payload["response"] = response.get("answer", response.get("response", ""))
                        self.send(200, dict(requestId=execution["requestId"], instanceId=execution["instanceId"],
                            actualFinished=True, success=True, payload=payload))
                    except (RuntimeError, KeyError) as exc:
                        owner.journal.append(dict(kind="media-bridge-error", error=str(exc)))
                        self.send(502, dict(error="MEDIA_BRIDGE_NO_RECEIPT"))
                    return
                if self.path != "/v1/chat/completions":
                    self.send(404, {})
                    return
                if controlled:
                    task = next(m['content'] for m in body['messages'] if m['role']=='user' and m.get('content','').startswith('请完成 '))
                    premature = '为测试门禁' in task and not any('platform:workflow-guard' in str(m) for m in body['messages'])
                    if premature:
                        decision = dict(kind='final', text='已完成')
                    else:
                        snapshot = re.search(r'inputId=([A-Za-z0-9_.:-]+)', task)
                        arguments = dict(skill='patrol/general', connectorId='uploaded-records' if snapshot else 'patrol-records',
                            argumentsJson=canonical(dict(inputId=snapshot.group(1)) if snapshot else dict(
                                **{'from':'2026-11-13T00:00:00+08:00','to':'2026-11-14T00:00:00+08:00'},
                                regionId=re.search(r'regionId=([^。]+)',task).group(1))))
                        decision = dict(kind='tool_calls', calls=[dict(proposalId='query1',tool='common.data.query',argumentsJson=canonical(arguments))])
                    message = dict(role='assistant', content=canonical(decision))
                    reason = 'stop'
                    if body.get('tools'):
                        name = 'respond_to_user' if premature else next(t['function']['name'] for t in body['tools'] if t['function']['description'].startswith('common.data.query:'))
                        message = dict(role='assistant', content=None, tool_calls=[dict(id='query1',type='function',function=dict(
                            name=name,arguments=canonical(dict(text='已完成') if premature else arguments)))])
                        reason = 'tool_calls'
                    response = dict(id='controlled-fixture',model=body['model'],choices=[dict(index=0,message=message,finish_reason=reason)],usage=dict(prompt_tokens=0,completion_tokens=0,total_tokens=0))
                    if body.get('stream'):
                        if message.get('tool_calls'):
                            for index, call in enumerate(message['tool_calls']): call['index'] = index
                        event = dict(response, choices=[dict(index=0,delta=message,finish_reason=reason)])
                        self.send(200, ('data: '+canonical(event)+'\n\ndata: [DONE]\n\n').encode(), 'text/event-stream')
                    else:
                        self.send(200,response)
                    return
                proxy_id = secrets.token_hex(16)
                self.proxy_request_id = proxy_id
                owner.intents.append(dict(proxyRequestId=proxy_id, stage="received", model=body.get("model"), requestBytesSha256=sha(raw)))
                with remote.serial:
                    start = time.monotonic()
                    req = urllib.request.Request(remote.base + self.path, data=raw,
                        headers={"Authorization": "Bearer " + remote.token, "Content-Type": "application/json"})
                    try:
                        owner.intents.append(dict(proxyRequestId=proxy_id, stage="dispatch-started", model=body.get("model"), requestBytesSha256=sha(raw)))
                        with urllib.request.urlopen(req, timeout=remote.timeout) as r:
                            content_type, data, status = r.headers.get("Content-Type", "application/json"), r.read(), r.status
                        result = collect_sse(io.BytesIO(data)) if "text/event-stream" in content_type else json.loads(data)
                    except (OSError, ValueError) as exc:
                        remote.journal.append(dict(identity=dict(kind="cell-model"), proxyRequestId=proxy_id, request=body,
                            actualFinished=False, error=type(exc).__name__, elapsedMs=round((time.monotonic()-start)*1000)))
                        self.send(502, dict(error=dict(message="REMOTE_COMPLETION_UNKNOWN", type="upstream_error")))
                    else:
                        remote.journal.append(dict(identity=dict(kind="cell-model"), proxyRequestId=proxy_id, request=body, response=result,
                            actualFinished=True, elapsedMs=round((time.monotonic()-start)*1000), responseBytesSha256=sha(data)))
                        # Delivery is a local hop; a failure here cannot undo the
                        # physically complete upstream receipt recorded above.
                        self.send(status, data, content_type)

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        # Keep receiving a physically dispatched request after the local task or
        # client exits. server_close joins these threads before this process can
        # terminate; a missing receipt must never disappear with a daemon thread.
        self.server.daemon_threads = False
        self.filter_test = False
        self.url = "http://127.0.0.1:" + str(self.server.server_port)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        if not self.remote.serial.acquire(blocking=False):
            raise RuntimeError("EVALUATION_REMOTE_EXECUTION_STILL_PENDING: preserve the ledger and stop this backend's follow-up")
        self.remote.serial.release()


class Evaluation:
    def __init__(self, args):
        self.args = args
        self.root, self.evidence = args.runtime.resolve(), args.evidence.resolve()
        self.root.mkdir(parents=True, exist_ok=False)
        self.evidence.mkdir(parents=True, exist_ok=True)
        self.remote = Remote(json.loads(args.config.read_text(encoding="utf-8")), self.evidence)
        self.upstream = LocalServer(self.remote, args.image, self.evidence, args.controlled_inference)
        binaries = self.root / 'binaries'
        binaries.mkdir()
        originals = [SERVER/'build/cube-common-3.0.jar', HARNESS/'build/cube-service-harness-3.0.jar', ROOT/'build/cube-service-harness-skill-3.0.jar']
        for original in originals: shutil.copy2(original, binaries/original.name)
        save(self.evidence/'binary-fingerprints.json', {p.name:sha((binaries/p.name).read_bytes()) for p in originals})
        self.cp = os.pathsep.join(str(p.resolve()) for p in [SERVER / "deploy/bin/cell.jar", binaries / "cube-common-3.0.jar",
            SERVER / "deploy/libs/*", binaries / "cube-service-harness-3.0.jar", HARNESS / "build/test-classes",
            binaries / "cube-service-harness-skill-3.0.jar", ROOT / "build/test-classes"])
        sys.path.insert(0, str(args.python_root.resolve()))
        from harness import lifecycle
        self.lifecycle = lifecycle
        self.observations = Journal(self.evidence / "cell-observations.jsonl", self.remote.token)

    def prepare(self, path, mode="prepare"):
        path.parent.mkdir(parents=True, exist_ok=True)
        cmd = [str(self.args.java), '-Dpatrol.eval.capability.timeout='+str(self.args.capability_timeout_millis),
            '-Dpatrol.eval.capability.queue.timeout='+str(self.args.capability_queue_timeout_millis),
            "-cp", self.cp, "cube.service.harness.test.CapabilityEvaluationFixture", mode,
            str(path), str(self.args.tool_packages.resolve()), str(self.args.business_zip.resolve()), self.upstream.url]
        if mode == "tools":
            cmd.append(str(self.args.image.resolve()))
            if self.args.tool_cases:
                cmd.append(self.args.tool_cases)
        with (path.parent / (path.name + "-prepare.log")).open("wb") as log:
            subprocess.run(cmd, cwd=SERVER, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=650)

    def tools(self):
        self.upstream.filter_test = self.args.tool_filter
        for rep in range(1, 4):
            path = self.root / ("tools-" + str(rep))
            self.prepare(path, "tools")
            rows = json.loads((path / "tool-results.json").read_text(encoding="utf-8"))
            save(self.evidence / "tools" / (str(rep) + ".json"), rows)
            for row in rows:
                summary = {k:v for k,v in row.items() if k != "evidence"}
                summary.update(identity=f'{row["caseId"]}-{rep}', repetition=rep, artifact=f'tools/{rep}.json')
                print(canonical(self.observations.append(summary)), flush=True)

    def batch(self, model):
        root = self.root / model
        root.mkdir()
        (self.evidence / model).mkdir(parents=True, exist_ok=True)
        data = root / "data"
        self.prepare(data)
        fixture = json.loads((data / "evaluation.fixture.json").read_text(encoding="utf-8"))
        cell_port, http_port = free_port(), free_port()
        api, credential, trace_token = (secrets.token_hex(24) for _ in range(3))
        stage = root / "cell"
        (stage / "cellets").mkdir(parents=True)
        shutil.copy2(self.root / "binaries/cube-service-harness-3.0.jar", stage / "cellets")
        xml = ET.parse(HARNESS / "config/standalone.xml")
        xml.find(".//listeners/class").text = "cube.service.harness.test.CapabilityEvaluationFixture$Carpet"
        xml.find(".//server/port").text = str(cell_port)
        xml.find(".//cellet").set("port", str(cell_port))
        xml.write(stage / "cell.xml", encoding="utf-8", xml_declaration=True)
        config = stage / "harness.properties"
        config.write_text((data / "evaluation.properties").read_text(encoding="utf-8") + f"""
http.host=127.0.0.1
http.port={http_port}
lease.millis=6000
worker.credential={credential}
fixture.user.token={api}
fixture.user.domain={fixture['tenant']}
fixture.user.contact={fixture['subject']}
fixture.trace.token={trace_token}
fixture.trace.domain={fixture['tenant']}
fixture.trace.contact=10003
trace.enabled=true
trace.reader.subjects={fixture['tenant']}/10003
store.enabled=true
execution.enabled=true
execution.tools.enabled=true
execution.model={model}@{'1' if model == 'Baize' else '2'}
backend.shared.type=openai
backend.shared.baseUrl={self.upstream.url}/v1
backend.shared.model={model}
backend.shared.token=local-proxy-private-fixture
backend.shared.capacity=1
backend.shared.timeoutSeconds=180
worker.model-a.role=model
worker.model-a.model={model}
worker.model-a.backend=shared
worker.model-b.role=model
worker.model-b.model={model}
worker.model-b.backend=shared
worker.runner-a.role=runner
worker.runner-a.capacity=1
worker.runner-b.role=runner
worker.runner-b.capacity=1
files.enabled=true
""", encoding="utf-8")
        gateway, process, workers = None, None, []
        log_path = root / "cell.log"
        log = log_path.open("wb")

        def api_call(path, body=None, binary=False, method=None, trace=False):
            headers = {"Authorization": "Bearer " + (trace_token if trace else api), "Accept": "application/json"}
            if body is not None:
                headers.update({"Content-Type": "application/octet-stream" if binary else "application/json", "Idempotency-Key": secrets.token_hex(16)})
            url = gateway.url(path, http_port) if gateway else "http://127.0.0.1:" + str(http_port) + path
            raw = body if binary else None if body is None else canonical(body).encode()
            try:
                with urllib.request.urlopen(urllib.request.Request(url, data=raw, headers=headers, method=method), timeout=10) as r:
                    return json.loads(r.read())
            except urllib.error.HTTPError as exc:
                return dict(httpStatus=exc.code, error=json.loads(exc.read()))

        def start_worker(path, name):
            if (self.args.runner_diagnostics or self.args.runner_utf8) and name.startswith("runner"):
                from unittest.mock import patch
                original_popen = subprocess.Popen
                def observed_popen(command, **options):
                    command = list(command)
                    index = command.index("-m")
                    command[index:index+2] = [str(ROOT / "scripts/capability_diagnostic_worker.py")]
                    if self.args.runner_utf8:
                        options["env"] = dict(options["env"], HARNESS_EVAL_RUNNER_UTF8="1")
                    return original_popen(command, **options)
                with patch.object(subprocess, "Popen", observed_popen):
                    self.lifecycle.start(path)
            else:
                self.lifecycle.start(path)

        def worker(name):
            path = root / (name + ".json")
            value = dict(host="127.0.0.1", port=cell_port, workerId=name, role="model" if name.startswith("model") else "runner",
                workerCredential=credential, runtimeDir=str(root / ("worker-" + name)), requestTimeout=3, startupTimeout=30)
            value["model" if name.startswith("model") else "runner"] = dict(model=model, nativeTools=model == "Baize2", maxWireBytes=131072) if name.startswith("model") else dict(type="tool", runtime="trusted-python-v1", capacity=1)
            if name.startswith('runner') and self.args.provider_await:
                value['runner']['providerAwait'] = True
            save(path, value)
            workers.append(path)
            start_worker(path, name)

        def upload(raw, name, media):
            digest = sha(raw)
            result = api_call("/harness/v1/inputs", dict(fileName=name, mediaType=media, size=len(raw), sha256=digest))
            input_id = result["inputId"]
            api_call("/harness/v1/inputs/"+input_id+"/content", raw, binary=True, method="PUT")
            api_call("/harness/v1/inputs/"+input_id+"/complete", dict(sha256=digest))
            return input_id

        def db_tables(run):
            tables = {}
            with sqlite3.connect(data / "harness_execution.db", timeout=20) as c:
                c.row_factory = sqlite3.Row
                for (name,) in c.execute("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'harness_%'").fetchall():
                    cols = {r[1] for r in c.execute('PRAGMA table_info("'+name+'")')}
                    if "run_id" in cols:
                        tables[name] = [dict(r) for r in c.execute('SELECT * FROM "'+name+'" WHERE run_id=?', (run,))]
                tables["harness_broker_exchange"] = [dict(r) for r in c.execute(
                    "SELECT e.* FROM harness_broker_exchange e JOIN harness_tool_job j ON e.job_id=j.job_id WHERE j.run_id=?", (run,))]
                tables["harness_inference"] = [dict(r) for r in c.execute(
                    "SELECT i.* FROM harness_inference i JOIN harness_model_job j ON i.job_id=j.job_id WHERE j.run_id=?", (run,))]
            return tables

        def done(run):
            result = api_call("/harness/v1/runs/" + run)
            return result if result.get("snapshot", {}).get("state") in TERMINAL else None

        try:
            process = subprocess.Popen([str(self.args.java), "-Dharness.config="+str(config), "-cp", self.cp,
                "cube.service.harness.HarnessCell", str(stage / "cell.xml"), str(stage / "stop")], cwd=stage,
                stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                **({"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}))
            wait(lambda: api_call("/healthz"), 40)
            gateway = DispatcherFixture(self.args.java, self.cp, cell_port, api, root)
            gateway.ready()
            for name in ("runner-a", "runner-b", "model-a", "model-b"):
                worker(name)
            save(self.evidence / model / "capabilities.json", api_call("/harness/v1/capabilities"))
            save(self.evidence / model / "profiles.json", api_call("/harness/v1/task-profiles"))
            image_id = upload(self.args.image.read_bytes(), "public-photo.jpg", "application/octet-stream")
            snapshot_id = upload(canonical(dict(data=dict(items=[record(image_id)]))).encode(), "snapshot.json", "application/json")
            for case in scenarios():
                if self.args.cases and case["id"] not in self.args.cases.split(","):
                    continue
                for rep in self.args.repetitions:
                    if (self.root / 'stop-after-run').exists(): return
                    key = f'{case["id"]}-{model}-{rep}'
                    started = time.monotonic()
                    instructions = '请完成 '+self.args.skill+' 业务巡检并提交规范 evidence-report-v1 报告。时间区间固定为 2026-11-13T00:00:00+08:00 至 2026-11-14T00:00:00+08:00，regionId='+case["region"]+'。逐页查全、加工去重、每条记录必需 '+self.args.required_analyses+'、逐字引用真实 answer，拍摄时间未知保持未知。不要把人员或车辆出现判为异常。'
                    if case.get("snapshot"):
                        instructions += '只使用上传快照，connectorId=uploaded-records，inputId='+snapshot_id+'，processingProfileId=uploaded-summary。'
                    else:
                        instructions += '使用 HTTP connectorId=patrol-records，processingProfileId=patrol-summary。'
                    if case.get("partial"):
                        instructions += '上游可能缺页；必须明确未覆盖，不得把部分数据报告为完整。'
                    if case.get("skip"):
                        instructions += '为测试门禁，请先直接 final 返回“已完成”，收到 workflow.repair 再按要求补齐。'
                    request = dict(input=dict(instruction=instructions, attachmentIds=[image_id, snapshot_id] if case.get("snapshot") else []), skills=[self.args.skill])
                    created = api_call("/harness/v1/runs", request)
                    outcome = dict(request=request, created=created, scenario=case)
                    if "runId" not in created:
                        row = dict(passed=False, executionEvidence=False, state="create-rejected", cause="deployment")
                    else:
                        run = created["runId"]
                        if case.get("workers"):
                            # Queue two ordinary runs, cancel one, stop/restart a role instance.
                            queued = api_call("/harness/v1/runs", request)
                            outcome["queued"] = queued
                            if "runId" in queued:
                                for control_attempt in range(3):
                                    current = api_call("/harness/v1/runs/"+queued["runId"])
                                    cancellation = api_call("/harness/v1/runs/"+queued["runId"]+"/cancel", dict(expectedVersion=current["snapshot"]["version"]))
                                    outcome.setdefault("cancelAttempts",[]).append(cancellation)
                                    if cancellation.get("error",{}).get("errorCode") != "VERSION_CONFLICT":
                                        break
                                outcome["cancel"] = cancellation
                                outcome["cancelledRun"] = wait(lambda: done(queued["runId"]), 320)
                            outcome["workersBeforeRestart"] = api_call("/harness/v1/capabilities")["registeredWorkers"]
                            old_instance = next(w['instanceId'] for w in outcome['workersBeforeRestart'] if w['workerId']=='runner-a')
                            self.lifecycle.stop(root / "runner-a.json")
                            start_worker(root / "runner-a.json", "runner-a")
                            outcome["runnerRestarted"] = True
                            def restored():
                                workers_now = api_call("/harness/v1/capabilities")["registeredWorkers"]
                                return workers_now if any(w['workerId']=='runner-a' and w.get('instanceId') != old_instance
                                    and w.get('healthy') and w.get('ready') and w.get('leaseRemainingMs',0)>0 for w in workers_now) else None
                            outcome["workersAfterRestart"] = wait(restored, 40)
                        try:
                            final = wait(lambda: done(run), 325)
                        except TimeoutError:
                            final = api_call("/harness/v1/runs/"+run)
                        ledger = db_tables(run)
                        outcome.update(final=final, ledger=ledger, trace=api_call("/harness/v1/runs/"+run+"/trace", trace=True))
                        row = score(case, final, ledger)
                        row["runId"] = run
                        if case.get("workers"):
                            row['cancelPassed'] = outcome.get("cancelledRun", {}).get("snapshot", {}).get("state") == "cancelled"
                            row['restoredReady'] = bool(outcome.get('workersAfterRestart'))
                            row["passed"] = row["passed"] and row['cancelPassed'] and row['restoredReady']
                    artifact = model + "/"+key+".json"
                    save(self.evidence / artifact, redact(outcome, self.remote.token))
                    row.update(identity=key, layer="orchestration", caseId=case["id"], model=model, repetition=rep,
                        elapsedMs=round((time.monotonic()-started)*1000), fault=case.get("fault"), artifact=artifact)
                    print(canonical(self.observations.append(row)), flush=True)
                    # A task terminal state does not establish physical completion.
                    # Never start the next independent run while this backend has
                    # a dispatched job or a remote request without its receipt.
                    if "runId" in created:
                        unresolved = [j for table in ("harness_model_job", "harness_tool_job")
                            for j in ledger.get(table, []) if not j.get("actual_finished")]
                        if unresolved or final.get("snapshot", {}).get("executionSettlement") == "unknown":
                            raise RuntimeError("UNRESOLVED_EXECUTION: stop batch; retain original ledger and do not replay")
                    http_path = self.evidence / "http.jsonl"
                    if http_path.exists() and any(json.loads(line).get("actualFinished") is False
                            for line in http_path.read_text(encoding="utf-8").splitlines()):
                        raise RuntimeError("UNRESOLVED_REMOTE_COMPLETION: stop batch; do not replay")
        finally:
            for path in reversed(workers):
                try:
                    self.lifecycle.stop(path)
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
            text = log_path.read_text(encoding="utf-8", errors="replace")
            for token in (api, credential, trace_token, self.remote.token):
                text = text.replace(token, "[REDACTED]")
            (self.evidence / model / "cell.log").write_text(text, encoding="utf-8")
            if self.args.runner_diagnostics or self.args.runner_utf8:
                records = []
                for name in ("runner-a", "runner-b"):
                    worker_log = root / ("worker-"+name) / "harness.log"
                    for line in worker_log.read_text(encoding="utf-8", errors="replace").splitlines() if worker_log.exists() else []:
                        if line.startswith('{"diagnostic":'):
                            records.append(dict(workerId=name, **json.loads(line)))
                save(self.evidence / model / "runner-diagnostics.json", records)


def score(case, final, ledger):
    state = final.get("snapshot", {}).get("state", "unknown")
    calls = ledger.get("harness_tool_job", [])
    tools = [r.get("tool_name") for r in calls]
    evidence = all(x in tools for x in ("common.data.query", "common.processing.process"))
    encoded = canonical(final)
    report = '"datasetRef"' in encoded and '"findings"' in encoded and '"report"' in encoded
    passed = state == "succeeded" and evidence and report
    if case.get("count", 1):
        passed = passed and "common.capability.invoke" in tools
    if case.get("partial"):
        # Missing-page case passes only with explicit incomplete diagnosis and no clean success.
        errors = canonical(ledger)
        passed = state != "succeeded" and "DATA_QUERY_INCOMPLETE" in errors
    return dict(passed=passed, executionEvidence=evidence, reportPresent=report, state=state, toolCalls=len(calls), tools=tools,
        semanticReview="required: host validates references, not generated summary/assessment semantics")


def main():
    p = argparse.ArgumentParser(__doc__)
    for name in ("java", "python-root", "runtime", "evidence", "config"):
        p.add_argument("--"+name, type=Path, required=True)
    p.add_argument("--image", type=Path, default=WORKSPACE / "forest/media/evaluation/obstacle-negative-01.jpg")
    p.add_argument("--business-zip", type=Path, default=ROOT / "build/fhe-patrol-m4-test.zip")
    p.add_argument('--tool-packages', type=Path, default=ROOT/'build/packages')
    p.add_argument('--provider-await', action='store_true')
    p.add_argument('--controlled-inference', action='store_true', help='Explicit local model/visual fixtures; validates execution only and never calls remote inference')
    p.add_argument('--capability-timeout-millis', type=int, default=20000)
    p.add_argument('--capability-queue-timeout-millis', type=int, default=0)
    p.add_argument("--skill", default="fhe/patrol")
    p.add_argument("--required-analyses", default="describe")
    p.add_argument("--layers", default="tools,orchestration")
    p.add_argument("--models", default="Baize,Baize2")
    p.add_argument("--cases", default="", help="Comma-separated E case IDs for separately labeled controls")
    p.add_argument("--tool-cases", default="")
    p.add_argument("--repetitions", type=lambda text: [int(x) for x in text.split(",")], default=[1,2,3], help="Explicit independent trial numbers for an infrastructure recovery shard; preserve all earlier evidence")
    p.add_argument("--tool-filter", action="store_true", help="Use the diagnostic package containing evaluation-filtered; emit an excluded third record")
    p.add_argument("--runner-diagnostics", action="store_true", help="Evaluation-only observer; preserves Runner decisions and broker responses")
    p.add_argument("--runner-utf8", action="store_true", help="Separately labeled control: force UTF-8 for root tool children, without editing production sources")
    a = p.parse_args()
    if not a.repetitions or len(set(a.repetitions)) != len(a.repetitions) or any(x not in (1,2,3) for x in a.repetitions):
        p.error("repetitions must be unique values from 1,2,3")
    evaluation = Evaluation(a)
    save(a.evidence / "orchestration-fixture.json", dict(cases=scenarios(), repetitions=3, trialNumbers=a.repetitions,
        sourceFingerprints={name:sha((a.python_root / "harness" / name).read_bytes()) for name in ("model_adapter.py","runner_process.py","runner_sdk.py","runner_worker.py")},
        physicalInferenceConcurrency=1, sseProxy="buffers and relays original bytes; excludes TTFT measurement",
        controlledInference=a.controlled_inference,
        runnerObserver=a.runner_diagnostics or a.runner_utf8,runnerUtf8Control=a.runner_utf8,
        budgets=dict(maxRounds=8,maxTools=32,maxRepairs=2,maxLifetimeMillis=300000),
        boundary=("controlled execution only; local model/visual/HTTP/business fixtures; no remote inference or production Unit transport"
            if a.controlled_inference else "public diagnosis; local HTTP/business/search fixtures; remote real model and visual inference; no production Unit transport")))
    try:
        if "tools" in a.layers.split(","):
            evaluation.tools()
        if "orchestration" in a.layers.split(","):
            for model in a.models.split(","):
                if (evaluation.root / 'stop-after-run').exists(): break
                evaluation.batch(model)
    finally:
        evaluation.upstream.close()


if __name__ == "__main__":
    main()
