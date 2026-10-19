"""Freeze a local SLO, then exercise a dedicated Cell and multiple scripted Python processes.

No production addresses, credentials or existing runtime directories are accepted.
Linux/KVM, real model quality and old production probes remain separate gates.
"""
import argparse
import concurrent.futures
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import secrets
import shutil
import signal
import socket
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from dispatcher_fixture import DispatcherFixture


MODULE = Path(__file__).resolve().parents[1]


def utc():
    return datetime.now(timezone.utc).isoformat()


def save(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def percentile(values, p):
    return sorted(values)[max(0, math.ceil(len(values) * p) - 1)] if values else None


def process_usage(pid):
    """Read only this drill's child PIDs; RSS and cumulative process CPU seconds."""
    if os.name != "nt":
        proc = Path("/proc") / str(pid)
        fields = (proc / "stat").read_text().split(") ", 1)[1].split()
        rss = int((proc / "statm").read_text().split()[1]) * os.sysconf("SC_PAGE_SIZE")
        return rss, (int(fields[11])+int(fields[12])) / os.sysconf("SC_CLK_TCK")
    import ctypes
    from ctypes import wintypes
    class Counters(ctypes.Structure):
        _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD)] + [
            (name, ctypes.c_size_t) for name in ("PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage",
                "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage", "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage", "PrivateUsage")]
    kernel, psapi = ctypes.WinDLL("kernel32", use_last_error=True), ctypes.WinDLL("psapi", use_last_error=True)
    kernel.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.CloseHandle.argtypes = [wintypes.HANDLE]
    kernel.GetProcessTimes.argtypes = [wintypes.HANDLE] + [ctypes.POINTER(ctypes.c_ulonglong)]*4
    psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
    handle = kernel.OpenProcess(0x1010, False, pid)
    if not handle:
        return None
    try:
        counters = Counters(); counters.cb = ctypes.sizeof(counters)
        times = [ctypes.c_ulonglong() for _ in range(4)]
        if not psapi.GetProcessMemoryInfo(handle, ctypes.byref(counters), counters.cb):
            return None
        if not kernel.GetProcessTimes(handle, *(ctypes.byref(t) for t in times)):
            return None
        return counters.WorkingSetSize, (times[2].value+times[3].value)/10000000
    finally:
        kernel.CloseHandle(handle)


def freeze(args):
    value = json.loads(args.profile.read_bytes())
    assert value["thresholds"] and all(type(n) in (int, float) and n > 0 for n in value["thresholds"].values())
    value.update(frozenAt=utc(), profileSha256=sha(args.profile), hardware=dict(
        os=platform.platform(), machine=platform.machine(), processor=platform.processor(),
        logicalCPUs=os.cpu_count(), python=sys.version))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as out:
        json.dump(value, out, ensure_ascii=False, indent=2)
        out.write("\n")
    print("Frozen local-only SLO:", sha(args.output))


