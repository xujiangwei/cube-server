# service-forest 森林场景插件方案

- 日期：2026-10-25
- 状态：首期代码已实施，真实预训练模型推理待部署环境验收
- 实施范围：Java 森林模块、Python OmniForest、公共插件调用通道、网关路由、启停配置和接口文档。
- 设计依据：[Python 插件框架设计 v2](C:/Users/hermi/workspace/apexnova-wisdom/docs/2026-10-23-plugin-framework-design.md)，以及当前心理学业务插件的实现。

## 实施记录（2026-10-25）

首期代码已落地：Java `service-forest` 提供检测和能力查询；Python 插件为 **OmniForest**，目录是 `C:/Users/hermi/workspace/apexnova-wisdom/plugins/omniforest`，配置使用 `plugin.omniforest.*`，能力名仍固定为 `forest-detection`。

- [Java 接入和接口说明](../service-forest/README.md)、[Python 启停与依赖说明](C:/Users/hermi/workspace/apexnova-wisdom/plugins/omniforest/README.md) 已补齐；本机 Python 开关默认关闭，已有单元配置保留。配置变更需要重启，不提供热加载。
- Java 使用独立有界队列，检测预算计入排队和准备时间；超时保留模型占用，匹配迟到响应或断线/连接替换后释放。支持同名多个提供者。
- 能力快照保留 `version=1`，通过 `moduleRoutingVersion=1` 和 `modules` 做增量扩展，旧网关读取原字段不受影响；旧快照不能路由新增模块动作。
- 文件只允许当前域和当前用户拥有的上传图片。Java 传递完整可信 FileLabel，Python 在独立临时目录校正 EXIF 方向、转换 RGB 并检测；不修改原图，不迁移数据库。
- 已核对附带的 DeepForest 2.1 源码：使用构造配置和 `predict_tile`；不使用该版本不存在的 `use_release()` 或 `return_plot` 参数。附带源码的 numpy 未声明 `>=2`，依赖冲突应以实际解析结果为准。
- 完成 JDK 8 编译、协议/HTTP 验证及原有功能回归。当前验证环境未安装 DeepForest 完整依赖和权重，真实模型推理、业务精度与性能仍需在目标环境验收；未部署或重启运行中的服务。

以下正文保留设计分工与验收边界。首期没有新增页面、历史库、持久化长任务、其他林业模型或通用插件管理 API。

验证记录：Python 7 组共 141 个用例通过，包含 OmniForest 新增的 15 个用例和原有注册表、依赖安装、单元依赖、多模态文件、文本补全、ML 依赖用例。Java 14 个验证程序通过，覆盖森林模块、HTTP、调度及原有功能。另使用修改前的 v25 SPI 源码编译心理学插件，在新宿主上通过 162 项断言；更早的 v24 缓存 jar 按兼容规则被拒绝。apiDoc 在临时目录成功生成并包含两个森林接口。验证没有连接业务数据库或部署服务。

## 1. 建议怎么做

新增 `service-forest`，接入方式沿用 `service-psychology`：作为独立 Java 业务插件，负责森林场景的接口、权限、参数校验和业务结果。图像识别由 Python 的 OmniForest 插件（基于 DeepForest）完成。

可以把两者理解为：**Java 负责接待和组织业务，Python 负责看图和计算。** Java 不安装 DeepForest，也不直接加载模型。

首期完成一条清晰的业务链路：

> 用户上传一张森林航拍图 → 发起检测 → 系统圈出树冠 → 返回检测数量和框的位置。

这里的“数量”是模型检测到的树冠数量，适合辅助调查与人工复核。树种识别、病虫害诊断、树高、蓄积量、碳储量，以及精确冠层覆盖率，需要额外模型或测量数据，不能从检测框直接推导，暂列后续扩展。

## 2. 三个名字，各管一件事

