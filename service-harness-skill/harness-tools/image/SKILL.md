---
name: image
description: Process uploaded image content and deliver a downloadable artifact.
---

使用 `common.image.process` 处理当前任务的附件。

对 JPEG/PNG 做转换、压缩、裁剪和缩放。crop 以自动旋正后的左上角为原点，单位像素；maxWidth/maxHeight 等比只缩小；maxBytes 为输出字节上限，必要时缩小。quality 只用于 JPG。

用真实的已绑定 inputId，不使用路径或业务资源编号。关键参数、目标文件或合并顺序不明确时，先询问用户。处理成功后用返回的 artifactId 交付新文件，原件保留。preview 如为 truncated，只代表内容开头，不能当作全文。
