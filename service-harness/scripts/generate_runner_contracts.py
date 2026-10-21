"""Generate current tool and sandbox Runner contracts in both repositories."""
import argparse
import copy
import hashlib
import json
from pathlib import Path

MODULE = Path(__file__).resolve().parents[1]


def raw(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()


def obj(properties, required=None):
    return dict(type="object", properties=properties, required=list(properties) if required is None else required, additionalProperties=False)


def generate(python_root):
    ident = dict(type="string", pattern="^[A-Za-z0-9_.:-]{1,128}$", maxLength=128)
    digest = dict(type="string", pattern="^[a-f0-9]{64}$", maxLength=64)
    integer = dict(type="integer", minimum=0, maximum=9007199254740991)
    boolean = dict(type="boolean")
    text = lambda n: dict(type="string", maxLength=n)
    grant = obj(dict(bodyJson=text(8192), signature=digest))
    job = obj(dict(runnerType=dict(type="string", enum=["tool"]), jobId=digest, jobHash=digest, runId=ident, callId=ident, attempt=integer,
        originFence=integer, intentHash=digest, tenantId=ident, subjectId=ident, release=digest,
        tool=ident, argumentsJson=text(16384), argumentsHash=digest, bundleJson=text(180000),
        workerId=ident, instanceId=ident, runtimeId=ident, grant=grant,
        timeoutMillis=dict(type="integer", minimum=100, maximum=30000),
        maxOutputBytes=dict(type="integer", minimum=1024, maximum=65536),
        maxWorkspaceBytes=dict(type="integer", minimum=1024, maximum=10485760),
        maxWorkspaceFiles=dict(type="integer", minimum=1, maximum=256)))
    report = obj(dict(jobId=digest, jobHash=digest, actualFinished=boolean,
        effectStatus=dict(type="string", enum=["none", "completed", "unknown"]), resultJson=text(65536)))
    descriptor = obj(dict(runtimeId=ident, capacity=dict(type="integer", minimum=1, maximum=8),
        runtime=dict(type="string", enum=["trusted-python-v1"]), sourceDigest=digest))
    common = dict(runnerType=dict(type="string", enum=["tool"]), action=ident, requestId=ident,
        credential=text(256), workerId=ident, instanceId=ident, role=dict(type="string", enum=["runner"]),
        capabilities=dict(type="array", items=dict(type="string", enum=["tool.execute"]), minItems=1, maxItems=1),
        sessionId=ident, healthy=boolean)
    branches = []
    for action, extra in {
        "describe": dict(descriptor=descriptor), "heartbeat": {}, "poll": {}, "drain": {}, "unregister": {},
        "accepted": dict(jobId=digest, jobHash=digest), "status": dict(jobId=digest, jobHash=digest),
        "result": dict(report=report),
        "cleanup_ack": dict(jobId=digest, jobHash=digest, resultHash=digest),
        "broker_invoke": dict(jobId=digest, jobHash=digest, grant=grant, argumentsJson=text(16384)),
    }.items():
        properties = copy.deepcopy(common)
        properties["action"] = dict(type="string", enum=[action])
        properties.update(extra)
        required = [k for k in common if k not in ("sessionId", "healthy")]
        if action != "describe":
            required.append("sessionId")
        branches.append(obj(properties, required + list(extra)))
    success = obj(dict(runnerType=dict(type="string", enum=["tool"]), requestId=text(128), ok=dict(type="boolean", enum=[True]),
        sessionId=ident, leaseMillis=integer, registered=boolean, ready=boolean, cancellations=dict(type="array", items=digest,maxItems=64),
        job=job, dataJson=text(65536), status=text(32), resultHash=digest, actualFinished=boolean,
        cleanup=dict(type="array",items=obj(dict(jobId=digest,jobHash=digest,runtimeId=ident,resultHash=digest)),maxItems=32)),
        ["runnerType", "requestId", "ok", "sessionId", "leaseMillis", "registered", "ready", "cancellations"])
    error = obj(dict(runnerType=dict(type="string", enum=["tool"]), requestId=text(128), ok=dict(type="boolean", enum=[False]), errorCode=text(128)))
    schemas = {"runner-request": dict(oneOf=branches), "runner-response": dict(oneOf=[success,error]), "runner-job": job, "runner-report": report}
    files = {"schemas/" + k + ".json": raw(dict(v, **{"$schema": "http://json-schema.org/draft-07/schema#"})) for k,v in schemas.items()}
    sources = {p: hashlib.sha256((python_root / "harness" / p).read_bytes()).hexdigest()
               for p in ("runner_worker.py", "runner_journal.py", "runner_process.py", "runner_bootstrap.py", "runner_sdk.py")}
    files["runtime.json"] = raw(dict(runtime="trusted-python-v1", sources=sources,
        digest=hashlib.sha256(json.dumps(sources,sort_keys=True,separators=(",",":")).encode()).hexdigest()))
    files["manifest.json"] = raw({k: hashlib.sha256(v).hexdigest() for k,v in files.items()})
    for root in (MODULE / "contracts/runner-tool", python_root / "harness/contracts/runner-tool"):
        for path, value in files.items():
            target = root / path
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(value)



def generate_sandbox(python_root):
    files = {}
    for path in (MODULE/'contracts/runner-tool/schemas').glob('*.json'):
        value = json.loads(path.read_bytes())

        def runner_type(node):
            if isinstance(node, dict):
                if 'runnerType' in node.get('properties', {}):
                    node['properties']['runnerType']['enum'] = ['sandbox']
                for child in node.values():
                    runner_type(child)
            elif isinstance(node, list):
                for child in node:
                    runner_type(child)
        runner_type(value)
        files[path.name] = value
    digest = dict(type='string', pattern='^[a-f0-9]{64}$', maxLength=64)
    profile = dict(type='string', enum=['linux-firecracker-python-v1'])

    def job(node):
        if isinstance(node, dict):
            if {'bundleJson', 'jobHash'} <= set(node.get('properties', {})):
                node['properties'].update(executionProfile=profile)
                node['required'] += ['executionProfile']
            for child in node.values():
                job(child)
        elif isinstance(node, list):
            for child in node:
                job(child)
    for value in files.values():
        job(value)
    request = files['runner-request.json']
    descriptor = request['oneOf'][0]['properties']['descriptor']
    descriptor['properties'].update(executionProfile=profile,
        sandboxProfileDigest=digest, certificateDigest=digest)
    descriptor['required'] += ['executionProfile', 'sandboxProfileDigest', 'certificateDigest']
    descriptor['properties']['capacity']['maximum'] = 1
    prepare = copy.deepcopy(next(b for b in request['oneOf']
        if b['properties']['action']['enum'] == ['broker_invoke']))
    del prepare['properties']['argumentsJson']
    prepare['required'].remove('argumentsJson')
    prepare['properties']['action']['enum'] = ['sandbox_prepare']
    complete = copy.deepcopy(prepare)
    complete['properties']['action']['enum'] = ['sandbox_complete']
    # Completion is authorized by worker/runtime/job identity, even after grant expiry or revoke.
    del complete['properties']['grant']
    complete['required'].remove('grant')
    complete['properties']['receiptJson'] = dict(type='string', maxLength=65536)
    complete['required'].append('receiptJson')
    request['oneOf'] = [b for b in request['oneOf']
        if b['properties']['action']['enum'][0] != 'broker_invoke']
    request['oneOf'] += [prepare, complete]
    output = dict(type='object', properties=dict(stdout=dict(type='string', maxLength=8192),
                  artifactText=dict(type='string', maxLength=8192)),
                  required=['stdout', 'artifactText'], additionalProperties=False)
    properties = dict(actualFinished=dict(type='boolean', enum=[True]),
                      proofHash=digest, output=output,
                      errorCode=dict(type='string', pattern='^[A-Z0-9_]{1,128}$', maxLength=128))
    files['sandbox-receipt.json'] = dict(oneOf=[
        dict(type='object', properties={k: properties[k] for k in keys}, required=list(keys),
             additionalProperties=False)
        for keys in (('actualFinished', 'proofHash', 'output'),
                     ('actualFinished', 'proofHash', 'errorCode'))])
    names = ['runner_worker.py', 'runner_journal.py', 'runner_process.py',
             'runner_bootstrap.py', 'runner_sdk.py', 'sandbox_worker.py',
             'microvm/sandbox.py', 'microvm/firecracker.py']
    sources = {name: hashlib.sha256((python_root/'harness'/name).read_bytes()).hexdigest()
               for name in names}
    runtime = dict(runtime='trusted-python-v1', sources=sources,
                   digest=hashlib.sha256(json.dumps(sources, sort_keys=True,
                       separators=(',', ':')).encode()).hexdigest())
    for root in (MODULE/'contracts/runner-sandbox', python_root/'harness/contracts/runner-sandbox'):
        root.mkdir(parents=True, exist_ok=True)
        (root/'runtime.json').write_bytes(raw(runtime))
        for name, value in files.items():
            target = root/'schemas'/name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(raw(value))
    for family in ('runner-tool', 'runner-sandbox'):
        root = MODULE/'contracts'/family
        manifest = {f.relative_to(root).as_posix(): hashlib.sha256(f.read_bytes()).hexdigest()
                    for f in sorted(root.rglob('*.json')) if f.name != 'manifest.json'}
        for target in (root, python_root/'harness/contracts'/family):
            (target/'manifest.json').write_bytes(raw(manifest))


if __name__ == "__main__":
    parser=argparse.ArgumentParser(__doc__)
    parser.add_argument("--python-root",type=Path,required=True)
    args = parser.parse_args()
    generate(args.python_root)
    generate_sandbox(args.python_root)
    schema_root = MODULE / 'resources/runtime/schemas'
    (schema_root / 'manifest.json').write_bytes(raw({
        p.name: hashlib.sha256(p.read_bytes()).hexdigest()
        for p in sorted(schema_root.glob('*.json')) if p.name != 'manifest.json'}))
