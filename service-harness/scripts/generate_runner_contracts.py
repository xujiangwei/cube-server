"""Additive Cell v4 CPU Runner contracts; frozen model/transport contracts are untouched."""
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
    strings = dict(type="array", items=ident, maxItems=32)
    grant = obj(dict(bodyJson=text(8192), signature=digest))
    job = obj(dict(jobId=digest, jobHash=digest, runId=ident, callId=ident, attempt=integer,
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
    common = dict(protocolVersion=dict(type="integer", enum=[4]), action=ident, requestId=ident,
        credential=text(256), workerId=ident, instanceId=ident, role=dict(type="string", enum=["runner"]),
        capabilities=dict(type="array", items=dict(type="string", enum=["tool.execute"]), minItems=1, maxItems=1),
        sessionId=ident, healthy=boolean)
    branches = []
    for action, extra in {
        "describe": dict(descriptor=descriptor), "heartbeat": {}, "poll": {}, "drain": {}, "unregister": {},
        "accepted": dict(jobId=digest, jobHash=digest), "status": dict(jobId=digest, jobHash=digest),
        "result": dict(report=report),
        "broker_query": dict(jobId=digest, jobHash=digest, grant=grant, targets=strings, fields=strings),
    }.items():
        properties = copy.deepcopy(common)
        properties["action"] = dict(type="string", enum=[action])
        properties.update(extra)
        required = [k for k in common if k not in ("sessionId", "healthy")]
        if action != "describe":
            required.append("sessionId")
        branches.append(obj(properties, required + list(extra)))
    success = obj(dict(protocolVersion=dict(type="integer", enum=[4]), requestId=text(128), ok=dict(type="boolean", enum=[True]),
        sessionId=ident, leaseMillis=integer, registered=boolean, ready=boolean, cancellations=dict(type="array", items=digest,maxItems=64),
        job=job, dataJson=text(65536), status=text(32), resultHash=digest, actualFinished=boolean),
        ["protocolVersion", "requestId", "ok", "sessionId", "leaseMillis", "registered", "ready", "cancellations"])
    error = obj(dict(protocolVersion=dict(type="integer", enum=[4]), requestId=text(128), ok=dict(type="boolean", enum=[False]), errorCode=text(128)))
    schemas = {"runner-request": dict(oneOf=branches), "runner-response": dict(oneOf=[success,error]), "runner-job": job, "runner-report": report}
    files = {"schemas/" + k + ".json": raw(dict(v, **{"$schema": "http://json-schema.org/draft-07/schema#"})) for k,v in schemas.items()}
    sources = {p: hashlib.sha256((python_root / "harness" / p).read_bytes()).hexdigest()
               for p in ("runner_worker.py", "runner_journal.py", "runner_process.py", "runner_bootstrap.py", "runner_sdk.py")}
    files["runtime.json"] = raw(dict(runtime="trusted-python-v1", sources=sources,
        digest=hashlib.sha256(json.dumps(sources,sort_keys=True,separators=(",",":")).encode()).hexdigest()))
    files["manifest.json"] = raw({k: hashlib.sha256(v).hexdigest() for k,v in files.items()})
    for root in (MODULE / "contracts/runner", python_root / "harness/contracts/runner"):
        for path, value in files.items():
            target = root / path
            target.parent.mkdir(parents=True,exist_ok=True)
            target.write_bytes(value)
    # Versioned runtime/manifest expansion leaves all v1 schemas byte-for-byte unchanged.
    runtime = json.loads((MODULE / "resources/runtime/schemas/runtime-profile-v1.json").read_text())
    runtime["properties"]["schemaVersion"]["enum"] = [2]
    runtime["properties"]["mode"]["enum"] = ["trusted-python-v1"]
    runtime["properties"]["certification"]["enum"] = ["offline-trusted-runner"]
    manifest = json.loads((MODULE / "resources/runtime/schemas/manifest-v1.json").read_text())
    manifest["properties"]["schemaVersion"]["enum"] = [2]
    tool = manifest["properties"]["tools"]["items"]
    tool["properties"].update(effect=dict(type="string",enum=["read-only"]), brokerOperation=dict(type="string",enum=["mockconsole.query_status"]))
    tool["required"] += ["effect", "brokerOperation"]
    schema_root = MODULE / "resources/runtime/schemas"
    for name, value in (("runtime-profile-v2.json", runtime), ("manifest-v2.json", manifest)):
        (schema_root/name).write_bytes(raw(value))
    index_path = schema_root / "manifest.json"
    index = json.loads(index_path.read_text())
    for name in ("runtime-profile-v2.json", "manifest-v2.json"):
        index[name] = hashlib.sha256((schema_root/name).read_bytes()).hexdigest()
    index_path.write_bytes(raw(index))


if __name__ == "__main__":
    parser=argparse.ArgumentParser(__doc__)
    parser.add_argument("--python-root",type=Path,required=True)
    generate(parser.parse_args().python_root)
