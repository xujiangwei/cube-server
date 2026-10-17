# Harness Cellet / N00–N03

正式服务是 `cube.service.harness.HarnessCellet` + `HarnessService`，NAME=`Harness`。产物 `cube-service-harness-3.0.jar` 只放在 `cellets/`，按标准 Cell XML 安装并交给 Kernel 启停；不注册到 AIGC 插件表。当前只有 CPU scripted 通信能力，业务任务返回 `HARNESS_NOT_READY`。协议见 [CONTRACT.md](CONTRACT.md)，完整验收记录见工作区 `harness/execution-records/g0-20261017/`。

N03 增加可选的独立 JDBC RunStore，详见 [STORE.md](STORE.md)。SQLite 与隔离 MySQL 的并发、租约、事务、Outbox 和用量结算已有本机专项验收；服务启动只校验，不自动迁移。默认关闭存储；业务 API 的 profile/Coordinator 准入仍待后续阶段。N02 原生 Linux 缺项继续保留。

## 构建与目录

必须使用实际 JDK 8、Ant。`src/cube/service/harness/` 是运行代码，`test/` 单独编译且不进入正式 jar，`contracts/` 是跨语言契约，`config/`、`scripts/` 是部署入口。所有本模块输出默认在 `service-harness/build/`，clean 仅清本模块输出。

```powershell
# 从 service-harness/ 执行。common.jar 可以指向经验证的隔离构建产物。
ant "-Dcommon.jar=C:/isolated/build/cube-common-3.0.jar" test
ant "-Dcommon.jar=C:/isolated/build/cube-common-3.0.jar" "-Dstage.dir=C:/isolated/harness-stage" stage
```

stage 生成 cellets、配置、脚本和默认 cell.xml。补入同版本 `bin/cell.jar`、`libs/cube-common-3.0.jar` 及其经验证的依赖后即可启动。可用根目录的显式联合发布目标一次准备 staging；不要把独立 stage 指向正在运行的目录。`stage` 只是复制目标，不停止任何服务。

从 cube-server 根目录显式选择：

```powershell
ant build-service-with-harness-debug
ant build-service-with-harness-release
# 需要完整构建所有旧服务时：ant build-with-harness
ant "-Dharness.stage.dir=C:/isolated/release-with-harness" deploy-with-harness
```

旧依赖与各 service 构建失败仍按原规则处理；联合目标不掩盖它们。`deploy-with-harness` 使用已构建的旧产物集合，正式联合包应先完成全量构建和产物清单核对；G0 的目录分类演练不等于完整业务发布认证。目标强制显式 staging，拒绝源 deploy 及其父/子目录和非空无标记目录。它不会修改原 `deploy/`。旧 build/deploy/install 默认入口不依赖 Harness；原 libs 通配复制排除 Harness，旧 clean 不清 Harness Cellet。

## 两种运行方式

**独立 Cell（G0 默认）**：使用 staging 的 `cell.xml`，由 `HarnessCarpet` 创建专用 Kernel；不加载数据库、Console、AIGCService 或业务模块。HTTP 默认 127.0.0.1:7088，Cell 127.0.0.1:7600；多容器必须分开目录、端口及停止文件。

```powershell
python scripts/init_dev.py --output C:/private/harness-g0
# 把 staging 的 cell.xml 中 Cell 端口与私有配置保持一致。
./scripts/run.ps1 -Stage C:/isolated/release-with-harness -Config C:/private/harness-g0/harness.local.properties -StopFile C:/private/harness-g0/cell.stop -Java C:/jdk8/bin/java.exe
```

Linux 对应 `sh scripts/run.sh /absolute/stage /absolute/private/harness.local.properties /absolute/private/cell.stop`，可设置 `HARNESS_JAVA`。入口在前台运行，交给专用 supervisor/systemd 管理。创建此实例独有的停止文件或发送服务管理器的 SIGTERM 会停止此 Cell。再次启动前移除已消费的本实例停止文件。

Cell 这个版本的程序化入口固定读取工作目录的 `cell.xml`；`Cell.start(boolean,String)` 的第二参数是日志名。`HarnessCell` 校验配置选择，避免误加载默认业务配置。Cell 启动时自身存在约 3 秒 TCP safe-lock；HTTP health 不代表 worker 已注册。

**合部署**：同一个 jar 与其他服务放在同一 `cellets/`。将 `config/cohost-cellet.xml` 的元素合入经审阅的服务 XML；联合 staging 已生成 `config/service-with-harness.xml`，保留原 `ServiceCarpet`。通过宿主原有 Cell 启动命令显式选择此 XML，并加 `-Dharness.config=<私有配置>`。不要更换宿主监听器或跳过宿主许可证检查。Harness 启动配置/端口失败只影响自身 readiness，不关闭旧 Kernel。共享 JVM 的 CPU/堆/GC、许可证、数据库和全业务性能验收在 N16；共享 JVM 不提供进程级故障隔离或无重启升级。

## Python 部署与停机

配套工程 `apexnova-wisdom/harness` 提供 `python -m harness` 及 Linux/PowerShell 启停脚本。`init_dev.py` 为 model/runner 生成不同 workerId/角色配置和私有运行目录，两者都注册到 Java 的 Harness。worker credential 与 Java 配置相同；API credential 独立，不写入 worker 文件。G0 只允许 loopback development；生产身份源未接入时拒绝启用 production 模式。

开发配置目录应由运行账户独占；Linux 生成权限为 0700/0600，Windows 使用账户私有目录与其 ACL。不要提交本地配置、PID、日志或停止文件。启动脚本等到注册确认才成功；停止按停止接单、drain、注销、断开连接、退出执行。不使用按进程名 kill 的旧脚本。

## 验证

```powershell
python scripts/check_build_graph.py
python scripts/generate_contracts.py --python-contracts C:/python-repo/harness/contracts
ant "-Dcommon.jar=C:/isolated/build/cube-common-3.0.jar" test
python -B scripts/g0_smoke.py --java C:/jdk8/bin/java.exe --common-jar C:/isolated/build/cube-common-3.0.jar --cell-jar ../deploy/bin/cell.jar --libs ../deploy/libs --python-root C:/python-repo --runtime C:/isolated/g0-runtime --evidence C:/isolated/g0-evidence
```

最后一个命令应使用已安装 `harness/requirements.txt` 的 Python。它实际启动两种 XML 容器、注册双角色，测试原始载荷、心跳、错误身份、重复实例、旧 nonce、断线重连、停止期间重连和实例脚本；同容器以测试专用 Sentinel Cellet 验证定向停止，不冒充完整生产业务。每次只停止自己创建的进程。

独立 CI 在 `.github/workflows/harness.yml`，不作为旧发布的新增必需检查。Java 契约自动运行；手动跨仓库 Cell 验证必须选择带匹配契约的 Python revision，Linux 实测结果由该流水线产出。添加工作流不表示已在远端执行。
