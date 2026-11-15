# 分析能力查询

使用 `common.capabilities.inspect` 查询当前业务 SKILL 的逻辑分析档案，参数 `skill` 必须使用固定目录中的准确名称。业务 SKILL 必须已激活并含有 `analysis-profiles.json`，当前 run 必须获授 `capability.read`。

返回 `profileId`、`mediaTypes` 与 `status`。`ready` 表示查询时存在声明了容量的兼容就绪实例，`unavailable` 表示没有就绪的兼容实例，`disabled` 表示提供方未启用该能力。状态只是就绪快照，不反映当前预留量，也不保证能立即执行。实际调用按共享容量账本选择实例。

选择匹配的逻辑 `profileId` 后，使用已获授权的 `common.capability.invoke` 和已确认的媒体引用执行分析。查询本身不执行分析，也不证明媒体内容。查询失败或不可用时说明具体缺口，不编造分析结果。
