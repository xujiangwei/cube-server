"""Disposable local MySQL fixture. Never uses an installed service or existing database.

Private passwords and runtime properties stay under --runtime. Only the process
started with the exact fixture defaults file can be stopped by this helper.
"""
import argparse
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("action", choices=["start", "stop"])
    parser.add_argument("--mysql-home", required=True, type=Path)
    parser.add_argument("--runtime", required=True, type=Path)
    args = parser.parse_args()
    runtime = args.runtime.resolve()
    runtime.mkdir(parents=True, exist_ok=True)
    if os.name != "nt":
        runtime.chmod(0o700)
    suffix = ".exe" if os.name == "nt" else ""
    mysql = str(args.mysql_home.resolve()/"bin"/("mysql"+suffix))
    mysqld = str(args.mysql_home.resolve()/"bin"/("mysqld"+suffix))
    marker = runtime/"fixture.json"
    state = json.loads(marker.read_text()) if marker.exists() else None

    def query(sql, user="root", password="", port=None):
        env = os.environ.copy()
        env["MYSQL_PWD"] = password
        return subprocess.run([mysql, "--no-defaults", "--protocol=TCP", "-h127.0.0.1",
                               "-P"+str(port or state["port"]), "-u"+user, "--batch", "--skip-column-names"],
                              input=sql, text=True, capture_output=True, env=env, timeout=15)

    if args.action == "stop":
        if not state:
            raise SystemExit("Fixture marker missing; refused")
        # A SQL SHUTDOWN on our private loopback port with our random root secret
        # cannot stop another system service even if the PID has been reused.
        result = query("SHUTDOWN;", password=state["rootPassword"])
        if result.returncode:
            raise SystemExit("Private fixture shutdown failed")
        print("Stopped private MySQL fixture")
        return
    if state:
        raise SystemExit("Fixture already initialized; choose a fresh runtime directory")
    data = runtime/"data"
    if data.exists():
        raise SystemExit("Preexisting data directory refused")
    data.mkdir()
    with (runtime/"initialize.log").open("w") as log:
        init = subprocess.run([mysqld, "--no-defaults", "--basedir="+str(args.mysql_home.resolve()),
                               "--datadir="+str(data), "--initialize-insecure", "--console"],
                              stdout=log, stderr=subprocess.STDOUT, timeout=60)
    if init.returncode:
        raise SystemExit("Initialization failed; see private initialize.log")
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    defaults = runtime/"fixture.cnf"
    defaults.write_text("[mysqld]\n" + "\n".join([
        "basedir="+args.mysql_home.resolve().as_posix(), "datadir="+data.as_posix(),
        "bind-address=127.0.0.1", "port="+str(port), "mysqlx=0", "skip-log-bin",
        "innodb-flush-log-at-trx-commit=1", "log-error="+(runtime/"server.log").as_posix(),
        "pid-file="+(runtime/"server.pid").as_posix()])+"\n", encoding="utf-8")
    with (runtime/"console.log").open("w") as log:
        proc = subprocess.Popen([mysqld, "--defaults-file="+str(defaults)], stdout=log, stderr=subprocess.STDOUT,
                                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    for _ in range(50):
        if proc.poll() is not None:
            raise SystemExit("Private mysqld exited; see server.log")
        try:
            if query("SELECT 1;", port=port).returncode == 0:
                break
        except subprocess.TimeoutExpired:
            pass
        time.sleep(0.2)
    else:
        proc.terminate()
        raise SystemExit("Private mysqld startup timeout")
    password, root_password, identity = secrets.token_hex(24), secrets.token_hex(24), secrets.token_hex(24)
    state = dict(port=port, pid=proc.pid, rootPassword=root_password, runtime=str(runtime), database="harness_n03_fixture")
    # This file must survive errors so the helper can always stop its own server.
    marker.write_text(json.dumps(state, indent=2), encoding="utf-8")
    if os.name != "nt":
        marker.chmod(0o600)
    sql = f"""
ALTER USER 'root'@'localhost' IDENTIFIED BY '{root_password}';
CREATE DATABASE harness_n03_fixture CHARACTER SET utf8mb4 COLLATE utf8mb4_bin;
CREATE DATABASE n03_legacy_probe;
CREATE TABLE n03_legacy_probe.protected_table (id INT);
CREATE USER 'harness_migrator'@'127.0.0.1' IDENTIFIED BY '{password}';
CREATE USER 'harness_runtime'@'127.0.0.1' IDENTIFIED BY '{password}';
CREATE USER 'harness_overprivileged'@'127.0.0.1' IDENTIFIED BY '{password}';
GRANT SELECT,INSERT,UPDATE,DELETE,CREATE,INDEX,REFERENCES ON `harness_n03_fixture`.* TO 'harness_migrator'@'127.0.0.1';
GRANT SELECT,INSERT,UPDATE,DELETE ON `harness_n03_fixture`.* TO 'harness_runtime'@'127.0.0.1';
GRANT ALL PRIVILEGES ON *.* TO 'harness_overprivileged'@'127.0.0.1';
"""
    provision = query(sql)
    if provision.returncode:
        raise SystemExit("Provision failed; private fixture marker retained for stop")
    for account, name in [("harness_migrator", "migrator"), ("harness_runtime", "runtime"), ("harness_overprivileged", "overprivileged")]:
        properties = dict({"store.dialect": "mysql", "store.database": state["database"], "store.identity": identity,
                           "store.test.disposable": "true",
                           "store.mysql.host": "127.0.0.1", "store.mysql.port": str(port), "store.mysql.user": account,
                           "store.mysql.password": password, "store.mysql.loopbackDevelopment": "true"})
        path = runtime/(name+".local.properties")
        path.write_text("\n".join(k+"="+v for k,v in properties.items())+"\n", encoding="ascii")
        if os.name != "nt":
            path.chmod(0o600)
    denied = query("INSERT INTO n03_legacy_probe.protected_table VALUES (1);", "harness_runtime", password)
    if denied.returncode == 0:
        raise SystemExit("Isolation check failed: fixture runtime can write another database")
    print(json.dumps(dict(result="PASS", database=state["database"], port=port,
                          serverVersion=query("SELECT VERSION();", password=root_password).stdout.strip(),
                          legacyWriteDenied=True, properties=str(runtime/"migrator.local.properties"))))


if __name__ == "__main__":
    main()
