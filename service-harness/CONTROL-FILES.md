# N10～N12 控制、状态查询与文件

使用实际 JDK 8；这是独立 `service-harness` 的可选开发入口。数据库显式升级至 **schema6**，服务启动只校验。升级前停止新写入并备份专用库，升级后由支持 schema6 的节点恢复；旧 schema5 二进制不能直接读新库。`001`～`005.sql` 不变，新增 `006.sql`（等待/回执与文件/root 身份等八表，加 tool job 清理列）。

## 控制接口

新接口沿用 `/harness/v1`、独立 API Bearer 凭据和 `Idempotency-Key`。完整新增响应契约见 [control-files/openapi.json](contracts/control-files/openapi.json)；原冻结契约保留。

- `GET /runs/{runId}`：等待时附加 `interaction`（waitId、kind、expiresAt、argumentsHash、details）。
- `POST /runs/{runId}/resume`：`{expectedVersion,waitId,input:{instruction,attachmentIds:[]}}`。
- `POST /runs/{runId}/approvals/{waitId}/decision`：`{expectedVersion,decision:"approve"|"reject",argumentsHash,reason?}`。
- `POST /runs/{runId}/cancel`：`{expectedVersion,reason?}`。

服务先鉴权，再查同 key 回执，再检查版本；同 key 异参数返回 409。等待问题、检查点摘要、审批固定参数和原始截止时间都在数据库中保存，重启不续期。resume 增加一条 user 输入；已确认工具不重复执行。批准人必须使用独立 `control.approver.credential`，其 `control.approver.subject` 不同于执行主体；此凭据只能读取 run 和提交审批。审批参数绑定 SHA256，派发前再检查固定定义/参数/权限。这里是固定开发身份适配器，生产身份接入仍需后续认证；当前审批只用于可信文件写入或 fake effect 测试。

取消受理返回 202、进入 cancelling 并拦截新模型/工具派发。在途执行继续保存物理完成证据，超过 `control.cancel.grace.millis` 可先进入 cancelled/unknown；晚到结果只结算执行与用量，不重开终态、不重复副作用。未知执行保持容量隔离。

## 文件接口与配置

显式配置 `files.enabled=true`；事先创建独立空目录 `files.root`，由可信服务账户独占，不与包仓库、旧 FileStorage 或其他用户可写目录交叉。`files.root.id`、磁盘随机身份标记及数据库 root 绑定共同固定根归属；`files.download.key` 至少 32 字符，仅保存在私有 `harness.properties`。文件根在同机多 JVM 间必须指向同一个真实目录及相同 namespace/policy；不支持用多个互不共享的本地目录冒充同一 rootId。跨主机对象存储适配器未实现。

1. `POST /inputs`：`{fileName,mediaType,size,sha256}` 创建会话（201）。
2. `PUT /inputs/{inputId}/content`：`application/octet-stream`，完整流替换重传；需要 Idempotency-Key。流有大小、摘要和最长读取期限约束，不支持 multipart/Range。
3. `POST /inputs/{inputId}/complete`：`{sha256}`；校验成功后固定 readyAt/expiresAt。重复 complete 不续期。
4. 创建 run 或 resume 的 `attachmentIds` 只能绑定本主体 ready 且未到期的 Input。
5. 可信 `input.read_text` 读取已绑定 UTF-8 text/plain（最多 32 KiB），`artifact.write_text` 生成正式 UTF-8 产物。文件 ID 不能作为宿主路径使用，模型不执行动态代码。
6. `GET /runs/{runId}/artifacts`、`GET /inputs/{inputId}`、`GET /artifacts/{artifactId}` 查询元数据；`GET /artifacts/{artifactId}/content` 下载。
7. `GET /artifacts/{artifactId}/download-handle` 返回短期签名相对 URL；打开仍需 API 认证，句柄有效期不超过文件有效期。