| 名字 | 在哪里 | 负责什么 |
| --- | --- | --- |
| `forest` / `service-forest` | Java 服务 | 森林业务模块名 / 工程名，对外提供森林场景接口 |
| `OmniForest` / `omniforest` | Python 插件名称 / 插件 ID 与目录 | 安装、配置和加载 DeepForest 算法 |
| `forest-detection` | Java 与 Python 之间 | 检测能力的固定调用名称，与 `capability.name` 完全一致 |

这三个名字不混用。例如，Java 调用的是 `forest-detection`，不需要知道 Python 的类名、插件目录或模型所在机器。

森林业务动作与模型传输动作也要区分：`forestDetect` 表示“办理一次森林检测业务”；`pluginInvoke` 表示“调用一个 Python 插件单元”。以后增加别的算法单元，仍复用 `pluginInvoke`。

## 3. 现有基础与需要补齐的部分

本方案已核对源码。下表保留设计时的现状和安排；实施后的完成情况见前面的实施记录。

| 部分 | 当前情况 | 本方案安排 |
| --- | --- | --- |
| Java 业务模块加载 | 已有 `ActionModule`、`ModuleRegistry`、`AIGCHost` | `ForestModule` 沿用该机制 |
| Java 插件注入 REST 端点 | 已有 `DispatcherExtension`，心理学插件已有实例 | 森林端点及 handler 全部放在 `service-forest` |
| Java 单元注册与选择 | 已有 `UnitScheduler`；支持多个单元 | 复用真实连接和能力记录，补插件严格名称选择及记录清理 |
| Java 底层单元调用 | `AIGCHost.invokeUnit()` 已能发送指定 action | 复用底层传输，新增按插件名称调用的统一封装 |
| Python 注册表 | `cube/plugin/registry.py` 已存在 | 继续沿用，不再建第二套单元生命周期 |
| Python 插件加载、DeepForest、`pluginInvoke` | 当前只有参考文档中的设计；尚无 `plugins/omniforest/` | 作为首期前置工作完成 |
| 网关按插件动作转发 | 当前 `Manager.syncRequest()` 使用固定动作枚举；`CapabilityRouter` 尚不处理森林动作 | 增加通用模块转发和选点能力，森林动作由插件声明 |

还有两个容易遗漏的差异：

- 现有 `UnitScheduler` 的部分选择方法使用忽略大小写的比较；插件契约要求原值精确匹配，因此不能直接把这些方法当作完整的插件路由。
- 现有 `setup` 会增加或更新本次上报的能力，但不会完整移除同一节点不再上报的旧能力；插件停用、重连和重复上报时，需要补齐替换与清理规则。

## 4. 一次检测怎样完成

```mermaid
flowchart LR
    U[调用方：上传图片并发起检测] --> D[dispatcher：鉴权与转发]
    D --> F[service-forest：校验森林业务请求]
    F --> H[AIGC 宿主：按名称选择插件单元]
    H --> P[Python 插件路由：pluginInvoke]
    P --> M[DeepForest：检测树冠]
    M --> F
    F --> U
```

具体分工如下：

1. 调用方通过已有文件上传能力获得 `fileCode`，再提交森林检测请求。
2. dispatcher 使用现有令牌与设备鉴权，只接入和转发请求。
3. `service-forest` 校验文件访问权限、图像类型、图像大小和检测参数。
4. 插件通过公共宿主接口调用 `forest-detection`；宿主选择真正连接在线的 Python 提供者。
5. 宿主发送固定动作 `pluginInvoke`，其中 `data.unit` 固定为 `forest-detection`。
6. Python 的 `PluginUnitRouter` 精确找到单元，检查是否就绪，再执行检测。
7. 检测结果沿原请求返回；Java 保留结构化数据，调用方可据此在原图上画框。

文件内容通过既有文件服务获取，调用信封只携带文件引用，避免在消息中塞入整张图像。`service-forest` 不自行建立另一条 Python HTTP 连接，继续使用工程现有通信链路。

### 4.1 Java 公共能力只补一次

