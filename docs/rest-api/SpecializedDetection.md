# 专项检测

该接口使用 dispatcher 业务 REST 地址，沿用 AIGC token、设备与平台校验。成功直接返回业务 JSON；失败返回 `{"error":{"state":HTTP状态码,"code":Unit状态码,"reason":"...","message":"..."}}`。JSON 请求体最大 64 KiB。

Java 通过 `specializedDetection` action 调用能力名为 `SpecializedDetection` 的 Python Unit，协议固定为 `{unit,files,option}`。本接口不接收提示词、聊天历史或 `content`，不支持 `update`。频道仅用于校验归属，不设置聊天 `processing`，也不写聊天历史。

## 图片检测

```http
POST /specialized-detection/base/{token}
Content-Type: application/json
```

```json
{
  "channel": "inspection-001",
  "input": {
    "unit": "SpecializedDetection",
    "files": ["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"],
    "option": {"profile": "fire-smoke", "confidence": 0.4}
  }
}
```

`unit` 可省略；`option.operation` 默认为 `detect`。`files` 必须恰好包含一个已经上传完成的文件码（64 位字母数字）。Java 查询文件并验证域和所有者，再转换为 FileLabel 发送给 Python。外部请求不接收 FileLabel 对象、本地路径或 HTTP 媒体地址，不额外下载 HTTP 媒体。

`profile` 必填，只支持 `fire-smoke` 和 `uav-fire-smoke`，且必须在目标实例的 Java 配置中启用。`confidence` 可省略，省略时使用 Python 配置；范围为 `[0,1]`。`sliced` 为布尔值，默认 `false`，仅 `uav-fire-smoke` 可设为 `true`。Python 使用 640 切片、20% 重叠并恢复到原图坐标；具体推理行为以 Python 部署为准。

成功示例（无检测也算成功）：

```json
{
  "sn": 123456789,
  "unit": "SpecializedDetection",
  "timestamp": 1793322000000,
  "response": "",
  "thought": "",
  "resultPayload": {"profile": "fire-smoke", "width": 1920, "height": 1080, "refs": []},
  "performance": {"inputTokens": 0, "outputTokens": 0, "elapsed": "30"}
}
```

`sn` 为 Java 单元调用关联号；`timestamp` 为 Java 接收结果的 Unix 毫秒时间。命中时 `refs` 中包含 `content`（`fire` / `smoke`）、`classId`（0 / 1）、`boxes`（`x1,y1,x2,y2,confidence`）和 `points`。坐标基于方向校正后的原图像素。空 `response` / `thought`、零 token 和 `refs=[]` 均合法。图片检测沿用用量账本，真实零 token 保存为 actual，不做文本估算。

## 视频启动与控制

```http
POST /specialized-detection/stream/{token}
Content-Type: application/json
```

```json
{
  "channel": "inspection-001",
  "input": {
    "files": ["rtsp://camera.example.com/live"],
    "option": {
      "operation": "start",
      "profile": "uav-fire-smoke",
      "sliced": true,
      "callbackUrl": "https://receiver.example.com/detection",
      "intervalSeconds": 10,
      "inferenceFps": 1
    }
  }
}
```

`start` 接受一个上传视频的文件码，或 `rtsp://` / `rtsps://` 地址；`profile`、HTTP(S) `callbackUrl` 必填。可选 `confidence`、`sliced` 与图片一致；`intervalSeconds` 范围 `[0,86400]`，`inferenceFps` 范围 `[0.1,60]`，省略时使用 Python 配置。

启动只表示 Python 已创建后台会话，不能据此认定媒体已打开、首帧已到达或已产生检测结果。响应保留图片示例相同的外层字段，`resultPayload` 为会话快照，包含 `streamId`、`status`、`profile`、统计数据等。典型初始状态为 `starting`，后续可为 `running`、`reconnecting`、`stopping`、`stopped` 或 `failed`。

客户收到启动结果前，service 与 dispatcher 都已登记原始实例路由。随后使用同一域、同一联系人身份查询或停止（频道仍要属于调用者）：

```json
{
  "channel": "inspection-001",
  "input": {"files": [], "option": {"operation": "status", "streamId": "<启动返回的streamId>"}}
}
```

停止时仅将 `operation` 改为 `stop`。控制参数只允许 `operation` 和 `streamId`，不能修改 profile 或回调地址。`stopping` 仍占用会话名额，直到终态应答、可信停止事件或确定的会话不存在结果。每个 Python 实例保留最近 20 个终态路由，支持最后查询和重复停止；Python 自身更早移除的会话仍可能返回 404。Java 每 5 秒通过独立有界控制线程池核对活动会话，补偿停止事件丢失。

## PNG 回调

