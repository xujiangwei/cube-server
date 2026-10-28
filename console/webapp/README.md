# Cube Console 前端（webapp）

控制台 Web 端的工程化前端，负责查看调度机（Dispatcher）与服务单元（Service）的运行状态。

- 技术栈：Vue 3 + TypeScript + Vite + Pinia + Vue Router + Tailwind CSS 4 + ECharts 5
- 构建产物：直接输出到上一级的 `web/` 目录，即 Jetty `ResourceHandler` 的 `resourceBase`，
  因此后端**无需改动静态资源路径**即可服务新前端。

## 环境要求

| 项 | 版本 |
| --- | --- |
| Node.js | ≥ 20（推荐 22） |
| npm | ≥ 10 |

## 常用命令

```bash
npm install          # 安装依赖

npm run dev          # 开发模式，默认 http://localhost:5173
                     # 接口请求会代理到本机 7080 端口的 Cube Console

npm run typecheck    # 仅做 TypeScript 类型检查（vue-tsc）
npm run build        # 类型检查 + 构建，产物输出到 ../web
npm run build:only   # 跳过类型检查直接构建
```

## 没有后端时如何预览

真实的 Cube Console 依赖 MySQL，本地未连库时无法启动。此时可以用自带的 mock 后端
（同时提供静态文件服务与 SPA 回退）：

```bash
# 在 console/ 目录下执行
python3 webapp/mock/mock_console_server.py web 8899
# 浏览器打开 http://127.0.0.1:8899/dashboard
```

mock 返回的 JSON 结构对齐后端 `toJSON()`，但**不校验凭据、数据全为内存假数据**，
仅限本地联调，不可部署到任何可被外部访问的环境。

`/host/static`、`/host/metrics` 也已 mock：动态指标用正弦波随时间漂移，方便观察实时曲线是否在动。
**注意单位**：JVM 报告里的内存是 **MB**（控制台入库前已按 1048576 换算），mock 必须保持一致，
否则前端纵轴会大三个数量级。

## 与后端的接口约定

Dispatcher REST 监控的指标口径、报告 schema、兼容行为与验证步骤见 [监控说明](../../docs/dispatcher-rest-monitor.md)。

前端依赖以下后端行为（见 `console/src/cube/console/container/handler/`）：

1. **鉴权**：登录成功后后端下发 `CubeConsoleToken` Cookie，前端后续请求自动携带；
   任一接口返回 `401` 时前端会清理本地登录态并跳回登录页。
2. **登录**：`POST /signin`。带 `username` / `password`（口令为 **MD5 摘要**）时执行登录；
   无参数时按 Cookie 续期。请求头 `X-Requested-With: XMLHttpRequest` 时返回 JSON，
   否则保持旧版表单跳转行为。响应体为 `UserToken`（含 `user` 档案）。
3. **启停**：`POST /dispatcher/{start,stop}`、`POST /service/{start,stop}`，
   参数 `tag` / `path` / `pwd`（同样为 MD5 摘要）。
4. **路由回退**：`SpaFallbackHandler` 会把非静态文件、非接口路径回退到 `index.html`，
   前端因此可以安全使用 history 路由模式。
5. **AI 单元能力**：`GET /statistic/units` 的每个单元带 `capabilities` 数组与顶层
   `unitReportTime`。能力**不在任何数据库里**，而是由 service 进程的 `Daemon` 每约 60 秒
   经既有节点上报通道（`POST /report`，报文名 `UnitReport`）送到控制台内存快照，
   控制台按物理实体 ID 归并、跨节点同能力去重，TTL 5 分钟。
   因此：**能力可能为空**（节点未上报 / 该实体上没有 AIGC 单元），
   前端以 `--` 并按 `unitReportTime` 提示数据新鲜度，不要把它当成统计口径缺失。
   「按单元名称」标签页单独调用 `GET /statistic/unit-deployments?domain=...`（需登录 Cookie）。
   此接口直接读取有效节点快照，不依赖统计库或昨日联系人台账；缺少域返回 400，无部署返回 200 与空 `groups`。
   响应为 `{domain, queryTime, unitReportTime, truncated, groups}`，其中每组为
   `{name, deploymentCount, capabilities, deployments}`，名称取 `capability.name`，精确区分大小写。
   部署为 `{id, name, addresses, device, platform, reporters, reportedAt, capabilities}`：
   同域同名下按 Contact ID 去重，跨节点上报合并节点、接入 IP 和能力，不按 IP 去重。
   `reportedAt`、`unitReportTime` 使用 console 接收时间，后者为最近有效节点报告的接收时间；
   连接有效的条目进入部署视图，超过 5 分钟的报告不参与查询，空报告立即替换该节点的旧部署。
   扩展 `UnitReport.units` 的可选字段为 `connected/address/device/platform`，原台账仍读取全部能力。
   旧报告缺少扩展字段时保留其身份与能力，地址、设备和平台显示 `--`；未声明连接状态不推断健康或容量。
   报告超过 500 条时携带 `truncated=true`，页面提示部署数不完整；截断可能漏掉完整域，故任一有效报告截断均提示。
   目前仅覆盖现有 AIGCService 单元，不接入 Harness worker；独立部署需使用独立 Contact ID。
   接入 IP 是 Java 服务观察到的单元连接地址，不是远端推理 HTTP 后端地址。
