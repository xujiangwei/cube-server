# 服务端模型、执行器与请求选择

`execution.profiles=./config/harness-execution` 加载本目录全部 JSON。服务器发布模型和执行器，租户只保存技能授权、权限和业务配额。修改目录后重启 Java；在途任务保留完整快照。

`document-python.json` 提供 32 MiB 工作区的可信文件执行器，用于 PDF、图片、表格和报告。它需要文件服务、对应 Provider 和具有匹配环境摘要及 provider-await 协议的健康 Python Runner。OCR 和音频转写使用具备 provider-await 的普通执行器，由现有 Unit 处理。健康节点满载时允许排队，目录的 available 表示当前空闲容量。安装与授权见[文件处理部署](../../BUSINESS-DEPLOYMENT.md#通用文件工具部署)。

完整互动语义见[互动方案](../../INTERACTION.md)，配置更新与人工运行顺序见[部署指南](../../BUSINESS-DEPLOYMENT.md)。普通消息入口与模型结构化提问是独立开关，客户端不能替代服务端配置或 worker 能力声明。

## 互动配置

普通执行中补充只需在私有 harness.properties 开启 `interaction.enabled=true`；默认关闭。消息总数默认 128，待采纳数默认 32，配置分别为 `interaction.max.messages` 和 `interaction.max.pending`，后者不得大于前者。初始输入是版本 0，后续有效输入从版本 1 递增；追加不重置原有轮次、工具、修复、费用或时间预算。

需要选项卡提问时，复制模型 JSON 为一个新版本，例如将 Baize2 的版本改为 `3`，在 `generation` 增加 `"structuredInput":true`，并让当前模型引用指向 `Baize2@3`。旧版本文件可保留；同一 ID 有多个版本时更新下文的 current 映射。新 Python 模型单元声明 `structuredInput=true`，Java 只将此模型的作业交给具备该能力的单元；旧 worker 仍可处理未开启该标志的模型。不能仅更新客户端就开启新模型格式。

互动任务建议发布独立的执行配置版本，例如在 execution.json 保留其他字段、将 version 改为 `4`，并配置：

```json
{"maxRounds":16,"maxTools":32,"maxRepairs":2,"maxRepeatedCalls":3,"maxActiveMillis":300000,"maxInteractionWaitMillis":600000,"maxLifetimeMillis":1800000}
```

以上对象替换 `executionLimits`。`maxActiveMillis` 统计排队、处理和对账时间，扣除已持久化的用户输入/审批等待；缺省等于 maxLifetimeMillis。`maxInteractionWaitMillis` 限制每次等待，缺省使用 control.wait.millis；实际等待还受 control.wait.millis 和总期限限制。总期限包括等待，不随重启或追加消息续期。租户 policy.businessLimits.maxLifetimeMillis 必须允许所需总期限，否则取较小值；初始租户模板也需同步配置，修改模板不会改写已初始化租户。

基础补充、结构化提问和固定业务流程可以分别配置。旧 CompletionGuard 默认不支持目标调整，会返回 WORKFLOW_INTERACTION_UNSUPPORTED；配套 WorkflowGuard 已支持按最新输入版本重新规划查询及后续步骤。固定业务要求和授权继续生效。安装与人工运行顺序集中见 [部署指南](../../BUSINESS-DEPLOYMENT.md)。

默认 `execution.json` 修订号为 `4`，上下文参数如下；旧固定配置缺省时保持原行为：

| 字段 | 默认模板值 | 行为 |
| --- | --- | --- |
| `contextPolicy.taskState` | `true` | 注入原始用户输入引用、确认事实、查询路线、错误纠正、读取会话、待决依赖及剩余预算；工具内容作为 user 数据 |
| `contextPolicy.evidenceAdjustment` | `true` | 在原模型轮次内根据确认错误、信息缺口和证据冲突调整行动；要求 taskState=true，缺省 false；不增加工具、权限、轮次或独立规划调用 |
| `contextPolicy.adaptiveHistory` | `true` | 预算充足时保留全部历史，压力下逐组归档；保留最新完整组、当前回读会话、未消费游标及引用依赖闭包 |
| `contextPolicy.historyWindow` | 模板省略 | 仅旧策略使用固定近期组数；adaptiveHistory=true 时由预算选择取代它 |
| `contextPolicy.compressionTriggerPercent` | `80` | 输入达到可用预算的 80% 后开始压缩，需同时设置目标百分比并启用 adaptiveHistory |
| `contextPolicy.compressionTargetPercent` | `60` | 压向可用预算的 60%；不可达时恢复首个满足硬预算的候选并标记 targetUnreachable，保留有效正文 |
| `contextPolicy.repairReserveTokens` / `repairReserveBytes` | `1024` / `2048` | 独立预留格式修复的计数单位和字节空间；字节模式下前者也是保守估算单位 |
| `contextPolicy.semanticSummary.enabled` | `false` | 可选模型语义交接摘要；必须同时启用 adaptiveHistory 和 finalWireGuard |
| `contextPolicy.semanticSummary.maxCalls` | `1` | 每个 run 最多摘要次数，允许 1..2；失败调用也占次数，不重复总结同一来源 |
| `contextPolicy.semanticSummary.outputReserve` / `maxSummaryBytes` | `512` / `4096` | 每次摘要输出预算及完整来源包装的 UTF-8 上限；实际安排摘要时按最终 JSON 转义开销预留注入空间，预算拒绝后可撤掉摘要 |
| `contextPolicy.skillWindow` | `6` | 优先载入最近访问过的六个 active SKILL 首页；固定资源目录及显式读取句柄仍保留 |
| `contextPolicy.finalWireGuard` | `true` | 实际推理请求在 Java 入队前重新检查字节和上下文预算，含格式修复请求 |
| `executionLimits.maxRepeatedCalls` | `3` | 相同参数和相同结果的不可变读取，或相同参数错误连续三次后，以 `TASK_NO_PROGRESS` 停止 |

百分比以扣除 outputReserve、修复预留及本次可选摘要预留后的预算为基数，模型计数、wire 字节、作业字节三道限制分别检查。触发值必须大于目标值。检查点持久保存已归档组和各来源文本额度；下一轮沿用，重新成为依赖的旧组恢复保留，避免反复扩张和压缩。未授权 `context.read_result` 时不归档，不能放入预算的完整回读或固定输入仍明确拒绝。

语义摘要复用同一逻辑模型、Java 推理队列和多 worker 调度，不增加外部服务。只读取最多八项已归档原始证据的有界视图，不把旧摘要再作摘要输入。摘要绑定用户输入摘要、源清单摘要、事件范围、模型版本和 intent/resultHash；Java 从实际响应验证、重建，作为普通 user 数据恢复。用户追加或纠正输入后不复用旧摘要。格式无效或已确认结束的摘要失败可回退确定性状态，unknown 停止且保留容量。摘要独立计真实 usage，不计入格式修复次数，不替代权限、工具原文或报告验证。默认关闭时无需额外推理。

Java 按与 Python 一致的 `openai-wire-v1` 结构计量实际模型输入，worker 校验 wireDigest；工具说明、序列化示例和原生工具定义均计入。实际派发前再次准入。仅 Java 已持久证明未派发的预算拒绝允许最多两次有界正文重建；未知执行不重试。最多一次摘要、一次正常决策、一次格式修复、两次预算拒绝，共五个 wireAttempt；两次预算拒绝用量为零。

无进展检测目前覆盖 resource.read/search/list、context.read_result 和计算器；查询刷新、时间/状态轮询不按结果不变判死。新 `control.resume` 输入重置连续计数；新参数或新结果中断连续重复。它不是通用语义完成判定器，原有轮次、工具、修复和生命周期上限继续约束所有运行。

任务工作状态保留每次失败。同工具、同参数、同输入版本后续成功才记录 resolvedBy，含义仅是执行恢复；改参数成功以 successfulFollowUps 关联，模型仍须核对是否处理了原依赖，不能把无关查询成功当成恢复。旧输入错误保留历史并标为 supersededByInputRevision。工具返回的 conflicts/sourceVersion 仅作为有界用户数据保留，不构成宿主对语义冲突的认定。

关闭 evidenceAdjustment 可比较既有推进策略；两组都保留修复后的错误投影和相同观测。不要同时切换历史压缩、语义摘要、模型或固定流程再把结果归因于本开关。当前总执行上限是轮次、工具数、修复数和期限，加上每次上下文/wire 上限；实际 token 用量另计，未实现任务总 token/金额硬准入。缺测不作零成本。比较与冻结方法见[评测方案](../../TRACE.md)。

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
