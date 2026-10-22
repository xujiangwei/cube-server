# Harness REST 与 Cell SHM 传输

业务链路为 `REST → cube.dispatcher.harness.HarnessHandler → Performer.syncTransmit → SHM → HarnessCellet → HarnessBusiness`。dispatcher 通过 Cell 长连接访问 Harness；service HTTP 只开放 `/healthz`、`/readyz` 和 `/harness-admin/v1/admission`，业务 `/harness/v1/*` 在该监听器返回 404。

## 地址与部署

| 入口 | 配置与用途 |
| --- | --- |
| dispatcher HTTP | 客户端使用的业务 REST 基地址，在 `dispatcher.properties` 配置 |
| service Cell | dispatcher Director 和 Python worker 的连接目标，在实际加载的 Cell XML 配置；独立模板默认 7600 |
| service HTTP | loopback 健康/运维地址，在 `harness.properties` 配置，默认 7088 |
| Console | 业务技能 provider 使用的独立地址及凭据，按该技能配置 |

部署配套 common、dispatcher 和 Harness JAR。在 `dispatcher.properties` 设置 `harness.rest.enabled=true`，将 `Harness` 加入 `cellets` 和对应的 `director.N.cellets`；`director.N.address/port` 指向 service Cell，不能指向健康 HTTP。

`Performer.selectDirectorForCellet("Harness")` 选择 scope 包含 Harness、连接就绪的最高权重节点。复用 Cell 长连接，握手超时后重新建连；不可用时返回 503。跨机器和多 Python 单元配置见 [REMOTE-NODES.md](REMOTE-NODES.md)。多个 Java 节点共享任务时，需满足账本协调与文件根共享规则。

## 认证与权限

请求的 Bearer 原样经 SHM 交给 service；dispatcher 不保存或转换调用方凭据，无需配置 client/backend credential。service 查询同 Kernel 的 `cube.auth.AuthTokenProvider`，验证令牌有效期及联系人绑定。tenant=`token.domain`，subject=`token.contactId`；不从请求或 worker 载荷读取身份。

无效、过期或未绑定用户的 token 返回 401，Auth 不可用返回 503 `AUTH_UNAVAILABLE`。正式业务部署使用宿主 AuthService；仅有 Harness 的独立 Cell 缺少此认证源。普通任务、事件、反馈和文件按域及用户隔离，后台恢复和工具授权使用账本中的任务归属。用户 token 不下发给 Python。

`control.approver.subjects`、`trace.reader.subjects` 分别配置“域/联系人ID”白名单。用户仍使用自己的 Cube token，只获得同域审批/读取或 trace 导出权限；其他普通操作限定本人资源。worker 使用独立内部密钥及 workerId/role 白名单，接单管理使用独立管理密钥，Console 技能按其业务认证配置。

## 控制报文

共享动作为 `cube.common.action.HarnessAction`，线上名称为 `harness.<枚举名>`，覆盖任务、配置、事件、控制、文件、反馈和 trace。

请求采用 Packet/ActionDialect，携带 `sn`、JSON `data` 和 Performer 添加的 `_performer`。data 保存路由 ID、幂等 key、Content-Type、原始 query、lastEventId 和 Bearer credential；原始 JSON 正文放入独立二进制 `body` 参数。service 直接对原始 UTF-8 字节执行重复键、schema 和 262144 字节上限校验。

响应保留 `sn` 和 `_performer`，data 为 `{status,headers,body}`。错误、异常及队列满使用相同关联格式；队列满返回 503 `HARNESS_BUSY`。dispatcher 的 JSON 应答上限为 8 MiB。Python worker 的单 STRING 原语与业务 ActionDialect 分开处理。

## 事件流

service 每次返回有限事件页，limit 为 1～100，并验证游标及连续性。dispatcher 输出 JSON 或有限 SSE 页：eventSeq 作为 id，保留 type/data，带 `retry: 500` 和 `: replay-page-complete`。

每个 gateway 最多两个 SSE 连接，非阻塞写出，期限 2 秒。客户端按最后完整事件续读，收到页结束后继续请求；连接关闭不代表 run 已结束。

## 文件流

文件字节通过原生 SHM 流传输，不嵌入 JSON/ActionDialect。

1. 上传先同步准备会话，再用 `Performer.openStream(Director,"Harness",transferId)` 发送字节，最后同步取得 TransferResult。service 校验长度、摘要并入库后，dispatcher 才报告上传成功。
2. 下载先验证权限和句柄，取得元数据、响应头及 transferId；dispatcher 注册接收器，再同步 StartDownload，将 SHM 流转发到 HTTP。保留媒体类型、文件名、长度和相对句柄语义。

一次传输固定到选定 Director 和会话，不中途切换节点。transferId 绑定连接、身份、文件及截止时间；未知或跨会话流直接关闭。每个 gateway 和 service 传输层各最多两个活动文件传输，使用独立有界文件线程池；service 最多保留 64 条传输记录。

下载租约使用 `file.download.lease.seconds`；单文件默认 10 MiB，可配置至 64 MiB。超时、断连、客户端退出或停机时关闭流并释放 pin/会话；HTTP 头已提交后的错误中止连接。客户端需要重新发起被中断的传输。文件接口及生命周期见 [CONTROL-FILES.md](CONTROL-FILES.md)。
