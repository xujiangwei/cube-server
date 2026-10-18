"""Run the frozen 50x3 matrix against an already isolated real Console/SQLite Harness.

Private JSON config: harnessBaseUrl, apiCredential, profiles {Baize:...,Baize2:...},
target, consoleBaseUrl, sqliteLedger, environmentEvidence (existing reviewed text).
No credentials are accepted on argv. Requires an explicit dedicated environment.
Language quality remains a separate human review, never silently marked passed.
"""
import argparse
import hashlib
import json
from pathlib import Path
import secrets
import sqlite3
import time
import urllib.error
import urllib.request


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    a = parser.parse_args()
    p = json.loads(a.config.read_text(encoding='utf-8'))
    scenario_path = Path(__file__).resolve().parents[1] / 'fixtures/n10-scenarios.json'
    matrix = json.loads(scenario_path.read_text(encoding='utf-8'))
    evidence = Path(p['environmentEvidence'])
    if p.get('environment') != 'dedicated-real-console' or not evidence.is_file():
        raise ValueError('Reviewed dedicated Console environment evidence required')
    if a.output.exists():
        raise ValueError('Use a fresh evidence output directory; prior failures must be retained')
    a.output.mkdir(parents=True)
    results = []

    def http(path, body=None):
        headers = {'Authorization': 'Bearer ' + p['apiCredential']}
        if body is not None:
            headers.update({'Content-Type': 'application/json', 'Idempotency-Key': secrets.token_hex(16)})
        req = urllib.request.Request(p['harnessBaseUrl'].rstrip('/') + path, headers=headers,
                                     data=None if body is None else json.dumps(body).encode())
        with urllib.request.urlopen(req, timeout=10) as x:
            return json.loads(x.read())

    def inspect(run, scenario):
        ledger = Path(p['sqliteLedger']).resolve()
        with sqlite3.connect(ledger.as_uri() + '?mode=ro', uri=True, timeout=5) as c:
            calls = c.execute('SELECT payload_json,result_json FROM harness_tool_call WHERE run_id=? AND status=\'confirmed\'', (run,)).fetchall()
        selected = [(json.loads(x), json.loads(y)) for x, y in calls if json.loads(x)['tool'] == 'cube_console.query_status']
        assert selected, 'no real executable result'
        for call, result in selected:
            assert result['status'] == 'success', 'executable failed'
            args = json.loads(call['argumentsJson'])
            assert args['targets'] == [p['target']] and set(args['fields']) == set(scenario['fields']), 'scope or fields differ'
            data = json.loads(result['dataJson'])
            assert len(data['nodes']) == 1, 'missing node'
            node = data['nodes'][0]
            assert node['target'] == p['target'] and node['queriedAt'] > 0, 'identity/time'
            assert node['source'].startswith(p['consoleBaseUrl'].rstrip('/') + '/'), 'wrong Console source'
            assert 'errorCode' not in node, 'upstream error'
            assert not set(node) - {'target','queriedAt','source','running','version','jvm','reportTimestamp','reportState'}, 'unexpected field'
            assert all(f in node for f in scenario['fields']), 'missing requested field'
            if 'jvm' in scenario['fields']:
                assert node['reportState'] in ('fresh','stale','placeholder','missing'), 'report age missing'
        return hashlib.sha256(json.dumps(selected, sort_keys=True).encode()).hexdigest()

    for model in matrix['models']:
        for scenario in matrix['scenarios']:
            for repetition in range(matrix['repetitions']):
                row = dict(model=model, scenario=scenario['id'], repetition=repetition+1, hardPass=False, quality='PENDING_REVIEW')
                try:
                    text = scenario['instructionTemplate'].replace('{target}', p['target'])
                    created = http('/harness/v1/runs', dict(taskProfileId=p['profiles'][model], input=dict(instruction=text, attachmentIds=[])))
                    row['runId'] = run = created['runId']
                    end = time.monotonic() + 240
                    while True:
                        read = http('/harness/v1/runs/' + run)
                        if read['snapshot']['state'] in ('succeeded','failed','cancelled','timed_out','waiting_input','waiting_approval'):
                            break
                        if time.monotonic() >= end:
                            raise TimeoutError('run incomplete; retained for reconciliation')
                        time.sleep(.3)
                    row['state'] = read['snapshot']['state']
                    assert row['state'] == 'succeeded' and read['snapshot']['executionSettlement'] == 'confirmed', 'run did not succeed'
                    row['resultEvidenceDigest'] = inspect(run, scenario)
                    row['final'] = read['result']
                    row['hardPass'] = True
                except Exception as exc:
                    row['failureType'] = type(exc).__name__  # no transport/secret text
                results.append(row)
                (a.output / 'attempts.json').write_text(json.dumps(results, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
        passed = sum(x['hardPass'] for x in results if x['model'] == model)
        print(model + ': hard checks ' + str(passed) + '/150; language review pending', flush=True)
    report = dict(scenariosSha256=hashlib.sha256(scenario_path.read_bytes()).hexdigest(),
                  environmentEvidenceSha256=hashlib.sha256(evidence.read_bytes()).hexdigest(),
                  models={name: dict(attempts=150, hardPasses=sum(x['hardPass'] for x in results if x['model']==name), minimum=143) for name in matrix['models']},
                  quality='PENDING_REVIEW', G2='NOT_APPROVED')
    (a.output / 'report.json').write_text(json.dumps(report, indent=2)+'\n')


if __name__ == '__main__':
    main()
