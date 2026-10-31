# Service 执行监控与运行拓扑

此版本沿用 PerfReport /report 与 /server-report，不增加监控数据库。服务性能快照独立每 10 秒上报，JVMReport、UnitReport 保留原周期。本轮仅生成本地构建产物，不执行线上部署。

## 统计对象与口径

- `items.Service.schemaVersion = 1`，包含 execution、modules、topology、jvm、runtimeSeries、pools。
- Cellet 原始入站消息只计 `receivedMessages`，不等于业务调用；控制命令独立放在 `execution.control`，默认不进入业务排行。Harness 原始 worker 注册、心跳、poll 和结果消息放在 worker-message 控制命令中。文件字节流不作为业务调用。
- 业务命令在公共 Task 提交执行器前计数，执行器开始时从 queued 转入 running。异常、队列拒绝和异步终态都保证只结束一次。Harness 使用独立业务执行池，接入同一指标采集器。
- `requests` 是进入执行队列的次数；`completed` 是结束观察的次数（包含 accepted/unknown），拒绝单独计 rejected。HTTP 形态的 Harness 202 或 AIGC 中间态表示 accepted，不能解释成最终完成。普通码 0 只表示该命令返回结果成功。
- `succeeded` / `failed` / `timedOut` / `cancelled` / `rejected` / `accepted` / `unknown` 分开展示；失败率 = (failed + timedOut) / (completed - accepted - unknown)。拒绝率不混入失败率，取消独立展示。没有已知终态时失败率为 null。
- 普通总耗时包含排队；executionAvgMs 是开始执行至结束观察的时间，异步动作和长任务包含等待时间，不能当作 CPU 时间。无样本耗时为 null，页面显示 --。
- 流式任务独立统计首响应与总时长，不进入普通平均/P95；首响应不等于首个 Token。
- 10 秒时间桶；窗口和排行合并最近 6 个已完成桶（最多延后 10 秒）；RPS 按进入次数除以窗口实际秒数，启动首桶完成前为零秒窗口。全局趋势保存 180 个完成桶。固定 128 桶对数直方图按样本数合并计算近似 P95。
- 运行累计值在 JVM 重启后重置。动作最多约 1024 种，超出归入 Other；不使用动态资源 ID、令牌、查询参数或请求内容作为排行键。

## 异步与插件覆盖

- 结构化 AIGC generation 在进入生成服务时计提交；真实队列开始、delta/完成首响应、completed/error、超时和取消分别计量。逻辑超时后物理 reservation 继续占用时，只结束逻辑指标，不改变资源释放策略；迟到终态不会重复计完成。
- 传统 chat 的图像/文本/知识问答动作在实际终态回调结束，Processing/Inferencing 不计最终成功。
- Harness run 使用 JdbcRunStore 已提交的 `run.*` 状态事件。回滚不计数，幂等重放不新增任务；等待输入/审批保留状态。恢复任务标注 recovered，耗时从本 JVM 首次观测开始。内存最多跟踪 8192 个活跃任务及 8192 个已结束任务的去重标识，超出观测上限的事件计 untracked；标识不进入报告。
- 其他旧异步动作在未观测到结果的执行返回时计 unknown；命令受理与任务终态分别查看。新插件应在返回前调用 `ActionContext.deferResponse()`，非流式响应自动结束观察；流式插件在终态片段发送后调用 `completeResponse(code)`。该方法为 Java 8 default 方法，兼容旧插件。未声明延迟的旧流式插件返回时标记 unknown。

## 拓扑与容量

- 上游由实际 TalkContext 和 `_performer.node` 发现，按 dispatcher 节点身份归并重连；报告实际远端连接地址。旧 dispatcher 无节点名时按会话端点识别。totalCalls 是入站消息量，包含控制调用，不是 REST 次数。
- 下游按 module + workerId + instanceId 区分，不把相同角色的多个 worker 合并。AIGC Unit 使用已注册路由与会话身份；Harness 仅转出 worker 身份、角色、就绪、健康、排水、租约和占用字段，不发送完整 descriptor 或凭据。
- Harness `capacity` 完整复用 ModelDispatcher.capacity()，沿用共享 backend 与 inference capacity group 去重。不能将 worker 声明的容量直接相加；AI Unit 不推算全局可执行并发；其连接状态与现有调度忙碌标记单独展示，忙碌标记不当作精确物理占用。
- 断线节点保留 30 分钟；provider 采集异常时标记未知，不伪装断线。最多保留 256 个上游与 512 个下游。
- 30 秒没有新报告即过期。新鲜度以 console 接收时间计算的 reportAgeMs 为准，并在浏览器用单调时钟增加；数据过期、刷新失败或节点停止时，连接状态降为未知。

## JVM 与页面

轻量 JVM 健康随性能快照采集：堆 used/committed/max（字节）、线程/守护/峰值、GC 累计与采集间隔增量；runtimeSeries 最多 180 点/30 分钟。原 JVMReport 口径不变。ThreadPoolExecutor 展示活跃、队列、线程上限、完成数；未知实现显示 null，共享 Cellet 执行器去重。Harness 提供 worker/business/admin 专用池。

页面默认每 10 秒自动刷新；每次只查询一份包含完整历史的最新性能快照。支持命令/长任务趋势切换、模块和动作排行与过滤、控制命令开关、拓扑缩放/悬停/点击。旧报告显示“当前节点尚未提供服务执行监控数据”；查询失败保留最后快照；节点切换用查询代数忽略旧响应。部署列表显示业务运行/排队，Contact 在线人数独立标注。

独立 Harness 部署未安装 Contact 时也应正常上报；Daemon 仅在 Contact 模块已启动时追加其指标。

## 验证入口（JDK 8）

- `verify/src/cube/monitor/ServiceMetricsTest.java`：可控时钟、队列/并发、终态幂等、RPS/P95、窗口淘汰、流式拆分、控制命令与 worker/上游重连、真实 Task 执行器桥接。
- `verify/src/cube/monitor/ServiceActionMonitoringTest.java`：真实 SPI 延迟/流式/旧模块、异常、取消与终态幂等。
- `service/src/cube/service/test/ChatCompletionServiceTest.java`：生成、断连、超时、取消、重复/迟到事件、12 单元与物理 reservation。
- `service-harness/test/cube/service/harness/test/RunStoreTest.java`：真实 SQLite 提交/回滚、响应丢失、幂等与终态的监控一致性。
- Harness `test-execution`、`test-inference`、`test-transport`：多 worker、共享容量去重、重连、队列拒绝与真实 Cell 业务。
- `console/webapp/mock/check_service_monitor.cjs`：生产前端的 23 项 Chrome 检查。先 `npm run build`，启动 `python webapp/mock/mock_console_server.py web 8897`，设置 PLAYWRIGHT_MODULE 后运行脚本。

不增加 AI Token 统计、跨天查询或监控数据库。生产配置与线上服务保持原状。
