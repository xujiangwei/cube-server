# 业务能力部署指南

本文统一说明数据查询、加工、单图分析、必需步骤检查与结构化报告的安装和人工运行顺序。基础服务与 dispatcher 配置见 [服务部署](README.md)，能力契约见 [业务数据](BUSINESS-DATA.md)和[执行检查与报告](WORKFLOW-REPORTS.md)，业务资源跨环境晋级见 [发布与回退](RELEASE-MANAGEMENT.md)。

## 任务推进与上下文能力升级

配套交付目录 `cube-server/build/harness-agent-delivery/` 及同名 ZIP 包含两个 Java JAR、Python 节点 ZIP、执行/Provider 模板、通用工具包和离线评测脚本；`checksums.json` 记录 SHA-256。它不包含生产凭据、数据库或已验证的远端 tokenizer。安装和首次运行按以下顺序操作：

1. 关闭新准入，drain 并对账现有任务；备份两个 JAR、全部私有配置、Python 节点目录和原 journal，保留原 release/policy/binding。两个 JAR 配套替换，Python 节点更新完整运行包，保留各 workerId/runtimeDir/journal。一个 JVM 接多个 Python 单元的部署方式不变。
2. 更新全部可能接到任务的 Python 模型单元，完整安装 model_adapter.py、context_compaction.py、context_summary.py 和 contracts；不能只替换单文件。再合并 `execution.json` 修订 3：taskState=true、adaptiveHistory=true、compressionTriggerPercent=80、compressionTargetPercent=60、repairReserveTokens=1024、repairReserveBytes=2048、skillWindow=6、finalWireGuard=true、maxRepeatedCalls=3。semanticSummary 默认关闭。保留私有模型/执行器引用和总预算；移除旧 historyWindow 或保留作旧策略配置，新 run 使用新版本。旧 worker 无法保证新 wire 校验和语义摘要，升级完成后再开放新准入。
3. 启动 Java 和各 Python 单元。基础状态、预算选择、完整链接/游标保护及回读由核心 JAR 与模型 worker 独立提供，不需要新增 Provider、业务包或模型服务。回读要求 resource.read 且固定工具集合提供 context.read_result。先运行“查询后计算并解释来源”的多轮任务，检查答案、完整 URL/Ref、resource.read 的可见 nextOffset，以及内部 snapshot 的 taskState/historySelection/compressionState；业务 REST 的 run-snapshot 不返回这些内部字段。再回读超过三页的历史结果，或使用 selector=/records/0 读完整记录；建议分页 limit=1024，放不下时明确拒绝。连续重复不可变读取达到阈值时返回 TASK_NO_PROGRESS；同参数刷新与新用户输入不按此规则停止。
4. 按需启用只读能力发现：在已有 broker.providers 后追加 capabilities，登记 `cube.service.harness.skill.CapabilityInspectProvider`，其 config 指向与 capability 调用一致的私有允许名单。发布 `harness-skill/packages/capabilities-v1.zip`，将实际 ready release 加入租户 policy，并在身份、部署上限及 policy 中授予 capability.read。Runner、业务包和此工具均须按正常流程授权/激活；示例参数为 `{"skill":"fhe/patrol"}`。返回逻辑档案和就绪状态，查询不触发视觉推理；ready 不保证即时容量。业务包原有依赖无需重发，只有希望默认选中该工具时才调整业务选择。此工具用于允许它的模型工具循环；fixed-patrol-v1 的规划视图仍只提供 common.data.query，默认业务接入通过 task-profiles 的 ready/reason 检查状态。
5. 模型单元配置显式 nativeTools，Java adapter 必须一致。先以原字节预算启动，检查 budgetMeasurement.accuracy=estimated。若有已验证 tokenizer/聊天模板及可信计数器，再安装对应计数器类、注册 model.token.counter.classes 并发布独立配置版本；没有匹配计数器会拒绝执行。无需为当前升级安装 tokenizer 依赖。实际模型质量与目标环境计数一致性在部署后验证。
6. 需要模型语义交接摘要时，另发布新 execution version，将 semanticSummary.enabled=true，保留 maxCalls=1、outputReserve=512、maxSummaryBytes=4096。运行足以触发历史归档的多轮任务，检查 model outcome 的 summary audit、contextSummary 的用户/来源摘要、intent/resultHash 和后续快照恢复。摘要按现有模型计费及排队，失败可回退，unknown 保留容量并停止；不需要新增 Python 角色或一对一实例。确定性压缩可保持摘要关闭独立部署。

