# 文档说明

## 森林场景 OmniForest

森林接口已加入 apiDoc，源码见 [Forest.js](src/Forest.js)：上传图片后调用 `POST /aigc/forest/detect/:token` 获取树冠数量及位置，或调用 `GET /aigc/forest/capability/:token` 查询限制和在线提供者。业务地址使用 dispatcher，响应统一为 `{code,data}`。安装和启停配置见 [service-forest 说明](../../service-forest/README.md)。

## Harness 资源任务

Harness 的 18 个业务接口已纳入 apiDoc，接口源码见 [Harness.js](src/Harness.js)，生成页面见 [Harness 接口文档](output/index.html#api-Harness)。部署、认证与接入流程见 [Harness 任务 API](src/Harness.md)，涵盖任务、控制/审批、文件/产物、反馈、trace 与 SSE。

业务统一使用 dispatcher 地址，经 SHM 长连接访问 service；健康与运维单独配置 service HTTP 地址。兼容模型 API 与 Harness 任务 API 复用已有 Cube 用户 token；各服务地址分别配置，Console 登录凭据独立。

### 生成 API 文档

在本目录运行 `./build.ps1`（Windows，需 Node.js/npm），或 `./build.sh`（Linux/macOS，需 Node.js 和 apiDoc）。构建会扫描 `src/` 中全部接口注释，生成 `output/index.html` 并应用文档主题；导航中的 **Harness 任务服务** 为 Harness 接口分组。

## OpenAI 与 Claude SDK 兼容

dispatcher 提供 `/v1/chat/completions`、`/v1/messages` 和 `/v1/models`。使用 Cube Token 和现有模型 ID，支持文本、图片、客户端函数工具和真实 SSE；无需创建频道。

完整配置、能力边界、两套官方 SDK 及 curl 示例见 [兼容接口说明](src/Compatibility.md)，接口索引见 [Compatibility.js](src/Compatibility.js)。

## 以对话方式进行绘画解读

该文档用于说明如何开发基于对话的方式进行心理学绘画测试。

以下步骤是一个标准的数据处理流程：

1. 上传绘画文件；
2. 调用 `/aigc/psychology/converse` 接口，使用该接口提交提示词和上传文件的文件码；
3. 如果提示词信息不足，`AiXinLi模型`会引导补全信息，这个过程是一个正常的对话交互过程，使用 `/aigc/psychology/converse` 接口；
4. 进入推理流程后，UI应该进入等待状态，UI程序轮询 `/aigc/psychology/report/part` 接口获取报告状态和数据。
5. 使用返回的报告内容数据填充到UI程序。


### 第一步 - 上传绘画文件

上传文件调用 [上传文件数据](http://211.157.179.37:8080/doc/index.html#api-File_Processing-%E4%B8%8A%E4%BC%A0%E6%96%87%E4%BB%B6%E6%95%B0%E6%8D%AE) 接口。

上传文件后使用 [查找已上传的文件](http://211.157.179.37:8080/doc/index.html#api-File_Processing-%E6%9F%A5%E6%89%BE%E5%B7%B2%E4%B8%8A%E4%BC%A0%E7%9A%84%E6%96%87%E4%BB%B6) 接口查询文件状态，确保文件已正确上传到服务器。


### 第二步 - 提交提示词和文件码

使用 [互动对话](http://211.157.179.37:8080/doc/index.html#api-Psychology-%E4%BA%92%E5%8A%A8%E5%AF%B9%E8%AF%9D) 同时提交用户的提示词和文件的文件码。

请求数据如下所示：

``` json
{
    "channelCode": "mnxRSaLdPuHEVaKA",
    "context": [
        "fileCode": "yEryPsWMYphCHatmTRmoPMvlSKqyfJCFkchtgjgFYVfKQaAzmnbVHHboRYwquJJY"
    ],
    "query": "画里能看出什么心理状态"
}
```

**提示**：频道码`channelCode`是管理对话和查询对话记录的唯一标识，因此UI程序应当有统一的管理机制。另推荐频道码如示例所示采用**16位**随机字符串。


### 第三步 - 根据模型返回数据判断如何进行交互

经过第二步调用，`AiXinLi模型`会根据提示词推理是否开始进行绘画心理学推理任务。

进行绘画心理学推理时，模型需要知道被测人的年龄和性别等信息，因此在返回数据的`answer`字段会提示用户告知这些必要信息。

在程序中，应当通过判断返回数据是否有`context`字段，同时`context.inferring`的值是否为`true`，依此判断模型是否开始进行子任务推理。如果没有进入子任务推理，则按照正常的对话交互流程进行相关UI操作。


### 第四步 - 获取子任务推理数据

如果`context.inferring`为`true`值，表示模型开始进行子任务推理，此时UI应当进入等待状态，这时如果UI程序继续提交对话会被服务器拒收。

因此，当进入子任务推理后，数据`context.resources.payload.attachments.id`字段为绘画报告的序列号，调用 [获取报告指定部分信息](http://211.157.179.37:8080/doc/index.html#api-Psychology-%E8%8E%B7%E5%8F%96%E6%8A%A5%E5%91%8A%E6%8C%87%E5%AE%9A%E9%83%A8%E5%88%86%E4%BF%A1%E6%81%AF) 接口查询报告生成状态和生成内容。

请求示例：
``` url
http://211.157.179.37:7010/aigc/psychology/report/part/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG?sn=3325739656&content=true
```

参数`sn`即是`context.resources.payload.attachments.id`字段数据。

接口返回的数据中，`state`表示推理状态。当状态为`0`值时，字段`content`即为推理后的内容，该内容格式为**Markdown**格式。


### 第五步 - 显示报告数据

通过 [获取报告指定部分信息](http://211.157.179.37:8080/doc/index.html#api-Psychology-%E8%8E%B7%E5%8F%96%E6%8A%A5%E5%91%8A%E6%8C%87%E5%AE%9A%E9%83%A8%E5%88%86%E4%BF%A1%E6%81%AF) 接口获取到`content`数据，进行**Markdown**渲染显示。

至此对话方式推理报告就完成了。
