# Harness 基础契约与协议兼容

基础 v1 契约继续用于注册和通信探针；执行能力使用独立版本化契约。业务接口和 Java SHM 传输见 [TRANSPORT.md](TRANSPORT.md)、[EXECUTION.md](EXECUTION.md)、[CONTROL-FILES.md](CONTROL-FILES.md) 和 [TRACE.md](TRACE.md)。service HTTP 只开放健康/运维，业务接口由 dispatcher 提供。

Java `scripts/generate_contracts.py` 是基础契约源；使用 `--python-contracts <Python工程>/harness/contracts` 同步镜像。各契约目录的 `manifest.json` 固定 JSON 的 SHA256，两端逐文件校验。各类契约只维护当前定义，生成后同步两仓镜像并运行两侧 fixtures 和真实 Cell 回归。基础 OpenAPI 与 execution/control-files/trace 增量契约共同描述接口；默认未启用执行时不创建 run、不占用幂等键。

## 身份和版本

- 服务名与 Cellet 路由目标均为 `Harness`；Python `HarnessService.NAME` 相同。
- 基础注册和 echo 使用 `protocolVersion=1`；模型支持 v2 scripted/v3 Java 推理，Runner 支持 tool / sandbox，各自严格校验对应契约。未知模型版本返回 `PROTOCOL_MISMATCH`，未知 Runner 类型返回 `RUNNER_TYPE_INVALID`，不作旧 Chat 协议协商。
- Python 节点可通过指定 IPv4 或主机名直接连接 Java Cell；健康/运维 HTTP 仍仅监听 loopback。API Bearer 使用已有 Cube 用户 token，service 经 Cube Auth 校验有效期和联系人绑定，以 domain/contactId 隔离数据。worker credential 为独立的至少 24 字符内部密钥，worker 还须匹配 Java 配置中的 workerId/role 白名单。不能从载荷取得 tenant、subject、roles 或 grants。
- 配置不区分 development/production，不要求 mode；旧 mode 值被忽略。API 主体来自已验证的 Cube token，工具权限受服务端策略与固定任务/资源权限交集约束；审批/trace 使用同域用户白名单。worker 按内部凭据和 workerId/role 白名单授权，业务身份不使用 Console Cookie。
- 无 model/runner 执行配置的单元只提供 scripted.echo；正式执行、授权和持久事件必须满足对应 profile、存储、能力与凭据配置。

## 原始 JSON 边界

JSON body 与 Python worker Cell STRING Stuff 都直接取原始 UTF-8 字节；先验证，再构造 JSON 对象。Python worker 协议每条原语仅允许一个 STRING Stuff，不使用 ActionDialect 或 JSON Stuff。Java dispatcher 业务协议单独使用 Packet/ActionDialect，其 JSON body 作为二进制参数保留至 service 严格解析。原始字节保留至处理完成，不记录 credential 或完整消息日志；不改 Cell 的全局解码器。

JSON 上限 262144 字节，嵌套深度 32；拒绝重复键、非有限数、注释、单引号、尾随内容、非法 UTF-8、控制字符及截断。schema 关闭未知字段；整数不接受 bool/字符串，版本与时间/计数不超过 JS 安全整数范围。`model-decision` 的 final/tool_calls/need_input 是互斥形状，不允许混合字段；tool_calls 非空。

摘要采用 SHA256 对明确字节计算，不对不同 JSON 排序/空格格式假定等价。Broker grant 使用下述固定规范化及签名规则，不能用 JSONObject.toString 代替签名规范。

## 注册状态机

`TCP connected → Cell handshake → authenticated register → registered → capability ready` 分别观测。model/runner 均注册到 Harness，使用不同 workerId。instanceId 每个 Python 进程随机生成；相同进程重连保留 instanceId。

register 必含 requestId、credential、workerId、instanceId、role、capabilities，不接受 sessionId/payload。成功返回随机 sessionId（租约 nonce）、leaseMillis、registered、ready。没有能力的 worker 可以 registered，但 ready=false。注册表最多 64 个 worker；空闲租约过期自动移除。配置的 worker 角色不能被客户端修改。

后续 heartbeat/echo/drain/unregister 必须携带相同身份、能力和 sessionId，并绑定同一个 Cell transport session。心跳在 leaseMillis/3 内进行。活跃 workerId 的另一实例或连接返回 DUPLICATE_WORKER；同一连接的同一 register 可重试并取得原 nonce。旧实例/过期租约/旧连接/旧 nonce 返回 STALE_SESSION；断开回调只删除该 transport 的记录，不删除新会话。

echo 仅为 CPU 通信探针，含 payload.text。drain 令 ready=false 并拒绝新 echo；unregister 删除记录。控制线程停止后不再发起注册或重连，尝试 drain→unregister→关闭连接和线程。链路不可用时直接完成本地停止，状态记录 cleanUnregister=false，服务端通过断开/租约收尾。

