# Harness 任务账本

`cube.service.harness.store` 是独立 JDBC 账本，兼容实际 JDK 8。SQLite 用于单实例开发；MySQL 使用专用数据库和具备本库建表权限的服务账号。任务账本涵盖任务执行、工具调度、交互与文件、轨迹与反馈。

同一 Harness 部署下，各租户共用一套数据库结构和同一组表；租户数据按 `tenant` 隔离，任务关联记录通过 `run_id` 归属任务。合法租户首次访问时自动初始化配置与绑定，不需要创建数据库或执行 Java 命令。

当前结构定义随 JAR 分发，SQLite 和 MySQL 分别使用 `resources/store/sqlite.sql`、`resources/store/mysql.sql`，服务启动时按当前结构自动初始化。

## 配置与启动建表

默认 `store.enabled=false`。不回退到其他模块的数据库配置或环境凭据，不修改旧表。设置为 true 后，HarnessService 在接受请求前连接数据库，自动检查并补齐必要表、索引和可安全补建的列。数据库身份、当前结构摘要及已有结构须匹配；失败时仅停止 Harness 自身启动。doctor 和存储校验 API 只读检查，不执行 DDL。

SQLite 私有配置示例（路径可为相对服务工作目录的路径；缺失目录在加载配置时创建）：

```properties
store.enabled=true
store.dialect=sqlite
store.database=harness_development
store.identity=replace-with-private-random-store-identity
store.sqlite.root=C:/private/harness-store
store.sqlite.file=C:/private/harness-store/harness_development.db
```

文件名必须等于 `store.database + .db`，真实父目录必须位于指定 root 内，拒绝数据库文件符号链接。SQLite 使用独立连接、BEGIN IMMEDIATE、foreign_keys、busy_timeout 和 synchronous=FULL；部署范围为单实例开发。

MySQL 由数据库管理员一次性创建专用数据库并配置可访问它的账号。数据库名必须以 `harness_` 开头，只允许小写字母、数字和下划线；账户名不限制前缀或格式，用户名与密码均不能为空。`store.identity` 是部署级数据库身份，首次初始化自动写入库，之后继续匹配校验。服务需要在本库执行 SELECT/INSERT/UPDATE/DELETE/CREATE/INDEX/REFERENCES/ALTER。Harness 不查询 `SHOW GRANTS`，也不因账号具备全局、跨库或角色授权而拒绝连接；实际操作权限由 MySQL 校验。账号配置在同一个 `harness.properties` 中，由服务完成建表和结构自检。

```properties
store.enabled=true
store.dialect=mysql
store.database=harness_dedicated
store.identity=replace-with-private-random-store-identity
store.mysql.host=db.example.internal
store.mysql.port=3306
store.mysql.user=harness_runtime
store.mysql.password=replace-in-private-properties-only
```

MySQL 统一使用 `common` 的 `MySQLStorage` 连接池，连接参数与其他服务模块一致（当前为 `useSSL=false`、`allowPublicKeyRetrieval=true`），Harness 不再单独拼装 JDBC URL 或强制 `VERIFY_IDENTITY`，无需配置 `store.mysql.loopbackDevelopment`。每次操作借用池内连接，在同一连接上执行账本事务，关闭逻辑连接时回滚未完成的事务、恢复连接状态并归还池；清理失败的连接直接丢弃。服务停止或启动失败时关闭当前实例的连接池。凭据仍保存在实例私有配置中，不交给 Python worker。

Harness 专用池以 READ COMMITTED、手动提交为默认状态，借还连接时保留该默认值，避免每个短事务重复切换隔离级别和自动提交。JDBC 驱动使用本地会话/事务状态跳过重复状态查询和已提交事务的空回滚；会话状态变更须通过 JDBC 方法，禁止用原始 SET 语句绕过驱动状态。事务连接关闭自动重连，断线直接失败并由账本保留提交结果未知语义。其他服务未设置这些连接默认值时沿用原行为。

数据库构建统一由服务启动触发。启动自检在 SQLite 写事务或 MySQL GET_LOCK 下串行执行，防止多个 JVM 重复建表；已存在对象检查列类型、默认值、主键、唯一约束、外键和索引。`harness_store_identity` 保存部署身份及当前结构摘要，`harness_tenant` 保存租户初始化时间及模板摘要。外来表、结构冲突或身份/摘要不匹配会拒绝启动，不会删除或重建业务数据。

MySQL DDL 独立提交，启动先写入数据库身份，再逐项检查和创建缺失对象；中断后重启继续完成。SQLite 失败回滚整个结构事务。

