"""Apply the additive M1 admin contracts after the existing contract generators.

Preserves unrelated schemas and paths in each checkout, including local Python changes.
"""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def generate(python_root):
    string = dict(type="string", minLength=1, maxLength=128, pattern="^[A-Za-z0-9_.:-]+$")
    def obj(properties):
        return dict(type="object", properties=properties, required=list(properties), additionalProperties=False)
    definitions = {
        "admin-policy-binding": obj(dict(target=dict(type="string", minLength=1, maxLength=257), expectedVersion=dict(type="integer", minimum=0))),
        "admin-source": obj(dict(baseUrl=dict(type="string", minLength=1, maxLength=1024), secretRef=string,
                                  identityMapping=dict(type="string", enum=["cube-subject-hmac-v1"]),
                                  allowedPaths=dict(type="array", minItems=1, maxItems=16, items=dict(type="string", minLength=1, maxLength=256)))),
        "admin-source-grant": obj(dict(subject=string, sourceRef=dict(type="string", minLength=3, maxLength=257), allowed=dict(type="boolean"))),
        "admin-policy": json.loads((ROOT / "resources/runtime/schemas/tenant-policy.json").read_text(encoding="utf-8")),
    }
    routes = [
        ("post", "packages/validate", None, 200), ("post", "releases", None, 201), ("get", "releases/{digest}", None, 200),
        ("post", "policies", "admin-policy", 201), ("get", "bindings/policy/default", None, 200),
        ("put", "bindings/policy/default", "admin-policy-binding", 200),
        ("put", "sources/{id}/versions/{version}", "admin-source", 201), ("get", "sources/{id}/versions/{version}", None, 200),
        ("post", "source-grants", "admin-source-grant", 200),
    ]
    for root in (ROOT / "contracts", python_root / "harness/contracts"):
        for name, schema in definitions.items():
            (root / "schemas" / (name + ".json")).write_text(json.dumps(schema, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        profiles_path = root / "schemas/profiles-response.json"
        profiles = json.loads(profiles_path.read_text(encoding="utf-8"))
        properties = profiles["properties"]["skills"]["items"]["properties"]
        properties.update(ready=dict(type="boolean"), reason=dict(type="string", maxLength=128))
        profiles_path.write_text(json.dumps(profiles, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        # HTTP metadata is richer than the legacy worker artifact descriptor.
        artifact = json.loads((root / "schemas/artifact.json").read_text(encoding="utf-8"))
        artifact["properties"].update(createdAt=dict(type="integer", minimum=0), state=dict(type="string", maxLength=32),
                                      downloadable=dict(type="boolean"), origin=obj(dict(kind=dict(type="string", enum=["provider"]),
                                      operation=string, jobId=string, confirmed=dict(type="boolean"))))
        artifact["required"] += ["createdAt", "state", "downloadable"]
        (root / "schemas/artifact-view.json").write_text(json.dumps(artifact, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        api = json.loads((root / "openapi.json").read_text(encoding="utf-8"))
        api["components"]["schemas"].update(definitions)
        api["components"]["schemas"]["profiles-response"] = profiles
        api["components"]["schemas"]["artifact-view"] = artifact
        for relative, schema in (("/artifacts/{artifactId}", {"$ref": "#/components/schemas/artifact-view"}),
                                  ("/runs/{runId}/artifacts", obj(dict(artifacts=dict(type="array", items={"$ref": "#/components/schemas/artifact-view"}))))):
            api["paths"]["/harness/v1" + relative]["get"]["responses"]["200"] = dict(description="Authenticated artifact metadata; origin is derived by the server from the confirmed Provider ledger.", content={"application/json": dict(schema=schema)})
        for method, relative, schema, status in routes:
            parameters = [dict(name="Authorization", **{"in": "header"}, required=True, schema=dict(type="string"))]
            for segment in relative.split("/"):
                if segment.startswith("{"):
                    parameters.append(dict(name=segment[1:-1], **{"in": "path"}, required=True, schema=string))
            operation = dict(summary="Harness M1 administration: " + relative, parameters=parameters,
                             security=[dict(HarnessBearer=[])],
                             description="Cube authentication and independent administrator allowlist. Body <=256KiB. No policy can grant administration rights.",
                             responses={str(status): dict(description="Success"), "400": dict(description="Invalid request"), "403": dict(description="Administrator or source forbidden"), "409": dict(description="CAS, immutable version or idempotency conflict")})
            if method in ("post", "put"):
                parameters.append(dict(name="Idempotency-Key", **{"in": "header"}, required=True, schema=string))
                media = "application/json" if schema else "application/zip"
                operation["requestBody"] = dict(required=True, content={media: dict(schema={"$ref": "#/components/schemas/" + schema} if schema else dict(type="string", format="binary"))})
            if relative == "releases" and method == "post":
                for name in ("X-Harness-Key-Id", "X-Harness-Signature"):
                    parameters.append(dict(name=name, **{"in": "header"}, required=True, schema=dict(type="string")))
            if method == "get" and relative == "bindings/policy/default":
                operation["responses"]["200"]["content"] = {"application/json": dict(schema=obj(dict(target=dict(type="string", maxLength=257), version=dict(type="integer", minimum=0))))}
            api["paths"].setdefault("/harness/v1/admin/" + relative, {})[method] = operation
        (root / "openapi.json").write_text(json.dumps(api, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        files = [root / "fixtures.json", root / "openapi.json"] + sorted((root / "schemas").glob("*.json"))
        manifest = {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
        (root / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    p = argparse.ArgumentParser(__doc__)
    p.add_argument("--python-root", type=Path, required=True)
    generate(p.parse_args().python_root)
