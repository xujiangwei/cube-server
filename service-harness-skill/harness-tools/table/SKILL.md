---
name: table
description: Process uploaded table content and deliver a downloadable artifact.
---

使用 `common.table.process` 处理当前任务的附件。

读取 UTF-8 CSV 或 XLSX，首行为列名；XLSX 默认首个工作表。先按 filters 全部条件筛选，再按 deduplicateBy 留第一条，然后 sort 按文本排序，最后投影 columns 和限制行数。省略 format 输出 CSV。XLSX 取缓存值，不执行公式或外部链接。

用真实的已绑定 inputId，不使用路径或业务资源编号。关键参数、目标文件或合并顺序不明确时，先询问用户。处理成功后用返回的 artifactId 交付新文件，原件保留。preview 如为 truncated，只代表内容开头，不能当作全文。
