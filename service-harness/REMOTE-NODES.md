# Python Harness 跨机器、多节点部署

Python Harness 是注册到 Java Harness 的独立服务节点。一个 Java JVM 可以接入多个 model/runner，同一角色也可有多个节点。节点主动建立 Cell TCP 长连接并注册、续租、拉取任务和提交结果；Java 按角色、能力、健康与实际可用容量调度。

下面使用 Linux `192.168.1.35` 上的 service + dispatcher，以及 Windows 上的两个 model 和一个 CPU runner。无需 SSH 转发，也无需设置 development/production `mode`；旧配置中的 mode 被忽略。IPv4 或主机名直接写入 Python host，端口单独配置，配置校验不执行 DNS 查询。当前 Cell TCP 连接使用 IPv4。

| 入口 | 配置 | 本例地址 |
| --- | --- | --- |
| Python → Java Cell | service 实际加载的 Cell XML、Python host/port | `192.168.1.35:6000` |
| 客户端 → Harness REST | dispatcher.properties | `http://192.168.1.35:7010/harness/v1` |
| 健康/运维 HTTP | 私有 harness.properties | `http://127.0.0.1:7088`，仅在 Linux 本机访问 |

## Java 服务器

使用 JDK 8。假设发布目录 `/opt/cube`，私有配置 `/opt/cube-private/harness.properties`。在 service 实际加载的 `config/service.xml` 中保留原 ServiceCarpet，确认 Talk server 的 host 为 `0.0.0.0` 或服务器可访问网卡 IP，port 为 `6000`，并在 cellets 中装载一次：

```xml
<cellet port="6000" jar="cellets/cube-service-harness-3.0.jar">
  <class>cube.service.harness.HarnessCellet</class>
</cellet>
```

service JVM 增加 `-Dharness.config=/opt/cube-private/harness.properties`，部署配套 common/dispatcher/Harness JAR。独立 Cell 则修改其实际 `cell.xml` 的监听地址和端口；模板默认 `127.0.0.1:7600`，不是强制监听地址。确保 Windows 到所选 Cell 端口可达。

在现有 dispatcher.properties 中设置 `harness.rest.enabled=true`，并在 `cellets`、`director.1.cellets` 的原列表末尾追加 `Harness`。Java Cell 绑定 `0.0.0.0` 时，同机 dispatcher 可指向 `director.1.address=127.0.0.1`、`director.1.port=6000`；若 Cell 仅绑定 `192.168.1.35`，Director 地址也使用该 IP。REST 监听 `http.host=0.0.0.0`、`http.port=7010`；外部地址使用 `external.http.host=192.168.1.35`。dispatcher 不配置 worker/API credential。

按 [STORE.md](STORE.md) 初始化独立数据库到当前 schema7，并按 [EXECUTION.md](EXECUTION.md) 发布 model/runtime/task profile 和 active task 绑定。`execution.enabled=true`、`store.enabled=true` 才启用任务执行。以下是补入同一私有 Harness 配置的节点相关设置，所有占位密钥须替换为不同的实际私有值：

```properties
http.host=127.0.0.1
http.port=7088
worker.credential=<至少24字符的worker密钥>
api.credential=<不同的至少24字符的API密钥>
lease.millis=6000
execution.accepting=true
inference.node.id=harness-35-inference

worker.baize2-1.role=model
worker.baize2-1.model=Baize2
worker.baize2-1.backend=baize2-shared
worker.baize2-2.role=model
worker.baize2-2.model=Baize2
worker.baize2-2.backend=baize2-shared
backend.baize2-shared.type=unit
backend.baize2-shared.token=<有效的Cube模型访问令牌>
backend.baize2-shared.capacity=1
backend.baize2-shared.timeoutSeconds=180

execution.tools.enabled=true
worker.runner-a.role=runner
worker.runner-a.capacity=1
runner.grant.key=<至少32字符的独立密钥>
broker.node.id=harness-35-broker
```