建议在 `AIGCHost` 增加概念接口 `invokePluginUnit(unitName, input, timeoutMs)`，由 `AIGCHostImpl` 委托宿主完成。具体入参和返回类型在实施时按现有 `JSONObject`、`Packet` 风格确定。

这项公共能力负责：

- 校验名称格式和输入；严格按 `capability.name` 选择提供者，不按 `task/subtask` 猜模型。
- 统一发送 `pluginInvoke`，填写 `data.unit`，关联本次传输的 `sn`，解析状态码与结果。
- 按“节点身份 + 能力名”保留多个提供者；重复 `setup` 替换该节点能力集合，`teardown` 或断连移除对应记录。
- 复用现有调度与单元队列设施，补齐插件调用的占用、释放和排队上限；选择和占位需要协调，不能让并发请求都认为同一单元空闲。
- 名称不存在、模型未就绪、模型忙、调用失败分别返回明确结果；不切换到其他名称或内置模型。

当前 `invokeUnit()` 主要提供底层发送和超时等待；上述完整的名称路由、占位及结果检查属于新增工作，不能只换一个 action 字符串就算完成。

`AIGCSPI.VERSION` 已从 25 升到 26。此次只增加默认方法，宿主显式允许 v25 心理学二进制插件继续加载；新森林插件需要 v26 宿主。

### 4.2 网关也需要找到正确的 Java 服务

多 Java 服务部署时，需要连续做两次选择：dispatcher 先选“已加载森林模块、且拥有目标能力”的 Java service；该 service 再选一个 Python 提供者。

建议给公共网关能力补一个通用模块转发入口，接收模块名、动作名、所需能力和请求数据；端点由插件声明这些信息。公共入口发送动作字符串，不要求每个新业务动作加入公共 `AIGCAction` 枚举。

同时扩展现有能力快照：由服务端发布已装载模块及其动作，结合已有单元能力列表筛选服务节点。dispatcher 继续使用后台刷新的缓存，用户请求不临时查询所有节点。快照增加版本约定；旧快照继续服务原有请求，但不能据此宣称支持新增模块动作。

选点条件按动作声明：检测动作要求森林模块和 `forest-detection` 提供者同时存在；能力查询只要求森林模块已装载，这样没有模型在线时也能正常回答“尚无在线提供者”。

能力状态按模块在运行期实时统计，随单元上下线变化。启动期不做能力校验：单元由 Relay 上报产生，部署顺序是先 service 后算法单元，启动完成时单元表必然为空，启动期判定只会把部署顺序误报成模块不可用。`ModuleRegistry#countCapabilityProviders` 是唯一权威来源，`logCapabilityDeclarations` 只记录声明与当前提供者数量；能力缺失也不得把宿主标记为降级，更不得扩大为其他模块不可用。

这些都是通用设施，公共工程中不写 `if forest`。首个联调环境可以使用一个 Java service 和多个 Python 提供者；交付多 service 部署前，必须完成上述网关筛选，不能依赖随机转发碰巧成功。

## 5. 首期提供什么接口

**以下 URL、动作名和返回字段已按首期契约实现；实际用法见 service-forest/README.md。** HTTP 地址属于 dispatcher 的业务 REST 地址，不能使用 console 管理地址替代。

| 接口 | Java 模块动作 | 用途 |
| --- | --- | --- |
| `POST /aigc/forest/detect/:token` | `forestDetect` | 对一张图像检测树冠，返回数量和检测框 |
| `GET /aigc/forest/capability/:token` | `forestGetCapability` | 返回支持的输入、参数范围，以及是否发现在线提供者 |

两个动作均要求有效令牌。能力查询的“在线”只表示已发现连接有效的提供者；`setup` 不代表模型完成初始化，检测时仍可能收到“模型未就绪”。

### 5.1 检测请求

```json
{
  "fileCode": "uploaded-image-code",
  "scoreThreshold": 0.3
}
```