回退时再次关闭新准入、drain 并对账，再恢复成套 JAR、Python 运行代码、旧配置与 policy/binding。已固定新回读工具或新计数模式的任务先收尾，不能交给不认识这些配置的旧运行时；unknown 与未决 journal 保留，不通过清空账本恢复容量。本升级无数据库 schema 迁移。

## 部署组成

Java 构建、测试与运行使用 **JDK 8**。common、service、dispatcher、Harness 核心与技能扩展须配套；视觉桥接需要同 Kernel/JVM 的 Auth/AIGC 与 FileStorage。普通 Python model/Runner 使用独立 Python 3.11 环境。

一个 Harness JVM 支持多个 Python 服务单元，同一角色可有多个；每个单元独占 workerId、实例身份、runtimeDir、journal 和租约。调度按角色、能力、健康与实际容量选择，共享物理后端统一计容。跨机器配置见 [REMOTE-NODES.md](REMOTE-NODES.md)。

| 产物或配置 | 安装/生效方式 |
| --- | --- |
| `cube-service-harness-3.0.jar` | 宿主 cellets/，与技能扩展配套更新 |
| `cube-service-harness-skill-3.0.jar` | 宿主 libs/，提供 Provider 与 WorkflowGuard |
| Python `harness/runner_process.py`、`harness/runner_sdk.py` | 更新全部普通 Runner 节点并重启，根入口和 SDK 派生入口统一 UTF-8 |
| Python `harness/model_adapter.py`、`context_compaction.py`、`context_summary.py` 及 `contracts/` | 用完整 Python 节点 ZIP 更新全部模型单元；同步实际 wire 渲染、预算重建、可选摘要和五次 wireAttempt 契约 |
| `scripts/evaluate_traces.py`、`quality_evaluation.py`、`replay_trace.py` | 同目录部署的离线评测工具；v2 证据与基线比较见 [TRACE.md](TRACE.md)，不需新增在线服务 |
| data、processing、capability、capabilities、time 工具 ZIP | 管理员发布 executable 工具，授权其实际 ready release |
| Provider、guard、身份及视觉配置 | 合并到私有绝对路径与宿主配置，重启生效 |
| 签名业务资源 ZIP、policy、binding | 业务包锁定真实依赖，发布 policy 后 CAS 切换，新 run 生效 |
| 业务系统报告与来源页面 | 读取规范 report、校验来源关联，鉴权展示固定版本原图 |

## 构建与安装

在 `cube-server/` 执行；预先准备匹配的 common/service/dispatcher 及基础依赖：

```powershell
ant -f service-harness/build.xml build
ant -f service-harness-skill/build.xml build packages
ant -f service-harness/build.xml stage "-Dstage.dir=C:/releases/harness-business"
ant -f service-harness-skill/build.xml stage "-Dstage.dir=C:/releases/harness-business"
```

staging 合并核心与技能扩展，不自动启动或发布。升级前关闭新准入、drain 并对账未决任务，保留原 JAR、配置、release/policy/binding；同时安装两个 JAR，移除重复旧副本，合并配置后重启。模板不覆盖私有值。在途任务固定旧 schema 或旧能力时须先收尾，再升级 Provider 与工具。

## Provider 与交付检查

将 [data.properties.example](../service-harness-skill/config/data.properties.example)、[capability.properties.example](../service-harness-skill/config/capability.properties.example)、[processing.properties.template](../service-harness-skill/config/processing.properties.template)、[time.properties.template](../service-harness-skill/config/time.properties.template) 复制到私有目录。按实际需要把以下登记项合并到宿主配置，保留原有 Provider 与权限：