两个模型 worker 共用同一个 backendId，物理 capacity 仍为 1。默认 type=unit 要求同 JVM 的 AIGCService 和对应 Baize2 Unit 已就绪；外部 OpenAI 后端只在 Java 显式配置，见 EXECUTION.md。CPU runner 的可信工具包、runtime/task profile 与业务 provider 需独立安装、发布，见 [TOOLS.md](TOOLS.md)。仅注册节点不会自动安装技能。

API、审批、trace 和接单管理继续使用各自配置的独立凭据；身份/权限由服务端私有配置确定，不从 Python 或 REST 载荷自报取得。

## Windows Python 节点

安装 Python 3.11 venv 与 `harness/requirements.txt`。将 Python 工程的 `harness/config/model-remote.template.json` 复制为 `C:/private/harness/baize2-1.json`，填入相同的 Java worker credential：

```json
{
  "host": "192.168.1.35",
  "port": 6000,
  "workerId": "baize2-1",
  "role": "model",
  "workerCredential": "<Java配置中的worker.credential>",
  "runtimeDir": "./runtime-baize2-1",
  "requestTimeout": 3,
  "startupTimeout": 30,
  "model": {"model": "Baize2", "nativeTools": true, "maxWireBytes": 131072}
}
```

复制为 `baize2-2.json`，只将 workerId 改成 `baize2-2`、runtimeDir 改成 `./runtime-baize2-2`。复制 `runner-remote.template.json` 为 `runner-a.json`，填入同一个 worker credential；模板已设置 `192.168.1.35:6000`、独立 workerId/runtimeDir 和 capacity=1。

所有节点均可分布在不同机器；每个节点有自己的本地运行目录、实例身份、journal 和租约。同一 Java 服务内 workerId 必须唯一；不得复制正在使用的运行目录作为新节点。新增节点要在 Java 私有配置中逐个授权。

初始化脚本也支持 `--cell-host`，例如在 cube-server 根目录运行：

```powershell
python -B service-harness/scripts/init_dev.py --output C:/private/new-harness-config --cell-host 192.168.1.35 --cell-port 6000
```

该脚本创建新 worker/API credential 和一组基础 scripted model/runner 配置，用于首次初始化；不会修改 Java XML。为已有 Java 新增节点时使用其现有 worker credential，不要用重新生成的密钥覆盖它。上述真实模型配置还需保留 model/runner 对象。生成的绝对 runtimeDir 归生成机器所有，跨机器复制后须修改；Java 的数据库、资源、文件目录应填写 Linux 本地路径。

在 Python 工程根目录，用非管理员 PowerShell 分别启动：

```powershell
./harness/scripts/start.ps1 -Config C:/private/harness/baize2-1.json -Python C:/private/harness-venv/Scripts/python.exe
./harness/scripts/start.ps1 -Config C:/private/harness/baize2-2.json -Python C:/private/harness-venv/Scripts/python.exe
./harness/scripts/start.ps1 -Config C:/private/harness/runner-a.json -Python C:/private/harness-venv/Scripts/python.exe
```

## 实机验收

1. Linux 启动 service、dispatcher；本机请求 `http://127.0.0.1:7088/healthz`。Windows 用 `Test-NetConnection 192.168.1.35 -Port 6000` 确认连接目标可达。
2. 启动上述三个 Python 节点，分别使用 status 脚本确认 connected、registered、ready。用普通 Harness API Bearer 请求 dispatcher 的 `/harness/v1/capabilities`，应看到 baize2-1、baize2-2、runner-a 和三个独立 instanceId。注册成功不代替模型后端就绪检查。
3. 请求 `/harness/v1/task-profiles`，确认存在已发布 active task。使用普通 API Bearer、Content-Type 和唯一 Idempotency-Key 向 `/harness/v1/runs` 提交 `{"taskProfileId":"resources-Baize2","input":{"instruction":"请简短介绍自己","attachmentIds":[]}}`。查询 run/events 直到终态；并发请求核对共享 backend 的 occupied 不超过配置容量。
4. 使用 `stop.ps1 -Config C:/private/harness/baize2-1.json` 只停止该节点。capabilities 中应保留 baize2-2、runner-a，原有两个进程与实例保持在线；新任务可由剩余健康模型节点处理。
5. 保留原 runtimeDir 重启 baize2-1。它应重新注册并获得新会话，其他节点不受影响；已有 unknown 执行仍按原账本对账，不通过换目录清空容量。
6. 在独立失败测试配置中验证错误凭据、未授权 workerId、角色不匹配、重复 workerId 均启动失败，且不影响现有节点。只停止本次启动的测试节点。

