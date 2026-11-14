# 业务能力部署指南

本文统一说明数据查询、加工、单图分析、必需步骤检查与结构化报告的安装和人工运行顺序。基础服务与 dispatcher 配置见 [服务部署](README.md)，能力契约见 [业务数据](BUSINESS-DATA.md)和[执行检查与报告](WORKFLOW-REPORTS.md)，业务资源跨环境晋级见 [发布与回退](RELEASE-MANAGEMENT.md)。

## 部署组成

Java 构建、测试与运行使用 **JDK 8**。common、service、dispatcher、Harness 核心与技能扩展须配套；视觉桥接需要同 Kernel/JVM 的 Auth/AIGC 与 FileStorage。普通 Python model/Runner 使用独立 Python 3.11 环境。

一个 Harness JVM 支持多个 Python 服务单元，同一角色可有多个；每个单元独占 workerId、实例身份、runtimeDir、journal 和租约。调度按角色、能力、健康与实际容量选择，共享物理后端统一计容。跨机器配置见 [REMOTE-NODES.md](REMOTE-NODES.md)。

| 产物或配置 | 安装/生效方式 |
| --- | --- |
| `cube-service-harness-3.0.jar` | 宿主 cellets/，与技能扩展配套更新 |
| `cube-service-harness-skill-3.0.jar` | 宿主 libs/，提供 Provider 与 WorkflowGuard |
| Python `harness/runner_process.py`、`harness/runner_sdk.py` | 更新全部普通 Runner 节点并重启，根入口和 SDK 派生入口统一 UTF-8 |
| Python `harness/model_adapter.py` | 更新 JSON 决策模型节点并重启，assistant 历史使用原决策 schema；来源标签留在权威账本及资料消息 |
| data、processing、capability、time 工具 ZIP | 管理员发布 executable 工具，授权其实际 ready release |
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

新工具作者包为 `*-v2.zip`，一般巡检资源包为 `general-patrol-v4.zip`。重新验证并以实际作者密钥签名发布，更新依赖 digest、policy.skillReleases 与 CAS binding；本地作者 ZIP 和评估签名不是生产作者签名。回退时关闭新准入，先 drain/对账；保留旧版本固定的在途任务和 unknown 预留，再切回获准 release 与配套配置。不得删除账本或更换 runtimeDir 释放未知槽。

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
ant -f service-harness/build.xml test-execution test-tools test-control-files
ant -f service-harness/build.xml test-transport
```

回归覆盖真实签名包、SQLite/Broker、分页/来源、加工、执行检查、报告引用及恢复预算；视觉调用使用明确夹具。真实识别质量、耗时和部署容量在人工部署后验证，现场验收不作为先行安装与运行的前置条件。
