# Harness Cellet

Java 会记录每次 Python 节点注册结果：协议 1 的 `register`、协议 2–6 的 `describe`。成功使用 INFO，预期拒绝使用 WARN，内部异常使用 ERROR，沿用 `#process - ...` 日志格式。日志包含 workerId、角色、实例、协议、requestId；模型节点另有 model/backend。`registered=true ready=false` 表示登记成功但后端或节点未就绪，拒绝则打印现有 errorCode（例如 UNAUTHORIZED、FORBIDDEN、DUPLICATE_WORKER）。正常心跳和 poll 不重复打印注册日志，凭据和 sessionId 不进入日志。

开发环境可通过同 JVM AIGCService 的 [Relay](../service/RELAY.md) 联调远端 Cube。Relay 代理保留 `100000` 联系人身份，但 Harness 依据远端真实 `chatCompletion` 能力判断就绪，并按共享容量组限制多个模型及节点的总并发。

业务 REST 统一由 dispatcher 提供，通过 `Performer.syncTransmit` 和 Cell SHM 长连接访问 HarnessCellet；文件使用同连接的原生 SHM 流。service HTTP 只保留健康检查和现有运维入口，业务路径返回 404。部署、身份、协议与完整传输测试见 [TRANSPORT.md](TRANSPORT.md)。dispatcher 与 service-harness 需配套升级，数据库与 Python worker 协议不变。

正式服务是 `cube.service.harness.HarnessCellet` + `HarnessService`，NAME=`Harness`。产物 `cube-service-harness-3.0.jar` 只放在 `cellets/`，按标准 Cell XML 安装并交给 Kernel 启停；不注册到 AIGC 插件表。默认配置只启用 CPU scripted 通信；存储、独立模型单元与资源循环需显式启用并发布 profile，见 [EXECUTION.md](EXECUTION.md)。协议见 [CONTRACT.md](CONTRACT.md)，整体导航见工作区 [Harness 入口](../../harness/README.md)。

Harness 默认读取服务进程工作目录下的 `config/harness.properties`，与其他 service 配置的目录约定一致，无需修改 JVM 启动脚本。将唯一的 `config/harness.properties.template` 复制为该文件，在同一文件中配置基础服务、任务执行、worker/backend、文件、审批与诊断。配置缺失或凭据无效时 Harness 启动失败。原 `-Dharness.config=<私有文件路径>` 保留为显式覆盖方式，设置后只读取指定文件。

可选的独立 JDBC RunStore 支持 SQLite/MySQL，服务启动只校验，不自动迁移，默认关闭存储，详见 [STORE.md](STORE.md)。

资源包、固定 profile、工作区、上下文预算及可恢复快照由 [RUNTIME.md](RUNTIME.md) 说明。固定 schema 随正式 jar 分发；当前独立库为 schema7，旧库需显式 upgrade。Baize/Baize2 adapter、模型 journal、共享物理后端计容、多单元调度和资源循环见 [EXECUTION.md](EXECUTION.md)。离线与本机跨进程测试不能替代真实模型在线认证。

## 构建与目录

必须使用实际 JDK 8、Ant。`src/cube/service/harness/` 是运行代码，`test/` 单独编译且不进入正式 jar，`contracts/` 是跨语言契约，`config/`、`scripts/` 是部署入口。所有本模块输出默认在 `service-harness/build/`，clean 仅清本模块输出。

```powershell
# 从 service-harness/ 执行。common.jar 可以指向经验证的隔离构建产物。
ant "-Dcommon.jar=C:/isolated/build/cube-common-3.0.jar" test
# 先用同一 JDK 8 构建 common、dispatcher，再验证网关：
ant "-Dcommon.jar=C:/isolated/build/cube-common-3.0.jar" test-transport
ant "-Dcommon.jar=C:/isolated/build/cube-common-3.0.jar" "-Dstage.dir=C:/isolated/harness-stage" stage
```

stage 按明确清单生成 cellets、一份统一配置模板、两份 XML、五个脚本及默认 cell.xml：脚本仅含 run.sh、run.ps1、init_dev.py、evaluate_traces.py、replay_trace.py。测试、SLO fixtures、契约生成器和 CI 工具留在源码工程。补入同版本 `bin/cell.jar`、`libs/cube-common-3.0.jar` 及其经验证的依赖后即可启动。可用根目录的显式联合发布目标一次准备 staging；不要把独立 stage 指向正在运行的目录。`stage` 只是复制目标，不停止任何服务。

