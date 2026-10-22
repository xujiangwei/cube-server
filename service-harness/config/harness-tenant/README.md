# 租户初始化配置

`execution.tenant.templates=./config/harness-tenant` 指向本目录，相对路径按 Java 服务工作目录解析。启用 `execution.enabled=true` 时，服务启动读取并校验目录中的 `.json` 文件，形成不可变快照；README 不参与加载。文件名用于管理，实际身份由 JSON 的 `kind/id/version` 决定。

| 文件 | 定义 | 引用关系 |
| --- | --- | --- |
| `Baize2.json` | 模型 `Baize2@2` | Java 推理网关与 `openai-tools-v2` adapter |
| `resources.json` | 运行时 `resources@1` | 资源操作及工作区配额 |
| `task-Baize2.json` | 任务 `resources-Baize2@2` | 固定以上模型、运行时、权限、上下文和执行限额 |

业务请求通过 Cube token 认证后，以 `token.domain` 初始化租户：在同一事务中复制 profile、为每个 task 建立 active 绑定并保存初始化记录。已有 profile 或 binding 的租户保留原配置。模板修改须重启服务，且仅影响尚未初始化的新租户；已有租户通过 `RuntimeAdmin` 发布新版本、切换 active 绑定。在途 run 始终使用创建时固定的定义。

## 通用规则

- 现行配置表中的字段均为必填，不会自动补默认值；“当前值”是随部署提供的配置值。
- 对象不接受未知字段。`id`、`version` 及普通声明项为 1～128 字符，仅允许 `A-Z a-z 0-9 _ . : -`；`version` 是字符串，和整数 `schemaVersion` 含义不同。
- 摘要为 64 位小写十六进制 SHA256。`capabilities`、`permissions`、`requiredCapabilities`、`skillReleases` 声明数组不得重复，每组最多 64 项。
- 每个 JSON 文件不超过 1 MiB；目录最多 256 份定义、1～64 个 task；同 `kind/id/version` 不得重复，每个 task ID 只允许一个版本。task 引用的 model/runtime 必须同目录存在，其能力并集须覆盖 `requiredCapabilities`。
- 初始化模板的 `skillReleases` 必须为空。技能包在租户初始化后单独发布和绑定；这里不安装包、provider 或 Python worker，也不按在线 worker 数量过滤任务定义。
- 同一租户已发布的 `kind/id/version` 不可换内容。修改模型或运行时后，为已有租户发布时应增加版本，同时更新 task 引用并发布新的 task 版本。

## Baize2.json：模型

| 字段 | 类型 / 当前值 | 含义与约束 |
| --- | --- | --- |
| `id` | string / `Baize2` | 逻辑模型名；与 task 的 `model.id`、Java `worker.<id>.model` 及 Python `model.model` 对应。 |
| `version` | string / `"2"` | 不可变模型配置版本；task 以 ID 和版本精确引用。 |
| `kind` | string / `model` | 定义类型，固定为 `model`。 |
| `backend` | string / `java-inference` | 执行方式，本配置经 Python adapter 请求 Java 推理网关。此值不是物理 backendId；具体后端由 Java worker/backend 配置绑定。 |
| `capabilities` | string[] / `["resource_decisions"]` | 模型提供的决策能力，用于满足任务能力要求；不授予工具权限。 |
| `adapter` | string / `openai-tools-v2` | 决策适配规范。`nativeTools=true` 必须配 `openai-tools-v2`；false 配 `openai-json-v2`，后者将工具/schema 放入提示。 |
| `nativeTools` | boolean / `true` | 是否使用 OpenAI 原生 tools/tool_calls；须与 adapter、Python 声明及真实后端能力匹配。 |
| `certification` | string / `offline-adapter` | 校验标记，java-inference 格式允许 `offline-adapter`、`online-certified`。当前值表示离线适配验证；修改字符串本身不会完成在线认证。 |
| `maxContextTokens` | integer / `65536` | 上下文预算上限，128～1048576。当前算法按渲染后的 UTF-8 字节计数，再加模板开销与输出预留；不代表远端模型真实 token 容量。 |
| `templateOverhead` | integer / `4096` | 上下文计数的额外预留，0～8192；与输入计数相加后检查预算。 |
| `generation` | object | 下列生成参数；不接受其他字段。 |
| `generation.temperature` | integer / `0` | 生成温度，当前格式只接受整数 0。 |
| `generation.topP` | integer / `1` | nucleus sampling 参数，当前格式只接受整数 1，发往上游时为 `top_p`。 |
| `generation.stream` | boolean / `true` | 是否使用流式推理。上游流由 Java 保存结果和 usage；客户端事件流另由 dispatcher 提供。 |
| `generation.maxRepairs` | integer / `1` | 单轮决策格式修复上限，0～1；还受 task 剩余修复总额约束，不用于重跑已执行的工具。 |
| `tokenizer` | object | 传输预算算法描述，ID 与版本须匹配 `ModelProfiles.tokenizer()`。 |
| `tokenizer.id` | string / `transport-bytes-v1` | 字节预算算法标识；不加载远端 tokenizer。 |
| `tokenizer.version` | string / `"1"` | 预算算法版本，须和实现匹配。 |
| `template` | object | 上下文渲染模板描述，ID 与版本须匹配 `ContextEngine.template()`。 |
| `template.id` | string / `harness-json-template-v1` | 模板标识，渲染为固定前缀加完整规范化 JSON 请求。 |
| `template.version` | string / `"1"` | 模板版本，须和实现匹配。 |

