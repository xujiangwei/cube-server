# Harness 工具与 Runner

工具链路由 Java Coordinator/Broker、可信业务 provider 和 Python Runner 组成。Java 固定调用身份、权限、参数及执行意图；Runner 执行受控作业并持久保存结果，Java 在确认执行证据后推进任务。

| 类型 | 用途 | 配套契约 / task 工具集 |
| --- | --- | --- |
| 内建资源工具 | Java 读取固定技能资源，不运行代码 | `resource-tools-v1` |
| `tool` Runner | 执行管理员审核的 Python 标准库工具入口，通过 Broker 调用业务 provider | `contracts/runner-tool/` / `broker-tools` |
| `sandbox` Runner | 在专用 Python 单元中隔离执行动态代码 | `contracts/runner-sandbox/` / `sandbox-tools` |

一个 Java JVM 可连接多个同角色 Runner，每个独占 workerId、runtimeDir、journal、实例身份和租约。调度检查角色、类型、能力、健康、drain 状态及实际容量。

## 启用可信工具

按 [EXECUTION.md](EXECUTION.md) 配置存储、执行、模型与资源仓库，然后补充私有配置：

```properties
execution.tools.enabled=true
worker.runner-a.role=runner
worker.runner-a.capacity=1
worker.runner-b.role=runner
worker.runner-b.capacity=1
runner.grant.key=<至少32字符的私有随机密钥>
broker.node.id=<当前Java实例唯一且重启后稳定的标识>
```

`execution.permissions`、租户业务策略和技能 release 均须包含实际业务权限。grant key、上游 token 和地址留在 Java 私有配置中，不进入脚本或模型上下文。服务启动自动建立工具调度与 Broker 所需账本结构。

Python 使用 `harness/config/runner-cpu.template.json`，`runner.type=tool`、runtime=`trusted-python-v1`，每进程容量为 1～8，须与 Java 授权一致；requestTimeout 不超过 3 秒。以非管理员/非 root 身份运行。依赖使用 Python 工程的 `harness/requirements.txt`。未配置执行 Runner 的基础单元仅保留 scripted.echo。

## 技能包与 provider 部署

manifest 使用 `schemaVersion=6`，工具名处于技能命名空间，普通工具声明 `runnerType: "tool"`、固定 brokerOperation/权限、输入输出 schema 及审核后的 `.py` entrypoint。包固定文件摘要、空 dependencies lock，总大小不超过 64 KiB。模型输出代码不能通过普通工具入口运行。

发布端设置 `runtime.allowTrustedExecutables=true`，使用 [RUNTIME.md](RUNTIME.md) 的签名发布流程。在服务器 `harness-execution/` 配置 `mode=trusted-python-v1`、`toolSetVersion=broker-tools`、`capabilities` 包含 `tool.execute` 的执行器；`execution.json.requiredCapabilities` 定义通用能力要求。租户 policy 仅发布技能 release 与业务权限。客户端可在每次请求中选择该执行器，兼容技能无需重新发布。显式选择的技能立即激活；其余技能由模型按需激活。POST `/runs` 不支持指定工具，服务端计算并固定有效工具名单，调用及恢复执行均校验此范围。

实际业务由独立 QueryProvider 扩展提供。安装审核后的扩展 JAR 到发布目录 `libs/`，配置：

```properties
broker.providers=your-skill
broker.provider.your-skill.class=your.skills.ReadOnlyProvider
broker.provider.your-skill.config=C:/private/your-skill.properties
```

provider 在 JVM 内运行，独立配置地址、密钥、operation 和策略；可并存多个 provider，重启时注册。上传技能包不会安装 Java 类。未安装 provider 的相关调用返回 `TOOL_PROVIDER_UNAVAILABLE`；schema、权限及范围须与技能声明一致。SDK `query(targets,fields)` 转换为受控调用，不提供任意 HTTP 或 shell。

服务器状态示例见工作区 [server-status](../../skill/harness-packages/server-status/README.md)；Console 地址与认证在该 provider 独立配置。通用扩展见 [harness-extensions](../../skill/harness-extensions/README.md)。

离线联调可显式启用 `mockconsole.query_status`，配置 `broker.mock.enabled/url/token/targets/fields`；仅访问 loopback Mock 服务。实际业务部署使用自己的 provider。

## 工具数据流

1. Coordinator 保存唯一 call intent。Broker 复核固定 profile、有效权限、effect、schema 和范围，放入最多 256 项的持久队列。
2. Runner 拉取作业，先写本地 SQLite FULL journal，再向 Java 提交 durable accepted。grant 绑定 run/call/attempt/release、参数摘要、范围、tenant/subject、原 fence 和 worker/runtime，有效期 60 秒；accepted 和上游派发前再次授权。
3. Runner 用固定解释器 `-I -S -B` 启动已校验入口。脚本获得 arguments、packageRoot、workspace，通过严格逐行 JSON IPC 访问 SDK；Broker 根据固定定义选择 provider，访问上游并校验投影结果。
4. Runner 将结果写 journal，再向 Java 报告。Java 校验 tool-result、输出 schema 和授权范围，持久保存后 ACK 原 report 摘要。坏输出保存为 `TOOL_OUTPUT_INVALID`，不进入模型；传输重送不增加 attempt。

普通工具每次最多 30 秒，stdout/stderr 各 32 KiB，工作区最多 10 MiB/256 项，并受 RuntimeProfile 配额进一步收紧。工作区周期扫描，超额终止执行树。脚本只可通过 SDK 启动同包声明的子入口，拒绝任意网络、子进程及工作区外写入；可信工具进程不是恶意代码的通用安全沙箱。

Windows 使用 kill-on-close Job Object，取消后核验进程树结束；POSIX 使用独立进程组。网络等待与进程监督分离，Cell 超时不阻止本地取消/超时处理。Broker 使用独立有界 provider 线程池；数据库提交失败只重试保存同一结果，不重复上游 HTTP。

## 执行证据与清理

断线、租约过期、drain、取消或 ACK 不能单独证明物理执行结束。Runner journal 中 running 在重启后转 unknown，不自动重跑；workerId 绑定原 runtimeId 和容量，换目录不能绕开未决任务。未确认执行保留槽，单元可注册为 ready=false 并重送证据。

工具进程已结束但上游业务效果未知时，可据物理证据释放进程槽，run 的 executionSettlement 仍为 unknown，禁止下一模型轮或自动补执行。当前 owner 可核验原 intent/fence 后接收历史结果；旧 owner 无权发起新的 Broker 请求。

结果 ACK、call.confirmed、非 unknown 且物理树已结束后，Runner 按绑定 runtime/jobHash/resultHash 的清理指令删除自己的过程目录，保留 journal；见 [CONTROL-FILES.md](CONTROL-FILES.md)。停机先关闭新准入并完成对账，保留未决作业所需 provider 和原配置。

## sandbox 部署

动态代码工具声明 `runnerType: "sandbox"`，task 使用 `sandbox-tools`，由专用 Python `runner.type=sandbox` 单元执行。可信父进程启动 Firecracker/KVM；Java WorkerExecutionProvider 只转换认证配置与回执，不在 Java 宿主执行程序或清理 microVM。

Java 逐 worker 授权 runner 角色、容量及 sandbox profile/certificate 摘要，须与实际部署一致；tool 单元不能领取 sandbox 作业，sandbox 只领取其专用作业。控制器、宿主路径和 KVM 权限配置在 Python 工程，部署说明见 [microvm](../../skill/harness-extensions/microvm/README.md)。
