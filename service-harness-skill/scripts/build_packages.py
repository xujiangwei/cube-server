"""Build six immutable author ZIPs from this module's harness-tools (Python 3 stdlib)."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile
import zipfile

NAMES = ("search", "web", "knowledge", "attachment", "time", "calculator")
ROOT = Path(__file__).resolve().parents[1]


def package(source, destination):
    source = source.resolve(strict=True)
    manifest = json.loads((source / "manifest.template.json").read_text(encoding="utf-8"))
    entries, files = {}, []
    for spec in manifest.pop("resources"):
        relative = spec["path"]
        if "\\" in relative or ":" in relative or any(p in ("", ".", "..") for p in relative.split("/")):
            raise ValueError("Unsafe package path")
        path = source / relative
        if any(p.is_symlink() for p in (path, *path.parents)) or not path.resolve(strict=True).is_relative_to(source):
            raise ValueError("Package resource escapes source")
        raw = path.read_bytes()
        entries[relative] = raw
        files.append(dict(spec, size=len(raw), sha256=hashlib.sha256(raw).hexdigest()))
    manifest["files"] = sorted(files, key=lambda f: f["path"])
    entries["manifest.json"] = json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    destination.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=".package-", dir=destination.parent)
    try:
        with os.fdopen(fd, "wb") as stream, zipfile.ZipFile(stream, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for name, raw in sorted(entries.items()):
                item = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
                item.compress_type = zipfile.ZIP_DEFLATED
                item.external_attr = 0o100644 << 16
                archive.writestr(item, raw)
        os.replace(temporary, destination)
    finally:
        Path(temporary).unlink(missing_ok=True)
    return hashlib.sha256(entries["manifest.json"]).hexdigest()


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "build/packages")
    args = parser.parse_args()
    for name in NAMES:
        print(name, package(ROOT / "harness-tools" / name, args.output / (name + "-v1.zip")))


if __name__ == "__main__":
    main()