模型配置省略 `schemaVersion`，按当前格式校验；旧配置可保留整数 `schemaVersion: 3`，显式填写 1、2 或其他值仍会被拒绝。它表示配置格式，不选择模型功能；实际执行方式由 `backend` 和 `adapter` 决定，`version` 则用于引用不可变配置。

现行模型配置不需要 `adapterDigest`、`tokenizer.digest` 或 `template.digest`；旧配置中的这些字符串字段允许保留，但不校验 SHA256 格式或取值，也不参与上下文匹配及作业路由。adapter 仍须与 nativeTools 匹配，tokenizer/template 仍须使用实现支持的 ID 和版本。可用 `ExecutionAdmin <新目录>` 导出匹配当前实现的 Baize/Baize2 默认定义，再按需调整预算并发布。

## resources.json：运行时

| 字段 | 类型 / 当前值 | 含义与约束 |
| --- | --- | --- |
| `schemaVersion` | integer / `2` | 运行时定义格式，当前固定为 2。 |
| `id` | string / `resources` | 运行时 ID，与 task 的 `runtime.id` 对应。 |
| `version` | string / `"1"` | 不可变运行时版本，与 task 的 `runtime.version` 对应。 |
| `kind` | string / `runtime` | 定义类型，固定为 `runtime`。 |
| `mode` | string / `resources-only` | 当前模式只提供资源操作，不运行包内脚本或模型输出的代码。可信工具运行时另使用 `trusted-python-v1` 及配套 Runner。 |
| `capabilities` | string[] / `["resources"]` | 运行时能力声明，与模型能力一起核对 task 的要求。 |
| `maxWorkspaceBytes` | integer / `1048576` | 单个工作区字节配额，1～1073741824，当前 1 MiB；不是仓库或文件根的总配额。 |
| `maxWorkspaceFiles` | integer / `128` | 单个工作区文件数上限，1～4096。 |
| `certification` | string / `offline-resources` | `resources-only` 模式固定为此值。可信工具模式使用 `offline-trusted-runner`，需同时配置相应工具部署。 |

## task-Baize2.json：任务

