---
name: document
description: Add a text watermark to an attached PDF and deliver a new PDF artifact.
---

# PDF 文字水印

使用 `common.document.watermark` 对本任务已绑定的 PDF 输入生成水印副本。必须使用真实 inputId，不得把文件名、业务资源编号或宿主路径当作 inputId。

未提供文件或无法确定目标文件时，请用户上传或选择；未提供水印文字时，通过任务问答请求补充。不要猜测水印内容。

必需参数是 inputId、text。可选 fileName（以 .pdf 结尾）、opacity（默认 0.18）、fontSize（默认 42）、rotation（默认 45 度）、pages（从 1 开始的页码数组，默认全部页）。水印居中，使用浅灰文字，过长时自动缩小以适应页面。支持简体中文和基本拉丁字符；不支持的字符会明确报错。

该工具创建新产物，原 PDF 保留。加密、已签名、损坏 PDF 和非法页码会被拒绝，不将失败说成处理完成。只在收到成功工具结果后，按 artifactId 展示可下载文件；不要编造下载地址。覆盖业务系统原文件需要另行使用有权业务工具。