```properties
broker.providers=data,processing,capability,time
broker.provider.data.class=cube.service.harness.skill.DataQueryProvider
broker.provider.data.config=C:/private/harness/data.properties
broker.provider.processing.class=cube.service.harness.skill.DataProcessProvider
broker.provider.processing.config=C:/private/harness/processing.properties
broker.provider.capability.class=cube.service.harness.skill.CapabilityInvokeProvider
broker.provider.capability.config=C:/private/harness/capability.properties
broker.provider.time.class=cube.service.harness.skill.TimeProvider
broker.provider.time.config=C:/private/harness/time.properties
execution.permissions=skill.list,skill.activate,resource.list,resource.read,resource.search,data.query,data.process,capability.invoke,time.read,artifact.write_text
broker.completion.guard.class=cube.service.harness.skill.WorkflowGuard
broker.completion.guard.config=C:/private/harness/workflow.properties
```

将 [workflow.properties.template](../service-harness-skill/config/workflow.properties.template) 安装到对应绝对路径，内容为 `workflow.max.repairs=2`；Linux 使用实际绝对路径。guard 是 final 检查扩展，不加入 broker.providers。两个 JAR 不匹配或类/文件配置错误时启动拒绝。

工具执行、files/grant、普通 Runner 与可信可执行包配置按 [TOOLS.md](TOOLS.md) 启用。部署权限上限、可信身份和租户 policy 均须允许相应操作。上传业务资源包不会安装 Java 类或 executable 工具，也不会提升权限。

## 发布身份、数据源与视觉路由

远程资源包必须签名，且不能包含 executable 工具。以下为需替换的管理身份示例，四类白名单独立于业务 policy，默认不开放：

```properties
admin.release.subjects=forest-domain/10010
admin.policy.subjects=forest-domain/10010
admin.source.subjects=forest-domain/10011
admin.source-grant.subjects=forest-domain/10011
admin.tenant.forest-domain.signers=f-author
runtime.signer.f-author=<Base64 X.509 DER RSA public key>
admin.sources.allowedOrigins=https://forest.example.internal
admin.sources.allowedPaths=/api/patrol/records,/api/media/content
admin.sources.secretRefs=forest-source
```

HTTP 模式分别登记不可变 source 与对话用户 source-grant，在 data/capability 私有配置中设置与业务系统验签器一致的 secret；详见 [接口约定](../../forest/ForestHanressEngineeringAPIRef.md)。仅上传模式无需 HTTP source，但须绑定 JSON 与全部图片 Input。

真实视觉 Unit 上线并预热、隔离算力后，在 AIGC 私有 aigc.properties 中设置 `harness.media.dedicated=true` 与实际 `harness.media.units`，capability 的允许路由须一致。只声明已就绪的 connector 和分析 profile；任务创建会预检包内全部声明，调用时仍重查授权与容量。

人员、车辆等检测使用 `SpecializedDetection`、`adapter=specialized-detection`、`option.profile=thing`，并声明实际支持的 classes（例如 person、car、truck、bus、motorcycle、bicycle、train）。describe 使用 OmniMultimodal，烟火使用 SpecializedDetection/fire-smoke；同名业务 profile 不代表同一底层能力。部署前确认 thing 的权重、类别、容量和状态已经就绪，不能只修改业务别名。结构化检测读取真实 resultPayload.refs[].boxes；空 answer 不能解释为无目标，检测数量也不直接表示唯一人数或业务异常。

## 发布业务包与页面

1. 发布匹配 Provider/schema 的 data、processing、capability、time 工具 ZIP，确认实际 ready digest；同名同摘要且已获准的 release 可复用。工具源码与构建见 [技能工具包](../service-harness-skill/README.md)。
2. 将四个获准 digest 提供给业务包作者。包内包含 SKILL、规则、连接器、加工、分析、工作流与报告资源。只上传时同步移除 HTTP 连接器、对应加工 profile 与 workflow 路线；教程 HTTP/snapshot 同名技能一次只选一种。
3. 在 `forest/` 构建新版本，版本与输出文件名按实际发布指定：

