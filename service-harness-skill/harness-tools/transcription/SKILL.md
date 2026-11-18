---
name: common/transcription
description: 将已绑定的 WAV/MP3 音频转成文字。
---

使用 `common.transcription.recognize`，从当前任务获取真实 `inputId` 和 MIME（WAV 为 `audio/wav`，MP3 为 `audio/mpeg`）。音频上限 10 MiB、5 分钟。`text` 为预览，有文字时用 `artifact` 下载完整 TXT。`durationSeconds` 是音频总时长；当前返回不包含时间戳、说话人和逐段对齐。
