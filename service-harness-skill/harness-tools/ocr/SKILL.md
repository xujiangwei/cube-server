---
name: common/ocr
description: 使用已绑定的 JPEG/PNG 图片识别文字。
---

使用 `common.ocr.recognize`，从当前任务获取真实 `inputId` 和 MIME。省略 `region` 表示整张图片；指定区域时传原图像素坐标 `x1,y1,x2,y2`，右下边界不包含。可选 `language` 为 `zh` 或 `en`。结果中的 `text` 为预览，有文字时用 `artifact` 下载完整 TXT。没有识别结果时不要猜测文字。
