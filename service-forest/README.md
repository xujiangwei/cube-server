# service-forest 森林场景插件

本模块提供“上传图片 → 检测树冠 → 返回数量和框”的业务能力。Java 负责权限和接口，Python 的 OmniForest 负责推理。模块名为 `forest`，算法能力名为 `forest-detection`，算法通信动作固定为 `pluginInvoke`。

## 构建和接入

使用 **JDK 8**。顶层 `ant build-service-debug` / `ant build-service-release` 已纳入该模块；单独构建前须准备匹配版本的 common 和 dispatcher jar，再在本目录执行 `ant build-release`。产物是 `build/cube-service-forest-3.0.jar`（工程根目录下）。森林模块不依赖 service 实现或心理学插件。

正式部署时，将森林 jar 加入 service 和 dispatcher 的插件类路径，分别沿用现有心理学插件的 jar 放置方式。源码配置已追加以下声明，部署配置需同步合并，保留原有条目：

```properties
# service 的 config/aigc-modules.properties
module.2.class=cube.service.forest.ForestModule
module.2.enabled=true
```

dispatcher 的 `config/dispatcher.properties` 按「一行一个扩展」新增一行 `module.extensions.30=cube.service.forest.dispatcher.ForestEndpoints`（编号取未被占用的值即可，装载按编号升序，不影响已有条目）。重启对应进程后生效；停用 Java 模块可将该模块的 `enabled` 改为 false，停用接口则注释掉该行。

Python 插件位于 `C:/Users/hermi/workspace/apexnova-wisdom/plugins/omniforest`，按其 README 在 `config.properties` 开启 `plugins.enabled=True` 和 `plugin.omniforest=True`。当前本机 Python 配置仍默认关闭森林插件，未修改其他单元开关，也未部署或重启服务。

公共 SPI 升到 v26，新增方法均提供默认实现，已有 v25 心理学二进制插件仍允许加载；新森林插件要求 v26 宿主。能力快照保持 `version=1`，以新增 `moduleRoutingVersion=1`、`modules` 字段声明模块动作。网关仅将森林业务发给已加载森林模块的 service，检测还要求该 service 已连接 `forest-detection` 提供者。模型是否就绪由实际调用确认。

## 使用接口

业务地址使用 **dispatcher**。先通过已有文件上传接口获得当前用户拥有的 `fileCode`，再调用：

```http
POST /aigc/forest/detect/{token}
Content-Type: application/json
x-baize-api-device: MyClient
x-baize-api-platform: Web

{"fileCode":"<64位上传文件码>","scoreThreshold":0.3}
```

`scoreThreshold` 可省略，范围为 0–1；首期只接受单张 JPEG/PNG 和这两个字段。令牌及设备规则沿用原有接口；文件必须属于令牌所在域和当前调用者。成功返回 HTTP 200：

```json
{
  "code": 0,
  "data": {
    "response": "检测到 1 个树冠",
    "thought": "",
    "resultPayload": {
      "width": 100, "height": 80, "count": 1,
      "refs": [{"content": "tree", "boxes": [{"x1": 1, "y1": 2, "x2": 50, "y2": 60}], "points": []}]
    },
    "performance": {"inputTokens": 0, "outputTokens": 0, "elapsed": "120"}
  }
}
```

`count` 是框数；空结果为 `count=0, refs=[]`。坐标以校正 EXIF 方向后的图像左上角为原点，单位为像素。`elapsed` 为 Python 处理用时的毫秒字符串。

`GET /aigc/forest/capability/{token}` 返回 `{code:0,data:{plugin,unit,online,providers,readiness,formats,maxFiles,maxBytes,maxPixels,scoreThreshold,mode}}`。`online` 表示已连接提供者，`readiness="checked-on-invoke"` 表示需在调用时确认模型就绪；此接口在森林模块已加载但尚无模型节点时也可查询。

| HTTP | 含义 |
| --- | --- |
| 400 | 参数错误、未知字段或不支持的格式 |
| 401 / 403 | 令牌或设备无效 / 文件无权访问 |
| 404 | 文件或插件能力不存在 |
| 413 / 422 | 文件超过限制 / 图片无法解码 |
| 429 | 提供者忙或模块队列满 |
| 502 / 503 / 504 | 推理或结果异常 / 能力不可用或未就绪 / 超时 |

错误也使用 `{code,data}`，`data.message` 描述原因。接口详情见 `docs/rest-api/src/Forest.js`。

## 资源和数据边界

默认上限为 20 MiB、2000 万像素；检测预算为 60 秒（包含模块排队和 Java 文件准备），网关等待 90 秒。模块使用独立的 4 线程、8 个排队位置；同一 Python 提供者最多一个在途请求。HTTP 超时不会取消底层推理，也不会立即把它当作空闲；收到匹配响应、断线或连接替换后才释放占用。无法确认完成的节点需检查日志并重启或重连。

需要调整 Java 上限时，在 service 运行目录添加 `assets/modules/forest/config.json`，例如：

```json
{"maxBytes":20971520,"maxPixels":20000000,"timeoutMs":60000,"concurrency":4,"queueSize":8,"scoreThreshold":0.3}
```

`timeoutMs` 不超过 75000，Python 的上限应与 Java 配置协调。模块不新增数据库、表、历史记录或图片覆盖操作；Python 只在临时目录中准备图片。首期不包含页面、持久化任务、树种识别等后续能力。

验证程序为 `PluginUnitInvokerTest`、`ModuleRoutingTest`、`ForestPluginTest` 和 `ForestHttpTest`，分别位于 service、dispatcher、verify；请在临时工作目录运行。HTTP 用例只启动本机随机端口的临时 Jetty，后端采用隔离测试响应。真实预训练模型推理和业务精度需要在装好依赖与权重的环境中验收。
