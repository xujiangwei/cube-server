"""Python-owned microVM protocol. Frozen v4/v5 schemas are never regenerated."""
import argparse
import copy
import hashlib
import json
from pathlib import Path

MODULE = Path(__file__).resolve().parents[1]


def raw(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode()


def generate(python_root):
    # Refresh implementation attestations independently of the frozen wire contracts.
    base = ['runner_worker.py', 'runner_journal.py', 'runner_process.py',
            'runner_bootstrap.py', 'runner_sdk.py']
    for version, names in [(4, base), (5, base), (6, base + [
            'sandbox_worker.py', 'microvm/sandbox.py', 'microvm/firecracker.py'])]:
        family = 'runner' if version == 4 else 'runner-v' + str(version)
        sources = {name: hashlib.sha256((python_root/'harness'/name).read_bytes()).hexdigest()
                   for name in names}
        runtime = dict(runtime='trusted-python-v1', sources=sources,
                       digest=hashlib.sha256(json.dumps(sources, sort_keys=True,
                           separators=(',', ':')).encode()).hexdigest())
        for root in (MODULE/'contracts'/family, python_root/'harness/contracts'/family):
            root.mkdir(parents=True, exist_ok=True)
            (root/'runtime.json').write_bytes(raw(runtime))
    files = {}
    for path in (MODULE/'contracts/runner-v5/schemas').glob('*.json'):
        value = json.loads(path.read_bytes())

        def version(node):
            if isinstance(node, dict):
                if 'protocolVersion' in node.get('properties', {}):
                    node['properties']['protocolVersion']['enum'] = [6]
                for child in node.values():
                    version(child)
            elif isinstance(node, list):
                for child in node:
                    version(child)
        version(value)
        files[path.name] = value
    digest = dict(type='string', pattern='^[a-f0-9]{64}$', maxLength=64)
    profile = dict(type='string', enum=['linux-firecracker-python-v1'])

    def job(node):
        if isinstance(node, dict):
            if {'bundleJson', 'jobHash'} <= set(node.get('properties', {})):
                node['properties'].update(protocolVersion=dict(type='integer', enum=[6]),
                                          executionProfile=profile)
                node['required'] += ['protocolVersion', 'executionProfile']
            for child in node.values():
                job(child)
        elif isinstance(node, list):
            for child in node:
                job(child)
    for value in files.values():
        job(value)
    request = files['runner-request.json']
    descriptor = request['oneOf'][0]['properties']['descriptor']
    descriptor['properties'].update(executionProfile=profile,
        sandboxProfileDigest=digest, certificateDigest=digest)
    descriptor['required'] += ['executionProfile', 'sandboxProfileDigest', 'certificateDigest']
    descriptor['properties']['capacity']['maximum'] = 1
    prepare = copy.deepcopy(next(b for b in request['oneOf']
        if b['properties']['action']['enum'] == ['broker_invoke']))
    del prepare['properties']['argumentsJson']
    prepare['required'].remove('argumentsJson')
    prepare['properties']['action']['enum'] = ['sandbox_prepare']
    complete = copy.deepcopy(prepare)
    complete['properties']['action']['enum'] = ['sandbox_complete']
    # Completion is authorized by worker/runtime/job identity, even after grant expiry or revoke.
    del complete['properties']['grant']
    complete['required'].remove('grant')
    complete['properties']['receiptJson'] = dict(type='string', maxLength=65536)
    complete['required'].append('receiptJson')
    request['oneOf'] = [b for b in request['oneOf']
        if b['properties']['action']['enum'][0] not in ('broker_query', 'broker_invoke')]
    request['oneOf'] += [prepare, complete]
    output = dict(type='object', properties=dict(stdout=dict(type='string', maxLength=8192),
                  artifactText=dict(type='string', maxLength=8192)),
                  required=['stdout', 'artifactText'], additionalProperties=False)
    properties = dict(actualFinished=dict(type='boolean', enum=[True]),
                      proofHash=digest, output=output,
                      errorCode=dict(type='string', pattern='^[A-Z0-9_]{1,128}$', maxLength=128))
    files['sandbox-receipt.json'] = dict(oneOf=[
        dict(type='object', properties={k: properties[k] for k in keys}, required=list(keys),
             additionalProperties=False)
        for keys in (('actualFinished', 'proofHash', 'output'),
                     ('actualFinished', 'proofHash', 'errorCode'))])
    for root in (MODULE/'contracts/runner-v6', python_root/'harness/contracts/runner-v6'):
        for name, value in files.items():
            target = root/'schemas'/name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(raw(value))
    for family in ('runner', 'runner-v5', 'runner-v6'):
        root = MODULE/'contracts'/family
        manifest = {f.relative_to(root).as_posix(): hashlib.sha256(f.read_bytes()).hexdigest()
                    for f in sorted(root.rglob('*.json')) if f.name != 'manifest.json'}
        for target in (root, python_root/'harness/contracts'/family):
            (target/'manifest.json').write_bytes(raw(manifest))
    schema = MODULE/'resources/runtime/schemas'
    value = json.loads((schema/'manifest-v5.json').read_bytes())
    value['properties']['schemaVersion']['enum'] = [6]
    tool = value['properties']['tools']['items']
    tool['properties']['brokerProtocol']['enum'] = [6]
    tool['properties']['brokerOperation']['enum'] = ['extensions.code.python.v1']
    tool['properties']['permission']['enum'] = ['sandbox.execute']
    (schema/'manifest-v6.json').write_bytes(raw(value))
    manifest = json.loads((schema/'manifest.json').read_bytes())
    manifest['manifest-v6.json'] = hashlib.sha256((schema/'manifest-v6.json').read_bytes()).hexdigest()
    (schema/'manifest.json').write_bytes(raw(manifest))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--python-root', type=Path, required=True)
    generate(parser.parse_args().python_root)
