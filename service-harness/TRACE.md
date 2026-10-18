# N13 追踪、离线回放与质量评测

trace 与反馈统一访问 dispatcher `/harness/v1`，经 SHM 长连接交给 service；reader Bearer 与角色在 service 验证。service HTTP 仅保留健康/运维入口，详见 [TRANSPORT.md](TRANSPORT.md)。

当前实现为默认关闭的诊断导出、内容脱敏后的状态回放和追加反馈。schema7 显式迁移增加事件状态投影、反馈表、工具协议/物理完成证据和模型/工具/推理的派发、完成、取消时间；001～006 不变，启动不执行 DDL。升级前停止旧写入者并备份；旧二进制不能直接读 schema7。恢复继续使用权威账本，不访问导出文件或 Trace 后端。

新增契约位于 `contracts/trace/`，包含版本化 OpenAPI、导出和反馈 schema/摘要，并镜像到 Python 工程同名目录；原 base/control-files 契约保持原字节。当前 `TRACE_EXPIRED` 映射 HTTP 400，独立权限错误为403，幂等冲突409。评测的 expectedTools 对照全部调用（含内建资源工具），物理完成断言包含模型、工具和网关推理三类执行账本。

在同一私有 `harness.properties` 配置，重启生效：

```properties
trace.enabled=false
trace.reader.credential=
trace.retention.seconds=604800
trace.export.max.bytes=262144
feedback.enabled=false
```

启用时 reader 至少 24 字符，且必须不同于 API、worker、审批凭据。当前身份仍是 development 的固定 tenant/subject；不宣称生产身份系统已完成。

`GET /harness/v1/runs/{runId}/trace` 仅接受独立 reader Bearer；普通 API/审批凭据不能导出，reader 不能操作 run、反馈、文件或恢复 checkpoint。导出使用一个一致性事务和该身份的 tenant+subject 归属检查。默认保留结构、身份/版本摘要、状态、attempt、usage、队列/派发/结束/取消、文件 ready/delete 与提交时间；模型/工具输入输出的内容键和字符串均替换为摘要，反馈原因也不导出。不会把配置文件、网络 token、Cookie、grant 或宿主存储路径放进 Trace。

服务不持久缓存诊断包；超过 run 最后权威更新时间的 TTL 即拒绝新导出，默认 7 天。TTL 范围 60～31536000 秒；每包 4096～262144 字节、最多 1024 事件/256轮/256调用，过大明确拒绝，不能静默截断后宣称完整。下载者保管自己的导出副本。TTL、关闭或丢失导出包不会删除权威账本、恢复 checkpoint、journal 和未决文件 pin。各阶段时间的空值表示历史未采集或尚未发生，不填造耗时。

反馈沿用既有 API：`POST /harness/v1/runs/{runId}/feedback`，普通 API Bearer、`Content-Type: application/json` 和 `Idempotency-Key`。正文 `{"rating":"positive","reason":"可选原因"}`，rating 也可为 negative，原因最多4096字符。202 返回 feedbackId；同键同内容回放，不同内容409；新键追加，最多256条/run，不覆盖历史，也不改变 run 版本。反馈入口由独立 `feedback.enabled` 开关控制，默认关闭；不依赖 Trace 开关。

纯离线回放命令：

```sh
python -I -S scripts/replay_trace.py /private/evidence/trace.json
```

程序只读显式文件，使用 Python 标准库；不导入 Harness 配置、网络库、provider、Runner、数据库或执行器，不执行记录里的代码。检查包摘要、连续事件/版本、终态、最终投影一致性，返回确定性的状态和脱敏 mock 输出。它重建权威状态，不重新生成原自然语言；内容摘要不是原文。schema7 以前的事件没有投影，导出 `replayComplete=false`，回放拒绝，不能伪造历史补齐。

质量入口 `scripts/evaluate_traces.py <冻结fixture> <追加observations.jsonl> --traces-root <只读导出目录> --output <report.json>`。每个部署单独冻结 model/profile/configDigest 和至少50个不同场景；每场景包括 input、expectedTools/Scope、assertions、faults、budget、expectedState、quality。扩展夹具模板在工作区 `skill/harness-extensions/fixtures/`，仍须填入实际范围和承诺后冻结，不是已经执行的50场景。

每个真实 observation 需要 fixtureDigest、场景/repetition(1～3)/attempt、model/profile/configDigest、mode、traceDigest、recordedAt、hardPassed 和独立的 languagePassed 复核。导出按 `<traceDigest>.json` 存放，不能复用一次真实 run 充当多次。评测器重新核验 Trace，并检查终态、工具、预算、物理收尾及配置/参数/文件承诺；scope 不为空时必须有对应调用语义 argumentsHash 断言。语言判断单列；人工反馈不自动等价于质量通过。失败与修复/重试全部保留，同一槽任何失败不能被后续重试覆盖。至少143/150且硬断言全通过；mock、skip、缺项单列，不能由其他组合平均抵消。当前没有新增真实模型质量认证，G3 为未认证。

本机入口为 `ant -f service-harness/build.xml test-trace`，可传 `-Dtrace.test.config=<独立测试库配置>` 和 `-Dtrace.test.output=<导出文件>`。外部扩展及真实 Cell 回归见工作区 `skill/harness-extensions/README.md`。
