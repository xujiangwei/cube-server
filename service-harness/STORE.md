# N03 RunStore

`cube.service.harness.store` 是独立 JDBC 账本，兼容实际 JDK 8。SQLite 用于单实例开发；MySQL 8.0.43 已在本机独立实例上执行多连接、双 JVM 和崩溃恢复专项测试。完整结果见工作区 `harness/execution-records/n03-20261017/`。N02 的原生 Linux 验收尚未完成，不能据此宣布 G0/G1 通过。

## 配置和迁移

默认 `store.enabled=false`。不读取旧 Storage、旧数据库配置或环境凭据，不修改旧表。设置为 true 后，HarnessService 和 doctor 校验数据库身份、schema 版本及迁移摘要；失败时仅停止 Harness 自身启动。启动不执行 DDL。

SQLite 私有配置示例（先创建此实例独有的目录；路径必须是绝对路径）：

```properties
store.enabled=true
store.dialect=sqlite
store.database=harness_development
store.identity=replace-with-private-random-store-identity
store.sqlite.root=C:/private/harness-store
store.sqlite.file=C:/private/harness-store/harness_development.db
```

文件名必须等于 `store.database + .db`，真实父目录必须位于指定 root 内，拒绝数据库文件符号链接。SQLite 使用独立连接、BEGIN IMMEDIATE、foreign_keys、busy_timeout 和 synchronous=FULL；没有多副本认证承诺。

MySQL 由数据库管理员先创建**全新专用数据库**与两个账户。数据库名和账户名必须以 `harness_` 开头，只允许小写字母、数字和下划线；数据库标记必须与配置的随机 identity 一致。迁移账户仅在这个库获得 SELECT/INSERT/UPDATE/DELETE/CREATE/INDEX/REFERENCES/ALTER，运行账户仅获得 SELECT/INSERT/UPDATE/DELETE。两者均不授予全局权限、其他库权限、角色或 GRANT OPTION。连接时检查 SHOW GRANTS；超范围账户拒绝启动。

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

默认 MySQL TLS `VERIFY_IDENTITY`，使用部署环境配置的可信证书。仅专用 loopback 开发实例可设置 `store.mysql.loopbackDevelopment=true`，此时允许无 TLS 的本机 SHA2 公钥握手。没有任意 JDBC URL、multiQueries 或 LOCAL INFILE 配置入口。凭据保存在实例私有配置中，不交给 Python worker。

通过与部署一致的 JDK 8/classpath 显式执行：

```powershell
# Windows classpath 分隔符为 ;，Linux 为 :。依赖包括两种 JDBC 驱动及 org.json。
java -cp '<harness.jar>;<dependencies/*>' cube.service.harness.store.StoreMigrations initialize-empty C:/private/migrator.local.properties
java -cp '<harness.jar>;<dependencies/*>' cube.service.harness.store.StoreMigrations verify C:/private/runtime.local.properties
```

当前 schema=4：002 增加包、profile、工作区和上下文表，003 增加物理模型后端与任务/占用证据表，004 增加异步推理和增量事件账本，旧 job 保留 execution_protocol=2。`initialize-empty` 接受新空库，写身份、版本和迁移 SHA256；对于完整受信的已知旧版本，它与显式 `upgrade` 都只执行缺少的前向迁移；相同版本重复执行只校验。已有 v1/v2/v3 库建议明确执行 `StoreMigrations upgrade <private.properties>`，启动不升级。任何外来表、错误身份、版本、摘要或不完整 schema 都会拒绝。MySQL 使用 GET_LOCK 协调迁移，但其 DDL 不是可回滚事务；中途失败保留现场，由管理员在专用测试库重建或通过审核的前向修复恢复。不提供 DROP、自动 down migration 或旧表 DDL。旧二进制不能打开 v4；撤回保留 v4 兼容恢复程序和账本。详见 [RUNTIME.md](RUNTIME.md)、[EXECUTION.md](EXECUTION.md)。

## 权威数据和提交语义

