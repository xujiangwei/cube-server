# N06/N07 模型单元与资源循环

本实现仅开放独立 development 入口，需同时设置 `store.enabled=true` 和 `execution.enabled=true`。默认入口保持 G0 行为。使用 JDK 8、显式 schema v3 迁移，以及专用 Harness 数据库和资源目录。生产身份提供方、业务执行器、审批/恢复/取消 HTTP API 留在后续阶段。

## 模型与认证范围

默认模型名为 **Baize**、**Baize2**，不是底层权重名。能力依据 Python 工程 `cube/unit_baize.py`、`cube/unit_baize_2.py` 和 `docs/rest-api/src/Compatibility.md`：

| 模型 | 新适配器 | 原后端 | 决策方式 |
| --- | --- | --- | --- |
| Baize | `baize-json-v1` | Qwen3-14B-AWQ / Transformers | 完整多轮消息、工具/schema 作为提示数据，严格 JSON 决策；不发送原生 tools |
| Baize2 | `baize2-tools-v1` | qwen3.8-27b / NInfer | 完整消息和原生工具 schema；可逆函数别名和严格参数校验 |

两者默认通过兼容 API 访问**显式配置的**后端，支持 OpenAI JSON/SSE 和 Claude JSON/SSE。Baize2 也支持 `transport=ninfer`，默认底层名 `qwen3.8-27b`。Baize 的 `transport=transformers` 仅按显式本地路径懒加载，所有 `from_pretrained` 使用 `local_files_only=True`、`trust_remote_code=False`；模型库由专用环境另行准备。本次未加载或下载权重，没有修改旧 Unit、能力声明或模型池。

`ModelProfiles` 生成默认 v2 ModelProfile，`ExecutionAdmin <new-directory>` 导出 Baize、Baize2、resources RuntimeProfile 和两个 TaskProfile。先审阅资源 release、权限和限额，再用 `RuntimeAdmin profile/bind` 发布；不会在服务启动时自动发布配置。默认 TaskProfile 不含 release，读取包前应填写已上传、ready 的 release 摘要。v1 资源、模型和任务 schema 保留原字节。

adapter profile 摘要绑定实际 Python adapter/worker 源文件、适配版本、预算和取消语义；Java jar 与 Python 镜像包含同一份 `contracts/execution/adapter-profiles.json`，启动校验镜像和源文件摘要。修改 adapter 后需重新生成契约并发布**新的 profile 版本**，不能用原版本覆盖在途配置：

```powershell
python -B service-harness/scripts/generate_execution_contracts.py --python-root C:/Users/hermi/workspace/apexnova-wisdom
```

远程 API 未暴露可独立核验的 tokenizer/chat template，因此 v2 profile 明确使用 `transport-bytes-v1` **传输字节预算**，不把字节数当成模型实测 token。上下文包含完整消息、工具和决策 schema；输出预留及 wire byte 上限单独检查。实际 usage 来自模型响应。本地 Transformers 在 generate 前额外使用实际 tokenizer 检查输入加输出上限。导出 profile 的 `certification=offline-adapter` 只表示离线适配器验证，不能用于宣称真实模型工具质量、权重身份、专用部署容量或物理取消已认证。

输出拒绝重复键、非有限数、未知/未授权工具、错误参数、重复 proposalId、截断、refusal、错误 finish reason 和缺失流结束。原生调用 ID 转为每轮稳定且唯一的 proposalId，下一轮包含配对的 call/result。最多一次格式修复，且受 run 总修复限额控制；每次推理前记录完整 wire body 和摘要，响应摘要、finish reason、usage 和修复原因进入审计。缺失 usage 保持 provisional，不伪造实际 token。

## 多单元、容量与恢复

每个 Python 单元有独立 workerId、运行目录、实例 UUID、文件锁、SQLite journal 和 Cell 租约。一个 JVM 可以同时接入多个同角色单元。服务端白名单配置 worker 的模型/后端身份；`backend.<id>.capacity` 是可信容量来源，不能由单元自行扩容。当前每个 Python 单元最多执行一个 round。

多个单元连接同一物理后端时必须使用**相同 backendId**。数据库锁定 backend 行、核对活动 job 后原子认领；多个 JVM 也使用这份容量账本。能力接口分别报告注册单元数、后端容量、占用、当前可执行并发与可用数量。健康、未 drain 的单元才会获得新任务。任务队列和活跃/等待输入的 run 数有上限，满额返回 429；相同幂等键的合法重放仍可返回原 202。

Cell protocolVersion=2 的 `describe` 注册固定描述；`poll` 返回 round job 和 cancel 观察指令；`actual_finished` 提交已落盘的结果，`status/heartbeat/drain/unregister` 独立于推理线程。job 绑定 run、round、attempt、originFence、intentHash、workerId、实例和原 session。重连签发新 nonce；同实例可对原任务对账，重启后的单元只能报告 journal 中绑定原实例的证据，不能重新执行不明任务。

Python 先持久化 accepted，再持久化每次 wire intent，最后发送请求；结果先持久化再报告。断连重发不增加 attempt。JVM 在提交 round intent 后崩溃，当前 owner 可从原 intent 重建尚未入队的 job。执行中 Python 进程崩溃、HTTP 超时或流异常不能证明后端停止，任务记 unknown，保留后端占用并将单元置为 unhealthy，**不自动重试或释放槽**。

