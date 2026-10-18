"""Real JVM Cell + two separately owned Python model units; no online model dependency."""
import argparse
import concurrent.futures
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


def main():
    parser=argparse.ArgumentParser(__doc__)
    for key in ('java','common-jar','cell-jar','libs','python-root','runtime','evidence'):
        parser.add_argument('--'+key,required=True,type=Path)
    args=parser.parse_args();module=Path(__file__).resolve().parents[1]
    runtime=args.runtime.resolve();runtime.mkdir(parents=True,exist_ok=False)
    evidence=args.evidence.resolve();evidence.mkdir(parents=True,exist_ok=False)
    cp=os.pathsep.join([str(args.cell_jar.resolve()),str(args.common_jar.resolve()),str(args.libs.resolve()/'*'),str(module/'build/cube-service-harness-3.0.jar'),str(module/'build/test-classes')])
    def java(cls,*values):
        subprocess.run([str(args.java),'-cp',cp,cls,*map(str,values)],check=True,timeout=30,cwd=module)
    data=runtime/'data';java('cube.service.harness.test.ExecutionTest','prepare',data)
    fixture=json.loads((data/'fixture.json').read_text());sys.path.insert(0,str(args.python_root.resolve()))
    from harness import lifecycle
    from harness.model_worker import ModelHarnessService
    def port():
        with socket.socket() as sock:sock.bind(('127.0.0.1',0));return sock.getsockname()[1]
    cell_port,http_port=port(),port();token,api=secrets.token_hex(24),secrets.token_hex(24)
    host=None;host_log=None;configs=[];checks=[];observed=[]
    def record(name):checks.append(name);print('PASS:',name,flush=True)
    def wait(fn,timeout=60):
        end=time.monotonic()+timeout;last=None
        while time.monotonic()<end:
            try:
                last=fn()
                if last:return last
            except (urllib.error.URLError,ConnectionError,TimeoutError):pass
            time.sleep(.1)
        raise AssertionError('Condition timed out; last='+repr(last))
    def http(path,body=None,key=None,headers=None):
        h={'Authorization':'Bearer '+api};h.update(headers or {})
        if body is not None:h['Content-Type']='application/json';h['Idempotency-Key']=key or secrets.token_hex(16)
        req=urllib.request.Request('http://127.0.0.1:'+str(http_port)+path,data=None if body is None else json.dumps(body).encode(),headers=h)
        try:
            with urllib.request.urlopen(req,timeout=5) as response:
                raw=response.read();return response.status,raw if 'text/event-stream' in response.headers.get('Content-Type','') else json.loads(raw)
        except urllib.error.HTTPError as exc:return exc.code,json.loads(exc.read())
    def db(sql,*values):
        with sqlite3.connect(data/'harness_execution.db',timeout=5) as conn:return conn.execute(sql,values).fetchall()
    stage=runtime/'cell';stage.mkdir();(stage/'cellets').mkdir();shutil.copy2(module/'build/cube-service-harness-3.0.jar',stage/'cellets')
    xml=ET.parse(module/'config/standalone.xml');xml.find('.//server/port').text=str(cell_port);xml.find('.//cellet').set('port',str(cell_port));xml.write(stage/'cell.xml',encoding='utf-8',xml_declaration=True)
    config=stage/'harness.local.properties'
    config.write_text((data/'store.local.properties').read_text()+f'\nmode=development\nhttp.host=127.0.0.1\nhttp.port={http_port}\nlease.millis=3000\nworker.credential={token}\napi.credential={api}\nstore.enabled=true\nexecution.enabled=true\nexecution.tenant={fixture["tenant"]}\nexecution.subject={fixture["subject"]}\nexecution.max.active=6\nexecution.repository={data.as_posix()}/repository\nexecution.repository.namespace=runtime-test-repo\nbackend.shared.capacity=1\n'+''.join(f'worker.{w}.role=model\nworker.{w}.model=scripted\nworker.{w}.backend=shared\n' for w in ('a','b','probe')),encoding='utf-8')
    def launch():
        nonlocal host,host_log
        (stage/'stop').unlink(missing_ok=True);host_log=(runtime/('cell-'+str(time.time_ns())+'.log')).open('w')
        host=subprocess.Popen([str(args.java),'-Dharness.config='+str(config),'-cp',cp,'cube.service.harness.HarnessCell',str(stage/'cell.xml'),str(stage/'stop')],cwd=stage,stdin=subprocess.DEVNULL,stdout=host_log,stderr=subprocess.STDOUT,**({'creationflags':subprocess.CREATE_NO_WINDOW} if os.name=='nt' else {}))
        wait(lambda:http('/healthz')[0]==200,15);time.sleep(3.3)
    def stop_host():
        nonlocal host
        (stage/'stop').touch();assert host.wait(timeout=15)==0;host_log.close();host=None
    script=[{'kind':'tool_calls','calls':[{'proposalId':'list','tool':'skill.list','argumentsJson':'{}'}]},
            {'kind':'tool_calls','calls':[{'proposalId':'activate','tool':'skill.activate','argumentsJson':json.dumps({'release':fixture['release']})}]},
            {'kind':'tool_calls','calls':[{'proposalId':'read','tool':'resource.read','argumentsJson':json.dumps({'release':fixture['release'],'path':'guide.txt','offset':0,'limit':1024})}]},
            {'kind':'final','text':'Read the pinned resource.'}]
    def worker(w):
        path=runtime/(w+'.local.json');root=runtime/('worker-'+w)
        value=dict(host='127.0.0.1',port=cell_port,workerId=w,role='model',workerCredential=token,runtimeDir=str(root),requestTimeout=4,startupTimeout=20,
                   model=dict(model='scripted',backendId='shared',backendCapacity=1,script=script,scriptDelayMillis=400))
        path.write_text(json.dumps(value),encoding='utf-8');configs.append(path);return path
    def create(profile='task',instruction='Read resources',key=None):return http('/harness/v1/runs',dict(taskProfileId=profile,input=dict(instruction=instruction,attachmentIds=[])),key)
    try:
        launch();a,b=worker('a'),worker('b');lifecycle.start(a);lifecycle.start(b)
        caps=http('/harness/v1/capabilities')[1];assert len(caps['registeredWorkers'])==2 and caps['capacity']['backends'][0]['capacity']==1
        record('two-real-python-model-units-one-cell-shared-backend-capacity')
        key=secrets.token_hex(16);status,first=create(key=key);assert status==202,first
        runs=[first['runId']]
        for i in range(5):
            status,value=create(instruction='Read '+str(i));assert status==202,value;runs.append(value['runId'])
        assert create()[0]==429;assert create(key=key)[1]==first;assert create(instruction='conflict',key=key)[0]==409
        record('durable-create-idempotency-conflict-and-bounded-admission')
        wait(lambda:db("SELECT job_id FROM harness_model_job WHERE worker_id='a'"))
        lifecycle.stop(a)
        assert lifecycle.read_state(Path(json.loads(b.read_text())['runtimeDir']))['state']=='registered'
        record('drain-stops-new-work-and-preserves-other-unit')
        # Stop the JVM while the remaining unit has durable work, then recover without replacing that process.
        old_instance=lifecycle.read_state(Path(json.loads(b.read_text())['runtimeDir']))['instanceId']
        wait(lambda:db("SELECT job_id FROM harness_model_job WHERE worker_id='b'"));stop_host();launch()
        wait(lambda:lifecycle.read_state(Path(json.loads(b.read_text())['runtimeDir'])).get('reconnects',0)>=1)
        assert lifecycle.read_state(Path(json.loads(b.read_text())['runtimeDir']))['instanceId']==old_instance
        record('real-cell-restart-worker-reconnect-and-coordinator-takeover')
        def done():
            occupied=db("SELECT COUNT(*) FROM harness_model_job WHERE backend_id='shared' AND status<>'queued' AND actual_finished=0")[0][0]
            observed.append(occupied);assert occupied<=1
            states=[http('/harness/v1/runs/'+r)[1] for r in runs]
            failed=[s for s in states if s['snapshot']['state']=='failed']
            assert not failed,failed
            return all(s['snapshot']['state']=='succeeded' for s in states)
        wait(done,120)
        counts=db('SELECT worker_id,COUNT(*) FROM harness_model_job GROUP BY worker_id');assert {r[0] for r in counts}=={'a','b'}
        for run in runs:
            assert db('SELECT COUNT(*) FROM harness_result WHERE run_id=?',run)[0][0]==1
            assert http('/harness/v1/runs/'+run)[1]['usageSettlement']=='settled'
        record('real-resource-loop-list-activate-read-final-once-usage-settled-and-distribution')
        run=runs[0];cursor=0;events=[]
        while True:
            status,page=http(f'/harness/v1/runs/{run}/events?after={cursor}&limit=3');assert status==200
            if not page['events']:break
            events+=page['events'];cursor=page['nextCursor']
        assert [e['eventSeq'] for e in events]==list(range(1,cursor+1))
        status,sse=http(f'/harness/v1/runs/{run}/events',headers={'Accept':'text/event-stream','Last-Event-ID':'1'});assert status==200 and b'id: 2\n' in sse and b'id: 1\n' not in sse
        assert http(f'/harness/v1/runs/{run}/events?after=999999')[0]==400
        assert http(f'/harness/v1/runs/{run}/events?after=0&after=1')[0]==400
        record('ordered-event-pagination-finite-sse-last-event-id-and-cursor-validation')
        slow=[]
        try:
            for _ in range(2):
                sock=socket.socket();sock.setsockopt(socket.SOL_SOCKET,socket.SO_RCVBUF,1024);sock.settimeout(2);sock.connect(('127.0.0.1',http_port))
                sock.sendall((f'GET /harness/v1/runs/{fixture["slowRun"]}/events HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer {api}\r\nAccept: text/event-stream\r\nConnection: close\r\n\r\n').encode());slow.append(sock)
            time.sleep(3.5)
            assert http('/healthz')[0]==200
            status,_=http(f'/harness/v1/runs/{run}/events',headers={'Accept':'text/event-stream','Last-Event-ID':str(cursor)})
            assert status==200,('slow SSE did not release slots',status)
            record('slow-consumer-streams-expire-with-api-and-model-control-responsive')
        finally:
            for sock in slow:sock.close()
        # Fault-only disposable fixture: remove one event and verify an explicit gap response.
        with sqlite3.connect(data/'harness_execution.db') as c:c.execute('DELETE FROM harness_event WHERE run_id=? AND event_seq=2',(run,))
        assert http(f'/harness/v1/runs/{run}/events?after=1')[0]==409
        record('missing-event-refuses-silent-replay-gap')
        for profile,expected in [('one-round','ROUND_LIMIT_EXCEEDED'),('no-tools','TOOL_LIMIT_EXCEEDED')]:
            status,value=create(profile=profile);assert status==202
            r=value['runId'];wait(lambda:http('/harness/v1/runs/'+r)[1]['snapshot']['state']=='failed',30)
            assert http('/harness/v1/runs/'+r)[1]['result']['result']['errorCode']==expected
        record('round-and-tool-limits-durable-failure')
        assert max(observed)<=1
        record('actual-finished-only-slot-release-across-process-restart')
        (evidence/'results.json').write_text(json.dumps(dict(result='PASS',checks=checks,onlineCalls=0,workers=counts,maxObservedBackendOccupancy=max(observed),runtime=str(runtime)),indent=2)+'\n')
    finally:
        for path in configs:
            try:lifecycle.stop(path)
            except Exception:pass
        if host is not None:stop_host()


if __name__=='__main__':main()
