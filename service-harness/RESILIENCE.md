# Harness 接单、停机与恢复

接单开关控制当前 JVM 是否接受新任务。关闭接单后，Coordinator、Cell、结果回执、查询、取消和文件清理继续工作，以便完成在途执行。业务接口使用 dispatcher，管理接口直接访问 service 的 loopback HTTP 地址。

## 接单配置与管理

在私有 `config/harness.properties` 中配置：

```properties
execution.accepting=false
admission.admin.credential=<至少24字符的独立管理密钥>
```

管理密钥不能与 worker 凭据相同，调用时使用 `Authorization: Bearer <管理密钥>`。接口为：

```text
GET  /harness-admin/v1/admission
POST /harness-admin/v1/admission  {"accepting": false}
POST /harness-admin/v1/admission  {"accepting": true}
```

POST 只接受布尔字段 `accepting`。内存开关仅作用于当前 JVM，重启恢复配置值；多 JVM 需要逐节点关闭或先关闭入口路由。关闭后新任务返回 503 `HARNESS_DRAINING`，已创建请求仍可按原幂等键重放回执，异载荷仍冲突。

`/healthz` 仍表示存活，`/readyz` 和 capabilities 的 `runsReady` 反映接单状态。管理响应还包含 `activeRuns`、`unsettledModelJobs`、`unsettledToolJobs`、`unknownRuns`、`drained`。仅当活动任务、未结算执行和 unknown 全部归零才为 drained；这些计数覆盖共享数据库的全部租户，其他 JVM 的未决任务也可能阻止 drained。

## 部署、升级与停止顺序

1. 准备配套的 common、dispatcher、Harness JAR 和兼容的 Python worker；备份专用数据库、文件根及各单元 journal。新节点设置 `execution.accepting=false`。
2. 启动 Java，确认数据库结构自检、租户模板、Auth、文件根与 provider 配置通过。逐个启动 Python 单元，检查 worker 身份、能力、后端健康和共享容量。
3. 按节点开启接单。已有租户的 profile/release 变更先发布不可变新版本，再通过 Catalog CAS 切换 active；在途 run 保留原配置及 release 引用。
4. 停机时先关闭接单，保持模型与 Runner 在线至在途执行收敛，观察严格 `drained`。随后逐个 drain Python 单元，并使用其对应配置的 stop 脚本停止。
5. 最后停止 Java Cell。独立实例使用自身 StopFile 或服务管理器的 SIGTERM；再次启动前移除该实例已消费的停止文件。合部署的整 JVM 停止使用宿主服务管理方式。

worker drain 会停止分配新作业，保留结果与注销链路。每个进程使用独立 workerId、运行目录和租约；停止单个 worker 不应影响同一 JVM 下其他单元。

## 故障与对账

| 情况 | 行为与处理 |
| --- | --- |
| Cell 断连或租约过期 | 新作业不分配给该单元；重连后重新注册和对账，不能由断连推断物理执行已结束。 |
| 客户端取消或执行超时 | 拦截后续派发；在途执行继续报告。终态可以先进入 unknown，迟到证据只结算执行/用量，不重开结果。 |
| Java 重启时存在 dispatched 窗口 | 按稳定的 `inference.node.id` / `broker.node.id` 隔离未确认执行，等待原执行证据。 |
| Python journal 存在 running/unknown | 保留原运行目录并对账；不自动重跑代码，不通过更换 workerId、runtimeDir 或 backendId 清空占用。 |
| 已完成但 ACK 丢失 | 重送同一结果；Java 持久化去重后回 ACK，传输重送不增加 attempt。 |
| 数据库身份、结构或文件根策略冲突 | Harness 拒绝启动；核对配置、备份及配套产物，不删除业务数据或身份标记。 |

unknown 不能通过取消、删除账本或清空 journal 强制归零。未确认的物理执行保留相应容量；工具物理执行虽已结束，但上游业务效果未知时，run 仍可保持 unknown。需保留兼容恢复节点及原始证据。

撤回时先关闭新准入或切回仍 ready 的旧 active 定义，保留旧任务所需的模型、provider、Runner 和配置至结算完成。`execution.tools.enabled=false` 会切断工具链路，不能代替对未决 Runner 的 drain。数据库格式不兼容时不得直接让旧 JAR 继续写库。

## 运行边界

模型 worker 处理槽、物理推理容量、Runner 槽以及活跃任务限额分别计数。扩容后应通过 capabilities 检查实际容量，并核对共享 backend 没有重复计额。文件空间由配额、TTL、pin 和清理进度共同决定，unknown 文件/工作区保留到证据收敛。

合部署共享 JVM 的 CPU、堆和 GC；多 Java 节点还必须满足数据库协调及文件共享规则。SQLite 用于单实例开发，文件根当前只支持同机共享真实目录；跨主机部署的具体边界见 [STORE.md](STORE.md) 和 [CONTROL-FILES.md](CONTROL-FILES.md)。
