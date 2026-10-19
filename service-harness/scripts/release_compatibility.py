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
    for name in ("java", "ant", "runtime", "evidence", "old-v6-jar", "prior-v7-jar"):
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
        (mirror/"build"/name).write_bytes(("N16 publication fixture "+name).encode())
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
    config.write_text(f"store.dialect=sqlite\nstore.database=harness_n16\nstore.identity=n16-upgrade-identity-123456\nstore.sqlite.root={dbroot.as_posix()}\nstore.sqlite.file={dbroot.as_posix()}/harness_n16.db\n", encoding="utf-8")
    migrate = "cube.service.harness.store.StoreMigrations"
    probe = "cube.service.harness.test.LedgerCompatibilityProbe"
    run(java(a.old_v6_jar, migrate, "initialize-empty", config), "v6-initialize")
    with sqlite3.connect(dbroot/"harness_n16.db") as c:
        assert c.execute("SELECT schema_version FROM harness_store_identity").fetchone() == (6,)
    receipt = dbroot/"receipt.json"
    run(java(a.old_v6_jar, probe, "seed", config, receipt), "v6-writer")
    shutil.copy2(dbroot/"harness_n16.db", evidence/"before-upgrade-schema6.db")
    run(java(current, migrate, "upgrade", config), "v7-explicit-upgrade")
    with sqlite3.connect(dbroot/"harness_n16.db") as c:
        assert c.execute("SELECT schema_version FROM harness_store_identity").fetchone() == (7,)
    before_downgrade = inventory(dbroot)
    run(java(a.old_v6_jar, migrate, "verify", config), "v6-downgrade-refused", fail=True)
    assert inventory(dbroot) == before_downgrade, "refused downgrade mutated ledger"
    # Compatible recovery version finishes the pending record. Prior schema7 binary reads it.
    run(java(current, probe, "finish", config, receipt), "v7-compatible-recovery")
    run(java(a.prior_v7_jar, probe, "read", config, receipt), "prior-v7-read-current-write")
    receipt2 = dbroot/"receipt2.json"
    run(java(a.prior_v7_jar, probe, "seed", config, receipt2), "prior-v7-new-write")
    run(java(a.prior_v7_jar, probe, "finish", config, receipt2), "prior-v7-finish")
    run(java(current, probe, "read", config, receipt2), "current-read-prior-v7-write")
    with zipfile.ZipFile(a.old_v6_jar) as old, zipfile.ZipFile(current) as new:
        protected = [name for name in old.namelist() if name.startswith("store/migrations/") and name.endswith(".sql")]
        assert all(old.read(name) == new.read(name) for name in protected)
    shutil.copy2(dbroot/"harness_n16.db", evidence/"after-recovery-schema7.db")
    save("upgrade.json", dict(result="PASS", schema6To7="explicit additive upgrade", schema6Rollback="refused without mutation",
        compatibleRecovery="current schema7 node finishes preserved run", sameSchemaRollback="prior v7 reads current result; prior writer new run and final read by current",
        schemaDowngrade=False, oldV6JarSha256=hashlib.sha256(a.old_v6_jar.read_bytes()).hexdigest(),
        priorV7JarSha256=hashlib.sha256(a.prior_v7_jar.read_bytes()).hexdigest(), currentJarSha256=hashlib.sha256(current.read_bytes()).hexdigest(),
        unchangedMigrationFiles=protected, mysql="NOT_RUN", rollingMultiHost="NOT_RUN"))
    print("PASS: isolated original Ant targets, real v6/v7 upgrade/refusal/recovery and compatible binary rollback")


if __name__ == "__main__":
    main()
