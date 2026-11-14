"""Evaluation-only observer around the unchanged production Runner implementation."""
import json
import os
import runpy
import subprocess
import sys
import threading

from harness import runner_worker

_lock = threading.Lock()


def record(**value):
    with _lock:
        print(json.dumps(dict(diagnostic="runner-observer", **value), ensure_ascii=True), file=sys.stderr, flush=True)


original_broker = runner_worker.RunnerHarnessService._broker_once


def broker(self, job, request):
    try:
        response = original_broker(self, job, request)
        record(jobId=job["jobId"], phase="broker", responsePending=response is None,
               ok=response.get("ok") if response else None,
               errorCode=response.get("errorCode") if response else None)
        return response
    except Exception as exc:
        record(jobId=job["jobId"], phase="broker-exception", exception=type(exc).__name__)
        raise


runner_worker.RunnerHarnessService._broker_once = broker
original_execute = runner_worker.execute


def execute(job, *args):
    result, finished, effect = original_execute(job, *args)
    record(jobId=job["jobId"], phase="execute", status=result.get("status"),
           errorCode=result.get("errorCode"), actualFinished=finished, effect=effect)
    return result, finished, effect


runner_worker.execute = execute
if os.environ.get("HARNESS_EVAL_RUNNER_UTF8") == "1":
    original_popen = subprocess.Popen
    def utf8_popen(command, *args, **kwargs):
        command = list(command)
        if any(str(arg).endswith("runner_bootstrap.py") for arg in command):
            command[1:1] = ["-X", "utf8"]
        return original_popen(command, *args, **kwargs)
    subprocess.Popen = utf8_popen
    record(phase="control", change="root tool child -X utf8; unchanged production sources")
runpy.run_module("harness", run_name="__main__")