实机结果与下述本机自动化结果分别记录。此指南不代表已连接或部署 `192.168.1.35`。

## 自动化验证

```powershell
python -B -m unittest discover -s service-harness/scripts/tests -v
# 在 Python 工程根执行：
python -B -m unittest discover -s harness/tests -v
# 使用 JDK 8：
ant -f service-harness/build.xml test-inference test-execution test-tools test-store test-transport
```

`g0_smoke.py` 默认仍启动隔离的 loopback Java Cell。增加 `--cell-bind-host 0.0.0.0 --cell-host <测试机自己的LAN-IPv4>` 可验证经非 loopback 地址的真实 Cell 连接（不是连接现有服务器）：

```powershell
python -B service-harness/scripts/g0_smoke.py --java C:/jdk8/bin/java.exe --common-jar build/cube-common-3.0.jar --cell-jar deploy/bin/cell.jar --libs deploy/libs --python-root C:/Users/hermi/workspace/apexnova-wisdom --runtime C:/private/new-smoke --evidence C:/private/new-evidence --cell-bind-host 0.0.0.0 --cell-host <测试机自己的LAN-IPv4>
```

脚本覆盖三个独立 Python 进程、两个同角色节点、单节点停止/租约到期、凭据与角色拒绝、重连及旧会话拒绝。健康 HTTP 保持 loopback；所有测试实例和临时配置归 runtime 目录所有，不修改现有部署。

## 2026-10-19 本机验证记录

环境为 Windows、JDK 1.8.0_504、Ant 1.10.15。证据根目录为 `C:/Users/hermi/.cache/cube-harness/remote-nodes-20261019/`；私有配置和凭据留在独立 runtime 目录，不提交仓库。

| 检查 | 实际结果 |
| --- | --- |
| Python Harness 全量测试 | 47 项通过，含远程 IPv4/主机名/旧配置、非法地址/端口、配置加载不访问网络、每次连接保留目标地址、Java Cell 编解码固定向量 |
| init_dev 配置生成测试 | 3 项通过；远程 Cell 与 loopback 健康地址独立，不生成 mode，凭据隔离且不覆盖已有目录 |
| JDK 8 回归与构建 | `test-inference test-execution test-tools test-store test-transport build` 通过；含配置不依赖 mode、共享物理容量、租约和真实传输 |
| 默认 loopback 完整 Cell 冒烟 | 21 组通过；`loopback-evidence-02/smoke-results.json` |
| 非 loopback 完整 Cell 冒烟 | 21 组通过；Java 仅绑定本机 `192.168.1.34`，Python/dispatcher fixture 连接同一地址；`lan-evidence-02/smoke-results.json` |
| 三个 v3 模型进程与同 JVM Unit fixture | 6 个 run 通过，3 个 Python 节点对应 2 个物理后端容量；`inference-01/report.json` |
| 模型与两个 CPU Runner 实际执行回归 | 5 组通过；含任务分配、单节点 drain、崩溃重启 unknown 隔离与容量保留；`runner-evidence-01/results.json` |
| Windows → Linux `192.168.1.35:6000` | 未执行；未连接、部署或重启该服务器。按上文实机验收步骤在部署后另行记录 |

两轮完整 Cell 冒烟均验证了两个 model 和一个 runner 的独立进程/实例、注册、续租、单节点启停、重连新会话及旧会话拒绝，并覆盖 PowerShell 与 Git Bash 包装脚本。固定密钥 `0c000000` 的真实 Java 握手覆盖旧 Python Cell 在负数整除 8 时的取余差异；修正限于 Harness 适配层，Cell 协议及 model/runner 冻结源码摘要未改动。以上执行测试使用 fixture 后端，未执行真实模型质量认证或原生 Linux 测试。
