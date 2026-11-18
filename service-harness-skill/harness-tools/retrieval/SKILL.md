# 通用业务资源检索

使用 common.retrieval.query，skill 为当前已激活的业务技能。业务技能的 retrieval-profiles.json 声明资源类型、时间字段、区域来源、筛选条件及获准接口。

action=plan 从账本中的真实用户输入构造任务，代码解析时间、区域并进行语义覆盖检查。不能另传用户原话、日期、区域ID或URL替代真实输入。返回 needs_input 时按真实问题提问，不得自行补齐或调用 search。

action=search 使用同一任务已确认的 planRef；后续页只提交返回的 cursor。每页均要核对 complete 和 nextCursor，不能将单页当作完整结果。资源引用只证明检索到对应元数据，不表示内容已下载、播放、分析或验证。

固定 resource-retrieval-v1 流程由宿主推进规划、问题、分页及结果，模型不需要重复调用工具。用户调整输入会使旧计划失效；当前权限仍须在业务接口独立检查。
