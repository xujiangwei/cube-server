# Console AI 用量与 Contact Token 账单

AI 用量是 Console Web 的功能，登录后从导航「AI 用量」进入 `/ai-usage`。service 和 service-harness 负责计量、自动建表及持久化；Console 只读查询，无须单独启动监视程序。

## 结算口径

- 主体是 service 验证后的 AuthToken 的 `domain + Contact ID`。多个 Token 合并，不同域隔离；REST 请求里的 user、Contact、频道所有者、后端账号、AI 单元 Contact 均不能更改归属。
- 显式 `UsageContext` 贯穿任务队列、知识问答、资源提取、内部模型转发。无原始访问者的进程内系统任务以 `system / 0` 存储，Console Contact 账单排除这些记录。
- Harness 使用 run 的 tenant、subject_id。经 unit 后端调用 AIGC 时传递 delegated 上下文，AIGC 返回用量证据，只有 Harness 账本计量。
- 每次物理派发生成稳定 meter ID。回调重放与相同修订幂等；实际重试使用新 ID。排队取消和明确未派发的请求不计模型调用。派发后无法确定是否执行的记录保留待核对，不自动重发。
- `actual` 为提供方实际 Token，`estimated` 为估算，`unknown` 为缺失，`pending` 为尚无终态证据。缺失值保留 NULL，不补零；图片文件大小不进入 Token 账单。已收到部分实际用量、但执行状态未知的调用仍标为待核对。
- 账单只汇总实际输入、输出 Token，不计算金额、不扣款、不封账。估算与未知单列。失败、取消或断连已有可信消费证据时仍记账。
- 数据库时间为 UTC epoch 毫秒，显示与自然月账期使用 Asia/Shanghai。归属以持久派发开始时间为准，迟到结果及修订更新原小时、原账期。流式累计快照取最新值，不累加快照。
- 旧 `aigc_usage` 保留，不能回填历史账单。旧业务用量查询只允许查询当前 Token 自身 Contact；管理员从 Console 查询。

## 数据源配置

复制 `console/config/ai-usage-sources.json.example` 为 Console 工作目录的 `config/ai-usage-sources.json`，删除不用的示例项，填写实际的库地址。可在 Console properties 中使用 `ai.usage.sources=/absolute/path/sources.json` 指定文件。配置在 Console 启动时加载。

每项必须有唯一 name。kind 为 `aigc` 或 `harness`；type 为 `mysql` 或 `sqlite`。SQLite 必须是 Console 本机可访问、已存在的绝对文件路径，以只读模式打开，不会创建空库。不要复制运行中的 SQLite 单文件作持续数据源；应读取共享可访问的本地文件，或使用支持一致备份的快照。

### 配置文件格式与字段说明

文件使用 UTF-8 编码，顶层是 JSON 数组，每个对象代表一个逻辑计量数据库。使用标准 JSON，不支持注释和尾随逗号。示例里的库名、主机和文件路径均需按实际部署填写。

| 字段 | 类型 | 适用范围 / 默认值 | 说明 |
| --- | --- | --- | --- |
| `name` | 字符串 | 所有数据源，建议必填 | 数据源的唯一名称，例如 `aigc-main`。用于页面展示和查询参数 `source` 的精确匹配；不是域名、模型名或 service 地址。省略时按数组下标生成 `source-0` 等名称，部署时应显式设置唯一名称。 |
| `kind` | 字符串 | 所有数据源，必填 | 只能填写小写 `aigc` 或 `harness`。分别读取 `aigc_ai_usage_*` 或 `harness_ai_usage_*` 四张计量表；应与建表模块一致。 |
| `type` | 字符串 | 所有数据源，必填 | 只能填写小写 `mysql` 或 `sqlite`，决定连接方式。 |
| `host` | 字符串 | MySQL，必填 | 数据库服务器的主机名或 IPv4 地址，例如 `db.example.com`、`192.168.1.20`。不要包含 `http://`、`jdbc:mysql://`、端口或路径；当前实现不接受 IPv6 地址。 |
| `port` | 整数 | MySQL，默认 `3306` | 数据库端口，范围 `1–65535`。 |
| `database` | 字符串 | MySQL，必填 | service 或 service-harness 实际使用的数据库名，不是单张表名。当前允许字母、数字和下划线。示例 `cube_aigc` 不是固定值。 |
| `user` | 字符串 | MySQL，必填 | Console 查询数据库所使用的账号，建议使用只读账号。 |
| `passwordEnv` | 字符串 | MySQL，可选，推荐 | 保存数据库密码的环境变量名称，例如 `CUBE_AI_USAGE_MYSQL_PASSWORD`；这里填写变量名，不填写密码。环境变量必须对启动 Console 的进程可见。 |
| `password` | 字符串 | MySQL，可选，默认空字符串 | 直接指定数据库密码。配置了 `passwordEnv` 时优先读取环境变量，不回退到此字段，因此通常只配置两者之一。 |
| `file` | 字符串 | SQLite，必填 | Console 所在机器可访问的、已存在的 SQLite 数据库绝对路径。Windows 推荐写 `C:/cube/service-harness/storage/harness.db`；使用反斜杠时必须按 JSON 写成 `C:\\cube\\service-harness\\storage\\harness.db`。 |

