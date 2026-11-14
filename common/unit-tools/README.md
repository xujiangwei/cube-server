# Unit 操作目录与调用契约

此目录维护 Unit 业务操作的唯一目录源码、生成器和覆盖检查。目录包含 14 个内置 Unit、CV Service 端点和 OmniForest 插件的 51 个操作定义。正式范围以 `catalog_source.py` 为准；模型文件存在、内部辅助函数存在或历史 Action 常量存在，均不代表新增业务操作。

目录用于固定参数、权限和语义，不能据此判断模型已经部署、操作已绑定网关或租户已经获准调用。当前可执行工具仍见 [Harness 工具说明](../../service-harness/TOOLS.md)；全量执行接入遵循 [Unit 工具接入方案](../../service-harness/UNIT-TOOLS.md)。

## 产物和契约

| 产物 | 用途 |
| --- | --- |
| `catalog_source.py` | 操作定义、正式实现来源，以及生命周期、内部辅助方法和未实现 Action 的显式分类 |
| `common/resources/cube/aigc/unit/unit-operations.json` | 随 `cube-common-3.0.jar` 打包的版本化目录 |
| Python 工程的 `cube/unit_tools/unit-operations.json` | 同一生成器输出的独立部署副本；运行时不依赖 Java 源码工程或相邻目录 |
| `cube.aigc.unit.UnitCatalog`、`UnitOperationDescriptor` | Java 8 的不可变目录及业务描述投影 |
| `UnitExecutionContext`、`UnitExecutionRequest`、`UnitExecutionReceipt`、`UnitGateway` | 可信身份、冻结调用意图、结果及网关实现接口 |
| Python 的 `cube.unit_tools.UnitCatalog` | 无模型依赖的描述读取、摘要验证及私有绑定读取 |

目录格式为 `schemaVersion=1`。每个操作的 `descriptorDigest` 只覆盖业务描述；整个目录另有 `catalogDigest`，包括私有绑定。摘要使用 SHA-256、UTF-8、按键排序的紧凑 JSON；数值不使用指数格式或无意义的小数尾零，字符串保留 Unicode，转义控制字符、双引号和反斜杠。

业务投影仅含逻辑能力、操作、schema、权限、effect、审批、媒体类型、依赖及限制。`binding`、源码定位及 `adapterId` 留在安装目录内部。业务 schema 不包含传输方式、JVM 位置、worker 或物理实例选择字段；文件和数据源使用受控引用。

`UnitExecutionContext` 由已认证的宿主构造，并与用量身份交叉核对；不能从工具参数恢复。宿主和网关仍须校验参数 schema、资源授权、审批、预算、取消和吊销。`UnitGateway` 是执行实现接口，目录读取器不提供成功占位实现。网关应先持久化幂等执行意图，再做物理派发；未知状态不得自动重发或释放物理容量。

Python `UnitRegistry.operation_contracts(config_key)` 只返回已注册单元的安装描述，不加载模型、不推断就绪；撤销注册后也停止返回描述。未知扩展在安装经审核的目录前返回空描述。

## 修改和校验

在 `cube-server` 根目录运行。`--python-root` 指向配套 Python 工程的绝对目录：

```powershell
python -B common/unit-tools/generate.py --python-root C:/platform/apexnova-wisdom
python -B common/unit-tools/generate.py --check --python-root C:/platform/apexnova-wisdom
python -B common/unit-tools/test_catalog.py --python-root C:/platform/apexnova-wisdom
```

生成器只使用 Python 标准库，以 AST 检查注册和实现，不导入 torch、transformers 或模型模块。两份 JSON 必须同时提交；部署端不手工修改生成文件。修改操作业务含义或 schema 时，应按发布契约新增操作版本，已固定的运行不能被新定义替换。

覆盖检查交叉核对内置 `UNIT_SPECS`、AIGC Action 注册及实际 handler、CV Action、插件 manifest、插件 `SUPPORTED_ACTIONS` 和公开 Unit 方法。新增正式入口必须增加操作定义；生命周期、别名或内部辅助函数必须给出显式分类。`Embedding.encode` 目前只有真实 Unit 方法，没有既有 Service Action；空绑定字段表示仍需网关适配，不能作为可派发入口。`textToImage` 的现有空实现明确排除。

使用 JDK 8 执行构建与回归：

```powershell
ant -f common/build.xml -Dlib.dir=C:/platform/java-libs -Dpython.exe=python unit-contract-test
ant -f common/build.xml -Dpython.exe=python -Dpython.unit.root=C:/platform/apexnova-wisdom unit-catalog-coverage
ant -f service-harness/build.xml test-tools
```

`lib.dir` 指向现有 Java 依赖目录；可用 `extra.classpath` 补充独立依赖 JAR。普通 Common 构建检查 Java 目录生成物并把资源加入 JAR；跨工程覆盖目标额外验证 Python 副本及源码。Java 契约回归检查跨语言摘要、业务投影、不可变性、权限与身份绑定；源码覆盖回归通过新增 Unit、Action、插件能力和替换 handler 的变异用例验证漏接会失败。

平台更新顺序为：生成并校验两份目录，构建 Common 与 Harness，在配套 Python 工程运行目录和注册表回归，再更新对应部署产物。仅更新目录和公共契约不会自动发布新工具或启用模型；实际执行链路完成后，按 Harness 的签名、权限和就绪流程发布。
