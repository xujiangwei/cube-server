# Harness 通用技能工具包

本工程使用实际 **JDK 8** 和 Ant 构建 `cube-service-harness-skill-3.0.jar`，由 Java `service-harness` 在同一 JVM 内加载。六个 provider 可独立注册；Python tool Runner 执行技能的薄入口，通过 SDK/Broker 调用 Java，由 provider 查询数据并返回结构化证据。

## 工具与配套技能

| 技能包 | 工具 / operation | 权限 | 输入及结果 |
| --- | --- | --- | --- |
| `common/search` | `common.search.query` / `common.search.query.v1` | `web.search` | query，可选 language、maxResults、timeRange；返回 results、searchedAt、partial、warnings |
| `common/web` | `common.web.read` / `common.web.read.v1` | `web.read` | url；返回静态正文、来源、最终 URL、获取时间及原始响应字节摘要 |
| `common/knowledge` | `common.knowledge.search` / `common.knowledge.search.v1` | `knowledge.read` | query；返回当前租户和用户获准访问的引用片段 |
| `common/attachment` | `common.attachment.read` / `common.attachment.read.v1` | `attachment.read` | inputId；返回绑定的文本附件内容、完整输入摘要和 originalBytes |
| `common/time` | `common.time.query` / `common.time.query.v1` | `time.read` | action=now/convert，可选 zone；convert 必须提供带偏移的 ISO 8601 instant |
| `common/calculator` | `common.calculator.evaluate` / `common.calculator.evaluate.v1` | `calculator.evaluate` | expression；返回 DECIMAL128 精度的十进制字符串 |

六套作者包唯一源码在本工程 `harness-tools/`，每项都有 `SKILL.md`、`manifest.template.json`、输入输出 schema、`main.py`、空依赖锁和执行/租户策略模板。schema 构建时复制到 JAR；provider 与发布包使用同一份定义。包格式为 schemaVersion=6，runnerType=tool，effect=read-only。

原正文 SKILL 加载器不会执行这些入口。需要新 Harness 的技能发布、授权、激活及显式工具调用链路。上传技能 ZIP 不会安装 Java JAR；安装 JAR 也不会自动发布技能或授予权限。

## 构建和打包

在本工程目录，用实际 JDK 8 运行 Ant（仅设置 source/target=1.8 不能代替 JDK 8）：

```powershell
ant -f ../service-harness/build.xml build
ant build packages
ant test
```

依赖 `../build/cube-common-3.0.jar`、`../deploy/bin/cell.jar` 和已有 `../deploy/libs/*.jar`。不引入额外运行时 JAR，不重复打包核心类；HTML 静态提取使用 JDK 8 ParserDelegator。`packages` 使用 Python 3.11，可通过 `-Dpython.exe=C:/path/to/python.exe` 指定解释器。

输出：

```text
build/cube-service-harness-skill-3.0.jar
build/packages/{search,web,knowledge,attachment,time,calculator}-v1.zip
```

`packages` 可重复执行，原子更新自己生成的 ZIP，同一来源得到相同 manifest 摘要。生产 Java ZIP 校验器在 `ant test` 中校验六个实际 ZIP。

`build-debug` / `build-release` 同时将统一 JAR 复制到 `../build/`。服务器顶层增加可选入口：

```powershell
ant -f ../build.xml build-harness-skill-release
```

默认服务构建和部署不自动安装本扩展。`clean` 只删除本模块的编译/打包产物，保留本地工具链和验证证据目录。

## IntelliJ IDEA Community Edition

用 IDEA CE 打开服务器工程目录 `C:/Users/hermi/workspace/cube/cube-server`，模块列表已登记 `service-harness-skill/service-harness-skill.iml`。已有窗口可重载工程；手动导入时，使用 File → New → Module from Existing Sources 选择该 `.iml`，并确保同一工程已加载 `common` 和 `service-harness` 模块。

Project SDK 选择实际 JDK 8；本模块继承 Project SDK，语言级别固定为 Java 8。`src/` 和 `test/` 分别为源码与测试目录，`build/` 排除索引。`harness-tools/` 配置为资源目录，IDE 输出路径映射到 `cube/service/harness/skill/schemas/`，供 provider 加载 schema。

模块依赖现有 `common`、`service-harness`、`deploy/bin/cell.jar` 与 `deploy/libs/*.jar`。集成测试还引用 `service-harness/build/test-classes`；首次开发或修改核心测试夹具后，用 JDK 8 执行 `ant -f ../service-harness/build.xml test` 生成它。IDE 运行 `ProviderTest`、`CommonToolsTest` 的 main 时，将工作目录设为本模块目录，程序参数填 `build/packages`，并先执行 `ant packages`。

