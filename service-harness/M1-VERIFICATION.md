# M1 本地验证记录

验证日期：2026-11-03。结论：通用实现、资源包发布及单图协议闭环通过本地验证；M1 的真实 F、真实三人照片和 UNIT 现场退出验收尚未完成。

## 构建环境

Java 实际使用 `service-harness-skill/build/toolchain/jdk8u504-b01/bin/java.exe`，版本为 `1.8.0_504-b01`，不是仅指定 source/target=8。构建工具为本地 Ant，Java 编译器 classpath 包含该 JDK 的 tools.jar。

实际成功构建 common、service、dispatcher 的 `build-release`，以及 service-harness 和 service-harness-skill。旧工程存在的 deprecation/unchecked 提示仍在，不影响构建。缺失的 ZXing javase 3.5.0 构建依赖仅放在忽略的 toolchain 目录，校验其 Maven SHA-1，文件 SHA-256 为 `34b0692194a9e4ad40bb8a29079974d18130300c42ef1730e73f0b03d5f3c5f6`。

复现时从 `cube-server/` 执行，所有路径可替换为本机实际 JDK 8 / Ant：

```powershell
$m1Java = './service-harness-skill/build/toolchain/jdk8u504-b01/bin/java.exe'
$m1Ant = 'service-harness-skill/build/toolchain/ant.jar;service-harness-skill/build/toolchain/ant-launcher.jar;service-harness-skill/build/toolchain/jdk8u504-b01/lib/tools.jar'
$m1LegacyAnt = $m1Ant + ';service-harness-skill/build/toolchain/javase-3.5.0.jar;deploy/bin/cell.jar;deploy/libs/*'
& $m1Java -version
& $m1Java -cp $m1LegacyAnt org.apache.tools.ant.Main '-Dlib.dir=C:/Users/hermi/workspace/cube/cube-server/deploy/libs' -f common/build.xml build-release
& $m1Java -cp $m1LegacyAnt org.apache.tools.ant.Main '-Dlib.dir=C:/Users/hermi/workspace/cube/cube-server/deploy/libs' -f service/build.xml build-release
& $m1Java -cp $m1LegacyAnt org.apache.tools.ant.Main '-Dlib.dir=C:/Users/hermi/workspace/cube/cube-server/deploy/libs' -f dispatcher/build.xml build-release
& $m1Java -cp $m1Ant org.apache.tools.ant.Main -f service-harness/build.xml test-transport test-execution test-tools test-control-files
& $m1Java -cp $m1Ant org.apache.tools.ant.Main -f service-harness-skill/build.xml test
```

依赖构建和消费该 JAR 的检查须依次运行，避免编译期间另一个 Ant 进程重写依赖 JAR。

## 实际通过的检查

| 检查 | 证据与范围 |
| --- | --- |
| Harness 核心回归 | 50 个协议夹具、全部摘要、身份认证、单元租约、执行选择、JDBC 恢复、工具、取消和文件生命周期；上述四个 target 整体 BUILD SUCCESSFUL |
| 数据库升级 | 将旧版库保留 run 数据并升级四个通用元数据表，再严格重开；数据源版本、旧 run 和原有用量数据保留。修复了早期 Provider 自行建表导致 StoreSchema 重启拒绝的问题；DDL 统一归现有 StoreBootstrap |
| REST/Cell 管理传输 | 真实 Jetty/Cell 测试覆盖 ZIP 原文、签名头、幂等键、数据源 ID/版本透传；普通用户不能通过新增管理动作发布、绑定或读取数据源 |
| 通用工具 | 八个实际 ZIP 均经生产 Java 校验器验证，Provider 与发布包 schema 相同；89 项 Provider/package/HTTP/TLS 断言通过；原六工具的 Broker/JDBC/ACK 回归通过 |
| M1 集成 | 真实签名业务 ZIP、SQLite、Broker、绑定 Input 图片与本地 HMAC HTTP F 服务，走查询→可信 mediaRef→能力调用→来源文本产物；视觉桥接是明确标记的替身 |
| 引用与权限 | 拒绝伪造 mediaRef；模型写的同名 media-source.json 没有 Provider origin；确认后的来源元数据通过 artifact-view 契约；撤销源授权后不访问上游 |
| 分页 | 后续页保持原查询和 snapshotId；第一页不声称 complete；重复游标返回 DATA_QUERY_INCOMPLETE |
| 恢复与容量 | 同调用重放不再次推理或生成产物；超时请求取消但保留 unknown/槽位；两节点分配；重开存储保留未结执行；迟到回执只释放对应槽位，不补写已结束调用的来源 |
| 发布与 CAS | 未批准 signer、错误签名被拒绝；同键发布重放；CAS 重放得到原回执，变更请求和旧 expectedVersion 冲突 |
| Python 契约 | 在配套工程执行 `python -B -m unittest harness.tests.test_contracts`，4 项通过；只增量同步管理 schema、HTTP artifact-view 与 profiles 就绪字段，保留该工程原有本地改动 |
| REST 文档 | `node reader/build.mjs` 生成并同步 110 个接口；`node --test reader/reader.test.mjs` 的 5 项通过；`node reader/browser-test.mjs` 通过导航、搜索、懒加载、打印、移动端及本地文件浏览。首次脚本 178307 字节，低于既有 200 KiB 限制 |

最近一次 `test-media` 摘要：

```text
PASS M1: signed package, bound image -> Broker -> Unit fixture -> source artifact;
forged refs, timeout, restart, late receipts, two Units, revoked grant, CAS replay.
No live inference executed.
BUILD SUCCESSFUL
```

本地测试日志保存在忽略的 `service-harness/build/m1-regression.log`、`service-harness-skill/build/m1-tools.log` 和 `service-harness-skill/build/m1-media.log`；重跑会覆盖。F 构包脚本输出 `forest/build/fhe-patrol-v1.zip`，依赖摘要来自实际工具 ZIP，不代表这些依赖已在现场发布或授权。

## 现场验收仍需完成

- 没有连接真实 F。HMAC 测试验证的是本地协议夹具，不代替 F 的用户映射、nonce 防重放、记录权限和生产 TLS。
- 没有执行真实视觉推理，也没有由真实主模型完成整轮问答。warmup.jpg 是建筑树木，fire_0001.jpg 是绘画负样本；真实三人、烟火、道路障碍标注样本仍缺。
- 本轮视觉桥接需 service/AIGC、Auth、Harness 同 Kernel/JVM 和可用的 FileStorage；分 JVM 受控桥接尚未实现。未装配时拒绝准入，不返回假在线状态。
- 只允许明确隔离且已预热的 UNIT。支持多个节点及同节点能力共用一槽，但没有完成旧视觉入口、主模型和视觉任务共享 GPU 的统一计容验证。
- 整个 service 进程丢失且没有物理结束证据时，unknown 和容量预留保留；没有以重启推定结束的自动释放。真实断连、节点重启及文件回收需要现场故障测试。
- 本轮数据库集成验证使用 SQLite；MySQL 已补配套 DDL、排序规则和旧摘要升级入口，未执行真实 MySQL 冒烟。
- 视频、完整分析覆盖门禁及可配置报告约束按后续里程碑实施；M1 的 final 仍为既有文本协议。

启用步骤见 [M1.md](M1.md)，F 最小接口和原图交付约定见 [ForestHanressEngineeringAPIRef.md](../../forest/ForestHanressEngineeringAPIRef.md)。