- 每次只接受一张已上传图片，首期建议支持 RGB JPEG、PNG。
- `scoreThreshold` 可选，范围为 0～1；越高越严格，留下的检测框通常越少，默认 0.3。
- 调用方不传插件 ID、实现类、节点地址或任意 action。`forest-detection` 由森林模块配置固定，不允许请求随意指定。
- 阈值必须是合法有限数值；未知业务字段明确拒绝，避免拼写错误被静默忽略。
- 首期不开放外部 URL 和本地路径输入；底层通用插件协议仍保留参考设计中的文件引用能力。
- 文件校验需要核对令牌所在域和调用者访问权限，不能把“知道 fileCode”直接等同于“有权使用该文件”。

Java 将请求转换为参考设计约定的 Python 入参：

```json
{
  "unit": "forest-detection",
  "content": "",
  "history": [],
  "files": ["uploaded-image-code"],
  "option": {
    "mode": "tile",
    "score_threshold": 0.3,
    "wait": false
  }
}
```

传输 action 为 `pluginInvoke`。文件引用在实施时按现有文件规范化与下载流程落地，保留其域和访问上下文；不能把 Java 本机路径发给另一台机器上的 Python。

首期固定使用 `tile`：把较大的图片分块识别，再合并结果。切块尺寸、重叠比例和重复框合并参数由部署配置控制，先不作为普通业务参数开放。`wait=false` 表示模型已被占用时及时返回“忙”，不在 Python 模型锁上无期限等待。

### 5.2 检测结果

业务结果保留参考设计的四个字段：`response`、`thought`、`resultPayload`、`performance`。外层 HTTP 状态与错误包装由新端点按下面的规则实现，不能把示例当作当前接口响应。

```json
{
  "response": "检测到 1 个树冠",
  "thought": "",
  "resultPayload": {
    "width": 1024,
    "height": 768,
    "refs": [
      {
        "content": "tree",
        "boxes": [{"x1": 10, "y1": 20, "x2": 80, "y2": 120}],
        "points": []
      }
    ],
    "count": 1
  },
  "performance": {
    "inputTokens": 0,
    "outputTokens": 0,
    "elapsed": "120"
  }
}
```

结果解释：

- `width/height` 为实际检测及展示图像的像素尺寸。框坐标以左上角为原点，向右、向下递增；切块结果须还原为整图坐标。
- `count` 等于阈值过滤、跨切块重复框合并后，所有 `refs[].boxes` 的总数。未检测到树冠也算成功，返回 `count=0`、`refs=[]`。
- 图像方向处理必须与展示一致，避免 EXIF 旋转导致检测框错位；禁止用缩略图尺寸配原图坐标。
- `elapsed` 沿用现有性能字段，单位为毫秒。`response` 用于阅读，业务统计以结构化 `count` 为准。
- 该契约首期不包含逐框置信度；如以后需要筛选、排序展示，应明确扩展字段，再同步两端和界面。

### 5.3 失败时怎样告诉调用方

Python 已返回的业务码应保留，Java 自身的传输失败另行映射，不把所有失败都压成“图片不存在”。拟议新端点采用以下对应关系：

| 情况 | AIGC 业务码 | 建议 HTTP 状态 |
| --- | --- | --- |
| 参数、阈值、图像数量不合法 | `1 InvalidParameter` | 400 |
| 文件不存在，或合法能力名未注册 | `3 NotFound` | 404 |
| 没有文件使用权限 | 沿用现有权限错误约定 | 403；令牌无效为 401 |
| 模型已被占用 | `7 Processing` | 429 |
| 模型初始化中或尚未就绪 | `30 UnitNoReady` | 503 |
| 模型执行失败或结果结构不合法 | `31 UnitError` | 502 |
| 图像损坏或文件处理失败 | `32 FileError` | 422 |
| 已声明的业务模块未加载 | `33 ModuleNotLoaded` | 503 |
| 超过上传或图像资源上限 | 按超限项返回明确错误 | 413 |
| 提供者连接失败 / 调用超时 | Java 层调用失败，保留原因 | 503 / 504 |

