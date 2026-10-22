# Harness dispatcher SHM 接入

业务链路为 `REST → cube.dispatcher.harness.HarnessHandler → Performer.syncTransmit → SHM → HarnessCellet → HarnessBusiness`。dispatcher 不使用 AIGC Manager，也不连接 service HTTP。service 的 HTTP 监听器只保留 `/healthz`、`/readyz` 和现有 `/harness-admin/v1/admission`；`/harness/v1/*` 在此监听器返回 404，无 HTTP 回退。

## 部署与身份

dispatcher 与 service-harness 必须配套升级 common、dispatcher 和 Harness jar。Java 业务传输独立于 Python worker 协议和数据库结构。worker 通信按协议版本、消息 schema 与能力声明校验，不记录源码或构建摘要作为部署版本。一个 JVM 继续承载多个同角色或不同角色 Python 单元，各单元独立 workerId、实例、运行目录与租约。

Harness REST 在 `dispatcher.properties` 设置 `harness.rest.enabled=true` 后随 dispatcher 启动挂载，无需独立的 `dispatcher-harness.properties` 或 `harness.rest.mode`。dispatcher 不保存或转换调用方凭据，也不配置 client/backend credential 或 backend HTTP URL。请求 Bearer 原样通过 SHM 交给 service；service 复用同一 Kernel 的 Cube Auth 模块查询 token，并检查有效期和联系人绑定。租户取 token.domain，用户取 token.contactId，不接受载荷自报身份；无效、过期或未绑定用户的 token 返回 401，Auth 不可用返回 503。普通任务、事件、反馈与文件按域和用户隔离；后台执行、恢复及工具 grant 使用账本中的任务归属。无需配置 `api.credential`、`execution.tenant` 或 `execution.subject`，旧值不参与认证。审批/trace 分别使用 `control.approver.subjects`、`trace.reader.subjects` 配置“域/联系人ID”白名单，调用时仍携带自己的 Cube token；额外授权只作用于同域审批/读取及 trace 导出，其他操作仍限定本人资源。用户登录 token 不下发给 Python worker，worker 继续使用独立内部凭据。无需设置环境 mode，旧 mode 值被忽略。Python 节点可通过指定 IPv4 或主机名直接连接 Java Cell，多节点配置见 [REMOTE-NODES.md](REMOTE-NODES.md)。

把 Harness 加入 dispatcher 的 `cellets` 和相应 `director.N.cellets`；`director.N.address/port` 指向 service Cell SHM 监听器（独立部署默认 7600），不是运维 HTTP 端口（默认 7088）。`Performer.selectDirectorForCellet("Harness")` 仅选择 scope 包含 Harness 且连接就绪的最高权重节点。正常复用 Cell 长连接；握手超时后重新建连，重连期间返回 503。已开始的文件传输不会切换节点或会话，需客户端重新发起。

业务客户端只配置 dispatcher HTTP 基地址；运维探针另配 service HTTP 地址；Console 地址独立。多个 Java 节点共享业务时仍须满足原数据库、协调器及共享文件根策略。

## 业务报文

共享动作在 `cube.common.action.HarnessAction`，名称为 `harness.<枚举名>`。REST 路径与动作一一对应，覆盖 profiles/capabilities、run/create/read/events/cancel/resume/approval、inputs、artifacts、feedback 与 trace。

请求采用 `Packet/ActionDialect`，带 `sn`、JSON `data` 和 Performer 添加的 `_performer`。data 包含资源 `id`、`approvalId`、幂等 `key`、`contentType`、原始 `query`、`lastEventId` 和原始 Bearer `credential`；角色由 service 认证后确定。JSON 正文放在独立二进制 `body` 参数，不能先解码再序列化；service 沿用严格 UTF-8、重复键/schema 检查及 262144 字节上限。身份、权限、幂等、版本、事务和状态码由 service 决定。

响应保留 `sn` 并复制 `_performer`，data 为 `{status,headers,body}`。校验失败、内部异常、业务队列满均使用同一关联格式；队列满返回 503/HARNESS_BUSY。dispatcher 对 JSON 应答设 8 MiB 上限，保留业务错误结构。Python 基础探针、模型及 tool/sandbox Runner 继续使用原来单 STRING 原语，不进入业务执行池。

## 事件与文件

events 每次同步取得有限页（limit 1～100），service 校验游标和事件缺号。dispatcher 输出 JSON 或有限 SSE：原 eventSeq 作为 id、原 type/data、`retry: 500`、`: replay-page-complete`。每个 gateway 最多两个 SSE 连接；非阻塞写出，2 秒写出期限；断线后仅从最后完整事件恢复。

文件字节不进入 JSON/ActionDialect。上传先同步准备会话，再用 `Performer.openStream(Director,"Harness",transferId)` 发送原生 SHM 流，最后同步 `TransferResult`；service 完成长度、摘要与文件入库后才返回上传成功。下载先验证权限/句柄并获得元数据、响应头与 transferId，dispatcher 注册接收器后同步 `StartDownload`，将 SHM 流写到 HTTP；保留媒体类型、文件名、长度及相对下载句柄语义。

一次传输的全部控制和字节流固定到选定 Director 及会话。transferId 绑定连接、身份、文件与截止时间；未知或跨会话流直接关闭。每个 gateway 与 service 传输层各最多两个活动文件传输；独立有界文件线程池不占 worker 心跳池。service 最多保留 64 条传输记录，到期清理；租约仍使用 `file.download.lease.seconds`，文件大小沿用默认 10 MiB、可配置至 64 MiB。超时、断连、客户端退出和停机关闭流并释放 pin/会话；HTTP 已提交后的错误中止连接。

## 验证

使用真实 JDK 8，先构建 common、dispatcher，再执行：

```powershell
ant -f service-harness/build.xml test-transport
python -B service-harness/scripts/shm_transport_smoke.py --java C:/jdk8/bin/java.exe --common-jar C:/isolated/build/cube-common-3.0.jar --cell-jar deploy/bin/cell.jar --libs deploy/libs --runtime C:/isolated/shm-smoke --max-file-bytes 67108864
```

smoke 使用独立目录、SQLite 和真实 Cell/Performer，不需模型硬件。覆盖所有已实现路由、凭据/权限、原始 JSON、幂等、并发关联、事件、文件/摘要/句柄、中断、重连与双会话隔离。`lifecycle_smoke.py`、`execution_smoke.py` 等脚本也使用真实 dispatcher fixture，业务地址经过 SHM，健康和运维仍直达 service；运行前必须构建 dispatcher。模型在线质量及目标环境 SLO 认证使用各自专项流程。

业务 REST 需加载实现 `cube.auth.AuthTokenProvider` 的 Cube Auth 模块，正式部署使用宿主现有 `AuthService`。common、service 与 Harness JAR 需配套重建。仅含 Harness 的 standalone Cell 保留 worker 和健康/运维能力，业务请求返回 `AUTH_UNAVAILABLE`；测试令牌模块仅位于测试 classpath，不打入发布包。

迁移现有私有配置时，移除 `api.credential`、固定的 `execution.tenant/subject` 以及旧审批/trace 共享凭据，按需填写新的用户白名单。旧固定开发身份下的任务和文件不会自动转归某个 Cube 用户；新用户使用的 TaskProfile 与资源须发布在其 token.domain 对应的域内。
