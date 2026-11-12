# 业务数据查询、加工与单图分析

Harness 通过业务包中的固定配置查询资料、加工记录、调用已授权的视觉 Unit，并保留可追溯来源。业务系统维护字段、规则和资源，平台 Provider 解释受限配置。部署步骤统一见 [业务能力部署指南](BUSINESS-DEPLOYMENT.md)，业务接口与前端约定见 [业务系统接入参考](../../forest/ForestHanressEngineeringAPIRef.md)。

## 工具与业务配置

| 工具 | 包内资源 | 作用 |
| --- | --- | --- |
| `common.data.query` | `connectors.json` | 受限 HTTP 分页查询或读取本 run 绑定的 JSON 快照，返回记录、pageRef、mediaRef 和续页游标 |
| `common.processing.process` | `data-processing.json` | 按固定 profile 过滤、精确去重、分组聚合，生成可分页读取的 datasetRef |
| `common.capability.invoke` | `analysis-profiles.json` | 使用真实 mediaRef 调用获准 Unit，返回原始分析结果和来源产物 |
| `common.time.query` | 业务时区约定 | 获取当前时间或转换带偏移的时间，辅助构造查询范围 |

资源必须列入业务包 manifest，随签名 release 固定到 run。工具不接受任意 URL、宿主路径、加工代码或伪造的记录、来源引用。SKILL 加载器只读正文；需在正文中指导模型显式读取配置并调用工具。

## 查询与分页覆盖

HTTP 连接器使用部署方批准的 source、origin、路径和身份映射，查询及取图均检查实际用户授权。data 与 capability 私有配置中的 `secret.forest-source` 与业务系统验签器共享，不放入业务包。上传模式使用已 complete 且绑定本 run 的 JSON 与图片 Input，只能覆盖上传资料；拍摄时间默认标记为 `provider-declared`。

每个 `pageRef` 代表当前 run 内已确认成功的查询页。沿 `nextCursor` 读取后续页，再按遍历顺序把 pageRefs 交给加工工具。所有页须来自同一业务 skill、connector、查询和 snapshot。缺起始页、末页、跳页或顺序错误时 `coverage.queryComplete=false`，并返回 `QUERY_PAGES_INCOMPLETE`；混合查询或快照直接拒绝。

`complete` 和 `queryComplete` 描述查询页链的完整性，不代表每张图都已分析，也不代表整个业务区域没有异常。逐记录分析要求由 [执行检查与报告](WORKFLOW-REPORTS.md) 声明和核对。

## 数据加工

业务配置格式为 `{version:"1",profiles:[...]}`。每个 profile 声明 id、connectorId、inputSchema、filters、deduplicate、groupBy、aggregates；字段名引用连接器投影后的记录。例如：

```json
{
  "version": "1",
  "profiles": [{
    "id": "region-summary", "connectorId": "patrol-records",
    "inputSchema": {
      "type": "object",
      "properties": {"locationId": {"type": "string", "minLength": 1, "maxLength": 128}},
      "required": ["locationId"], "additionalProperties": false
    },
    "filters": [{"field": "locationId", "operator": "eq", "parameter": "locationId"}],
    "deduplicate": true, "groupBy": ["locationId", "locationName"],
    "aggregates": [{"name": "recordCount", "operation": "count"}]
  }]
}
```

filters 按 AND 执行，每项在固定 value 与 parameter 中二选一；参数须在 inputSchema 声明，调用的 argumentsJson 也须通过校验。支持 eq/ne/in/exists、数值 gt/gte/lt/lte，以及带偏移 ISO 8601 时间的 before/after/atOrBefore/atOrAfter。时间按实际 instant 比较；缺失或 null 的比较字段排除并披露，exists 判断非 null 值是否存在。

精确去重使用 `recordId + recordVersion + mediaId + mediaVersion`，内容还须一致；同身份异内容报 `DATA_PROCESS_RECORD_CONFLICT`。缺记录版本时不合并并返回 warning。不同版本、媒体与连续帧独立保留，不推断跨帧身份或唯一人数。

groupBy 最多四个字段。聚合支持 count、sum、min、max；后三者声明 field，以 BigDecimal 精确计算并返回十进制字符串。缺失值不当 0，无有效值返回 null 并记录 missingValues，非法数值失败。空数据且无 groupBy 时仍返回 count=0 的全局汇总。count 是过滤、去重后的记录行数。