从 cube-server 根目录显式选择：

```powershell
ant build-service-with-harness-debug
ant build-service-with-harness-release
# 需要完整构建所有旧服务时：ant build-with-harness
ant "-Dharness.stage.dir=C:/isolated/release-with-harness" deploy-with-harness
```

旧依赖与各 service 构建失败仍按原规则处理；联合目标不掩盖它们。`deploy-with-harness` 使用已构建的旧产物集合，正式联合包应先完成全量构建和产物清单核对。目标强制显式 staging，拒绝源 deploy 及其父/子目录和非空无标记目录。它不会修改原 `deploy/`。旧 build/deploy/install 默认入口不依赖 Harness；原 libs 通配复制排除 Harness，旧 clean 不清 Harness Cellet。

## 两种运行方式

**独立 Cell**：使用 staging 的 `cell.xml`，由 `HarnessCarpet` 创建专用 Kernel；不加载 Console、AIGCService 或旧业务模块，Harness 自身存储按私有配置启用。此方式没有 Cube Auth，业务请求返回 AUTH_UNAVAILABLE；面向现有用户的 REST 使用下述合部署方式。HTTP 默认 127.0.0.1:7088，Cell 127.0.0.1:7600；多容器必须分开目录、端口及停止文件。

```powershell
python scripts/init_dev.py --output C:/private/harness-dev
Copy-Item -LiteralPath C:/private/harness-dev/harness.local.properties -Destination C:/isolated/release-with-harness/config/harness.properties
# 把 staging 的 cell.xml 中 Cell 端口与私有配置保持一致。
./scripts/run.ps1 -Stage C:/isolated/release-with-harness -StopFile C:/private/harness-dev/cell.stop -Java C:/jdk8/bin/java.exe
```

Linux 将配置放入 `/absolute/stage/config/harness.properties` 后，执行 `sh scripts/run.sh /absolute/stage /absolute/private/cell.stop`，可设置 `HARNESS_JAVA`。需覆盖默认路径时，PowerShell 仍支持可选 `-Config` 参数，Linux 仍支持原来的三个路径参数 `sh scripts/run.sh /absolute/stage /absolute/private/harness.local.properties /absolute/private/cell.stop`。入口在前台运行，交给专用 supervisor/systemd 管理。创建此实例独有的停止文件或发送服务管理器的 SIGTERM 会停止此 Cell。再次启动前移除已消费的本实例停止文件。

Cell 这个版本的程序化入口固定读取工作目录的 `cell.xml`；`Cell.start(boolean,String)` 的第二参数是日志名。`HarnessCell` 校验配置选择，避免误加载默认业务配置。Cell 启动时自身存在约 3 秒 TCP safe-lock；HTTP health 不代表 worker 已注册。

**合部署**：同一个 jar 与其他服务放在同一 `cellets/`。将 `config/cohost-cellet.xml` 的元素合入经审阅的服务 XML；联合 staging 已生成 `config/service-with-harness.xml`，保留原 `ServiceCarpet`。通过宿主原有 Cell 启动命令显式选择此 XML，并将主配置放入宿主工作目录的 `config/harness.properties`，无需增加 JVM 参数。私有配置中的 sqlite 存储与资源仓库目录支持相对路径，按服务工作目录解析、缺失时自动创建；绝对路径同样支持，符号链接与越界仍按原规则拒绝。`HarnessCellet` 与其他 Cellet 一同加载到同一 JVM，安装时向现有 Kernel 注册 `HarnessService`，由 Kernel 管理模块启停。不要更换宿主监听器或跳过宿主许可证检查。Harness 启动配置/端口失败只影响自身 readiness，不关闭旧 Kernel。共享 JVM 的 CPU/堆/GC、许可证、数据库和全业务性能须在目标环境认证；共享 JVM 不提供进程级故障隔离或无重启升级。

## Python 部署与停机

