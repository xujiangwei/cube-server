# 验证记录

验证日期：2026-10-25。本记录区分本地协议夹具与真实外部环境，不将 scripted model 或网页 transport 替身计为在线验收。

## 已完成

| 项目 | 结果 | 范围 |
| --- | --- | --- |
| 实际 JDK 8 + Ant `test stage` | PASS | Temurin 1.8.0_504；81 项 Java provider、包、HTTP/TLS 断言；5 项 Python 验收证据/可重复打包测试 |
| 六个实际技能 ZIP | PASS | 生产 PackageRepository 校验；operation、权限、输入/输出 schema 与实际 provider 一致 |
| 真实 Broker/JDBC 集成 | PASS | 六个 provider、租户/用户 ACL、附件跨任务访问拒绝、结果持久化、重复调用与重复 ACK |
| 核心工具回归 | PASS | `service-harness/build.xml` 的 `test-tools`，覆盖 durable dispatch、grant、重放、取消/未知、数据库失败及旧扩展示例 |
| 顶层构建边界 | PASS | `service-harness/scripts/check_build_graph.py`；原默认构建、部署、清理仍独立 |
| 真实 Cell 双 Runner 冒烟 | PASS | dispatcher REST → SHM → 一个 service-harness JVM、两个独立 Python tool Runner；6 个任务共 36 次成功工具调用；搜索上游共 6 次 |
| Runner 停启与 ACK 重送 | PASS | 负载分配与剩余单元继续工作；隔离 journal 模拟 ACK 丢失，原报告经真实 SHM 再确认；账本与报告不变，搜索不重复执行 |
| Trace 证据核对 | PASS | 脱敏 Trace 的结果摘要与原账本承诺一致，引用来自实际夹具检索 URL；scripted model 无法满足真实模型验收条件 |
| JAR 与 stage 检查 | PASS | Java class major 52、12 份 schema、无 Harness 核心/测试夹具类；stage JAR 与构建产物相同 |

HTTP/TLS 夹具覆盖正常、空结果、部分引擎失败、403、429、超时、无效响应、取消、静态 HTML、重定向和 HTTPS 降级拒绝、全部 DNS 地址验证、内网拒绝、TLS 主机名校验、chunked/gzip、响应大小与中文输出预算。其余能力覆盖 DECIMAL128 精度、除零/非法表达式、时区与夏令时、知识 ACL 和绑定附件。

最新双 Runner 证据为 [build/evidence-cell-6/results.json](build/evidence-cell-6/results.json)，对应 [脱敏 Trace](build/evidence-cell-6/trace.json) 和 [Cell 日志](build/evidence-cell-6/cell.log)。运行目录为 `build/cell-smoke-6/`，其中进程已关闭。`evidence-cell-1` 至 `3` 是早期失败调试记录，不作为最终验收依据。

构建工具链位于本地忽略目录 `build/toolchain/`，不属于发布物。构建验证命令：

```powershell
& 'build/toolchain/jdk8u504-b01/bin/java.exe' `
  -cp 'build/toolchain/ant.jar;build/toolchain/ant-launcher.jar;build/toolchain/jdk8u504-b01/lib/tools.jar' `
  org.apache.tools.ant.Main test stage '-Dstage.dir=build/stage-check'
```

## 真实外部环境

**NOT_RUN**：部署方实际 SearXNG、真实公网页面、真实模型的端到端搜索验收。本轮没有提供实际 SearXNG 端点、已发布的线上技能配置及用户/Trace token。

真实验收入口为 `scripts/search_smoke.py`，默认问题是“全网搜索主流的3D建模软件”。部署要求、JSON 开关、凭据环境变量、只读账本/导出文件用法见 [README.md](README.md)。正式验收需要真实模型调用、成功搜索证据、来源 URL 和答案引用全部通过。

本轮只在隔离目录完成 stage 检查，没有启用生产 provider、发布生产技能或修改租户授权。首版网页只支持公网 IPv4 HTTPS 443；JavaScript 渲染、向量检索、复杂文档解析和动态代码执行不在交付范围。
