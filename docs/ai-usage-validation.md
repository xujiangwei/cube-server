# AI 用量交付验证记录

日期：2026-10-28。所有数据库与 HTTP 测试均在本机隔离环境进行，未连接线上数据库，未执行线上部署。

## 交付内容

- `lib/`：更新后的 common、service、service-harness、dispatcher、console 共 5 个 JAR，class major version 均为 52（Java 8）。
- `console/web/`：完整生产静态资源，须整套替换。
- `config/ai-usage-sources.json.example`：只读计量源配置示例，无实际凭据。
- `docs/ai-usage.md`：结算口径、接口、自动建表/迁移、补记恢复与升级顺序。
- `python/`：配套 apexnova-wisdom 的 OmniVL 协议更新与回归测试。保持其中相对路径复制到对应 Python 工程；也提供该工程的补丁供审阅。
- `evidence/`：页面截图、受控测试数据的 CSV 与验证日志。
- `SHA256SUMS.txt`：包内文件校验和；ZIP 另附校验和。

AI 用量属于 Console Web；登录后进入「仪表板 → AI 用量」（`/ai-usage`）。不需要单独启动监视程序。service 创建计量表并持久化；Console 配置数据源后只读查询。

## 环境

- Windows；OpenJDK 1.8.0_504，Ant 1.10.15。
- SQLite JDBC 与本机隔离 MariaDB 10.11.15（MySQL 兼容协议/方言），绑定 127.0.0.1:33316，使用专用测试库。
- Vue / vue-tsc / Vite 6.4.3；Python 3.11。
- HTTP 集成使用真实 Jetty 入口、service 队列和事件、JDBC、Console Cookie 权限与导出接口。模型单元为受控替身，不依赖 GPU。

## 已通过

| 验证 | 结果 |
| --- | --- |
| 5 个交付模块 JDK 8 release 构建 | 通过 |
| 前端 vue-tsc 与 Vite 生产构建 | 通过 |
| AIUsageTest / SQLite | 37 项断言 |
| AIUsageTest / MariaDB MySQL 兼容模式 | 37 项断言 |
| AIUsageIntegrationTest | 14 项断言：真实 JSON/SSE REST → 持久化 → Console 授权查询、Contact 账单、CSV；流分段身份与终态重放 |
| ChatCompletionServiceTest | 179 项断言：排队、取消、迟到完成、重复回调、未派发请求、无 Contact 拒绝及容量回收 |
| MultimodalStreamRoutingTest | 95 项断言 |
| CapabilitySnapshotTest | 8 项断言 |
| Harness test-execution / test-inference / test-transport | 通过；覆盖多个 worker、共享容量、身份隔离、重试与晚到证据 |
| Harness 严格启动迁移 / SQLite 与隔离 MariaDB | 通过：旧 schema 升级、保留 run、不回填历史、并发 JVM 启动、冲突拒绝 |
| dispatcher REST/模块/HTTP 及原报告链路回归 | 通过；RestMetricsTest 62、CompletionHttpTest 94、ReportSubmissionTest 35 项检查 |
| 原服务监视器与性能上报集成回归 | 通过 |
| Python 新流计量测试 | 4 项通过 |
| Python OmniVL 单元回归 | 42 项，1 项硬件相关测试跳过 |
| Python 视频流停止 E2E | 16 项通过；停止后的已执行分段仍保存用量 |
| Python capture timeouts / Token usage | 4 / 28 项通过 |
| 页面与下载 | 无流量、未配置源、部分源不可用、HTTP 500、Contact 账单、小时趋势、CSV 下载均验证；390px 与 1440px 布局无页面横向溢出 |
| 差异空白检查 | 通过 |

账本回归覆盖跨域同 ID、多 Token 同 Contact、归属不可重分配、并发写入、修订与迟到结果、实际/估算/未知分离、共享库去重、起点包含终点不包含、上海月界、故障补记与重启恢复。REST 测试确认客户端伪造 user 不能更改结算主体。

浏览器下载的总览 CSV 有 19 次调用、15,356 个实际 Token。Contact 10000001 的 CSV 有 6 次调用、4,800 个实际 Token及 1,500 个估算 Token；估算未混入实际总量。这些是隔离测试数据，不是生产账单。截图中的「数据不完整」是因为测试库在本月中途开始采集。

## 验证边界

- 本轮验证的是 MariaDB 的 MySQL 兼容实现，尚未在原生 MySQL 8 上执行同套回归。
- 未进行真实 GPU / 生产模型推理；一项硬件测试跳过。模型提供方缺少可信 Token 时保持估算或未知，不补成实际值。
- 多库联邦读取在各库各自事务内一致，不是全局同一时刻快照。开放账单会随迟到结果、修订更新。
- 未执行金额计算、扣款、封账或线上部署。数据库原始明细和聚合长期保留；旧 aigc_usage 不回填。

## 重跑入口

先使用 JDK 8 构建 common、service、dispatcher、console、service-harness，然后运行：

```text
ant -f verify/ai-usage.xml -Dlib.dir=<本机隔离依赖目录> test
ant -f service-harness/build.xml -Dlib.dir=<依赖目录> -Dcell.jar=<cell.jar> test-execution test-inference test-transport
npm --prefix console/webapp run build
python -m unittest discover -s tests -p test_ai_usage_stream.py
python -m unittest discover -s tests -p test_omni_vl_stream_stop_e2e.py
```

Python 命令在 apexnova-wisdom 根目录执行。MySQL 兼容测试必须使用空的专用测试库；禁止对线上库运行测试或删除表。具体升级与恢复操作见 `docs/ai-usage.md`。
