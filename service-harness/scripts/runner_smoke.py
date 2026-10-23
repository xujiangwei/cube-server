"""One real Java Cell, scripted model, two independent CPU runners and a private MockConsole."""
import argparse
import concurrent.futures
import http.server
import json
import os
from pathlib import Path
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


def main_impl():
    parser=argparse.ArgumentParser(__doc__)
    for k in ("java","python-root","runtime","evidence","libs","common-jar","cell-jar"):
        parser.add_argument("--"+k,required=True,type=Path)
    a=parser.parse_args();module=Path(__file__).resolve().parents[1]
    root=a.runtime.resolve();root.mkdir(parents=True,exist_ok=False)
    evidence=a.evidence.resolve();evidence.mkdir(parents=True,exist_ok=False)
    cp=os.pathsep.join(map(str,(a.cell_jar.resolve(),a.common_jar.resolve(),a.libs.resolve()/"*",module/"build/cube-service-harness-3.0.jar",module/"build/test-classes")))
    subprocess.run([str(a.java),"-cp",cp,"cube.service.harness.test.ToolTest","prepare",str(root/"data")],check=True,timeout=40)
    f=json.loads((root/"data/tool-fixture.json").read_text())
    sys.path.insert(0,str(a.python_root.resolve()))
    from harness import lifecycle
    from harness.runner_worker import RunnerHarnessService
    def port():
        with socket.socket() as s:s.bind(("127.0.0.1",0));return s.getsockname()[1]
    cell_port,http_port=port(),port();credential,api,private=secrets.token_hex(24),secrets.token_hex(24),secrets.token_hex(24)
    upstream=[];checks=[];process=None;gateway=None;paths=[];log=None
    def record(name):checks.append(name);print("PASS:",name,flush=True)
    class Mock(http.server.BaseHTTPRequestHandler):
        def do_POST(self):
            assert self.headers["Authorization"]=="Bearer "+private
            args=json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            upstream.append(args)
            time.sleep(.35)
            data=json.dumps(dict(nodes=[dict(target=t,queriedAt=1234,cpuPercent=7,memoryMiB=100,cookie="must-filter") for t in args["targets"]],token="must-filter")).encode()
            self.send_response(200);self.end_headers();self.wfile.write(data)
        def log_message(self,*_):pass
    mock=http.server.ThreadingHTTPServer(("127.0.0.1",0),Mock)
    thread=threading.Thread(target=mock.serve_forever,daemon=True);thread.start()
    def wait(fn,seconds=45):
        end=time.monotonic()+seconds
        while time.monotonic()<end:
            try:
                value=fn()
                if value:return value
            except (ConnectionError,urllib.error.URLError,TimeoutError):pass
            time.sleep(.1)
        raise AssertionError("condition timed out")
    def http_call(path,body=None):
        request=urllib.request.Request(gateway.url(path,http_port) if gateway else "http://127.0.0.1:"+str(http_port)+path,data=None if body is None else json.dumps(body).encode(),headers={"Authorization":"Bearer "+api,"Content-Type":"application/json","Idempotency-Key":secrets.token_hex(16)})
        with urllib.request.urlopen(request,timeout=5) as x:return json.loads(x.read())
    def db(sql,*values):
        with sqlite3.connect(root/"data/harness_execution.db",timeout=5) as c:return c.execute(sql,values).fetchall()
    def create(text="Query node-a cpu"):
        return http_call("/harness/v1/runs",dict(input=dict(instruction=text,attachmentIds=[])))["runId"]
    stage=root/"cell";(stage/"cellets").mkdir(parents=True)
    shutil.copy2(module/"build/cube-service-harness-3.0.jar",stage/"cellets")
    xml=ET.parse(module/"config/standalone.xml");xml.find(".//listeners/class").text="cube.service.harness.test.FixtureAuthCarpet";xml.find(".//server/port").text=str(cell_port);xml.find(".//cellet").set("port",str(cell_port));xml.write(stage/"cell.xml",encoding="utf-8",xml_declaration=True)
    config=stage/"harness.local.properties"
    config.write_text((root/"data/store.local.properties").read_text()+f"""
http.host=127.0.0.1
http.port={http_port}
lease.millis=3000
worker.credential={credential}
fixture.user.token={api}
store.enabled=true
execution.enabled=true
execution.tools.enabled=true
fixture.user.domain={f['tenant']}
fixture.user.contact={f['subject']}
execution.permissions=skill.list,skill.activate,resource.list,resource.read,resource.search,console.status.read
execution.repository={root.as_posix()}/data/repository
execution.repository.namespace=runtime-test-repo
backend.shared.capacity=1
worker.model.role=model
worker.model.model=scripted
worker.model.backend=shared
worker.a.role=runner
worker.a.capacity=1
worker.b.role=runner
worker.b.capacity=1
runner.grant.key={secrets.token_hex(32)}
broker.node.id=smoke-java
broker.mock.enabled=true
broker.mock.url=http://127.0.0.1:{mock.server_port}/status
broker.mock.token={private}
broker.mock.targets=node-a
""",encoding="utf-8")
    script=[dict(kind="tool_calls",calls=[dict(proposalId="activate",tool="skill.activate",argumentsJson=json.dumps(dict(release=f["release"])))]),
            dict(kind="tool_calls",calls=[dict(proposalId="query",tool="test.status.query",argumentsJson=json.dumps(dict(targets=["node-a"],fields=["cpuPercent"])))]),dict(kind="final",text="node-a CPU is 7 percent (MockConsole)")]
    def worker(name):
        path=root/(name+".local.json")
        value=dict(host="127.0.0.1",port=cell_port,workerId=name,role="model" if name=="model" else "runner",workerCredential=credential,runtimeDir=str(root/("worker-"+name)),requestTimeout=2,startupTimeout=15)
        if name=="model":value["model"]=dict(model="scripted",backendId="shared",backendCapacity=1,script=script)
        else:value["runner"]=dict(type="tool",runtime="trusted-python-v1",capacity=1)
        path.write_text(json.dumps(value),encoding="utf-8");paths.append(path);return path
    try:
        log=(root/"cell.log").open("w")
        process=subprocess.Popen([str(a.java),"-Dharness.config="+str(config),"-cp",cp,"cube.service.harness.HarnessCell",str(stage/"cell.xml"),str(stage/"stop")],cwd=stage,stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,**({"creationflags":subprocess.CREATE_NO_WINDOW} if os.name=="nt" else {}))
        wait(lambda:http_call("/healthz"),15)
        gateway=DispatcherFixture(a.java,cp,cell_port,api,root)
        gateway.ready()
        model,wa,wb=worker("model"),worker("a"),worker("b")
        for path in paths:lifecycle.start(path)
        caps=http_call("/harness/v1/capabilities")
        assert len(caps["registeredWorkers"])==3,caps
        record("one-cell-model-and-two-independent-runner-processes")
        runs=[create(str(i)) for i in range(6)]
        def completed():
            rows=[http_call("/harness/v1/runs/"+r) for r in runs]
            assert not any(r["snapshot"]["state"]=="failed" for r in rows),rows
            counts=db("SELECT worker_id,COUNT(*) FROM harness_tool_job WHERE actual_finished=0 AND status<>'queued' GROUP BY worker_id")
            assert all(n<=1 for _,n in counts),counts
            return all(r["snapshot"]["state"]=="succeeded" for r in rows)
        wait(completed,90)
        counts=dict(db("SELECT worker_id,COUNT(*) FROM harness_tool_job GROUP BY worker_id"));assert set(counts)=={"a","b"},counts
        assert len(upstream)==6,len(upstream)
        assert all(db("SELECT COUNT(*) FROM harness_result WHERE run_id=?",r)[0][0]==1 for r in runs)
        raw=" ".join(x[0] for x in db("SELECT result_json FROM harness_tool_job"));assert "must-filter" not in raw and "memoryMiB" not in raw,raw
        for w in ("a","b"):
            with sqlite3.connect(root/("worker-"+w)/"runner-journal.sqlite") as c:
                assert c.execute("SELECT COUNT(*) FROM calls WHERE acked=0").fetchone()[0]==0
        record("six-real-coordinator-executable-loops-load-distributed-filtered-result-journal-ack-final-once")
        lifecycle.stop(wa)
        r=create("drained a");wait(lambda:http_call("/harness/v1/runs/"+r)["snapshot"]["state"]=="succeeded")
        assert db("SELECT worker_id FROM harness_tool_job WHERE run_id=?",r)==[("b",)]
        record("drain-removes-unit-from-routing-other-runner-continues")
        lifecycle.stop(wb);started=lifecycle.start(wa)
        broken=create("crash during accepted tool")
        job_id=wait(lambda:db("SELECT job_id FROM harness_tool_job WHERE run_id=? AND worker_id='a' AND status='accepted'",broken))[0][0]
        def actually_started():
            with sqlite3.connect(root/"worker-a/runner-journal.sqlite",timeout=5) as journal:
                return journal.execute("SELECT state FROM calls WHERE job_id=?",(job_id,)).fetchone()==("running",)
        wait(actually_started)
        os.kill(started["pid"],signal.SIGTERM if os.name=="nt" else signal.SIGKILL)
        wait(lambda:not lifecycle.running(root/"worker-a"))
        lifecycle.start(wa)
        wait(lambda:db("SELECT job_id FROM harness_tool_job WHERE run_id=? AND status='unknown' AND actual_finished=0",broken))
        caps=wait(lambda:next((w for w in http_call("/harness/v1/capabilities")["registeredWorkers"] if w["workerId"]=="a" and w["occupied"]==1),None))
        assert caps["available"]==0 and not caps["ready"],caps
        record("hard-crash-journal-restart-reports-unknown-no-replay-capacity-quarantined")
        lifecycle.start(wb)
        follow=create("healthy b while a unknown");wait(lambda:http_call("/harness/v1/runs/"+follow)["snapshot"]["state"]=="succeeded")
        assert db("SELECT worker_id FROM harness_tool_job WHERE run_id=?",follow)==[("b",)]
        record("unknown-runner-does-not-block-other-capacity")
        (evidence/"results.json").write_text(json.dumps(dict(result="PASS",checks=checks,workers=counts,upstreamRequests=len(upstream),liveModels=0,productionConsoleCalls=0),indent=2)+"\n")
        # Durable evidence is copied after all child processes stop below.
    finally:
        for path in reversed(paths):
            try:lifecycle.stop(path)
            except Exception as exc:print("cleanup:",type(exc).__name__,flush=True)
        if gateway is not None:gateway.close()
        if process:
            (stage/"stop").touch()
            try:assert process.wait(timeout=15)==0
            finally:
                if process.poll() is None:process.terminate();process.wait(timeout=10)
        if log:log.close()
        mock.shutdown();mock.server_close()
        for name in ("a","b"):
            journal=root/("worker-"+name)/"runner-journal.sqlite"
            if journal.exists():shutil.copy2(journal,evidence/("runner-"+name+"-journal.sqlite"))
        shutil.copy2(root/"data/harness_execution.db",evidence/"harness_execution.db")
        if (root/"cell.log").exists():shutil.copy2(root/"cell.log",evidence/"cell.log")


if __name__=="__main__":main_impl()
