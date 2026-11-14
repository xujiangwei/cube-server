"""Generate/check the installed Unit catalog without importing a model stack."""
import argparse
import ast
import hashlib
import json
import os
import re
import tempfile
from copy import deepcopy
from decimal import Decimal
from pathlib import Path

from catalog_source import ACTION_DISPOSITIONS, METHOD_DISPOSITIONS, build_catalog

ROOT = Path(__file__).resolve().parents[2]
JAVA_RESOURCE = ROOT / "common/resources/cube/aigc/unit/unit-operations.json"
PUBLIC_FIELDS = (
    "operationId", "version", "unitName", "skill", "toolName", "brokerOperation", "permission",
    "description", "executionMode", "effect", "approval", "inputSchema", "outputSchema",
    "inputMediaTypes", "outputMediaTypes", "dependencies", "usageKind", "limits",
)
ID = re.compile(r"[A-Za-z0-9_.:-]{1,128}\Z")
FORBIDDEN = frozenset(("local", "remote", "transport", "gatewayId", "instanceRef", "instanceId",
                       "workerId", "jvm", "kernel", "cellAddress", "hostPath", "adapterId"))
SCHEMA_KEYS = frozenset(("$schema", "description", "oneOf", "type", "properties", "required",
    "additionalProperties", "enum", "minLength", "maxLength", "pattern", "minimum", "maximum",
    "items", "maxItems", "minItems"))


def canonical(value):
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, dict):
        return "{" + ",".join(canonical(key) + ":" + canonical(value[key]) for key in sorted(value)) + "}"
    if isinstance(value, list):
        return "[" + ",".join(canonical(item) for item in value) + "]"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        decimal = Decimal(str(value))
        if not decimal.is_finite():
            raise ValueError("nonfinite catalog number")
        if decimal == 0:
            return "0"
        plain = format(decimal, "f")
        return plain.rstrip("0").rstrip(".") if "." in plain else plain
    raise ValueError("unsupported catalog value")


def digest(value):
    return hashlib.sha256(canonical(value).encode("utf-8")).hexdigest()


def public_descriptor(operation):
    return {key: deepcopy(operation[key]) for key in PUBLIC_FIELDS}


def validate_schema(schema):
    if not isinstance(schema, dict) or schema.keys() - SCHEMA_KEYS:
        raise ValueError("unsupported schema keywords")
    if "oneOf" in schema:
        for child in schema["oneOf"]:
            validate_schema(child)
        return
    kind = schema.get("type")
    if kind == "object":
        properties = schema.get("properties", {})
        if schema.get("additionalProperties") is not False:
            raise ValueError("object schema must be closed")
        if set(schema.get("required", ())) - properties.keys():
            raise ValueError("required property missing")
        if FORBIDDEN.intersection(properties):
            raise ValueError("deployment field in public schema")
        for child in properties.values():
            validate_schema(child)
    elif kind == "array":
        if not 0 < schema.get("maxItems", 0) <= 1024:
            raise ValueError("array must be bounded")
        validate_schema(schema["items"])
    elif kind == "string":
        if not 0 < schema.get("maxLength", 0) <= 32768:
            raise ValueError("string must be bounded")
    elif kind not in ("boolean", "integer", "number"):
        raise ValueError("unsupported schema type")


def validate(catalog):
    unit_keys = [unit["key"] for unit in catalog["units"]]
    if len(set(unit_keys)) != len(unit_keys):
        raise ValueError("duplicate unit")
    for key in ("operationId", "toolName", "brokerOperation", "permission"):
        names = [row[key] for row in catalog["operations"]]
        if len(set(names)) != len(names) or any(not ID.fullmatch(name) for name in names):
            raise ValueError("invalid or duplicate " + key)
    operations = {row["operationId"]: row for row in catalog["operations"]}
    for row in operations.values():
        if row["unitKey"] not in unit_keys:
            raise ValueError("unknown operation unit")
        if row["effect"] not in ("read-only", "write", "session-control"):
            raise ValueError("unknown effect")
        if row["executionMode"] not in ("sync", "job", "session"):
            raise ValueError("unknown execution mode")
        if row["approval"] not in ("policy", "required"):
            raise ValueError("unknown approval policy")
        if not row["toolName"].startswith(row["skill"].replace("/", ".") + "."):
            raise ValueError("tool outside skill namespace")
        validate_schema(row["inputSchema"])
        validate_schema(row["outputSchema"])
        if set(row["dependencies"]) - operations.keys():
            raise ValueError("missing operation dependency")
    visiting, visited = set(), set()

    def visit(name):
        if name in visiting:
            raise ValueError("cyclic operation dependency")
        if name in visited:
            return
        visiting.add(name)
        for dependency in operations[name]["dependencies"]:
            visit(dependency)
        visiting.remove(name)
        visited.add(name)

    for name in operations:
        visit(name)
    if set(unit_keys) - {row["unitKey"] for row in operations.values()}:
        raise ValueError("unit has no operation")


