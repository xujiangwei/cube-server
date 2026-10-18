# N08/N09：可信 CPU Runner 与通用工具 Broker

本实现面向经管理员审核的 Python 标准库工具包，使用实际 JDK 8、Python 3.11、独立数据库 schema5 和 Cell v4。一个 Java Harness JVM 可连接多个同角色 Runner；每个单元独占 workerId、runtimeDir、journal、实例身份和租约。原 v1 注册、v2 scripted 模型及 v3 Java 推理网关保持各自协议。

核心仅保留显式启用的 loopback Mock 测试 provider（`mockconsole.query_status`），用于离线协议回归。实际业务由各个 SKILL/provider 独立提供，核心不包含 Console 业务实现。此实现不能执行模型生成代码，也不是 N14 的动态代码沙箱。

## 显式启用

保留 [EXECUTION.md](EXECUTION.md) 的独立身份、模型、仓库和存储配置，增加下列私有配置。先用专用迁移账户运行现有 `StoreMigrations` CLI，显式升级到 schema5；运行账户不执行 DDL。新增加四张 `harness_*` 表，001～004 迁移不变。

```properties
execution.tools.enabled=true
execution.permissions=skill.list,skill.activate,resource.list,resource.read,resource.search,console.status.read
worker.runner-a.role=runner
worker.runner-a.capacity=1
worker.runner-b.role=runner
worker.runner-b.capacity=1
runner.grant.key=replace-with-private-random-key-at-least-32-characters
broker.node.id=unique-stable-harness-node-id
broker.mock.enabled=true
broker.mock.url=http://127.0.0.1:18090/status
broker.mock.token=replace-with-private-random-token-at-least-24-characters
broker.mock.targets=node-a,node-b
broker.mock.fields=cpuPercent,memoryMiB
```

多个 Java 节点必须使用不同且重启后稳定的 `broker.node.id`。构造 Broker 时只将该节点原有 dispatched 窗口转为 unknown，避免重发可能已发送的上游请求。Java 保存的 grant 使用 HMAC-SHA256；key、上游 token 和地址均不进入脚本或模型上下文。开发 API 的身份仍由可信配置固定，尚无生产身份源。

Python 使用原 `harness/requirements.txt` 的六个固定版本依赖，无 torch、模型库或额外 Runner 包。配置参见 Python `harness/config/runner-cpu.template.json`，一个进程可配置 1～8 个槽，必须与 Java 白名单一致。`requestTimeout` 不超过 3 秒。以非管理员/非 root 身份运行，提升身份直接拒绝；未提供提升身份绕过开关。旧无 `runner` 配置的 G0 runner 保留 scripted.echo。

## 发布与执行

新包使用 manifest schemaVersion=2，固定审核后的 `.py` entrypoint、文件 SHA256、空 `dependencies.lock.json` 和明确的输入/输出 schema。包内容不超过 64 KiB；工具的参数/结果 schema 必须与服务端注册完全一致。发布端必须显式配置 `runtime.allowTrustedExecutables=true` 才能 validate/upload；原签名、公钥和本地 unsigned 开关继续生效。默认拒绝 executable。

作者样例在工作区 `skill/harness-packages/mock-status/`。用既有 `build_package.py` 生成 ZIP，再通过 `RuntimeAdmin` validate/upload。先发布 schemaVersion=2 的 `trusted-python-v1` RuntimeProfile，再发布引用该 runtime/release 的新 TaskProfile；toolSetVersion=`mock-tools-v1`，requiredCapabilities 包含 `tool.execute`。模型仍先 `skill.activate`，然后才能提议该包工具；不会读取旁边任意脚本后自动执行。

唯一 RunCoordinator 写入 call intent；Broker 验证固定 profile、权限交集、effect、schema、目标和字段，然后进入持久化有界队列（256）。Runner pull 仅选择健康、未 drain 且具有实际容量的单元，先提交本地 SQLite FULL journal，再请求 Java durable accepted。grant 固定 run/call/attempt/release/参数摘要/目标/字段/tenant/subject/原 fence/worker/runtime/60 秒 TTL；accepted 和上游派发前重新授权。

Runner 用固定解释器 `-I -S -B` 运行已校验包，子环境仅含必要的系统目录和独立临时目录。脚本只有 arguments、packageRoot、workspace；stdin/stdout 使用严格逐行 JSON IPC。SDK `query` 只传目标和字段（旧 `query_status` 为兼容别名）；Broker 选择已安装的 provider，provider 按私有配置访问并投影结果，核心验证固定 schema，再返回脚本。stdout 调试文本、重复字段、额外消息、超限或无合法结果均失败；stderr 有界。Python worker 的网络等待与进程监督分离，丢失 Cell 响应不会阻止监督线程检查超时/取消。

每任务最多 30 秒、stdout/stderr 各 32 KiB、工作区最多 10 MiB/256 项，并进一步收紧到 RuntimeProfile 的配额。工作区按周期扫描，超额后终止整树；这不是恶意进程的 OS 硬磁盘配额。审核策略默认拒绝 socket/native 注册表访问、任意 subprocess 和工作区外写入。SDK 只允许启动同包声明的子 entrypoint。Windows 在交付脚本 context 前绑定 kill-on-close Job Object，取消后核验活动进程数为零；POSIX 使用独立进程组，原生 Linux 验收仍待完成。

