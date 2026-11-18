---
name: report
description: Process uploaded report content and deliver a downloadable artifact.
---

使用 `common.report.process` 处理当前任务的附件。

使用内置标题、分节和图片模板生成 DOCX 或 PDF。sections 填入已确认的报告事实及来源说明；inputIds 可传已绑定的 JPEG/PNG，按列表顺序附在正文后。不得补造结论、引用或图片。

用真实的已绑定 inputId，不使用路径或业务资源编号。关键参数、目标文件或合并顺序不明确时，先询问用户。处理成功后用返回的 artifactId 交付新文件，原件保留。preview 如为 truncated，只代表内容开头，不能当作全文。
