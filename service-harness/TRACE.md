# Harness 诊断、反馈与离线回放

Trace 是可选诊断导出。任务恢复使用权威账本和 checkpoint，不依赖诊断文件或外部追踪后端。账本保留事件状态投影、模型/工具/推理的派发与完成时间、物理完成证据和追加反馈。

## 配置与导出

在私有 `harness.properties` 配置，重启生效：

```properties
trace.enabled=false
trace.reader.subjects=
trace.retention.seconds=604800
trace.export.max.bytes=262144
feedback.enabled=false
```

启用 Trace 时须配置 `trace.reader.subjects`，格式为 `example.com/10003`，多人用英文逗号分隔。调用 dispatcher 的 `GET /harness/v1/runs/{runId}/trace`，使用白名单用户自己的有效 Cube Bearer token，只允许导出同域任务。该授权不扩大普通 run、文件或反馈操作的本人数据范围。

导出在一致性事务中读取任务所有者的数据，保留身份/版本摘要、状态、attempt、usage、阶段时间与文件生命周期信息；模型/工具输入输出及内容字符串替换为摘要，反馈原因不导出。不会包含配置文件、token、Cookie、grant 或宿主路径。阶段时间为空表示未采集或未发生。

导出期限从 run 最后权威更新时间起算，默认 7 天，范围 60～31536000 秒；到期返回 `TRACE_EXPIRED`（HTTP 400）。每包上限为 4096～262144 字节，最多 1024 事件、256 轮、256 调用；超限明确拒绝，不截断。服务不缓存导出包，下载副本由下载者保管。关闭或到期不会删除账本、checkpoint、journal 或未决文件 pin。

## 反馈

`POST /harness/v1/runs/{runId}/feedback` 使用任务所属用户的 Cube token、`Content-Type: application/json` 和 `Idempotency-Key`：

```json
{"rating":"positive","reason":"可选原因"}
```

rating 可为 positive/negative，reason 最多 4096 字符。202 返回 feedbackId；同键同内容重放回执，异内容返回 409。新键追加反馈，最多 256 条/run，不覆盖历史，也不改变 run 版本。入口由 `feedback.enabled` 独立控制，不依赖 Trace 开关。

## 离线回放与评测

```sh
python -I -S scripts/replay_trace.py /private/evidence/trace.json
```

回放工具只读指定文件，不连接服务、数据库或 Runner，不执行记录中的代码。它校验包摘要、连续事件/版本、终态和最终状态投影，返回确定性的状态及脱敏 mock 输出；内容摘要无法重建原文。缺少历史状态投影的导出标记 `replayComplete=false`，工具拒绝回放。

质量评测工具读取固定场景定义、追加式观察记录及只读导出目录：

```text
python scripts/evaluate_traces.py <fixture.json> <observations.jsonl> --traces-root <trace目录> --output <report.json>
```

场景定义固定模型/profile/configDigest、工具范围、预算、故障和预期状态；观察记录关联真实 trace 摘要及独立语言复核结果。评测器重新校验 Trace、终态、工具、预算和物理收尾，保留失败与重试。场景模板见工作区 `skill/harness-extensions/fixtures/`，具体输入格式与校验条件以脚本为准。

导出和反馈契约位于 [contracts/trace/](contracts/trace/openapi.json)；业务访问统一经 dispatcher/SHM，见 [TRANSPORT.md](TRANSPORT.md)。

## 公开样本能力诊断

`scripts/capability_evaluation.py` 通过现有 Python 模型适配器调用真实接口，分别运行 Baize JSON 决策、Baize2 原生工具决策与视觉 Unit。`service-harness-skill/scripts/capability_cell_evaluation.py` 使用 JDK 8、独立账本、真实 dispatcher/Cell/SHM、多个 Python worker 与测试专用 MediaProvider 运行工具及完整编排诊断。代理仅在 test classpath 存在，不修改生产协议；真实模型与视觉调用串行执行。

工作区 [巡检评估入口](../../patrol/README.md)维护复现与评分命令，[能力验证总结](../../patrol/evaluation/REPORT.md)集中记录工程验证、真实推理与视觉校准结论。当前视觉集为 43 张独立照片、48 个案例，来源与标注见[样本登记](../../patrol/media/evaluation/manifest.json)；旧林业路由见[历史对照](../../forest/evaluation/REPORT.md)。重复调用不增加独立样本量。工具成功、任务成功、引用有效和业务判断正确分别评价；失败、修复、调用未知与夹具错误保留。

这套诊断与 `evaluate_traces.py` 的严格认证分开。诊断场景数较少，不能跳过原有至少 50 场景、真实 Trace、物理完成和独立语义复核条件，也不以同名样本或 mock 充作认证结果。

## 媒体执行阶段与回执边界

Provider 权威事实表按 job 保存 `media-phase`：`preparing` 为资源读取和图片准备，`queued` 为等待匹配的物理槽，`handoff` 为已预留实例并交给桥接器。`execution` 固定 requestId、unit、instanceId、serviceSn、deadlineAt；不能仅凭 handoff 推定已经物理派发。

生产 `HarnessMediaBridge` 在 transmit 之前同步回调 `stage=physical-dispatch`，持久化成功后才允许发送；持久化失败按确未发送收尾。该阶段表示进入发送边界，不能证明远端已受理或完成。旧 MediaProvider 未提供这一回调时，阶段缺测必须保留，不能由工具受理时间补造派发时间。

`receipt` 原样保存可信 `actualFinished=true` 回执；`receipt-metadata` 另存数据库记录时间、回执摘要、success/unsent。相同回执幂等，不同内容或不同 requestId/instanceId 明确拒绝。阶段宿主时间与数据库时间分别保留；跨机器时钟未校准时不能直接相减得到延迟。完整 payload 仍遵循原 Trace 脱敏与大小限制，诊断导出不替代权威事实表。

| 边界 | 判定和处理 |
| --- | --- |
| `CAPABILITY_NOT_READY` | 无符合角色、能力与有效健康状态的实例；未取得物理预留，不发出推理 |
| `CAPABILITY_CAPACITY_UNAVAILABLE` | 有兼容实例但实际槽已占用；仅重试预留，等待上限由 queue 配置和剩余期限共同约束 |
| `CAPABILITY_QUEUE_TIMEOUT` | 有界排队耗尽；不重发推理，不把未派发计成视觉漏检 |
| 确未发送 | 收到 `unsent=true, actualFinished=true` 回执，可释放预留；不能以单纯超时推断 unsent |
| 已完成失败 | 可信物理回执 `success=false`，保留原始原因，按失败收尾 |
| 回执后的本地交付失败 | 已保存物理回执仍有效；工具或 run 可失败，不能新增物理调用或改写远端完成事实 |
| 派发后超时、取消、断连、Runner 退出 | 缺可信完成回执时保持 unknown 和容量隔离；不以心跳恢复或新实例注册释放旧槽，不自动重发 |
| 迟到回执与重启对账 | 按原 requestId/instanceId 关联并释放已确认完成的预留；已超时或取消的 run 保持原终态 |

长等待必须由 Runner 显式声明 `provider-await-v1`，签名 wire 的期限同时限制准备、排队和执行；旧 Runner 不会接收超过 30 秒的 wire。配置范围与升级顺序见 [部署指南](BUSINESS-DEPLOYMENT.md)。每次独立评估保存二进制和源码摘要、实际请求、原始账本与回执关联；可控推理仅证明执行契约，不计入真实模型/视觉质量。
