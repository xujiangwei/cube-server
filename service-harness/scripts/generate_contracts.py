from pathlib import Path
import hashlib, json

import argparse
parser=argparse.ArgumentParser()
parser.add_argument('--python-contracts', type=Path)
args=parser.parse_args()
java=Path(__file__).resolve().parent.parent
roots=[java/'contracts']
if args.python_contracts: roots.append(args.python_contracts.resolve())
def obj(properties, required=None):
    return dict(type='object', properties=properties, required=list(properties) if required is None else required, additionalProperties=False)
def text(n=256): return dict(type='string', minLength=1, maxLength=n)
def array(items, maximum=64): return dict(type='array', items=items, maxItems=maximum)
identity = dict(type='string', minLength=1, maxLength=128, pattern=r'^[A-Za-z0-9_.:-]+$')
integer = dict(type='integer', minimum=0, maximum=9007199254740991)
schemas = {}
schemas['worker-request'] = obj(dict(protocolVersion=dict(type='integer',enum=[1]), action=dict(type='string',enum=['register','heartbeat','echo','drain','unregister']),requestId=identity,credential=text(1024),workerId=identity,instanceId=identity,role=dict(type='string',enum=['model','runner']),capabilities=array(dict(type='string',enum=['scripted.echo'])),sessionId=text(128),payload=obj(dict(text=dict(type='string',maxLength=8192)))),['protocolVersion','action','requestId','credential','workerId','instanceId','role','capabilities'])
schemas['worker-response'] = obj(dict(protocolVersion=dict(type='integer',enum=[1]),requestId=dict(type='string',maxLength=128),ok=dict(type='boolean'),errorCode=dict(type='string',maxLength=128),sessionId=dict(type='string',maxLength=128),leaseMillis=integer,registered=dict(type='boolean'),ready=dict(type='boolean'),payload=obj(dict(text=dict(type='string',maxLength=8192)))),['protocolVersion','requestId','ok'])
schemas['create-run'] = obj(dict(taskProfileId=identity,input=obj(dict(instruction=text(32768),attachmentIds=array(identity,16)))))
schemas['cancel-run'] = obj(dict(expectedVersion=integer,reason=dict(type='string',maxLength=1024)),['expectedVersion'])
schemas['resume-run'] = obj(dict(expectedVersion=integer,waitId=identity,input=obj(dict(instruction=text(32768),attachmentIds=array(identity,16)))))
schemas['approval'] = obj(dict(expectedVersion=integer,decision=dict(type='string',enum=['approve','reject']),argumentsHash=dict(type='string',pattern='^[a-f0-9]{64}$'),reason=dict(type='string',maxLength=1024)),['expectedVersion','decision','argumentsHash'])
schemas['input-create'] = obj(dict(fileName=text(256),mediaType=text(128),size=integer,sha256=dict(type='string',pattern='^[a-f0-9]{64}$')))
schemas['input-complete'] = obj(dict(sha256=dict(type='string',pattern='^[a-f0-9]{64}$')))
schemas['feedback'] = obj(dict(rating=dict(type='string',enum=['positive','negative']),reason=dict(type='string',maxLength=4096)),['rating'])
schemas['error'] = obj(dict(errorCode=text(128),requestId=identity,retryable=dict(type='boolean')))
schemas['profile'] = obj(dict(profileId=identity,version=identity,modelProfile=identity,skillReleases=array(identity),toolSetVersion=identity,runtimeProfile=identity,requiredCapabilities=array(identity)))
schemas['model-decision'] = obj(dict(kind=dict(type='string',enum=['final','tool_calls','need_input']),text=dict(type='string',maxLength=65536),calls=array(obj(dict(proposalId=identity,tool=identity,argumentsJson=text(65536))),16),question=dict(type='string',maxLength=4096)),['kind'])
schemas['run-snapshot'] = obj(dict(runId=identity,version=integer,eventSeq=integer,state=dict(type='string',enum=['queued','running','waiting_input','waiting_approval','cancelling','reconciling','succeeded','failed','cancelled','timed_out']),executionSettlement=dict(type='string',enum=['pending','confirmed','unknown']),taskProfileVersion=identity))
schemas['event'] = obj(dict(runId=identity,eventSeq=integer,runVersion=integer,type=identity,occurredAt=integer,schemaVersion=dict(type='integer',enum=[1]),dataJson=dict(type='string',maxLength=65536)))
schemas['grant'] = obj(dict(runId=identity,callId=identity,release=identity,tool=identity,argumentsHash=dict(type='string',pattern='^[a-f0-9]{64}$'),subjectId=identity,tenantId=identity,expiresAt=integer,fencingToken=integer))
schemas['artifact'] = obj(dict(artifactId=identity,runId=identity,fileName=text(256),mediaType=text(128),size=integer,sha256=dict(type='string',pattern='^[a-f0-9]{64}$'),expiresAt=integer))
schemas['release'] = obj(dict(name=identity,sha256=dict(type='string',pattern='^[a-f0-9]{64}$'),schemaVersion=dict(type='integer',enum=[1]),resources=array(text(256)),runtimeProfile=identity))
schemas['checkpoint'] = obj(dict(runId=identity,version=integer,contextDigest=dict(type='string',pattern='^[a-f0-9]{64}$'),pendingCallIds=array(identity),expiresAt=integer))
schemas['tool-result'] = obj(dict(status=dict(type='string',enum=['success','partial','error','unknown']),dataJson=dict(type='string',maxLength=65536),artifacts=array(identity),errorCode=dict(type='string',maxLength=128)),['status','artifacts'])