加工返回不可变 datasetRef、stats、coverage 和首批 itemsJson；`action=read` 按 offset 续读，view 选择 records 或 groups，`nextOffset=-1` 表示该视图结束。records 每项包含 record 和完整 sources，保留 pageRef、mediaRef、媒体 ID/版本、原页行号及时间信任级别；groups 保留分组键、聚合值、缺失值计数和指向完整 records 视图的零基 recordIndexes。HTTP 来源授权在加工与读取时均重查。

以下参数属于任务内工具调用：

```json
{"action":"process","skill":"fhe/patrol","profileId":"patrol-summary","pageRefs":["<第一页pageRef>","<第二页pageRef>"]}
```

```json
{"action":"read","skill":"fhe/patrol","profileId":"patrol-summary","datasetRef":"<真实datasetRef>","view":"records","offset":0,"limit":8}
```

每次最多 64 页、1024 行、128 组，持久结果最多 1 MiB。工具结果受 12 KiB 与 Runner 封装预算约束，默认每页最多 8 项，limit 为 1～16，并按字节预算缩小返回页；必须跟随 nextOffset。单项超预算明确失败，不截断来源。

## 单图分析与容量

分析输入为一张 JPEG/PNG，最多 1,048,576 字节；默认像素上限 4,000,000，由私有配置调整。平台验证真实格式、解码尺寸和字节摘要；不静默缩放、换图或跳过资料。当前链路不支持视频或 RTSP。

调用使用加工记录 sources 中的真实 mediaRef。视觉桥接要求 Harness、Auth、AIGC 在同一 Kernel/JVM，且 FileStorage 可用；分 JVM 未装配桥接时返回 `CAPABILITY_NOT_READY`。部署方须为已预热的真实 Unit 隔离算力，再启用 `harness.media.dedicated=true` 与 `harness.media.units`。同能力可有多个 Unit，同一联系人节点的不同视觉能力共用一个持久物理槽位；增加 profile 不增加物理容量。与旧接口或主模型共享 GPU 的统一计容、跨 JVM 转发须另行落实。

`SpecializedDetection` 使用现有 subtasks 注册，第一项固定 ObjectDetection，后续为实际启用的 uav-fire-smoke、fire-smoke、thing。独立 specializedDetectionStatus 报告提供就绪、切片能力与会话余量，30 秒无有效报告即未就绪。发现、准入、预留和发送均核对 profile 与真实余量，详见 [专项检测协议](../docs/specialized-detection.md)。配置示例：

```json
{"version":"1","profiles":[{"id":"people-vehicles","adapter":"specialized-detection","unit":"SpecializedDetection","mediaTypes":["image/jpeg","image/png"],"optionsJson":"{\"operation\":\"detect\",\"profile\":\"thing\",\"classes\":[\"person\",\"car\"]}"}]}
```

classes 为非空字符串数组，最多 256 项、单项最多 128 字符，规范类别名由 Python 校验，未知 options 字段拒绝。图片由桥接保存到 FileStorage；检测请求只包含 unit、files、option，不携带文本 content。结果保留类别契约、模型、工件摘要和检测元信息。

## 来源、持久化与恢复

Provider 事实与数据源版本、授权、管理幂等记录存于 Harness 专库，由 StoreBootstrap 自动建表和升级；图片字节不进数据库。事实绑定租户、用户、run/job，只有已确认成功的调用生成的引用和页游标可被后续工具消费。

视觉请求先持久化 job、实例选择和 serviceSn 再发送。单图等待最多 20 秒，普通工具上限仍为 30 秒。超时或取消不代表远端结束，无物理回执不释放预留。迟到响应仅对账回执、用量和清理，不重写历史回答；落库失败只重试写回执，不重新推理。JVM 丢失且缺物理终止证据时保留 unknown、预留和仍可能被 Unit 消费的暂存文件，按 [恢复规则](RESILIENCE.md) 处理。

可信来源使用 UTF-8 文本产物 `media-source.json`。服务器从文件账本关联真实 Provider job 推导 origin.operation/jobId/confirmed；业务系统只接受已确认 `common.capability.invoke.v1` 的来源，不能仅按文件名信任模型自行写的文件。同一调用重放复用原产物。来源展示与报告引用见 [业务接口参考](../../forest/ForestHanressEngineeringAPIRef.md)。
