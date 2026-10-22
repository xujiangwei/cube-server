# Harness 资源循环与 Java 推理网关

启用 `store.enabled=true`、`execution.enabled=true` 后提供独立资源任务入口。Java 必须使用 JDK 8；独立数据库显式迁移到 schema7。用户身份从 Cube Auth 验证后的 token 取得，任务按 token 的域与联系人 ID 隔离，业务工具通过独立 SKILL/provider 发布与配置；可信 CPU MockConsole 工具需显式启用，见 [TOOLS.md](TOOLS.md)。

## 推理链路与边界

```text
用户 Prompt → Java Harness（受理、上下文、任务）
            → Python Harness（消息、编排）
            → Cell v3（OpenAI messages/tools/body）
            → Java InferenceGateway
                → 默认：同 JVM CompletionProvider / AIGCService → 注册 Baize/Baize2 Unit
                → 显式 type=openai：Java 外部 OpenAI HTTP 服务
            → Java 持久推理结果/usage → Python 决策处理 → Java 循环或收尾
```

Python Harness 不调用模型 HTTP、NInfer，也不加载 Transformers 权重。地址、模型服务密钥、超时及共享物理限额仅由 Java 配置。Unit 自身的原有模型实现继续负责硬件推理，Python 编排 worker 与模型 Unit 分别管理。每轮由服务端固定的 ModelProfile 选择模型；调用方不能在 HTTP 请求中指定任意模型或身份。

common `CompletionProvider` 是异步内部接口，AIGCService 复用 ChatCompletionService 的鉴权、Unit 能力检查、队列和用量记录，内部调用无需 dispatcher TalkContext。默认传输采用 OpenAI Chat Completions：单 choice、messages、tools、tool_calls、finish_reason、usage 及工具参数增量。不支持的字段/参数明确拒绝。Java 外部适配支持 JSON/SSE，检查 UTF-8、大小、结束标记和实际 usage，设置整体截止时间；HTTP 断开不作为物理结束证据。

| 默认模型 | adapter | 决策方式 |
| --- | --- | --- |
| Baize | openai-json-v2 | 严格 JSON；工具/schema 放在提示中，不发送原生 tools |
| Baize2 | openai-tools-v2 | 原生工具；函数别名、参数及配对历史校验 |

外部其他模型由 ModelProfile.nativeTools 声明能力，不能按名称猜测。Python 保留最多一次格式修复、严格决策/schema、工具授权校验与 journal。Java 保存推理结果和真实 usage，Python 提交处理后的决策；Java 重建审计和用量，忽略 Python 对物理结束及用量的自行断言。缺失 usage 不估算实际 token。

## 多单元、容量及执行证据

一个 JVM 可接多个 Python worker，包括同角色多个实例。每个实例独占 workerId、runtimeDir、实例身份、journal 和租约，模型 worker 当前每进程一个处理槽；CPU Runner 的独立容量配置见 TOOLS.md。Java 配置 worker 的 model/backend 白名单，按模型/profile、能力、健康与可用处理槽分配。模型 Unit 的健康、能力和忙闲另行检查。

共享同一物理引擎的所有模型/Unit/worker 必须使用同一 backendId 和可信 `backend.<id>.capacity`。数据库锁定 backend 行认领推理槽；先持久化 dispatched，再调用提供方。Python 处理期不占物理推理槽，实际推理期间占用；能力接口分别报告 processingSlots、processingAvailable、occupied、inferenceAvailable。Unit 忙时推理留在持久队列中，Cell 与心跳线程不等待推理。

Cell protocolVersion=3 增加 `inference_submit`、`inference_poll`、`inference_cancel`。job、原 worker 实例、wireAttempt、请求摘要绑定唯一执行；同一 attempt 重复提交返回已有记录，摘要不同返回冲突。增量事件按单调 cursor 分页，最终结果在事件追平后读取。此接口只在已有 Cell 内使用，未增加推理 HTTP 入口。

超时、取消、断连及现有 Completion.fail/release 只能结束观察，不能证明物理推理结束。无法确认时记录 unknown，保留物理容量，禁止自动重发新推理；JVM 重启后的发送窗口也按 unknown 隔离。`inference.node.id` 必须稳定且部署实例唯一。内部提供方保留 Unit 观察与队列占用，迟到有效 completed 仍结算一次。未派发的 queued 推理可以安全取消。

Python 重启可以回放同一原 attempt 读取 Java 证据；没有既存推理记录时，新实例不能代表旧实例发起推理。Python 结果先写 journal 再报告，ACK 丢失可重放。迟到证据由当前 Coordinator 的 fence 对账，已发出的 run final 不改写。无法确认的物理执行保持隔离；不能通过换 backendId 绕过占用。停止先 drain 并保留数据库/journal。

## 部署和迁移

