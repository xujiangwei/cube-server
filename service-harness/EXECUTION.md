# Harness 模型推理与任务执行

启用 `store.enabled=true`、`execution.enabled=true` 后，Harness 提供资源任务和 Java 推理网关。用户身份来自 Cube Auth 验证后的 token；每个 run 固定模型、运行时、权限和技能 release，执行状态以 Java 任务账本为准。

## 推理数据流

```text
客户端 → dispatcher REST → Cell SHM → Harness
  → 事务创建 run / 固定配置 / 组装上下文 / 保存 round intent
  → Python model worker 适配 messages、tools 和决策格式
  → Cell v3 → Java InferenceGateway
      → type=unit：同 JVM CompletionProvider / AIGCService → 模型 Unit
      → type=openai：Java 调用配置的 OpenAI 兼容 HTTP 服务
  → Java 保存推理结果、增量事件和实际 usage
  → Python 校验决策并写 journal → Java 提交轮次结果
  → 执行工具、等待输入/审批，或提交最终结果
```

Python model worker 不直接连接模型 HTTP、不加载模型权重；模型地址、密钥、超时和物理容量均由 Java 配置。Unit 负责实际硬件推理，和 Python 编排 worker 分开管理。每轮模型由已固定的 ModelProfile 决定。

默认使用 OpenAI Chat Completions，支持单 choice、messages、tools/tool_calls、finish_reason、usage 和工具参数增量。Java 外部适配支持 JSON/SSE，校验 UTF-8、消息大小、结束标记及 usage；不支持的参数明确拒绝。实际 usage 由 Java 推理账本结算，缺失时不推算实际 token。

| 模型定义 | adapter | 决策方式 |
| --- | --- | --- |
| Baize | `openai-json-v2` | 工具/schema 放入提示，模型输出严格 JSON |
| Baize2 | `openai-tools-v2` | 使用原生工具，校验函数别名、参数及配对历史 |

其他模型按 ModelProfile 的 adapter/nativeTools 和后端实际能力匹配。格式修复同时受模型单轮上限与 task 累计修复预算限制。`transport-bytes-v1` 是传输字节预算，实际模型 token 用量另行记录；`offline-adapter` 表示离线适配验证。

## 部署配置

1. 按 [STORE.md](STORE.md) 配置专用数据库并启用存储、执行。服务启动自动检查并建立当前结构。
2. 完整部署 `config/harness-tenant/`，设置 `execution.tenant.templates=./config/harness-tenant`。启动校验并固定模板，新租户首次合法访问时原子初始化 profiles 和 active task。字段、引用及变更生效规则见[租户配置文档](config/harness-tenant/README.md)。
3. 逐个配置 Java `worker.<id>.role/model/backend` 及 `backend.<id>.*`，Python 使用配套 `harness/config/model-baize*.template.json`；workerCredential 与 Java worker 密钥一致。
4. 默认 `type=unit` 要求 Harness 与 AIGCService 同 Kernel/JVM，并有对应模型 Unit。独立 Cell 不具备默认 Unit 推理能力。外部模型须显式配置 `type=openai`、`baseUrl`、`model`、`token`、`capacity`、`timeoutSeconds`；字段示例见[主配置模板](config/harness.properties.template)。
5. 已有租户使用 `RuntimeAdmin` 发布新版本并切换 active。`ExecutionAdmin <新目录>` 可导出 Baize/Baize2 默认定义，供模板维护或人工发布；不写数据库。

新租户模板的 `skillReleases` 必须为空。技能包、业务 provider 和可执行工具需要单独安装、发布及授权，见 [RUNTIME.md](RUNTIME.md)、[TOOLS.md](TOOLS.md)。开发环境经同 JVM AIGCService 的 [Relay](../service/RELAY.md) 联调远端 Cube 时，仍按远端实际能力和共享容量判断就绪。

## 多单元与容量

一个 JVM 可连接多个 model/runner，同角色可有多个单元。每个 Python 进程独占 workerId、runtimeDir、实例身份、journal 和租约。模型 worker 每进程一个处理槽，Java 按模型/profile、能力、健康、未 drain 状态和可用处理槽分配；Runner 容量见 [TOOLS.md](TOOLS.md)。

