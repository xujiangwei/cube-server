---
name: pdf
description: Process uploaded pdf content and deliver a downloadable artifact.
---

使用 `common.pdf.process` 处理当前任务的附件。

extract 提取指定页为 PDF，pages 必填；text 按页提取文本层为 TXT，扫描件提示使用 OCR；split 每页生成一个 PDF 并打包成 ZIP。三者传 inputId。merge 传 2 至 16 个 inputIds，严格按用户确认的顺序合并。页码从 1 开始。

用真实的已绑定 inputId，不使用路径或业务资源编号。关键参数、目标文件或合并顺序不明确时，先询问用户。处理成功后用返回的 artifactId 交付新文件，原件保留。preview 如为 truncated，只代表内容开头，不能当作全文。