兼容 API 和当前本地 generate 取消能力均为 `observe-only`。逻辑超时结束 run、记录 cancel 请求，推理仍占槽直到实际响应结束；未派发任务可在数据库内安全终结。迟到结果只保存执行证据，当前 owner 以新 fence 对账，final 不改写，实际 usage 可迟到结算。只有证据确认结束才释放物理容量。无法确认的任务需运维在专用后端核对后提供真实证据；新实例/新 backendId 不能用于绕过同一物理后端的旧占用。

停止先 drain，等待当前请求真实收尾，再报告和注销。`stop` 的 15 秒等待窗口可能不足以等待推理；此时命令报超时并保留进程，不按进程名强杀，不假称推理已结束。网络断开时结果保留在 journal。

## 开启与调用

配置示例见 `config/execution.properties.template`、`config/dispatcher-harness.properties.template`，Python 对应 `harness/config/model-baize*.template.json`。凭据只放私有配置；服务固定绑定一个受信任的 development tenant/subject，客户端不能提交身份或权限。当前 development 入口不能作为多租户生产网关使用。

1. 按 `STORE.md` 初始化/升级专用库到 schema v3，普通运行账户只需 DML 权限。
2. 创建独立资源目录；配置与 `RuntimeAdmin` 相同的 repository namespace，导出并发布不可变 model/runtime/task profiles、ready release 和 task active 绑定。
3. 显式启用 execution，配置多个 worker 白名单及真实 backendId/容量。
4. 按 Python 独立启动入口启动对应 model 配置。无须为资源循环启动 runner。

API 使用新 Harness Bearer，操作只涉及新 run：

| 方法与路径 | 行为 |
| --- | --- |
| `GET /harness/v1/capabilities` | protocolVersion=2、单元描述、健康和独立容量统计 |
| `GET /harness/v1/task-profiles`、`/{id}` | 当前 tenant 的 active task 摘要 |
| `POST /harness/v1/runs` | `Idempotency-Key` 必填；原 create-run schema；事务成功后 202，响应原 run-snapshot |
| `GET /harness/v1/runs/{id}` | `{snapshot, usageSettlement, usage, result}`，结果未产生时 result=null；不会暴露 owner/租约内部字段 |
| `GET /harness/v1/runs/{id}/events?after=0&limit=100` | `{events,nextCursor}`；limit=1..100；未来 cursor 返回 400，任意缺号返回 409 EVENT_GAP |
| 同一 events 路径，`Accept: text/event-stream` | 有限 replay 页，最多 100 个完整事件；用 Last-Event-ID 重连，after 与 header 同时指定必须一致 |

启用执行后的 API 契约是对 G0 保留入口的新增成功分支；v1 原始 schema/fixture 不重写，新增执行契约单独放在 `contracts/execution/`。SSE 每页结束后客户端按最后**已收到的完整事件**重连；空页遵守 retry=500ms。HTTP 使用本模块专有 Jetty 线程池，SSE 为 Servlet 3.1 非阻塞输出，最多两个流，每流 2 秒，慢消费者断开后可以回放。JSON API、Cell 心跳和 Coordinator 分属独立池。

```json
{"taskProfileId":"resources-Baize2","input":{"instruction":"列出并阅读已授权的技能资源","attachmentIds":[]}}
```

Coordinator 使用唯一租约/fence、短事务和 checkpoint 推进；API 线程不执行推理。只执行 ResourceTools 注册的资源读取/激活能力，工具参数和输出均校验；激活事务再次验证 owner。任何未决 round 先对账，不盲目重放；资源读取/幂等激活可以从 call intent 恢复。`need_input` 持久化后释放模型槽并停止生成，寿命上限仍生效；HTTP resume/cancel 等控制在 N11 开放。

Dispatcher 仅在显式 `harness.rest.enabled=true` 时注册 `/harness`，代码集中在 `dispatcher/src/cube/dispatcher/harness/`。唯一旧文件接入是 Performer 的可选安装调用。独立 client 凭据与 backend 凭据必须不同，后端固定为 loopback Harness；不使用旧 AIGC、Chat、Completion 或 Console 凭据。代理限定路径、请求体、并发、等待队列、响应页和超时；SSE 使用非阻塞输出。默认开关为 false。

## 验证与撤回

`ant -f service-harness/build.xml test-execution` 执行真实 SQLite 测试；`ExecutionTest` 可用 `-Dexecution.test.config=<fresh-disposable-migrator.properties>` 在独立 MySQL 测试。`n06_n07_smoke.py` 启动真实 JVM Cell 和多个独立 Python 进程，覆盖资源循环、共享容量、背压、drain、重连、接管、SSE/慢消费者和限额。Python `unittest discover -s harness/tests -v` 覆盖两个 adapter、OpenAI/Claude 流、真实 loopback HTTP、修复、journal 重启和取消观察。无需下载模型或访问线上接口。

MySQL 可同时指定 `-Dexecution.test.runtime=<runtime.properties>`，使迁移后所有执行测试使用仅 DML 的运行账户。独立 CI 模板位于 `ci/harness.yml.template`；工作区现有 `.gitignore` 明确忽略 Harness workflows，本轮保留该规则，不强行安装或提交 workflow。原生 Linux/远端 CI 未执行。

关闭新准入，drain 新单元，保留数据库、journal、资源和未决执行证据，再关闭 execution/dispatcher 开关。迁移为显式加表，不运行 destructive down 或旧系统 stop/clean。N02 原生 Linux 验收仍待执行，不能据此宣布 G0/G1 最终晋级；真实 Baize/Baize2 专用后端在线认证另验。