注册错误：INVALID_ENVELOPE、INVALID_JSON、SCHEMA_INVALID、PROTOCOL_MISMATCH、UNAUTHORIZED、FORBIDDEN、DUPLICATE_WORKER、STALE_SESSION、IDENTITY_MISMATCH、REGISTRY_FULL、WORKER_NOT_READY、HARNESS_NOT_READY、HARNESS_BUSY、INTERNAL_ERROR。错误响应也携带协议版本和 requestId；无法安全取得 requestId 时为空串。

## HTTP 和 run 语义

`GET /healthz` 仅证明 Harness HTTP 启动；Cell 自身有约 3 秒启动保护窗口。基础设施 readiness 检查 model/runner 注册和能力；任务 readiness 还受执行、profile、后端及接单状态约束。`capabilities` 报告实际 runsReady 和容量；`task-profiles` 返回已发布的 active task，未启用执行时为空。

用户 API 使用 `/harness/v1`，接单管理使用独立 service HTTP 的 `/harness-admin/v1/admission` 和专用管理凭据，见 [RESILIENCE.md](RESILIENCE.md)。API 验证身份、方法、路由、Content-Type、schema 和 Idempotency-Key；未启用执行返回 HARNESS_NOT_READY/503。错误结构为 errorCode/requestId/retryable。无效输入 400、未认证 401、无权限 403、未知路由 404、方法不支持 405、超限 413、媒体类型不支持 415。

RunStore 将幂等键绑定 tenant+subject+operation+目标+载荷摘要，同键同载荷返回原回执，异载荷冲突；控制意图 CAS 检查 expectedVersion。幂等记录当前不自动清理，保留至少 24 小时且不短于 run 生命周期。成功创建等待事务提交后返回 202；resume 的 waitId 与 approval 参数绑定见 [CONTROL-FILES.md](CONTROL-FILES.md)。

终态、eventSeq/runVersion、executionSettlement 由 Java RunStore 提交后权威发布，worker 不可直接推进。取消请求成功不等同于执行终止，unknown settlement 不释放执行配额。


## 普通工具 Runner 契约（tool）

基础探针使用 v1；role=runner/capability=tool.execute 的普通单元统一使用 tool，契约位于 `contracts/runner-tool/`，专用 sandbox 单元使用 `contracts/runner-sandbox/`。生成器为 `scripts/generate_runner_contracts.py --python-root <root>`。describe 绑定源码摘要、持久 runtimeId 和固定容量，其余动作绑定 transport/instance/session：heartbeat、poll、accepted、status、result、broker_invoke、cleanup_ack、drain、unregister。sandbox 单元使用 sandbox_prepare/sandbox_complete 完成授权和物理执行对账，不能调用普通工具。见 [TOOLS.md](TOOLS.md)。

jobHash 对不含 jobHash/grant 的完整 job 使用既有跨语言规范摘要；参数单独摘要。grant 的规范 bodyJson 使用 HMAC-SHA256，只有 Java 持有 key。resultHash 对完整原 report 求摘要，Java 即使将坏输出规范为 TOOL_OUTPUT_INVALID 也回执原 report 的摘要。ACK 只说明证据已持久保存；actualFinished 与 effectStatus 分别表示物理执行和业务效果。细节、权限和边界见 [TOOLS.md](TOOLS.md)。

## 当前定义与归并规则

`resources/runtime/schemas/` 按用途各保留一份定义：`package-manifest.json`（schemaVersion=6）、`model-profile.json`（3）、`runtime-profile.json`（2）、`task-profile.json`（2）及 `dependencies.json`（1）。同目录 `manifest.json` 是 SHA256 索引。

包内资源工具、普通 provider 工具和 sandbox 工具由互斥闭合分支校验；后两者分别声明 `runnerType: "tool"` / `runnerType: "sandbox"`。模型 profile 按 backend 区分 scripted 与 java-inference，运行时 profile 按 mode 区分 resources-only 与 trusted-python-v1。用途独立的基础探针、scripted 执行、Java 推理和专用 sandbox 协议保留各自当前定义。普通 Runner 统一使用 tool。

Runner 唯一生成入口是 `scripts/generate_runner_contracts.py --python-root <Python工程>`，直接生成普通和沙箱两类当前契约、源码摘要及运行时 schema 索引，不依赖历史 schema 文件。SQL 迁移是已有数据库的必要升级链，保留原摘要与执行顺序。

升级前 drain 旧单元并完成在途对账；旧清单/profile 按当前格式重新构建、审核和发布，再切换 active 绑定。不要修改既有不可变 release/profile 或删除运行 journal。新建 run 会再次校验固定定义，旧格式不能通过已有 catalog 绕过发布校验。