def generated_catalog():
    catalog = build_catalog()
    validate(catalog)
    catalog["units"].sort(key=lambda row: row["key"])
    catalog["operations"].sort(key=lambda row: row["operationId"])
    for row in catalog["operations"]:
        row["descriptorDigest"] = digest(public_descriptor(row))
    catalog["catalogDigest"] = digest(catalog)
    return catalog


def parse(path):
    return ast.parse(path.read_text(encoding="utf-8-sig"), filename=str(path))


def constants(path, class_name):
    cls = next(node for node in parse(path).body if isinstance(node, ast.ClassDef) and node.name == class_name)
    result = {}
    for node in cls.body:
        if isinstance(node, ast.AnnAssign):
            result[node.target.id] = ast.literal_eval(node.value)
        elif isinstance(node, ast.Assign):
            for target in node.targets:
                if isinstance(target, ast.Name) and isinstance(node.value, ast.Constant):
                    result[target.id] = node.value.value
    return result


def check_coverage(catalog, python_root):
    """AST inspection keeps this check usable on build hosts without ML imports."""
    root = Path(python_root).resolve(strict=True)
    units = {row["key"]: row for row in catalog["units"]}
    builtins = {}
    for node in ast.walk(parse(root / "cube/unit_registry.py")):
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "UnitSpec":
            values = [ast.literal_eval(item) for item in node.args[:4]]
            builtins[values[0]] = tuple(values[1:4])
    catalog_builtins = {key for key in units if key.startswith("unit.")}
    if set(builtins) != catalog_builtins:
        raise ValueError("built-in Unit coverage mismatch: " + str(sorted(set(builtins) ^ catalog_builtins)))
    for key, (attribute, module, class_name) in builtins.items():
        if units[key]["source"] != dict(attribute=attribute, module=module, className=class_name):
            raise ValueError("Unit source mismatch: " + key)
    for unit in units.values():
        source = unit["source"]
        cls = next(node for node in parse(root / (source["module"].replace(".", "/") + ".py")).body
                   if isinstance(node, ast.ClassDef) and node.name == source["className"])
        methods = {node.name for node in cls.body if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))}
        if unit["service"] != "CV":
            capability_names = {node.args[0].value for node in ast.walk(cls)
                                if isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
                                and node.func.id == "AICapability" and node.args and isinstance(node.args[0], ast.Constant)}
            if capability_names != {unit["name"]}:
                raise ValueError("Unit capability name mismatch: " + unit["name"])
        bound = {row["binding"].get("method") for row in catalog["operations"] if row["unitKey"] == unit["key"]}
        if bound - methods - {None}:
            raise ValueError("missing Unit method: " + unit["name"] + " " + str(bound - methods - {None}))
        if "plugin" in unit:
            supported = set()
            for node in cls.body:
                if isinstance(node, ast.Assign) and any(isinstance(t, ast.Name) and t.id == "SUPPORTED_ACTIONS" for t in node.targets):
                    supported.update(ast.literal_eval(node.value))
            mapped_actions = {row["binding"].get("fixed", {}).get("action") for row in catalog["operations"]
                              if row["unitKey"] == unit["key"]}
            if supported != mapped_actions:
                raise ValueError("plugin Action coverage mismatch: " + unit["name"])
        if unit["service"] != "CV":
            explained = set(METHOD_DISPOSITIONS["*"]) | set(METHOD_DISPOSITIONS.get(unit["name"], {}))
            unexplained = {name for name in methods if not name.startswith("_")} - bound - explained
            if unexplained:
                raise ValueError("unclassified public Unit methods: " + unit["name"] + " " + str(sorted(unexplained)))
    action_names = constants(root / "cube/aigc_action.py", "AIGCAction")
    service_tree = parse(root / "cube/aigc_service.py")
    registered, registered_handlers = set(), {}
    dynamic_calls, resolved_calls = set(), set()
    handlers = {node.name for node in ast.walk(service_tree) if isinstance(node, ast.FunctionDef)}

    def path(node):
        if isinstance(node, ast.Name):
            return node.id
        if isinstance(node, ast.Attribute):
            return path(node.value) + "." + node.attr
        return ""

    def is_action_registration(node):
        return isinstance(node, ast.Call) and path(node.func) in ("actions.register", "self.actions.register")

    def register(name, call):
        registered.add(name)
        registered_handlers[name] = path(call.args[1]).removeprefix("self.") if len(call.args) > 1 else ""

    for node in ast.walk(service_tree):
        if is_action_registration(node):
            name = node.args[0]
            if isinstance(name, ast.Constant) and isinstance(name.value, str):
                register(name.value, node)
            elif path(name).startswith("AIGCAction."):
                register(action_names[name.attr], node)
            elif path(name) == "FileProcessorAction.ReadExcel":
                pass  # File processing belongs to its own service contract.
            elif isinstance(name, ast.Name):
                dynamic_calls.add(node.lineno)
            else:
                raise ValueError("unclassified action registration: " + ast.unparse(name))
        if isinstance(node, ast.For) and isinstance(node.iter, (ast.Tuple, ast.List)):
            for call in ast.walk(node):
                if not is_action_registration(call) or not isinstance(call.args[0], ast.Name) or call.args[0].id != path(node.target):
                    continue
                for item in node.iter.elts:
                    if path(item).startswith("AIGCAction."):
                        register(action_names[item.attr], call)
                    else:
                        raise ValueError("unclassified loop action: " + ast.unparse(item))
                resolved_calls.add(call.lineno)
    if dynamic_calls - resolved_calls:
        raise ValueError("unclassified dynamic action registration")
    mapped = {row["binding"]["action"] for row in catalog["operations"] if row["binding"]["service"] == "AIGC"}
    if registered - mapped - ACTION_DISPOSITIONS.keys():
        raise ValueError("unmapped AIGC actions: " + str(sorted(registered - mapped - ACTION_DISPOSITIONS.keys())))
    if mapped - registered - {""}:
        raise ValueError("missing AIGC action registration: " + str(sorted(mapped - registered - {""})))
    for row in catalog["operations"]:
        binding = row["binding"]
        if binding["service"] == "AIGC" and binding["action"]:
            if registered_handlers[binding["action"]] != binding["handler"]:
                raise ValueError("AIGC action handler mismatch: " + binding["action"])
        if binding["service"] == "AIGC" and "." not in binding["handler"] and binding["handler"]:
            if binding["handler"] not in handlers:
                raise ValueError("missing AIGC handler: " + binding["handler"])
    for module, cls_name, method in (("cube.chat_completion", "CompletionRunner", "start"),
                                    ("cube.plugin.router", "PluginUnitRouter", "invoke")):
        cls = next(node for node in parse(root / (module.replace(".", "/") + ".py")).body
                   if isinstance(node, ast.ClassDef) and node.name == cls_name)
        if method not in {node.name for node in cls.body if isinstance(node, ast.FunctionDef)}:
            raise ValueError("missing nested AIGC handler: " + cls_name + "." + method)
    cv_names = constants(root / "cube/cv_action.py", "CVAction")
    cv_tree = parse(root / "cube/cv_service.py")
    cv_registered = {cv_names[node.attr] for node in ast.walk(cv_tree)
                     if isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name) and node.value.id == "CVAction"}
    cv_mapped = {row["binding"]["action"] for row in catalog["operations"] if row["binding"]["service"] == "CV"}
    if cv_registered - cv_mapped - {"setup", "teardown"}:
        raise ValueError("unmapped CV actions: " + str(sorted(cv_registered - cv_mapped)))
    if cv_mapped - cv_registered:
        raise ValueError("missing CV action registration: " + str(sorted(cv_mapped - cv_registered)))
    cv_handlers = {}
    for branch in ast.walk(cv_tree):
        if not isinstance(branch, ast.If) or not isinstance(branch.test, ast.Compare):
            continue
        comparisons = branch.test.comparators
        if len(comparisons) != 1 or not path(comparisons[0]).startswith("CVAction."):
            continue
        action = cv_names[comparisons[0].attr]
        for statement in branch.body:
            for call in ast.walk(statement):
                if isinstance(call, ast.Call) and path(call.func) == "self.executor.submit" and call.args:
                    cv_handlers[action] = path(call.args[0]).removeprefix("self.")
    for row in catalog["operations"]:
        binding = row["binding"]
        if binding["service"] == "CV" and cv_handlers.get(binding["action"]) != binding["handler"]:
            raise ValueError("CV action handler mismatch: " + binding["action"])
    declared = set()
    for manifest in sorted((root / "plugins").glob("*/plugin.json")):
        definition = json.loads(manifest.read_text(encoding="utf-8"))
        for name in definition.get("provides", {}).get("units", []):
            declared.add((definition["id"], name))
        entry_module, entry_function = definition["entry"].split(":")
        entry = parse(manifest.parent / (entry_module.replace(".", "/") + ".py"))
        registration = next(node for node in entry.body if isinstance(node, ast.FunctionDef) and node.name == entry_function)
        registrations = {}
        for call in ast.walk(registration):
            if isinstance(call, ast.Call) and isinstance(call.func, ast.Attribute) and call.func.attr == "register_unit":
                declaration = {keyword.arg: ast.literal_eval(keyword.value) for keyword in call.keywords
                               if keyword.arg in ("name", "key", "module", "class_name")}
                registrations[declaration["name"]] = declaration
        if set(registrations) != set(definition["provides"]["units"]):
            raise ValueError("plugin declaration mismatch: " + definition["id"])
        for declaration in registrations.values():
            if declaration["key"] not in units:
                raise ValueError("plugin Unit coverage mismatch: " + declaration["key"])
            source = units[declaration["key"]]["source"]
            expected_module = str(manifest.parent.relative_to(root)).replace("\\", ".").replace("/", ".") + "." + declaration["module"]
            if source["module"] != expected_module or source["className"] != declaration["class_name"]:
                raise ValueError("plugin source declaration mismatch: " + declaration["key"])
    mapped_plugins = {(unit["plugin"], unit["name"]) for unit in units.values() if "plugin" in unit}
    if declared != mapped_plugins:
        raise ValueError("plugin Unit coverage mismatch: " + str(sorted(declared ^ mapped_plugins)))


def write_or_check(path, raw, check):
    if check:
        if not path.is_file() or path.read_bytes() != raw:
            raise ValueError("generated catalog drift: " + str(path))
    else:
        path.parent.mkdir(parents=True, exist_ok=True)
        handle, temporary = tempfile.mkstemp(prefix=".unit-catalog-", dir=path.parent)
        try:
            with os.fdopen(handle, "wb") as stream:
                stream.write(raw)
            os.replace(temporary, path)
        finally:
            Path(temporary).unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("--python-root", type=Path, help="Validate sources and generate the standalone Python catalog")
    parser.add_argument("--check", action="store_true", help="Fail if generated files have drifted")
    args = parser.parse_args()
    catalog = generated_catalog()
    raw = (canonical(catalog) + "\n").encode("utf-8")
    if args.python_root:
        check_coverage(catalog, args.python_root)
    write_or_check(JAVA_RESOURCE, raw, args.check)
    if args.python_root:
        write_or_check(args.python_root / "cube/unit_tools/unit-operations.json", raw, args.check)
    print("Unit catalog: {} units, {} operations, {}".format(len(catalog["units"]), len(catalog["operations"]), catalog["catalogDigest"]))


if __name__ == "__main__":
    main()
