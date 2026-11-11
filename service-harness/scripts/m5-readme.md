# M5 晋级与回退管理工具

本包可独立解压运行，Python 3.11 标准库，无新增依赖。它不含 JVM JAR、业务包、私钥和真实凭证；M4 环境沿用既有两个 JAR、guard、Provider、工具和业务包。部署本包无需重启 JVM。

完整循序教程在工作区 `tutorials/src/content/docs/guide/11-promotion-and-rollback.md`，实现与部署说明在 `cube-server/service-harness/M5.md`。已有 M4 环境可先行部署管理工具、人工跑通，再讨论真实环境验证。一个 Harness JVM 接多个独立 model/Runner 的部署约束不变。

## 本地检查

从本包解压目录运行，不连接真实租户：

```powershell
python -B examples/check_release_ops.py
python -B examples/release_ops.py --help
```

## 人工操作

1. 复制两个 config 模板到私有目录，分别填写 dispatcher Harness 地址、tokenEnv、真实 Cube domain ID（tenant）；将 token 设置到对应环境变量。URL 不含账号密码。恢复操作要保持同一 domain 和 Cube subject。
2. 准备测试环境已签名、ready 的原始 ZIP、Base64 签名、keyId；目标公钥须获准。目标环境所有锁定依赖、原 policy 的 release 须 ready；Provider、runtime、source/grant、模型和私有配置由运维提前安装。本工具不复制私钥或部署运行环境。
3. 让目标管理员提供完整当前 default policy，保留全部权限、其他技能和配额。现有 REST 只读 binding，不能下载完整 policy。首次空环境按教程第4章操作，本工具用于已有部署升级。

从解压目录执行以下示例，先把私有路径和占位值改为实际值：

```powershell
$Ops = Join-Path $env:LOCALAPPDATA "AgentIntegrationTutorial/m5"
$env:CUBE_PROMOTION_SOURCE_TOKEN = "<测试读取身份token>"
$env:CUBE_PROMOTION_TARGET_TOKEN = "<正式管理身份token>"
python -B examples/release_ops.py promote-plan --source-config "$Ops/source.json" --target-config "$Ops/target.json" --zip "$Ops/candidate.zip" --signature-file "$Ops/signature.base64.txt" --key-id <获准keyId> --base-policy "$Ops/policy-current.json" --policy-version prod-next-1 --replace-release <旧业务manifest-digest> --operation-id prod-promotion-1 --directory "$Ops/promotion-1"
# 确认上条命令exit code为0；审阅plan.json、policy-before.json、policy-next.json及ZIP摘要。
python -B examples/release_ops.py apply --target-config "$Ops/target.json" --directory "$Ops/promotion-1"
```

promote-plan 只查询与校验，不发布、不切换。apply 才实际写入；它原样发布 ZIP、发布新 policy，再携带固定 expectedVersion 做 CAS。检查 receipt.json 的 status=active 与 currentBinding，再用实际对话用户提交一两个小任务，保留 runId 和报告，此时即可先人工跑通。

若超时或中断，保留完整目录并重跑同一条 apply，维持原操作身份、配置和文件。工具会重放原键、原 body、原 CAS 版本，不强制覆盖其他操作员。status=superseded 表示历史操作成功过但当前已被后来切换替代；历史回执重放不会撤销后续回退。

需要回退时，晋级须有确认回执且仍是当前 binding；旧 release 和其依赖仍须 ready。生成独立回退目录：

```powershell
python -B examples/release_ops.py rollback-plan --target-config "$Ops/target.json" --promotion "$Ops/promotion-1" --operation-id prod-rollback-1 --directory "$Ops/rollback-1"
# 确认exit code为0，审阅新目录中的plan.json和policy-next.json后执行：
python -B examples/release_ops.py apply --target-config "$Ops/target.json" --directory "$Ops/rollback-1"
```

回退只改后续新 run 的策略，已有 run 保持创建时的 pin。当前 ACL、撤权和退役规则继续执行。保存两个操作目录、人工任务和实际现象，随后再安排现场验证。M5 本地回归完成；现场部署尚未执行，M6 尚未执行。
