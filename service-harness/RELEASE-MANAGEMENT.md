# 业务包晋级、CAS 切换与回退

跨环境晋级保持原始签名 ZIP 与 manifest digest，通过不可变 policy 和固定 expectedVersion 切换 binding。晋级和回退只影响后续新 run，已有 run 保留创建时的完整 pin；当前权限、数据源 ACL 与 release 退役规则仍生效。

## 工具与部署前提

管理工具源码为 [release_ops.py](../../tutorials/examples/release_ops.py)，配套 agent_client.py，使用 Python 3.11 标准库。配置模板为 [源环境](../../tutorials/examples/config/promotion-source.example.json)和[目标环境](../../tutorials/examples/config/promotion-target.example.json)。完整命令见[晋级与回退教程](../../tutorials/src/content/docs/guide/11-promotion-and-rollback.md)。

工具可直接从教程 examples/ 运行；独立工具包的用法见 [随包说明](scripts/release-tools-readme.md)。现有打包脚本 `python -B service-harness/scripts/stage_m5.py --output C:/releases/harness-release-tools` 从 cube-server/ 运行，会核对脚本指定的本机 JAR、业务包基线摘要并输出新目录与 ZIP；目标目录和 ZIP 须不存在。打包不构建服务或调用远端，工具包不包含 JAR、业务 ZIP、私钥、token 与现场配置。

已具备查询、加工、报告能力的环境可独立安装管理工具，无需重启 JVM。首次空环境先按 [部署指南](BUSINESS-DEPLOYMENT.md)与教程[业务包发布](../../tutorials/src/content/docs/guide/04-publish.md)准备服务和初始 policy。

目标环境预先批准签名 keyId 与管理身份，并准备全部锁定依赖、原 policy 的 release、Provider、runtime、模型/Runner、source/grant、origin/path 与私有 secret。相同 sourceRef 映射各环境实际业务地址；改包内容须作为新候选重新签名。

## 生成可审阅计划

`promote-plan` 查询源/目标 release 与 binding，调用只读 packages/validate；源包须签名为 SHA256withRSA 且 ready。它将原 ZIP、签名、完整前后 policy、固定 binding 版本、目标地址/租户与操作 ID 保存到新目录，尚不发布或切换。

管理员须提供完整原 policy JSON，包含 schemaVersion、kind、id、version、permissions、skillReleases、businessLimits；REST 只能读取 binding 的 target/version，不能下载完整 policy。plan 核对版本，apply 用不可变 POST 确认原 policy 内容一致后继续。

晋级保留原权限、无关 release 和配额，合入候选权限与锁定依赖。同名业务包显式指定被替换的旧 digest；同名工具依赖冲突时先准备兼容基线，不静默移除其他技能或工具。

配置 tenant 为真实 Cube domain ID，token 从 tokenEnv 指定的环境变量读取。apply 在任何写操作（含恢复 CAS）前核对目标租户；恢复须保持同租户、操作 subject 和原配置，更换 token 值不能更换身份。URL 不含账号密码。文件摘要与 planSha256 用于检出意外修改，不替代权限和签名。

## 应用、恢复与回退

```text
promotion/
  plan.json
  package.zip
  signature.txt
  policy-before.json
  policy-next.json
  receipt.json
  .apply.lock
```

`apply` 用 operationId 派生固定 `.base/.validate/.release/.policy/.bind` 幂等键。CAS 始终携带计划中的 target 与 expectedVersion；binding 已过期且尚未尝试 CAS 时停止，不写远端。CAS 前持久化 bindAttempted，响应丢失或中断后重跑同目录、同配置、同命令，重放原请求取得回执，不改用最新版本强制切换。

receipt.status=active 表示读取时该绑定仍生效；superseded 表示历史操作成功，但已被后续切换替代。历史回执重放不会撤销后来回退。网络错误保留完整目录与操作 ID，不改文件或直接删除锁文件；OS 锁随进程退出释放，并行 apply 拒绝。真实版本冲突须核对后另建计划。

`rollback-plan` 只接受已有确认绑定回执且仍为当前 binding 的晋级操作；它固定当前版本与原 policy，另建目录，审阅后 apply。旧包和依赖须仍 ready，原 policy 须保留。回退前创建的候选 run 继续使用原 pin，后续新 run 才取回退版本。

## 人工操作与检查

1. 准备测试环境 ready 候选、原始 ZIP/签名、目标完整旧 policy 与两份私有配置。
2. promote-plan 后审阅 digest、租户、前后 policy、权限与配额，再 apply。
3. 保存 receipt，确认 status=active 和 currentBinding；用实际对话用户提交小任务，检查规范报告。
4. 需要演练回退时另建 rollback-plan，审阅、apply 后再提交小任务。
5. 保留两份操作目录、runId、报告和实际现象，供恢复与后续环境验证。

Java 回归入口为 `ant -f service-harness/build.xml test-execution`，覆盖真实 RSA 签名、跨租户同摘要发布、CAS 冲突、回退、回执重放和旧 run pin。工具包解压后先运行 `python -B examples/release_ops.py --help` 检查入口，再按上述顺序在准备好的环境生成计划、审阅并执行；本地构建与命令检查不代表已完成真实业务或模型验证。