共享同一物理引擎的模型、Unit 和 worker 使用相同 backendId 与可信 `backend.<id>.capacity`。Java 通过数据库 backend 行锁认领物理推理槽，先持久化 dispatched 再调用提供方。Python 处理阶段与物理推理分别计容；capabilities 报告 processingSlots、processingAvailable、occupied、inferenceAvailable。Unit 忙时留在持久队列，不阻塞 Cell 和心跳线程。

## 任务循环与恢复

Coordinator 使用数据库租约、fence 和 checkpoint 推进 run：持久化 round intent → 获取模型结果 → 校验 final/tool_calls/need_input → 写入工具 intent 或等待状态 → 提交结果。资源工具由 Java `ResourceTools` 执行；工具 Runner、文件与 sandbox 按配置启用。等待、审批、取消见 [CONTROL-FILES.md](CONTROL-FILES.md)。

round intent 提交后在同一步立即入队模型作业，不等待下一次扫描；两次提交之间崩溃仍由历史 intent 恢复。模型完成后，同一步确认轮次、结算用量并处理最终答案；final 不再重建没有后续推理用途的上下文或 checkpoint。内部只取状态/版本时不查询完整结果和用量汇总，所有修改仍在各自事务内校验 owner、fence 和 version。

run 租约为十秒；正在执行的步骤由独立线程每两秒续租，覆盖远程数据库访问、上下文组装和工具执行期间的等待。续租仍验证数据库时间、owner 和 fence，已过期或被其他 JVM 接管的租约不能复活。未执行步骤时不持续续租；扫描推进失败会记录限频的 runId/errorCode 诊断日志。此租约独立于 Python worker 的 `lease.millis`。

Cell v3 的 `inference_submit/poll/cancel` 绑定 job、原 worker 实例、wireAttempt 和请求摘要。同 attempt 同摘要复用已有记录，异摘要冲突；增量事件按 cursor 分页，追平后读取最终结果。此通道不提供独立推理 HTTP 接口。

超时、取消、断连或 Completion.fail/release 不能证明物理推理结束。无法确认时记录 unknown，保留物理容量并禁止自动发起替代推理。Java 重启时未确认的发送窗口同样隔离；`inference.node.id` 必须部署实例唯一且重启后稳定。未派发的 queued 推理可以安全取消。

Python 先写 journal 再报告，ACK 丢失可重送同一结果；重启后只能回放原 attempt 的既有 Java 证据，新实例不能代表旧实例新建推理。当前 Coordinator 校验原 intent/hash/fence 后对账，迟到证据结算一次，已提交的 run final 不改写。保留账本及原 journal，直至执行和用量结算。

## 业务接口

下列路径均以 dispatcher 的 `/harness/v1` 为前缀，使用用户自己的 Cube Bearer token。

| 方法与路径 | 行为 |
| --- | --- |
| `GET /capabilities` | worker 健康、处理/推理容量及 runsReady |
| `GET /task-profiles`、`/task-profiles/{id}` | 已发布的 active task 摘要 |
| `POST /runs` | 带 `Idempotency-Key`，事务提交后返回 202 |
| `GET /runs/{id}` | 状态、执行与用量结算、结果 |
| `GET /runs/{id}/events?after=0&limit=100` | 可回放事件，limit 为 1～100 |
| events 带 `Accept: text/event-stream` | 有限 SSE 页，按最后完整事件的 Last-Event-ID 续读 |

创建示例：

```json
{"taskProfileId":"resources-Baize2","input":{"instruction":"请简短介绍自己","attachmentIds":[]}}
```

同键异请求为 409，活跃/等待任务满额为 429，未来事件游标为 400，事件缺号为 409 `EVENT_GAP`。业务传输与认证见 [TRANSPORT.md](TRANSPORT.md)；完整 schema 位于 `contracts/` 和 [REST 文档](../docs/rest-api/src/harness/README.md)。
