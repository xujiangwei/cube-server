# Console AI 用量数据源配置

示例文件：[ai-usage-sources.json.example](ai-usage-sources.json.example)。

逐字段说明、密码环境变量、数据库权限及部署方式见 [AI 用量文档：配置文件格式与字段说明](../../docs/ai-usage.md#配置文件格式与字段说明)。

复制示例为 Console 进程工作目录下的 `config/ai-usage-sources.json`，保留实际使用的数据源并填写真实库地址。该文件是 JSON 数组，不支持注释；修改后重启 Console。

Console 只读查询，计量表由 service / service-harness 自动创建。
