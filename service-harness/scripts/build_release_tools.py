"""Build a standalone release administration toolkit without runtime or local build dependencies."""
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
    parser.add_argument("--output", type=Path, default=module / "build/release-tools")
    args = parser.parse_args()
    output = args.output.resolve()
    archive = output.with_name(output.name + ".zip")
    if output.exists() or archive.exists():
        parser.error("Use a new output directory and ZIP path; existing delivery is preserved")

    sources = {
        "README.md": module / "scripts/release-tools-readme.md",
        "examples/release_ops.py": workspace / "tutorials/examples/release_ops.py",
        "examples/agent_client.py": workspace / "tutorials/examples/agent_client.py",
        "examples/config/promotion-source.example.json": workspace / "tutorials/examples/config/promotion-source.example.json",
        "examples/config/promotion-target.example.json": workspace / "tutorials/examples/config/promotion-target.example.json",
    }
    # Resolve and read every input before creating a new delivery directory.
    files = {name: source.read_bytes() for name, source in sources.items()}
    receipt = {"schemaVersion": 1, "kind": "standalone-admin-toolkit", "containsRuntimeJars": False,
        "files": {name: {"size": len(raw), "sha256": hashlib.sha256(raw).hexdigest()} for name, raw in files.items()},
        "published": False, "productionDeployment": False}
    files["manifest.json"] = (json.dumps(receipt, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
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
