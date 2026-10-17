"""Create a new private G0 configuration directory. Never prints credentials."""
import argparse
import json
import os
from pathlib import Path
import secrets

parser=argparse.ArgumentParser()
parser.add_argument('--output',type=Path,required=True)
parser.add_argument('--cell-port',type=int,default=7600)
parser.add_argument('--http-port',type=int,default=7088)
args=parser.parse_args()
out=args.output.resolve()
out.mkdir(parents=True,mode=0o700,exist_ok=False)
token,api=secrets.token_urlsafe(32),secrets.token_urlsafe(32)
properties='\n'.join(['mode=development','http.host=127.0.0.1','http.port='+str(args.http_port),
                      'lease.millis=6000','worker.credential='+token,'api.credential='+api,
                      'worker.model-1.role=model','worker.runner-1.role=runner'])+'\n'
(out/'harness.local.properties').write_text(properties,encoding='utf-8')
for role in ['model','runner']:
    worker=dict(host='127.0.0.1',port=args.cell_port,workerId=role+'-1',role=role,workerCredential=token,
                runtimeDir=str(out/('runtime-'+role)),requestTimeout=3,startupTimeout=15)
    (out/(role+'.local.json')).write_text(json.dumps(worker,indent=2)+'\n',encoding='utf-8')
if os.name!='nt':
    for file in out.iterdir():file.chmod(0o600)
print('Created private development configurations in '+str(out))
