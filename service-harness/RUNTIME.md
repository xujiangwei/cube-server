# 包、固定配置、资源与上下文

本模块使用实际 JDK 8，提供 Java 内部 API 与离线管理 CLI。可选创建/资源循环入口、Java InferenceGateway、Baize/Baize2 决策 adapter 与部署见 [EXECUTION.md](EXECUTION.md)；默认关闭 execution 时返回 `HARNESS_NOT_READY`，控制与文件 API 见 [CONTROL-FILES.md](CONTROL-FILES.md)。本文 scripted profile 仅作离线确定性验证，不代表真实模型 tokenizer/template/工具在线认证。

## 存储与独立目录

先按 [STORE.md](STORE.md) 配置专用数据库。正式 jar 包含不可变的 `001.sql`～`007.sql`，当前显式迁移目标为 schema7；资源运行时八表由 002 迁移引入。新空库用 `initialize-empty`，完整旧库显式用 `upgrade`；服务启动只校验，不执行 DDL。

```powershell
java -cp '<harness.jar>;<dependencies/*>' cube.service.harness.store.StoreMigrations upgrade C:/private/migrator.local.properties
java -cp '<harness.jar>;<dependencies/*>' cube.service.harness.store.StoreMigrations verify C:/private/runtime.local.properties
```

002 迁移增加 release、binding、profile、run_config、release_ref、run_skill、workspace、context 八表，沿用专用库身份和事务边界。升级保留旧 run/event/usage。SQLite 事务回滚；MySQL DDL 中断可能留下部分 schema，必须保留现场并做审核过的前向恢复，自动重试会拒绝不完整 schema。撤回时停止新写入者、保留兼容当前 schema7 的恢复程序和账本，不能直接换回只支持旧 schema 的 jar。

资源仓库和工作区使用两个事先建立的绝对目录，每个目录有独立 identity/总字节配额。拒绝无标记的非空目录、符号链接和越界真实路径。每个根采用 JVM 锁及 OS 文件锁；写入采用临时文件、force 和原子移动。失败遗留的 blob/临时文件仍计入配额；目前保守保留引用和内容，不提供自动 GC。目录必须由可信服务账户独占，不能与客户可直接写入的 OS 目录共享。

[文件生命周期与自动清理设计](../../harness/FILE-LIFECYCLE-DESIGN.md) 规定安全收尾后删除过程文件，用户输入与产物分别按 ready 起算 TTL，逻辑到期禁止新访问、物理回收尊重在途/恢复引用。TTL、扫描和重试等参数统一置于服务工作目录下的私有 `config/harness.properties`，需要时可通过 `-Dharness.config` 显式覆盖主配置路径；[模板](config/harness.properties.template) 列出可选参数，默认 files.enabled=false；实现、部署边界与回归入口见 [CONTROL-FILES.md](CONTROL-FILES.md)，unknown 工作区或 journal 不得手工清空。

## 包与管理 CLI

作者样例位于工作区 `skill/harness-packages/`，独立于旧 SKILL 教程。包包含 `manifest.json`、`SKILL.md`、`dependencies.lock.json` 及完整白名单。release digest 是规范化 manifest 的 SHA256，manifest 固定每个文件的 SHA256、长度、role 和媒体类型。只接受本地声明的 schema、锁定的 ready 依赖和已注册 runtime；不拉取远程依赖、不读取未声明文件。

ZIP 最多 65 项、每项 256 KiB、总解压 2 MiB、压缩包 8 MiB，超过 1 KiB 的项目压缩比不得超过 100。拒绝重复/大小写冲突路径、穿越、绝对路径、反斜线、DOS 设备名、Unix symlink/special attrs、目录项、加密及不支持的 ZIP 格式。默认使用 `resources-only`，拒绝 executable/entrypoint。可信标准库 executable 使用 64 KiB 包上限和 tool Runner，需显式启用；部署/授权/配额/撤回以 [TOOLS.md](TOOLS.md) 为准。模型生成的代码不进入该执行路径。

在私有 Store 配置中增加：

