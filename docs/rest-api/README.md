# 文档说明

## 响应格式（两种，勿混用）

- **宿主内联端点**（`/aigc/*`、`/app/*`、`/contact/*`、`/cv/*`、`/filestorage/*`、`/riskmgmt/*`）直接返回业务 JSON，**不带** `code`/`data` 包装。例如 `POST /aigc/channel/:token` 返回 `{"code":"KLKVaPXDZCPRjMPx","creationTime":...,"participant":...}`，其中 `code` 是**频道码**而非状态码。这类接口用 HTTP 状态码表达成败，失败体为 `{"error":{...}}`。
- **插件注入端点**（`/aigc/forest/*`、`/harness/*`）返回 `{"code":<AIGC 状态码>,"data":{...}}`，业务错误同时带 HTTP 状态码。

`/v1/*` 兼容层使用 OpenAI / Claude 各自的错误结构，见下一节。

### 文件接口的响应外壳

`/filestorage/operation/*` 与 `/filestorage/file/` 默认返回 `{sn,name,data}` 外壳（`data` 内才是业务数据）。请求头 `x-baize-api-version: v1` 时改为直接返回业务数据；令牌可通过请求头 `x-baize-api-token` 传入而不写在请求体里。`/filestorage/operation/` 下的操作按 URL 后缀区分：`/list`、`/find`、`/delete`、`/modify`。

## 专项检测 SpecializedDetection

专项检测使用 dispatcher 业务 REST 地址，先创建调用者所属频道；图片和视频文件须先上传并取得文件码。成功直接返回业务 JSON，失败返回 `{"error":{"state":HTTP状态码,"code":Unit状态码,"reason":"...","message":"..."}}`。

| 接口 | 用途 | 生成页面 |
| --- | --- | --- |
| `POST /specialized-detection/base/{token}` | 图片火焰、烟雾检测 | [专项图片检测](output/index.html#api-SpecializedDetection-SpecializedDetectionImage) |
| `POST /specialized-detection/stream/{token}` | 视频文件或 RTSP(S) 后台检测，支持 `start/status/stop` | [专项视频后台会话](output/index.html#api-SpecializedDetection-SpecializedDetectionStream) |

部署无需在 `aigc.properties` 或 `dispatcher.properties` 中添加专项配置，也无需声明支持的 profiles。默认数据池 8 线程、控制池 2 线程，各有 64 个排队名额；每个 Python 实例最多一个图片在途请求、两个后台会话。service 的数据/控制期限分别为 180/30 秒，dispatcher 分别等待 190/40 秒。已知 profiles 为 `fire-smoke`、`uav-fire-smoke`，由 Java `SpecializedDetectionProfile` 枚举统一汇总；实际支持情况由 Python 在请求时判断，不支持时返回对应状态，当前为 code `1` / HTTP `400`。

请求与应答、PNG 直连回调和重启边界统一见 [专项检测接口](output/index.html#api-SpecializedDetection)。

## 覆盖范围

本文档覆盖 dispatcher 的 AIGC 业务面，以及触点、文件、风控、CV、Forest、Harness 与 `/v1` 兼容层。

以下端点已在 dispatcher 注册，但**不在本文档范围内**（内部或半内部使用，无对外承诺）：`/hub/*`、`/robodroid/*`、`/ferry/gnosis/`、`/file/media/`、`/file/source/`、`/file/steganographic/`、`/sharing/`、`/doc/api/`、`/static/`、`/aigc/module/*`（`module.rest.enabled` 控制的兜底通道，默认关闭）。

以下端点属于业务面但尚未编写条目，需要时按源码补：`/aigc/chart/`（说话人视图）、`/aigc/cot/`、`/aigc/painting/label`、`/aigc/psychology/painting`、`/aigc/psychology/report/page`、`/aigc/psychology/report/remark`、`/aigc/psychology/report/state`、`/aigc/copilot/{apply,dispose,sheet}`、`/aigc/stream/{apply,stop,caption,strategy}`、`/aigc/speech/analysis`、`/aigc/speech/diarization/opt/`、`/aigc/facial/expression`、`/aigc/context/inference`、`/aigc/component/event`、`/aigc/queue/`、`/aigc/search/result/`、`/aigc/stop/`、`/aigc/knowledge/{doc,segment,backup,article,article/category,generate}`、`/app/{chat,change,config,customer,schedule,session,evaluate,inject,keepalive,membership,user/modify,user/profile,user/signout,verify,version,asciiart,emotion,wordcloud}`、`/cv/{object/detect,estimation/pose,estimation/hand,clippaper}`。

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

下列请求均使用 dispatcher 的业务 REST 地址。将示例中的 `http://localhost:7010` 和令牌替换为实际值；如果获取令牌时指定了设备信息，森林接口请求应携带对应的 `x-baize-api-device`、`x-baize-api-platform` 请求头。

### 第一步 - 查询森林能力

调用 [查询森林能力](output/index.html#api-Forest-ForestCapability)：

``` http
GET http://localhost:7010/aigc/forest/capability/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
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
POST http://localhost:7010/filestorage/file/?token=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG&filename=forest.jpg
Content-Type: application/octet-stream

<forest.jpg 的二进制数据>
```

上传接口默认使用 `{sn,name,data}` 外壳，从 `data.fileCode` 读取文件码。再调用 [查找已上传的文件](output/index.html#api-File_Processing-查找已上传的文件) 接口，确认图片已可查询后再提交检测。上传和检测应使用同一用户、同一域的令牌。

示例文件码为 `UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx`，实际调用时必须替换为上传接口返回的值。

### 第三步 - 提交树冠检测

调用 [检测树冠](output/index.html#api-Forest-ForestDetect)，请求体使用 JSON：

``` http
POST http://localhost:7010/aigc/forest/detect/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
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

## Harness 资源任务

Harness 的 18 个业务接口见 [Harness 接口文档](output/index.html#api-Harness)，覆盖任务、控制/审批、文件/产物、反馈、trace 与 SSE。部署、认证、存储与接入流程见工程中的 `service-harness/README.md`。

业务统一使用 dispatcher 地址，经 SHM 长连接访问 service；健康与运维单独配置 service HTTP 地址。兼容模型 API 与 Harness 任务 API 复用已有 Cube 用户 token；各服务地址分别配置，Console 登录凭据独立。

### 生成 API 文档

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

dispatcher 提供 `/v1/chat/completions`、`/v1/messages`、`/v1/models` 与 `/v1/models/:model`。使用 Cube Token 和现有模型 ID，支持文本、图片、客户端函数工具和真实 SSE；无需创建频道。

接口索引与字段说明见 [OpenAI 与 Claude 兼容接口](output/index.html#api-Compatibility)。开关为 `dispatcher/config/dispatcher.properties` 中的 `completion.rest.enabled`（默认 `true`；设为 `false` 时 `/v1` 全段返回 404），超时与缓冲见同文件 `completion.rest.*`。

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