名称已知但提供者不可达，应归为服务不可用，不伪装成“名称不存在”。网关端点本身未安装时仍为普通 404。自动重试暂不加入首期；超时只代表本次等待结束，不表示 Python 已取消计算。

## 6. service-forest 工程怎样组织

建议结构如下，首期只建立本次能力所需的类：

```text
service-forest/
├── build.xml
├── README.md
└── src/cube/service/forest/
    ├── ForestModule.java                   模块入口与动作清单
    ├── ForestConfig.java                   参数和资源上限
    ├── action/
    │   ├── ForestDetectAction.java          校验并执行一次检测
    │   └── ForestGetCapabilityAction.java   查询能力信息
    ├── scene/
    │   └── ForestDetectionService.java      检测业务组织与结果校验
    └── dispatcher/
        ├── ForestEndpoints.java            端点与路由声明
        └── handler/                        森林 HTTP 接口
```

实现上沿用心理学插件的关键约束：

- `ForestModule` 实现 `ActionModule`，模块名为 `forest`；动作处理器实现 `AIGCActionTask`。
- `declareActionNames()` 与 `getActions()` 必须一致，动作使用森林前缀，避免和其他模块冲突。
- `ModuleDescriptor.requiredCapabilities` 声明 `forest-detection`，不声明对心理学模块的依赖。建议将森林模块设为可选模块；能力缺失的处理还需配合前述按模块记录的状态，不能仅靠 `optional` 开关。
- `setup()` 只读取配置和装配对象，不加载 Python 模型、不等待模型在线，也不调用此时尚未就绪的宿主调度线程池。能力发现和请求阶段分别判断可用性。
- 编译依赖 `common`、`dispatcher` 及已有基础库；不依赖宿主 `service` 的实现，也不依赖 `service-psychology`。宿主和 dispatcher 不反向引用森林类。
- `teardown()` 停止接收新业务并收尾本模块持有的任务、临时资源；Python 模型生命周期仍由 Python 插件框架管理。

首期同步返回单次检测结果，**不要求新增数据库表**。已有原图继续由文件服务管理，Java 不重复存一份图片。需要历史查询时，再增加模块私有存储，不复用心理学报告表。

## 7. 部署、容量与超时

### 7.1 安装分为 Java 和 Python 两步

Java 侧：

1. 继续使用 JDK 8 和 Ant。新模块构建排在 `common`、`dispatcher` 之后。
2. 生成 `cube-service-forest-<工程版本>.jar`，与心理学插件一样放入对应运行环境的 `libs/`，部署目录为 `deploy/libs/`。
3. service 的 `aigc-modules.properties` 增加 `cube.service.forest.ForestModule`；dispatcher 的 `module.extensions.<n>` 新增一行 `cube.service.forest.dispatcher.ForestEndpoints`，保留已有配置项。
4. 不放入 `deploy/cellets/`，不使用旧的钩子插件 `config/plugin.json` 注册该业务模块。

Python 侧：

1. 按参考设计的 P1 完成插件 loader、预检、独立路由及 `plugins/omniforest/`。
2. 推荐独立虚拟环境和专用 Python 进程，启用 `plugins.enabled=True`、`plugins.only=True`、`plugin.omniforest=True`。
3. 每个提供者配置独立的联系人/实例身份、运行目录及连接身份，统一上报 `forest-detection`。
4. 模型路径、设备、阈值、切块参数沿用 `plugin.omniforest.*` 配置。上线前准备并验证权重，固定已验证的依赖和权重版本。

专用进程是首期可实施的部署隔离，不依赖参考设计 P3 的自动子进程 runner。参考设计指出 DeepForest 的 NumPy 要求与现有 embedding 环境存在硬冲突，因此不建议让两者共用虚拟环境。

### 7.2 一个 Java 服务可以连接多个 Python 提供者

```text
Java service（加载 forest）
  ├─ Python A：forest-detection，独立环境与实例身份
  ├─ Python B：forest-detection，独立环境与实例身份
  └─ Python C：其他已注册能力
```

