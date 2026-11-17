"""Check deployable experiment assets, not model task success."""
import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from prepare_adjustment_evaluation import freeze, prepare
from quality_evaluation import evaluate
from replay_trace import digest


class PreparationTest(unittest.TestCase):
    def test_pair_only_changes_version_and_switch_and_does_not_leak_labels_into_package(self):
        with tempfile.TemporaryDirectory() as temporary:
            destination = Path(temporary) / "experiment"
            profiles = Path(__file__).resolve().parents[2] / "config/harness-execution"
            prepare(profiles, destination)
            left = json.loads((destination / "baseline/harness-execution/execution.json").read_bytes())
            right = json.loads((destination / "candidate/harness-execution/execution.json").read_bytes())
            self.assertFalse(left["contextPolicy"].pop("evidenceAdjustment"))
            self.assertTrue(right["contextPolicy"].pop("evidenceAdjustment"))
            left.pop("version"); right.pop("version")
            self.assertEqual(left, right)
            with zipfile.ZipFile(destination / "evidence-adjustment-v1.zip") as package:
                self.assertLessEqual(len(package.namelist()), 65)
                manifest = json.loads(package.read("manifest.json"))
                self.assertEqual(manifest["tools"], [])
                self.assertFalse(any(name.startswith("references/") for name in package.namelist()))
                for file in manifest["files"]:
                    self.assertEqual(hashlib.sha256(package.read(file["path"])).hexdigest(), file["sha256"])
            suite = json.loads((destination / "suite.template.json").read_bytes())
            self.assertEqual(len(suite["scenarios"]), 24)
            self.assertEqual(len({digest(s["input"]) for s in suite["scenarios"]}), 24)
            self.assertEqual({s["split"] for s in suite["scenarios"]}, {"development"})
            frozen = destination / "baseline.fixture.json"
            freeze(destination / "suite.template.json", frozen, "actual-model", "paired-resources", "a" * 64)
            report = evaluate(json.loads(frozen.read_bytes()), [], diagnostic=True)
            self.assertEqual(report["result"], "NOT_CERTIFIED")
            self.assertEqual(report["deployments"][0]["coveredRuns"], 0)
            with self.assertRaises(FileExistsError):
                prepare(profiles, destination)
            with self.assertRaisesRegex(ValueError, "ACTUAL_RUN"):
                freeze(destination / "suite.template.json", destination / "bad.json", "m", "p", "placeholder")


if __name__ == "__main__":
    unittest.main()