class Drill:
    def __init__(self, args):
        self.a = args
        self.slo = json.loads(args.slo.read_bytes())
        assert self.slo.get("frozenAt") and self.slo.get("hardware")
        self.root = args.runtime.resolve()
        self.root.mkdir(parents=True, exist_ok=False)
        self.e = args.evidence.resolve()
        self.e.mkdir(parents=True, exist_ok=False)
        shutil.copy2(args.slo, self.e / "frozen-slo.json")
        self.slo_hash = sha(args.slo)
        self.cp = os.pathsep.join(map(str, (args.cell_jar.resolve(), args.common_jar.resolve(),
            args.libs.resolve() / "*", MODULE / "build/cube-service-harness-3.0.jar", MODULE / "build/test-classes")))
        sys.path.insert(0, str(args.python_root.resolve()))
        from harness import lifecycle
        self.lifecycle = lifecycle
        self.api, self.token, self.admin = [secrets.token_hex(24) for _ in range(3)]
        self.process, self.log = None, None
        self.gateway = None
        self.paths, self.commands, self.checks, self.metrics, self.faults = {}, [], [], {}, []
        self.samples, self.http_samples, self.runs = [], [], {}
        self.stop_sample = threading.Event()
        self.sampler = None
        self.cell_port, self.http_port = self.port(), self.port()
        self.stage = self.root / "cell"
        self.config = self.stage / "harness.local.properties"
        self.sampling_errors = []
        self.pids, self.cpu = {}, {}

    @staticmethod
    def port():
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            return sock.getsockname()[1]

    def check(self, name):
        self.checks.append(name)
        print("PASS:", name, flush=True)

    def command(self, argv, name, expected=0):
        row = dict(argv=list(map(str, argv)), startedAt=utc(), log=name + ".log")
        with (self.e / row["log"]).open("wb") as out:
            child = subprocess.run(row["argv"], stdout=out, stderr=subprocess.STDOUT, timeout=60)
        row.update(exitCode=child.returncode, finishedAt=utc(), expectedExitCode=expected)
        self.commands.append(row)
        save(self.e / "commands.json", self.commands)
        assert child.returncode == expected, (name, child.returncode)

    def wait(self, fn, seconds=45):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            try:
                value = fn()
                if value:
                    return value
            except (ConnectionError, urllib.error.URLError, TimeoutError):
                pass
            time.sleep(.1)
        raise AssertionError("condition timed out: " + getattr(fn, "__name__", "condition"))

    def http(self, path, body=None, key=None, admin=False, method=None):
        started = time.monotonic()
        headers = {"Authorization": "Bearer " + (self.admin if admin else self.api)}
        if body is not None:
            headers.update({"Content-Type": "application/json", "Idempotency-Key": key or secrets.token_hex(16)})
        request = urllib.request.Request(self.gateway.url(path, self.http_port) if self.gateway else "http://127.0.0.1:" + str(self.http_port) + path,
            data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                status, value = response.status, json.loads(response.read())
        except urllib.error.HTTPError as exc:
            status, value = exc.code, json.loads(exc.read())
        self.http_samples.append(dict(path=path, method=request.get_method(), status=status,
            elapsedMillis=(time.monotonic()-started)*1000, at=utc()))
        return status, value

    def db(self, sql, *values):
        with sqlite3.connect(self.root / "data/harness_execution.db", timeout=3) as c:
            return c.execute(sql, values).fetchall()

    def sample(self):
        while not self.stop_sample.is_set():
            try:
                occupied = dict(self.db("SELECT worker_id,COUNT(*) FROM harness_model_job WHERE actual_finished=0 AND status<>'queued' GROUP BY worker_id"))
                resources = {}
                for pid, name in tuple(self.pids.items()):
                    try:
                        usage = process_usage(pid)
                    except (OSError, ValueError, IndexError):
                        usage = None
                    if usage:
                        resources[str(pid)] = dict(name=name, rssBytes=usage[0], cpuSeconds=usage[1])
                        self.cpu[pid] = usage[1]
                self.samples.append(dict(at=utc(), occupied=occupied, backendOccupied=sum(occupied.values()), resources=resources,
                    aggregateRssBytes=sum(r['rssBytes'] for r in resources.values()), sampledCpuSeconds=sum(self.cpu.values()),
                    queued=self.db("SELECT COUNT(*) FROM harness_model_job WHERE status='queued'")[0][0],
                    active=self.db("SELECT COUNT(*) FROM harness_run WHERE state IN ('queued','running','reconciling','waiting_input','waiting_approval','cancelling')")[0][0]))
            except sqlite3.Error as exc:
                self.sampling_errors.append(type(exc).__name__)
            self.stop_sample.wait(.1)

    def setup(self):
        self.command([self.a.java, "-cp", self.cp, "cube.service.harness.test.ExecutionTest", "prepare", self.root / "data"], "prepare")
        self.fixture = json.loads((self.root / "data/fixture.json").read_bytes())
        (self.stage / "cellets").mkdir(parents=True)
        shutil.copy2(MODULE / "build/cube-service-harness-3.0.jar", self.stage / "cellets")
        xml = ET.parse(MODULE / "config/standalone.xml")
        xml.find(".//server/port").text = str(self.cell_port)
        xml.find(".//cellet").set("port", str(self.cell_port))
        xml.write(self.stage / "cell.xml", encoding="utf-8", xml_declaration=True)
        self.config.write_text((self.root / "data/store.local.properties").read_text() + f"""
http.host=127.0.0.1
http.port={self.http_port}
lease.millis=3000
worker.credential={self.token}
api.credential={self.api}
admission.admin.credential={self.admin}
store.enabled=true
execution.enabled=true
execution.accepting=false
execution.tenant={self.fixture['tenant']}
execution.subject={self.fixture['subject']}
execution.max.active={self.slo['loadProfile']['maxActive']}
execution.repository={self.root.as_posix()}/data/repository
execution.repository.namespace=runtime-test-repo
backend.shared.capacity={self.slo['loadProfile']['sharedBackendCapacity']}
""" + "".join(f"worker.{w}.role=model\nworker.{w}.model=scripted\nworker.{w}.backend=shared\n" for w in ("slow", "fast", "extra")), encoding="utf-8")
        for name, delay in self.slo["loadProfile"]["workerDelayMillis"].items():
            path = self.root / (name + ".local.json")
            save(path, dict(host="127.0.0.1", port=self.cell_port, workerId=name, role="model",
                workerCredential=self.token, runtimeDir=str(self.root / ("worker-"+name)), requestTimeout=2,
                startupTimeout=20, model=dict(model="scripted", backendId="shared", backendCapacity=2,
                    scriptDelayMillis=delay, script=[dict(kind="final", text="N16 scripted fixture output")])))
            self.paths[name] = path
        self.launch()
        self.sampler = threading.Thread(target=self.sample, daemon=True)
        self.sampler.start()

    def launch(self):
        (self.stage / "stop").unlink(missing_ok=True)
        self.log = (self.root / ("cell-" + str(time.time_ns()) + ".log")).open("w")
        self.process = subprocess.Popen([str(self.a.java), "-Xmx128m", "-Dharness.config=" + str(self.config),
            "-cp", self.cp, "cube.service.harness.HarnessCell", str(self.stage / "cell.xml"), str(self.stage / "stop")],
            cwd=self.stage, stdin=subprocess.DEVNULL, stdout=self.log, stderr=subprocess.STDOUT,
            **({"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {}))
        self.pids[self.process.pid] = "service-harness-jvm"
        self.wait(lambda: self.http("/healthz")[0] == 200, 20)
        if self.gateway is None:
            self.gateway = DispatcherFixture(self.a.java, self.cp, self.cell_port, self.api, self.root)
            self.pids[self.gateway.process.pid] = "dispatcher-jvm"
        self.gateway.ready()

    def stop_java(self, crash=False):
        if self.process is None:
            return
        if crash:
            self.process.kill()
        else:
            (self.stage / "stop").touch()
        try:
            code = self.process.wait(timeout=20)
            if not crash:
                assert code == 0, code
        finally:
            if self.process.poll() is None:
                self.process.kill()
                self.process.wait(timeout=5)
            self.log.close()
            self.process = None

    def accepting(self, value):
        status, result = self.http("/harness-admin/v1/admission", dict(accepting=value), admin=True)
        assert status == 200 and result["accepting"] == value, (status, result)
        return result

    def start_worker(self, name):
        result = self.lifecycle.start(self.paths[name])
        self.pids[result['pid']] = 'python-' + name
        return result

    def create(self, text="N16", key=None):
        started = time.monotonic()
        status, value = self.http("/harness/v1/runs", dict(taskProfileId="task", input=dict(instruction=text, attachmentIds=[])), key)
        if status == 202:
            self.runs.setdefault(value["runId"], dict(started=started, label=text))
        return status, value

    def completed(self, runs):
        done = True
        for run in runs:
            status, row = self.http("/harness/v1/runs/" + run)
            assert status == 200, row
            state = row["snapshot"]["state"]
            assert state not in ("failed", "timed_out"), row
            if state == "succeeded":
                self.runs[run].setdefault("totalMillis", (time.monotonic()-self.runs[run]["started"])*1000)
            else:
                done = False
        return done

    def batch(self, count, name):
        started = time.monotonic()
        all_runs = []
        for wave in range(0, count, self.slo["loadProfile"]["maxActive"]):
            with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
                replies = list(pool.map(lambda n: self.create(f"{name}-{n}"), range(wave, min(count, wave+8))))
            assert all(code == 202 for code, _ in replies), replies
            current = [row["runId"] for _, row in replies]
            all_runs += current
            self.wait(lambda: self.completed(current), 45)
        duration = time.monotonic()-started
        self.metrics[name] = dict(runs=count, durationSeconds=duration, throughput=count/duration,
            totalP50Millis=percentile([self.runs[r]["totalMillis"] for r in all_runs], .5),
            totalP95Millis=percentile([self.runs[r]["totalMillis"] for r in all_runs], .95))
        return all_runs

    def exercise(self):
        self.start_worker("slow")
        assert self.create()[0] == 503
        assert self.http("/readyz")[0] == 503
        assert self.http("/harness-admin/v1/admission")[0] == 403
        assert self.http("/harness-admin/v1/admission", dict(accepting="true"), admin=True)[0] == 400
        self.accepting(True)
        baseline = self.batch(self.slo["loadProfile"]["baselineRuns"], "baseline")
        self.start_worker("fast")
        self.start_worker("extra")
        self.batch(self.slo["loadProfile"]["scaledRuns"], "scaled")
        self.check("one JVM, three real Python model units, unequal speeds, bounded shared backend, measured scale effect")
        # Scale down and drain one unit; the remaining units keep working.
        self.lifecycle.stop(self.paths["extra"])
        self.batch(4, "scale-down")
        self.check("unit drain and scale-down preserve other unit progress")
        # Explicit overloaded admission, then control request while queued work remains.
        self.lifecycle.stop(self.paths["fast"])
        self.lifecycle.stop(self.paths["slow"])
        with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
            replies = list(pool.map(lambda n: self.create("pressure-"+str(n)), range(24)))
        assert sum(code == 202 for code, _ in replies) == 8, replies
        assert all(code in (202, 429) for code, _ in replies), replies
        pressure = [row["runId"] for code, row in replies if code == 202]
        for _ in range(8):
            assert self.http('/harness/v1/runs/'+baseline[0]+'/events?after=0&limit=10')[0] == 200
        r = pressure[-1]
        _, row = self.http("/harness/v1/runs/"+r)
        start = time.monotonic()
        status, result = self.http("/harness/v1/runs/"+r+"/cancel", dict(expectedVersion=row["snapshot"]["version"]))
        assert status == 202, result
        self.wait(lambda: self.http("/harness/v1/runs/"+r)[1]["snapshot"]["state"] in ("cancelled", "failed"))
        self.metrics["cancelLogicalMillis"] = (time.monotonic()-start)*1000
        self.accepting(False)
        assert self.create()[0] == 503
        assert not self.http("/harness-admin/v1/admission", admin=True)[1]["drained"]
        self.start_worker("slow")
        self.start_worker("fast")
        self.wait(lambda: self.completed(pressure[:-1]), 45)
        self.wait(lambda: self.http("/harness-admin/v1/admission", admin=True)[1]["drained"])
        self.check("24 concurrent submissions: 8 accepted/16 backpressured; cancel and receipt channels progress while closed")
        self.accepting(True)
        # Full JVM crash while queued/running, journals and Python processes retained.
        code, receipt = self.create("JVM crash recovery")
        assert code == 202
        run = receipt["runId"]
        self.wait(lambda: self.db("SELECT job_id FROM harness_model_job WHERE run_id=?", run))
        before = {w: self.lifecycle.read_state(self.root / ("worker-"+w))["instanceId"] for w in ("slow", "fast")}
        start = time.monotonic()
        self.stop_java(crash=True)
        time.sleep(.4)  # short, bounded transport outage
        self.launch()
        assert self.create()[0] == 503  # startup configuration remains closed
        self.wait(lambda: self.completed([run]), 30)
        self.metrics["recoveryMillis"] = (time.monotonic()-start)*1000
        assert all(self.lifecycle.read_state(self.root / ("worker-"+w))["instanceId"] == before[w] for w in before)
        self.faults.append(dict(fault="JVM crash/Cell connection loss", result="PASS", recoveryMillis=self.metrics["recoveryMillis"], retry="persisted same job only; unique final verified"))
        self.check("hard JVM crash, same Python instances reconnect, closed startup, durable final and lease takeover")
        # Active model cancellation measures physical completion independently.
        self.lifecycle.stop(self.paths["fast"])
        self.lifecycle.stop(self.paths["slow"])
        config = json.loads(self.paths["slow"].read_bytes())
        config["model"]["scriptDelayMillis"] = 4000
        save(self.paths["slow"], config)
        self.start_worker("slow")
        self.accepting(True)
        _, value = self.create("physical cancellation")
        run = value["runId"]
        self.wait(lambda: self.db("SELECT job_id FROM harness_model_job WHERE run_id=? AND status='assigned'", run))
        _, row = self.http("/harness/v1/runs/"+run)
        start = time.monotonic()
        status, value = self.http("/harness/v1/runs/"+run+"/cancel", dict(expectedVersion=row["snapshot"]["version"]))
        assert status == 202, value
        self.wait(lambda: self.db("SELECT job_id FROM harness_model_job WHERE run_id=? AND actual_finished=1", run), 20)
        self.metrics["modelFinishMillis"] = (time.monotonic()-start)*1000
        self.wait(lambda: self.http("/harness-admin/v1/admission", admin=True)[1]["drained"], 20)
        self.accepting(False)
        self.check("active cancellation separately observes actual_finished and strict drain")
        start = time.monotonic()
        def acked():
            for name in ("slow", "fast", "extra"):
                with sqlite3.connect(self.root / ("worker-"+name) / "model-journal.sqlite") as c:
                    if c.execute("SELECT COUNT(*) FROM jobs WHERE ack=0").fetchone()[0]:
                        return False
            return True
        self.wait(acked)
        self.metrics["ackBacklogMillis"] = (time.monotonic()-start)*1000
        # Controlled heap exhaustion in a separate bounded JVM, never on the host or service JVM.
        self.command([self.a.java, "-Xmx16m", "-cp", self.cp, "cube.service.harness.test.ResilienceTest", "oom"], "child-heap-oom", 73)
        assert "N16_CONTROLLED_CHILD_HEAP_OOM" in (self.e/"child-heap-oom.log").read_text()
        self.faults.append(dict(fault="bounded 16 MiB child Java heap OOM", result="PASS", nativeOSOOM="NOT_RUN"))
        assert self.http("/healthz")[0] == 200

    def evaluate(self):
        assert sha(self.a.slo) == self.slo_hash, "SLO changed during run"
        t = self.slo["thresholds"]
        rows = []
        def metric(name, measured, threshold, samples, comparator="max"):
            passed = measured is not None and samples > 0 and (measured <= threshold if comparator == "max" else measured >= threshold)
            rows.append(dict(name=name, threshold=threshold, comparator=comparator, loadProfile=self.slo["loadProfile"],
                sampleCount=samples, window=dict(start=self.started, end=utc()), hardware=self.slo["hardware"],
                measured=measured, verdict="PASS" if passed else "FAIL"))
        for label in ('create', 'query', 'events'):
            def selected(s):
                path = s['path'].split('?')[0]
                return (s['method'] == 'POST' and path == '/harness/v1/runs' if label == 'create'
                    else s['method'] == 'GET' and path.startswith('/harness/v1/runs/') and (path.endswith('/events') if label == 'events' else len(path.split('/')) == 5))
            values = [s['elapsedMillis'] for s in self.http_samples if selected(s)]
            metric(label+"P95Millis", percentile(values, .95), t["apiP95Millis"], len(values))
        total = [r["totalMillis"] for r in self.runs.values() if "totalMillis" in r]
        metric("runP95Millis", percentile(total, .95), t["runP95Millis"], len(total))
        queue = [r[0] for r in self.db("SELECT dispatched_at-created_at FROM harness_model_job WHERE dispatched_at IS NOT NULL")]
        metric("queueP95Millis", percentile(queue, .95), t["queueP95Millis"], len(queue))
        self.metrics["queueP50Millis"] = percentile(queue, .5)
        for name in ("cancelLogicalMillis", "modelFinishMillis", "recoveryMillis", "ackBacklogMillis"):
            metric(name, self.metrics[name], t[name], 1)
        ratio = self.metrics["scaled"]["throughput"] / self.metrics["baseline"]["throughput"]
        metric("scaleThroughputRatio", ratio, t["scaleThroughputRatioMin"], self.slo["loadProfile"]["scaledRuns"], "min")
        metric("sharedOccupancyMax", max(s["backendOccupied"] for s in self.samples), t["sharedOccupancyMax"], len(self.samples))
        metric("workerOccupancyMax", max(max(s["occupied"].values(), default=0) for s in self.samples), t["workerOccupancyMax"], len(self.samples))
        metric("activeRunsMax", max(s["active"] for s in self.samples), t["activeRunsMax"], len(self.samples))
        resource_samples = [s for s in self.samples if s['resources']]
        metric('peakAggregateRssBytes', max((s['aggregateRssBytes'] for s in resource_samples), default=None), t['peakAggregateRssBytes'], len(resource_samples))
        metric('sampledCpuSeconds', max((s['sampledCpuSeconds'] for s in resource_samples), default=None), t['sampledCpuSeconds'], len(resource_samples))
        for run in self.runs:
            assert self.db("SELECT COUNT(*) FROM harness_result WHERE run_id=?", run) == [(1,)], run
            assert self.db("SELECT COUNT(*) FROM harness_model_job WHERE run_id=?", run)[0][0] <= 1, run
        workers = self.db("SELECT worker_id,COUNT(*),AVG(dispatched_at-created_at),AVG(finished_at-dispatched_at) FROM harness_model_job WHERE worker_id IS NOT NULL GROUP BY worker_id")
        assert set(w[0] for w in workers) == {"slow", "fast", "extra"}
        worker_metrics = []
        for w, n, q, x in workers:
            latencies = self.db('SELECT dispatched_at-created_at,finished_at-dispatched_at FROM harness_model_job WHERE worker_id=? AND finished_at IS NOT NULL', w)
            worker_metrics.append(dict(workerId=w, completed=n, avgQueueMillis=q, avgExecutionMillis=x,
                queueP95Millis=percentile([v[0] for v in latencies], .95), executionP95Millis=percentile([v[1] for v in latencies], .95),
                maxOccupied=max(s['occupied'].get(w, 0) for s in self.samples)))
        save(self.e / "slo.json", dict(scope=self.slo["scope"], frozenSha256=self.slo_hash, metrics=rows,
            statistics=self.metrics, workers=worker_metrics,
            externalGates=self.slo["externalGates"], verdict="PASS" if all(r["verdict"] == "PASS" for r in rows) else "FAIL", G4="NOT_CERTIFIED"))
        save(self.e / "samples.json", dict(samples=self.samples, samplingErrors=self.sampling_errors, http=self.http_samples))
        save(self.e / "fault-matrix.json", self.faults)
        assert all(r["verdict"] == "PASS" for r in rows), [(r["name"], r["measured"]) for r in rows if r["verdict"] != "PASS"]
        self.check("frozen local SLO, unique job/final, physical occupancy and journal ACK invariants")

    def cleanup(self):
        errors = []
        for path in reversed(list(self.paths.values())):
            try:
                self.lifecycle.stop(path)
            except Exception as exc:
                errors.append(type(exc).__name__)
        self.stop_java()
        if self.gateway is not None: self.gateway.close()
        self.stop_sample.set()
        if self.sampler:
            self.sampler.join(timeout=5)
        for source in [self.root / "data/harness_execution.db", *self.root.glob("worker-*/model-journal.sqlite"), *self.root.glob("cell-*.log")]:
            target = self.e / source.relative_to(self.root)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        save(self.e / "cleanup.json", dict(errors=errors, processesRemaining=[name for name in self.paths if self.lifecycle.running(self.root/("worker-"+name))]))
        save(self.e / "samples.json", dict(samples=self.samples, samplingErrors=self.sampling_errors, http=self.http_samples))
        save(self.e / "fault-matrix.json", self.faults)
        assert not errors

    def run(self):
        self.started = utc()
        result = "FAIL"
        try:
            self.setup()
            self.exercise()
            self.evaluate()
            result = "PASS_LOCAL_ONLY"
        finally:
            try:
                self.cleanup()
            finally:
                save(self.e / "results.json", dict(result=result, checks=self.checks, externalGates=self.slo["externalGates"],
                    G4="NOT_CERTIFIED", scope=self.slo["scope"], startedAt=self.started, finishedAt=utc()))


def main():
    parser = argparse.ArgumentParser(__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    p = sub.add_parser("freeze")
    p.add_argument("--profile", type=Path, default=MODULE / "scripts/tests/fixtures/local-slo.json")
    p.add_argument("--output", type=Path, required=True)
    p = sub.add_parser("run")
    for name in ("java", "python-root", "runtime", "evidence", "libs", "common-jar", "cell-jar", "slo"):
        p.add_argument("--"+name, type=Path, required=True)
    args = parser.parse_args()
    if args.action == "freeze":
        freeze(args)
    else:
        Drill(args).run()


if __name__ == "__main__":
    main()
