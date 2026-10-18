# Harness v1 / G0 契约

本文保留 G0 历史契约；当前业务接口和 Java SHM 传输以 [TRANSPORT.md](TRANSPORT.md)、[EXECUTION.md](EXECUTION.md)、[CONTROL-FILES.md](CONTROL-FILES.md) 和 [TRACE.md](TRACE.md) 为准。当前 service HTTP 只开放健康/运维，业务接口由 dispatcher 提供。

Java `scripts/generate_contracts.py` 是契约源；使用 `--python-contracts <Python工程>/harness/contracts` 同步镜像。`manifest.json` 固定每份 JSON 的 SHA256，两端逐文件校验。修改契约必须重新生成两侧、运行两侧 fixtures 和真实 Cell 冒烟。OpenAPI 3.1 描述 G0 实际可调用的接口；尚未实现的业务操作只返回 503，不产生 run、不占用幂等键。N03 以后增加成功响应时必须再次版本化验收。

## 身份和版本

- 服务名与 Cellet 路由目标均为 `Harness`；Python `HarnessService.NAME` 相同。
- 当前 `protocolVersion=1`；未知版本返回 `PROTOCOL_MISMATCH`。不作旧 Chat 协议协商。
- G0 仅支持 loopback development 身份。API Bearer 和 worker credential 必须不同且至少 24 字符；worker 还须匹配 Java 配置中的 workerId/role 白名单。API 开发身份固定为 `development/local`，无管理权限；不能从载荷取得 tenant、subject、roles 或 grants。
- 生产 issuer/audience、过期和撤权源尚未接入。`mode` 非 development 时拒绝启动 Harness，不寻找旧登录态或 Console Cookie。上线前必须补独立身份源及验证。
- 正式的 model/tool 执行协议、授权分发和持久事件传输在 N06/N08/N09 完成；G0 只启用 scripted.echo，不宣称具备模型、Runner 执行或工具调度。

## 原始 JSON 边界

JSON body 与 Python worker Cell STRING Stuff 都直接取原始 UTF-8 字节；先验证，再构造 JSON 对象。Python worker 协议每条原语仅允许一个 STRING Stuff，不使用 ActionDialect 或 JSON Stuff。Java dispatcher 业务协议单独使用 Packet/ActionDialect，其 JSON body 作为二进制参数保留至 service 严格解析。原始字节保留至处理完成，不记录 credential 或完整消息日志；不改 Cell 的全局解码器。

JSON 上限 262144 字节，嵌套深度 32；拒绝重复键、非有限数、注释、单引号、尾随内容、非法 UTF-8、控制字符及截断。schema 关闭未知字段；整数不接受 bool/字符串，版本与时间/计数不超过 JS 安全整数范围。`model-decision` 的 final/tool_calls/need_input 是互斥形状，不允许混合字段；tool_calls 非空。

摘要采用 SHA256 对明确字节计算，不对不同 JSON 排序/空格格式假定等价。G0 不实现签名 grant；未来 Broker 的规范化和签名格式须单独冻结，不能用 JSONObject.toString 代替签名规范。

## 注册状态机

`TCP connected → Cell handshake → authenticated register → registered → capability ready` 分别观测。model/runner 均注册到 Harness，使用不同 workerId。instanceId 每个 Python 进程随机生成；相同进程重连保留 instanceId。

register 必含 requestId、credential、workerId、instanceId、role、capabilities，不接受 sessionId/payload。成功返回随机 sessionId（租约 nonce）、leaseMillis、registered、ready。没有能力的 worker 可以 registered，但 ready=false。注册表最多 64 个 worker；空闲租约过期自动移除。配置的 worker 角色不能被客户端修改。

