# Harness 推理网关验证记录（2026-10-18）

实现范围为 Java/Python 两仓的源码、契约、迁移、模板与文档；未修改运行中的模型部署，未自动发布 active profile。默认链路经 Cell 调用同 JVM AIGCService，外部 OpenAI HTTP 后端需显式配置。新 model/task 版本均为 2，ModelProfile schemaVersion=3，Cell protocolVersion=3，存储 schema=4。

## 已通过

| 范围 | 结果及边界 |
| --- | --- |
| Python Harness | 15 个 unittest 通过；源码摘要校验、严格 JSON、原生工具/历史配对、格式修复、journal 重启、Cell 丢 ACK 重放/取消观察、Java/Python 数字及 Unicode 语义摘要；检查无模型 HTTP/权重加载导入 |
| Java 构建 | 实际 JDK 1.8.0_504；common、dispatcher、service-harness 的 Ant 构建通过；service 生产源及测试类以同 JDK 直接编译通过，排除缺独立 NLP 依赖的旧 TestNLP 示例 |
| Java 契约 | 44 个原 fixture 与摘要/会话/租约检查通过；新增 inference 两仓镜像、v3 profile 和源码摘要通过 |
| 推理网关 | test-inference 通过：重复提交/摘要冲突、外来身份拒绝、共享推理容量、独立 Python 处理槽、unknown 隔离、崩溃发送窗口、重启身份、排队取消、迟到结果与 Java 权威 usage |
| 外部 HTTP | Java 本机 HTTP fixture 验证 JSON/SSE、工具参数增量、finish reason、refusal、缺 usage、截断、HTTP 明确拒绝、无换行慢流的整体截止时间和未知参数拒绝 |
| SQLite | test-execution、test-runtime（8 组）、test-store（12 场景）通过；v1 升级及单独 v3→v4 测试保留原始 job/旧 backend 身份/unknown 容量，新 routing_hash 可与旧描述并存 |
| MySQL | 独立 MySQL 8.0.43 新数据目录；test-inference 通过。迁移账户只在专用库有 DDL（含 ALTER），迁移后使用仅 DML 运行账户；旧业务库写入拒绝。测试实例已停止 |
| 默认链路跨进程 | 真实 Cell + 3 个 Python v3 进程（含同角色 Baize2 两个 worker）+ 同 JVM AIGCService 的真实 Unit 队列，6 个任务成功，2 个共享物理槽；模拟推理 2.2 秒超过 1 秒租约，worker 心跳保持正常 |
| 原资源循环跨进程 | n06_n07_smoke.py 10 项检查通过，含资源 list/activate/read/final、背压、drain、JVM 重启/重连/接管、SSE/慢消费者、限额和 actual_finished 才释放槽 |
| 旧 Completion | ChatCompletionServiceTest 151、CompletionGatewayTest 35、CompletionHttpTest 94、ManagerRobustnessTest 290 项断言通过 |
| 构建图与差异 | check_build_graph.py、两仓 git diff --check、冻结 v1/v2 契约保留及新版镜像一致性检查通过 |

## 可复现入口

在实际 JDK 8 下：

```powershell
ant -f service-harness/build.xml test-inference test-execution test-runtime test-store
python -B service-harness/scripts/check_build_graph.py
```

Python 工程的独立环境执行：

```powershell
python -B -m unittest discover -s harness/tests -v
```

`inference_smoke.py --help` 给出实际 Cell/三进程测试参数。测试 host 为 service 测试类 HarnessInferenceCarpet；它安装真实 AIGCService 和注册 Unit，只模拟硬件回复，测试类不进入 service 正式 jar。

MySQL 使用 `n03_mysql_fixture.py` 创建新的隔离数据目录；向 InferenceTest JVM 传入 `-Dinference.test.config=<migrator.local.properties>` 与 `-Dexecution.test.runtime=<runtime.local.properties>`，测试后按 fixture 私有凭据停止该实例。不能指向业务库或复用包含测试行的库。

本机详细跨进程证据：`C:/Users/hermi/.cache/cube-harness/inference-final-20261018/report.json` 与对应 cell.log；旧循环证据位于 `inference-legacy-evidence-20261018`。私有连接配置只留本机 cache，不进入仓库。

## 未执行及限制

未访问真实 Baize/Baize2 模型、未加载/下载权重，未认证 GPU、真实模板/tokenizer、模型工具质量或物理取消。原生 Linux、远端 CI 和生产共同部署性能未执行。旧 service Ant 全量目标包含 TestNLP.java，当前依赖目录缺 cube.nlp；该目标失败，直接 JDK 8 编译覆盖所有其余源文件及本次修改。测试启动时还有原工程可选 tokenizer 资产缺失日志，不影响这些测试使用的模拟推理。

无结束证据的 unknown 故意保留容量，进程退出不能解除物理占用。上线前须显式 schema4 迁移、同时部署匹配的 Java/Python 契约、发布审阅后的新 model/task profile，再切换 active 绑定。旧直连 profile 停止新准入，保留旧 worker/journal 用于在途证据对账。