A、B 提供同一份输入输出契约，Java 按连接有效性和占用情况分配。首期每个 DeepForest 实例一次处理一个检测；扩容可以增加提供者，无需再启动一个配对 Java 插件。

现有 capability 没有完整的实时剩余容量和模型就绪字段；首期不能据此承诺精细容量调度。Java 保守限制单元在途请求，Python 通过就绪检查和非阻塞模型锁作最终校验。后续需要更高并发时，再扩展容量与健康上报契约。

### 7.3 首期控制长任务

首期采用同步接口，建议初始设置文件上限 20 MiB、像素总数上限 2000 万，均可配置；这些是联调起点，最终支持范围需以目标硬件实测为准。按文件头和实际解码结果校验资源上限，模型端保留同样的保护。

Python 调用预算建议先设为 60 秒，包含文件准备、排队和推理；外层转发保留更长预算及收尾时间。当前网关常见同步等待为 90 秒，应检查整条调用链，确保内层先返回、外层还能发送错误响应，而不是每层各等 90 秒。

Java 等待超时后，不能立即把仍在计算的提供者当作空闲，也不能因周期性状态重置解除这类占用。需要追踪尚未完成的调用，收取迟到结果或通过明确的恢复流程解除；无法确认时暂停对该提供者派发。取消与进度协议属于后续设计。

若目标航拍图普遍超过预算，下一阶段应改为“提交任务 → 查询状态 → 读取结果”。仅增加 HTTP 超时不能代替任务管理。

## 8. 如何分步交付和验收

以下是本方案的交付阶段；参考文档中的 P1/P2/P3 仍按其原定义使用。

| 阶段 | 工作 | 能交付什么 |
| --- | --- | --- |
| A：打通公共通道 | 完成参考设计 P1，含其内置路由兼容性前置验收；Java 插件调用封装、严格名称匹配、记录替换清理；先使用轻量假单元验证 | Java 能按 `forest-detection` 完整收发 `pluginInvoke` |
| B：上线森林最小能力 | 新增 `service-forest`、两个端点、DeepForest 真模型；通用模块转发与网关选点；构建部署和 REST 文档 | 上传单图、检测、返回数量与框；支持多个提供者 |
| C：补使用体验 | 独立插件页面，原图叠框；按需要增加异步任务、历史记录和结果导出 | 适合日常业务使用的森林检测流程 |
| D：扩场景 | 依据数据与模型能力增加树种、病虫害、变化检测等单元 | 新单元仍复用插件调用通道 |

阶段 B 相当于提前交付参考设计 P2 中森林场景所需的专属 HTTP 接口；通用 `POST /aigc/plugin/invoke/:token` 和动态插件目录不是它的前提。基础页面另行交付，调用方可先用接口获取画框数据。

首期验收重点：

1. **真实调用链**：经过 dispatcher → Java 森林模块 → `pluginInvoke` → Python DeepForest，结果正确返回，不能只直接调用 Python 方法。
2. **名称严格匹配**：`forest-detection` 成功；大小写变化、前后空格等非法名称被拒绝；合法未知名称返回 3；不回落到内置模型。
3. **业务结果**：空结果也成功；数量与框数一致；切块重叠区域不重复计数；框与展示图的方向、尺寸一致。
4. **异常有响应**：未就绪、忙、断连、超时、坏图、结果缺字段、线程池拒绝入队均有明确错误，不悬挂、不重复应答。
5. **文件权限**：无效令牌、跨域文件和无访问权文件被拒绝；错误不泄露他人文件信息。
6. **多个提供者**：同名 Python A/B 均可接单；A 断连后从后续候选中移除；迟到响应不能匹配到另一个请求；重复上报、插件停用不留下旧能力。
7. **多个 Java 服务**：仅部分 service 安装森林模块时，网关只转发到支持该动作及目标能力的 service；缓存过期或节点下线有明确失败。
8. **插件隔离与回归**：森林模块未安装时端点不注册；已识别但未加载的模块动作返回 33；心理学和原有文本、多模态链路按原有契约工作。
9. **质量与性能**：准备带人工标注的代表性森林航拍样本，记录漏检、误检、计数误差、耗时和内存/显存占用，再确定业务精度目标及资源上限；不预先承诺未经验证的准确率。

