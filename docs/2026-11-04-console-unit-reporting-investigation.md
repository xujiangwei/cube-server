# 2026-11-04 console AI 单元离线排查

## 线上证据

控制台：`http://211.157.179.37:7080`，域：`default_domain`。以下时间均为北京时间。

- service 日志在 21:03:30 仍有 `skipped - reporting suspended`。
- 21:03:40 出现 `Resume submitting reports - retry up to 10 times`，随后成功投递到 `http://127.0.0.1:7080/report`。
- 21:03:50，service 成功提交 UnitReport，console 同时记录收到该报告；随后在 21:05:00、21:06:10 等时刻继续收到报告。
- 21:08:30 复核 `/statistic/units?domain=default_domain`：130 个台账实体，25 在线、5 离线、100 无活动，最近上报为 21:08:20。浏览器页面也显示相同状态。
- `/statistic/unit-deployments?domain=default_domain` 的有效连接部署按 Contact ID 去重后，与台账的 25 个在线 ID 完全一致。
- 5 个离线实体为 200111、201075、201076、201077、201078；当前部署快照均无这些实体，历史最近活动分别在 10 月 28 日、27 日、16 日。100 个“无活动”条目不属于“离线”计数。

## 原因与边界

`ReportService` 连续 10 次提交失败后暂停 30 分钟，期间丢弃所有报告，包括 UnitReport。console 的单元快照有效期只有 5 分钟，快照过期后在线实体集合为空，仍保持工作连接的 AI 单元也会在台账上失去在线状态。这是监控上报中断造成的状态缺失，不能据此证明 Python 单元断线。

原 `UnitOverviewView.vue` 只在进入页面、切换域、切换标签或点击刷新时查询数据，没有定时刷新。因此即便上报已经恢复，停留的页面仍可继续显示旧的离线结果。

线上记录已确认暂停与恢复过程，但 console 留存日志没有包含首次触发暂停时的失败详情，无法据此认定第一次失败是连接拒绝、超时或 HTTP 拒绝。需要 service 本地日志中 20:33 左右的 SubmitThread 失败记录才能继续区分。排查期间没有重启线上进程，也没有向线上写入模拟报告。

台账“最近活动”来自联系人历史事件，不是最后一次 UnitReport 的时间；长时间没有登录事件不代表单元离线。当前在线证据表示工作连接有效，不代替模型推理健康检查。

## 修改

- 上报暂停时间从 30 分钟缩短到 10 分钟，保留连续失败 10 次触发、丢弃陈旧队列、暂停期后重新尝试的行为。
- AI 单元页面每 30 秒刷新当前标签；隐藏页面时暂停请求，返回前台时立即刷新；请求未结束时跳过本轮，继续保留域与标签切换的过期响应保护。
- 重建 `console/web/`，同步前端说明。
- 扩展真实 HTTP 上报回归，覆盖触发暂停的次数、10 分钟截止时间、暂停期间零请求及到期恢复。

## 验证

- 使用 Amazon Corretto JDK 8u504 编译 common、console，成功。
- `ReportSubmissionTest`：48 项检查通过，包括 503 连续失败、暂停期与恢复。
- `UnitOnlineStatusTest`：129 项断言通过，包含多节点、断线、报告超期与重新上报恢复。
- `UnitDeploymentsTest`：37 项断言通过。
- `npm run build`：TypeScript 检查及 Vite 构建通过。
- 本地浏览器使用现有 mock 响应验证：初次查询返回 0 在线，后续上报恢复后自动重新查询。

## 发布范围

改动已经在本地完成并验证，尚未发布到线上。需要替换实际 service/dispatcher classpath 使用的 `cube-common-3.0.jar` 并重启对应 JVM，10 分钟常量才会生效。页面自动刷新需要发布重建后的整个 `console/web/` 并重新加载页面。此次不需要更换 console Java 后端；打包检查生成的 console JAR 仅用于回归验证。

service 当前部署根目录由线上接口报告为 `/home/baize/server/cube-server/server`。发布前应核对实际 JVM classpath 与 console 启动目录，备份旧文件；本次没有修改服务器配置或线上部署文件。
