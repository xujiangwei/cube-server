import argparse
from copy import deepcopy
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest

from catalog_source import build_catalog
from generate import check_coverage, digest, generated_catalog, public_descriptor, validate


class CatalogTest(unittest.TestCase):
    def test_stable_digests_and_public_projection(self):
        first = generated_catalog()
        self.assertEqual(first, generated_catalog())
        for row in first["operations"]:
            public = public_descriptor(row)
            self.assertEqual(row["descriptorDigest"], digest(public))
            self.assertNotIn("binding", public)
            self.assertNotIn("adapterId", public)

    def test_unicode_and_decimal_canonical_fixture(self):
        value = dict(text="</script>\u0085\u2028\U0001f600\n", ratio=1e-7)
        self.assertEqual("41a0b7e8ade1eb99d22b7ddc893876e1a0c02d667f0ce1f664dca33ef223896c", digest(value))

    def test_nested_deployment_parameter_rejected(self):
        data = build_catalog()
        data["operations"][0]["inputSchema"]["properties"]["target"] = dict(
            type="object", properties=dict(instanceRef=dict(type="string", maxLength=128)),
            required=[], additionalProperties=False)
        with self.assertRaisesRegex(ValueError, "deployment field"):
            validate(data)

    def test_duplicates_unknown_dependencies_and_cycles_rejected(self):
        for change in ("duplicate", "missing", "cycle"):
            data = build_catalog()
            if change == "duplicate":
                data["operations"].append(deepcopy(data["operations"][0]))
            elif change == "missing":
                data["operations"][0]["dependencies"] = ["missing.v1"]
            else:
                data["operations"][0]["dependencies"] = [data["operations"][0]["operationId"]]
            with self.assertRaises(ValueError):
                validate(data)

    def test_unsupported_schema_keyword_and_unbounded_array_rejected(self):
        for change in ("keyword", "array"):
            data = build_catalog()
            schema = data["operations"][0]["inputSchema"]
            if change == "keyword":
                schema["$ref"] = "other.json"
            else:
                schema["properties"]["items"] = dict(type="array", items=dict(type="boolean"))
            with self.assertRaises(ValueError):
                validate(data)


class SourceCoverageTest(unittest.TestCase):
    python_root = None

    def setUp(self):
        if self.python_root is None:
            self.skipTest("Run with --python-root to validate the companion source checkout")
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.catalog = generated_catalog()
        files = {unit["source"]["module"].replace(".", "/") + ".py" for unit in self.catalog["units"]}
        files.update(("cube/unit_registry.py", "cube/aigc_action.py", "cube/aigc_service.py", "cube/cv_action.py"))
        files.update(("cube/chat_completion.py", "cube/plugin/router.py"))
        for path in (self.python_root / "plugins").glob("*/plugin.json"):
            files.add(str(path.relative_to(self.python_root)))
            entry_module = json.loads(path.read_text(encoding="utf-8"))["entry"].split(":")[0]
            files.add(str((path.parent / (entry_module.replace(".", "/") + ".py")).relative_to(self.python_root)))
        for file in files:
            destination = self.root / file
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(self.python_root / file, destination)
        check_coverage(self.catalog, self.root)

    def append(self, file, source):
        with (self.root / file).open("a", encoding="utf-8") as stream:
            stream.write("\n" + source + "\n")

    def test_new_unit_requires_contract(self):
        self.append("cube/unit_registry.py", "UNIT_SPECS += (UnitSpec('unit.new', 'newUnit', 'cube.new', 'NewUnit', ()),)")
        with self.assertRaisesRegex(ValueError, "built-in Unit coverage mismatch"):
            check_coverage(self.catalog, self.root)

    def test_new_action_requires_contract(self):
        path = self.root / "cube/aigc_action.py"
        source = path.read_text(encoding="utf-8").replace("class AIGCAction(object):", "class AIGCAction(object):\n    New = 'freshAction'")
        path.write_text(source, encoding="utf-8")
        self.append("cube/aigc_service.py", "actions.register(AIGCAction.New, self.freshAction)")
        with self.assertRaisesRegex(ValueError, "unmapped AIGC actions"):
            check_coverage(self.catalog, self.root)

    def test_new_plugin_unit_and_action_require_contracts(self):
        manifest = self.root / "plugins/omniforest/plugin.json"
        original = json.loads(manifest.read_text(encoding="utf-8"))
        changed = deepcopy(original)
        changed["provides"]["units"].append("NewForest")
        manifest.write_text(json.dumps(changed), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "plugin (Unit coverage|declaration) mismatch"):
            check_coverage(self.catalog, self.root)
        manifest.write_text(json.dumps(original), encoding="utf-8")
        source = self.root / "plugins/omniforest/unit.py"
        source.write_text(source.read_text(encoding="utf-8").replace('SUPPORTED_ACTIONS = ("detection",)',
                          'SUPPORTED_ACTIONS = ("detection", "freshAction")'), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "plugin Action coverage mismatch"):
            check_coverage(self.catalog, self.root)

    def test_new_cv_action_requires_contract(self):
        path = self.root / "cube/cv_action.py"
        path.write_text(path.read_text(encoding="utf-8").replace("class CVAction(object):",
                        "class CVAction(object):\n    New = 'freshAction'"), encoding="utf-8")
        self.append("cube/cv_service.py", "action = CVAction.New")
        with self.assertRaisesRegex(ValueError, "unmapped CV actions"):
            check_coverage(self.catalog, self.root)

    def test_cv_and_plugin_implementation_bindings_are_checked(self):
        source = self.root / "cube/cv_service.py"
        source.write_text(source.read_text(encoding="utf-8").replace("self.executor.submit(self.detectBarCode, dialect)",
                          "self.executor.submit(self.clipPaper, dialect)"), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "CV action handler mismatch"):
            check_coverage(self.catalog, self.root)
        shutil.copyfile(self.python_root / "cube/cv_service.py", source)
        declaration = self.root / "plugins/omniforest/__init__.py"
        declaration.write_text(declaration.read_text(encoding="utf-8").replace('class_name="OmniForestUnit"',
                               'class_name="OtherUnit"'), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "plugin source declaration mismatch"):
            check_coverage(self.catalog, self.root)

    def test_literal_action_and_changed_handler_are_checked(self):
        self.append("cube/aigc_service.py", "actions.register('freshAction', self.freshAction)")
        with self.assertRaisesRegex(ValueError, "unmapped AIGC actions"):
            check_coverage(self.catalog, self.root)
        source = self.root / "cube/aigc_service.py"
        shutil.copyfile(self.python_root / "cube/aigc_service.py", source)
        source.write_text(source.read_text(encoding="utf-8").replace("AIGCAction.SemanticSearch, self.executeSemanticSearch",
                          "AIGCAction.SemanticSearch, self.executeRetrieveReRank"), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "AIGC action handler mismatch"):
            check_coverage(self.catalog, self.root)

    def test_new_public_method_requires_classification(self):
        path = self.root / "cube/unit_embedding.py"
        path.write_text(path.read_text(encoding="utf-8").replace("class EmbeddingUnit(CubeUnit):",
                        "class EmbeddingUnit(CubeUnit):\n    def freshOperation(self):\n        pass\n"), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "unclassified public Unit methods"):
            check_coverage(self.catalog, self.root)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--python-root", type=Path)
    args, remaining = parser.parse_known_args()
    SourceCoverageTest.python_root = args.python_root
    unittest.main(argv=[sys.argv[0], *remaining])
