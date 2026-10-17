"""Prepare common's existing compile dependencies outside the checkout."""
import hashlib
from pathlib import Path
import shutil
import sys
import urllib.request

root = Path(__file__).resolve().parents[2]
out = Path(sys.argv[1]).resolve()
out.mkdir(parents=True, exist_ok=True)
for source in (root / "deploy/libs").glob("*.jar"):
    shutil.copy2(source, out/source.name)
shutil.copy2(root/"deploy/bin/cell.jar", out/"cell.jar")
url = "https://repo.maven.apache.org/maven2/com/google/zxing/javase/3.5.0/javase-3.5.0.jar"
data = urllib.request.urlopen(url, timeout=30).read()
expected = "34b0692194a9e4ad40bb8a29079974d18130300c42ef1730e73f0b03d5f3c5f6"
if hashlib.sha256(data).hexdigest() != expected:
    raise RuntimeError("ZXing dependency digest mismatch")
(out/"javase-3.5.0.jar").write_bytes(data)
print("Prepared isolated common compile dependencies")
