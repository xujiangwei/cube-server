"""Additive N10-N12 schemas. Frozen schemas remain byte-for-byte unchanged."""
import argparse
import copy
import hashlib
import json
from pathlib import Path

MODULE = Path(__file__).resolve().parents[1]


def raw(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode()


def obj(properties, required=None):
    return dict(type='object', properties=properties,
                required=list(properties) if required is None else required,
                additionalProperties=False)


def generate(python_root):
    source = MODULE / 'contracts'
    ident = dict(type='string', minLength=1, maxLength=128, pattern='^[A-Za-z0-9_.:-]+$')
    integer = dict(type='integer', minimum=0, maximum=9007199254740991)
    interaction = obj(dict(waitId=ident, kind=dict(type='string', enum=['input', 'approval']),
                           expiresAt=integer, argumentsHash=dict(type='string', pattern='^[a-f0-9]{64}$'),
                           details=dict(type='object')))
    read = json.loads((source / 'execution/schemas/run-read.json').read_text())
    read['properties']['interaction'] = interaction
    metadata = json.loads((source / 'schemas/artifact.json').read_text())
    metadata['properties'].update(createdAt=integer, state=dict(type='string', enum=[
        'uploading', 'ready', 'expired', 'delete_pending', 'deleted']), downloadable=dict(type='boolean'))
    metadata['required'] += ['createdAt', 'state', 'downloadable']
    input_meta = copy.deepcopy(metadata)
    input_meta['properties']['inputId'] = input_meta['properties'].pop('artifactId')
    input_meta['required'].remove('artifactId')
    input_meta['required'].remove('runId')
    input_meta['required'].append('inputId')
    schemas = {'run-read': read, 'interaction': interaction, 'artifact-metadata': metadata,
               'input-metadata': input_meta,
               'artifacts-list': obj(dict(artifacts=dict(type='array', items=metadata, maxItems=256))),
               'download-handle': obj(dict(url=dict(type='string', maxLength=4096), expiresAt=integer,
                                            requiresAuthentication=dict(type='boolean', enum=[True])))}
    for name in ('resume-run', 'cancel-run', 'approval', 'input-create', 'input-complete'):
        schemas[name] = json.loads((source / ('schemas/' + name + '.json')).read_text())
    files = {'schemas/' + k + '.json': raw(dict(v, **{'$schema': 'http://json-schema.org/draft-07/schema#'}))
             for k, v in schemas.items()}
    paths = {}
    operations = [('/runs/{runId}', 'get', None, 'run-read', 200),
                  ('/runs/{runId}/resume', 'post', 'resume-run', None, 202),
                  ('/runs/{runId}/cancel', 'post', 'cancel-run', None, 202),
                  ('/runs/{runId}/approvals/{waitId}/decision', 'post', 'approval', None, 202),
                  ('/inputs', 'post', 'input-create', 'input-metadata', 201),
                  ('/inputs/{inputId}/complete', 'post', 'input-complete', 'input-metadata', 200),
                  ('/inputs/{inputId}', 'get', None, 'input-metadata', 200),
                  ('/artifacts/{artifactId}', 'get', None, 'artifact-metadata', 200),
                  ('/runs/{runId}/artifacts', 'get', None, 'artifacts-list', 200),
                  ('/artifacts/{artifactId}/download-handle', 'get', None, 'download-handle', 200)]
    for path, verb, request, response, status in operations:
        op = dict(security=[{'ApproverBearer' if request == 'approval' else 'HarnessBearer': []}],
                  responses={str(status): dict(description='Durable response')}, parameters=[])
        import re
        for name in re.findall(r'\{(\w+)\}', path):
            op['parameters'].append(dict(name=name, **{'in': 'path'}, required=True, schema=ident))
        if verb == 'post':
            op['parameters'].append(dict(name='Idempotency-Key', **{'in': 'header'}, required=True, schema=ident))
        if request:
            op['requestBody'] = dict(required=True, content={'application/json': dict(schema={'$ref': 'schemas/' + request + '.json'})})
        if response:
            op['responses'][str(status)]['content'] = {'application/json': dict(schema={'$ref': 'schemas/' + response + '.json'})}
        for status in (400, 401, 403, 404, 409, 410, 429, 503):
            op['responses'][str(status)] = dict(description='Rejected without new execution')
        paths['/harness/v1' + path] = {verb: op}
    for path, verb in [('/inputs/{inputId}/content', 'put'), ('/artifacts/{artifactId}/content', 'get')]:
        operation = dict(security=[dict(HarnessBearer=[])], parameters=[dict(name='inputId' if verb == 'put' else 'artifactId', **{'in': 'path'}, required=True, schema=ident)],
                         responses={'200': dict(description='Bounded binary stream')})
        if verb == 'put':
            operation['parameters'].append(dict(name='Idempotency-Key', **{'in': 'header'}, required=True, schema=ident))
            operation['requestBody'] = dict(required=True, content={'application/octet-stream': dict(schema=dict(type='string', format='binary'))})
        else:
            operation['parameters'].append(dict(name='handle', **{'in': 'query'}, schema=dict(type='string')))
        paths['/harness/v1' + path] = {verb: operation}
    files['openapi.json'] = raw(dict(openapi='3.0.3', info=dict(title='Harness controls and files', version='N10-N12'), paths=paths,
        components=dict(securitySchemes={k: dict(type='http', scheme='bearer') for k in ('HarnessBearer', 'ApproverBearer')})))
    files['manifest.json'] = raw({k: hashlib.sha256(v).hexdigest() for k, v in files.items()})
    for root in (source / 'control-files', python_root / 'harness/contracts/control-files'):
        for name, value in files.items():
            target = root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(value)
    root = MODULE / 'resources/runtime/schemas'
    manifest = json.loads((root / 'manifest-v2.json').read_text())
    manifest['properties']['schemaVersion']['enum'] = [3]
    manifest['properties']['tools']['items']['properties']['brokerOperation']['enum'] = ['cube_console.query_status']
    (root / 'manifest-v3.json').write_bytes(raw(manifest))
    index = json.loads((root / 'manifest.json').read_text())
    index['manifest-v3.json'] = hashlib.sha256((root / 'manifest-v3.json').read_bytes()).hexdigest()
    (root / 'manifest.json').write_bytes(raw(index))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--python-root', type=Path, required=True)
    generate(parser.parse_args().python_root)