- `harness_run/lease` 保存状态、version、eventSeq、owner、数据库时钟租约及递增 fencing。所有 owner 写入验证租约；CAS 检查 UPDATE 的 affectedRows。renew 不推进业务版本。接管不会自动重发旧 attempt。
- `harness_round/tool_call` 保存固定 intent、参数摘要、原 fence、attempt 和不可变结果。旧 owner 回调拒绝；新 owner 可核验原 intent/hash/fence 后对账历史证据。未确认 attempt 存在时拒绝盲目重试。
- `harness_request` 将 tenant、subject、operation、target、key 和载荷摘要绑定，保存原始回执。同载荷返回原回执，冲突拒绝。当前不清理幂等记录，保留不短于 run 生命周期和 24 小时。
- `harness_event/outbox` 与状态同事务；每次推进一条 event 和稳定 messageId 的发送意图。没有推送通知时仍能轮询恢复。Outbox claim 也有租约/fence，失败重试；成功发送后丢 ACK 允许重送。跨 run 不承诺全局顺序，消费者按 runVersion/eventSeq 处理顺序。
- `harness_inbox` 将接收去重与**同数据库**业务效果合并提交；外部效果需 N08/N09 的 journal/Broker。`dispatch` 的 Sender 仅在接收方持久接收后成功返回；实际 model/runner 发送仍须由后续 Coordinator/Broker 做当前 fence、权限和状态准入。持久的旧 intent 不是重新执行授权。
- `harness_result` 每 run 唯一。终态结果、可选实际 usage、事件和 Outbox 同事务提交；不确定的提交结果必须按幂等键/intent 查询和重试，不能另建操作。N03 的存储方法在事务提交后才返回，未来 HTTP 创建入口必须等待该结果才能返回 202。
- `harness_usage` 分列估算和实际；`harness_usage_adjustment` 以 round/attempt/evidenceId 去重，并用 expectedRevision 防止迟到修正覆盖新值。实际值替换该 attempt 的总量，汇总不重复累加；只有估算时为 provisional。终态后可由当前 owner 对账，不重开结果。

`submitControl` 仅记录 CAS 控制意图和幂等回执；`requestCompensation` 记录绑定原 intent 的补偿/对账义务。N11 决定 wait/approval/取消策略；N09 决定外部执行与补偿。此处没有自动执行外部效果。

恢复使用 run、intents、事件、控制回执和未发送 Outbox，不依赖 Trace。`recover` 按 tenant 分页返回过期 owner 的非终态 run，以及执行/usage 未结算的终态 run；调用方通过 tenant+subject 继续读取。事件分页明确拒绝错误游标，缺失区间返回 EVENT_GAP；N03 不自动删除事件。

## 验证和撤回

```powershell
ant -f service-harness/build.xml test-store
ant -f service-harness/build.xml '-Dstore.test.config=C:/private/mysql-runtime.local.properties' '-Dstore.test.output=C:/evidence/mysql-results.json' test-store
```

第二条仅对已初始化的**专用测试库**执行，配置必须显式含 `store.test.disposable=true`，测试会写入 run 和账本。同一套测试在两种数据库中覆盖并发幂等/CAS、双 JVM 抢占、真实 JVM halt 前后崩溃、租约接管、旧 owner 拒绝、历史证据、终态与迟到用量、补偿意图、跨租户查询和 Outbox/Inbox 故障。

本机可用 `scripts/n03_mysql_fixture.py start --mysql-home <专用解压目录> --runtime <新建私有目录>` 创建临时实例，脚本只绑定 loopback，生成独立端口/账户/配置并验证运行账户不能写隔离的模拟旧库。结束后用同参数 `stop`；不按进程名或系统服务名停止。不对真实旧库进行测试写入。

撤回时停止专用 Harness 写入者、关闭新入口，保留数据库/账本及备份。仅可删除确认可丢弃的测试库。新 SQL 资源随正式 jar 分发，旧构建和旧发布不依赖数据库或新测试通过。N03 没有启用业务 run API，仍返回 HARNESS_NOT_READY；profile/Coordinator 与完整控制能力分别在后续阶段验收。
