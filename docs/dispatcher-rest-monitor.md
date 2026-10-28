# Dispatcher REST 监控与服务拓扑

调度机页面保留部署管理，监控区展示 HTTP/HTTPS 请求、模块与规范化接口、30 分钟趋势和实际 service 连接。自动刷新默认开启，每 10 秒查询一次。后端独立采集和上报，关闭浏览器不会丢失趋势。

## 统计口径

- 公共 `HttpServer` 的可选 Jetty `HttpChannel.Listener` 由 dispatcher 安装。到达时增加请求数及并发；真正完成响应后统计耗时并回收并发。同步、异步、异常和断连均按同一请求记录，完成操作幂等。
- 普通请求统计完整响应耗时。响应类型为 `text/event-stream` 的 SSE 单独统计首个非空响应正文耗时及连接总时长，不进入普通平均耗时或 P95。首包是 Jetty 输出正文事件，不表示首个 AI Token。
- 失败率 = 完成时 HTTP 4xx/5xx 或传输失败的请求数 / 已完成请求数。同时出现只计一次失败；传输失败另列。HTTP 200 不能推断业务执行结果。
- RPS 按请求到达次数计算。长请求的到达与完成可处于不同窗口，因此窗口请求数和完成数不保证相等。当前并发包含尚未结束的 SSE。
- 使用单调时钟和 10 秒时间桶。窗口合并最近 6 个**已完成**的桶，启动不足一分钟时按已覆盖秒数计算 RPS；首个桶完成前窗口为 0 秒。窗口最多延后 10 秒，累计值与并发即时计数。
- 趋势保留最近 180 个完整桶（30 分钟），概览、模块、接口使用同一窗口。累计值从本次启动计算，重启归零；全部数据仅存在内存。
- P95 合并样本直方图后求分位点，不能平均多个 P95。固定 128 档，按毫秒的四分之一倍频程分档，约 19% 相对粒度。无样本的耗时和失败率为 `null`，前端显示 `--`。
- 模块来自内置处理器所属包或 SPI 注册名称。普通接口使用注册上下文；Harness 和 `/v1` 使用已有路由模板。ID、Token 后缀、查询参数不进入统计键。未知路径归入“未匹配路由”；OPTIONS、`/static`、`/doc` 不计。接口键数有固定上限，溢出合并至 Other。

## 报告与兼容

仍使用 `PerfReport`、`POST /report` 与 `GET /server-report?report=PerfReport&time=0&name=...&detail=true`。dispatcher 性能报告使用独立定时器每 10 秒提交，不受下游延迟探测阻塞；JVM 报告保持原来的 60 秒周期。

`report.items.Dispatcher` 的 v1 结构：

```text
schemaVersion: 1
http:
  startedAt, windowSeconds, windowStartedAt, windowEndedAt, inFlight
  window, total: requests/completed/failures/transportFailures/statusCodes/rps/
                 failureRate/normalCount/avgMs/p95Ms/streamCount/streamAvgMs/firstByteAvgMs
  series: [timestamp + 同一组指标]                # 最多 180 点
  modules: [module, window, totalRequests, inFlight]
  routes: [module, method, route, window, totalRequests, inFlight]
topology.services:
  [id, address, port, cellets, ready, calls, sendFailures, totalCalls, totalSendFailures]
```

完整趋势随每份快照发送，不依赖 console 的历史报告条数。查询响应顶层可带 `reportAgeMs`，从 console **收到报告的单调时钟**计算，避免节点与 console 的系统时间偏差。前端在查询间继续累计时间，30 秒未收到新报告即过期，实时连接状态降为未知。查询失败保留旧指标并显示错误。切换调度机后丢弃旧查询结果。

旧 dispatcher 缺少 v1 快照时显示“当前节点尚未提供 REST 监控数据”，只根据配置绘制灰色拓扑并标注“未获实时状态”。旧 console 未提供 `reportAgeMs` 时显示新鲜度未知。

拓扑从运行中的 Director 表读取，以 `host:port` 为节点身份（IPv6 加方括号），同模块可有多个端点，断开仍保留节点。`ready` 表示 Cell 会话就绪。`calls` / `sendFailures` 是最近窗口的**下游消息发送尝试及发送失败**，包含内部控制消息；不等于 HTTP 请求数、应答成功数或业务执行成功数。

## 验证与构建

Java 统一使用 JDK 8。按各模块 Ant 脚本依次构建 common、dispatcher、console；`lib.dir` 指向第三方依赖目录，避免历史 Cube jar 覆盖刚构建的 common。

- `cube.dispatcher.test.RestMetricsTest`：可控时钟的窗口、RPS、加权 P95、淘汰及累计重置；真实 Jetty 同步/异步/HTTP 错误/异常/SSE/断连；路由归一及多端点断线重连。
- `cube.console.test.ReportSubmissionTest`：生产上报处理器、异步发送器、嵌套快照、详细与简略查询、接收时间新鲜度及失败重试。
- `verify/src/cube/dispatcher/RestReportIntegrationTest.java`：classpath 同时包含 common、dispatcher、console。使用 JDK 自带 keytool 生成临时测试证书，验证真实 HTTP + HTTPS → Performer 快照 → ReportService → console HTTP 查询，无外部数据库依赖。
- 前端运行 `npm run typecheck`、`npm run build`，输出到 `console/web`。
- 在 `console/` 运行 `python webapp/mock/mock_console_server.py web 8897`，再运行 `node webapp/mock/check_dispatcher_monitor.cjs`。需 Playwright 和 Chrome；可用 `PLAYWRIGHT_MODULE` 指定模块路径、`CONSOLE_TEST_URL` 指定地址。覆盖自动刷新、模块过滤/排序、节点点击、过期、无流量、旧报告、错误、切换竞态及 390px 布局。mock 仅用于本地联调。

交付包含 common、dispatcher、console 三个 jar 与 console/web 全部资源。本改造不新增数据库、不支持跨天历史查询，也不统计 AI Token 用量。
