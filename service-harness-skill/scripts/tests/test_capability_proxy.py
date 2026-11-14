"""Local-only regression: a task shutdown cannot discard a late physical receipt."""
import http.server
import io
from contextlib import nullcontext
import importlib.util
import json
from pathlib import Path
import socket
import sys
import tempfile
import threading
from types import SimpleNamespace
import unittest
import urllib.request
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'capability_cell_evaluation.py'
sys.path.insert(0, str(SCRIPT.parent))
SPEC = importlib.util.spec_from_file_location('capability_cell_evaluation', SCRIPT)
evaluation = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(evaluation)


class ProxyReceiptTests(unittest.TestCase):
    def test_controlled_sse_has_complete_usage_and_both_parameter_layers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            image = root / 'image.jpg'
            image.write_bytes(b'fixture')
            remote = SimpleNamespace(token='offline', serial=threading.RLock())
            proxy = evaluation.LocalServer(remote, image, root, controlled=True)
            try:
                for native in (False, True):
                    body = dict(model='fixture', stream=True, messages=[dict(role='user', content='请完成 巡检 regionId=single。')])
                    if native:
                        body['tools'] = [dict(function=dict(name='alias_query', description='common.data.query: 查询'))]
                    request = urllib.request.Request(proxy.url + '/v1/chat/completions',
                        data=json.dumps(body, ensure_ascii=False).encode(), headers={'Content-Type':'application/json'})
                    with urllib.request.urlopen(request, timeout=3) as response:
                        self.assertEqual('text/event-stream', response.headers['Content-Type'])
                        result = evaluation.collect_sse(io.BytesIO(response.read()))
                    self.assertEqual(dict(prompt_tokens=0,completion_tokens=0,total_tokens=0), result['usage'])
                    message = result['choices'][0]['message']
                    if native:
                        call = message['tool_calls'][0]
                        self.assertEqual('alias_query', call['function']['name'])
                        arguments = json.loads(call['function']['arguments'])
                    else:
                        arguments = json.loads(json.loads(message['content'])['calls'][0]['argumentsJson'])
                    self.assertEqual('patrol/general', arguments['skill'])
                    self.assertEqual('patrol-records', arguments['connectorId'])
                    self.assertEqual('single', json.loads(arguments['argumentsJson'])['regionId'])
            finally:
                proxy.close()

    def test_shutdown_and_client_disconnect_retain_late_receipt(self):
        self.assert_late_receipt(False)

    def test_failed_header_delivery_does_not_create_remote_unknown(self):
        self.assert_late_receipt(True)

    def assert_late_receipt(self, fail_headers):
        started, release, closed = threading.Event(), threading.Event(), threading.Event()

        class DelayedModel(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_POST(self):
                self.rfile.read(int(self.headers['Content-Length']))
                started.set()
                release.wait(3)
                raw = json.dumps({'choices': [{'index': 0, 'finish_reason': 'stop',
                    'message': {'role': 'assistant', 'content': 'offline receipt'}}]}).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

        upstream = http.server.ThreadingHTTPServer(('127.0.0.1', 0), DelayedModel)
        threading.Thread(target=upstream.serve_forever, daemon=True).start()
        failures = []
        proxy = None
        try:
            with tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                image = root / 'image.jpg'
                image.write_bytes(b'not used by the text-only test')
                remote = SimpleNamespace(base='http://127.0.0.1:' + str(upstream.server_port),
                    token='offline-test', timeout=3, serial=threading.RLock(),
                    journal=evaluation.Journal(root / 'http.jsonl'))
                proxy = evaluation.LocalServer(remote, image, root)
                client = socket.create_connection(('127.0.0.1', proxy.server.server_port), timeout=3)
                raw = b'{"model":"offline-test"}'
                client.sendall(b'POST /v1/chat/completions HTTP/1.0\r\nContent-Length: '
                               + str(len(raw)).encode() + b'\r\n\r\n' + raw)
                self.assertTrue(started.wait(3))
                client.close()

                def close_proxy():
                    try:
                        proxy.close()
                    except Exception as error:
                        failures.append(error)
                    finally:
                        closed.set()

                header_failure = patch.object(proxy.server.RequestHandlerClass, 'end_headers',
                    side_effect=ConnectionResetError('offline simulated client reset')) if fail_headers else nullcontext()
                with header_failure:
                    closer = threading.Thread(target=close_proxy)
                    closer.start()
                    self.assertFalse(closed.wait(.1), 'shutdown discarded the in-flight model request')
                    release.set()
                    self.assertTrue(closed.wait(4))
                    closer.join(1)
                self.assertEqual([], failures)
                receipts = [json.loads(line) for line in (root / 'http.jsonl').read_text('utf8').splitlines()]
                intents = [json.loads(line) for line in (root / 'http-intents.jsonl').read_text('utf8').splitlines()]
                self.assertEqual(1, len(receipts))
                self.assertTrue(receipts[0]['actualFinished'])
                self.assertEqual('offline receipt', receipts[0]['response']['choices'][0]['message']['content'])
                self.assertEqual(['received', 'dispatch-started'], [r['stage'] for r in intents])
                self.assertTrue(all(r['proxyRequestId'] == receipts[0]['proxyRequestId'] for r in intents))
                proxy = None
        finally:
            release.set()
            if proxy:
                proxy.close()
            upstream.shutdown()
            upstream.server_close()


if __name__ == '__main__':
    unittest.main()
