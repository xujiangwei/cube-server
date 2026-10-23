# Harness 协议与一致性约束

业务接口由 dispatcher 提供，经 [SHM 传输](TRANSPORT.md) 到达 service；Python worker 使用独立 Cell 协议。协议兼容由版本、严格消息 schema 和能力声明共同决定。`contracts/` 保存各用途的 OpenAPI/schema，manifest 中的 SHA256 用于本地文件完整性检查，不作为源码、构建或部署版本标识。

## 契约分工

| 定义位置 | 用途 |
| --- | --- |
| `contracts/` 基础定义 | 注册/探针、通用任务、事件、错误 |
| `contracts/execution/` | scripted 模型执行与共享业务接口 |
| `contracts/inference/` | v3 Java 推理请求、结果、adapter 能力规范 |
| `contracts/runner-tool/` | 普通可信工具 Runner |
| `contracts/runner-sandbox/` | 专用 sandbox Runner |
| `contracts/control-files/` | 用户输入、审批、取消、文件与下载句柄 |
| `contracts/trace/` | Trace 导出和反馈 |
| `resources/runtime/schemas/` | 包清单、服务器 model/runtime/execution、tenant-policy、依赖锁 |

Java/Python 部署使用匹配契约。schemaVersion 表示数据格式版本，protocolVersion 表示通信版本，runnerType 表示 Runner 类型，三者独立。模型配置无需填写 schemaVersion，始终按当前格式校验；兼容旧配置中的整数 3，不接受旧格式 1、2。模型功能由 backend、adapter 和能力声明决定。

## 身份与消息边界

服务名与 Cellet 路由目标均为 `Harness`。基础注册/echo 使用 protocolVersion=1；模型执行为 v2 scripted 或 v3 Java 推理；Runner 使用 protocolVersion=1 及 runnerType=tool/sandbox。未知 Runner 版本返回 `PROTOCOL_MISMATCH`，未知类型返回 `RUNNER_TYPE_INVALID`。

业务主体来自有效 Cube token，按 domain/contactId 隔离。worker 使用独立的至少 24 字符内部密钥，并匹配 Java 配置的 workerId/role 白名单；不能由载荷声明 tenant、subject、roles 或 grants。工具授权取固定配置与服务端策略交集，审批/trace 使用同域用户白名单。

Python worker 每条 Cell 原语只允许一个 STRING Stuff，直接验证原始 UTF-8 字节后构造 JSON，不使用 ActionDialect/JSON Stuff。Java dispatcher 业务协议使用独立 Packet/ActionDialect，并保留二进制 JSON 正文。凭据和完整消息不写入日志。

JSON 正文上限 262144 字节、嵌套深度 32；拒绝重复键、非有限数、注释、单引号、尾随内容、非法 UTF-8、控制字符及截断。schema 关闭未知字段，整数不接受 bool/字符串，版本及时间/计数不超过 JS 安全整数范围。model-decision 的 final/tool_calls/need_input 为互斥形状，tool_calls 必须非空。

普通摘要按明确字节计算 SHA256，不把不同 JSON 空白/排序视为相同字节。涉及 grant、jobHash、resultHash 的规范化规则由对应协议定义。

## 注册与租约

状态顺序为 `TCP connected → Cell handshake → authenticated register/describe → registered → capability ready`。注册成功不等于后端或节点已就绪。

基础 register 必含 requestId、credential、workerId、instanceId、role、capabilities，不接受 sessionId/payload。成功返回随机 sessionId、leaseMillis、registered、ready；无执行能力时可 registered=true、ready=false。注册表最多 64 个 worker，空闲租约过期自动移除。模型和 Runner 的执行注册使用各自 describe 消息。

每个 Python 进程使用独立 workerId 和随机 instanceId，同进程重连保留 instanceId。后续 heartbeat/echo/drain/unregister 绑定身份、能力、sessionId 及同一 Cell transport session；心跳在 leaseMillis/3 内进行。

活跃 workerId 的其他实例/连接返回 `DUPLICATE_WORKER`；同连接同 register 可重试并取得原 sessionId。旧实例、过期租约、旧连接或旧 sessionId 返回 `STALE_SESSION`。断开回调只移除对应 transport 的记录，不能删除新会话。

echo 仅为含 payload.text 的 CPU 通信探针。drain 令 ready=false、拒绝新作业；unregister 删除登记。停止顺序为 drain → unregister → 关闭连接/线程；链路不可用时完成本地停止，由服务端断开或租约收尾。

## HTTP 与事务

用户 API 前缀为 `/harness/v1`；接单管理位于独立 service HTTP 的 `/harness-admin/v1/admission`。`GET /healthz` 仅说明健康 HTTP 已启动；Cell 有约 3 秒启动保护窗口。任务 readiness 同时取决于执行开关、固定配置、Auth/后端、worker 能力、容量与接单状态。

API 校验身份、方法、路由、Content-Type、schema 和 Idempotency-Key。执行未启用时返回 `HARNESS_NOT_READY`，不创建 run 或占用创建幂等键。错误结构为 errorCode/requestId/retryable；常用 HTTP 状态为输入错误 400、未认证 401、无权限 403、未知路由 404、方法错误 405、冲突 409、超限 413、媒体类型错误 415、暂不可用 503。

幂等记录绑定 tenant+subject+operation+目标+载荷摘要，同键同载荷返回原回执，异载荷冲突。当前不自动清理幂等记录，保留不短于 run 生命周期和 24 小时。控制操作用 expectedVersion 做 CAS；resume/approval 还绑定 waitId 与参数摘要。

创建事务提交后才返回 202。run 终态、eventSeq/runVersion、executionSettlement 均由 Java RunStore 提交并权威发布。取消已受理不代表执行已终止，unknown 不释放未确认执行的容量；迟到证据不改写终态。

## 工具执行证据

Runner describe 声明 runtime、容量及持久 runtimeId。普通工具使用 heartbeat/poll/accepted/status/result/broker_invoke/cleanup_ack/drain/unregister；sandbox 另使用 sandbox_prepare/sandbox_complete 对账物理执行，不能调用普通工具。

jobHash 对不含 jobHash/grant 的完整 job 使用跨语言规范摘要，参数单独摘要。grant 的规范 bodyJson 使用 HMAC-SHA256，只有 Java 持有 key。resultHash 对原始完整 report 求摘要，即使 Java 将坏输出规范为 `TOOL_OUTPUT_INVALID`，仍 ACK 原 report 的摘要。ACK 表示证据已持久保存；actualFinished 和 effectStatus 分别表示物理执行结束与业务效果，两者不可互相替代。

当前运行时 schema 为 package-manifest=6、model-profile=3、runtime-profile=2、execution-profile=1、tenant-policy=1、dependencies=1。包内资源工具、普通 provider 工具与 sandbox 工具采用互斥封闭分支，分别要求对应 runtime、runnerType 和 服务器工具集。服务器字段见[执行配置](config/harness-execution/README.md)，业务字段见[租户策略](config/harness-tenant/README.md)，执行证据及授权流程见 [TOOLS.md](TOOLS.md)。