| 字段 | 类型 / 当前值 | 含义与约束 |
| --- | --- | --- |
| `schemaVersion` | integer / `2` | 任务定义格式，当前固定为 2。 |
| `id` | string / `resources-Baize2` | 客户端创建任务时填写的 `taskProfileId`，租户初始化时自动为该 ID 建立 active 绑定。 |
| `version` | string / `"2"` | 不可变任务配置版本。切换 active 只影响新建 run。 |
| `kind` | string / `task` | 定义类型，固定为 `task`。 |
| `model` | object | 精确固定模型引用。 |
| `model.id` | string / `Baize2` | 引用同目录 `kind=model` 的 ID。 |
| `model.version` | string / `"2"` | 引用模型版本，必须与模型定义一致。 |
| `runtime` | object | 精确固定运行时引用。 |
| `runtime.id` | string / `resources` | 引用同目录 `kind=runtime` 的 ID。 |
| `runtime.version` | string / `"1"` | 引用运行时版本，必须与运行时定义一致。 |
| `skillReleases` | string[] / `[]` | 固定的技能 release 摘要列表。初始化模板强制为空；已有租户发布 task 时可引用已发布、ready 且 runtime 匹配的 release，传递依赖也须固定。 |
| `permissions` | string[] / 见下表 | task 请求的工具权限上限。实际权限还取决于服务端 `execution.permissions`、可信身份及平台/已固定 release 声明的交集。 |
| `requiredCapabilities` | string[] / `["resources","resource_decisions"]` | 任务要求的能力，必须由引用的模型和运行时能力并集覆盖；实际执行还检查 worker 健康及容量。 |
| `toolSetVersion` | string / `resource-tools-v1` | 工具集规则。当前模板使用资源工具；实现还支持 `file-tools-v1`，可信工具/沙箱分别使用 `broker-tools` / `sandbox-tools` 并要求 `trusted-python-v1` runtime 和配套部署。 |
| `contextPolicy` | object | 下列上下文构建、压缩和预算参数。 |
| `contextPolicy.version` | string / `context-v1` | 上下文组装策略，当前实现支持此值。 |
| `contextPolicy.budgetVersion` | string / `transport-bytes-v1` | Java 推理模型的传输预算版本，须与 model backend 对应。scripted 模型另使用 `full-json-bytes-v1`。 |
| `contextPolicy.outputReserve` | integer / `2048` | 输出预留，1～65536；纳入总预算，并作为上游 `max_tokens`，仍须满足真实后端限制。 |
| `contextPolicy.maxSourceChars` | integer / `4096` | 每段资源文本或工具结果初次提取的保留字符上限，0～32768；超预算时还可继续压缩。原任务、权限、调用参数和 schema 不按此值裁剪。 |
| `contextPolicy.compaction` | string / `extractive-v1` | 压缩方法，当前固定为此值；保存来源、原文摘要和保留内容摘要以支持审计。 |
| `contextPolicy.maxInputBytes` | integer / `48000` | 完整渲染输入的 UTF-8 字节上限，128～2097152；需同时满足模型预算和实际 worker/协议报文上限，调大本值不会扩大其他边界。 |
| `executionLimits` | object | 下列单个 run 的累计执行限额。 |
| `executionLimits.maxRounds` | integer / `8` | 模型决策轮数上限，1～64。 |
| `executionLimits.maxTools` | integer / `32` | 工具调用总数上限，0～128，包含内建资源工具；不是 Runner 并发数。 |
| `executionLimits.maxRepairs` | integer / `2` | 整个任务允许的决策格式修复总额，0～64；每轮还受 `generation.maxRepairs` 的 0～1 限制。 |
| `executionLimits.maxLifetimeMillis` | integer / `300000` | 任务最长生命周期，1000～86400000 毫秒，当前 5 分钟；包括交互等待。超时不构成物理推理/工具已停止的证据。 |

`permissions` 当前各项的用途：

| 权限 | 用途 |
| --- | --- |
| `skill.list` | 列出该任务固定且获准使用的技能。 |
| `skill.activate` | 激活固定技能，不扩张权限。 |
| `resource.list` | 列出获准读取的技能资源。 |
| `resource.read` | 分页读取获准资源。 |
| `resource.search` | 在获准资源中进行有界字面量检索。 |
| `input.read_text` | 读取本任务绑定的文本输入，需启用文件服务并获得服务端授权。 |
| `artifact.write_text` | 生成文本产物，需启用文件服务并获得服务端授权。 |

统一 `harness.properties.template` 的默认 `execution.permissions` 只包含五项技能/资源权限，因此 task 声明的两项文件权限不会自动生效。使用附件/产物时，同时启用 `files.enabled` 并按需增加文件权限。`skillReleases=[]` 的初始任务没有业务技能包可读。

## 调整与部署

1. 先确认 Java backend、Python model/runner 与目标模型能力，按需调整 model/runtime/task，保持引用及 adapter、预算版本一致。一个模型配置可由多个同角色 worker 服务，不需要为每个 worker 复制 profile。
2. 将本目录完整放入 Java 的 `execution.tenant.templates` 路径，重启时检查模板校验结果。不要将模型 token、worker credential、主机路径等私有连接信息写入 profile；这些信息配置在服务端私有配置中。
3. 使用有效 Cube token 查询 dispatcher 的 `/harness/v1/task-profiles`，新租户应看到 `resources-Baize2`；再检查 `/harness/v1/capabilities` 的 worker、后端容量和 `runsReady`。任务列表存在只表示配置已发布。
4. 已有租户的变更使用 `RuntimeAdmin profile` 先发布 model/runtime、再发布 task，最后以 CAS `bind task <taskId> <id@version> <expectedVersion>` 切换 active。

格式约束以源码中的 `resources/runtime/schemas/model-profile.json`、`runtime-profile.json`、`task-profile.json` 为准；加载由 `TenantTemplates`、运行时匹配由 `ProfileRegistry` / `ContextEngine` 执行。部署和管理命令见 [服务入口](../../../service-harness/README.md)、[运行时管理](../../../service-harness/RUNTIME.md)。
