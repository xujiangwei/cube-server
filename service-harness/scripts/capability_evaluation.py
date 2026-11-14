"""Real-model diagnostic runner. This is deliberately separate from certification.

Credentials are read from a private JSON file, never CLI arguments or evidence.
The append-only journal preserves every attempt, HTTP rejection and unknown completion.
"""
import argparse
import base64
import copy
import hashlib
import io
import json
import re
import sys
import threading
import subprocess
import os
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

WORKSPACE = Path(__file__).resolve().parents[3]


def canonical(v):
    return json.dumps(v, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def sha(v):
    return hashlib.sha256(v if isinstance(v, bytes) else canonical(v).encode()).hexdigest()


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def utc():
    return datetime.now(timezone.utc).isoformat()


def redact(value, token=""):
    if isinstance(value, dict):
        return {k: ("[REDACTED]" if k.lower() in ("authtoken", "token", "authorization", "cookie", "credential") else redact(v, token)) for k, v in value.items()}
    if isinstance(value, list):
        return [redact(v, token) for v in value]
    return value.replace(token, "[REDACTED]") if token and isinstance(value, str) else value


class Journal:
    def __init__(self, path, token=""):
        self.path, self.token, self.lock = Path(path), token, threading.Lock()
        self.path.parent.mkdir(parents=True, exist_ok=True)

    def append(self, value):
        value = redact(dict(recordedAt=utc(), **value), self.token)
        with self.lock, self.path.open("a", encoding="utf-8") as out:
            out.write(canonical(value) + "\n")
        return value


class Remote:
    def __init__(self, config, evidence):
        self.base = config["baseUrl"].rstrip("/")
        self.token = config["token"]
        self.timeout = config.get("timeoutSeconds", 180)
        self.journal = Journal(Path(evidence) / "http.jsonl", self.token)
        self.channel = None
        self.uploads = {}
        self.serial = threading.RLock()

    def call(self, path, body=None, binary=None, identity=None):
        raw = binary if binary is not None else None if body is None else canonical(body).encode()
        headers = {"Authorization": "Bearer " + self.token}
        if raw is not None:
            headers["Content-Type"] = "application/octet-stream" if binary is not None else "application/json; charset=utf-8"
        req = urllib.request.Request(self.base + path, data=raw, headers=headers)
        start = time.monotonic()
        row = dict(identity=identity, method=req.get_method(), path=path, requestSha256=sha(raw or b""))
        if body is not None:
            row["request"] = body
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                row["httpStatus"] = r.status
                if "text/event-stream" in r.headers.get("Content-Type", ""):
                    result = collect_sse(r)
                else:
                    result = json.loads(r.read(8 * 1024 * 1024))
            row.update(response=result, elapsedMs=round((time.monotonic() - start) * 1000), actualFinished=True)
            self.journal.append(row)
            return result
        except urllib.error.HTTPError as exc:
            try:
                response = json.loads(exc.read(65536))
            except ValueError:
                response = {"error": "NON_JSON_HTTP_ERROR"}
            self.journal.append(dict(**row, httpStatus=exc.code, response=response,
                elapsedMs=round((time.monotonic() - start) * 1000), actualFinished=True))
            raise RuntimeError("HTTP_" + str(exc.code) + ":" + canonical(redact(response, self.token))) from None
        except (OSError, ValueError) as exc:
            self.journal.append(dict(**row, error=type(exc).__name__, actualFinished=False,
                elapsedMs=round((time.monotonic() - start) * 1000)))
            raise RuntimeError("REMOTE_COMPLETION_UNKNOWN") from None

    def completion(self, body, attempt, cancelled):
        return self.call("/v1/chat/completions", body, identity=dict(kind="model", wireAttempt=attempt))

    def visual(self, image, unit, content, option=None, identity=None):
        # One shared client lock also covers the text gateway used by local Cell.
        with self.serial:
            if self.channel is None:
                response = self.call("/aigc/channel/" + self.token,
                    dict(participant="Harness public-photo evaluation"), identity=dict(kind="channel"))
                self.channel = response["code"]
            digest = sha(image)
            if digest not in self.uploads:
                query = urllib.parse.urlencode(dict(token=self.token, filename="eval-" + digest[:16] + ".jpg", filesize=len(image)))
                response = self.call("/filestorage/file/?" + query, binary=image, identity=dict(kind="upload", sha256=digest))
                self.uploads[digest] = response["data"]["fileCode"]
            inputs = dict(unit=unit, files=[self.uploads[digest]])
            if unit != "SpecializedDetection":
                inputs["content"] = content
            else:
                inputs.pop("unit")
            if option:
                inputs["option"] = {k:v for k,v in option.items() if k != "operation"}
            path = "/specialized-detection/base/" if unit == "SpecializedDetection" else "/multimodal/base/"
            response = self.call(path + self.token, dict(channel=self.channel, input=inputs), identity=identity)
            # Both endpoints expose top-level answer/resultPayload. Do not synthesize absent counts.
            return response


def collect_sse(stream):
    message = dict(role="assistant", content="")
    tools, usage, reason, done = {}, None, None, False
    model, response_id = None, None
    for raw in stream:
        line = raw.decode("utf-8", errors="strict").strip()
        if not line.startswith("data:"):
            continue
        data = line[5:].strip()
        if data == "[DONE]":
            done = True
            break
        event = json.loads(data)
        if "error" in event:
            raise ValueError("SSE_ERROR")
        model, response_id = event.get("model", model), event.get("id", response_id)
        if event.get("usage"):
            usage = event["usage"]
        for choice in event.get("choices", []):
            if choice.get("index", 0) != 0:
                raise ValueError("MULTIPLE_CHOICES")
            delta = choice.get("delta", {})
            message["content"] += delta.get("content") or ""
            for call in delta.get("tool_calls", []):
                saved = tools.setdefault(call["index"], dict(id="", type="function", function=dict(name="", arguments="")))
                saved["id"] += call.get("id") or ""
                saved["function"]["name"] += call.get("function", {}).get("name") or ""
                saved["function"]["arguments"] += call.get("function", {}).get("arguments") or ""
            reason = choice.get("finish_reason") or reason
    if not done or reason is None:
        raise ValueError("INCOMPLETE_SSE")
    if tools:
        message["tool_calls"] = [tools[k] for k in sorted(tools)]
    result = dict(id=response_id, model=model, choices=[dict(index=0, message=message, finish_reason=reason)])
    if usage is not None:
        result["usage"] = usage
    return result


def model_cases():
    calc = dict(name="common.calculator.evaluate", description="精确计算表达式", parameters=dict(type="object",
        properties=dict(expression=dict(type="string")), required=["expression"], additionalProperties=False))
    lookup = dict(name="patrol.lookup", description="按明确区域查询巡检记录", parameters=dict(type="object",
        properties=dict(region=dict(type="string")), required=["region"], additionalProperties=False))
    return [
        dict(id="M01", title="指令约束", instruction="只回答四个汉字：林道复检。不要标点，不要解释。", expected=dict(kind="final", exact="林道复检")),
        dict(id="M02", title="决策与业务JSON格式", instruction='以 final 返回 JSON 文本，恰好包含 {"region":"西区","requiresReview":true,"capturedAt":null}，不添加字段。', expected=dict(kind="final", jsonEquals=dict(region="西区", requiresReview=True, capturedAt=None))),
        dict(id="M03", title="工具选择与参数", instruction="请用精确计算工具计算 0.1+0.2，不要心算作答。", tools=[calc, lookup], expected=dict(kind="tool_calls", tool=calc["name"], arguments=dict(expression="0.1+0.2"))),
        dict(id="M04", title="调用依赖", instruction="先查询西区记录，再把工具返回的两个记录数相加。禁止猜测记录数。", tools=[lookup, calc], expected=dict(kind="tool_calls", tool=lookup["name"], arguments=dict(region="西区")), followup=dict(tool=calc["name"], result=dict(counts=[17, 25]), expression="17+25")),
        dict(id="M05", title="必要澄清", instruction="请查询那个区域的巡检。当前对话没有说明是哪个区域，必须先询问，不得自行选择。", tools=[lookup], expected=dict(kind="need_input")),
        dict(id="M06", title="错误反馈修正", instruction="上次查询被拒绝：region 为空。用户明确指定西区，请修正参数后重新调用 patrol.lookup。", tools=[lookup], fault="fixed validation error feedback", expected=dict(kind="tool_calls", tool=lookup["name"], arguments=dict(region="西区"))),
        dict(id="M07", title="长上下文关键信息", instruction="读取资料后只回答目标 A17 的精确值，不要附加文字。\n" + ("其他设备记录：状态正常，值0。\n" * 220) + "\n唯一有效记录：目标 A17 的值是 4279。\n" + ("其他设备记录：状态正常，值0。\n" * 220), expected=dict(kind="final", exact="4279")),
        dict(id="M08", title="观测与业务规则", instruction='已观测两名人员。规则：只有确认未经授权进入禁区才判 abnormal。授权名单和区域性质均未知。以 final 返回 JSON 文本 {"status":...,"reason":...}，选择 not-assessed、review-required、normal 或 abnormal；不能把人员出现直接当异常。', expected=dict(kind="final", jsonField="status", allowed=["not-assessed", "review-required"]))
    ]


def check_decision(decision, expected):
    if decision.get("kind") != expected["kind"]:
        return False, "DECISION_KIND"
    if expected["kind"] == "tool_calls":
        calls = decision.get("calls", [])
        actual = json.loads(calls[0]["argumentsJson"]) if len(calls) == 1 else None
        wanted = expected["arguments"]
        if expected["tool"] == "common.calculator.evaluate" and isinstance(actual, dict) and set(actual) == {"expression"}:
            actual = dict(expression=re.sub(r"\s+", "", actual["expression"]))
            wanted = dict(expression=re.sub(r"\s+", "", wanted["expression"]))
        ok = len(calls) == 1 and calls[0]["tool"] == expected["tool"] and actual == wanted
        return ok, "TOOL_AND_ARGUMENTS"
    if expected["kind"] == "need_input":
        return bool(decision.get("question", "").strip()), "CLARIFICATION"
    text = decision.get("text", "").strip()
    if "exact" in expected:
        return text == expected["exact"], "EXACT_TEXT"
    try:
        value = json.loads(text)
    except ValueError:
        return False, "FINAL_JSON"
    if "jsonEquals" in expected:
        return value == expected["jsonEquals"], "JSON_EQUALITY"
    return isinstance(value, dict) and value.get(expected["jsonField"]) in expected["allowed"] and bool(value.get("reason")), "BUSINESS_RULE"


def run_models(remote, python_root, evidence, repetitions=3, java=None):
    sys.path.insert(0, str(python_root))
    from harness.model_adapter import ModelAdapter
    schema = json.loads((python_root / "harness/contracts/schemas/model-decision.json").read_text(encoding="utf-8"))
    observations = Journal(evidence / "model-observations.jsonl", remote.token)
    save(evidence / "model-fixture.json", dict(cases=model_cases(), repetitions=repetitions,
        sourceFingerprints={"model_adapter.py": sha((python_root / "harness/model_adapter.py").read_bytes())},
        generation=dict(temperature=0, topP=1, stream=True, maxRepairs=1), outputReserve=2048))
    for model in ("Baize", "Baize2"):
        adapter = ModelAdapter(dict(model=model, nativeTools=model == "Baize2"), caller=remote.completion)
        for case in model_cases():
            for repetition in range(1, repetitions + 1):
                key = f'{case["id"]}-{model}-{repetition}'
                if any(json.loads(s).get("identity") == key for s in observations.path.read_text(encoding="utf-8").splitlines()) if observations.path.exists() else False:
                    continue
                request = dict(messages=[dict(role="user", content=dict(instruction=case["instruction"]))],
                    tools=case.get("tools", []), responseSchema=schema, nativeTools=model == "Baize2", outputReserve=2048,
                    maxRepairs=1, generation=dict(temperature=0, topP=1, stream=True, maxRepairs=1))
                started, audit = time.monotonic(), []
                outcome = adapter.execute(request, audit.append)
                passed, reason = check_decision(outcome.get("decision", {}), case["expected"]) if outcome["status"] == "success" else (False, outcome.get("errorCode"))
                if passed and case.get("followup"):
                    call = outcome["decision"]["calls"][0]
                    request["messages"].extend([dict(role="assistant", content=outcome["decision"]),
                        dict(role="tool", proposalId=call["proposalId"], content=case["followup"]["result"])])
                    next_outcome = adapter.execute(request, audit.append)
                    expected = dict(kind="tool_calls", tool=case["followup"]["tool"], arguments=dict(expression=case["followup"]["expression"]))
                    passed, reason = check_decision(next_outcome.get("decision", {}), expected) if next_outcome["status"] == "success" else (False, next_outcome.get("errorCode"))
                    outcome = dict(first=outcome, second=next_outcome)
                if passed and java and case['id'] in ('M03', 'M04'):
                    decision = outcome.get('second', outcome)['decision']
                    expression = json.loads(decision['calls'][0]['argumentsJson'])['expression']
                    server = WORKSPACE / 'cube-server'
                    cp = [server/'service-harness-skill/build/test-classes', server/'service-harness-skill/build/classes',
                          server/'service-harness/build/cube-service-harness-3.0.jar', server/'build/cube-common-3.0.jar',
                          server/'deploy/bin/cell.jar', *sorted((server/'deploy/libs').glob('*.jar'))]
                    completed = subprocess.run([str(java), '-cp', os.pathsep.join(map(str, cp)),
                        'cube.service.harness.skill.CalculatorEvaluation', expression], check=True, capture_output=True, text=True, encoding='utf8')
                    calculation = json.loads(completed.stdout.strip().splitlines()[-1])
                    passed = calculation['result'] == ('42' if case['id'] == 'M04' else '0.3')
                    outcome['calculation'] = dict(arguments=dict(expression=expression), result=calculation, provider='common.calculator.evaluate.v1')
                save(evidence / "models" / (key + ".json"), dict(request=request, journal=audit, outcome=outcome))
                row = observations.append(dict(identity=key, layer="model", caseId=case["id"], model=model, repetition=repetition,
                    passed=passed, criterion=reason, fault=case.get("fault"), elapsedMs=round((time.monotonic() - started) * 1000),
                    artifact="models/" + key + ".json"))
                print(canonical(row), flush=True)
                results = [outcome.get('first', outcome), outcome.get('second', outcome)]
                if any(item.get('status') == 'unknown' for item in results):
                    raise RuntimeError('UNRESOLVED_REMOTE_COMPLETION: retain attempt; stop this batch')


PHOTO_PROFILES = {
    "fire": ("SpecializedDetection", "检测照片中的真实火焰或烟雾；忽略图案和光照。", dict(operation="detect", profile="fire-smoke", confidence=0.25, sliced=False)),
    "people": ("SpecializedDetection", "检测图中可见的人员。", dict(operation="detect", profile="thing", classes=["person"], confidence=0.25, sliced=False)),
    "obstacle": ("OmniMultimodal", '判断道路或步道通行区域是否被倒木、落石、滑坡等阻挡。路旁物体不算阻路。仅返回 JSON：{"blocked":true或false,"reason":"可见依据"}。', dict(operation="analyze", parameters={})),
}


def visual_prediction(category, response):
    if category == "people" and isinstance(response.get("resultPayload"), dict) and response["resultPayload"].get("profile") == "thing":
        refs = response["resultPayload"].get("refs")
        if not isinstance(refs, list):
            return None, None
        count = 0
        for ref in refs:
            if not isinstance(ref, dict) or not isinstance(ref.get("boxes"), list) or not isinstance(ref.get("content"), str):
                return None, None
            if ref["content"] == "person":
                count += len(ref["boxes"])
        return count > 0, count
    if category == "fire":
        if "resultPayload" not in response or "refs" not in response["resultPayload"]:
            return None, None
        refs = response["resultPayload"]["refs"]
        boxes = sum(len(r.get("boxes", [])) for r in refs if r.get("content") in ("fire", "smoke"))
        return boxes > 0, None
    answer = response.get("answer", response.get("response", ""))
    if category == "people":
        # Read-only compatibility for frozen historical OmniPerception evidence.
        # New photo calls always use SpecializedDetection / thing above.
        match = re.search(r'(\d+)\s*(?:个|名|位)?(?:人|person|people)', answer, re.I) or re.search(r'(?:人数|count)[：:\s]*(\d+)', answer, re.I)
        if not match:
            match = re.search(r'(?:数到|共有|一共|总共|计数为)\s*(\d+)\s*(?:个|名|位)', answer)
        if not match:
            match = re.search(r'(?:总数|数量|数到的)\s*(?:是|为|：|:)\s*(\d+)', answer)
        if match:
            count = int(match.group(1))
            return count > 0, count
        if re.search("没有人|无人|未发现人|未检测到人|没有请求的对象存在|no people|no person", answer, re.I):
            return False, 0
        return None, None
    try:
        cleaned = re.sub(r'^```(?:json)?\s*|\s*```$', '', answer.strip())
        v = json.loads(cleaned)
        if isinstance(v, list) and len(v) == 1:
            v = v[0]
        return (v["blocked"], None) if isinstance(v, dict) and type(v.get("blocked")) is bool else (None, None)
    except ValueError:
        return None, None


def run_photos(remote, manifest, evidence, repetitions=3):
    data = json.loads(manifest.read_text(encoding="utf-8"))
    journal = Journal(evidence / "photo-observations.jsonl", remote.token)
    prior = {json.loads(s)["identity"] for s in journal.path.read_text(encoding="utf-8").splitlines()} if journal.path.exists() else set()
    for photo in data["photos"]:
        raw = (WORKSPACE / "forest" / photo["path"]).read_bytes()
        if sha(raw) != photo["sha256"]:
            raise ValueError("PHOTO_DIGEST_MISMATCH")
        unit, prompt, option = PHOTO_PROFILES[photo["category"]]
        for repetition in range(1, repetitions + 1):
            key = photo["id"] + "-" + str(repetition)
            if key in prior:
                continue
            started = time.monotonic()
            result = dict(identity=key, layer="visual", sampleId=photo["id"], category=photo["category"], unit=unit,
                repetition=repetition, expected=photo["labels"]["present"], expectedCount=photo["labels"].get("count"), photoSha256=photo["sha256"])
            try:
                response = remote.visual(raw, unit, prompt, option, identity=dict(kind="photo", sampleId=photo["id"], repetition=repetition))
                prediction, count = visual_prediction(photo["category"], response)
                save(evidence / "photos" / (key + ".json"), response)
                result.update(responseArtifact="photos/" + key + ".json", prediction=prediction, predictedCount=count,
                    outcome="unscorable" if prediction is None else "correct" if prediction == result["expected"] else "false-negative" if result["expected"] else "false-positive")
            except RuntimeError as exc:
                result.update(outcome="call-failed", error=str(exc))
            result["elapsedMs"] = round((time.monotonic() - started) * 1000)
            print(canonical(journal.append(result)), flush=True)
            if result.get("error") == "REMOTE_COMPLETION_UNKNOWN":
                raise RuntimeError("UNRESOLVED_REMOTE_COMPLETION: stop this batch and retain the original attempt")


def main():
    p = argparse.ArgumentParser(__doc__)
    p.add_argument("layer", choices=("models", "photos"))
    p.add_argument("--config", type=Path, required=True)
    p.add_argument("--evidence", type=Path, required=True)
    p.add_argument("--python-root", type=Path, default=WORKSPACE.parent / "apexnova-wisdom")
    p.add_argument("--manifest", type=Path, default=WORKSPACE / "forest/media/evaluation/manifest.json")
    p.add_argument("--repetitions", type=int, default=3)
    p.add_argument("--java", type=Path, help='JDK 8 executable; run the real arithmetic provider for M03/M04')
    a = p.parse_args()
    a.evidence.mkdir(parents=True, exist_ok=True)
    remote = Remote(json.loads(a.config.read_text(encoding="utf-8")), a.evidence)
    if a.layer == "models":
        save(a.evidence / "remote-models.json", remote.call("/v1/models"))
        run_models(remote, a.python_root, a.evidence, a.repetitions, a.java)
    else:
        run_photos(remote, a.manifest, a.evidence, a.repetitions)


if __name__ == "__main__":
    main()
