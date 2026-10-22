"""Generate Harness redacted trace and append-only feedback contracts."""
import argparse, hashlib, json
from pathlib import Path

MODULE = Path(__file__).resolve().parents[1]

def generate(python_root):
    def obj(properties, required=None):
        return dict(type='object', properties=properties, required=list(properties) if required is None else required, additionalProperties=False)
    def array(item, maximum=None):
        result=dict(type='array',items=item)
        if maximum is not None:result['maxItems']=maximum
        return result
    text=dict(type='string');number=dict(type='integer',minimum=0);nullable_number=dict(type=['integer','null'],minimum=0)
    digest=dict(type='string',pattern='^[a-f0-9]{64}$')
    snapshot=json.loads((MODULE/'contracts/schemas/run-snapshot.json').read_text())
    snapshot.pop('$schema',None)
    snapshot['properties']['usageSettlement']=dict(type='string',enum=['provisional','settled'])
    snapshot['required'].append('usageSettlement')
    event=obj(dict(event_seq=number,run_version=number,event_type=text,occurred_at=number,projection=snapshot),['event_seq','run_version','event_type','occurred_at'])
    content=obj(dict(intent_id=text,attempt=number,intent_hash=digest,origin_fence=number,status=text,payload_json=dict(type='object'),result_json=dict(type=['object','null']),result_hash=dict(type=['string','null']),payload_json_sha256=digest,result_json_sha256=digest,argumentsHash=digest,tool=text),['intent_id','attempt','intent_hash','origin_fence','status','payload_json','result_json','result_hash','payload_json_sha256'])
    def rows(columns, numeric=(), nullable=()):
        return array(obj({k:(nullable_number if k in nullable else number) if k in numeric else dict(type=['string','null']) if k in nullable else text for k in columns.split()}))
    common='job_id attempt worker_id status actual_finished cancel_requested created_at dispatched_at finished_at cancelled_at'
    numeric=('attempt','actual_finished','cancel_requested','created_at','dispatched_at','finished_at','cancelled_at')
    nullable=('worker_id','dispatched_at','finished_at','cancelled_at')
    props=dict(schemaVersion=dict(type='integer',enum=[1]),kind=dict(type='string',enum=['harness-redacted-state-trace']),runId=text,redaction=dict(type='string',enum=['all-content-strings-sha256']),replayComplete=dict(type='boolean'),snapshot=snapshot,config=dict(type=['object','null']),events=array(event,1024),rounds=array(content,256),calls=array(content,256),
        usage=rows('round_id attempt estimated_input estimated_output actual_input actual_output revision',('attempt','estimated_input','estimated_output','actual_input','actual_output','revision'),('actual_input','actual_output')),
        modelJobs=rows(common+' round_id model_id profile_digest backend_id',numeric,nullable),
        toolJobs=rows(common+' call_id release_digest tool_name effect_status cleanup_after cleanup_done',numeric+('cleanup_after','cleanup_done'),nullable+('cleanup_after',)),
        inferences=rows('inference_id job_id wire_attempt backend_id status actual_finished cancel_requested error_code created_at dispatched_at finished_at cancelled_at',('wire_attempt','actual_finished','cancel_requested','created_at','dispatched_at','finished_at','cancelled_at'),('error_code','dispatched_at','finished_at','cancelled_at')),
        files=rows('file_id kind size_bytes sha256 status created_at ready_at expires_at deleted_at attempts',('size_bytes','created_at','ready_at','expires_at','deleted_at','attempts'),('sha256','ready_at','deleted_at')),
        feedback=rows('feedback_id payload_hash rating created_at',('created_at',)),resultCommitment=dict(type=['object','null']),timingCoverage=text,metrics=dict(type='object'),digest=digest)
    trace=obj(props);trace['$schema']='http://json-schema.org/draft-07/schema#'
    receipt=obj(dict(feedbackId=digest,accepted=dict(type='boolean',enum=[True])))
    feedback=json.loads((MODULE/'contracts/schemas/feedback.json').read_text())
    files={'schemas/trace.json':trace,'schemas/feedback.json':feedback,'schemas/feedback-receipt.json':receipt}
    paths={}
    for suffix,method,security,schema,status in [('trace','get','TraceReader','trace',200),('feedback','post','HarnessBearer','feedback-receipt',202)]:
        operation=dict(security=[{security:[]}],parameters=[dict(name='runId',**{'in':'path'},required=True,schema=dict(type='string',maxLength=128))],responses={str(status):dict(description='Bounded diagnostic export' if suffix=='trace' else 'Append-only durable receipt',content={'application/json':dict(schema={'$ref':'schemas/'+schema+'.json'})}),**{str(code):dict(description=description) for code,description in [(400,'Disabled, invalid or expired diagnostic request; TRACE_EXPIRED is currently HTTP 400'),(401,'Invalid API credential'),(403,'Separate reader credential required; reader cannot perform mutations'),(404,'Run not owned by configured tenant/subject'),(409,'Idempotency conflict'),(503,'Store unavailable')]}})
        if method=='post':
            operation['parameters'].append(dict(name='Idempotency-Key',**{'in':'header'},required=True,schema=dict(type='string',maxLength=128)))
            operation['requestBody']=dict(required=True,content={'application/json':dict(schema={'$ref':'schemas/feedback.json'})})
        paths['/harness/v1/runs/{runId}/'+suffix]={method:operation}
    files['openapi.json']=dict(openapi='3.0.3',info=dict(title='Harness independent redacted Trace and append-only feedback',version='1.0.0'),paths=paths,components=dict(securitySchemes={name:dict(type='http',scheme='bearer') for name in ('TraceReader','HarnessBearer')}))
    raw={k:(json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode() for k,v in files.items()}
    raw['manifest.json']=(json.dumps({k:hashlib.sha256(v).hexdigest() for k,v in raw.items()},indent=2)+'\n').encode()
    for root in (MODULE/'contracts/trace',python_root/'harness/contracts/trace'):
        for name,value in raw.items():
            target=root/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(value)

if __name__=='__main__':
    p=argparse.ArgumentParser(__doc__);p.add_argument('--python-root',type=Path,required=True);generate(p.parse_args().python_root)