后续 heartbeat/echo/drain/unregister 必须携带相同身份、能力和 sessionId，并绑定同一个 Cell transport session。心跳在 leaseMillis/3 内进行。活跃 workerId 的另一实例或连接返回 DUPLICATE_WORKER；同一连接的同一 register 可重试并取得原 nonce。旧实例/过期租约/旧连接/旧 nonce 返回 STALE_SESSION；断开回调只删除该 transport 的记录，不删除新会话。

echo 仅为 CPU 通信探针，含 payload.text。drain 令 ready=false 并拒绝新 echo；unregister 删除记录。控制线程停止后不再发起注册或重连，尝试 drain→unregister→关闭连接和线程。链路不可用时直接完成本地停止，状态记录 cleanUnregister=false，服务端通过断开/租约收尾。

注册错误：INVALID_ENVELOPE、INVALID_JSON、SCHEMA_INVALID、PROTOCOL_MISMATCH、UNAUTHORIZED、FORBIDDEN、DUPLICATE_WORKER、STALE_SESSION、IDENTITY_MISMATCH、REGISTRY_FULL、WORKER_NOT_READY、HARNESS_NOT_READY、HARNESS_BUSY、INTERNAL_ERROR。错误响应也携带协议版本和 requestId；无法安全取得 requestId 时为空串。

## HTTP 和 run 语义

`GET /healthz` 仅证明 Harness HTTP 启动；Cell 自身有约 3 秒启动保护窗口。`GET /readyz` 在 model/runner 两角色均注册且能力就绪时返回 200，否则 503；该就绪只覆盖基础设施。`GET /harness/v1/capabilities` 总是明确 runsReady=false；`task-profiles` 返回空列表。

用户 API 使用 `/harness/v1`，管理 API `/harness-admin/v1`；管理请求当前拒绝为 403。保留 API 验证身份、方法、路由、Content-Type、schema 和 Idempotency-Key 后，返回 HARNESS_NOT_READY/503。错误结构为 errorCode/requestId/retryable。无效输入 400、未认证 401、无权限 403、未知路由 404、方法不支持 405、超限 413、媒体类型不支持 415。没有虚假的 202 或内存 run。

N03 的内部 RunStore 已将幂等键绑定 tenant+subject+operation+目标+载荷摘要，同键同载荷返回原回执，异载荷冲突；控制意图 CAS 检查 expectedVersion。幂等记录当前不自动清理，保留至少 24 小时且不短于 run 生命周期。HTTP 业务入口仍返回 503；未来成功路径须等待创建事务提交才能返回 202。resume 的 waitId、approval 参数绑定及完整业务策略继续在 N11 验收，不将存储方法当作已开放的控制 API。

终态、eventSeq/runVersion、executionSettlement 由 Java RunStore 提交后权威发布，worker 不可直接推进。取消请求成功不等同于执行终止，unknown settlement 不释放执行配额。相关状态 schema 已作为规划契约保留，实际流转从 N03 开始。


## N08/N09 增量协议（Cell v4）

G0 v1 规则作为历史兼容保留。v2 scripted 与 v3 Java 推理协议之后，新增 role=runner/capability=tool.execute 的 v4；源和 SHA256 manifest 在 `contracts/runner/`，生成器为 `scripts/generate_runner_contracts.py --python-root <root>`。旧契约文件不重生成。v4 describe 绑定 runner 源码摘要、持久 runtimeId 和固定容量，其余动作绑定 transport/instance/session：heartbeat、poll、accepted、status、result、broker_query、drain、unregister。

jobHash 对不含 jobHash/grant 的完整 job 使用既有跨语言规范摘要；参数单独摘要。grant 的规范 bodyJson 使用 HMAC-SHA256，只有 Java 持有 key。resultHash 对完整原 report 求摘要，Java 即使将坏输出规范为 TOOL_OUTPUT_INVALID 也回执原 report 的摘要。ACK 只说明证据已持久保存；actualFinished 与 effectStatus 分别表示物理执行和业务效果。细节、权限和边界见 [TOOLS.md](TOOLS.md)。