发布 JAR、复制 schema 和生成六个 ZIP 仍以本模块 Ant 目标为准；IDE 的普通编译用于开发和调试。

## 安装和授权

将产物复制到**指定的 Harness stage**：

```powershell
ant stage -Dstage.dir=C:/releases/harness-with-tools
```

此目标复制 JAR 到 `libs/`，将配置模板及技能 ZIP 放到 `harness-skill/`，不修改实际私有配置、不启动服务、不自动上传或授权。

复制 `config/*.properties.template` 到仓库外的私有目录，填好所需工具的配置。再将 `config/providers.properties.template` 中需要的登记项合并到 Harness 私有配置。示例仅启用搜索和时间：

```properties
broker.providers=search,time
broker.provider.search.class=cube.service.harness.skill.SearchProvider
broker.provider.search.config=C:/private/harness/search.properties
broker.provider.time.class=cube.service.harness.skill.TimeProvider
broker.provider.time.config=C:/private/harness/time.properties
```

`broker.providers` 若已有其他登记项，应合并保留。provider 私有配置路径必须为绝对路径；即使计算器不需要上游地址，也要提供配置文件。注册在启动时生效，更新前 drain 相关任务并保留在途任务使用的配置。

基础执行配置沿用 [Harness TOOLS.md](../service-harness/TOOLS.md)：启用工具执行、grant、普通 Runner 及可信可执行包发布，使用 `mode=trusted-python-v1`、`toolSetVersion=broker-tools` 的 runtime。可采用本工程 `config/runtime-cpu.json`。一个 JVM 可以连接多个同角色 Runner，各有独立 workerId、实例身份、runtimeDir、journal 和租约。

按照 [Harness RUNTIME.md](../service-harness/RUNTIME.md) 用 RuntimeAdmin 校验、上传并发布需要的技能 ZIP，固定 ready release。将各包 `tenant-policy.template.json` 的 release 占位值替换为实际摘要，将权限及 release 合并到对应租户 policy 并发布绑定。`execution.template.json` 中的 `offline-scripted` 仅用于离线示例，线上改为已配置的实际模型；不要覆盖现有执行配置。

请求可显式激活所需技能：

```json
{
  "input": {"instruction": "全网搜索主流的3D建模软件，比较用途并引用来源", "attachmentIds": []},
  "runtime": "trusted-cpu",
  "skills": ["common/search", "common/web"]
}
```

POST 发送到 dispatcher 的 `/harness/v1/runs`，携带有效 Cube 用户 token 和 Idempotency-Key。请求不支持指定 tools，服务端按技能、权限和运行时计算有效工具。业务 REST 地址与 service 的健康/运维 HTTP 地址分别配置。

## 各能力配置和边界

### SearXNG

部署方自行提供 SearXNG。本工程不安装或启动搜索引擎。在实例 `settings.yml` 中启用 JSON：

```yaml
search:
  formats:
    - html
    - json
```

私有 `search.properties`：

```properties
search.url=http://127.0.0.1:8080/search
search.token=
network.timeout.millis=5000
result.max.bytes=12288
```

`search.url` 是完整搜索端点，可指定部署方的内网实例，不能含查询参数、fragment 或 URL 用户信息。可选 token 用于受控反向代理的 Bearer 认证；不接受模型提供地址、请求头或凭据。不跟随该端点的重定向。

首版默认 language=zh-CN，maxResults=10，固定第一页；timeRange 支持空值、day、month、year。上游时间过滤取决于具体引擎。摘要去除 HTML 标记；发布时间仅在上游提供字符串时保留，不推断。partial/warnings 表示引擎部分失败或无效条目被过滤，空 results 是正常结果。HTTP 403 返回 SEARCH_FORBIDDEN，应检查 JSON 开关、实例访问策略和认证；429 返回 SEARCH_RATE_LIMIT。

### 公开网页

只获取公开 IPv4 HTTPS（443），拒绝内网、保留及转换地址，首版拒绝 IPv6。验证全部 DNS 地址，选择已验证地址连接，不再次按域名解析；TLS 使用原主机名校验及 SNI。最多 3 次 HTTPS 重定向，每次重新验证，整个过程共用一个 deadline。

可用 `web.hosts=example.com,www.example.com` 进一步限定精确域名；为空表示允许符合上述规则的公网域名。无 Cookie、代理、登录态和 JavaScript，不加载页面子资源。HTML 提取是静态文本，不保证与浏览器渲染正文一致。接受 text/html、text/plain、application/json，默认解压后响应上限 1 MiB；原始响应摘要与投影正文分别保存。

### 本地知识与附件