支持 text/plain、text/csv、application/json（顶层 object）、application/octet-stream。文本严格 UTF-8、拒绝 NUL；JSON 拒绝重复字段。文件大小默认 10 MiB、根配额默认 1 GiB。每个文件身份使用独立随机 storageKey，**不共享或去重 blob**；同内容文件分别计额、分别到期，避免一个引用的删除影响另一个身份。上传意图与可信工具调用分账，失败仅补传/complete，不重做业务工具。

`harness.properties` 参数、单位与范围见 [设计](../../harness/FILE-LIFECYCLE-DESIGN.md) 和 [模板](config/harness.properties.template)。默认 Input 7 天、Artifact 30 天、未完成上传 24 小时；重启应用新配置，不追溯重算已保存 expiresAt。活跃多 JVM 的同根策略不同会拒绝启动，需先 drain 全部旧策略节点再改。

到期时间满足 `now >= expiresAt` 就禁止新绑定、消费、下载和句柄；有权主体返回 FILE_EXPIRED/410。`cleanup.enabled=false` 只暂停物理删除。消费 pin 保留到 call.confirmed；下载 pin 保留至流关闭，下载最长 `file.download.lease.seconds`。到期前开始的消费可按原 run/call pin 恢复；新增消费仍拒绝。unknown 执行、未确认结果及在途 pin 不因 TTL 被清除。

Java 在 OS 根锁内执行文件回收，DB claim/fence/CAS 与失败退避记录删除结果；提交回执丢失时根据墓碑/缺失字节恢复，不重做工具。扫描批量有界，指标见 capabilities 中的 files。Runner 按持久化 call.confirmed 事实推导并保存 cleanup_after，经过宽限后下发绑定 runtime/jobHash/resultHash 的清理指令；只有执行树已结束、结果已 ACK 且非 unknown 才删除自己的 calls/jobId 目录，保留 journal。指令和 ACK 可幂等重传。失败仅日志记录脱敏错误码并重试，不清理旧目录、包或仓库证据。

## Console 只读适配器

`broker.type=console` 启用 `cube_console.query_status`，显式配置独立 `broker.console.baseUrl`、私有 `broker.console.cookie=CubeConsoleToken=...` 和允许节点映射。每个 target 固定 kind（service/dispatcher/console-host），服务节点还固定 tag/path/reportName；模型只选择 target 和 running/version/jvm/host 字段。

服务状态使用 Console 现有 POST 表单 tag/path；JVM Report 已是 MiB，保留 reportTimestamp 与 fresh/stale/placeholder/missing。host 仅 Console 本机，CPU 分数转百分比、内存字节转 MiB。Broker 先投影，再把数据交给 Runner；不传 storage/cache、凭据和部署路径。连接不跟随重定向，单请求总期限和 QPS/熔断均有界。默认 QPS=2、timeout=1500ms、报告最大年龄=120000ms。

`N10N12Fixture` 在隔离目录生成新 release ZIP 与 `cube-server-status.readonly.v1`（及两个模型专属 profile），不覆盖旧样例；这是测试作者包，仍需按 RuntimeAdmin 的独立管理与认证流程发布。真实 Console 环境尚缺，当前本地协议夹具和真实模型冒烟均不等于 N10 每模型 50×3 认证。

## 验证与撤回

`ant -f service-harness/build.xml test-control-files test-tools test-execution test-inference`；MySQL 专项使用全新专用库及 `-Dcontrol.test.config=<私有 migrator 配置>`。Python 用 `python -B -m unittest discover -s harness/tests -v`；实际 Cell 流程见 `scripts/n10_n12_smoke.py --help`。

新增 Runner 契约和源摘要需要两工程同步部署，先 drain 旧实例，保留运行目录与 journal，不能混用旧摘要。撤回仅停止新交互接单/上传/状态 profile，保留兼容 schema6 的恢复节点至在途执行、文件保留和清理收敛。原生 Linux、多主机存储及生产身份未认证；不据 Windows 测试晋级发布。

N10 场景冻结在 `fixtures/n10-scenarios.json`（50 场景、每模型 3 轮）；`scripts/n10_certify.py --help` 在已审阅的专用真实 Console/SQLite 环境执行，逐次保留失败和硬断言，语言质量单列人工审阅。当前尚未执行该认证。