回滚时先停止森林端点接入并等待在途任务收尾，再关闭森林模块和 Python 插件。若包含本次公共 SPI 升级，须把宿主、common 与所有相关 Java 插件作为匹配版本一起回滚。

## 9. 预计涉及的工程与文档

以下为首期涉及的工程清单；页面和持久化任务继续保留为后续扩展。

| 位置 | 工作内容 |
| --- | --- |
| `cube-server/service-forest/` | 新业务模块、场景服务、端点、构建说明 |
| `cube-server/common/src/cube/aigc/spi/` | 通用插件调用契约、SPI 版本升级 |
| `cube-server/service/src/cube/service/aigc/` | 通用插件调用、单元调度、能力记录维护、模块状态和快照扩展 |
| `cube-server/dispatcher/src/cube/dispatcher/aigc/` | 通用模块动作转发与能力选点、扩展声明契约 |
| `cube-server/build.xml` 及模块构建配置 | JDK 8 编译顺序、产物和部署规则 |
| service 与 dispatcher 的现有配置目录 | 森林模块和端点加载配置；不覆盖心理学配置 |
| `cube-server/verify/` | 跨宿主与插件的集成验收，兼容原有插件 |
| `apexnova-wisdom/cube/plugin/`、`plugins/omniforest/` | 参考设计 P1 和 DeepForest 单元 |
| `cube-server/docs/rest-api/src/`（本工作区实际存在的文档入口） | 按配套 REST 文档入口补森林接口，示例核对最终实现 |

建议采用本方案的首期边界：**一个森林业务插件、一个树冠检测能力、两个专属接口，以及一次通用插件调用与网关路由补齐。** 历史库、长任务、业务页面和其他林业识别能力按实际使用需要后续扩展。

## 附：本次核对的主要依据

- [插件框架设计 v2，重点为 §4.4、§7.3、§8](C:/Users/hermi/workspace/apexnova-wisdom/docs/2026-10-23-plugin-framework-design.md)
- [Java 工程说明中的插件开发章节](C:/Users/hermi/workspace/cube/cube-server/README.md)
- [PsychologyModule](C:/Users/hermi/workspace/cube/cube-server/service-psychology/src/cube/service/psychology/PsychologyModule.java) 与 [PsychologyEndpoints](C:/Users/hermi/workspace/cube/cube-server/service-psychology/src/cube/service/psychology/dispatcher/PsychologyEndpoints.java)
- [AIGCHost](C:/Users/hermi/workspace/cube/cube-server/common/src/cube/aigc/spi/AIGCHost.java)、[AIGCHostImpl](C:/Users/hermi/workspace/cube/cube-server/service/src/cube/service/aigc/spi/AIGCHostImpl.java) 与 [AIGCSPI](C:/Users/hermi/workspace/cube/cube-server/common/src/cube/aigc/spi/AIGCSPI.java)
- [UnitScheduler](C:/Users/hermi/workspace/cube/cube-server/service/src/cube/service/aigc/unit/UnitScheduler.java) 与 [AIGCService](C:/Users/hermi/workspace/cube/cube-server/service/src/cube/service/aigc/AIGCService.java)
- [Manager](C:/Users/hermi/workspace/cube/cube-server/dispatcher/src/cube/dispatcher/aigc/Manager.java)、[CapabilityRouter](C:/Users/hermi/workspace/cube/cube-server/dispatcher/src/cube/dispatcher/aigc/CapabilityRouter.java) 与 [ModuleAction](C:/Users/hermi/workspace/cube/cube-server/dispatcher/src/cube/dispatcher/aigc/handler/ModuleAction.java)