配套工程 `apexnova-wisdom/harness` 提供 `python -m harness` 及 Linux/PowerShell 启停脚本。`init_dev.py` 为 model/runner 生成不同 workerId/角色配置和私有运行目录，两者都注册到 Java 的 Harness。worker credential 与 Java 配置相同；业务请求使用已有 Cube 用户 token，不写入 worker 文件。Python 可直接连接指定 IPv4 或主机名，不要求与 Java 同机。配置不再区分 development/production，无需设置 mode；旧配置中的该键被忽略。用户身份来自 Cube Auth，工具权限上限由服务端配置。远程多节点配置与验收见 [REMOTE-NODES.md](REMOTE-NODES.md)。

部署支持一个 service-harness JVM 实例接入多个 Python Harness 单元，包括多个同角色单元。每个 Python 进程使用唯一 workerId、独立运行目录和实例身份，host/port 指向同一 Java Cell；Java 私有配置逐个授权，例如：

```properties
worker.model-1.role=model
worker.model-2.role=model
worker.runner-1.role=runner
worker.runner-2.role=runner
```

这些配置键由 `HarnessConfig.authorizedWorker` 支持。当前注册表最多 64 个条目，提供独立注册、租约、drain 和注销；注册数不代表执行并发容量。调度按能力、健康和实际可用容量选择单元；共享物理后端不得重复计算容量，未决 attempt 必须先对账。多单元负载验证见 [RESILIENCE.md](RESILIENCE.md)。

dispatcher 的 Harness 接入代码位于 `dispatcher/src/cube/dispatcher/harness/`（包 `cube.dispatcher.harness`），在 dispatcher.properties 配置 harness.rest.enabled=true 后随 dispatcher 启动挂载，无需独立配置文件。沿用 `dispatcher.properties` 的 Cellet/Director 配置连接 Harness。业务客户端使用 dispatcher 地址，Bearer 原样交给 service 认证；dispatcher 无需配置 client/backend credential。service HTTP 运维地址及 Console 地址分别配置，详见 [TRANSPORT.md](TRANSPORT.md)。

开发配置目录应由运行账户独占；Linux 生成权限为 0700/0600，Windows 使用账户私有目录与其 ACL。不要提交本地配置、PID、日志或停止文件。启动脚本等到注册确认才成功；停止按停止接单、drain、注销、断开连接、退出执行。不使用按进程名 kill 的旧脚本。

## 验证

```powershell
python scripts/check_build_graph.py
python scripts/generate_contracts.py --python-contracts C:/python-repo/harness/contracts
ant "-Dcommon.jar=C:/isolated/build/cube-common-3.0.jar" test
python -B scripts/lifecycle_smoke.py --java C:/jdk8/bin/java.exe --common-jar C:/isolated/build/cube-common-3.0.jar --cell-jar ../deploy/bin/cell.jar --libs ../deploy/libs --python-root C:/python-repo --runtime C:/isolated/lifecycle-runtime --evidence C:/isolated/lifecycle-evidence
```

最后一个命令应使用已安装 `harness/requirements.txt` 的 Python。它实际启动两种 XML 容器、注册双角色，测试原始载荷、心跳、错误身份、重复实例、旧 nonce、断线重连、停止期间重连和实例脚本；同容器以测试专用 Sentinel Cellet 验证定向停止，不冒充完整生产业务。每次只停止自己创建的进程。

独立 CI 在 `.github/workflows/harness.yml`，不作为旧发布的新增必需检查。Java 契约自动运行；手动跨仓库 Cell 验证必须选择带匹配契约的 Python revision，Linux 实测结果由该流水线产出。添加工作流不表示已在远端执行。

## 推理与认证边界

Python Harness 经 Cell v3 发送 OpenAI 请求，默认调用同 JVM AIGCService 注册的 Unit。合部署使用现有 ServiceCarpet；独立 XML 没有 AIGCService，默认 Unit 后端未就绪。外部 HTTP 后端必须在 Java 显式配置。当前权威库为 schema7；model/task profile 与 `contracts/inference/` 配套发布；详见 [EXECUTION.md](EXECUTION.md)。

服务级接单/严格 drain、冻结 SLO 的 Cell 多单元负载、文件回收与历史 JAR 升级回退验证见 [RESILIENCE.md](RESILIENCE.md)。本机专项通过不等于生产认证；真实模型、原生 Linux/KVM 及生产合部署性能需要在目标环境另行验证。