MySQL 建表统一显式指定 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin`，保证字符串外键两端的字符集和排序规则一致。旧版部分表继承数据库默认排序规则，可能在创建 `harness_inference` 等表时失败；升级后，启动仅兼容该已知旧版结构摘要，在核对部署身份和已有结构后继续补建，完整结构校验通过才更新摘要。已有表和数据保留，doctor 和存储校验 API 仍只接受当前结构摘要。

## 租户首次访问

在同一个 `harness.properties` 中配置：

```properties
execution.enabled=true
execution.tenant.templates=./config/harness-tenant
```

默认目录随部署包提供 Baize2 资源任务的 model/runtime/task JSON，全部字段及引用约束见[租户配置说明](config/harness-tenant/README.md)。启动时整体校验并固定快照：model/runtime 引用和能力必须完整，每个 task ID 只能有一个版本，所有 task 自动建立 active 绑定，`skillReleases` 必须为空。模板不自动导入技能包；技能发布及已有租户配置管理仍使用现有管理能力。需要 Baize 或自定义模型时集中调整模板，模型定义必须与部署能力匹配；模板不按 worker 数量或在线状态过滤。

执行启用时，业务请求通过 Cube token、权限和基本参数校验后，用 token.domain 检查租户。新租户在同一事务内创建 profiles、active 绑定和初始化记录；数据库唯一键与锁处理多请求、多 JVM 并发，失败全部回滚并可重试。task-profiles 和直接创建 run 均覆盖此流程，无效 token 不初始化。

已有 profile 或 binding 的租户只登记初始化状态，保留原配置。初始化完成后，模板修改仅影响新租户，重启后生效；既有租户不会自动增加或重新启用任务。task-profiles 列表与模型 worker 是否就绪分别表达配置和运行状态。

## 权威数据和提交语义

- `harness_run/lease` 保存状态、version、eventSeq、owner、数据库时钟租约及递增 fencing。所有 owner 写入验证租约；CAS 检查 UPDATE 的 affectedRows。renew 不推进业务版本。接管不会自动重发旧 attempt。
- `harness_round/tool_call` 保存固定 intent、参数摘要、原 fence、attempt 和不可变结果。旧 owner 回调拒绝；新 owner 可核验原 intent/hash/fence 后对账历史证据。未确认 attempt 存在时拒绝盲目重试。
- `harness_request` 将 tenant、subject、operation、target、key 和载荷摘要绑定，保存原始回执。同载荷返回原回执，冲突拒绝。当前不清理幂等记录，保留不短于 run 生命周期和 24 小时。
- `harness_event/outbox` 与状态同事务；每次推进一条 event 和稳定 messageId 的发送意图。没有推送通知时仍能轮询恢复。Outbox claim 也有租约/fence，失败重试；成功发送后丢 ACK 允许重送。跨 run 不承诺全局顺序，消费者按 runVersion/eventSeq 处理顺序。
- `harness_inbox` 将接收去重与**同数据库**业务效果合并提交；外部效果需 Runner journal/Broker。`dispatch` 的 Sender 仅在接收方持久接收后成功返回；实际 model/runner 发送仍须由 Coordinator/Broker 做当前 fence、权限和状态准入。持久的旧 intent 不是重新执行授权。
- `harness_result` 每 run 唯一。终态结果、可选实际 usage、事件和 Outbox 同事务提交；不确定的提交结果必须按幂等键/intent 查询和重试，不能另建操作。存储方法在事务提交后才返回，HTTP 创建入口等待提交后返回 202。
- `harness_usage` 分列估算和实际；`harness_usage_adjustment` 以 round/attempt/evidenceId 去重，并用 expectedRevision 防止迟到修正覆盖新值。实际值替换该 attempt 的总量，汇总不重复累加；只有估算时为 provisional。终态后可由当前 owner 对账，不重开结果。

`submitControl` 仅记录 CAS 控制意图和幂等回执；`requestCompensation` 记录绑定原 intent 的补偿/对账义务。控制接口决定 wait/approval/取消策略，Broker 负责外部执行与对账。存储方法本身不会执行外部效果。

恢复使用 run、intents、事件、控制回执和未发送 Outbox，不依赖 Trace。`recover` 按 tenant 分页返回过期 owner 的非终态 run，以及执行/usage 未结算的终态 run；调用方通过 tenant+subject 继续读取。事件分页明确拒绝错误游标，缺失区间返回 EVENT_GAP；当前不自动删除事件。

## 工具调度与执行证据

工具调度保存 runner identity、tool job、durable revocation 和 Broker exchange。unknown 执行不删除、不重执行；已保存结果可重放回执。部署和保留策略见 [TOOLS.md](TOOLS.md)。

## 交互控制与文件生命周期

等待、控制回执、文件/root 身份、pin、策略及删除墓碑均保存在任务账本中，启动自动建表。控制/文件接口和默认 TTL 见 [CONTROL-FILES.md](CONTROL-FILES.md)。

## 轨迹、反馈与扩展执行证据

任务账本保存追加式反馈、事件状态投影、模型/工具/推理阶段时间及物理完成证据。关闭 Trace 不删恢复账本。参见 [TRACE.md](TRACE.md) 及工作区 `skill/harness-extensions/README.md`。Python Firecracker 执行器与 Runner 分别记录物理完成，重启只对账或清理本单元 unknown 实例，不重发代码；物理证据齐全才释放容量。
