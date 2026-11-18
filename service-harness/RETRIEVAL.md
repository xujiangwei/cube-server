# 任务结构化与业务资源检索

`common.retrieval.query` 将真实用户输入解析成检索任务，校验条件后调用获准的业务接口。业务包可配置图片、录像、文档或其他媒体类型；`resource-retrieval-v1` 由宿主推进规划、主动问答、分页和结果交付。部署顺序见 [业务能力部署](BUSINESS-DEPLOYMENT.md)，可运行示例见 [教程示例](../../tutorials/examples/README.md)。

## 任务与输入版本

结构化任务包含 `intent=retrieve`、`profileId`、`resourceType`、`time`、`region`、`keywords`、`filters`、`inputRevision` 和输入摘要。例如“调出今天下午3点到4点的厂区1内的巡检录像”，在业务时区当天 16:00 后接收、目录含厂区1且录像档案可用时，得到：

```json
{
  "schemaVersion": 1,
  "intent": "retrieve",
  "inputRevision": 0,
  "profileId": "patrol-videos",
  "resourceType": "video",
  "time": {"from":"2026-11-17T15:00:00+08:00","to":"2026-11-17T16:00:00+08:00","zone":"Asia/Shanghai","mode":"overlap"},
  "region": {"id":"plant-1","name":"厂区1","scope":"subtree"},
  "keywords": "",
  "filters": {}
}
```

模型不能另传原话、日期、区域 ID 或 URL 替换用户输入。Provider 从账本读取本次 run 的原始消息、接收时间和已持久化的问题/回答。结构化选项必须来自对应旧问题，服务再次核对选项与业务包摘要。初始历史仅作为模型对话上下文；检索流程不会猜测上一 run 的隐含条件，应重新提供检索范围。

`plan` 返回 `ready` 或 `needs_input`，保存不可变 `retrieval-plan` 事实。`search` 只接受本 run 已成功确认的 `planRef` 和返回的不透明 `cursor`。新输入使旧计划失效；旧工具结果可以完成对账，但不能用于新输入版本的交付。宿主在 run 租约、版本和输入水位检查下采纳输入，消息 `applied` 同时适用于模型上下文与可信宿主规划。

## 时间与区域

时间语法有明确边界：

- 日期支持今天/今日、昨天/昨日、前天、明天/明日、后天、`YYYY-MM-DD`、`YYYY年M月D日`。
- 时段支持带上午/下午等说明的中文数字、点/半/一刻/三刻及 24 小时制 `15:00`。第二端继承时段，例如“下午3点到4点”为 15:00–16:00。无时段的“3点到4点”要求澄清。
- 明确两端日期可跨午夜，例如“昨晚11点到今天凌晨1点”。结束不晚于开始时不会自动猜测跨天。
- 最近/过去 N 小时、分钟、天以对应消息接收时刻倒推；相对日期使用该条消息的持久接收时间和业务时区，重启不改变“今天”。
- 仅给日期时采用全天半开区间；禁止未来且日期是消息接收当天时，截止到该条消息接收时刻。明确的未来时段要求确认/调整。日期后补时段、已有时段后更正日期均重新校验。
- 带偏移的 ISO 起止时间支持夏令时消歧；时区的时间缺口/重叠、非法日期、倒序和超出跨度均要求澄清。

区域必须命中业务目录的名称或别名，最长名称优先，并检查 ASCII 编号边界，避免厂区1命中厂区10。同名区域、多个区域或未知区域要求用户选择；当前一次任务采用一个明确区域。`exact` 仅本区域，`subtree` 包含目录中后代区域，并在返回结果中再次核对实际区域 ID。

目录支持 `inline` 和 `http`。inline 适合静态公共名称，**不代表访问授权**；业务接口始终按真实用户 ACL 过滤。http 使用获准 source/HMAC，必须完整返回该用户获准目录，`complete=true`，最多 128 项；父区域不在可见目录时应将其 `parentId` 置空。目录不完整直接拒绝，不从部分目录猜测区域。动态 ACL 在每次检索和业务内容读取时仍需检查。

## 语义覆盖检查

资源类型按配置别名选择；多档案命中或请求未配置的资源类型需要选择。双引号或中文双引号中的内容作为关键词，保留大小写；业务接口负责实际索引/全文语义，宿主只能验证传参和结构化范围。图片、文档可使用时间点筛选；录像可使用区间重叠筛选，不会漏掉开始早于检索时段但仍重叠的录像。

枚举过滤项由业务包定义，例如文档 `status=published/draft`。返回值必须属于配置枚举，已选择的值必须与结果一致。未知条件（例如未配置的“4K”）、否定/排除、播放、下载、分析等目标不会被悄悄忽略，会进入 `waiting_input`。scope 问题的回答清除旧条件，并要求明确重述完整可执行范围；调整仍含不支持条件时继续提问。补充可识别字段会保留已有字段，未解决的未知条件保留至用户回答范围问题。

当前实现是可验证的中文/ISO有界解析器，不等同于任意自然语言理解或全文内容核验。不同类型的复合任务、地理坐标范围、空间几何、自由表达的高级过滤、任意自然语言日期等需扩展语法/配置或使用专门能力；无需依赖模型猜出参数。

