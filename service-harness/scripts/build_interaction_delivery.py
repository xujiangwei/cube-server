"""Package the built interaction upgrade, public configuration and local chat client."""

import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import zipfile


SERVER = Path(__file__).resolve().parents[2]
MODULE = SERVER / "service-harness"
JARS = {
    "cube-common-3.0.jar": "cube/common/action/HarnessAction.class",
    "cube-dispatcher-3.0.jar": "cube/dispatcher/harness/HarnessGateway.class",
    "cube-service-harness-3.0.jar": "cube/service/harness/HarnessService.class",
    "cube-service-harness-skill-3.0.jar": "cube/service/harness/skill/WorkflowGuard.class",
}
DOCS = (
    "README.md", "INTERACTION.md", "BUSINESS-DEPLOYMENT.md", "BUSINESS-DATA.md",
    "WORKFLOW-REPORTS.md", "CONTROL-FILES.md", "STORE.md", "CONTRACT.md", "EXECUTION.md",
    "RUNTIME.md", "REMOTE-NODES.md", "RESILIENCE.md", "RELEASE-MANAGEMENT.md", "TOOLS.md",
    "TRANSPORT.md", "TRACE.md", "UNIT-TOOLS.md",
)
README = """# Harness 持续互动交付包

此包用于升级已有 Cube 部署，提供执行中补充和调整、主动问题与选择、刷新恢复及完成后继续对话。

| 路径 | 用途 |
| --- | --- |
| `jars/cube-common-3.0.jar` | 配套更新 service 和 dispatcher 的 libs/ |
| `jars/cube-dispatcher-3.0.jar` | 更新 dispatcher 的 cellets/ |
| `jars/cube-service-harness-3.0.jar` | 更新 service 的 cellets/ |
| `jars/cube-service-harness-skill-3.0.jar` | 使用 WorkflowGuard 时更新 service 的 libs/ |
| `python-harness-node.zip` | 在每个 Python 单元更新完整运行包，保留各自身份、运行目录和 journal |
| [部署指南](cube-server/service-harness/BUSINESS-DEPLOYMENT.md) | 升级、迁移、配置、人工运行和回退顺序 |
| [互动契约](cube-server/service-harness/INTERACTION.md) | 消息、问题、执行边界、恢复和历史限制 |
| [聊天客户端](harness/client/README.md) | Python 3.11 本机代理及浏览器界面 |
| `cube-server/service-harness/config/` | 公共配置模板，须合并到私有配置 |
| `cube-server/service-harness/contracts/` | REST 与 worker 协议及摘要索引 |
| `cube-server/service-harness/resources/store/` | SQLite/MySQL 版本化建表定义 |
| `cube-server/service-harness-skill/config/` | 可选 Provider/WorkflowGuard 配置示例 |
| `checksums.json` | 其余交付文件的 SHA-256，清单不包含自身 |

先按部署指南关闭新准入、drain、备份，再成套替换并启动。Java 构建和运行要求 JDK 8；Python 要求 3.11，依赖按节点包 requirements.txt 安装。此包不包含基础 Cell、Cube Auth/AIGC、第三方 JAR、生产数据库或私有凭据；沿用原部署的兼容依赖。

interaction.enabled 默认 false，部署后显式开启。结构化问题按需另发布 generation.structuredInput=true 的模型配置；普通补充不依赖该格式。一个 Java JVM 可连接多个同角色 Python 单元，调度按能力、健康和容量选择。固定业务流程需配套更新 WorkflowGuard。

能力和配置文档保持工作区目录结构；涉及业务包、教程或其他宿主组件的链接应在完整源码工作区阅读。本包是升级材料，不会自动部署或覆盖私有配置。
"""


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build(python_root, output):
    python_root, output = python_root.resolve(), output.resolve()
    archive_path = output.with_suffix(".zip")
    if output.exists() or archive_path.exists():
        raise ValueError("DELIVERY_ALREADY_EXISTS: use a new output directory")
    for name, entry in JARS.items():
        with zipfile.ZipFile(SERVER / "build" / name) as jar:
            header = jar.read(entry)[:8]
            if header[:4] != b"\xca\xfe\xba\xbe" or int.from_bytes(header[6:8], "big") != 52:
                raise ValueError("JDK8_BYTECODE_REQUIRED: " + name)
    spec = importlib.util.spec_from_file_location("interaction_python_bundle", python_root / "harness/scripts/build_bundle.py")
    bundle = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(bundle)
    output.mkdir(parents=True)

    def copy(source, relative):
        target = output / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)

    def module_copy(relative):
        copy(MODULE / relative, Path("cube-server/service-harness") / relative)

    for name in JARS:
        copy(SERVER / "build" / name, Path("jars") / name)
    bundle.build(output / "python-harness-node.zip")
    for name in DOCS:
        module_copy(name)
    for name in ("harness.properties.template", "standalone.xml", "cohost-cellet.xml",
                 "harness-execution/README.md", "harness-execution/Baize.json", "harness-execution/Baize2.json",
                 "harness-execution/execution.json", "harness-execution/resources.json", "harness-execution/trusted-python.json",
                 "harness-tenant/README.md", "harness-tenant/default.json"):
        module_copy(Path("config") / name)
    for family, pattern in (("contracts", "*.json"), ("resources/store", "*.sql")):
        for path in sorted((MODULE / family).rglob(pattern)):
            module_copy(path.relative_to(MODULE))
    for name in ("run.ps1", "run.sh", "init_dev.py", "evaluate_traces.py", "quality_evaluation.py", "replay_trace.py"):
        module_copy(Path("scripts") / name)
    skill = SERVER / "service-harness-skill"
    for path in sorted((skill / "config").iterdir()):
        if path.name.endswith((".template", ".example", ".example.json")) or path.name == "runtime-cpu.json":
            copy(path, Path("cube-server/service-harness-skill/config") / path.name)
    copy(skill / "README.md", "cube-server/service-harness-skill/README.md")
    for name in ("README.md", "src/Harness.js", "src/Harness.md"):
        source = SERVER / "docs/rest-api" / name
        if source.exists():
            copy(source, Path("cube-server/docs/rest-api") / name)
    client = SERVER.parent / "harness/client"
    for name in ("README.md", "server.py", "index.html", "style.css", "app.js", "test_client.py", "test_app.cjs"):
        copy(client / name, Path("harness/client") / name)
    (output / "README.md").write_text(README, encoding="utf-8")
    checksums = {path.relative_to(output).as_posix(): sha(path) for path in sorted(output.rglob("*")) if path.is_file()}
    (output / "checksums.json").write_text(json.dumps(checksums, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    with zipfile.ZipFile(archive_path, "x", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(output.rglob("*")):
            if path.is_file():
                archive.write(path, (Path(output.name) / path.relative_to(output)).as_posix())
    print(json.dumps(dict(output=str(output), archive=str(archive_path), files=len(checksums) + 1,
                          archiveSha256=sha(archive_path)), ensure_ascii=False))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--python-root", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=SERVER / "build/harness-interaction-delivery")
    args = parser.parse_args()
    build(args.python_root, args.output)
