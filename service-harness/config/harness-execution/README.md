# 服务端模型、执行器与请求选择

`execution.profiles=./config/harness-execution` 加载本目录全部 JSON。服务器发布模型和执行器，租户只保存技能授权、权限和业务配额。修改目录后重启 Java；在途任务保留完整快照。

| 文件 | 内容 |
| --- | --- |
| `Baize.json` | `Baize@1`，JSON 决策模型 |
| `Baize2.json` | `Baize2@2`，原生工具模型 |
| `resources.json` | `resources@1`，Java 资源执行器，无需 Python runner |
| `trusted-python.json` | `trusted-python@1`，需要启用工具、注册 provider 和匹配的 Python runner |
| `execution.json` | 默认模型/执行器引用、上下文参数、执行上限和可选当前版本映射 |

```properties
execution.profiles=./config/harness-execution
execution.model=Baize@1
execution.runtime=resources@1
execution.tenant.templates=./config/harness-tenant
```

`execution.model`、`execution.runtime` 只设置请求省略相应字段时的默认值；省略这两个属性时使用 `execution.json` 中的引用。它们不限制请求选择其他已发布配置。模型、执行器版本是配置修订号，与 Python 节点编号、模型名称和适配器版本无关。

GET `/harness/v1/task-profiles` 返回 defaults、models、runtimes、当前用户获授权的 skills 和 tools，以及兼容执行器、依赖、就绪状态、容量和离线原因。未激活的技能包工具也可查询；不返回后端凭据或地址。目录是即时观察，创建任务时再次检查。

POST `/harness/v1/runs` 示例：

```json
{"model":"Baize","runtime":"resources","skills":[],"input":{"instruction":"请简短介绍自己","attachmentIds":[]}}
```

`model`、`runtime` 支持名称或 `名称@版本`。省略时使用服务器默认值。`skills` 使用目录的技能名称；省略时允许全部获授权且兼容的技能，`[]` 表示不启用技能。显式选择的技能立即激活，未明确选择的技能继续按需激活。POST `/runs` 不支持 `tools` 字段，传入会返回 HTTP 400。有效工具由服务端按所选技能、权限及执行器计算，并固定在响应 `selection.tools` 中；平台工具也必须经过权限校验，禁用技能不会扩大工具权限。

技能依赖必须已获租户授权；服务端补齐合法依赖并固定 release digest。默认过滤与所选执行器不兼容的技能，显式选择不兼容技能则报错。创建和读取响应的 `selection` 显示实际模型、执行器版本、技能及工具名单。`taskProfileId` 不再使用。

同一 ID 发布多个配置版本时，在 `execution.json` 声明当前版本，例如：

```json
{"activeVersions":{"model":{"Baize":"1"},"runtime":{"resources":"1"}}}
```

单版本 ID 可省略映射；多版本未声明当前版本时启动失败，不按版本字符串大小猜测。明确传 `名称@版本` 可选择其他已发布版本。

`toolSetVersion` 属于各 runtime 文件：resources 使用 `resource-tools-v1`，可信执行器使用 `broker-tools` 或 `sandbox-tools`。`execution.json` 的 requiredCapabilities 由所选模型与执行器共同满足。在线推理上下文使用 `transport-bytes-v1`，scripted 夹具使用 `full-json-bytes-v1`。

模型无健康在线节点时创建返回 HTTP 503 / MODEL_NOT_READY，必需 runner 或 provider 未就绪时返回 HTTP 503 / RUNTIME_NOT_READY；健康节点容量已满仍允许排队，不自动替换模型。相同幂等请求优先返回原受理快照，不受后续配置、授权或节点状态变化影响；不同选择使用同一键返回 HTTP 409。`/capabilities.runsReady` 表示存在可接单的就绪组合，具体选择以目录和创建校验为准。

POST 仍需 Cube Bearer、JSON Content-Type 和 Idempotency-Key。worker/backend 身份、授权、物理容量和密钥继续由服务器私有 properties 管理。一个 JVM 支持多个同角色 Python 节点，每个节点使用独立 workerId、实例、目录和租约。
