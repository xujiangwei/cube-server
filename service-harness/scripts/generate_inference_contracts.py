"""Generate additive Cell v3 contracts. Frozen v1/v2 execution contracts are never overwritten."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
from generate_execution_contracts import obj, integer, enum, ID

ROOT = Path(__file__).resolve().parents[1]


def raw(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()


def generate(python_root):
    digest = dict(type="string", pattern="^[a-f0-9]{64}$")
    descriptor = obj(dict(model=ID, adapter=enum("openai-json-v2", "openai-tools-v2"),
        nativeTools=dict(type="boolean"), cancellation=enum("observe-only"), budget=enum("transport-bytes-v1"), profileDigest=digest))
    job = json.loads((ROOT / "contracts/execution/schemas/model-job.json").read_text())
    job["properties"]["model"] = ID
    report = json.loads((ROOT / "contracts/execution/schemas/model-report.json").read_text())
    base = dict(protocolVersion=dict(type="integer", enum=[3]), requestId=ID,
        credential=dict(type="string", minLength=24, maxLength=512), workerId=ID, instanceId=ID,
        role=enum("model"), capabilities=dict(type="array", items=enum("model.round"), minItems=1, maxItems=1))
    branches = []
    for action in ("describe", "poll", "heartbeat", "drain", "unregister", "actual_finished", "status",
                   "inference_submit", "inference_poll", "inference_cancel"):
        fields = dict(base, action=enum(action))
        if action == "describe":
            fields["descriptor"] = descriptor
        else:
            fields.update(sessionId=ID, healthy=dict(type="boolean"))
        if action == "actual_finished":
            fields["report"] = report
        if action.startswith("inference_"):
            fields.update(jobId=digest, jobInstanceId=ID, wireAttempt=integer(1, 2))
            if action == "inference_submit":
                fields.update(requestHash=digest, requestJson=dict(type="string", minLength=1, maxLength=131072))
            else:
                fields["after"] = integer(0, 4096)
        branches.append(obj(fields))
    page = obj(dict(inferenceId=digest, status=enum("queued", "dispatched", "finished", "unknown"),
        actualFinished=dict(type="boolean"), cancelRequested=dict(type="boolean"),
        events=dict(type="array", maxItems=16, items=obj(dict(sequence=integer(1, 4096), data=dict(type="object")))),
        nextCursor=integer(0, 4096), hasMore=dict(type="boolean"), response=dict(type="object"), errorCode=ID),
        ["inferenceId", "status", "actualFinished", "cancelRequested", "events", "nextCursor", "hasMore"])
    success = obj(dict(protocolVersion=dict(type="integer", enum=[3]), requestId=ID, ok=dict(type="boolean", enum=[True]), sessionId=ID,
        leaseMillis=integer(1000, 60000), registered=dict(type="boolean"), ready=dict(type="boolean"),
        cancellations=dict(type="array", items=digest, maxItems=32), job=job, inference=page),
        ["protocolVersion", "requestId", "ok", "sessionId", "leaseMillis", "registered", "ready", "cancellations"])
    failure = obj(dict(protocolVersion=dict(type="integer", enum=[3]), requestId=dict(type="string", maxLength=128), ok=dict(type="boolean", enum=[False]), errorCode=ID))
    schemas = {"model-worker-v3-request": dict(oneOf=branches), "model-worker-v3-response": dict(oneOf=[success, failure]),
               "model-v3-job": job, "model-v3-descriptor": descriptor, "model-v3-report": report}
    profiles = {}
    for adapter, native in (("openai-json-v2", False), ("openai-tools-v2", True)):
        sources = {p: hashlib.sha256((python_root / "harness" / p).read_bytes()).hexdigest()
                   for p in ("model_adapter.py", "model_worker.py", "inference_client.py")}
        spec = dict(adapter=adapter, protocolVersion=3, nativeTools=native, sources=sources,
                    transport="cell-java-inference", cancellation="observe-only", maxRepairs=1)
        profiles[adapter] = dict(spec=spec, digest=hashlib.sha256(json.dumps(spec, ensure_ascii=False,
            sort_keys=True, separators=(",", ":")).encode()).hexdigest())
    files = {"schemas/" + key + ".json": raw(dict(value, **{"$schema": "http://json-schema.org/draft-07/schema#"})) for key, value in schemas.items()}
    files["adapter-profiles.json"] = raw(profiles)
    files["canonical-vectors.json"] = raw({"value": {"temperature": 1.0, "tiny": 1e-7, "message": "</script>\u2028\u0085"},
        "canonical": '{"message":"<\\/script>\\u2028\\u0085","temperature":1,"tiny":0.0000001}'})
    files["manifest.json"] = raw({key: hashlib.sha256(value).hexdigest() for key, value in files.items()})
    for root in (ROOT / "contracts/inference", python_root / "harness/contracts/inference"):
        for key, value in files.items():
            path = root / key; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(value)
    schema = copy.deepcopy(json.loads((ROOT / "resources/runtime/schemas/model-profile-v2.json").read_text()))
    schema["properties"]["schemaVersion"] = dict(type="integer", enum=[3])
    schema["properties"]["backend"] = enum("java-inference")
    schema["properties"]["adapter"] = enum("openai-json-v2", "openai-tools-v2")
    path = ROOT / "resources/runtime/schemas/model-profile-v3.json"; path.write_bytes(raw(schema))
    path = path.parent / "manifest.json"; manifest = json.loads(path.read_text())
    manifest["model-profile-v3.json"] = hashlib.sha256(raw(schema)).hexdigest(); path.write_bytes(raw(manifest))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(); parser.add_argument("--python-root", required=True, type=Path)
    generate(parser.parse_args().python_root)