6. **节点版本号**：`GET /servers/dispatcher`、`GET /servers/service` 的每一项都带 `version`
   （调度机取 `cube.dispatcher.Version`，服务单元取 `cube.service.Version`）。
   值来自**该节点部署目录** `libs/` 里 jar 内的同名类，而不是控制台自身的 classpath，
   因此一个控制台下挂着不同构建版本的节点时能显示各自的真实版本；
   目录不可读、jar 里没有该类、控制台 classpath 也没有时，后端给**空串**（字段一定存在），
   前端以 `--` 兜底。

## 目录结构

```
src/
├── api/            接口封装与数据模型
│   ├── client.ts       HTTP 客户端（表单编码、超时、401 统一处理）
│   ├── console.ts      按后端句柄划分的语义化接口
│   ├── monitor.ts      性能/JVM 报告的计算与拉取
│   └── types.ts        与后端 toJSON() 严格对齐的类型定义
├── charts/         ECharts 按需注册
├── components/     布局、表格、弹窗、日志面板、监视器等通用组件
│                    （AppShell 为外壳：侧边栏导航 + 顶栏 + 内容区，侧边栏支持收窄为图标条；
│                     RealtimeAreaChart 为任务管理器风格的实时曲线）
├── composables/    useChart（图表生命周期）、usePolling（轮询）、useMediaQuery（断点判断）
├── mock/           本地联调用的 mock 后端（纯 Python 标准库，无依赖）
├── router/         路由表与登录守卫
├── stores/         Pinia：登录态、服务器清单、全局通知
├── utils/          MD5、格式化、表单校验
└── views/          登录、服务器概览、用户概览、调度机、服务单元
```

## 部署说明

`web/` 的构建产物已纳入版本管理，服务端无需安装 Node.js 即可直接运行控制台。
修改前端代码后需在本目录执行 `npm run build` 并提交 `console/web/` 的变更。

## 维护约定（重要）

1. **不要手工编辑 `console/web/` 下的任何文件**。该目录是 `emptyOutDir: true` 的构建产物，
   下一次 `npm run build` 会全部清空重建。所有改动都发生在 `src/`。
2. **服务必须从 `console/` 目录启动**。`Handlers` 里 `ResourceHandler` 的 `resourceBase` 是
   相对路径 `web`，工作目录不对会直接 404。
3. **前端路由与接口同名的坑**：`/dispatcher`、`/service` 既是前端页面路由，又是后端接口的
   上下文路径（接口挂在 `/dispatcher/xxx`、`/service/xxx`）。
   `SpaFallbackHandler` 因此把前缀分成 `RESERVED_PREFIXES`（裸路径 + 子路径都排除）
   与 `SUB_PATH_ONLY_PREFIXES`（仅子路径排除）两组。
   **新增接口前缀时必须同步维护这两组名单**，否则页面会 404 或接口被回退成 HTML。

服务执行监控：默认 10 秒刷新，消费 `items.Service` v1 快照；采集与兼容口径见 `../../docs/service-execution-monitor.md`。本地浏览器回归：`node mock/check_service_monitor.cjs`（先构建并启动 mock 服务，需要 Playwright/Chrome；可用 `PLAYWRIGHT_MODULE` 指定依赖路径）。