结果先进入 Runner journal，再重送给 Java；Java 校验严格 tool-result、包输出 schema 及授权投影后保存，才返回原 report 摘要作为 ACK。无效输出保存 `TOOL_OUTPUT_INVALID` 并 ACK，坏输出不进入模型。传输重送不增加 attempt。Broker 有 2 个 provider 查询线程、32 个排队项和最多 34 个执行/待提交结果；HTTP 超时、大小及截止限制独立于 Cell 回调。结果 DB 提交失败只重试提交内存中的同一结果，不重复 HTTP。

## 故障、保留与撤回

断线、租约过期、drain、取消请求或 ACK 都不能证明执行结束。真实树收尾前保留 Java 槽；journal 中 running 在重启后转 unknown，不自动重跑，即使 unknown report 已 ACK 也不恢复健康。workerId 持久绑定原 runtimeId 和容量；换目录或换实例不能绕开未决任务。Runner 可注册为 ready=false 并重送 unknown 证据，启动 CLI 会保留这种隔离状态。

执行结束而 Broker 效果仍未知时，物理槽可以依据实际结束释放，但 run 的 executionSettlement 保持 unknown，禁止下一模型轮或自动补执行。当前 owner 可以接收原 intent/fence 的历史结果；旧 fence 无权发起新的 Broker 操作。不承诺通用 exactly-once。N11 已开放 HTTP cancel/resume/approval，见 [CONTROL-FILES.md](CONTROL-FILES.md)。

保留 journal、结果未 ACK 的工作区和 Java 未决账本。N12 已接通 ACK/结算后的过程文件自动清理，见 [CONTROL-FILES.md](CONTROL-FILES.md)；为独立运行根配置磁盘预算，不能删除 unknown 记录以恢复容量。撤回先禁用 executable profile/新派发，定向停止各 Runner 并保存 journal；不要直接用 `execution.tools.enabled=false` 切断仍需报告的未决 Runner。结算后再关闭入口。schema5 是加法迁移，旧 schema4 二进制会拒绝新库；需要回旧版本时使用停机前专用库备份，不删除新表伪造降级。

验证使用 `ant test-tools`、既有 test-store/runtime/execution/inference、Python unittest 及 `scripts/n08_n09_smoke.py`。本轮完整命令、两库结果、真实多进程记录与撤回边界见工作区 `harness/execution-records/n08-n09-20261018/N08-N09/report.md`。Windows 本机专项通过不替代 N02 原生 Linux、N06 在线、N10 或 G2 门槛。

## 独立业务 SKILL 的 provider 注册

新增包使用 manifest-v4，工具名必须以技能命名空间开头；自定义 `brokerOperation`/权限通过部署端 `QueryProvider` 注册绑定。输入/输出 schema 必须与该 provider 一致。v1 资源包与 v2 Mock 包继续支持；旧 v3 Console 特例保持历史 schema，不再接收新发布/新 run，需先 drain 旧节点再重新发布 v4。

```properties
broker.providers=your-skill
broker.provider.your-skill.class=your.skills.ReadOnlyProvider
broker.provider.your-skill.config=C:/private/your-skill.properties
```

管理员单独安装可信扩展 JAR 到 stage `libs/`。扩展在 JVM 内运行，必须审核；上传技能不会安装 Java 类。每个 provider 使用独立配置与 operation；可并存多个 provider。不配置任何 provider 时服务可启动，相关调用返回 TOOL_PROVIDER_UNAVAILABLE。旧 broker.type 配置会明确拒绝，避免误用历史业务内建入口。

平台常量只列核心资源/文件权限；业务权限取已固定 release 声明与可信身份/TaskProfile 的交集，派发和访问前再次核验 provider 的权限、schema 与范围。Cell v4 的当前 query 协议限定 targets/fields，并未提供任意 HTTP、shell 或动态代码能力。

服务器状态业务示例、独立 Console 配置和认证脚本位于工作区 [server-status](../../skill/harness-packages/server-status/README.md)。`test-tools` 包括另一个 weather 夹具 provider，验证自定义权限、独立注册、未安装拒绝、schema 绑定、私有配置隔离与持久结果重放；没有访问天气网络服务。业务 API/单位的专项由各技能测试负责。


## 通用调用扩展（N13～N15）

原Cell v4 targets/fields协议保持不变；manifest-v5 + Cell v5新增broker_invoke，可信调用身份和受控input/artifact由Broker提供，不从模型参数读取。v4 Runner不能领取v5作业；Python配置runner.protocolVersion=5选择通用协议，默认4。部署时drain旧worker并按匹配摘要升级两端。新SKILL使用固定broker-tools-v5工具集；普通Runner仍只执行审核薄入口，动态代码交给另装的强隔离provider。扩展契约、安装/认证和默认关闭范围见工作区 `skill/harness-extensions/README.md`，追踪与质量见 [TRACE.md](TRACE.md)。


动态代码另用 manifest-v6 + Cell v6 + sandbox-tools-v6。专用Python Runner的可信父进程启动Firecracker/KVM；Java WorkerExecutionProvider只声明profile并转换认证单元回执，不启动宿主程序或做本机清理。按worker核对profile/certificate摘要，v4/v5不得领取v6作业；v6仅领取sandbox任务。控制器、宿主路径及权限只在配套Python工程harness/microvm部署，见工作区skill/harness-extensions/microvm/README.md。
