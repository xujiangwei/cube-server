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
