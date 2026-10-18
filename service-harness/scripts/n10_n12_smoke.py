"""Real local Cell/model/Runner processes, N11 controls and N12 files.

Console is a protocol fixture. Optional live model calls are smoke tests only;
they never count toward N10's dedicated-Console 50 x 3 certification.
"""
import argparse
import hashlib
import http.server as http_server
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
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(__doc__)
    for name in ('java', 'python-root', 'runtime', 'evidence', 'libs', 'common-jar', 'cell-jar'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--remote-config', type=Path)
    a = parser.parse_args()
    module = Path(__file__).resolve().parents[1]
    root, evidence = a.runtime.resolve(), a.evidence.resolve()
    root.mkdir(parents=True, exist_ok=False)
    evidence.mkdir(parents=True, exist_ok=False)
    cp = os.pathsep.join(map(str, (a.cell_jar.resolve(), a.common_jar.resolve(), a.libs.resolve() / '*', module / 'build/cube-service-harness-3.0.jar', module / 'build/test-classes')))
    subprocess.run([str(a.java), '-cp', cp, 'cube.service.harness.test.N10N12Fixture', str(root / 'data')], check=True, timeout=40)
    data = root / 'data'
    fixture = json.loads((data / 'n10-n12-fixture.json').read_text())
    sys.path.insert(0, str(a.python_root.resolve()))
    from harness import lifecycle

    def port():
        with socket.socket() as s:
            s.bind(('127.0.0.1', 0))
            return s.getsockname()[1]

    checks, remote_results, configs, upstream = [], [], [], []
    cleanup_before = {}
    cell_port, http_port = port(), port()
    api, worker_key, approver, cookie = [secrets.token_hex(24) for _ in range(4)]
    private_values = [api, worker_key, approver, cookie]
    process, log = None, None

    def record(name):
        checks.append(name)
        print('PASS:', name, flush=True)

    class Console(http_server.BaseHTTPRequestHandler):
        def do_POST(self):
            if self.headers.get('Cookie') != 'CubeConsoleToken=' + cookie:
                self.send_response(401); self.end_headers(); return
            form = urllib.parse.parse_qs(self.rfile.read(int(self.headers['Content-Length'])).decode())
            assert self.path == '/service/status' and form == dict(tag=['node-a'], path=['C:/isolated/status'])
            upstream.append(time.time())
            for name in ('a', 'b'):
                for path in (root / ('worker-' + name) / 'calls').rglob('*'):
                    if path.is_file():
                        try:
                            content = path.read_bytes()
                            cleanup_before[path.relative_to(root).as_posix()] = dict(size=len(content), sha256=hashlib.sha256(content).hexdigest())
                        except FileNotFoundError:
                            pass
            time.sleep(.2)
            body = json.dumps(dict(tag='node-a', deployPath='C:/isolated/status', running=True, version='3.0', storage=dict(password='must-filter'))).encode()
            self.send_response(200); self.end_headers(); self.wfile.write(body)

        def log_message(self, *_):
            pass

    console = http_server.ThreadingHTTPServer(('127.0.0.1', 0), Console)
    threading.Thread(target=console.serve_forever, daemon=True).start()

    def wait(fn, seconds=90):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            try:
                value = fn()
                if value:
                    return value
            except (ConnectionError, urllib.error.URLError, TimeoutError):
                pass
            time.sleep(.1)
        raise AssertionError('condition timeout')

    def http(path, body=None, method=None, credential=None, key=None, binary=False):
        h = {'Authorization': 'Bearer ' + (credential or api)}
        if body is not None:
            h.update({'Content-Type': 'application/octet-stream' if binary else 'application/json', 'Idempotency-Key': key or secrets.token_hex(16)})
        req = urllib.request.Request('http://127.0.0.1:' + str(http_port) + path, data=body if binary else None if body is None else json.dumps(body).encode(), headers=h, method=method)
        try:
            with urllib.request.urlopen(req, timeout=10) as x:
                raw = x.read()
                return x.status, raw if x.headers.get_content_type() != 'application/json' else json.loads(raw)
        except urllib.error.HTTPError as x:
            return x.code, json.loads(x.read())

    def db(query, *values):
        with sqlite3.connect(data / 'harness_execution.db', timeout=5) as c:
            return c.execute(query, values).fetchall()

    def read(run):
        status, value = http('/harness/v1/runs/' + run)
        assert status == 200, value
        return value

    def state(run, expected):
        value = read(run)
        assert value['snapshot']['state'] not in ('failed', 'timed_out'), value
        return value if value['snapshot']['state'] == expected else None

    def create(profile, instruction, ids=None):
        status, value = http('/harness/v1/runs', dict(taskProfileId=profile, input=dict(instruction=instruction, attachmentIds=ids or [])))
        assert status == 202, value
        return value['runId']

    stage = root / 'cell'
    (stage / 'cellets').mkdir(parents=True)
    (root / 'files').mkdir()
    shutil.copy2(module / 'build/cube-service-harness-3.0.jar', stage / 'cellets')
    xml = ET.parse(module / 'config/standalone.xml')
    xml.find('.//server/port').text = str(cell_port)
    xml.find('.//cellet').set('port', str(cell_port))
    xml.write(stage / 'cell.xml', encoding='utf-8', xml_declaration=True)
    config = stage / 'harness.local.properties'
    remote = {}
    if a.remote_config:
        remote = dict(line.split('=', 1) for line in a.remote_config.read_text().splitlines() if line and not line.startswith('#'))
        private_values.append(remote['backend.remote.token'])
    text = (data / 'store.local.properties').read_text() + f'''
mode=development
http.host=127.0.0.1
http.port={http_port}
lease.millis=3000
worker.credential={worker_key}
api.credential={api}
store.enabled=true
execution.enabled=true
execution.tools.enabled=true
execution.tenant={fixture['tenant']}
execution.subject={fixture['subject']}
execution.permissions=skill.list,skill.activate,resource.list,resource.read,resource.search,console.status.read,input.read_text,artifact.write_text
execution.repository={data.as_posix()}/repository
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
broker.node.id=n10-n12-smoke
broker.type=console
broker.console.baseUrl=http://127.0.0.1:{console.server_port}
broker.console.cookie=CubeConsoleToken={cookie}
broker.console.targets=node-a
broker.console.target.node-a.kind=service
broker.console.target.node-a.tag=node-a
broker.console.target.node-a.path=C:/isolated/status
broker.console.target.node-a.reportName=node-a
broker.console.qps=10
control.approver.credential={approver}
control.approver.subject=independent-reviewer
control.approval.tools=artifact.write_text
control.cancel.grace.millis=1000
files.enabled=true
files.root={root.as_posix()}/files
files.root.id=smoke-files
files.download.key={secrets.token_hex(32)}
cleanup.temp.grace.seconds=0
cleanup.scan.interval.seconds=5
'''
    for name in ('Baize', 'Baize2'):
        if remote:
            text += f'worker.{name}.role=model\nworker.{name}.model={name}\nworker.{name}.backend={name}\nbackend.{name}.type=openai\nbackend.{name}.capacity=1\nbackend.{name}.model={name}\nbackend.{name}.baseUrl={remote["backend.remote.baseUrl"]}\nbackend.{name}.token={remote["backend.remote.token"]}\n'
    config.write_text(text, encoding='utf-8')

    def launch():
        nonlocal process, log
        (stage / 'stop').unlink(missing_ok=True)
        log = (root / ('cell-' + str(time.time_ns()) + '.log')).open('w')
        process = subprocess.Popen([str(a.java), '-Dharness.config=' + str(config), '-cp', cp, 'cube.service.harness.HarnessCell', str(stage / 'cell.xml'), str(stage / 'stop')], cwd=stage, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT, **({'creationflags': subprocess.CREATE_NO_WINDOW} if os.name == 'nt' else {}))
        wait(lambda: http('/healthz')[0] == 200, 20)

    def stop_host():
        nonlocal process
        (stage / 'stop').touch()
        assert process.wait(timeout=20) == 0
        log.close()
        process = None

    def worker(name, script=None, delay=0):
        path = root / (name + '.local.json')
        value = dict(host='127.0.0.1', port=cell_port, workerId=name, role='runner' if name in ('a', 'b') else 'model', workerCredential=worker_key, runtimeDir=str(root / ('worker-' + name)), requestTimeout=4, startupTimeout=20)
        if name in ('a', 'b'):
            value['requestTimeout'] = 2
            value['runner'] = dict(runtime='trusted-python-v1', capacity=1)
        elif name == 'model':
            value['model'] = dict(model='scripted', backendId='shared', backendCapacity=1, script=script, scriptDelayMillis=delay)
        else:
            value['model'] = dict(model=name)
        path.write_text(json.dumps(value))
        if path not in configs:
            configs.append(path)
        lifecycle.start(path)
        return path

    def proposal(name, args):
        return dict(kind='tool_calls', calls=[dict(proposalId=name, tool=name, argumentsJson=json.dumps(args))])

    try:
        launch()
        for name in ('a', 'b'):
            worker(name)
        model = worker('model', [proposal('skill.activate', dict(release=fixture['release'])), proposal('cube_console.query_status', dict(targets=['node-a'], fields=['running', 'version'])), dict(kind='final', text='Fixture node-a running; source is local Console fixture.')])
        runs = [create('cube-server-status.readonly.v1', 'Query node-a status ' + str(i)) for i in range(4)]
        for run in runs:
            wait(lambda: state(run, 'succeeded'))
        lifecycle.stop(root / 'a.local.json')
        runs.append(create('cube-server-status.readonly.v1', 'Query using remaining Runner'))
        wait(lambda: state(runs[-1], 'succeeded'))
        assert db('SELECT worker_id FROM harness_tool_job WHERE run_id=?', runs[-1]) == [('b',)]
        lifecycle.stop(root / 'b.local.json')
        lifecycle.start(root / 'a.local.json')
        runs.append(create('cube-server-status.readonly.v1', 'Query using restored Runner a'))
        wait(lambda: state(runs[-1], 'succeeded'))
        assert db('SELECT worker_id FROM harness_tool_job WHERE run_id=?', runs[-1]) == [('a',)]
        lifecycle.start(root / 'b.local.json')
        assert set(w for w, in db('SELECT DISTINCT worker_id FROM harness_tool_job')) == {'a', 'b'}
        assert len(upstream) == 6
        assert 'must-filter' not in ' '.join(value for value, in db('SELECT result_json FROM harness_tool_job'))
        wait(lambda: db('SELECT COUNT(*) FROM harness_tool_job WHERE cleanup_done=0')[0][0] == 0)
        for name in ('a', 'b'):
            assert not list((root / ('worker-' + name) / 'calls').glob('*'))
            assert (root / ('worker-' + name) / 'runner-journal.sqlite').is_file()
        assert cleanup_before
        (evidence / 'process-files-before-after.json').write_text(json.dumps(dict(before=cleanup_before, after=[], reclaimedBytes=sum(v['size'] for v in cleanup_before.values()), retained=['worker-a/runner-journal.sqlite','worker-b/runner-journal.sqlite']), indent=2)+'\n')
        record('real-cell-two-independent-runners-console-fixture-filtered-and-ACK-confirmed-workspaces-deleted-journals-retained')
        lifecycle.stop(model)
        content = 'input consumed without model filesystem access'.encode()
        digest = hashlib.sha256(content).hexdigest()
        status, inp = http('/harness/v1/inputs', dict(fileName='input.txt', mediaType='text/plain', size=len(content), sha256=digest))
        assert status == 201, inp
        input_id = inp['inputId']
        assert http('/harness/v1/inputs/' + input_id + '/content', content, method='PUT', binary=True)[0] == 200
        assert http('/harness/v1/inputs/' + input_id + '/complete', dict(sha256=digest))[0] == 200
        model = worker('model', [dict(kind='need_input', question='Confirm the input.'), proposal('input.read_text', dict(inputId=input_id)), proposal('artifact.write_text', dict(fileName='result.txt', text='verified file artifact')), dict(kind='final', text='File ready.')])
        run = create('files', 'Pause then read input and produce a file', [input_id])
        waiting = wait(lambda: state(run, 'waiting_input'))
        wait_id = waiting['interaction']['waitId']
        stop_host(); launch()
        after = wait(lambda: state(run, 'waiting_input'))
        assert after['interaction'] == waiting['interaction']
        body = dict(expectedVersion=after['snapshot']['version'], waitId=wait_id, input=dict(instruction='Confirmed; continue.', attachmentIds=[]))
        key = secrets.token_hex(16)
        first = http('/harness/v1/runs/' + run + '/resume', body, key=key)
        assert first[0] == 202 and http('/harness/v1/runs/' + run + '/resume', body, key=key) == first
        approval = wait(lambda: state(run, 'waiting_approval'))
        control = dict(expectedVersion=approval['snapshot']['version'], decision='approve', argumentsHash=approval['interaction']['argumentsHash'])
        route = '/harness/v1/runs/' + run + '/approvals/' + approval['interaction']['waitId'] + '/decision'
        assert http(route, control)[0] == 403
        assert http(route, dict(control, argumentsHash='0'*64), credential=approver)[0] == 409
        assert http(route, control, credential=approver)[0] == 202
        final = wait(lambda: state(run, 'succeeded'))
        status, artifacts = http('/harness/v1/runs/' + run + '/artifacts')
        assert status == 200 and len(artifacts['artifacts']) == 1, artifacts
        artifact = artifacts['artifacts'][0]
        route = '/harness/v1/artifacts/' + artifact['artifactId']
        status, raw = http(route + '/content')
        assert status == 200 and raw == b'verified file artifact' and hashlib.sha256(raw).hexdigest() == artifact['sha256']
        handle = http(route + '/download-handle')[1]
        assert http(handle['url'])[1] == raw
        assert http(handle['url'], credential='wrong')[0] == 401
        assert db("SELECT COUNT(*) FROM harness_tool_call WHERE run_id=? AND status='confirmed'", run)[0][0] == 2
        record('HTTP-input-stream-complete-pause-JVM-restart-resume-idempotency-independent-approval-file-tools-real-download-hash-and-authenticated-handle')
        lifecycle.stop(model)
        model = worker('model', [dict(kind='final', text='late')], 5000)
        cancelled = create('files', 'cancel active model')
        wait(lambda: db("SELECT job_id FROM harness_model_job WHERE run_id=? AND status<>'queued' AND actual_finished=0", cancelled))
        value = read(cancelled)
        assert http('/harness/v1/runs/' + cancelled + '/cancel', dict(expectedVersion=value['snapshot']['version']))[0] == 202
        wait(lambda: read(cancelled)['snapshot']['state'] == 'cancelled')
        wait(lambda: db('SELECT actual_finished FROM harness_model_job WHERE run_id=?', cancelled) == [(1,)])
        wait(lambda: read(cancelled)['snapshot']['executionSettlement'] == 'confirmed')
        assert db('SELECT COUNT(*) FROM harness_result WHERE run_id=?', cancelled) == [(1,)]
        record('HTTP-active-cancel-final-once-late-physical-completion-and-settlement')
        lifecycle.stop(model)
        for name in ('Baize', 'Baize2') if remote else ():
            path = worker(name)
            run = create('cube-server-status.readonly.v1.' + name, 'Activate the available server status release. Call cube_console.query_status for targets ["node-a"] and fields ["running","version"]. Then report the real values, source and queriedAt. Do not ask for input.')
            value = wait(lambda: read(run) if read(run)['snapshot']['state'] in ('succeeded', 'failed', 'timed_out', 'waiting_input') else None, 200)
            remote_results.append(dict(model=name, runId=run, state=value['snapshot']['state'], settlement=value['snapshot']['executionSettlement'], result=value.get('result'), console='local-protocol-fixture', certified=False))
            lifecycle.stop(path)
        report = dict(result='PASS', checks=checks, liveModelSmoke=remote_results, dedicatedConsole=False, n10Certification='NOT_RUN', upstreamFixtureRequests=len(upstream))
        (evidence / 'results.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    finally:
        for path in reversed(configs):
            try:
                lifecycle.stop(path)
            except Exception as exc:
                print('Owned worker cleanup:', type(exc).__name__, flush=True)
        if process:
            try:
                stop_host()
            finally:
                if process and process.poll() is None:
                    process.terminate(); process.wait(timeout=10)
        console.shutdown(); console.server_close()
        for source in root.glob('cell-*.log'):
            text = source.read_text(errors='replace')
            for secret in private_values:
                text = text.replace(secret, '[REDACTED]')
            (evidence / source.name).write_text(text, encoding='utf-8')
        shutil.copy2(data / 'harness_execution.db', evidence / 'harness_execution.db')
        for name in ('a', 'b'):
            source = root / ('worker-' + name) / 'runner-journal.sqlite'
            if source.exists():
                shutil.copy2(source, evidence / ('runner-' + name + '-journal.sqlite'))
        for source in data.glob('cube-server-status*'):
            shutil.copy2(source, evidence / source.name)
        inventory = {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in (root / 'files').rglob('*') if p.is_file()}
        (evidence / 'file-inventory-after.json').write_text(json.dumps(inventory, indent=2) + '\n')


if __name__ == '__main__':
    main()
