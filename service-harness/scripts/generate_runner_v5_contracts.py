"""Add generic invocation alongside the frozen v4 targets/fields protocol."""
import argparse, copy, hashlib, json
from pathlib import Path

MODULE = Path(__file__).resolve().parents[1]
def raw(v): return (json.dumps(v,ensure_ascii=False,indent=2)+'\n').encode()
def generate(python_root):
    files = {}
    for path in (MODULE/'contracts/runner').rglob('*.json'):
        if path.name in ('manifest.json','runtime.json'): continue
        v = json.loads(path.read_text())
        def version(o):
            if isinstance(o,dict):
                if 'protocolVersion' in o.get('properties',{}): o['properties']['protocolVersion']['enum']=[5]
                for x in o.values(): version(x)
            elif isinstance(o,list):
                for x in o:version(x)
        version(v)
        if path.stem=='runner-request':
            b=copy.deepcopy(next(b for b in v['oneOf'] if b['properties']['action']['enum']==['broker_query']))
            b['properties']['action']['enum']=['broker_invoke']
            for k in ('targets','fields'):del b['properties'][k];b['required'].remove(k)
            b['properties']['argumentsJson']=dict(type='string',maxLength=16384)
            b['required'].append('argumentsJson');v['oneOf'].append(b)
        files[path.relative_to(MODULE/'contracts/runner').as_posix()]=raw(v)
    files['runtime.json']=(MODULE/'contracts/runner/runtime.json').read_bytes()
    files['manifest.json']=raw({k:hashlib.sha256(v).hexdigest() for k,v in files.items()})
    for root in (MODULE/'contracts/runner-v5',python_root/'harness/contracts/runner-v5'):
        for name,v in files.items():
            target=root/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(v)
    schema=MODULE/'resources/runtime/schemas'
    v=json.loads((schema/'manifest-v4.json').read_text());v['properties']['schemaVersion']['enum']=[5]
    tool=v['properties']['tools']['items'];tool['properties']['brokerProtocol']=dict(type='integer',enum=[5]);tool['required'].append('brokerProtocol')
    (schema/'manifest-v5.json').write_bytes(raw(v))
    p=schema/'manifest.json';m=json.loads(p.read_text());m['manifest-v5.json']=hashlib.sha256((schema/'manifest-v5.json').read_bytes()).hexdigest();p.write_bytes(raw(m))
if __name__=='__main__':
    p=argparse.ArgumentParser(__doc__);p.add_argument('--python-root',type=Path,required=True);generate(p.parse_args().python_root)
