# 隔离负载、故障与发布兼容性

适用 JDK 8、独立 `service-harness` Cell 和独立 Python Harness 单元。默认开发配置只监听 loopback。回归工具覆盖 SQLite/scripted CPU 环境；生产负载、真实模型物理取消、Linux/KVM、跨主机滚动升级或旧生产流稳定性须另行认证。Firecracker/KVM 只由 Python Harness v6 单元执行，这些回归不启动 microVM。

业务负载统一通过 dispatcher/SHM，健康和接单管理直达单独配置的 service HTTP 地址，详见 [TRANSPORT.md](TRANSPORT.md)。先用 JDK 8 构建 common、dispatcher、service-harness；`resilience_check.py` 会另启 dispatcher fixture，并将该 JVM 纳入 CPU/RSS 采样。原直连 HTTP 认证记录不能当作新链路 SLO 结果，升级后须按相应负载重新执行冻结的认证。

## 接单与收尾

部署私有 `harness.properties` 设置 `execution.accepting=false`，并设置独立 `admission.admin.credential`（至少 24 字符，不能与 API、worker、trace reader 或审批凭据相同）。原开发配置默认仍为 `true`；部署时必须显式关闭。管理开关仅属于当前 JVM，重启恢复配置值，不能依赖内存开关在重启后保持关闭。多 JVM 部署必须逐节点关闭接单或先在新入口关闭路由。

使用管理凭据调用以下入口；凭据从私有配置读取，不放命令行或报告中：

```text
GET  /harness-admin/v1/admission
POST /harness-admin/v1/admission  {"accepting": false}
POST /harness-admin/v1/admission  {"accepting": true}
```

普通 API/worker 凭据不能管理接单。POST 仅接受一个布尔字段 `accepting`。关闭后新任务返回 503 `HARNESS_DRAINING`；已存在创建凭据仍能幂等重放，参数改变仍冲突。关闭接单不停止协调器、Cell、结果回执、查询、取消或文件清理。`/healthz` 保持存活，`/readyz` 和 capabilities 的 `runsReady` 反映接单状态。

管理响应给出 `activeRuns`、`unsettledModelJobs`、`unsettledToolJobs`、`unknownRuns` 和 `drained`。只有活动任务、未物理结束/未确认回执和 unknown 全部归零才报告 drained。计数覆盖当前 tenant 的账本，因此共享同一库/tenant 的其他节点也会阻止 drained；接单开关不跨 JVM 广播。unknown 不能靠取消或删除账本清零，保留兼容恢复节点与对应 Python journal 对账。

## 可复现本机演练

从 `cube-server` 执行，工具链使用真正 JDK 8。所有输出和 runtime 参数都必须选择专用新目录。脚本不会接受已有 runtime；不连接真实 Console 或模型。

```text
ant -f service-harness/build.xml test-tools test-control-files test-inference test-execution test-trace
python service-harness/scripts/resilience_check.py freeze --output <new-frozen-slo.json>
ant -f service-harness/build.xml -Dresilience.test.output=<new-storage-evidence> test-resilience
python service-harness/scripts/resilience_check.py run --java <jdk8-java> --python-root <apexnova-wisdom> --runtime <new-private-runtime> --evidence <new-evidence> --libs deploy/libs --common-jar build/cube-common-3.0.jar --cell-jar deploy/bin/cell.jar --slo <frozen-slo.json>
python service-harness/scripts/release_compatibility.py --java <jdk8-java> --ant <ant> --runtime <new-private-runtime> --evidence <new-evidence> --old-v6-jar <verified-schema6-jar> --prior-v7-jar <verified-prior-schema7-jar>
```

`scripts/tests/fixtures/local-slo.json` 是本机专项阈值，先冻结到不可覆盖的新文件并记录硬件/时间/摘要，再执行。每项报告包含 threshold、loadProfile、sampleCount、window、hardware、measured、verdict。调整阈值必须另建冻结文件和执行目录，保留失败轮，不允许执行后覆盖阈值。真实部署应另冻结正式生产负载/SLO，不能直接复用开发阈值。

Cell 脚本执行 service-harness JVM、dispatcher JVM 与三个不同速度、独立身份/目录/journal 的 Python model 单元，测单单元基线和三单元扩容、共享物理后端容量 2、缩容/drain、24 次并发创建的有界接单、关闭时取消/回执、JVM 硬崩溃与同实例重连、独立逻辑取消/actual_finished、ACK 收敛和受限子 JVM 堆 OOM。scripted worker 每个处理槽为 1；不同 Runner 容量/速度、真实模型实际取消另做目标环境认证，不能由这些测试代替。

`test-resilience` 执行 96 次上传和多轮扫描，记录回收前后实际字节、7 条 batch 的积压消化、下载 pin、unknown Artifact、quota、关闭重启、两个真实 JVM 竞争同一文件根，以及另一 Runner 根的哨兵保护。时间通过专用测试库的 expires_at 加速，不缩短产品 TTL 下限；不是 30 天 retention 浸泡，也不填满宿主磁盘。控制/文件回归补充 fence、claim 退避、关闭清理和权限竞态；Runner 回归覆盖提交前/后故障、丢 ACK、未知执行和拒绝重放。

release 脚本在一次性目录运行原始 Ant deploy/clean，模拟 Harness CI 失败后旧发布仍执行，确认新 clean 不动旧产物。发布样本为明确标记的 fixture 字节，不是生产二进制构建证明。账本部分实际加载历史 schema6 JAR 建库/写任务、新 JAR 显式升级到 schema7，历史版本拒绝降级且账本字节不变，兼容节点完成原任务；前一 schema7 JAR 和新版本分别读写新记录。迁移 SQL 不改字节、不做 down migration。

## 升级与撤回顺序

1. 固定新旧 JAR/Python/release/profile/schema 摘要，备份独立库与 journal；保持旧业务地址、DB 和发布任务独立。
2. 新节点配置 `execution.accepting=false`；确认健康、契约、模型/Runner/profile 和文件根正确。只对专用库显式执行 `StoreMigrations upgrade <private.properties>`，运行账户不做自动迁移。
3. 按节点开启新入口。更新 active task/release 使用 Catalog CAS；新任务固定新版本，旧任务保留旧 release pin。
4. 停止接单，保留查询/取消/Cell/回执并观察严格 drained。先 drain Python 单元，再用其自身 stop 脚本停止；不要按进程名杀进程。unknown 保留容量、journal 和恢复单元，不强制收尾。
5. 同 schema/兼容版本可回退新 JAR 和 active binding，启动仍保持关闭；验证既有账本读写后再开启。跨 schema 旧程序若 verify 拒绝，保持新入口关闭，用兼容 schema 的恢复节点收尾，不恢复旧 DB 快照覆盖新记录、不删表降级。
6. 任一旧探针超过冻结容差，只关闭/缩减 Harness。回退核心和 Firecracker/知识/联网等扩展分别记录；未认证扩展继续关闭。

本机不能覆盖的门槛必须报告 NOT_RUN：正式 SLO/旧业务同负载探针和已建立流、生产资源/成本、跨租户服务公平、多主机/多副本 MySQL、长窗口真实 TTL/存储吞吐、真实磁盘满/OS OOM、真实模型和 Linux Firecracker 攻击认证。脚本通过仅表示本机专项通过，生产认证仍需满足这些条件。
