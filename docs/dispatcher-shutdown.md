# Dispatcher 启停与停机故障排查

## 启停与资源释放

停止链路为 `stop.sh → stop-dispatcher.sh → shutdown.sh → bin/cell.sh`。脚本负责识别目标 JVM 并等待真实退出，dispatcher 在销毁回调中关闭请求入口和资源；故障定位需结合脚本输出、日志与线程栈。

| 能力 | 行为 |
| --- | --- |
| 角色与 PID 隔离 | dispatcher、service、ferryboat 分别使用 `bin/cell-dispatcher.pid`、`bin/cell-service.pid`、`bin/cell-ferryboat.pid`；支持自定义 `CC_PID` |
| 有界停止 | 等待真实目标退出，回收通知客户端；失败返回非零并保留 PID 文件 |
| 参数与返回码 | 透传 `-force` 等参数，总 stop 汇总所有服务失败状态 |
| 目标身份校验 | 按完整 JVM 参数核对主类、tag、安装目录，发信号前再次核对进程创建时间；拒绝无效 PID、错误角色、多个候选和不明身份 |
| 工作目录 | 固定切换至 `CC_BASE`，可通过 classpath 安装路径或 Linux 工作目录找回旧实例 |
| Shell 与 Java 兼容 | 使用 POSIX 参数解析及 JDK 8 `-Xloggc`，保留用户提供的 `JAVA_OPTS` |
| 语音流与文件等待 | 取消定时器、中断执行器，只清理本地流状态；文件块读取在关闭或中断时退出等待并保留中断标记 |
| 请求与客户端收尾 | `cellPredestroy` 拒绝新下游请求，取消等待；独立清理资源，允许重复销毁。关闭空闲和借出 HTTP 客户端，先取消异步响应与出站请求，HTTP 停止等待上限 5 秒 |
| 端口与监控上报 | 流端口绑定与停止互斥；关闭上报入口、中断在途请求，由提交线程退出时关闭其客户端 |

## 服务器更新

使用 JDK 8 构建 common 和 dispatcher，两个 JAR 必须配套替换，以保持 HTTP 停止接口一致。

替换部署目录中的以下文件：

- `libs/cube-common-3.0.jar`
- `libs/cube-dispatcher-3.0.jar`
- `bin/cell.sh`、`bin/cell-process.sh`
- `stop-dispatcher.sh`、`stop-service.sh`、`stop-ferryboat.sh`、`stop.sh`

无需改动业务配置或 `cell.jar`。`bin/cell-process.sh` 是 `cell.sh` 必需的配套文件，不可漏传。脚本使用 LF 换行；服务器需要 Linux `/proc` 和支持 `-p/-e/-ww/-o` 的 `ps`，由原服务账号执行。不能读取进程身份或发现 PID 指向其他进程时，脚本拒绝发送信号。

如果旧 dispatcher 已经卡住，可以先更新脚本，再用新脚本停止旧实例；新脚本会查找同安装目录、同 tag 的旧 JVM，不使用旧共享 `bin/pid` 发信号。随后替换两个 JAR 并重启 dispatcher。新脚本和新 JAR 都需要发到服务器，本地修改不会影响已运行的旧 JVM。

先将部署产物准备到临时目录并备份现有文件，再按“更新脚本 → 停止旧实例 → 替换两个 JAR → 启动”的顺序安装。不要直接覆盖正在运行的 JAR。

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

## 验证入口

- 使用实际 JDK 8 构建 common 和 dispatcher，并检查启动脚本的 JVM 参数。
- `ShutdownTest`：借出中的 HTTP 客户端、控制台上报阻塞、后台语音流、半截上传、下游等待、资源释放异常、部分初始化和重复销毁。
- `deploy/test/cell-process-test.sh`：可控 Linux 进程表、角色与安装目录隔离、带空格路径、失效 PID、旧启动方式、重复实例、TERM/KILL、PID 复用、通知客户端、锁和参数透传。
- 配套回归：`ManagerRobustnessTest`、`RestMetricsTest`、`CapabilityRoutingTest`、`CompletionGatewayTest`。

Shell 回归通过可控进程表测试信号选择，不连接业务服务器。Java 回归使用本地测试进程，不依赖真实下游。服务器上的完整启动/停止仍需按上述顺序执行；如再次异常，线程栈可以确定具体阻塞位置。
