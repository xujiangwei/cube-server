# 文档说明

REST API 文档中的 dispatcher 业务请求示例统一使用 `http://211.157.179.37:7010`；其他服务地址的主机统一使用 `211.157.179.37`，协议、端口和路径按实际服务保留。新增或更新示例时遵循此约定。

## 认证与响应格式

接入前先取得 Cube 用户令牌。各接口的路径参数或请求头说明了令牌的传递方式；频道、文件和任务 ID 使用接口返回的实际值。

- AIGC、触点、CV、风控及专项检测等接口直接返回业务 JSON。例如 `POST /aigc/channel/:token` 返回的 `code` 是频道码；专项检测的目标信息位于 `resultPayload`。失败时先判断 HTTP 状态码，再按具体接口读取错误信息；部分接口的响应体可能为空。
- 森林接口 `/aigc/forest/*` 返回 `{"code":0,"data":{...}}`，从 `data.resultPayload` 读取检测结果；失败时读取 HTTP 状态码、非零 `code` 和 `data.message`。
- Harness `/harness/v1/*` 使用 `Authorization: Bearer <Cube Token>`，成功直接返回任务、文件等 JSON 对象，失败返回 `errorCode`、`requestId` 和 `retryable`；下载和 SSE 按对应内容类型读取。
- 兼容接口 `/v1/*` 使用 OpenAI 或 Claude 的认证、响应及错误格式，具体见 [兼容接口](output/index.html#api-Compatibility)。

### 文件接口的响应外壳

`/filestorage/operation/*` 与文件上传接口默认返回 `{sn,name,data}` 外壳（`data` 内才是业务数据）。请求头 `x-baize-api-version: v1` 时改为直接返回业务数据；令牌可通过请求头 `x-baize-api-token` 传入而不写在请求体里。文件下载直接返回二进制内容。`/filestorage/operation/` 下的操作按 URL 后缀区分：`/list`、`/find`、`/delete`、`/modify`。

## 常见接入流程

接口中的令牌、频道码、文件码和任务序号均为示例值，调用时使用实际返回值。请求体为 JSON 时设置 `Content-Type: application/json`；URL 查询值中的中文、空格等字符应编码。若令牌关联了设备，后续请求携带相同的 `x-baize-api-device` 与 `x-baize-api-platform`。

| 场景 | 建议调用顺序 | 读取结果 |
| --- | --- | --- |
| 语音转写或说话者分析 | 上传音频 → 提交任务 → 保存 `queryCode` → 以 `qc` 查询 | `stateCode=0` 后读取 `result`；`7` 时继续等待。 |
| 语音情绪识别 | 上传音频 → 提交文件码 → 使用原文件码 `fc` 查询 | `result.emotion` 与 `result.score`。 |
| 文件处理 | 二进制上传 → 保存 `data.fileCode` → 提交自然语言处理指令 → 以任务 `sn` 查询 → 下载生成文件 | `state=0` 后读取 `result.answerFileLabels`；下载响应按二进制保存。 |
| 知识库问答 | 查询库名 → 上传并导入资料 → 等待导入完成 → 创建频道 → 提问 | 异步模式用相同 `channel` 与 `base` 查询，`code=0` 后读取 `result.record.answer` 与 `result.sources`。 |
| 量表测评 | 查询量表清单 → 生成量表实例 → 展示全部题目 → 提交作答 → 生成报告 → 查询报告 | 量表实例 `sn` 用于答题；生成报告返回的报告 `sn` 用于报告查询。 |
| 模板或综合测评 | 上传绘画 → 提交对应主题与受测信息 → 保存生成任务 `sn` → 调用同类型查询接口 | `state=0` 后展示返回的段落、章节等内容。 |
| 条形码与风控 | 条码识别先上传图片；风控修改先查询当前 `mask` | 条码遍历 `result[].barcodes[]`；风控把查询值作为 `originalMask`，按需设置或解除布尔限制项。 |

异步接口返回 HTTP 200 可能仅表示受理成功。请使用各接口规定的业务状态字段判断是否完成，处理中间隔数秒重查，失败后按接口的错误说明处理。不同模块的 `code`、`state`、`stateCode` 含义以各自文档为准，不共用一套判断规则。

各接口提供完整请求地址和请求/响应示例。长报告和量表的示例保留代表性条目，实际界面应遍历全部返回数据；所有标记为 JSON 的示例均可直接解析。文件上传下载的 curl 示例只展示本地文件读写，Windows PowerShell 中使用 `curl.exe`。

## 专项检测 SpecializedDetection

专项检测用于识别图片、视频及实时画面中的火焰和烟雾。先创建频道；图片和视频文件先上传并取得文件码，实时视频则使用 RTSP(S) 地址。图片请求直接返回检测区域；视频会话在命中目标时向指定地址推送带检测框的 PNG 图片。

| 接口 | 用途 | 生成页面 |
| --- | --- | --- |
| `POST /specialized-detection/base/{token}` | 图片火焰、烟雾检测 | [专项图片检测](output/index.html#api-SpecializedDetection-SpecializedDetectionImage) |
| `POST /specialized-detection/stream/{token}` | 视频文件或 RTSP(S) 后台检测，支持 `start/status/stop` | [专项视频后台会话](output/index.html#api-SpecializedDetection-SpecializedDetectionStream) |

`fire-smoke` 用于火焰、烟雾检测，`uav-fire-smoke` 用于无人机巡检画面，可设置 `sliced=true` 增强小目标检测；实际可用方案以服务提供方开通的能力为准。图片结果按 `refs[].content` 区分类别，按 `boxes` 绘制检测框，空 `refs` 表示未检出目标。

视频接入顺序为：准备可接收 PNG 二进制的回调地址 → `start` 创建会话 → 保存 `streamId` → `status` 查询状态与进度 → 不再需要时 `stop`。回调使用 `X-VLM-Event-Id` 去重，元数据从 PNG 的 `vlm_meta` 文本信息读取；未收到回调时应查询会话状态。

完整图片请求、视频文件与 RTSP 请求、状态响应及回调解析示例见 [专项检测接口](output/index.html#api-SpecializedDetection)。

## 覆盖范围

本文档覆盖 dispatcher 的 AIGC 业务面，以及触点、文件、风控、CV、Forest、Harness 与 `/v1` 兼容层。

以下端点已在 dispatcher 注册，但**不在本文档范围内**（内部或半内部使用，无对外承诺）：`/hub/*`、`/robodroid/*`、`/ferry/gnosis/`、`/file/media/`、`/file/source/`、`/file/steganographic/`、`/sharing/`、`/doc/api/`、`/static/`、`/aigc/module/*`（`module.rest.enabled` 控制的兜底通道，默认关闭）。

以下端点属于业务面但尚未编写条目，需要时按源码补：`/aigc/chart/`（说话人视图）、`/aigc/cot/`、`/aigc/painting/label`、`/aigc/psychology/painting`、`/aigc/psychology/report/page`、`/aigc/psychology/report/remark`、`/aigc/psychology/report/state`、`/aigc/copilot/{apply,dispose,sheet}`、`/aigc/stream/{apply,stop,caption,strategy}`、`/aigc/speech/analysis`、`/aigc/speech/diarization/opt/`、`/aigc/facial/expression`、`/aigc/context/inference`、`/aigc/component/event`、`/aigc/queue/`、`/aigc/search/result/`、`/aigc/stop/`、`/aigc/knowledge/{doc,segment,backup,article,article/category,generate}`、`/app/{chat,change,config,customer,schedule,session,evaluate,inject,keepalive,membership,user/modify,user/profile,user/signout,verify,version,asciiart,emotion,wordcloud}`、`/cv/{object/detect,estimation/pose,estimation/hand,clippaper}`。

## 多模态图片与实时视频

单次图片、视频分析使用 [多模态数据推理](output/index.html#api-Multimodal-多模态数据推理)，实时 RTSP(S) 分析使用 [流式多模态推理](output/index.html#api-Multimodal-流式多模态推理)。先创建频道，上传媒体并确认可查询后使用文件码；实时视频则直接提供视频源地址。

| 场景 | 模型与参数选择 | 读取结果 |
| --- | --- | --- |
| 图片目标、文字或 GUI 定位 | `OmniGround`，用 `task` 选择任务 | `resultPayload.refs[].boxes` 或 `points`，原图像素坐标。 |
| 视频总结、媒体问答 | `OmniVL` 分析一个视频；`OmniMultimodal` 结合媒体、问题及文字历史回答 | REST 应答的 `answer`。 |
| 图片计数、OCR、区域描述、问答 | `OmniPerception`，单张图片；区域任务提供 `bboxList` 与 `targetRegionIndexes`，整图 `vqa` 省略它们 | `answer` 与目标框；计数不提供独立的 `count` 字段。 |
| 实时视频 | `OmniGround` 提供 RTSP(S) `outputUrl`；`OmniVL` 接收 JSON 回调；`OmniPerception` 提供必填的 HTTP(S) PNG 回调地址 | 保存 `resultPayload.streamId`；回调的文字字段为 `response`。 |

实时会话全部使用 POST：`start` → 保存字符串 `streamId` → `status` 确认状态 → 接收结果 → 按需 `update` → `stop` → `status` 确认 `stopped` 或 `failed`。始终保留 `input.content`，查询和停止时传 `""`，`input.files` 传 `[]`；沿用创建会话的用户身份、频道和模型，同一频道顺序发送控制请求。`sn` 是请求流水号，不能用来代替 `streamId`。

`starting` 只表示会话创建成功，`stopping` 仍需继续查询。未收到回调时检查状态、`lastError` 与回调计数；OmniPerception 只有命中目标时才发送 PNG。接收端按 `eventId` 去重，从 PNG 的 `vlm_meta` 读取 JSON，成功保存后返回 2xx。启动和控制请求应按各模型的字段限制分别构造，不要直接复用整份启动选项。

接口条目包含计数、区域描述、OCR、问答、未检出目标的应答，以及完整实时会话和 PNG 元数据读取示例。多模态接口失败时先检查 HTTP 状态码，响应体可能为空。

## GUI 元素定位 OmniGround

使用 dispatcher 的 `POST /multimodal/base/{token}` 调用 `ground_gui`，可在界面截图中定位按钮、输入框、图标等元素。先创建频道并取得频道码，将截图上传后的文件码或服务端可访问的图片 URL 放入 `input.files`，用 `input.content` 描述目标元素，指定 `input.unit="OmniGround"`、`input.option.task="ground_gui"`。

`input.option.output` 默认为 `box`，从 `resultPayload.refs[].boxes` 读取边界框；设为 `point` 时，从 `resultPayload.refs[].points` 读取定位点。结果使用原图像素坐标，图片尺寸见 `resultPayload.width`、`resultPayload.height`。该接口直接返回业务 JSON；GUI 定位只返回位置，不执行点击或输入操作。

两种模式的完整请求及应答示例见 [多模态数据推理](output/index.html#api-Multimodal-多模态数据推理)。示例中的令牌、频道码和截图地址应替换为实际值。

## 森林场景 OmniForest

森林场景用于检测图片中的树冠，获取树冠数量和位置。接口参数及完整应答示例见 [森林场景接口文档](output/index.html#api-Forest)。

推荐调用顺序：

1. 查询森林能力，确认服务可用，并获取图片限制和默认阈值。
2. 上传一张 JPEG 或 PNG 图片，确认上传完成后保存文件码。
3. 提交文件码，等待本次请求返回检测结果。
4. 显示树冠数量，在图片上绘制检测框；失败时按 HTTP 状态码处理。

下列请求均使用 dispatcher 的业务 REST 地址。将示例中的 `http://211.157.179.37:7010` 和令牌替换为实际值；如果获取令牌时指定了设备信息，森林接口请求应携带对应的 `x-baize-api-device`、`x-baize-api-platform` 请求头。

### 第一步 - 查询森林能力

调用 [查询森林能力](output/index.html#api-Forest-ForestCapability)：

``` http
GET http://211.157.179.37:7010/aigc/forest/capability/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
```

森林接口统一返回 `{code,data}`。HTTP `200` 且 `code=0` 表示本次调用成功。能力查询成功后，使用 `data` 中的以下字段决定后续操作：

| 字段 | 调用建议 |
| --- | --- |
| `ready`、`availableCapacity` | `ready=true` 且容量大于 `0` 时提交检测；未就绪时提示服务暂不可用；已就绪但容量为 `0` 时提示繁忙并稍后查询。 |
| `formats`、`maxFiles` | 当前支持 JPEG、PNG，单次检测一张图片，多张图片分别提交。 |
| `maxBytes` | 单张图片的字节上限，默认 `20971520`（20 MiB）。 |
| `maxPixels` | 单张图片宽度乘以高度的上限，默认 `20000000`。 |
| `scoreThreshold` | 省略检测阈值时使用的默认值，通常为 `0.3`。 |

`online=true` 仅表示存在在线实例，仍需检查 `ready` 和可用容量。能力查询反映当前状态，提交检测后仍须处理服务繁忙等错误。

### 第二步 - 上传并确认图片

使用 [上传文件数据](output/index.html#api-File_Processing-上传文件数据) 接口，以二进制流上传图片：

``` http
POST http://211.157.179.37:7010/filestorage/file/?token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&filename=forest.jpg
Content-Type: application/octet-stream

<forest.jpg 的二进制数据>
```

上传接口默认使用 `{sn,name,data}` 外壳，从 `data.fileCode` 读取文件码。再调用 [查找已上传的文件](output/index.html#api-File_Processing-查找已上传的文件) 接口，确认图片已可查询后再提交检测。上传和检测应使用同一用户、同一域的令牌。

示例文件码为 `UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx`，实际调用时必须替换为上传接口返回的值。

### 第三步 - 提交树冠检测

调用 [检测树冠](output/index.html#api-Forest-ForestDetect)，请求体使用 JSON：

``` http
POST http://211.157.179.37:7010/aigc/forest/detect/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
Content-Type: application/json

{
    "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx"
}
```

首次调用建议省略 `scoreThreshold`，使用能力接口返回的默认阈值。若需要减少低置信度结果，可在请求体中增加 `"scoreThreshold": 0.5`；降低阈值则会保留更多候选树冠。阈值必须是 `0` 至 `1` 之间的数值，比较不同图片的数量时建议保持阈值一致。

检测请求体仅包含 `fileCode` 和可选的 `scoreThreshold`。默认检测超时为 60 秒，客户端和代理超时应留出余量。请求等待期间显示处理状态，收到应答后直接处理检测结果。

### 第四步 - 显示结果与处理失败

检测成功后，从 `data.resultPayload` 读取结果：

- `count`：树冠总数，用于数量展示；`data.response` 可用于显示文字说明。
- `width`、`height`：按 EXIF 方向校正后的图片宽高，单位为像素。显示图片时应使用相同方向。
- `refs[].boxes[]`：检测框列表，每个框的 `(x1,y1)` 为左上角、`(x2,y2)` 为右下角。绘图时使用 `x=x1`、`y=y1`、`width=x2-x1`、`height=y2-y1`；图片缩放显示时按返回宽高同比例换算，若有留白再加上图片显示位置的偏移。

`count=0`、`refs=[]` 是正常的成功结果，界面可显示“未检出树冠”。

失败时读取 HTTP 状态码和 `data.message`。`400` 检查请求参数；`401` 检查令牌和设备信息；`403` 检查图片归属；`404` 先确认图片上传状态。`413` 按图片限制压缩或缩小后重新上传，`422` 检查图片能否正常打开。`429` 降低并发并延迟重试，`503` 稍后查询能力再提交，`502` 或 `504` 提示稍后重试，避免连续重复提交。完整状态说明见检测接口条目。

## Harness 任务服务

Harness 支持异步任务、补充输入、审批及文件交付。全部业务接口使用 `http://211.157.179.37:7010/harness/v1` 和 Cube 用户令牌，参数与示例见 [Harness 接口文档](output/index.html#api-Harness)。

推荐接入流程：

1. 查询任务能力目录，确定可用模型、执行器及技能；省略选择时使用默认值。
2. 有附件时依次创建输入文件会话、上传内容、确认就绪，保存 `inputId`。
3. 创建任务，保存返回的 `runId`。HTTP `202` 表示已受理，后续查询任务或事件获取结果。
4. 遇到 `waiting_input` 时展示问题并补充输入；遇到 `waiting_approval` 时交由有权限的审批人处理。
5. 任务成功后读取 `result.result.text`，通过产物接口下载生成文件。调用者可按需取消任务或提交反馈。

POST/PUT 请求必须带 `Idempotency-Key`；同一次操作重试时保持键和内容一致。取消、恢复、审批前先查询任务，使用最新 `snapshot.version`。文件、反馈和诊断能力是否开放以服务提供方开通情况为准。

### 生成 API 文档

接口说明面向应用开发者，先说明用途、调用前提、参数选择、结果读取和错误处理；示例应覆盖常用场景及有意义的能力差异，并与现有实现核对。部署配置、服务内部通信、调度和存储机制应写入相应工程的维护文档。

维护字段说明时，固定结构的 `Object` 应逐项声明 `parent.child`，对象数组使用 `Object[]` 并声明数组元素的子字段；同时说明条件返回、可空值及计量单位。开放的键值对象应写明字段由谁定义、值类型和 JSON 示例，不能仅写“对象”或“扩展数据”。字段以 Java/Python 的实际序列化结果为准，复用注释时需保留正确的嵌套路径。

只需安装 **Node.js 18 或更新版本（含 npm）**，然后在本目录执行一个脚本：

```powershell
# Windows PowerShell
./build.ps1
```

```bash
# Linux / macOS
./build.sh
```

脚本自动安装锁定的构建依赖（首次运行需要联网，依赖未变时复用），解析 `src/*.js` 中的 apiDoc 注释，生成 `output/`，并同步到 `dispatcher/assets/doc/`。无需全局安装 apiDoc，也无需手工复制。运行 dispatcher 后，继续通过 **`/doc/api/`** 或 **`/doc/api/index.html`** 阅读；也可以直接打开 `output/index.html`。

页面提供分组概览和单接口详情，只加载当前接口正文。左侧搜索覆盖名称、路径、方法、摘要与参数名，支持组合查询（如 `POST detection`、`callbackUrl specialized`）、方法和分组筛选、中文输入、`↑/↓` 选择与 `Enter` 打开；`/` 或 `Ctrl/Cmd+K` 聚焦搜索，`Esc` 返回目录。已有 `#api-分组-接口名` 链接保持可用，浏览器前进、后退及刷新均可定位。

每个接口默认显示一个请求和响应示例，其他示例按需展开。页面右上角可打印当前接口，`output/print.html` 提供完整静态打印视图；跨接口查找请使用全局搜索，浏览器自带查找覆盖当前加载的正文。专项检测与其他接口统一从阅读器的分组目录进入。

仅生成预览而不更新 dispatcher 时，Windows 使用 `./build.ps1 -NoSync`，Linux/macOS 使用 `./build.sh --no-sync`。`npm run build` 与默认脚本相同，生成并同步；`npm test` 验证搜索与内容完整性，`npm run test:browser` 验证浏览器交互（默认使用本机 Chrome/Edge；其他环境可先运行 `npx playwright install chromium`，或通过 `CUBE_DOCS_BROWSER` 指定浏览器可执行文件）。

目录用途如下：

| 目录 / 文件 | 用途 |
| --- | --- |
| `src/` | 当前接口说明的 apiDoc 注释源码 |
| `reader/` | 新版阅读器、构建器与验证脚本 |
| `README.md` | 仓库内的文档说明与构建指南 |
| `apidoc.json`、`package.json`、`package-lock.json` | 当前文档元信息和锁定的构建依赖 |
| `build.ps1`、`build.sh` | 新版一键构建入口 |
| `output/` | 生成好的可发布文件，可将整个目录直接部署到静态站点 |
| `old/` | 旧版页面、主题、配置与构建脚本归档，默认构建不使用也不发布 |

`output/` 和 `dispatcher/assets/doc/` 均由构建脚本管理，每次构建会清理过期及非产物文件，确保两个目录只包含统一阅读器、接口内容、浏览器资源和完整打印页。`README.md` 仅在仓库内阅读，不构建独立 HTML。接口注释源码、构建脚本、依赖与构建清单均不复制到发布目录。请在上述源码目录中修改文档，再执行构建；不要手工编辑发布目录或在其中存放其他文件。

## OpenAI 与 Claude SDK 兼容

兼容接口提供 `/v1/chat/completions`、`/v1/messages`、`/v1/models` 与 `/v1/models/:model`。使用 Cube 用户令牌，先查询模型列表，再以返回的模型 ID 发起生成请求，无需创建频道。图片、函数工具和流式输出的可用性以所选模型能力为准。

每次请求提交完整的对话上下文。函数工具由应用执行，并将执行结果回传给模型以生成最终回答；流式调用按对应协议的结束标识判断完成。完整的文本、多轮对话、图片、函数工具往返、SSE 及两种模型列表格式示例见 [OpenAI 与 Claude 兼容接口](output/index.html#api-Compatibility)。

## 以对话方式进行绘画解读

该文档用于说明如何开发基于对话的方式进行心理学绘画测试。

以下步骤是一个标准的数据处理流程：

1. 上传绘画文件；
2. 调用 `/aigc/psychology/converse` 接口，使用该接口提交提示词和上传文件的文件码；
3. 如果提示词信息不足，`AiXinLi模型`会引导补全信息，这个过程是一个正常的对话交互过程，使用 `/aigc/psychology/converse` 接口；
4. 进入推理流程后，UI应该进入等待状态，UI程序轮询 `/aigc/psychology/report/part` 接口获取报告状态和数据。
5. 使用返回的报告内容数据填充到UI程序。


### 第一步 - 上传绘画文件

上传文件调用 [上传文件数据](http://211.157.179.37:7010/doc/api/index.html#api-File_Processing-%E4%B8%8A%E4%BC%A0%E6%96%87%E4%BB%B6%E6%95%B0%E6%8D%AE) 接口。

上传文件后使用 [查找已上传的文件](http://211.157.179.37:7010/doc/api/index.html#api-File_Processing-%E6%9F%A5%E6%89%BE%E5%B7%B2%E4%B8%8A%E4%BC%A0%E7%9A%84%E6%96%87%E4%BB%B6) 接口查询文件状态，确保文件已正确上传到服务器。


### 第二步 - 提交提示词和文件码

使用 [互动对话](http://211.157.179.37:7010/doc/api/index.html#api-Psychology-%E4%BA%92%E5%8A%A8%E5%AF%B9%E8%AF%9D) 同时提交用户的提示词和文件的文件码。

请求数据如下所示：

``` json
{
    "channelCode": "mnxRSaLdPuHEVaKA",
    "context": {
        "fileCode": "yEryPsWMYphCHatmTRmoPMvlSKqyfJCFkchtgjgFYVfKQaAzmnbVHHboRYwquJJY"
    },
    "relation": {},
    "query": "画里能看出什么心理状态"
}
```

**提示 1**：`relation` 不可省略。该接口按是否提交 `relations` 数组分成两条实现路径：省略 `relations` 时走「`context` + `relation`」路径，`relation` 为空会直接返回 `400`。仅需携带绘画文件时传空对象 `{}` 即可。

**提示 2**：频道码 `channelCode` 是管理对话和查询对话记录的唯一标识，因此 UI 程序应当有统一的管理机制。另推荐频道码如示例所示采用 **16 位**随机字符串。（请求体同时接受 `channel` 作为 `channelCode` 的别名。）


### 第三步 - 根据模型返回数据判断如何进行交互

经过第二步调用，`AiXinLi模型`会根据提示词推理是否开始进行绘画心理学推理任务。

进行绘画心理学推理时，模型需要知道被测人的年龄和性别等信息，因此在返回数据的`answer`字段会提示用户告知这些必要信息。

在程序中，应当通过判断返回数据是否有`context`字段，同时`context.inferring`的值是否为`true`，依此判断模型是否开始进行子任务推理。如果没有进入子任务推理，则按照正常的对话交互流程进行相关UI操作。


### 第四步 - 获取子任务推理数据

如果`context.inferring`为`true`值，表示模型开始进行子任务推理，此时UI应当进入等待状态，这时如果UI程序继续提交对话会被服务器拒收。

因此，当进入子任务推理后，数据`context.resources.payload.attachments.id`字段为绘画报告的序列号，调用 [获取报告指定部分信息](http://211.157.179.37:7010/doc/api/index.html#api-Psychology-%E8%8E%B7%E5%8F%96%E6%8A%A5%E5%91%8A%E6%8C%87%E5%AE%9A%E9%83%A8%E5%88%86%E4%BF%A1%E6%81%AF) 接口查询报告生成状态和生成内容。

请求示例：
``` url
http://211.157.179.37:7010/aigc/psychology/report/part/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG?sn=3325739656&content=true
```

参数`sn`即是`context.resources.payload.attachments.id`字段数据。

接口返回的数据中，`state`表示推理状态。当状态为`0`值时，字段`content`即为推理后的内容，该内容格式为**Markdown**格式。


### 第五步 - 显示报告数据

通过 [获取报告指定部分信息](http://211.157.179.37:7010/doc/api/index.html#api-Psychology-%E8%8E%B7%E5%8F%96%E6%8A%A5%E5%91%8A%E6%8C%87%E5%AE%9A%E9%83%A8%E5%88%86%E4%BF%A1%E6%81%AF) 接口获取到`content`数据，进行**Markdown**渲染显示。

至此对话方式推理报告就完成了。