MySQL 数据源不需要 `file`；SQLite 数据源不需要 `host`、`port`、`database`、`user` 或密码字段。此文件不配置 Contact 或域权限；权限由 Console 登录用户在后端校验，结算归属来自数据库中的域与 Contact。

例如，示例中的 `aigc-main` 表示「通过 MySQL 读取 AIGC 计量表」；`harness-local` 表示「通过本机可访问的 SQLite 文件读取 Harness 计量表」。如果实际只使用 AIGC MySQL，删除第二个对象即可。若 Harness 也使用 MySQL，则为它增加一个 `kind: "harness"`、`type: "mysql"` 的对象，填写 Harness 的实际库地址。

Console 默认从**进程工作目录**读取 `config/ai-usage-sources.json`。需要指定其他位置时，在 Console 实际加载的 properties 文件中加入。Console 按 `console_dev.properties`、`console.properties`、`config/console.properties`、`deploy/config/console.properties` 的顺序使用第一个存在的文件：

```properties
ai.usage.sources=C:/cube/console/config/ai-usage-sources.json
```

环境变量密码示例（PowerShell；在同一个终端中随后启动 Console）：

```powershell
$env:CUBE_AI_USAGE_MYSQL_PASSWORD = '<只读账号密码>'
```

使用系统服务启动 Console 时，应在对应服务的运行环境配置该变量。修改数据源列表后需要重启 Console；无需为此重启 dispatcher。

MySQL 支持远程主机，填写 host、port、database、user，推荐用 passwordEnv 引用 Console 进程环境变量。也支持 password 字段，但配置文件须由部署环境妥善保存。不要把实际凭据提交入库。Console 账号只需要相关四张计量表的 SELECT 权限，不需要 CREATE、ALTER 或写权限。service 账号需要初始化表、索引和更新计量数据的权限。

多个部署共享一个数据库时，可以重复配置，但按库内 `store_id` 去重。AIGC 和 Harness 即使位于同一个物理 MySQL schema，仍是不同的逻辑计量源，两者都需要配置。不要将同一库的多个备份当成独立数据源。

查询会返回各源 storeId、开始采集时间、最近记账时间、状态，以及 complete 标志。缺失、未升级、配置错误或查询失败会标为不完整；范围早于开始采集时间也标为不完整。全部不可用时 summary 为 null，区别于已就绪但没有流量的 0。updatedAt 是最近一次记账时间，不表示 service 心跳。存在未知或待核对调用时，页面另行提示实际账单尚待核对。

## 表与自动升级

service 在现有 AIGC 库自动创建 `aigc_ai_usage_store / call / revision / hourly`；Harness 在自己的专用库创建相应 `harness_ai_usage_*` 表。schemaVersion 为 1。

- store：随机数据库身份、版本、采集起点、最后更新时间。
- call：归属、模块、模型、执行单元、关联 request/run、开始结束时间、状态、质量、Token、修订。
- revision：稳定证据摘要及修订审计。
- hourly：UTC 整小时，按域、Contact、模块、模型、执行单元聚合。

明细、修订、小时增量在同一数据库事务中更新。MySQL 通过单例行锁串行化计量写入；SQLite 使用 BEGIN IMMEDIATE 和 FULL 同步。明细与汇总不自动清理。