# Exclusive variants prevent fields from one decision/action leaking into another.
common=schemas['worker-request']['properties']
branches=[]
for action in ['register','heartbeat','echo','drain','unregister']:
    props={k:v for k,v in common.items() if k not in ('sessionId','payload')}
    props['action']=dict(type='string',enum=[action])
    if action!='register':props['sessionId']=common['sessionId']
    if action=='echo':props['payload']=common['payload']
    branches.append(obj(props))
schemas['worker-request']={'oneOf':branches}
response=schemas['worker-response']['properties']
success={k:v for k,v in response.items() if k!='errorCode'}
success['ok']=dict(type='boolean',enum=[True])
failure={k:response[k] for k in ['protocolVersion','requestId','ok','errorCode']}
failure['ok']=dict(type='boolean',enum=[False])
schemas['worker-response']={'oneOf':[obj(success,[k for k in success if k!='payload']),obj(failure)]}
decision=schemas['model-decision']['properties']
calls=dict(decision['calls'],minItems=1)
schemas['model-decision']={'oneOf':[
    obj(dict(kind=dict(type='string',enum=['final']),text=decision['text'])),
    obj(dict(kind=dict(type='string',enum=['tool_calls']),calls=calls)),
    obj(dict(kind=dict(type='string',enum=['need_input']),question=text(4096)))
]}
paths = {}
worker_view=obj(dict(workerId=identity,instanceId=identity,role=dict(type='string',enum=['model','runner']),registered=dict(type='boolean'),ready=dict(type='boolean'),capabilities=array(dict(type='string',enum=['scripted.echo']))))
schemas['capabilities-response']=obj(dict(service=dict(type='string',enum=['Harness']),protocolVersion=dict(type='integer',enum=[1]),registeredWorkers=array(worker_view),infrastructureReady=dict(type='boolean'),runsReady=dict(type='boolean',enum=[False]),execution=dict(type='string',enum=['scripted'])))
schemas['profiles-response']=obj(dict(profiles=array(schemas['profile'])))
schemas['health-response']=obj(dict(service=dict(type='string',enum=['Harness']),ready=dict(type='boolean'),runsReady=dict(type='boolean',enum=[False])))
def endpoint(path, method, schema=None, status='503', summary='Reserved; G0 returns HARNESS_NOT_READY'):
    operation={'summary':summary,'security':[{'HarnessBearer':[]}], 'responses':{'401':{'description':'Unauthorized'},'403':{'description':'Forbidden'},'400':{'description':'Invalid request'},status:{'description':'G0 readiness response' if status=='200' else 'Capability not ready'}}}
    parameters=[]
    import re
    for name in re.findall(r'\{(\w+)\}',path): parameters.append(dict(name=name,**{'in':'path'},required=True,schema=identity))
    if method in ('post','put'): parameters += [dict(name='Idempotency-Key',**{'in':'header'},required=True,schema=identity)]
    if parameters: operation['parameters']=parameters
    if schema:operation['requestBody']={'required':True,'content':{'application/json':{'schema':{'$ref':'#/components/schemas/'+schema}}}}
    for code in ['400','401','403','404','405','409','413','415','503']:
        operation['responses'].setdefault(code,{'description':'Harness error'})
        operation['responses'][code]['content']={'application/json':{'schema':{'$ref':'#/components/schemas/error'}}}
    paths.setdefault(path,{})[method]=operation
