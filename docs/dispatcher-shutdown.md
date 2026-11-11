# Dispatcher 启停与停机故障排查

## 已修复的问题

本次检查覆盖 `stop.sh → stop-dispatcher.sh → shutdown.sh → bin/cell.sh` 及 dispatcher 自身的 Java 资源释放，不检查或修改 `cell.jar`。以下是代码中确认存在的问题；没有读取故障服务器的日志或线程栈，不能据此断定本次现场故障只由其中某一项触发。

| 原有问题 | 修复后的行为 |
| --- | --- |
| dispatcher、service、ferryboat 共用 `bin/pid`，后启动的服务覆盖前一个 PID | 默认分别使用 `bin/cell-dispatcher.pid`、`bin/cell-service.pid`、`bin/cell-ferryboat.pid`；保留自定义 `CC_PID` |
| stop 通知返回即结束，不确认 JVM 是否退出；通知客户端自己也可能挂住 | 等待真实目标退出，停止有时间上限，回收通知客户端；失败返回非零并保留 PID 文件 |
| dispatcher 入口丢弃 `-force`；总 stop 的返回码仅反映最后一个服务 | 透传参数，并汇总所有服务的失败状态 |
| 强制停止直接相信共享 PID 文件 | 按完整 JVM 参数核对主类、tag、安装目录，并在发信号前再次核对进程创建时间；拒绝无效 PID、错误角色、多个候选和不明身份 |
| 启停继承调用者的工作目录，相对配置和停止控制文件可能落在不同位置 | 固定切换至 `CC_BASE`；旧实例可以通过 classpath 中的安装路径或 Linux 工作目录找回 |
| `/bin/sh` 脚本使用 Bash 数组切片和 `let`；默认 GC 参数使用 JDK 9+ 的 `-Xlog` | 使用 POSIX 参数解析，GC 日志参数改为 JDK 8 的 `-Xloggc`，保留用户提供的 `JAVA_OPTS` |
| 语音流处理线程池未关闭；停止时逐条同步删除远端临时文件 | 取消定时器并中断执行器，停机只清理本地流状态，不再等待下游文件删除 |
| 文件块读取忽略中断，半截上传可能继续等待约 5 分钟 | 关闭存储器或收到中断立即退出等待，保留线程中断标记 |
| Performer 只调用 `shutdown()`，仍允许新请求；异常会跳过后续资源清理 | 在 `cellPredestroy` 拒绝新下游请求，取消等待并中断执行器；各资源分别清理，销毁回调可重复执行 |
| HTTP 工厂只关闭空闲客户端，借出中的 Jetty 客户端仍有线程 | 同时关闭空闲与借出客户端，关闭后拒绝新借用；先取消异步响应及出站请求，再停止 HTTP 服务，HTTP 停止等待上限为 5 秒 |
| 流端口异步绑定与 stop 交错，可能在停止之后才执行绑定 | 绑定与停止互斥，已经停止的实例不再绑定 |
| 控制台上报有独立的 HTTP 客户端，停止 dispatcher 时未取消在途上报 | 停机时关闭上报入口、中断在途请求，提交线程在退出时关闭自己的 HTTP 客户端 |

## 服务器更新

使用 JDK 8 构建 common 和 dispatcher。两个 JAR 必须配套替换：dispatcher 使用了本次 common 新增的 HTTP 停止超时方法。

替换部署目录中的以下文件：

- `libs/cube-common-3.0.jar`
- `libs/cube-dispatcher-3.0.jar`
- `bin/cell.sh`、新增的 `bin/cell-process.sh`
- `stop-dispatcher.sh`、`stop-service.sh`、`stop-ferryboat.sh`、`stop.sh`

无需改动业务配置或 `cell.jar`。`bin/cell-process.sh` 是 `cell.sh` 必需的配套文件，不可漏传。脚本使用 LF 换行；服务器需要 Linux `/proc` 和支持 `-p/-e/-ww/-o` 的 `ps`，由原服务账号执行。不能读取进程身份或发现 PID 指向其他进程时，脚本拒绝发送信号。

如果旧 dispatcher 已经卡住，可以先更新脚本，再用新脚本停止旧实例；新脚本会查找同安装目录、同 tag 的旧 JVM，不使用旧共享 `bin/pid` 发信号。随后替换两个 JAR 并重启 dispatcher。新脚本和新 JAR 都需要发到服务器，本地修改不会影响已运行的旧 JVM。

补丁包包含上面列出的文件。先解压到临时目录，备份现有同名文件，再按“更新脚本 → 停止旧实例 → 替换两个 JAR → 启动”的顺序覆盖。不要直接覆盖正在运行的 JAR。

```sh
# 可从任意工作目录调用；将路径替换成服务器的实际部署目录。
sh /opt/cube/stop-dispatcher.sh
echo $?

# 普通停止仍未退出时，允许在等待和 TERM 失败后发送 KILL。
sh /opt/cube/stop-dispatcher.sh -force

# 启动后再停一次，确认 PID、退出码和进程退出。
sh /opt/cube/start-dispatcher.sh
cat /opt/cube/bin/cell-dispatcher.pid
sh /opt/cube/stop-dispatcher.sh
```

普通 stop 默认先等待优雅通知 30 秒，超时后发送 TERM 并再等 10 秒；仍未退出返回非零。`-force` 在这两步之后允许 KILL，并最多再等待 5 秒。不会仅因通知客户端返回成功就打印已停止。可用环境变量调整等待秒数：

```sh
CC_STOP_TIMEOUT=60 CC_TERM_TIMEOUT=15 sh /opt/cube/stop-dispatcher.sh
```

如果失败，PID 文件会保留；在强制结束之前可用 JDK 8 的 `jstack <pid>` 保存线程栈，连同 `logs/cell_dispatcher.out` 和脚本输出定位剩余阻塞。若提示 `.pid.lock` 已存在，先确认另一次启停脚本已经结束，再删除该锁目录；不要在启停仍在运行时删除锁。重复启动也会被拒绝。

## 本地验证

- 使用实际 Corretto JDK `1.8.0_504` 编译 common 的 805 个和 dispatcher 的 207 个 Java 源文件，默认 JVM 参数也通过 JDK 8 启动校验。
- `ShutdownTest` 的 23 项断言通过：借出中的 HTTP 客户端、控制台上报阻塞、后台语音流线程、半截上传、8 分钟下游等待、资源释放异常、部分初始化和重复销毁。
- `deploy/test/cell-process-test.sh` 的 42 项断言通过：模拟 Linux 进程表，检查角色与安装目录隔离、带空格的路径、缺失及失效 PID、旧启动方式、重复实例、TERM/KILL、PID 复用、通知客户端挂住、锁和参数透传。
- `ManagerRobustnessTest`（292 项）、`RestMetricsTest`（62 项）、`CapabilityRoutingTest`（86 项）、`CompletionGatewayTest`（36 项）全部通过。

Shell 回归通过可控进程表测试信号选择，不连接业务服务器。Java 回归使用本地测试进程，不依赖真实下游。服务器上的完整启动/停止仍需按上述顺序执行；如再次异常，线程栈可以确定具体阻塞位置。