```properties
runtime.tenant=tenant-a
runtime.actor=release-admin
runtime.repository.root=C:/private/harness-packages
runtime.repository.identity=tenant-a-package-root
runtime.repository.maxBytes=104857600
runtime.allowUnsignedLocal=false
runtime.source=private-registry
# X.509 DER 公钥的 Base64，由管理员提供；不读取上传包内的信任声明。
runtime.signer.author-key=REPLACE_WITH_PUBLIC_KEY_BASE64
```

```powershell
java -cp '<harness.jar>;<dependencies/*>' cube.service.harness.runtime.RuntimeAdmin C:/private/admin.local.properties profile C:/author/runtime.json
java -cp '<harness.jar>;<dependencies/*>' cube.service.harness.runtime.RuntimeAdmin C:/private/admin.local.properties validate C:/author/package.zip
java -cp '<harness.jar>;<dependencies/*>' cube.service.harness.runtime.RuntimeAdmin C:/private/admin.local.properties upload C:/author/package.zip author-key C:/author/signature.txt
java -cp '<harness.jar>;<dependencies/*>' cube.service.harness.runtime.RuntimeAdmin C:/private/admin.local.properties bind skill example/resource-guide <digest> 0
java -cp '<harness.jar>;<dependencies/*>' cube.service.harness.runtime.RuntimeAdmin C:/private/admin.local.properties export <digest> C:/author/new-export.zip
```

签名为 `SHA256withRSA`，签署 **64 字节 ASCII release digest**，签名文件保存 Base64；公钥 ID、签名、算法、可信 actor/source 和依赖摘要进入 provenance。同 digest 再发布保留首次 provenance，不用后来的上传覆盖。仅本地作者开发可显式设置 `runtime.allowUnsignedLocal=true` 且 source=`local-author`，此时允许省略 upload 的签名两参数。校验通过并不等于受信签名发布。

`bind <skill|task> <name> <target> <expectedVersion>` 原子 CAS；首次版本为 0，成功返回递增版本。task target 为 `id@version`。`lifecycle <digest> <expectedVersion> <ready|draining|retired>` 管理生命周期；draining 阻止新 run，已有 run 可继续读取固定内容；run 引用、active skill 或其他未 retired 包的依赖会阻止 retirement。retired 不可恢复，内容不自动删除。回退使用仍 ready 的旧摘要和当前指针版本执行 bind。

## 固定 profile、资源与工作区 API

`ProfileRegistry.publish` 用五个封闭 schema 校验 Model/Runtime/TaskProfile、包和依赖锁。同 kind/id/version 异内容拒绝。`createRun(Identity, key, taskId, request)` 在创建事务内解析 active task、固定 model/runtime/release/权限及引用，提交后返回；幂等重试即使 active 已切换也返回原 run。默认 files 关闭时拒绝非空 attachmentIds；显式开启文件服务后在创建事务中验证并绑定本主体 ready、未到期 Input。

Identity 由可信身份源构造，不能从客户端请求字段映射。有效权限是 task、可信身份与已固定 release 声明/核心权限的交集，执行工具还须匹配部署端 provider 策略；相同技能名的多个 release 不能同时固定。客户工具名限定在包命名空间。资源能力只有 `skill.list/activate`、`resource.list/read/search`；激活产生版本化事件，不增加权限。`ResourceTools.invoke` 对每次调用复查权限、参数 schema、激活和固定 release；自定义资源工具只读 manifest 固定路径，并验证输出 schema，不运行代码。

`read` offset/limit 按 Unicode codepoint 分页，最大 8192，返回 release/path/sha256/handle/nextOffset；只能读 skill/resource role。`search` 是有界字面量匹配。每次操作重新校验 tenant、subject、当前可信身份权限及包权限，不能通过 activation 读取未授权内容。

`Workspaces.acquire(Lease,duration)` 绑定数据库时钟、owner/fence 和 run 租约上限，目录 ID 由 tenant/subject/run 摘要派生；每次读写重验当前租约，写入执行 runtime 的字节/文件数及独立根配额。相对路径经真实路径解析；禁止工作区外访问。只有 executionSettlement=confirmed 才能 release；未知执行不删除工作区，过期接管不能让旧 owner 继续写入。

## 上下文、预算和恢复

