# Harness 文件处理能力

文件处理是平台通用工具，与业务技能共用任务、权限、持续互动、调度和产物链路。安装、授权、运行顺序见[部署指南](BUSINESS-DEPLOYMENT.md#通用文件工具部署)，输入上传与产物下载见[控制与文件](CONTROL-FILES.md)。

| 技能 | 能力与产物 | 执行环境 |
| --- | --- | --- |
| `common/document` | PDF 文字水印 → PDF 副本 | `pdf-watermark-v1` |
| `common/pdf` | 指定页、文本层提取、按附件顺序合并、逐页拆分 → PDF、UTF-8 TXT 或 ZIP | `pdf-watermark-v1` |
| `common/image` | JPEG/PNG 裁剪、按比例缩小、按字节上限压缩、格式转换 → JPEG 或 PNG | `image-utility-v1` |
| `common/table` | CSV/XLSX 读取、按列筛选、去重、排序和投影 → CSV 或 XLSX | `table-utility-v1` |
| `common/report` | 内置版式：标题、章节和可选图片 → Word 或 PDF | `report-utility-v1` |
| `common/ocr` | JPEG/PNG 整图或指定区域识字 → TXT 与预览 | 已启用的 `OmniPerception` Unit |
| `common/transcription` | WAV/MP3 转写 → TXT、预览、语言及音频总时长 | 已启用的 `Speech` Unit |

PDF 页码从 1 开始，显式页码决定输出顺序。合并使用 `inputIds` 顺序，最多 16 项，每项 10 MiB、输入合计 20 MiB，PDF 共最多 1000 页。拆分 ZIP 内按请求顺序存储独立 PDF。文本层不存在时返回 `PDF_TEXT_LAYER_MISSING`，扫描件可另用图片 OCR。编辑操作拒绝加密和签名 PDF。

图片先按 EXIF 方向旋正，再按旋正坐标裁剪并等比缩小；JPEG 以白底处理透明区域。表格首行必须为唯一非空列名，最多 128 列、10,000 行。筛选条件取交集；去重保留输入中的首条；排序和筛选按文本比较，如升序下 `10` 在 `2` 前。XLSX 公式仅取文件中已有的缓存值，XLSX 导出写为字面量，CSV 导出保护可能被电子表格解释为公式的内容。报告使用固定标题、章节和附图结构，接受结构化内容，无需上传模板文件。

OCR 使用 Unit 的 `region_ocr`，省略区域时取原图全幅，显式坐标使用未应用 EXIF 旋转的原图像素；图片上限 1 MiB、1600 万像素。转写接受最多 10 MiB、5 分钟的 WAV 或 MP3；当前没有逐词时间戳和说话人标注。有文字时保存完整 TXT，模型上下文只接收有限预览。Unit 未就绪、已知失败和发送后无回执分别处理；最后一种保留未知状态与容量预留，不自动重发。

## 执行边界

Java Harness 固定输入、参数、权限和环境档案，管理文件配额、摘要、产物和任务恢复；Python 工作器执行安装好的 `harness/file_operation.py`，调用独立 venv 的 PDF 库生成并重新打开输出。SKILL ZIP 只包含技能正文、schema 和标准库薄入口，不能安装依赖或选择解释器、宿主路径、网络地址与任意命令。普通 Runner 的 `-I -S` 及标准库限制保留。

`DocumentProvider` 属于可信 JVM 扩展，只授权文件并转换结果。工具声明 `effect=artifact-create`，权限为 `document.watermark`；该 effect 只允许 `FileProcessingProvider`，不授予业务系统写回能力。执行器使用 `document-python`（32 MiB 工作区），任务参数可显式选择 `runtime: "document-python"`。输入与输出分别最多 10 MiB，PDF 最多 1000 页，每次最多显式指定 256 个页码。

## 参数与结果

必需参数 `inputId` 是当前任务已绑定、属于当前用户且有效的 `application/pdf` Input；`text` 为 1～128 字符水印文字。水印文字或目标文件缺失时，技能要求模型通过现有问答机制澄清。

| 可选参数 | 取值与默认行为 |
| --- | --- |
| fileName | `.pdf` 文件名，默认 watermarked.pdf |
| opacity | 0.05～0.8，默认 0.18 |
| fontSize | 8～96 pt，默认 42；按页面大小自动缩小，低于 8 pt 拒绝 |
| rotation | -180～180 度，默认 45 |
| pages | 从 1 开始、不可重复的页码数组，默认全部页 |

支持基本拉丁字符、简体中文及字体覆盖的中日韩标点；不支持的字符明确返回 `WATERMARK_CHARACTER_UNSUPPORTED`。固定 Noto Sans SC 字体随能力环境发布，核对 SHA-256，输出按字形子集嵌入，无需依赖查看端字体。字体来源、构建信息和 OFL 许可随环境资源提供。

居中的浅灰水印叠加于内容之上；处理旋转与裁剪页面，保留原文件及原有文档结构。加密、签名、损坏 PDF、无效页码和不支持的页面尺寸明确拒绝。处理成功结果包含 `operation=pdf.watermark.v1`、`inputId` 与 `artifact`（artifactId/runId/fileName/mediaType/size/sha256/expiresAt）；文件内容仍通过鉴权下载接口获取。

## Python 环境契约

节点宿主使用 `harness/requirements.txt`，各第三方能力使用独立 venv。`fileEnvironments` 是平台私有配置，声明解释器和环境清单的绝对路径；一个工作器可登记多个环境，同一能力也可在多个工作器部署。不同库版本不得通过同一环境标识冒充同一档案。

`environment.json` 固定环境 id、operation、CPython 完整补丁版本、全部业务依赖版本、requirements.lock 摘要、字体及许可资源摘要。requirements.lock 固定全部传递依赖和允许的 wheel SHA-256。安装时只接受 wheel、完整 hash lock，不在任务期间运行 pip。

Python 启动和每次执行前检查 venv 隔离、解释器版本、实际安装包集合/版本、包 RECORD 文件摘要及资源；忽略宿主 PYTHONPATH、PYTHONHOME 和用户 site-packages。Java Provider 与 Python 登记共同使用清单的规范化摘要，Java 私有配置逐 worker 授权 `worker.<id>.file.environment.<environmentId>=<digest>`。摘要不匹配的工作器不能领取相应文件作业。环境不可用时拒绝执行，不退回全局 Python。

库或 Python 升级时创建新 venv 与新环境档案，先验证，再 drain 对应工作器并成套更新 Java、Python 配置。保留旧环境和原 runtimeDir/journal 直至在途任务完成；不在原 venv 中原地升级。环境清单不会把模型生成代码变成可执行程序。

## 文件传输与恢复

受控 `file_exchange` 仅供已受理且 grant 有效的文件处理作业使用，经现有 Cell 通道传输，每块最多 16 KiB。输入由宿主固定工具参数中的 `inputId` 或对应 `inputIndex` 的 `inputIds` 项决定；工作器核对组装后的完整摘要。输出先按该调用申请唯一产物并预留配额，再按 offset 写入；同 offset 同字节幂等重放，异字节拒绝，越界拒绝。完成后校验大小、SHA-256 和声明类型，通过原子重命名与文件账本标记 ready。

任务取消、授权失效或过期后停止新的文件消费和提交。固定子进程有超时、工作区/输出配额及 512 MiB 进程内存上限；Windows 使用 Job Object，Linux 使用进程组和 RLIMIT_AS。子进程结束证据、结果 journal、ACK 重放和 unknown 隔离沿用 Runner；不能通过重启重做可能已开始的工作。失败上传保留在文件账本，按未完成上传 TTL 回收，不能作为完成结果交付。

新增文件能力时须提供审核过的固定处理器、参数与结果 schema、Provider 和相应环境清单。通用能力生成的是任务产物；业务系统文件回写使用有权业务连接器。

## 正式验证入口

`service-harness-skill` 的 `ant test-document` 验证真实 JDBC、文件绑定、授权、环境匹配、分块重放、部分写入恢复和产物下载。Python `harness.tests.test_file_processing` 使用 `HARNESS_PDF_PYTHON` 指定独立 PDF venv，验证中文、旋转页、指定页、错误摘要、运行中取消及依赖/字体不匹配。另设置 `HARNESS_PDF_ALTERNATE_PYTHON` 与 `HARNESS_PDF_ALTERNATE_MANIFEST` 可验证两个不同依赖版本的环境并发执行，且不向节点宿主加载 PDF 库。

`service-harness-skill/scripts/document_smoke.py` 提供实际 JDK 8、dispatcher/Cell/SHM、两个独立 PDF 工作器及普通 Runner 的完整集成回归；使用 scripted 模型固定工具决定，验证文件执行与传输，不作为真实模型自然语言质量认证。

`ant test-utilities` 验证工具包发布、按索引访问附件、Unit 回执和文字产物下载。Python `harness.tests.test_file_utilities` 使用 `HARNESS_FILE_TEST_ENVS` 指定包含 `harness-<环境标识>` 的环境目录，运行实际处理子进程并重新打开 PDF、图片、表格和报告产物。
