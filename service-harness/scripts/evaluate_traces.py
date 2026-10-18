"""Frozen fixture/append-only observation evaluator; never invokes a model or retries a run.

Observations are JSONL, each includes scenarioId, repetition (1..3), attempt, model/profile/configDigest,
mode(real/mock/skip), hardPassed, languagePassed, traceDigest and recordedAt. Every attempt is counted.
Retries cannot replace a failed original; each scenario/repetition passes only if ALL its attempts pass.
"""
import argparse, hashlib, json
from pathlib import Path
from replay_trace import replay

def hard_assertions(scene, deployment, trace):
    replay(trace)
    if trace.get('config',{}).get('digest')!=deployment['configDigest']:return False
    tools=[call.get('tool') for call in trace['calls']]
    if sorted(tools)!=sorted(scene['expectedTools']):return False
    if trace['snapshot']['state']!=scene['expectedState']:return False
    if len(trace['rounds'])>scene['budget']['maxRounds'] or len(trace['calls'])>scene['budget']['maxCalls']:return False
    for column,limit in (('actual_input','maxInputTokens'),('actual_output','maxOutputTokens')):
        if limit in scene['budget'] and sum(u.get(column) or 0 for u in trace['usage'])>scene['budget'][limit]:return False
    # Claims are checked against the server's recorded argument commitment, never model prose.
    for assertion in scene['assertions']:
        if assertion['kind']=='argumentsHash':
            if not any(c.get('argumentsHash')==assertion['value'] for c in trace['calls']):return False
        elif assertion['kind']=='fileSha256':
            if not any(f['sha256']==assertion['value'] and f['status'] in ('ready','deleted') for f in trace['files']):return False
        elif assertion['kind']=='allPhysicalFinished':
            if any(not j['actual_finished'] for j in trace['toolJobs']+trace['modelJobs']+trace.get('inferences',[])):return False
        else:raise ValueError('UNKNOWN_HARD_ASSERTION')
    if scene['expectedScope'] and not any(a['kind']=='argumentsHash' for a in scene['assertions']):
        raise ValueError('SCOPE_COMMITMENT_REQUIRED')
    return True

def evaluate(fixture, observations, traces=None):
    required=('input','expectedTools','expectedScope','assertions','faults','budget','expectedState','quality')
    if fixture.get('status')!='FROZEN' or fixture.get('schemaVersion')!=1:raise ValueError('FIXTURE_NOT_FROZEN')
    scenarios=fixture['scenarios'];ids=[s['id'] for s in scenarios]
    if len(ids)!=len(set(ids)) or len(ids)<50 or any(any(k not in s for k in required) for s in scenarios):
        raise ValueError('FIXTURE_INCOMPLETE')
    frozen=hashlib.sha256(json.dumps(fixture,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    rows=[];seen=set()
    for r in observations:
        if r['fixtureDigest']!=frozen or r['scenarioId'] not in ids or r['repetition'] not in (1,2,3) or type(r['attempt']) is not int or r['attempt']<1:
            raise ValueError('OBSERVATION_IDENTITY_INVALID')
        key=(r['model'],r['profile'],r['configDigest'],r['scenarioId'],r['repetition'],r['attempt'])
        if key in seen:raise ValueError('DUPLICATE_OBSERVATION')
        seen.add(key);rows.append(r)
    real_digests=[r['traceDigest'] for r in rows if r['mode']=='real']
    if len(real_digests)!=len(set(real_digests)):raise ValueError('REAL_TRACE_REUSED')
    reports=[]
    for deployment in fixture['deployments']:
        matching=[r for r in rows if all(r[k]==deployment[k] for k in ('model','profile','configDigest'))]
        real=[r for r in matching if r['mode']=='real'];slots={}
        for r in real:
            if not isinstance(r.get('hardPassed'),bool) or not isinstance(r.get('languagePassed'),bool) or not r.get('traceDigest') or not r.get('recordedAt'):
                raise ValueError('REAL_EVIDENCE_REQUIRED')
            if traces is None or r['traceDigest'] not in traces:raise ValueError('TRACE_ARTIFACT_REQUIRED')
            trace=traces[r['traceDigest']]
            if trace.get('digest')!=r['traceDigest']:raise ValueError('TRACE_IDENTITY_MISMATCH')
            r['hardPassed']=r['hardPassed'] and hard_assertions(next(s for s in scenarios if s['id']==r['scenarioId']),deployment,trace)
            slots.setdefault((r['scenarioId'],r['repetition']),[]).append(r)
        hardFailures=sum(not r['hardPassed'] for r in real)
        passed=sum(all(r['hardPassed'] and r['languagePassed'] for r in attempts) and sorted(r['attempt'] for r in attempts)==list(range(1,len(attempts)+1)) for attempts in slots.values())
        total=len(ids)*3;threshold=(total*95+99)//100
        reports.append(dict(**deployment,result='PASS' if len(slots)==total and hardFailures==0 and passed>=threshold else 'NOT_CERTIFIED',requiredRuns=total,requiredPasses=threshold,coveredRuns=len(slots),passed=passed,hardFailures=hardFailures,languageFailures=sum(not r['languagePassed'] for r in real),allAttempts=len(real),mock=sum(r['mode']=='mock' for r in matching),skip=sum(r['mode']=='skip' for r in matching)))
    return dict(fixtureDigest=frozen,deployments=reports,result='PASS' if reports and all(r['result']=='PASS' for r in reports) else 'NOT_CERTIFIED')
if __name__=='__main__':
    p=argparse.ArgumentParser(__doc__);p.add_argument('fixture',type=Path);p.add_argument('observations',type=Path);p.add_argument('--traces-root',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    observations=[json.loads(s) for s in a.observations.read_text(encoding='utf-8').splitlines() if s.strip()]
    traces={}
    for r in observations:
        if r['mode']=='real':
            import re
            if not a.traces_root or not re.fullmatch('[a-f0-9]{64}',r['traceDigest']):raise ValueError('TRACE_ARTIFACT_REQUIRED')
            path=a.traces_root/(r['traceDigest']+'.json')
            if path.is_symlink() or path.stat().st_size>262144:raise ValueError('TRACE_FILE_INVALID')
            traces[r['traceDigest']]=json.loads(path.read_text(encoding='utf-8'))
    report=evaluate(json.loads(a.fixture.read_text(encoding='utf-8')),observations,traces);a.output.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    raise SystemExit(0 if report['result']=='PASS' else 2)