endpoint('/harness/v1/capabilities','get',status='200',summary='Infrastructure capability readiness; no task execution at G0')
endpoint('/harness/v1/task-profiles','get',status='200',summary='G0 returns an empty executable profile list')
for path,name in [('/harness/v1/capabilities','capabilities-response'),('/harness/v1/task-profiles','profiles-response')]:
    paths[path]['get']['responses']['200']['content']={'application/json':{'schema':{'$ref':'#/components/schemas/'+name}}}
endpoint('/harness/v1/task-profiles/{profileId}','get')
endpoint('/harness/v1/runs','post','create-run')
endpoint('/harness/v1/runs/{runId}','get')
endpoint('/harness/v1/runs/{runId}/events','get')
for action, schema in [('cancel','cancel-run'),('resume','resume-run'),('feedback','feedback')]:endpoint('/harness/v1/runs/{runId}/'+action,'post',schema)
endpoint('/harness/v1/runs/{runId}/approvals/{approvalId}/decision','post','approval')
endpoint('/harness/v1/inputs','post','input-create')
endpoint('/harness/v1/inputs/{inputId}','get')
endpoint('/harness/v1/inputs/{inputId}/content','put')
endpoint('/harness/v1/inputs/{inputId}/complete','post','input-complete')
endpoint('/harness/v1/runs/{runId}/artifacts','get')
endpoint('/harness/v1/artifacts/{artifactId}','get')
endpoint('/harness/v1/artifacts/{artifactId}/content','get')
for resource,schema in [('packages','release'),('releases','release'),('task-profiles','profile')]:endpoint('/harness-admin/v1/'+resource,'post',schema)
for action in ['activate','drain','retire']:endpoint('/harness-admin/v1/releases/{releaseId}/'+action,'post','cancel-run')
endpoint('/harness-admin/v1/certifications','get')
openapi=dict(openapi='3.1.0',info=dict(title='Cube Harness',version='1.0.0'),paths=paths,components=dict(securitySchemes={'HarnessBearer':dict(type='http',scheme='bearer')},schemas=schemas))
fixtures=[]
def fixture(name,schema,value,expected,raw=False):fixtures.append(dict(name=name,schema=schema,raw=value if raw else json.dumps(value,ensure_ascii=False,separators=(',',':')),expected=expected))
worker=dict(protocolVersion=1,action='register',requestId='req-1',credential='fixture-only',workerId='model-1',instanceId='instance-1',role='model',capabilities=['scripted.echo'])
fixture('worker-register','worker-request',worker,'OK')
fixture('worker-version','worker-request',dict(worker,protocolVersion=2),'SCHEMA_INVALID')
fixture('worker-role','worker-request',dict(worker,role='AIGC'),'SCHEMA_INVALID')
fixture('unknown-field','worker-request',dict(worker,grant='arbitrary'),'SCHEMA_INVALID')
for key in worker:fixture('missing-'+key,'worker-request',{k:v for k,v in worker.items() if k!=key},'SCHEMA_INVALID')
run=dict(taskProfileId='cube-server-status.readonly.v1',input=dict(instruction='检查服务器',attachmentIds=[]))
fixture('new-run','create-run',run,'OK')
fixture('legacy-chat','create-run',dict(run,channelCode='legacy'),'SCHEMA_INVALID')
fixture('wrong-attachment','create-run',dict(run,input=dict(instruction='x',attachmentIds=['../secret'])),'SCHEMA_INVALID')
fixture('cancel','cancel-run',dict(expectedVersion=1),'OK')
fixture('cancel-negative','cancel-run',dict(expectedVersion=-1),'SCHEMA_INVALID')
for name,raw in [('duplicate','{"a":1,"a":2}'),('nested-duplicate','{"a":{"x":1,"x":2}}'),('nan','{"a":NaN}'),('infinity','{"a":1e999}'),('truncated','{"a":'),('comment','{/*x*/"a":1}'),('trailing','{}{}'),('single-quote',"{'a':1}"),('control','{"a":"\u0001"}'),('depth','['*34+'0'+']'*34),('fence','```json\n{}\n```'),('trailing-comma','{"a":1,}')]:fixture(name,'create-run',raw,'INVALID_JSON',True)
for name,schema,data in [('decision','model-decision',dict(kind='final',text='done')),('artifact','artifact',dict(artifactId='a1',runId='r1',fileName='x.txt',mediaType='text/plain',size=2,sha256='0'*64,expiresAt=1))]:fixture(name,schema,data,'OK')