Harness 已将这些表纳入严格结构校验，启动时仅允许已知旧版本 schema digest 迁移。未知 digest、错误列类型等继续拒绝启动。升级保留原 run 等数据，不生成历史调用。AIGC 的新表独立于旧累计表，不改旧累计表结构。

## 故障与补记

派发前持久化 pending 调用。计量存储不可用时停止新的模型派发；传统协议返回 `UsageUnavailable(34)`，`/v1` 返回 503。Harness 保留持久队列并暂停 claim。

正在执行的模型继续收尾。终态证据先写 fsync 日志，再尝试数据库事务；失败保留日志，每 10 秒幂等重放。重启同样先重放，成功后恢复新派发。

- AIGC：service 工作目录 `storage/ai-usage-outbox/<store_id>/`。
- Harness：service-harness 工作目录 `storage/ai-usage-outbox/<配置 store.identity>/`。
- Python OmniVL：单元工作目录 `storage/ai-usage-outbox/omni-vl/`，仅保存计量标识、时间和 Token，不保存对话正文。

这些目录必须位于持久磁盘，随数据库一起备份。迁移服务工作目录时一同迁移补记目录。不要删除未重放 JSON；冲突或损坏应人工核对 meter ID、revision 及原始提供方证据，禁止直接给未知记录补零。数据库已记录 pending 但终态证据永久丢失时会长期显示待核对。

### OmniVL 视频流

需要同时更新配套 Python 工程 `cube/aigc_capability.py` 和 `cube/unit_omni_vl.py`。新单元声明 usageProtocol=1；新 service 拒绝向未声明协议的 OmniVL 单元启动 RTSP 推理，返回 34，提示升级。

每段推理先以 `segmentBegin` 同步取得 service 持久 meter ID，再执行模型。`segmentUsage` 在用户回调前确认计量收据，取消时已产生的消费也照常保存。终态收据在 Python 落盘，重启或数据库恢复后重放；service 按库中记录及已注册物理单元身份验证收据，客户端不能指定结算 Contact。启动、状态、提示更新、停止控制本身不计模型调用。

## Console 接口

`GET /statistic/ai-usage/{summary,trend,breakdown,calls,bill,export}`，统一 Console Cookie 登录态。超级管理员 role=1 可跨域；其他管理员由后端限定在 user.group 对应域，跨域返回 403。

公共过滤参数：`from`、`to`（UTC epoch 毫秒，起点包含、终点不包含）、domain、contact、model、module、unit、source。单次最多 366 天。group 为 model/module/contact/unit；grain 为 hour/day。calls 使用 offset（默认0）和 limit（默认50，最大500）。

bill 必须传 domain、contact、month=YYYY-MM，按上海自然月覆盖 from/to。export 使用同样的权限、账期和筛选，导出全部匹配调用（最多十万条，超过则缩小区间）。CSV 含 UTF-8 BOM、生成时间、时区、筛选、完整性、数据源状态与调用证据，文本做公式注入防护。

整小时查询使用 hourly，首尾不足小时读取 call，避免重复计数。多个源在各自事务中读取后合并，因此不同库之间不是全局同一时点快照；开放账单会随迟到结果、修订变化。

Contact 名称从 Console 当前联系人库读取，删除时回退为 ID。名称仅作展示，账本始终按域与 ID 归属。

## 页面与升级交付

默认本月，近7天包含今天；支持自定义、小时/天趋势、Contact/模型/模块/单元排行、调用明细、Contact 月度账单和 CSV。筛选变化会清除旧结果并要求查询；异步响应有代次检查。AI 单元概览提供当前域摘要，服务监视器提供授权域已配置计量库摘要及筛选跳转；后者明确为库汇总，不表示选中服务实例的独占消费。

升级时使用 JDK 8：先备份数据库与补记目录，更新 common、service、service-harness，配套更新 OmniVL Python 文件，再更新 dispatcher、console 和整个 console/web 目录。启动 service 等待自动建表/严格校验通过，配置 Console 只读数据源后重启 Console。不要只替换 index.html；新哈希静态资源需成套更新。首月采集前的时间显示不完整是预期行为。

本次交付未执行线上部署。验证及其环境限制见交付包 VALIDATION.md。
