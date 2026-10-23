# Harness 技能包、配置与上下文

运行时把租户的技能、权限、业务配额与请求选择的服务器模型、执行器分别解析，然后固定到每个 run。请求省略模型或执行器时使用服务器默认值，模型、执行器和工具白名单不属于租户配置。资源包、配置及任务账本共用专用数据库；模型执行见 [EXECUTION.md](EXECUTION.md)，租户默认 JSON 的全部字段见[配置文档](config/harness-tenant/README.md)。

## 仓库与工作区

先按 [STORE.md](STORE.md) 启用存储。资源仓库和工作区使用独立根目录，分别绑定 identity 与总字节配额，路径按服务工作目录解析。首次初始化要求空目录或匹配的身份标记；拒绝符号链接、越界真实路径及无标记的非空目录。

每个根使用 JVM 锁和 OS 文件锁，写入采用临时文件、force 和原子移动。失败遗留内容计入配额。包仓库当前保守保留内容及引用，不自动 GC；输入、产物和 Runner 过程文件另按 [CONTROL-FILES.md](CONTROL-FILES.md) 清理。unknown 工作区、journal 和未决证据不能手工清空。

## 技能包与发布

作者示例位于工作区 `skill/harness-packages/`。包包含 `manifest.json`、`SKILL.md`、`dependencies.lock.json` 及完整文件白名单；manifest 使用 `schemaVersion=6`，固定文件 SHA256、长度、role 和媒体类型。release digest 为规范化 manifest 的 SHA256。只接受声明的本地 schema、锁定的 ready 依赖；可执行工具在创建任务时核对服务器的协议能力，不下载远程依赖或读取未声明文件。

资源包默认 `resources-only`，不执行脚本。ZIP 最多 65 项、每项 256 KiB、总解压 2 MiB、压缩包 8 MiB，超过 1 KiB 的项压缩比不超过 100。拒绝路径穿越、绝对路径、反斜线、重复/大小写冲突、设备名、symlink/special attrs、目录项、加密及不支持的格式。可信可执行工具另受 64 KiB 包上限与显式授权约束，见 [TOOLS.md](TOOLS.md)。

仅 `SKILL.md` 正文作为技能说明加载，旁边的脚本或引用文件不会自动读取、执行。资源须由工具显式读取；模型输出代码也不自动运行。

## 离线管理 CLI

管理命令使用 JDK 8。先启动服务完成建表，管理配置填入同一数据库的 `store.*`，再增加以下配置：

```properties
runtime.tenant=tenant-a
runtime.actor=release-admin
runtime.repository.root=C:/private/harness-packages
runtime.repository.identity=tenant-a-package-root
runtime.repository.maxBytes=104857600
runtime.allowUnsignedLocal=false
runtime.source=private-registry
runtime.signer.author-key=REPLACE_WITH_PUBLIC_KEY_BASE64
```

`runtime.tenant` 必须对应业务用户 token.domain；仓库路径、identity 应与服务运行时使用的仓库一致。signer 值为 X.509 DER 公钥的 Base64，来源由管理员提供。

```powershell
# Windows classpath 分隔符为 ;，Linux 改用 :。profile 命令只发布 kind=policy 的租户业务策略。模型/运行时直接配置在服务器 harness-execution 目录。
java -cp '<harness.jar>;<dependencies/*>' cube.service.harness.runtime.RuntimeAdmin C:/private/admin.properties profile C:/author/tenant-policy.json
java -cp '<harness.jar>;<dependencies/*>' cube.service.harness.runtime.RuntimeAdmin C:/private/admin.properties validate C:/author/package.zip
java -cp '<harness.jar>;<dependencies/*>' cube.service.harness.runtime.RuntimeAdmin C:/private/admin.properties upload C:/author/package.zip author-key C:/author/signature.txt
java -cp '<harness.jar>;<dependencies/*>' cube.service.harness.runtime.RuntimeAdmin C:/private/admin.properties bind skill example/resource-guide <digest> 0
java -cp '<harness.jar>;<dependencies/*>' cube.service.harness.runtime.RuntimeAdmin C:/private/admin.properties bind policy default default@2 1
```