```powershell
python -B scripts/build_package.py --dependency common/data=<获准digest> --dependency common/capability=<获准digest> --dependency common/time=<获准digest> --dependency common/processing=<获准digest> --version <新版本> --output build/fhe-patrol-candidate.zip
```

4. 经 admin/packages/validate、RSA 签名发布、policy 发布与 CAS binding 切换。policy.skillReleases 包含业务包及四个依赖，合并权限和配额；同名技能只固定一个 release。任何包内文件变更都会改变摘要，须重新构包、签名，不修改已发布 ZIP。
5. 业务系统使用 [render-report.js](../../tutorials/examples/render-report.js) 展示 result.result.report，按 citation 的 artifactId/hash 获取可信来源并鉴权取图。未声明报告要求的包保留原文本展示。

## 人工运行顺序

长视觉调用需一起更新 `cube-service-harness-3.0.jar`、`cube-service-harness-skill-3.0.jar`、AIGC 宿主的 `cube-service-3.0.jar`，以及 Python `harness/model_adapter.py`、`runner_worker.py`、`runner_process.py`、`runner_sdk.py` 和 `harness/contracts/`。宿主负责落库物理派发标识；只升级 Provider 无法补齐这段证据。JVM 继续使用 JDK 8。

在普通 tool Runner 的 `runner` 配置中显式设置 `"providerAwait": true`，它才会协商 `provider-await-v1`。然后可将 capability 私有配置设为 `capability.timeout.millis=60000`、`capability.queue.timeout.millis=20000`。默认仍为 20000/0；执行范围为 100..240000，槽等待为 0..40000 毫秒。Runner 等待上限为 `max(30000, 执行+排队+5000)`，最多 290000，并受任务剩余期限约束；准备、排队、派发和收尾共享签名 wire 期限，Provider 预留 1 秒交付时间。sandbox 保持 30 秒上限且拒绝此开关。旧 Runner 不领取超 30 秒作业；不能靠延长 Provider 单独升级。

工具作者包仍为 `*-v2.zip`，新版一般巡检资源包为 `general-patrol-v5.zip`，林业作者包默认 `fhe-patrol-v6.zip`（已有历史 v5 保留）。新版报告要求为 version=2，默认空 assessmentPolicy，只交付观察和需复核判断；明确业务条件后按 [证据策略](WORKFLOW-REPORTS.md) 配置、升版本构包。重新验证并以实际作者密钥签名发布，更新依赖 digest、policy.skillReleases 与 CAS binding；本地作者 ZIP 和评估签名不是生产作者签名。保留原业务包和历史评测，不能用当前源码覆盖旧版本 ZIP。

升级顺序：先关闭新准入、drain/对账，安装配套核心/技能 JAR；逐节点升级全部 JSON/原生工具 model worker，保留各自 workerId、实例目录与 journal；使用原默认名称模式跑通已有任务后，再签名发布 v2 报告要求的新业务 release/policy。若要试用可读工具名，最后发布新的 ModelProfile 版本，添加 generation.toolNameStyle=readable-v1；所有可接单 worker 必须已升级，不能在混合版本下开启。现有 profile 省略此项仍使用 hashed-v1，配置见 [模型与执行器](config/harness-execution/README.md)。

这三项能力可分别使用：离线评测独立运行；工具描述和通用提示随核心/模型适配器更新；证据条件由新版技能 JAR 加业务包 v2 显式启用。没有新增外部 judge 服务、凭据、数据库迁移或预算放宽。旧业务要求 v1 兼容运行，但仅规则引用仍会标记需复核。

