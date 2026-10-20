# 开发环境 HTTP Relay

Relay 用于开发人员本机无法安装大模型时调用另一台 Cube，支持旧聊天、OpenAI/Claude REST 接口和 Harness 联调。生产环境应关闭 `relay`。

`config/aigc.properties` 示例：

```properties
relay=true
relay.url=http://remote-cube:7010
relay.token=YOUR_REMOTE_CUBE_TOKEN
relay.protocol=openai
relay.capacity=1
relay.timeout.seconds=300
relay.max.tokens=2048
```

`relay.url` 是远端 dispatcher 的业务 REST 根地址，不是 console 地址，不带 `/v1`。令牌来自远端 Cube 授权系统。`relay.protocol` 可选 `openai` 或 `claude`；两种配置都支持本地 `/v1/chat/completions` 和 `/v1/messages`。Claude 上游发送 `x-api-key` 和 `anthropic-version: 2023-06-01`；OpenAI 使用 Bearer。旧聊天始终使用 `/aigc/chat/{token}/`，保留实际生成参数和历史。`relay.timeout.seconds` 默认 300、范围 1–600；`relay.max.tokens` 默认 2048，用于 Claude 请求缺少长度限制时。

Relay 向 UnitScheduler 注入 Contact ID 为 `100000` 的 Baize、Baize2 代理单元。上层继续使用 `select`、`selectIdle` 及普通单元队列；底层根据 Relay 自有上下文转发 HTTP，不仅凭 Contact ID 识别代理。真实单元可以共存。代理没有真实 Unit 注册后的 60 秒忙碌期，关闭或替换 Relay 仅删除它自己注入的对象。

结构化能力启动后立即探测，并每 10 秒从远端 OpenAI 格式 `/v1/models` 刷新；单次探测总期限为 3 秒。`chatCompletion.text/images/tools/stream/parameters` 取远端声明与协议转换能力的交集。模型 ID 必须与远端 Cube 的 Baize/Baize2 一致，本地账号模型权限仍然生效。认证失败、连接失败、异常模型列表会清空结构化能力；远端未返回的模型也不会出现在本地结构化模型列表中。此时代理对象仍用于旧聊天的兼容路径，其存在本身不表示结构化模型已就绪。

模型列表和 Harness 就绪查询读取内存能力，不执行调度选择或同步请求远端。`registered=true` 表示 Python 节点登记成功，`ready=true` 还需要远端声明所需的文本或工具能力。本地 Harness 的 backend token 仍是本地 Cube Token；远端认证统一使用 `relay.token`。

每个代理模型沿用串行队列。`relay.capacity`（1–32）约束不同模型、REST、Harness 和旧聊天共享的本地 HTTP 槽位，不代表远端物理 GPU 数量。CompletionProvider 保留 `registered/available`，并提供可选 `capacityGroup` 和 `capacityGroups`；组项包含 `route/registered/available/groupCapacity/groupAvailable`。仅返回 `capacityGroup` 时，计数表示整个共享池；返回明细时按 route 去重。Harness 在多个模型及 backend ID 之间遵守共享组上限，先分配空闲可执行路由，避免忙碌的 worker 占走其他 backend 的可用额度。繁忙时 available 为 0，不把忙碌等同于未就绪。

超时包含排队和 HTTP 时间，并受调用方剩余期限限制。只有有效终态或确定的前置拒绝才释放已尝试的 HTTP 槽位；断流、取消、网络异常不能证明远端推理结束。Harness 保留未知执行的账本和占用，不自动重试或估算 usage。Relay 的不确定槽位保留到实例停用；排查远端实际执行后按开发环境运维流程重新初始化，单纯刷新模型列表不会释放槽位。

Claude 不支持的 seed、惩罚参数不声明；无法保留的 developer 角色、消息 name、非前置 system 或图片 detail 会明确拒绝。SSE 必须具有合法终态和真实 usage，工具由客户端执行。停止序列仅在远端实际返回时传递；OpenAI choice 使用可选 `stop_sequence` 扩展，旧远端未返回时不推测。

配置仍沿用服务每五分钟的检查周期。同值配置保留已有 Relay 和在途状态，配置变更、禁用或服务停止会关闭旧实例。日志不输出令牌、提示词、图片、工具参数或完整响应。

## 回归验证

必须使用实际 JDK 8，先构建 common、service、dispatcher。协议集成测试位于 `transport-test/`，独立于 service 正常构建，避免引入 service 对 dispatcher 的生产依赖。在 service 目录运行：

```powershell
$cp = 'build/classes;../common/build/classes;../dispatcher/build/classes;../deploy/bin/cell.jar;../deploy/libs/*'
New-Item -ItemType Directory -Force build/relay-test | Out-Null
& "$env:JAVA_HOME/bin/javac.exe" -encoding UTF-8 -cp $cp -d build/relay-test transport-test/cube/service/test/RelayIntegrationTest.java
& "$env:JAVA_HOME/bin/java.exe" -cp "build/relay-test;$cp" cube.service.test.RelayIntegrationTest
```

测试使用本机临时端口和模拟远端 Cube，不需要模型权重或真实令牌，验证双协议 JSON/SSE、图片、工具往返、生成参数、真实用量、断流、超时、身份隔离和配置切换。另运行 `ChatCompletionServiceTest`、dispatcher Completion 测试，以及 service-harness 的 `test` 和 `test-inference`。