知识索引采用 `config/knowledge-index.example.json` 的格式，最多 128 文档、256 KiB，单文档文本最多 8192 字符、subjects 最多 32 项。配置绝对 `knowledge.index` 和实际 `knowledge.index.sha256`；索引变化必须更新摘要并重启 provider。先按可信 tenant/subject 过滤，再进行大小写不敏感子串匹配，最多返回 5 条、每条 2048 字符。它不提供向量或语义检索。

附件必须先通过 Harness Input API 上传、完成校验，并列入创建 run 的 attachmentIds。只支持 UTF-8 text/plain、text/csv、application/json，最多 16 KiB，拒绝 NUL、ZIP 和 PDF 特征，不解压、不执行文件。输出预算导致截断时，sha256 仍对应完整输入，truncated 为 true。

### 时间和计算

时间默认 `time.default.zone=Asia/Shanghai`，支持 IANA 时区及夏令时转换。now 不接受 instant；convert 必须提供带偏移的 ISO 8601 instant，拒绝无时区时间。时间来自服务器时钟，结果重放保留原调用时刻，不重新取时。

计算只支持十进制、`+ - * / % ( )` 和一元正负号，`%` 是取余。表达式最多 512 字符、嵌套 32 层、数字 128 位，采用 DECIMAL128 精度。无函数、变量、幂、科学计数法、脚本引擎或动态代码。

所有工具结果默认限制为 12 KiB JSON，并检查 SDK 嵌套 dataJson 后的标准输出低于 32 KiB。字符串按实际 UTF-8 字节与 JSON 转义预算裁剪，不拆开代理对；截断明确标记。网络总超时默认 5 秒、最高 8 秒，默认不重试上游。取消、断线和结果未知遵循原 Broker 的账本及对账规则，不伪造未观察到的成功结果。

## 验证

本次实际执行结果及夹具/实机边界见 [VERIFICATION.md](VERIFICATION.md)。

`ant test` 覆盖六个实际作者 ZIP、输入输出 schema 一致性、算术精度、时区和夏令时、知识 ACL、附件绑定、中文与转义输出预算。网络测试使用真实本地 HTTP/TLS 服务，覆盖 SearXNG 空结果/部分失败/403/429/超时/无效响应，以及公网 DNS 拒绝、TLS 主机名、chunked/gzip 和响应大小。Broker 测试使用真实 SQLite、grant、结果持久化、重复调用和 ACK 重送。

多单元冒烟（先运行 `ant test`，使用 Python Harness 已安装依赖的 Python 3.11）：

```powershell
python -B scripts/cell_smoke.py --java C:/jdk8/bin/java.exe --python-root C:/Users/hermi/workspace/apexnova-wisdom --runtime C:/private/common-tools-smoke --evidence C:/private/common-tools-evidence
```

runtime/evidence 必须是新目录。脚本启动隔离真实 dispatcher、一个 service-harness JVM、一个 scripted model worker 和两个独立 tool Runner，验证六项工具、中文附件、分担负载和单元停启。停止一个 Runner 后，仅将隔离 journal 中一条已完成记录的 acked 重置为 0，模拟 ACK 丢失；重启经真实 SHM 重送原报告并重新确认，断言账本结果不变且搜索不重复执行。搜索为本地 HTTP 协议夹具，网页 transport 为测试 classpath 专用替身，均不算真实公网认证；正式 JAR 不包含夹具类。

真实 SearXNG 与模型验收需要先部署真实端点、发布技能，并准备 Cube 用户 token 和 Trace 白名单用户的 Cube token（配置 `trace.enabled=true`、`trace.reader.subjects=域/联系人ID`）。将两者放入 CUBE_TOKEN / CUBE_TRACE_TOKEN 环境变量，再运行：

```powershell
python -B scripts/search_smoke.py --api-url http://127.0.0.1:7010/harness/v1 --sqlite-store C:/private/harness/harness_execution.db --evidence C:/private/common-tools-online
```

Trace 会脱敏内容，不能从 Trace 还原搜索 URL。因此脚本以只读方式读取部署方显式提供的 SQLite 账本，将工具结果原文摘要与 Trace 承诺核对，再检查答案引用的 URL。MySQL 或远程账本可由运维导出本任务 `harness_tool_call` 的 `run_id,intent_id,attempt,result_json` 行为 JSON 数组，保持 result_json 原始字符串，通过 `--run-id <已有任务ID> --call-results <导出文件>` 代替 `--sqlite-store` 验证；不新增内容导出接口。

可用 `--model` / `--runtime` 选择已配置模型和执行器。该入口创建真实任务并保存结果、账本证据和 trace，要求实际模型、真实搜索证据和答案引用至少一个实际检索 URL；scripted 模型不能通过。任务等待输入、审批或失败均不算通过。未提供真实环境时，不将本地夹具记录为线上验收。
