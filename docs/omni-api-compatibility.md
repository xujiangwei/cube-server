# Omni 接口适配检查（2026-11-07）

对照配套 Python 工作区当前版本（包含尚未提交的修改）的 `cube/omni_api.py`、
`cube/aigc_service.py`、四个 Omni 单元及 `docs/omni-api.md` 检查。
结论：Java 的 SHM action 与响应外壳无需改变，但请求处理、Harness 调用和 REST 示例需要调整。

## 请求变更

| 旧格式 | 新格式 |
| --- | --- |
| OmniGround `detect` | `detection` |
| `ground_single` / `ground_multi` | `grounding_single` / `grounding_multi` |
| `ground_text` / `detect_text` | `text_grounding` / `text_detection` |
| `ground_gui` | `gui_grounding` |
| `option.mode/categories/lang/bboxList/targetRegionIndexes` | 对应字段放入 `option.parameters` |
| GUI 的 `option.output="point"` | `option.parameters.outputType="point"` |
| `option.outputUrl` / `option.callbackUrl` | `option.output.url` |
| `option.callbackHeaders` / `option.intervalSeconds` | `option.output.headers` / `option.output.intervalSeconds` |

Python 不接受旧字段别名。Java REST 原样传递新的 `option`，不会自动迁移外部客户端的旧请求。
`option.operation` 使用精确的小写值。非 RTSP 默认 `analyze`；RTSP 默认 `start`。
OmniVL、OmniMultimodal 的任务为 `understanding`。

控制请求的 `option` 仅接受 `operation/streamId`；`status/stop` 的 `content` 可省略或为空，
`update` 必须提供新提示词，且仅 OmniVL、OmniPerception 支持。
控制请求的 `files/history` 可省略或为 `[]`，不能复用启动时的 `task/parameters/output`。

## Java 调整

- `MultimodalInput` 支持省略 `content`，仍拒绝显式 `null`；拒绝核心 Omni 请求的未知顶层字段，防止旧顶层 `task` 被静默丢弃。Java 自用的 `recordable` 不会发送给 Python。
- Omni 的 `content` 作为提示词传递，其中的 URL 不再触发 Java 下载。
- Java REST 的媒体入口仍为 `files` 中的文件码或 HTTP(S) URL；HTTP(S) 媒体先由 Java 下载，再以 FileLabel 发送给 Python。Python 直接调用支持的本地路径和 FileLabel 不属于这个 REST 入口。
- 文件查找失败返回 `NotFound`，媒体 URL 下载失败返回 `FileError`，不会静默移除附件后继续推理。RTSP 地址按大小写无关方式识别。
- Python 返回的 `InvalidParameter/NotFound/Processing/UnitNoReady` 等错误码保留到 Java 服务应答；dispatcher 沿用现有执行失败 HTTP 400 行为。
- Harness 的分析档案保持自身现有 schema，发送时将 Perception 的 `task/lang` 转为 `operation=analyze`、`task` 和 `parameters.lang`；OmniMultimodal 使用 `understanding`。SpecializedDetection 保持独立协议。
- REST 源码文档和 dispatcher 随附文档同步更新了 28 个请求示例。

`response/thought/resultPayload/performance` 的 Python 响应格式仍可由 Java 现有解析器读取；
REST 文字字段仍为 `answer`。流会话控制仍按创建会话的物理单元路由。
新增资源预算配置属于 Python 单元配置；生命周期新增的 `eventId` 不改变事件名与 `streamId`，
Java 的停止处理可重复调用。

## 验证

- 使用真实 JDK 8（Corretto 1.8.0_504）编译 common 804 个、service 355 个、dispatcher 206 个 Java 源文件，以及 Harness skill 的 15 个生产源文件和新增测试。
- `MultimodalStreamRoutingTest`：119 个断言通过，覆盖省略字段、嵌套输出、URL 提示词、RTSP 地址、错误码以及原有会话归属和路由回归。
- `OmniOptionsTest`：9 个断言通过，覆盖 Harness 到 Omni 的参数转换及 SpecializedDetection 协议隔离。
- 配套 Python `test_omni_api.py`：17 项测试通过。
- 28 个 REST 请求示例全部通过 Python 实际 `normalize_envelope/normalize_call` 校验；其中 4 个 HTTP 媒体示例按 Java 的转换方式替换为 FileLabel 夹具后校验。
- REST 文档构建通过，生成 110 个接口；文档阅读器 5 项测试通过。

验证使用本地协议夹具，没有进行真实 GPU 推理、摄像头或在线 SHM 联调，也没有部署服务。
构建诊断保存在工作区 `reports/omni-api-20261107/build/`。
上线需同时更新 common、service、dispatcher 文档资源和使用能力调用工具的 service-harness-skill；
外部客户端应同步迁移旧任务名与选项结构。
