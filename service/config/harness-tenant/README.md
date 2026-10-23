# 租户业务策略

本目录由 `execution.tenant.templates=./config/harness-tenant` 加载，只接受一份 `default.json`。模型、运行时、执行器和工具集配置统一位于相邻的 `harness-execution/`，放入本目录会在启动校验时被拒绝。

```json
{
  "schemaVersion": 1,
  "kind": "policy",
  "id": "default",
  "version": "1",
  "skillReleases": [],
  "permissions": ["skill.list", "skill.activate", "resource.list", "resource.read", "resource.search", "input.read_text", "artifact.write_text"],
  "businessLimits": {"maxActiveRuns": 128, "maxLifetimeMillis": 300000}
}
```

| 字段 | 含义 |
| --- | --- |
| `skillReleases` | 租户已授权的技能 release 摘要，包括其固定依赖。初始化模板必须为空；发布技能后通过租户策略版本更新。 |
| `permissions` | 业务授权。实际权限还取决于可信用户身份、服务器授权及平台/技能声明。声明文件权限不等于启用文件服务。 |
| `businessLimits.maxActiveRuns` | 租户活跃任务配额，1～256，并受服务器 `execution.max.active` 上限约束。 |
| `businessLimits.maxLifetimeMillis` | 租户单任务最长生命周期，1000～86400000 毫秒，并受服务器执行上限约束。 |

配置不含模型、运行时、工具列表、工具集版本或 worker 身份。服务器更换模型、后端或执行器不需要更新本文件，也不需要逐租户发布。

认证通过后，服务按 Cube token 的 domain 原子初始化租户：保存一份 policy、建立 `policy/default` active 绑定及初始化记录。不会向租户复制 model/runtime/task。已有业务策略保留，修改此模板只影响未初始化的租户；已有租户的权限、技能和配额通过 `RuntimeAdmin profile` 发布 `kind=policy` 的新版本，再执行 `bind policy default default@<版本> <当前绑定版本>`。策略中不接受执行配置字段。

任务创建时同时固定租户策略、获准技能、有效权限和服务器当时的执行配置。配置修改只影响新任务；同一 Idempotency-Key 和相同输入始终重放原受理回执。

升级时需同时部署新的 `harness-execution/`、本目录和 `harness.properties`，移除本目录原有 `Baize2.json`、`resources.json`、`task-Baize2.json`。本次调整不迁移旧测试表；按部署计划清空旧测试数据后由服务器重建。
