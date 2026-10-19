import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "init_dev.py"


class InitDevTest(unittest.TestCase):
    def generate(self, root, *args):
        return subprocess.run([sys.executable, "-B", str(SCRIPT), "--output", str(root), *args],
                              capture_output=True, text=True, timeout=10)

    def test_remote_cell_address_does_not_change_health_or_credentials(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "private"
            result = self.generate(root, "--cell-host", "192.168.1.35", "--cell-port", "6000")
            self.assertEqual(result.returncode, 0, result.stderr)
            properties = dict(line.split("=", 1) for line in
                              (root / "harness.local.properties").read_text().splitlines())
            self.assertNotIn("mode", properties)
            self.assertEqual(properties["http.host"], "127.0.0.1")
            self.assertEqual(properties["http.port"], "7088")
            self.assertNotEqual(properties["worker.credential"], properties["api.credential"])
            runtimes = set()
            for role in ("model", "runner"):
                worker = json.loads((root / (role + ".local.json")).read_text())
                self.assertEqual((worker["host"], worker["port"]), ("192.168.1.35", 6000))
                self.assertEqual(worker["workerCredential"], properties["worker.credential"])
                self.assertEqual(properties["worker." + worker["workerId"] + ".role"], role)
                self.assertNotIn(properties["api.credential"], json.dumps(worker))
                runtimes.add(worker["runtimeDir"])
            self.assertEqual(len(runtimes), 2)
            for key in ("worker.credential", "api.credential"):
                self.assertNotIn(properties[key], result.stdout + result.stderr)
            self.assertNotEqual(self.generate(root).returncode, 0)

    def test_default_loopback_and_hostname(self):
        with tempfile.TemporaryDirectory() as directory:
            for name, args, host in (("default", (), "127.0.0.1"),
                                     ("dns", ("--cell-host", "harness.example.internal"), "harness.example.internal")):
                root = Path(directory) / name
                self.assertEqual(self.generate(root, *args).returncode, 0)
                worker = json.loads((root / "model.local.json").read_text())
                self.assertEqual((worker["host"], worker["port"]), (host, 7600))

    def test_invalid_endpoint_does_not_create_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "invalid"
            for option, value in (("--cell-host", ""), ("--cell-host", " host"),
                                  ("--cell-host", "http://host"), ("--cell-host", "host:6000"),
                                  ("--cell-port", "0"), ("--cell-port", "65536"),
                                  ("--http-port", "-1")):
                with self.subTest(option=option, value=value):
                    self.assertNotEqual(self.generate(root, option, value).returncode, 0)
                    self.assertFalse(root.exists())


if __name__ == "__main__":
    unittest.main()
