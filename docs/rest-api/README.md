# 文档说明

## 响应格式（两种，勿混用）

- **宿主内联端点**（`/aigc/*`、`/app/*`、`/contact/*`、`/cv/*`、`/filestorage/*`、`/riskmgmt/*`）直接返回业务 JSON，**不带** `code`/`data` 包装。例如 `POST /aigc/channel/:token` 返回 `{"code":"KLKVaPXDZCPRjMPx","creationTime":...,"participant":...}`，其中 `code` 是**频道码**而非状态码。这类接口用 HTTP 状态码表达成败，失败体为 `{"error":{...}}`。
- **插件注入端点**（`/aigc/forest/*`、`/harness/*`）返回 `{"code":<AIGC 状态码>,"data":{...}}`，业务错误同时带 HTTP 状态码。

`/v1/*` 兼容层使用 OpenAI / Claude 各自的错误结构，见下一节。

### 文件接口的响应外壳

`/filestorage/operation/*` 与 `/filestorage/file/` 默认返回 `{sn,name,data}` 外壳（`data` 内才是业务数据）。请求头 `x-baize-api-version: v1` 时改为直接返回业务数据；令牌可通过请求头 `x-baize-api-token` 传入而不写在请求体里。`/filestorage/operation/` 下的操作按 URL 后缀区分：`/list`、`/find`、`/delete`、`/modify`。

## 覆盖范围

本文档覆盖 dispatcher 的 AIGC 业务面，以及触点、文件、风控、CV、Forest、Harness 与 `/v1` 兼容层。

以下端点已在 dispatcher 注册，但**不在本文档范围内**（内部或半内部使用，无对外承诺）：`/hub/*`、`/robodroid/*`、`/ferry/gnosis/`、`/file/media/`、`/file/source/`、`/file/steganographic/`、`/sharing/`、`/doc/api/`、`/static/`、`/aigc/module/*`（`module.rest.enabled` 控制的兜底通道，默认关闭）。

以下端点属于业务面但尚未编写条目，需要时按源码补：`/aigc/chart/`（说话人视图）、`/aigc/cot/`、`/aigc/painting/label`、`/aigc/psychology/painting`、`/aigc/psychology/report/page`、`/aigc/psychology/report/remark`、`/aigc/psychology/report/state`、`/aigc/copilot/{apply,dispose,sheet}`、`/aigc/stream/{apply,stop,caption,strategy}`、`/aigc/speech/analysis`、`/aigc/speech/diarization/opt/`、`/aigc/facial/expression`、`/aigc/context/inference`、`/aigc/component/event`、`/aigc/queue/`、`/aigc/search/result/`、`/aigc/stop/`、`/aigc/knowledge/{doc,segment,backup,article,article/category,generate}`、`/app/{chat,change,config,customer,schedule,session,evaluate,inject,keepalive,membership,user/modify,user/profile,user/signout,verify,version,asciiart,emotion,wordcloud}`、`/cv/{object/detect,estimation/pose,estimation/hand,clippaper}`。

## 森林场景 OmniForest

森林接口已加入 apiDoc，源码见 [Forest.js](src/Forest.js)：上传图片后调用 `POST /aigc/forest/detect/:token` 获取树冠数量及位置，或调用 `GET /aigc/forest/capability/:token` 查询限制和在线提供者。业务地址使用 dispatcher，响应为 `{code,data}`。安装和启停配置见 [service-forest 说明](../../service-forest/README.md)。

## Harness 资源任务

Harness 的 18 个业务接口已纳入 apiDoc，接口源码见 [Harness.js](src/Harness.js)，生成页面见 [Harness 接口文档](output/index.html#api-Harness)。部署、认证、存储与接入流程见 [service-harness 说明](../../service-harness/README.md)，覆盖任务、控制/审批、文件/产物、反馈、trace 与 SSE。

业务统一使用 dispatcher 地址，经 SHM 长连接访问 service；健康与运维单独配置 service HTTP 地址。兼容模型 API 与 Harness 任务 API 复用已有 Cube 用户 token；各服务地址分别配置，Console 登录凭据独立。

### 生成 API 文档

在本目录运行 `./build.ps1`（Windows，需 Node.js/npm），或 `./build.sh`（Linux/macOS，需 Node.js 和 apiDoc）。构建会扫描 `src/` 中全部接口注释，生成 `output/index.html` 并应用文档主题；导航中的 **Forest**、**Harness**、**Compatibility** 为对应 `src/*.js` 的分组。

生成后需手工同步到 dispatcher 才能通过 `/doc/api/` 访问：

``` bash
rsync -a --delete output/ ../../dispatcher/assets/doc/
```

`output/` 与 `dispatcher/assets/doc/` 均为构建产物，仓库内的版本可能落后于 `src/`（`/doc/api/` 提供的就是 `dispatcher/assets/doc/`），改动接口注释后请重新生成并同步。

## OpenAI 与 Claude SDK 兼容

dispatcher 提供 `/v1/chat/completions`、`/v1/messages`、`/v1/models` 与 `/v1/models/:model`。使用 Cube Token 和现有模型 ID，支持文本、图片、客户端函数工具和真实 SSE；无需创建频道。

接口索引与字段说明见 [Compatibility.js](src/Compatibility.js)。开关为 `dispatcher/config/dispatcher.properties` 中的 `completion.rest.enabled`（默认 `true`；设为 `false` 时 `/v1` 全段返回 404），超时与缓冲见同文件 `completion.rest.*`。

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
