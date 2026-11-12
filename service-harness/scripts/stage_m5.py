"""Build a standalone M5 admin toolkit; verify reused M4 artifacts, never deploy."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    module = Path(__file__).resolve().parents[1]
    workspace = module.parents[1]
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("--output", type=Path, default=module / "build/stage-m5")
    args = parser.parse_args()
    output = args.output.resolve()
    archive = output.with_name(output.name + ".zip")
    if output.exists() or archive.exists():
        parser.error("Use a new output directory and ZIP path; existing delivery is preserved")

    baseline = json.loads((workspace / "forest/build/m4-releases.json").read_text(encoding="utf-8-sig"))
    m4 = workspace / "cube-server/service-harness-skill/build/stage-m4"
    for name, expected in baseline["jarSha256"].items():
        if sha(m4 / name) != expected:
            raise ValueError("M4 JAR checksum mismatch: " + name)
    packages = {}
    for name, expected in baseline["businessManifestDigests"].items():
        filename = workspace / "forest/build" / name
        with zipfile.ZipFile(filename) as source:
            manifest = json.loads(source.read("manifest.json"))
        canonical = json.dumps(manifest, sort_keys=True, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        if hashlib.sha256(canonical).hexdigest() != expected:
            raise ValueError("M4 business manifest mismatch: " + name)
        packages[name] = {"manifestDigest": expected, "zipSha256": sha(filename)}

    sources = {
        "README.md": module / "scripts/release-tools-readme.md",
        "examples/release_ops.py": workspace / "tutorials/examples/release_ops.py",
        "examples/agent_client.py": workspace / "tutorials/examples/agent_client.py",
        "examples/config/promotion-source.example.json": workspace / "tutorials/examples/config/promotion-source.example.json",
        "examples/config/promotion-target.example.json": workspace / "tutorials/examples/config/promotion-target.example.json",
    }
    # Resolve and read every input before creating a new delivery directory.
    files = {name: source.read_bytes() for name, source in sources.items()}
    receipt = {"stage": "M5", "kind": "standalone-admin-toolkit", "containsRuntimeJars": False,
        "reusedBaselineStage": "M4", "jarSha256": baseline["jarSha256"],
        "toolManifestDigests": baseline["toolManifestDigests"], "reusedBusinessPackages": packages,
        "files": {name: {"size": len(raw), "sha256": hashlib.sha256(raw).hexdigest()} for name, raw in files.items()},
        "stagingCheck": "Verified actual M4 JAR and business manifest digests; no rebuild or remote calls",
        "validationRecord": "service-harness/RELEASE-MANAGEMENT.md describes regression checks; staging itself does not run tests",
        "published": False, "productionDeployment": False}
    files["M5-RELEASES.json"] = (json.dumps(receipt, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    output.mkdir(parents=True)
    for name, raw in files.items():
        destination = output / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(raw)
    with zipfile.ZipFile(archive, "x", zipfile.ZIP_DEFLATED) as target:
        for name, raw in sorted(files.items()):
            entry = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            target.writestr(entry, raw)
    print(json.dumps({"directory": str(output), "zip": str(archive), "zipSha256": sha(archive)}, indent=2))


if __name__ == "__main__":
    main()