回退时关闭新准入，先 drain/对账；先切回获准旧业务 release 与旧 ModelProfile，再回退配套二进制及配置。旧技能 JAR 不能读取报告要求 v2，因此必须先收尾所有固定新版 release 的任务。保留原在途任务与 unknown 预留，不删除账本或更换 runtimeDir 释放未知槽。历史评测不回写；部署跑通后再按冻结任务集开展真实基线/候选对比。

固定流程由业务包的 `workflow-requirements.json` 显式启用，旧包继续使用原模型循环。例如：

```json
{"execution":{"version":"fixed-patrol-v1","maxPages":64,"bindings":[
  {"profileId":"objects","attribute":"object-presence","dimension":"presence","content":"检测可见人车，仅返回观察"},
  {"profileId":"describe","attribute":"scene-description","dimension":"visible-scene","content":"描述可见场景，不推断授权或身份"}
]}}
```

此段合并到原工作流配置，保留 pipelines。绑定必须覆盖全部必需分析；属性、Unit、profile 和量纲交叉校验。宿主只向规划模型提供已授权查询及固定连接器 schema，范围与上传 ID 仍由用户输入确定。确认第一页后按完整 cursor 链、加工、逐记录分析、报告校验推进，每步计入原 32 次工具预算；同样执行权限、来源授权、审批、取消和期限校验。固定流程不增加模型轮数或令牌预算，不能把唯一人数、身份、业务授权或跨帧跟踪绑定到普通检测能力。

报告逐字复制确认的 answer/payload，并附 `semanticReview`。存在性、单图框数、唯一计数和业务判断分别表示；未知唯一数为 null。重叠框、小目标、烟雾/云雾、通行区解释及身份/行为推断进入有界复核提示，原始观察不改写。当前复核为确定程序检查，新增模型调用为 0，`semanticallyVerified=false`；需要人工核实的发现不能据此宣称已完成事实认证。

1. 启动业务系统两个只读接口，或上传并 complete JSON 与图片；启动智能体系统、多个已授权 model/Runner 与视觉 Unit。
2. 检查 service 健康与就绪，再以实际对话用户查询 dispatcher `GET /harness/v1/task-profiles`，确认模型、trusted-cpu、技能与能力可用，随后开启接单。
3. 用唯一 Idempotency-Key 向 dispatcher `POST /harness/v1/runs` 提交小范围任务，显式 skills 选择业务技能与四个依赖，先使用一两页资料。上传模式在 attachmentIds 中绑定两类 Input，instruction 指明快照 inputId。
4. 轮询 runs/events 至终态，查看 workflow.repair 与真实缺项。只有 succeeded 且 executionSettlement=confirmed 才作为成功答案；失败、取消、unknown 保留原事件。
5. 检查 workflow.satisfied、report 的记录统计、逐记录观测、判断、时间信任标记与原图；空资料应有真实空 dataset 报告。保存版本、digest、policy target、runId 与现象，作为后续实际环境质量、性能与故障验证依据。

一般巡检作者包与评估入口见 [patrol](../../patrol/README.md)。核心 JAR 更新后提供授权资源目录与结构化上下文压缩，须与上述 Python Runner 更新一并部署。先跑单页中文记录，确认完整 pageRef/datasetRef/mediaRef，再检查分页、空资料和失败恢复；不通过扩大 8 轮、32 工具、2 次修复或 5 分钟预算掩盖问题。多 worker 仍使用各自身份、目录、journal 与租约，升级按节点逐个 drain、对账、重启。

## 本地验证入口

在 `cube-server/` 使用实际 JDK 8：

```powershell
ant -f service-harness-skill/build.xml test
ant -f service-harness/build.xml test-runtime test-inference test-execution test-tools test-control-files
ant -f service-harness/build.xml test-transport
python -B -m unittest discover -s service-harness/scripts/tests -v
```

回归覆盖真实签名包、SQLite/Broker、分页/来源、加工、执行检查、报告引用及恢复预算；视觉调用使用明确夹具。真实识别质量、耗时和部署容量在人工部署后验证，现场验收不作为先行安装与运行的前置条件。
