"""Offline state replay. Stdlib/file input only; no providers, credentials, execution or network imports.

Run: python -I -S replay_trace.py trace.json
Redacted mock outputs preserve structure/commitments; replay does not regenerate natural language.
"""
import argparse
import hashlib
import json
from pathlib import Path

def canonical(v):
    return json.dumps(v, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)
def digest(v): return hashlib.sha256(canonical(v).encode()).hexdigest()
def pairs(items):
    out={}
    for k,v in items:
        if k in out: raise ValueError('DUPLICATE_KEY')
        out[k]=v
    return out
def load(path):
    with Path(path).open('rb') as f: raw=f.read(262145)
    if len(raw)>262144: raise ValueError('TRACE_TOO_LARGE')
    return json.loads(raw,object_pairs_hook=pairs,parse_constant=lambda _: (_ for _ in ()).throw(ValueError('INVALID_NUMBER')))
def replay(value):
    v=dict(value); expected=v.pop('digest')
    if digest(v)!=expected: raise ValueError('TRACE_DIGEST_MISMATCH')
    if v['schemaVersion']!=1 or v['kind']!='harness-redacted-state-trace' or not v['replayComplete']:
        raise ValueError('REPLAY_INCOMPLETE')
    state=None
    for seq,e in enumerate(v['events'],1):
        p=e['projection']
        if e['event_seq']!=seq or e['run_version']!=seq or p['eventSeq']!=seq or p['version']!=seq or p['runId']!=v['runId']:
            raise ValueError('REPLAY_EVENT_GAP')
        if p['state'] not in ('queued','running','reconciling','waiting_input','waiting_approval','cancelling','succeeded','failed','cancelled','timed_out'):
            raise ValueError('REPLAY_STATE_INVALID')
        if state and state['state'] in ('succeeded','failed','cancelled','timed_out') and state['state']!=p['state']:
            raise ValueError('REPLAY_TERMINAL_CHANGED')
        state=p
    if state!=v['snapshot']:raise ValueError('REPLAY_FINAL_MISMATCH')
    mocks=[dict(kind=k,id=r['intent_id'],attempt=r['attempt'],output=r.get('result_json')) for k in ('rounds','calls') for r in v[k]]
    return dict(snapshot=state,mockOutputs=mocks,stateDigest=digest(state),traceDigest=expected)
if __name__=='__main__':
    p=argparse.ArgumentParser(__doc__);p.add_argument('trace',type=Path);a=p.parse_args()
    print(canonical(replay(load(a.trace))))