1. 按 [STORE.md](STORE.md) 显式执行 `StoreMigrations upgrade` 到 schema7。004 迁移引入推理/事件表与 job.execution_protocol，后续迁移随包分发并保留旧行；MySQL 迁移账户另需专用库 ALTER 权限，运行账户仍只需 DML。
2. 将唯一的 `config/harness.properties.template` 复制为工作目录下的 `config/harness.properties`，在其中统一填写基础、执行与模型后端配置；Java 默认加载该文件，启动无需 `-Dharness.config`。Python 使用 `harness/config/model-baize*.template.json`。workerCredential 与 Java 后端模型 token 分别配置；业务调用携带已有 Cube 用户 token，无需 API 共享凭据。
3. 默认 Unit 后端要求 Harness 和 AIGCService 位于同一 Kernel/JVM。沿用宿主 ServiceCarpet 和 `config/cohost-cellet.xml`；独立 standalone.xml 只有 Harness，没有 AIGCService，默认推理保持未就绪。外部模型必须显式 type=openai，并设置 baseUrl、model、token、capacity、timeoutSeconds。
4. `ExecutionAdmin <new-directory>` 导出 ModelProfile schemaVersion=3/version=2 与 TaskProfile version=2。填写已 ready 的资源 release、权限与限额，显式 publish 后再切 active 绑定。清单和各类 profile 只接受当前格式；已发布旧格式需重新构建、审核并发布新版本，切换前先 drain 和完成在途对账。

`contracts/inference/` 在两仓逐字节镜像。openai-json-v2/openai-tools-v2 的规范只声明协议版本、adapter 和能力，不绑定 Python 源码或构建摘要，也不用于记录部署版本。模型 describe 直接校验 adapter/nativeTools/cancellation/budget 与消息 schema。`contracts/execution/` 仅保留 scripted 执行和共享 REST 定义；旧 Baize 直连 adapter 定义已删除。变更协议或能力规范时生成契约；不可变 profile 内容变化时发布新版本，单纯实现修改无需重新生成：

```powershell
python -B service-harness/scripts/generate_inference_contracts.py --python-root C:/Users/hermi/workspace/apexnova-wisdom
```

profile 使用 transport-bytes-v1 传输预算，不宣称知道远端 tokenizer/chat template；实际 usage 单独结算。offline-adapter 仅表示离线适配验证。

## HTTP 资源任务

Dispatcher 启动时挂载 `cube.dispatcher.harness` handler，直接使用 `Performer.syncTransmit` 经 SHM 长连接调用 HarnessCellet，需在 dispatcher.properties 设置 harness.rest.enabled=true，无需独立配置文件或 client/backend credential。请求 Bearer 原样交给 service 认证。业务地址统一使用 dispatcher；service HTTP 只保留健康/运维，console 地址另配。连接、身份及文件流配置见 [TRANSPORT.md](TRANSPORT.md)。

| 方法与路径 | 行为 |
| --- | --- |
| GET /harness/v1/capabilities | protocolVersion=3、worker 健康及独立处理/推理容量 |
| GET /harness/v1/task-profiles、/{id} | active task 摘要 |
| POST /harness/v1/runs | Harness Bearer、Idempotency-Key；事务受理后 202 |
| GET /harness/v1/runs/{id} | snapshot、usageSettlement、usage、result |
| GET /harness/v1/runs/{id}/events?after=0&limit=100 | 可回放事件，limit=1..100 |
| events 带 Accept: text/event-stream | 有限 SSE 页；按完整收到的 Last-Event-ID 重连 |

同键不同请求为 409，活跃/等待输入 run 满额为 429；未来 cursor 为 400，缺号为 409 EVENT_GAP。SSE 最多两个连接，每页 2 秒，空页遵守 retry=500ms。Cell 心跳、HTTP 和 Coordinator 使用独立池。

```json
{"taskProfileId":"resources-Baize2","input":{"instruction":"列出并阅读已授权的技能资源","attachmentIds":[]}}
```

Coordinator 以租约/fence/checkpoint 推进资源循环，资源工具只执行 ResourceTools 注册的读/激活能力，不执行模型生成的代码。可信工具和 sandbox 需另行显式启用。need_input 持久化等待问题与期限；resume/approval/cancel、文件服务与清理见 [CONTROL-FILES.md](CONTROL-FILES.md)。

## 验证与回退

JDK 8 下运行 `ant -f service-harness/build.xml test-inference test-execution test-runtime test-store`；Python 运行 `python -B -m unittest discover -s harness/tests -v`。ChatCompletionServiceTest、CompletionGatewayTest、CompletionHttpTest 和 ManagerRobustnessTest 回归原 Completion REST。

`inference_smoke.py` 启动真实 Cell、三个独立 Python v3 worker，经过同 JVM AIGCService 真实 Unit 队列，只有硬件回复由测试替身提供。参数见 `--help`，service-classes 指向编译后的 service 测试类；测试类不进入正式 jar。`execution_smoke.py` 继续验证 v2 scripted 资源循环。外部 HTTP JSON/SSE 使用本机测试服务。所有离线结果与真实 Baize/Baize2 GPU、权重、模板和工具质量验收分别记录。

上述测试使用模拟后端，真实模型、原生 Linux/远端 CI 与生产共同部署性能需单独验收。MySQL 迁移后使用仅 DML 运行账户。撤回先关新准入并 drain，保留兼容当前 schema7 的恢复程序、账本、journal 和 unknown 证据；不执行 destructive down 或旧系统 stop/clean。
