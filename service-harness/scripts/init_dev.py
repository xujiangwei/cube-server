"""Create a new private Harness configuration directory. Never prints credentials."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets


def cell_host(value):
    if value != '::1' and not re.fullmatch(r'[A-Za-z0-9_.-]+', value):
        raise argparse.ArgumentTypeError('Use an IP address or hostname without a URL or port')
    return value


def port(value):
    number = int(value)
    if not 0 < number < 65536:
        raise argparse.ArgumentTypeError('Port must be between 1 and 65535')
    return number


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--cell-host', type=cell_host, default='127.0.0.1',
                        help='Java Cell address written to Python worker configs; does not change health/admin HTTP')
    parser.add_argument('--cell-port', type=port, default=7600)
    parser.add_argument('--http-port', type=port, default=7088)
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, mode=0o700, exist_ok=False)
    token = secrets.token_urlsafe(32)
    properties = '\n'.join(['http.host=127.0.0.1', 'http.port=' + str(args.http_port),
                            'lease.millis=6000', 'worker.credential=' + token,
                            'worker.model-1.role=model', 'worker.runner-1.role=runner']) + '\n'
    (out / 'harness.local.properties').write_text(properties, encoding='utf-8')
    for role in ['model', 'runner']:
        worker = dict(host=args.cell_host, port=args.cell_port, workerId=role + '-1', role=role,
                      workerCredential=token, runtimeDir=str(out / ('runtime-' + role)),
                      requestTimeout=3, startupTimeout=15)
        (out / (role + '.local.json')).write_text(json.dumps(worker, indent=2) + '\n', encoding='utf-8')
    if os.name != 'nt':
        for file in out.iterdir():
            file.chmod(0o600)
    print('Created private Harness configurations in ' + str(out))


if __name__ == '__main__':
    main()