签名算法为 `SHA256withRSA`，签署 64 字节 ASCII release digest；签名文件保存 Base64。首次 provenance 保存签名、公钥 ID、可信 actor/source 和依赖摘要，同 digest 再发布不覆盖。仅本地作者开发可设置 `runtime.allowUnsignedLocal=true` 且 `runtime.source=local-author`，upload 才可省略签名参数。

`bind <skill|policy> <name> <target> <expectedVersion>` 原子 CAS：首次 expectedVersion=0，已有绑定填写当前版本。`export <digest> <新ZIP路径>` 导出固定内容。`lifecycle <digest> <expectedVersion> <ready|draining|retired>` 管理生命周期；draining 阻止新 run，已有 run 可继续读取固定内容。run 引用、active skill 或未 retired 依赖会阻止 retirement；retired 不可恢复，内容不自动删除。回退可 bind 到仍 ready 的旧版本。

## 固定配置与权限

租户 policy 由封闭 schema 校验，同 kind/id/version 的异内容拒绝。新 run 在事务内解析 policy/default，固定获准技能、权限和业务限额；模型、运行时、工具集、上下文和执行参数从服务器 ExecutionProfiles 读取。服务器切换模型/执行器不修改任何租户记录。幂等重试即使服务器配置或租户策略已切换也返回原 run。

run 的权限是租户策略声明、可信身份授权和平台/固定 release 声明的交集；业务工具还须匹配部署端 provider 的权限、schema 和范围。`skill.activate` 仅激活已固定技能，不扩张权限；同名技能的多个 release 不可同时固定。包内工具名须处于技能命名空间。

资源能力为 `skill.list/activate`、`resource.list/read/search`。每次调用复查租户、用户、权限、激活状态和固定 release；自定义资源工具只读 manifest 固定路径，验证输出 schema，不运行代码。read 按 Unicode codepoint 分页，limit 最多 8192，返回 release/path/sha256/handle/nextOffset；search 为有界字面量检索。

工作区租约绑定数据库时钟、owner/fence 和 run 租约，每次读写重验，受 RuntimeProfile 的字节/文件数与根总配额限制。只有 executionSettlement=confirmed 才可 release；unknown 保留工作区，旧 owner 不能在接管后继续写入。

## 上下文数据流与预算

`ContextEngine` 从 run 的固定配置、原始任务、已授权资源句柄和账本读取上下文，按执行意图顺序重建消息。proposalId、tool、argumentsJson 与结果必须配对；未决 round/proposal/call 记录到 snapshot，存在未决执行时拒绝发送新的模型输入。

system 消息来自固定平台规则、权限和账本事实；技能资料为 user 数据，工具输出为 tool 数据。实际工具权限由 Java 在调用前再次校验。

上下文策略使用 `context-v1` / `extractive-v1`；java-inference 的预算版本为 `transport-bytes-v1`，scripted 为 `full-json-bytes-v1`。模型按 tokenizer/template 的 ID、版本及 adapter/nativeTools 匹配，不要求填写配置摘要。渲染为 `harness-json-template-v1` 前缀加完整规范化请求；预算包含 messages、工具完整 schema、响应 schema、模板开销和 outputReserve，并另检查 maxInputBytes。字节计数不等于远端模型真实 token 用量。

先按 maxSourceChars 提取资源与工具文本，超预算时继续确定性缩短这些文本，记录来源、原文摘要、保留长度及摘要。原任务、权限、调用参数、schema、执行事实和 pending 不裁剪；不可压缩部分仍超预算则拒绝执行。

snapshot 保存 configDigest、固定任务/权限、执行事实、pending、压缩记录、模型 request、精确 rendered、模板及预算。checkpoint 在同一事务中校验 owner、CAS、basisVersion 和配置绑定，并提交事件/Outbox。restore 重验授权、摘要及模板，重建相同输入字节；是否允许再次执行仍由 Coordinator 根据当前状态和 fence 判断。
