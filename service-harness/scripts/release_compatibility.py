"""Disposable real Ant publication/clean and archived-JAR schema rollback drills."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import zipfile

MODULE = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(__doc__)
    for name in ("java", "ant", "runtime", "evidence", "pre-trace-jar", "compatible-jar"):
        parser.add_argument("--"+name, type=Path, required=True)
    a = parser.parse_args()
    root, evidence = a.runtime.resolve(), a.evidence.resolve()
    root.mkdir(parents=True, exist_ok=False)
    evidence.mkdir(parents=True, exist_ok=False)
    env = dict(os.environ, JAVA_HOME=str(a.java.resolve().parents[1]))
    env["PATH"] = str(a.java.resolve().parent) + os.pathsep + env["PATH"]
    rows = []

    def save(name, value):
        (evidence/name).write_text(json.dumps(value, indent=2)+"\n", encoding="utf-8")

    def run(argv, name, cwd=root, fail=False):
        row = dict(argv=list(map(str, argv)), cwd=str(cwd), startedAt=datetime.now(timezone.utc).isoformat(), log=name+".log")
        with (evidence/row["log"]).open("wb") as out:
            child = subprocess.run(row["argv"], cwd=cwd, env=env, stdout=out, stderr=subprocess.STDOUT, timeout=90)
        row.update(exitCode=child.returncode, finishedAt=datetime.now(timezone.utc).isoformat())
        rows.append(row); save("commands.json", rows)
        assert (child.returncode != 0 if fail else child.returncode == 0), name

    def inventory(directory):
        return {p.relative_to(directory).as_posix(): dict(size=p.stat().st_size, sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in directory.rglob("*") if p.is_file()}

    # Actual original build.xml in a disposable mirror, fixture artifacts explicitly labeled.
    mirror = root/"publication"; mirror.mkdir()
    shutil.copy2(MODULE.parent/"build.xml", mirror/"build.xml")
    for name in ("build", "deploy/libs", "deploy/cellets", "deploy/bin", "service/config", "service/assets", "service-harness/src", "service-harness/resources", "service-harness/contracts", "service-harness/config", "service-harness/scripts"):
        (mirror/name).mkdir(parents=True)
    for name in ("cube-common-fixture.jar", "cube-dispatcher-fixture.jar", "cube-service-fixture.jar", "cube-service-harness-fixture.jar"):
        (mirror/"build"/name).write_bytes(("Harness publication fixture "+name).encode())
    (mirror/"deploy/libs/cube-service-harness-keep.jar").write_bytes(b"explicit independent Harness asset")
    (mirror/"deploy/bin/old-runtime.keep").write_bytes(b"unrelated old process sentinel")
    (mirror/"service/config/aigc-modules.properties").write_bytes((MODULE.parent/"service/config/aigc-modules.properties").read_bytes())
    (mirror/"service-harness/build.xml").write_bytes((MODULE/"build.xml").read_bytes())
    before = inventory(mirror/"deploy")
    # A new Harness CI failure must not become a dependency of old deploy.
    run([a.ant, "-f", "service-harness/build.xml", "-Dcommon.jar=missing.jar", "check"], "harness-ci-failure", mirror, fail=True)
    run([a.ant, "deploy"], "old-deploy-after-harness-failure", mirror)
    published = inventory(mirror/"deploy")
    assert "libs/cube-service-fixture.jar" in published
    assert "libs/cube-service-harness-fixture.jar" not in published
    assert published["libs/cube-service-harness-keep.jar"] == before["libs/cube-service-harness-keep.jar"]
    # Execute the unchanged module clean against its own isolated build output.
    (mirror/"service-harness/build").mkdir()
    (mirror/"service-harness/build/generated.tmp").write_text("owned new output")
    run([a.ant, "-f", "service-harness/build.xml", "clean"], "harness-clean", mirror)
    assert not (mirror/"service-harness/build").exists()
    assert inventory(mirror/"deploy") == published
    save("publication.json", dict(result="PASS_DISPOSABLE_SELECTION_AND_CLEAN", before=before, published=published,
        afterHarnessClean=inventory(mirror/"deploy"), productionBuild="NOT_RUN", oldLiveStream="NOT_RUN", artifactType="fixture bytes; actual original Ant targets"))
    # Use real archived binaries; never relabel the new binary as an old version.
    current = MODULE/"build/cube-service-harness-3.0.jar"
    dependencies = [MODULE.parent/"deploy/bin/cell.jar", MODULE.parent/"build/cube-common-3.0.jar", MODULE.parent/"deploy/libs/*"]

    def java(jar, cls, *args):
        cp = os.pathsep.join(map(str, [jar.resolve(), *dependencies, MODULE/"build/test-classes"]))
        return [a.java, "-cp", cp, cls, *args]

    dbroot = root/"upgrade"; dbroot.mkdir()
    config = dbroot/"store.local.properties"
    config.write_text(f"store.dialect=sqlite\nstore.database=harness_compatibility\nstore.identity=compatibility-upgrade-identity-123456\nstore.sqlite.root={dbroot.as_posix()}\nstore.sqlite.file={dbroot.as_posix()}/harness_compatibility.db\n", encoding="utf-8")
    migrate = "cube.service.harness.store.StoreMigrations"
    probe = "cube.service.harness.test.LedgerCompatibilityProbe"
    run(java(a.pre_trace_jar, migrate, "initialize-empty", config), "pre-trace-initialize")
    with sqlite3.connect(dbroot/"harness_compatibility.db") as c:
        assert c.execute("SELECT schema_version FROM harness_store_identity").fetchone() == (6,)
    receipt = dbroot/"receipt.json"
    run(java(a.pre_trace_jar, probe, "seed", config, receipt), "pre-trace-writer")
    shutil.copy2(dbroot/"harness_compatibility.db", evidence/"before-ledger-upgrade.db")
    run(java(current, migrate, "upgrade", config), "ledger-explicit-upgrade")
    with sqlite3.connect(dbroot/"harness_compatibility.db") as c:
        assert c.execute("SELECT schema_version FROM harness_store_identity").fetchone() == (7,)
    before_downgrade = inventory(dbroot)
    run(java(a.pre_trace_jar, migrate, "verify", config), "incompatible-downgrade-refused", fail=True)
    assert inventory(dbroot) == before_downgrade, "refused downgrade mutated ledger"
    # The current binary finishes the pending record; an archived compatible binary reads it.
    run(java(current, probe, "finish", config, receipt), "ledger-compatible-recovery")
    run(java(a.compatible_jar, probe, "read", config, receipt), "compatible-read-current-write")
    receipt2 = dbroot/"receipt2.json"
    run(java(a.compatible_jar, probe, "seed", config, receipt2), "compatible-new-write")
    run(java(a.compatible_jar, probe, "finish", config, receipt2), "compatible-finish")
    run(java(current, probe, "read", config, receipt2), "current-read-compatible-write")
    with zipfile.ZipFile(a.pre_trace_jar) as old, zipfile.ZipFile(current) as new:
        protected = [name for name in old.namelist() if name.startswith("store/migrations/") and name.endswith(".sql")]
        assert all(old.read(name) == new.read(name) for name in protected)
    shutil.copy2(dbroot/"harness_compatibility.db", evidence/"after-ledger-recovery.db")
    save("upgrade.json", dict(result="PASS", ledgerUpgrade="explicit additive upgrade", incompatibleRollback="refused without mutation",
        compatibleRecovery="current node finishes preserved run", compatibleRollback="archived compatible binary reads current result; archived writer new run and final read by current",
        schemaDowngrade=False, preTraceJarSha256=hashlib.sha256(a.pre_trace_jar.read_bytes()).hexdigest(),
        compatibleJarSha256=hashlib.sha256(a.compatible_jar.read_bytes()).hexdigest(), currentJarSha256=hashlib.sha256(current.read_bytes()).hexdigest(),
        unchangedMigrationFiles=protected, mysql="NOT_RUN", rollingMultiHost="NOT_RUN"))
    print("PASS: isolated original Ant targets, ledger upgrade/refusal/recovery and compatible binary rollback")


if __name__ == "__main__":
    main()