视频命中由 **Python 直接**向 `callbackUrl` POST 原始 PNG 字节，Java 不代理、不保存告警。请求头为 `Content-Type: image/png` 和 `X-VLM-Event-Id: <稳定事件ID>`。图片中绘制火焰与烟雾检测框，并在 PNG 的 `vlm_meta` iTXt 块嵌入 UTF-8 JSON，包含 `eventId,streamId,unit,profile,sequence,timestamp,offsetSeconds,resultPayload,response,performance`。

```python
import json
from PIL import Image
with Image.open("callback.png") as image:
    metadata = json.loads(image.info["vlm_meta"])
```

回调的 `timestamp` 为帧接收时间（Unix 毫秒）；`offsetSeconds` 为视频媒体时间或 RTSP 会话经过时间。接收方用事件 ID 去重。Python 默认回调超时 10 秒、最多 3 次尝试，同一事件重试复用 ID；网络错误、408、429、5xx 可重试，不跟随重定向。PNG 默认上限 20 MiB。回调失败计入 `callbackFailures`，检测继续；无命中不产生回调。

## 调度预算与失败边界

service 默认数据池 8 线程、64 个排队名额，控制池 2 线程、64 个排队名额。每实例最多一个图片在途请求，默认两个后台会话；`starting/running/reconnecting/stopping` 均占用预算。队列满或容量不足返回 `Processing(7)`。数据期限默认 180 秒，控制期限默认 30 秒，包含排队与文件准备；dispatcher 默认分别等待 190 秒和 40 秒，以便返回 service 的错误。

配置示例见 [service 配置](../../service/config/specialized-detection.properties.example) 和 [dispatcher 配置](../../dispatcher/config/specialized-detection.properties.example)，分别复制到 `aigc.properties` 和 `dispatcher.properties`。全局设置 `specialized.detection.profiles`、`specialized.detection.max.sessions`；按域和联系人覆盖格式为 `specialized.detection.node.<domain>.<contactId>.profiles` / `.max.sessions`。线程和排队名额变更需要重启 service。

这些配置仅代表 **Java 调度预算**，必须与 Python 的 profiles、max_sessions 等部署配置对齐。能力快照中的 `specializedDetection.version=1` 只表示 service 实现了本 action；摘要标明 `readiness=unknown`、`budgetSource=java-config`，并不宣称知道 Python 模型就绪状态或实际推理队列容量。Python 返回的忙碌、未就绪与参数错误仍是最终结果。dispatcher 排除未明确支持该 action 的旧 service。

视频启动和控制只统计请求，不将其当作逐帧推理计量。控制始终回到原 service 和 Python 连接，不因能力快照过期或活动会话长时间闲置而改派。连接替换后旧路由失效，不把会话迁到新进程；Java/service/dispatcher 重启后不恢复会话。

Java 不自动重试已经发出的 `start`。传输超时不证明远端任务已停止，保留不确定预留；若迟到应答证实启动成功，Java 自动请求停止并在确认终态后释放名额。若没有迟到证据，或启动返回无法证明未创建后台任务的单元内部错误，则保守保留预留至原连接失效。调用方收到超时后立即再次 `start` 可能创建重复任务。

| Unit code | HTTP | 含义 |
| --- | --- | --- |
| 1 | 400 | 参数不合法 |
| 3 | 404 | 文件或会话不存在 |
| 7 | 429 | 忙碌、队列或预算耗尽 |
| 21 / 22 | 401 | 认证失败 |
| 25 | 403 | 文件、频道或会话不属于调用者 |
| 30 | 503 | 单元未就绪、无匹配实例 |
| 31 | 502 | 单元处理失败或响应不合法 |
| 32 | 422 | 文件处理失败 |
| 31 + `DETECTION_TIMEOUT` | 504 | 传输或请求期限超时 |

## 验证

`cube.service.test.SpecializedDetectionTest` 使用真实 service 入口、执行器和元任务，模拟 Python 连接；`cube.dispatcher.test.SpecializedDetectionRoutingTest` 使用真实路由、Manager 和 Jetty HTTP 端点，模拟 service 连接。二者以 JDK 8 的模块 classes 和依赖 jars 运行，不依赖真实权重或摄像头。此类协议测试不等同于真实 SHM 联调或模型精度验证。

可选真实 SHM 测试位于 `service/transport-test/cube/service/test/SpecializedDetectionShmTest.java`，单独编译到 service 的测试 classes 后运行。依次传入 Python 可执行文件、配套 Python 工程绝对路径、`service/transport-test/specialized_detection_bridge.py` 绝对路径。它在本地临时端口运行 Cell，调用真实 Python 专项 action handler 和 `SpecializedDetectionUnit(dummy=True)`，覆盖两个 profile 的图片、启动、状态、停止与重复停止。文件存储适配器生成 32×24 测试图片；不访问真实文件服务、不下载权重、不连接摄像头，也不代表实际视频解码、PNG 回调或真实模型精度验证。
