"""Online acceptance against an explicitly configured, deployed Harness with real model and SearXNG."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import sqlite3
import time
import urllib.request


def verify(result, trace, rows):
    """Trace redacts content: bind a read-only ledger export to its result commitments."""
    sources = set()
    committed = 0
    for call in trace.get("calls", []):
        if call.get("tool") != "common.search.query" or call.get("status") != "confirmed":
            continue
        for row in rows:
            if row.get("run_id") != result["snapshot"]["runId"] or row.get("intent_id") != call["intent_id"] or row.get("attempt") != call["attempt"]:
                continue
            raw = row.get("result_json")
            if not isinstance(raw, str) or hashlib.sha256(raw.encode()).hexdigest() != call.get("result_json_sha256"):
                continue
            value = json.loads(raw)
            if value.get("status") != "success":
                continue
            data = json.loads(value["dataJson"])
            if "searchedAt" not in data or not isinstance(data.get("results"), list):
                continue
            committed += 1
            for item in data["results"]:
                if isinstance(item, dict) and re.match(r"https?://", item.get("url", "")):
                    sources.add(item["url"])
    answer = json.dumps(result.get("result"), ensure_ascii=False)
    selected = result["snapshot"]["selection"]["model"]["id"]
    inference = any(row.get("status") == "finished" and row.get("actual_finished") == 1
                    and not row.get("error_code") and not row.get("cancel_requested") for row in trace.get("inferences", []))
    checks = dict(succeeded=result["snapshot"]["state"] == "succeeded", realModel=inference,
                  searchEvidence=committed > 0, searchSources=bool(sources),
                  citedSource=any(url in answer for url in sources))
    return dict(result="PASS" if all(checks.values()) else "FAIL", runId=result["snapshot"]["runId"],
                checks=checks, model=selected, sources=sorted(sources))


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("--api-url", required=True, help="Dispatcher business URL ending in /harness/v1")
    parser.add_argument("--token-env", default="CUBE_TOKEN")
    parser.add_argument("--trace-token-env", default="CUBE_TRACE_TOKEN")
    parser.add_argument("--model")
    parser.add_argument("--runtime")
    parser.add_argument("--run-id", help="Validate an existing run, without creating another one")
    ledger = parser.add_mutually_exclusive_group(required=True)
    ledger.add_argument("--sqlite-store", type=Path, help="Operator-authorized read-only Harness SQLite ledger")
    ledger.add_argument("--call-results", type=Path, help="Exported run_id/intent_id/attempt/raw result_json rows")
    parser.add_argument("--evidence", required=True, type=Path)
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()
    token, trace_token = os.environ[args.token_env], os.environ[args.trace_token_env]
    args.evidence.mkdir(parents=True, exist_ok=False)

    def http(path, body=None, trace=False):
        request = urllib.request.Request(args.api_url.rstrip("/") + path,
                                         data=None if body is None else json.dumps(body, ensure_ascii=False).encode(),
                                         headers={"Authorization": "Bearer " + (trace_token if trace else token),
                                                  "Content-Type": "application/json", "Accept": "application/json",
                                                  "Idempotency-Key": secrets.token_hex(16)})
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.loads(response.read())

    body = dict(input=dict(instruction="全网搜索主流的3D建模软件。请实际调用联网搜索工具，按用途归纳，并在答案中引用实际检索来源链接。",
                           attachmentIds=[]), skills=["common/search", "common/web"])
    for name in ("model", "runtime"):
        if getattr(args, name):
            body[name] = getattr(args, name)
    created = dict(runId=args.run_id) if args.run_id else http("/runs", body)
    run_id = created["runId"]
    (args.evidence / "created.json").write_text(json.dumps(created, ensure_ascii=False, indent=2), encoding="utf-8")
    until = time.monotonic() + args.timeout
    while True:
        result = http("/runs/" + run_id)
        state = result["snapshot"]["state"]
        if state not in ("queued", "running") or time.monotonic() >= until:
            break
        time.sleep(0.5)
    trace = http("/runs/" + run_id + "/trace", trace=True)
    for name, value in (("result", result), ("trace", trace)):
        (args.evidence / (name + ".json")).write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")
    if args.sqlite_store:
        with sqlite3.connect(args.sqlite_store.resolve(strict=True).as_uri() + "?mode=ro", uri=True) as connection:
            connection.row_factory = sqlite3.Row
            rows = [dict(row) for row in connection.execute(
                "SELECT run_id,intent_id,attempt,result_json FROM harness_tool_call WHERE run_id=? AND result_json IS NOT NULL", (run_id,))]
    else:
        rows = json.loads(args.call_results.read_text(encoding="utf-8"))
    (args.evidence / "call-results.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2), encoding="utf-8")
    outcome = verify(result, trace, rows)
    (args.evidence / "results.json").write_text(json.dumps(outcome, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(outcome, ensure_ascii=False))
    if outcome["result"] != "PASS":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
