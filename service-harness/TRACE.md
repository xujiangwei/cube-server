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
python -I -S scripts/evaluate_traces.py <fixture.json> <observations.jsonl> --traces-root <trace目录> --reviews-root <复核目录> --evidence-root <原始证据目录> --output <新report.json>
```

使用 Python 3.11，`evaluate_traces.py`、`quality_evaluation.py`、`replay_trace.py` 须放在同一目录，均只读本地证据，不调用模型或业务服务。输出独占创建，已有报告不会覆盖。成功认证退出码为 0，证据齐备但未认证为 2；证据缺失、摘要不符或格式错误直接报错，不能计作成功。场景与复核模板见工作区 [fixtures](../../skill/harness-extensions/fixtures/)。

配套行为评测还需要同目录的 `behavior_evaluation.py`。Trace 的 metrics.decisionObservations 对齐每个模型轮次的输入版本、近期确认结果、先前行动和本次决定；旧轮次没有观测时标为缺测。metrics.adjustmentControlDigest 对完整固定配置移除 execution.version 与 evidenceAdjustment 后求摘要，比较时用于约束授权、模型、生成参数、工具/资源、固定流程和预算一致。二进制、外部数据与运行环境仍须由实验流程冻结，配置摘要不替代这些条件。hostWorkflowSteps 单列宿主固定步骤，不能将其成功归因于模型自主选择。

### 证据调整的独立行为复核

fixture 增加 `behaviorEvaluation:"evidence-adjustment-v1"`，每份现有独立人工 review 增加 behavior，version 同值，并完整标注四个基础数组。每条都须有具体 rationale 和本 review 中的 evidenceIds；不允许只写总体计数或模型自评：

| 数组 | 绑定与分类 |
| --- | --- |
| calls | 覆盖全部 `intent_id/attempt`，字段 intent；classification 为 useful、necessary_exploration、wasteful 或 uncertain |
| requests | 覆盖全部 run.waiting_input/run.waiting_approval 的 event_seq，字段 eventSeq；分类为 necessary_clarification、required_approval、avoidable 或 uncertain |
| interventions | 覆盖全部 message.accepted/control.resume/control.approval；分类同上，另允许 goal_change；可填有证据的 elapsedMillis，缺测另列 |
| adjustments | 覆盖全部模型 `intent_id/attempt`，字段 round；trigger 为 confirmed_error、information_gap、evidence_conflict 或 none；effect 为 helpful、harmful、neutral 或 unassessable |
| finals | 新模板覆盖全部模型 final，字段 round，逐项引用该轮原始上下文。分类为 complete、premature、justified_limited 或 uncertain |

interaction.requested/answered 是上述操作的伴随事件，不重复计数。既定审批必须标为 required_approval，不能计为可避免救援；改变用户目标另列。首次探索失败、合理回读、必要分页和冲突交叉核对不自动算浪费；已有可用证据却重复读取、收到明确反馈仍重复错误参数等，须结合当轮实际可见内容判定。评审工作本身不是运行中的人工介入。没有行为复核时统计缺测，不报告零救援/零浪费；存在 uncertain、unassessable 或目标变化时不宣称完整目标达成。

提前收尾按人工复核统计：有可获得的必需证据或用户信息、仍可继续推进却直接收尾，标为 premature。证据确实不可用且答复明确交代已覆盖范围和缺口，标为 justified_limited。旧复核缺少 finals 或存在 uncertain 时，报告覆盖情况，提前收尾率留空。

baseline 比较同时输出 behaviorComparison：按同一任务和 repetition 配对，保留全部尝试，先在任务内平均三次重复，再按独立任务做固定种子的 4000 次聚类 bootstrap，报告正确率、无需救援正确率、可避免请求/介入、无效调用与总调用量差的 95% 区间。重复运行不扩大独立样本数。基线开关必须关闭、候选开启，固定配置约束摘要一致、Trace 不复用、观测与复核完整才可比较。旧式 nonRegressing 仍仅表示现有条件下未退化。

goalAchieved 还要求非诊断、原有正式认证通过、至少 50 个独立任务及三次重复、正常任务（category=clean）不退化、零执行硬失败、无宿主固定流程代替模型选择，以及正确率提高、人工救援减少、无效调用减少三项区间分别支持对应方向；可避免请求也不能增加。三项分别报告，费用或时延增加须单列，不能用“同预算上限”代替“同实际成本”。该统计只适用于冻结任务集，不证明生产分布上的普遍收益。

准备 24 个公开开发任务、两份执行配置和无可执行代码的资源测试包：

```text
python -I -S scripts/prepare_adjustment_evaluation.py prepare <实际harness-execution目录> <全新实验目录>
python -I -S scripts/prepare_adjustment_evaluation.py freeze <实验目录>/suite.template.json <实验目录>/baseline.fixture.json --model <逻辑模型> --profile <共同逻辑profile> --config-digest <基线真实run的config.digest>
```

同一包还生成 `continuity.suite.template.json`，用于诊断多轮交互，覆盖补充资料、冲突来源、必要追问和执行中更正。更正场景订阅事件，看到首个 `round.intent` 后按 interactionScript 追加 instruction，保留受理和采纳证据；如果消息受理前任务已终态，该次计作场景未跑通。该场景集用于诊断续接和收尾，与自动决策的基线开关比较使用各自的结果。

候选另用其真实 config.digest 冻结同一模板，两份 fixture 的 suiteDigest 应一致。必须使用同一套新二进制，仅更换开关和执行修订；不能拿升级前旧流程作唯一因果基线。包按标准发布、审核、ready、授权后显式选择 `skills:["evaluation/evidence-adjustment"]`，runtime=resources@1，模型选择真实网关配置。两组每任务三次，每次独立 run；澄清任务按 interactionScript 使用同一固定答案和延迟，保留原问题及全部交互。对照所用配置在独立目录中，不把两份 execution JSON 放进同一加载目录。

测试包含旧路径、需补读的资料、可消解/不可消解冲突、必要澄清和正常任务，每类四个；旧路径不保证模型一定犯错，提前选对来源也应按实际结果评分。明确已确认错误的注入及 unknown/取消/权限/重启控制由本地边界回归覆盖；真实质量运行意外出现的 unknown 保留为失败，不换新任务抹除。24 例全为公开 development，不是留出集，评估须用 --diagnostic，不能认证。扩展到正式规模和封存 holdout 后重新冻结。参考答案放在实验目录 references，不在模型可读包内；实际 request/result/reference 与人工 sidecar 的保存仍遵循下文的摘要绑定规则。

保存真实请求、run 响应 result 字段及该场景参考答案后，还须由获授权的账本读取/采集流程保存每轮 harness_round.payload_json 原样 JSON 到受控证据目录。它包含当轮真正送入模型的 request/messages，而非仅有账本结果的摘要；不得复制进公开 Trace。以 `[{"round":"round-0001/1","path":"run/round-0001.json"}]` 列出全部轮次及其相对路径，生成逐项对齐的待复核 sidecar：

```text
python -I -S scripts/prepare_adjustment_evaluation.py review --fixture <冻结fixture.json> --trace <真实trace.json> --scenario <场景ID> --evidence-root <原始证据目录> --request <相对request.json> --result <相对result.json> --reference <相对reference.json> --contexts <逐轮证据索引.json> --reviewer <独立评审ID> --destination <全新review.template.json>
```

输出初始标准均为 fail，行为项为 uncertain/unassessable；人工完成事实判定、记录实际复核时间及逐项依据后，按规范 digest 命名为 `<reviewDigest>.json`。round-context 证据与 Trace 的 payload_json_sha256 逐轮绑定，所有行为判定须引用上下文证据；缺少当轮可见内容时不能认定重复读取是浪费。observations 按原格式绑定对应 run、Trace 和 review，不以模板替代复核。该命令不提交任务、不调用模型，也不自动判定成功。

### 冻结任务与评分口径

新版 fixture 使用 `schemaVersion=2,status=FROZEN`，固定实际部署的 model、逻辑 profile 和 configDigest，以及每个任务的真实 input、expectedScope、faults、预期终态和预算。输入摘要必须互异；改名或重复调用不能增加独立场景数。模板仍标记 `TEMPLATE_NOT_FROZEN`，不能直接认证。输入中的 attachmentIds 也应固定，复现时保留获准的同一批 Input。

每个场景声明 category、split（development/holdout）及 qualityCriteria；全局 rubric 固定 id/version、criteria 的 id/dimension/description。每场景必须覆盖四个维度：protocol（遵守交互契约）、task（完成用户目标）、grounding（结论有证据）、limitations（正确表达未知和限制）。各标准须写成可以核对的任务预期，而非笼统“语言质量好”。holdout 在调优期间保持封存，不能依据其结果反复修改提示和规则。

默认按 expectedTools 精确核对工具多重集。允许多种正确路线的任务可声明 `toolPolicy:{"mode":"bounded","required":[...],"allowed":[...]}`，required 必须是 allowed 子集；允许工具不代表增加实际权限。预算约束 maxRounds/maxCalls，可选 maxInputTokens/maxOutputTokens；指定 token 上限但实际 usage 缺失时拒绝通过，不把未知计成 0。非空 expectedScope 须有 argumentsHash 承诺；该哈希对应真正包含范围的完整调用参数对象，范围语义仍需复核。

每场景必须有 `allPhysicalFinished` 断言，校验全部模型、工具及推理作业的真实物理完成、`executionSettlement=confirmed` 和无工具 effect_status=unknown。轮次、工具成功、任务成功和业务语义分别统计。认证要求至少 50 个独立任务、每任务 3 次真实独立运行、全部覆盖、零执行硬失败、总体至少 95% 通过，且 holdout 占比至少 20%、其通过率至少 95%，同时保留 development 集。`--diagnostic` 可用于小规模开发集，结果始终为 NOT_CERTIFIED。

### 观察记录与独立复核

observations.jsonl 每行记录 fixtureDigest、model/profile/configDigest、scenarioId、repetition（1～3）、attempt（从 1 连续递增）、mode（real/mock/skip）、producerId、recordedAt；real 还需 hardPassed、traceDigest、reviewDigest，可选 elapsedMillis。hardPassed 只是采集方断言，评测器仍会重验执行条件。每次真实尝试使用独立 run/trace，不能重复使用同一 trace。失败原始尝试不能被成功重试替换；同一任务/重复槽的全部尝试都通过才计通过。mock/skip 不填补真实覆盖。

Trace 放在 `traces/<traceDigest>.json`。复核 sidecar 放在 `reviews/<reviewDigest>.json`，digest 按 `replay_trace.digest` 的规范 JSON 计算。复核须绑定 fixtureDigest、scenarioId、traceDigest、runId、rubricId/version，并记录 reviewer（kind=human，id 与 producerId 不同）、recordedAt、逐 criterion 的 pass/fail、具体 rationale 和 evidenceIds。单个 `languagePassed=true` 不再能证明质量。

每份复核至少引用 request/result/reference 三类证据；path 相对 evidence-root，sha256 为原始文件字节摘要，每文件最多 1 MiB，每份最多 64 文件，不接受路径逃逸或符号链接。request.json 是原 POST 的 input 对象，须等于冻结 input，且与 Trace 中模型轮次的 run.created 用户消息脱敏承诺一致。result.json 是 GET run 响应的 `result` 字段原样对象（含 state、executionSettlement、result、usage），规范 JSON 摘要须匹配 Trace 的 resultCommitment.result_hash。reference 是人工标注、规则及业务事实依据，不是模型自评。每条标准的 evidenceIds 只能引用本份复核中存在的证据。

脱敏 Trace 无法恢复原始文本。授权人员须另行保存实际请求、结果和参考材料，在受控目录中完成复核，不能把这些私有资料复制进公开 Trace。摘要只证明内容关联与未被替换；reviewer.id 是记录声明，不是身份认证。评审人员独立性、标注正确性和留出集保密仍由实际评审流程保证。

旧 schemaVersion=1 仍可读取并输出 legacyResult，正式 result 始终 NOT_CERTIFIED。既有报告与 ZIP 保留，新证据按 v2 另存。

### 基线比较

对候选运行使用相同 rubric、任务、split 和预算，固定另一份实际 configDigest，再增加 `--baseline <原report.json>`。两份报告 suiteDigest 必须一致，并按同一逻辑 model/profile 对齐；可修改配置修订与 configDigest。每份只保留该逻辑组合的一种配置，不能在同报告中混合基线和候选。

比较输出总体/holdout 通过差、各维度失败差、轮次/工具数、p50/p95 和已报告 token 差。覆盖完整、总体和 holdout 未下降、各维度失败未增加且零执行硬失败才标记 nonRegressing。耗时为采集方记录的整次耗时，token 为实际返回部分，缺测另列；不能把缺测估算成完整成本。比较结果不替代认证，也不证明统计显著改善。改变任务或评分标准后须重新建立基线。

导出和反馈契约位于 [contracts/trace/](contracts/trace/openapi.json)；业务访问统一经 dispatcher/SHM，见 [TRANSPORT.md](TRANSPORT.md)。

## 公开样本能力诊断

`scripts/capability_evaluation.py` 通过现有 Python 模型适配器调用真实接口，分别运行 Baize JSON 决策、Baize2 原生工具决策与视觉 Unit。`service-harness-skill/scripts/capability_cell_evaluation.py` 使用 JDK 8、独立账本、真实 dispatcher/Cell/SHM、多个 Python worker 与测试专用 MediaProvider 运行工具及完整编排诊断。代理仅在 test classpath 存在，不修改生产协议；真实模型与视觉调用串行执行。

工作区 [巡检评估入口](../../patrol/README.md)维护复现与评分命令，[林业样本入口](../../forest/evaluation/README.md)说明旧路由与原始证据。能力行为分别以 [数据能力](BUSINESS-DATA.md)、[执行检查与报告](WORKFLOW-REPORTS.md)及[持续互动](INTERACTION.md)为准，部署步骤集中于[部署指南](BUSINESS-DEPLOYMENT.md)。评测脚本只输出机器记录，不自动改写能力说明。

诊断固定业务包、模型配置、适配方式、工具定义、数据、规则、评分器与预算，再索引原始请求、run/job、回执及摘要。普通报告、缺页保护、取消和单元恢复分别评分；普通报告分母保留真实失败及容量超时，夹具错误与主动中断单列。token 只加总已返回的模型 audit，含修复尝试；缺失、迟到用量和视觉成本不估算。变更路由、提示、适配或调用顺序后重新记录实际配置，不能将多项变更解释为单变量改善。

样本来源、许可和标注见[样本登记](../../patrol/media/evaluation/manifest.json)。视觉评分按独立案例汇总，重复调用仅用于评价稳定性。报告分别列出误报、漏报、失败及结构缺失。人员、车辆或物体存在需要结合业务规则判断，实际执行状态、引用有效性和业务结论分别核对。正式质量认证按上文的独立任务、真实 Trace、物理完成和人工复核要求执行。

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