for name,value,expected in [
    ('heartbeat-no-session',dict(worker,action='heartbeat'),'SCHEMA_INVALID'),
    ('register-with-session',dict(worker,sessionId='stale'),'SCHEMA_INVALID'),
    ('echo-no-payload',dict(worker,action='echo',sessionId='s1'),'SCHEMA_INVALID'),
    ('heartbeat',dict(worker,action='heartbeat',sessionId='s1'),'OK'),
    ('bool-version',dict(worker,protocolVersion=True),'SCHEMA_INVALID')
]:fixture(name,'worker-request',value,expected)
for name,value,expected in [
    ('missing-final-text',dict(kind='final'),'SCHEMA_INVALID'),
    ('mixed-decision',dict(kind='final',text='x',calls=[]),'SCHEMA_INVALID'),
    ('empty-tool-calls',dict(kind='tool_calls',calls=[]),'SCHEMA_INVALID'),
    ('empty-question',dict(kind='need_input',question=''),'SCHEMA_INVALID')
]:fixture(name,'model-decision',value,expected)
fixture('integer-as-string','cancel-run',dict(expectedVersion='1'),'SCHEMA_INVALID')
fixture('boolean-as-integer','cancel-run',dict(expectedVersion=True),'SCHEMA_INVALID')
fixture('non-ascii-escape','create-run','{"x":"\\uＦＦＦＦ"}','INVALID_JSON',True)
fixture('escaped-duplicate-key','create-run','{"a":1,"\\u0061":2}','INVALID_JSON',True)
for root in roots:
    (root/'schemas').mkdir(parents=True,exist_ok=True)
    for name,value in schemas.items():(root/'schemas'/f'{name}.json').write_text(json.dumps(dict(value,**{'$schema':'http://json-schema.org/draft-07/schema#'}),ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (root/'openapi.json').write_text(json.dumps(openapi,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    (root/'fixtures.json').write_text(json.dumps(fixtures,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    # Version 2 execution assets have their own manifest and must not change the frozen G0 digest.
    files=sorted(p for p in root.rglob('*.json') if p.name!='manifest.json' and 'execution' not in p.relative_to(root).parts)
    manifest={str(p.relative_to(root)).replace('\\','/'):hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
    (root/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n',encoding='utf-8')
print('Created mirrored schemas, OpenAPI and',len(fixtures),'fixtures')
