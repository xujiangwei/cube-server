"""Add generic manifest-v4 without changing frozen v1/v2/v3 schemas."""
import hashlib
import json
from pathlib import Path


def generate():
    root = Path(__file__).resolve().parents[1] / 'resources/runtime/schemas'
    schema = json.loads((root / 'manifest-v2.json').read_text(encoding='utf-8'))
    schema['properties']['schemaVersion']['enum'] = [4]
    schema['properties']['tools']['items']['properties']['brokerOperation'] = dict(
        type='string', minLength=1, maxLength=128, pattern='^[A-Za-z0-9_.:-]+$')
    raw = (json.dumps(schema, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    (root / 'manifest-v4.json').write_bytes(raw)
    index = json.loads((root / 'manifest.json').read_text(encoding='utf-8'))
    index['manifest-v4.json'] = hashlib.sha256(raw).hexdigest()
    (root / 'manifest.json').write_text(json.dumps(index, indent=2) + '\n', encoding='utf-8', newline='\n')


if __name__ == '__main__':
    generate()
