# 服务端模型、执行器与请求选择

`execution.profiles=./config/harness-execution` 加载本目录全部 JSON。服务器发布模型和执行器，租户只保存技能授权、权限和业务配额。修改目录后重启 Java；在途任务保留完整快照。

默认 `execution.json` 修订号为 `2`，新增以下可选字段；旧固定配置缺省时保持原行为：

| 字段 | 默认模板值 | 行为 |
| --- | --- | --- |
| `contextPolicy.taskState` | `true` | 注入从账本导出的近期证据、历史错误、待决依赖、授权动作及剩余轮次/工具/修复预算 |
| `contextPolicy.historyWindow` | `3` | 保留最近三组完整工具调用；只有已授权 `context.read_result` 时才归档更早的完整配对 |
| `contextPolicy.skillWindow` | `6` | 优先载入最近访问过的六个 active SKILL 首页；固定资源目录及显式读取句柄仍保留 |
| `contextPolicy.finalWireGuard` | `true` | 实际推理请求在 Java 入队前重新检查字节和上下文预算，含格式修复请求 |
| `executionLimits.maxRepeatedCalls` | `3` | 相同参数和相同结果的不可变读取，或相同参数错误连续三次后，以 `TASK_NO_PROGRESS` 停止 |

无进展检测目前覆盖 resource.read/search/list、context.read_result 和计算器；查询刷新、时间/状态轮询不按结果不变判死。新 `control.resume` 输入重置连续计数；新参数或新结果中断连续重复。它不是通用语义完成判定器，原有轮次、工具、修复和生命周期上限继续约束所有运行。

通用模型须在 Java ModelProfile 和 Python `model` 配置中显式指定 `nativeTools`。`false` 对应 `openai-json-v2`，`true` 对应 `openai-tools-v2`；模型名称仅用于身份/路由。Java `ModelProfiles.model(name,nativeTools,contextTokens)` 支持显式能力。旧 Baize/Baize2 节点保留兼容默认；未知模型省略能力时拒绝启动，能力与固定请求冲突时拒绝执行。

默认 Baize/Baize2 继续使用 `transport-bytes-v1`，snapshot 的 `budgetMeasurement.accuracy=estimated`；没有随包交付已验证的远端 tokenizer/聊天模板资产。需要精确计数时，在私有 Java `libs/` 安装实现 `ModelBudgets.Counter` 的可信类，通过 `model.token.counter.classes` 注册；使用独立版本的 tokenizer/template 描述符，将预算版本设为 `model-tokens-v1`。计数器必须精确匹配模型、adapter、tokenizer 资产及聊天模板版本，分别计算完整上下文和实际推理 wire（含工具定义、模板帧和修复提示），不能只对 JSON 序列化文本分词。没有或有多个匹配计数器时拒绝；字节上限独立保留。此 SPI 不构成任何实际模型的 tokenizer 认证，实际 usage 仍由 Java 推理账本结算。

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

原生工具名称可在模型配置的 generation 内选 `"toolNameStyle":"readable-v1"`，例如将 Baize2 的 generation 保持原字段并补入此项。省略或 `hashed-v1` 保留已有哈希别名。readable-v1 由真实工具名的合法字符前缀和摘要组成，最长 59 字符，历史 assistant/tool 配对使用同一映射；工具授权名与参数契约不变。该项属于服务端固定的 ModelProfile，不能放到 Python 节点配置或用户输入中覆盖。

启用前升级全部可能接到该逻辑模型任务的 Python model worker（model_adapter.py），逐节点 drain、对账并重启。再复制模型配置为新的文件和 version，添加 toolNameStyle，并按 activeVersions/defaults 规则发布；不原地修改旧版本。旧 worker 不识别可读映射且不会自动拒绝此配置，混合版本不能开启。原配置继续可选，旧 run 使用固定快照。先以小任务验证，再用同一冻结任务集比较；可读名称的质量收益以真实评测为准。

模型无健康在线节点时创建返回 HTTP 503 / MODEL_NOT_READY，必需 runner 或 provider 未就绪时返回 HTTP 503 / RUNTIME_NOT_READY；健康节点容量已满仍允许排队，不自动替换模型。相同幂等请求优先返回原受理快照，不受后续配置、授权或节点状态变化影响；不同选择使用同一键返回 HTTP 409。`/capabilities.runsReady` 表示存在可接单的就绪组合，具体选择以目录和创建校验为准。

POST 仍需 Cube Bearer、JSON Content-Type 和 Idempotency-Key。worker/backend 身份、授权、物理容量和密钥继续由服务器私有 properties 管理。一个 JVM 支持多个同角色 Python 节点，每个节点使用独立 workerId、实例、目录和租约。
