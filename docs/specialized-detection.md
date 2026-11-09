# 专项检测注册、状态与调度

Java 运行和构建使用 JDK 8；Python 工程为相邻的 `apexnova-wisdom`。
REST 地址、`specializedDetection` action 和既有结果封装不变。
调用示例及错误码见 [REST 源码](rest-api/src/SpecializedDetection.js)，
Harness 配置见 [M1 单图接入](../service-harness/M1.md)。

## 注册

```json
{"name":"SpecializedDetection","task":"ComputerVision","version":"2.0","subtasks":["ObjectDetection","uav-fire-smoke","fire-smoke","thing"],"description":""}
```

Python 配置生效后设置 `subtask=None`，以已有 `subtasks` 数组序列化。
`ObjectDetection` 固定第一项，其余按配置顺序列出启用的 profile 并去重。
不增加 `AICapability` 专项字段。保留 description 与主 subtask，查询键保持稳定。
Java 只对 `SpecializedDetection` 解释后续项为 profile；其他 Unit 仍使用原子任务语义。
同一连接重新注册更新完整 subtasks（含能力删除），沿用 Unit 对象、计数和会话关联。
本版本要求节点统一升级，无旧节点隐含能力回退。

## 内部状态 action

Python 经当前 AIGC 连接发送 Packet `name=specializedDetectionStatus`：

```json
{"version":1,"sequence":3,"state":"ready","maxSessions":2,"slicedProfiles":["uav-fire-smoke"]}
```

| 字段 | 约束 |
|---|---|
| version | 整数 1 |
| sequence | 正整数，发布器生命周期内递增；同一注册连接拒绝重复或乱序 |
| state | registered / initializing / ready / failed / stopping / stopped |
| maxSessions | 生效的 Python 上限，整数 1…64 |
| slicedProfiles | 生效模型绑定支持切片的 profile，去重且为注册列表子集 |

所有已启用模型加载、预热成功后才能报告 ready。状态变化立即唤醒发布线程，
每 10 秒补发最新状态；ACK 期限 2 秒，失败后继续补发最新快照。
ACK 使用原请求 sn/action 和既有 Packet 响应封装，code=0 接受、code=1 拒绝。
网络 I/O 不持有模型生命周期锁；状态更新不执行完整 setup，不重置其他插件状态。

Java 按当前注册连接核验报告；30 秒无有效报告即未就绪。重连及重新注册后需要新报告确认。
能力快照 specializedDetection.providers 与 Harness 媒体发现输出 profiles、slicedProfiles、
state、ready、statusExpiresAt、maxSessions、availableSessions、activeSessions、pendingStarts、availableImages。
消费者在发送前重新核验状态有效期；ready 布尔值本身不能代替租约。
dispatcher 对包含检测提供方的快照每 10 秒刷新，避免沿用普通能力的 30 秒刷新周期造成租约间隙。

## 请求与容量

`detect/start` 的 `option.classes` 为可选的非空字符串数组，按首次出现顺序去重；
省略该字段时不限制类别，检测所选方案的全部可识别类别。
null、空数组、空白名称与错误类型返回参数错误。Java 不枚举限制 profile，也不硬编码
切片服务名称；规范类别由 Python 根据实际绑定的类别契约验证。

```json
{"channel":"inspection-001","input":{"files":["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"],"option":{"profile":"thing","classes":["person","car"],"confidence":0.4}}}
```

`files` 示例须替换为当前用户实际上传完成的 64 位文件码。视频 `start` 仍接受文件码或 RTSP，
必须提供 callbackUrl；类别、模型、工件摘要和 parameters 等元信息完整保留。
`status/stop` 根据 streamId 回到原会话提供方，不再按 profile 选择；停止期间仍允许控制请求。

service、dispatcher 先匹配注册 profile，再检查就绪、切片和剩余容量，发送前复核。
无有效提供方或匹配节点未就绪返回 503；不支持请求能力返回 400；匹配且就绪但容量耗尽返回 429。
Java 未配置会话限制时采用 Python maxSessions；显式全局和节点限制均取更小值。
所有 profile 共用该节点的会话预算和图像准入，不会按 profile 复制容量。
可配置项见 [示例](../service/config/specialized-detection.properties.example)。

Harness 保持 `harness.media.dedicated` 独占部署前提，媒体预检、持久预留和实际发送使用同样的
profile/切片/有效就绪/图像余量匹配条件。同一联系人只有一个持久物理槽位，未知执行保留槽位直到物理完成回执。

## 验证入口

- `cube.service.test.SpecializedDetectionCapabilitiesTest`：七种注册组合、查询键与对象稳定、能力增删、状态序号/租约、异构节点和上限收紧。
- `cube.service.test.SpecializedDetectionTest`：服务协议、生命周期、共享容量、超时和会话归属。
- `cube.dispatcher.test.SpecializedDetectionRoutingTest`：异构节点选路、发送前复核和真实本机 REST 错误映射。
- `cube.service.test.SpecializedDetectionShmTest`（`service/transport-test`）：真实 SHM、Python 实际能力序列化及独立状态发布/ACK、三 profile dummy 图片和视频控制。
- `service-harness-skill` 的 `ant test`：严格 schema、类别参数、就绪/切片预检、真实 JDBC 持久槽位与原有媒体链路。
- Python：`python -m unittest discover -s tests -p "test_specialized_detection*.py"`，以及 `test_detection_profiles.py`、`test_unit_initialization.py`。

SHM 测试使用 dummy 推理，证明跨语言协议与路由，不代表真实权重、GPU 或摄像头的验证结果。
Python 既有真实权重记录保留在该工程 `docs/specialized-detection-validation.md`。

2026-11-09 本轮使用 Temurin OpenJDK **1.8.0_504** 完成 common、dispatcher、service 的 Ant build-debug，
以及 service-harness、service-harness-skill 构建和测试。结果如下：

| 验证 | 结果 |
|---|---|
| 检测能力与服务协议 | 109 + 312 项断言通过 |
| dispatcher 路由与 HTTP | 106 项断言通过 |
| 插件单元回归 | 31 项断言通过；另覆盖检测状态更新不清除同连接插件就绪 |
| 真实 SHM | 63 项断言通过；三个 profile 的图片、视频、classes、无人机切片及模型元信息，dummy 推理 |
| Harness | 全套 ant test 通过；专项 options 14 项断言，含真实 JDBC 共享物理槽位测试 |
| 作者侧配套契约 | 8 份 schema、25 个正反例通过 |
| Python | 检测/视频/状态 32 项、profile 13 项、初始化 3 项、插件注册 36 项，共 84 项通过 |
| REST 文档 | 已生成 110 个接口；5 项文档构建与同步检查通过 |

Python 视频测试使用本机 FFmpeg/FFprobe 和真实 HTTP PNG 接收端；本轮未重新加载真实模型权重，
未验证 GPU 推理和实际摄像头场景。wisdom 原有检测改造、权重验证记录及其他未提交内容保留。