## 业务包配置契约

完整可用配置见 [retrieval-profiles.json](../../tutorials/examples/retrieval-package/retrieval-profiles.json)。业务包包含 `SKILL.md`、`retrieval-profiles.json`、依赖锁，以及：

```json
{"version":"1","maxRepairs":2,"execution":{"version":"resource-retrieval-v1"}}
```

依赖只需锁定获准的 `common/retrieval` release，权限为 `resource.retrieve` 及业务包所需资源权限。检索包无需巡检分析使用的 connectors、analysis、processing 和报告配置。

| 配置 | 含义与约束 |
| --- | --- |
| `version`, `zone` | `1`；有效 IANA 业务时区 |
| `regions` | inline 的 `items`，或 http 的 sourceRef/request/response；目录项 id/name/aliases/parentId，最多128项、无循环 |
| `profiles` | 1～8 个档案，固定 id/label/resourceType/aliases/sourceRef |
| `request` | GET、固定 path 和 query；走已有数据源白名单/HMAC，不能由用户选择任意地址 |
| `request.query` | 模板变量为 `${args.from/to/regionId/regionScope/query/pageSize/resourceType/intent}`，每个变量独占整个值；自定义枚举为 `${args.filter_<id>}` |
| `time` | mode=point/overlap/none，required、allowFuture、maxRangeHours=1..8784；none 不得 required |
| `regionRequired`, `regionScope` | 是否必需；exact/subtree。映射区域时须有响应区域字段，subtree 须映射 regionScope |
| `response` | items 为 JSON Pointer；fields 投影 id/version/title/mediaType，及 regionId/start/end/summary/filter_*；point 必须 start，overlap 必须 start/end |
| `pagination` | 响应 nextCursor/snapshotId 指针，请求 cursorParameter/snapshotParameter；游标和快照绑定查询，短页/空页有游标仍继续 |
| `mediaTypes` | 获准精确 MIME 类型，不以扩展名推断 |
| `pageSize`, `maxPages` | 1..8、1..64；受工具单次和结果总预算约束，预算不足明确失败，不静默截断 |
| `filters` | 最多4项，每项 id/label/required/values，最多16枚举值（id/label/aliases）；须有请求和响应映射 |
| `vocabulary` | 业务场景词，如“巡检”；只帮助覆盖场景词，不赋予新操作或过滤含义 |

http 区域目录示例：

```json
{
  "kind":"http","sourceRef":"source-my-business-system@1",
  "request":{"method":"GET","path":"/api/resources/regions","query":{}},
  "response":{"items":"/data/items","complete":"/data/complete","fields":{"id":"/id","name":"/name","aliases":"/aliases","parentId":"/parentId"}}
}
```

接口响应必须为 JSON、同一查询使用稳定 snapshotId；后续页重查用户授权，拒绝过期/变化快照。宿主限制单次 HTTP 响应128 KiB，工具输出受 schema 和 `result.max.bytes`（示例12288）共同限制；最终聚合结果上限256 KiB，并受已有执行预算约束。maxTools 应覆盖规划和全部页及必要的输入修订，不能只等于 maxPages。

## 结果与失败行为

成功结果位于 `result.result.retrieval`，其 `schemaVersion=1`、`format=resource-retrieval-v1`，`results[]` 每项包含 skill/release、结构化 task、resources 和 coverage。资源含 id/version/title/mediaType/resourceType/resourceRef 及映射的时间、区域、摘要和枚举字段；不返回模型伪造的播放/下载地址。coverage 标记 complete、snapshotId、pages、totalResources，只有已确认完整页链才能交付，包括完整空结果。`text` 是同一对象的 JSON 镜像。

跨页使用 id/version 去重；同键不同内容、快照改变、游标循环、超页数、越界时间/区域、错误类型和枚举均失败。关键错误包括 `RETRIEVAL_PLAN_STALE`、`RETRIEVAL_SCOPE_VIOLATION`、`RETRIEVAL_SNAPSHOT_CONFLICT`、`RETRIEVAL_CURSOR_REPEATED`、`RETRIEVAL_PAGE_LIMIT` 和 `RETRIEVAL_RESULT_LIMIT`。失败结果不能作为“没有资料”。source-grant 会在调用与交付前再次校验，业务接口负责实时 ACL。

resourceRef 是任务内的可信元数据引用，不能直接作为文件 URL。业务系统展示元数据后，按受权资源 ID/version 取内容并重新鉴权；当前通用检索工具不负责播放、下载、图片识别或文档内容分析。

## 本地验证

JDK 8 下运行 `ant -f service-harness-skill/build.xml test-retrieval`，覆盖中文/ISO 时间、日期更正、类型与区域别名、语义覆盖、结果范围校验，以及真实 SQLite、签名 HTTP、两个 Runner、宿主规划/问答、重启、输入更正和撤销授权。完整回归使用 `ant test`。业务接口示例另有 `python -B tutorials/examples/business_api/test_retrieval.py`，覆盖类型、区间重叠、子区域 ACL、关键词/枚举、分页快照与签名；这些是本地契约测试，真实数据检索效果在部署后检查。