`ContextEngine.assemble(identity,run,handles)` 只接受固定资源句柄 `{release,path,offset,limit}`。任务来自 `run.created`，模型决策和工具结果来自同事务读取的账本，按 intent 事件顺序重建。调用 proposalId、tool 和完整 argumentsJson 必须配对；重复、孤立或缺失 intent 证据拒绝。未决 round/proposal/call 保存在 snapshot，`modelInput` 在未决状态拒绝发送。round.result 使用基础 model-decision，call.payload 使用原 proposal，call.result 使用基础 tool-result；模型 adapter 遵守此边界。

system 消息只由固定平台规则、固定权限及账本事实摘要生成；技能资料为 user 数据、工具输出为 tool 数据，不提升为平台指令。来源角色不是模型注入防御的全部保证，实际工具权限仍由资源操作和后续 Coordinator 准入强制校验。

固定策略为 `context-v1` / `full-json-bytes-v1` / `extractive-v1`，toolSetVersion=`resource-tools-v1`。model 必须为 `offline-scripted`，tokenizer/template 的 id/version/digest 必须与 `ContextEngine.tokenizer()/template()` 精确匹配。模板为 `harness-json-template-v1\n` 加整个规范化 JSON request；离线 tokenizer 每 UTF-8 字节计 1 token，加 profile.templateOverhead。预算包含所有 messages、tools 的完整输入/输出 schema、responseSchema、模板和 outputReserve，另检查完整输入字节上限。这不能作为 Qwen 或任何真实后端的容量/模板认证。

先按 maxSourceChars 对资源页与工具 dataJson 做可审计提取，再在预算超限时确定性缩短这两类文本；原任务、权限、调用参数、schema、执行事实和 pending 不缩短。每条压缩记录包含方法、策略、来源句柄/账本定位、原文摘要、保留长度和摘要 SHA256；原资源可校验后重读，完整工具结果仍在账本和 snapshot.facts 内。不可压缩部分超限时带策略/预算版本明确拒绝，不能截断后执行。

snapshot 固定 configDigest、task、permissions、全部执行事实/摘要、pending、压缩记录、模型 request、精确 rendered、tokenizer/template 和预算。`checkpoint` 验证当前 owner/CAS、basisVersion 和配置绑定，与事件/Outbox 原子提交；并发激活或执行推进会使旧 basis 失效。同 digest 重试返回原回执。`restore` 重验授权、摘要和模板，重建相同模型输入字节；历史 snapshot 是恢复证据，是否允许再次发起模型执行由 Coordinator 的当前状态/fence 准入决定。

## 验证

```powershell
ant -f service-harness/build.xml test-runtime
# 专用全新空测试库：迁移账户只做升级，全部 runtime 场景使用 DML 账户。
ant -f service-harness/build.xml '-Druntime.test.config=C:/private/runtime.local.properties' '-Druntime.test.migrator=C:/private/migrator.local.properties' '-Druntime.test.output=C:/evidence/runtime-results.json' test-runtime
```

外部测试配置必须声明 `store.test.disposable=true`；`test-runtime` 要求空库并建立真实 v1 后验证升级，不能对生产或已有业务测试库执行。源码 fixture 覆盖两个节点的内容分发、签名、ZIP/path/schema 攻击、CAS/pin、资源授权、工作区接管/配额/真实路径逃逸、注入、配对、超长 schema、tokenizer/template 不匹配、重复压缩、未决恢复和精确输入复现。Windows 使用实际 junction 越界测试及 ZIP symlink attrs；普通 OS symlink 创建权限不足有记录。原生 Linux 与真实模型在线认证需单独执行，独立 CI 文件存在不表示远端已运行。

业务 SKILL 使用统一 `package-manifest.json`（schemaVersion=6），普通工具声明 runnerType="tool"，与独立 QueryProvider 扩展绑定。平台不枚举 Console 等业务权限；operation/permission/schema 由可信部署配置与技能声明共同约束。旧清单格式不再接受，先 drain 再重新发布当前格式的 release/profile。扩展与技能发布说明见 [TOOLS.md](TOOLS.md) 和工作区 [server-status](../../skill/harness-packages/server-status/README.md)。
