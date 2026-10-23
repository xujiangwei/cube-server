"""Current scripted execution contracts, REST schemas and adapter capabilities."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ID = dict(type='string', minLength=1, maxLength=128, pattern='^[A-Za-z0-9_.:-]+$')
def obj(properties, required=None):
    return dict(type='object', properties=properties, required=list(properties) if required is None else required, additionalProperties=False)
def integer(low, high):
    return dict(type='integer', minimum=low, maximum=high)
def enum(*values):
    return dict(type='string', enum=list(values))

def generate(target):
    digest = dict(type='string', pattern='^[a-f0-9]{64}$')
    descriptor = obj(dict(model=enum('scripted'), backendId=ID, adapter=enum('scripted-v1'),
                          capacity=integer(1,32), nativeTools=dict(type='boolean'), cancellation=enum('observe-only'),
                          budget=enum('transport-bytes-v1')))
    job = obj(dict(jobId=digest, runId=ID, roundId=ID, attempt=integer(1,1), originFence=integer(1,10**12),
                   intentHash=digest, requestHash=digest, requestJson=dict(type='string',maxLength=65536),
                   workerId=ID, instanceId=ID, sessionId=ID, model=enum('scripted')))
    report = obj(dict(jobId=digest, requestHash=digest, instanceId=ID, actualFinished=dict(type='boolean'),
                      outcomeJson=dict(type='string',maxLength=65536)))
    base = dict(protocolVersion=dict(type='integer',enum=[2]), requestId=ID, credential=dict(type='string',minLength=24,maxLength=512),
                workerId=ID, instanceId=ID, role=enum('model'), capabilities=dict(type='array',items=enum('model.round'),minItems=1,maxItems=1))
    branches=[]
    for action in ('describe','poll','heartbeat','drain','unregister','actual_finished','status'):
        p=dict(base,action=enum(action))
        if action=='describe': p['descriptor']=descriptor
        else:
            p['sessionId']=ID
            p['healthy']=dict(type='boolean')
        if action=='actual_finished': p['report']=report
        branches.append(obj(p))
    response = obj(dict(protocolVersion=dict(type='integer',enum=[2]),requestId=dict(type='string',maxLength=128),ok=dict(type='boolean',enum=[True]),
                        errorCode=ID,sessionId=ID,leaseMillis=integer(1000,60000),registered=dict(type='boolean'),ready=dict(type='boolean'),
                        job=job,cancellations=dict(type='array',items=digest,maxItems=32),capacity=integer(0,1024),occupied=integer(0,1024)),['protocolVersion','requestId','ok','sessionId','leaseMillis','registered','ready','cancellations'])
    response['properties'].pop('errorCode')
    response=dict(oneOf=[response,obj(dict(protocolVersion=dict(type='integer',enum=[2]),requestId=dict(type='string',maxLength=128),ok=dict(type='boolean',enum=[False]),errorCode=ID))])
    usage=obj(dict(input=integer(0,10**12),output=integer(0,10**12)))
    audit=obj(dict(attempt=integer(1,2),requestHash=digest,responseHash=digest,finishReason=dict(type='string',maxLength=64),errorCode=ID,usage=usage),['attempt','requestHash'])
    outcome=obj(dict(status=enum('success','error','unknown'),decision=json.loads((ROOT/'contracts/schemas/model-decision.json').read_text()),errorCode=ID,
                     usage=usage,audit=dict(type='array',items=audit,maxItems=2),cancellationObserved=dict(type='boolean')),['status','audit','cancellationObserved'])
    schemas={'model-worker-request':dict(oneOf=branches),'model-worker-response':response,'model-descriptor':descriptor,'model-job':job,'model-report':report,'model-outcome':outcome}
    snapshot=json.loads((ROOT/'contracts/schemas/run-snapshot.json').read_text());snapshot.pop('$schema',None)
    event=json.loads((ROOT/'contracts/schemas/event.json').read_text());event.pop('$schema',None)
    amounts=obj({k:integer(0,10**15) for k in ('estimatedInput','estimatedOutput','actualInput','actualOutput')})
    amounts['properties']['pending']=dict(oneOf=[integer(0,10**9),dict(type='null')]);amounts['required'].append('pending')
    result=obj(dict(state=enum('succeeded','failed','cancelled','timed_out'),executionSettlement=enum('confirmed','unknown'),
                    result=dict(type='object'),usage=dict(type='array',items=dict(type='object'),maxItems=64)))
    schemas['run-read']=obj(dict(snapshot=snapshot,usageSettlement=enum('settled','provisional'),usage=amounts,result=dict(oneOf=[dict(type='null'),result])))
    schemas['events-page']=obj(dict(events=dict(type='array',items=event,maxItems=100),nextCursor=integer(0,9007199254740991)))
    path=target/'execution'; (path/'schemas').mkdir(parents=True,exist_ok=True)
    manifest={}
    for name,value in schemas.items():
        raw=(json.dumps(dict(value,**{'$schema':'http://json-schema.org/draft-07/schema#'}),ensure_ascii=False,indent=2)+'\n').encode()
        name='schemas/'+name+'.json'; (path/name).write_bytes(raw);manifest[name]=hashlib.sha256(raw).hexdigest()
    adapters={}
    for name,model in [('scripted-v1','scripted')]:
        spec=dict(adapter=name,model=model,protocolVersion=2,nativeTools=False,budget='transport-bytes-v1',tokenizer='transport-bytes-v1; remote tokenizer unverified',template='harness-json-template-v1; remote chat template unverified',cancellation='observe-only',maxRepairs=1)
        adapters[name]=dict(spec=spec,digest=hashlib.sha256(json.dumps(spec,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest())
    raw=(json.dumps(adapters,indent=2)+'\n').encode();(path/'adapter-profiles.json').write_bytes(raw);manifest['adapter-profiles.json']=hashlib.sha256(raw).hexdigest()
    def success(schema,status='200'):
        return {status:dict(description='Durable success',content={'application/json':dict(schema={'$ref':schema})})}
    operations={
        '/harness/v1/runs':{'post':dict(summary='Create after durable commit; bounded admission',parameters=[dict(name='Idempotency-Key',**{'in':'header'},required=True,schema=ID)],requestBody=dict(required=True,content={'application/json':dict(schema={'$ref':'../schemas/create-run.json'})}),responses=success('../schemas/run-snapshot.json','202'))},
        '/harness/v1/runs/{runId}':{'get':dict(summary='Read snapshot, immutable final and separate actual usage',responses=success('schemas/run-read.json'))},
        '/harness/v1/runs/{runId}/events':{'get':dict(summary='Finite ordered page; SSE reconnect with Last-Event-ID; 2 second deadline',parameters=[dict(name='after',**{'in':'query'},schema=integer(0,9007199254740991)),dict(name='limit',**{'in':'query'},schema=integer(1,100)),dict(name='Last-Event-ID',**{'in':'header'},schema=dict(type='string',pattern='^[0-9]{1,15}$'))],responses=success('schemas/events-page.json'))},
        '/harness/v1/capabilities':{'get':dict(summary='Protocol 2, model units, health and physical/effective capacity',responses={'200':dict(description='Enabled execution diagnostics')})}}
    for route,methods in operations.items():
        for operation in methods.values():
            operation['security']=[dict(HarnessBearer=[])]
            if '{' in route:
                name='runId' if '{runId}' in route else 'id'
                operation.setdefault('parameters',[]).insert(0,dict(name=name,**{'in':'path'},required=True,schema=ID))
            for code,description in [('400','Invalid schema/cursor'),('401','Independent Harness credential required'),('404','No authorized resource'),('409','Idempotency conflict or EVENT_GAP'),('429','Bounded admission or SSE slots full'),('503','Disabled/not ready/store unavailable')]:
                operation['responses'][code]=dict(description=description,content={'application/json':dict(schema={'$ref':'../schemas/error.json'})})
    raw=(json.dumps(dict(openapi='3.0.3',info=dict(title='Cube Harness task execution',version='2'),paths=operations,components=dict(securitySchemes=dict(HarnessBearer=dict(type='http',scheme='bearer')))),indent=2)+'\n').encode()
    (path/'openapi.json').write_bytes(raw);manifest['openapi.json']=hashlib.sha256(raw).hexdigest()
    (path/'manifest.json').write_bytes((json.dumps(manifest,indent=2)+'\n').encode('utf-8'))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--python-root',type=Path,required=True);args=parser.parse_args()
    generate(ROOT/'contracts');generate(args.python_root/'harness'/'contracts')
