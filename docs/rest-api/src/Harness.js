/**
 * @apiDefine Harness Harness 任务服务
 * 创建异步任务，查询执行进度，在需要时补充输入或提交审批，并下载生成的文件。<br/>
 * 业务基地址为 http://211.157.179.37:7010，统一前缀为 /harness/v1，使用 Cube 用户令牌认证。
 * <p>推荐顺序：查询任务能力 → 准备附件（可选）→ 创建任务 → 查询任务或事件 → 处理补充输入/审批 → 读取结果及产物。</p>
 * <p>所有 POST/PUT 请求必须携带 Idempotency-Key，取值为 1～128 位字母、数字或 _.:-。
 * 重试同一次操作时使用相同的键和请求内容；修改内容后使用新键。同键不同内容可能返回 409。
 * 资源 ID 使用同一字符范围，均应取自接口返回值。</p>
 * <p>JSON 请求使用 application/json，请求体不超过 256 KiB、嵌套深度不超过 32，字段以各接口声明为准。
 * 上传文件使用 application/octet-stream。时间戳均为 Unix 毫秒。
 * 除文件下载和 SSE 外，成功响应直接为 JSON 对象，失败时读取 errorCode、requestId 和 retryable。</p>
 * <p>任务、输入文件和产物仅对所属用户开放；审批和诊断导出另需相应权限。
 * 文件、反馈、诊断等能力须由服务提供方开通。示例中的令牌、资源 ID、模型和技能名称应替换为实际值。</p>
 */

/**
 * @apiDefine HarnessAuth
 * @apiHeader {String} Authorization Bearer &lt;Cube Token&gt;，使用已有 Cube 用户 token。
 * @apiError {String} errorCode 稳定的错误码；429、503 时可重试。
 * @apiError {String} requestId 本次失败请求的追踪 ID。
 * @apiError {Boolean} retryable 是否可重试；当前仅 429、503 为 true。
 * @apiErrorExample {json} 未认证
 *     HTTP/1.1 401 Unauthorized
 *     {"errorCode":"UNAUTHORIZED","requestId":"request-001","retryable":false}
 */

/**
 * @apiDefine HarnessIdempotency
 * @apiHeader {String} Idempotency-Key 必填，1～128 位字母、数字或 _.:-；重试同一次操作时保持不变。
 */

/**
 * @apiDefine HarnessJson
 * @apiHeader {String} Content-Type application/json。
 */

/**
 * @apiDefine HarnessSnapshot
 * @apiSuccess (202) {String} runId 任务 ID。
 * @apiSuccess (202) {Number} version 任务版本；后续控制操作用作 expectedVersion。
 * @apiSuccess (202) {Number} eventSeq 最新事件序号。
 * @apiSuccess (202) {String} state queued、running、waiting_input、waiting_approval、cancelling、reconciling、succeeded、failed、cancelled 或 timed_out。
 * @apiSuccess (202) {String} executionSettlement 执行确认状态：pending 为等待确认，confirmed 为已确认，unknown 为尚无法确认；可通过查询任务获取后续变化。
 * @apiSuccess (202) {String} executionProfileVersion 本次任务采用的执行配置版本，仅供诊断关联。
 * @apiSuccess (202) {Object} selection 本次任务实际选择的模型、执行器、技能和工具。
 * @apiSuccess (202) {Object} selection.model 模型名称和版本。
 * @apiSuccess (202) {String} selection.model.id 模型名称。
 * @apiSuccess (202) {String} selection.model.version 模型版本。
 * @apiSuccess (202) {Object} selection.runtime 执行器名称和版本。
 * @apiSuccess (202) {String} selection.runtime.id 执行器名称。
 * @apiSuccess (202) {String} selection.runtime.version 执行器版本。
 * @apiSuccess (202) {Object[]} selection.skills 本次任务可用的技能版本。
 * @apiSuccess (202) {String} selection.skills.name 技能名。
 * @apiSuccess (202) {String} selection.skills.version 技能版本。
 * @apiSuccess (202) {String} selection.skills.digest 技能内容摘要。
 * @apiSuccess (202) {String[]} selection.tools 本次任务可用的工具名。
 */

/**
 * @apiDefine HarnessInputMetadata
 * @apiSuccess {String} inputId 输入文件 ID；供 input.attachmentIds 引用。
 * @apiSuccess {String} [runId] 已绑定时返回的任务 ID。
 * @apiSuccess {String} fileName 文件名。
 * @apiSuccess {String} mediaType 声明的文件媒体类型。
 * @apiSuccess {Number} size 文件字节数。
 * @apiSuccess {String} sha256 文件字节的 SHA-256，64 位小写十六进制。
 * @apiSuccess {Number} createdAt 创建时间。
 * @apiSuccess {Number} expiresAt 到期时间。
 * @apiSuccess {String} state uploading、ready、expired、delete_pending 或 deleted；仅 ready 且未到期可绑定任务。
 * @apiSuccess {Boolean} downloadable 输入文件为 false。
 */

/**
 * @api {get} /harness/v1/capabilities 查询 Harness 能力
 * @apiName HarnessCapabilities
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 查询任务服务是否可用以及当前可接收的任务量，可用于进入任务页面时展示服务状态。
 *                 <p><code>runsReady=true</code> 表示当前可受理任务，仍需结合 <code>capacity.available</code> 判断空闲容量。未就绪或容量为零时应暂缓提交，并在稍后重新查询；创建任务时仍可能返回繁忙错误。</p>
 *                 <p>选择模型、执行器和技能请使用<a href="#api-Harness-HarnessTaskProfiles">查询模型、执行器及授权能力</a>接口。<code>registeredWorkers</code>、<code>capacity.backends</code> 和 <code>files</code> 为诊断明细，普通接入无需据此选择服务节点。</p>
 * @apiUse HarnessAuth
 * @apiSuccess {String} service 服务名称。
 * @apiSuccess {Number} protocolVersion 能力协议版本，仅供诊断关联。
 * @apiSuccess {String} execution 服务执行模式标识，可能为 scripted、resource-loop 或 trusted-tool-loop；创建任务无需填写。
 * @apiSuccess {Object[]} registeredWorkers 已注册单元；按角色返回身份、ready 状态、能力和容量信息。
 * @apiSuccess {String} registeredWorkers.workerId 注册单元 ID；同一角色可存在多个独立单元。
 * @apiSuccess {String} registeredWorkers.instanceId 此次服务节点的实例标识，仅供诊断关联。
 * @apiSuccess {String} registeredWorkers.role 单元角色，如 model 或 runner。
 * @apiSuccess {Number} registeredWorkers.transportId 服务连接标识，仅供诊断关联。
 * @apiSuccess {Boolean} registeredWorkers.draining 是否正排空任务。
 * @apiSuccess {Number} registeredWorkers.leaseRemainingMs 租约剩余有效时间，单位：毫秒。
 * @apiSuccess {Boolean} registeredWorkers.ready 是否可接收与其能力匹配的任务。
 * @apiSuccess {Boolean} [registeredWorkers.registered] 节点是否已登记可用能力；部分节点不返回此字段。
 * @apiSuccess {String[]} [registeredWorkers.capabilities] 基础/模型单元的能力标识列表。
 * @apiSuccess {Boolean} [registeredWorkers.healthy] 服务节点是否健康。
 * @apiSuccess {Object} [registeredWorkers.descriptor] 模型节点的能力描述；部分节点不返回此字段。
 * @apiSuccess {String} registeredWorkers.descriptor.model 模型 ID。
 * @apiSuccess {String} registeredWorkers.descriptor.backendId 推理服务标识，仅供诊断关联。
 * @apiSuccess {Number} registeredWorkers.descriptor.capacity 对应推理服务的总并发容量。
 * @apiSuccess {Number} registeredWorkers.descriptor.protocolVersion 模型节点支持的协议版本，仅供诊断。
 * @apiSuccess {String} [registeredWorkers.descriptor.adapter] 模型接入适配器名称。
 * @apiSuccess {String} [registeredWorkers.runtimeId] Runner 关联的执行器 ID。
 * @apiSuccess {String} [registeredWorkers.runnerType] Runner 类型 tool 或 sandbox。
 * @apiSuccess {Number} [registeredWorkers.capacity] Runner 总容量。
 * @apiSuccess {Number} [registeredWorkers.occupied] Runner 已占用量。
 * @apiSuccess {Number} [registeredWorkers.available] Runner 当前可用量。
 * @apiSuccess {Boolean} infrastructureReady 服务基础状态是否正常；是否可创建任务请读取 runsReady。
 * @apiSuccess {Boolean} runsReady 当前是否可受理任务；指定模型和执行器的可用性还应查询任务能力目录。
 * @apiSuccess {Boolean} [accepting] 服务当前是否接受新任务。
 * @apiSuccess {String[]} [runnerTypes] 当前支持的工具执行类型列表，仅供诊断。
 * @apiSuccess {Object} [capacity] 当前任务容量；未提供容量信息时可省略。
 * @apiSuccess {Number} capacity.registeredUnits 注册的模型单元数。
 * @apiSuccess {Number} capacity.available 当前可用执行量。
 * @apiSuccess {Number} capacity.executableConcurrency 实际可执行并发。
 * @apiSuccess {Object[]} capacity.backends 推理服务容量明细；普通调用读取顶层 available 即可。
 * @apiSuccess {String} capacity.backends.backendId 推理服务标识，仅供诊断关联。
 * @apiSuccess {Number} capacity.backends.capacity 推理服务的总容量。
 * @apiSuccess {Number} capacity.backends.occupied 正在执行的推理数量。
 * @apiSuccess {Number} capacity.backends.processingSlots 已就绪的请求处理数。
 * @apiSuccess {Number} capacity.backends.processingAvailable 当前空闲的请求处理数。
 * @apiSuccess {Number} capacity.backends.inferenceUnits 当前注册并可用于本后端的推理容量。
 * @apiSuccess {Number} capacity.backends.inferenceAvailable 当前可用的推理容量。
 * @apiSuccess {Number} capacity.backends.readyUnits 就绪处理槽数，与 processingSlots 一致。
 * @apiSuccess {Number} capacity.backends.available 该推理服务当前可接收的任务数。
 * @apiSuccess {Number} capacity.backends.executableConcurrency 该推理服务当前可执行的并发任务数。
 * @apiSuccess {Object/Null} [files] 文件能力诊断信息；文件能力不可用时为 null，未提供时可省略。
 * @apiSuccess {Object[]} files.states 按文件状态汇总的计数，仅供诊断。
 * @apiSuccess {String} files.states.status 文件状态。
 * @apiSuccess {Number} files.states.count 该状态的文件数。
 * @apiSuccess {Number} files.states.bytes 该状态的文件字节总数。
 * @apiSuccess {Object[]} files.pins 按固定引用类型汇总的计数。
 * @apiSuccess {String} files.pins.kind 引用类型。
 * @apiSuccess {Number} files.pins.count 该类型的引用数。
 * @apiSuccess {Object[]} files.failures 最近文件清理错误，最多 100 条。
 * @apiSuccess {String} files.failures.file_id 文件 ID。
 * @apiSuccess {Number} files.failures.attempts 清理尝试次数。
 * @apiSuccess {String/Null} files.failures.last_error 最近清理错误说明。
 * @apiSuccess {Number/Null} files.failures.next_attempt 下次重试时间，Unix 毫秒时间戳。
 * @apiSuccess {String/Null} files.lastError 最近一次清理扫描的错误码；成功扫描后为 null。
 * @apiSuccess {Boolean} files.cleanupEnabled 是否启用文件清理。
 *
 * @apiExample {http} 请求地址与请求头
 *     GET http://211.157.179.37:7010/harness/v1/capabilities
 *     Authorization: Bearer <Cube Token>
 *
 * @apiSuccessExample {json} 服务暂未就绪：稍后重试
 *     HTTP/1.1 200 OK
 *     {
 *         "service": "Harness",
 *         "protocolVersion": 1,
 *         "registeredWorkers": [],
 *         "infrastructureReady": false,
 *         "runsReady": false,
 *         "execution": "scripted"
 *     }
 */

/**
 * @api {get} /harness/v1/task-profiles 查询模型、执行器及授权能力
 * @apiName HarnessTaskProfiles
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 查询当前用户可以使用的模型、执行器、技能和工具，为创建任务提供可选项。
 *                 <p>首次接入可直接使用 <code>defaults</code> 中的默认项；需要指定模型或执行器时，从列表选择名称，或使用 <code>id@version</code> 固定版本。选择技能时应检查 <code>compatibleRuntimes</code>，确保与所选执行器兼容。</p>
 *                 <p><code>ready</code>、<code>available</code> 反映查询时的可用状态；不可用时读取 <code>reason</code> 并稍后重试。工具列表用于了解可用能力，创建任务时通过 <code>skills</code> 选择技能，实际可用工具见任务响应。</p>
 * @apiUse HarnessAuth
 * @apiSuccess {Object} defaults 默认模型、执行器的 id/version。
 * @apiSuccess {Object} defaults.model 模型的名称和版本。
 * @apiSuccess {String} defaults.model.id 模型或执行器的名称标识。
 * @apiSuccess {String} defaults.model.version 版本标识。
 * @apiSuccess {Object} defaults.runtime 执行器的名称和版本。
 * @apiSuccess {String} defaults.runtime.id 模型或执行器的名称标识。
 * @apiSuccess {String} defaults.runtime.version 版本标识。
 * @apiSuccess {Object[]} models 模型 id/version、current、capabilities、nativeTools、ready、capacity、available、reason。
 * @apiSuccess {String} models.id 模型或执行器的名称标识。
 * @apiSuccess {String} models.version 版本标识。
 * @apiSuccess {String[]} models.capabilities 支持的能力标识列表。
 * @apiSuccess {Boolean} models.nativeTools 是否支持模型原生工具调用。
 * @apiSuccess {Boolean} models.current 是否为当前发布的版本。
 * @apiSuccess {Boolean} models.ready 当前是否就绪。
 * @apiSuccess {Number} models.capacity 可执行的总容量。
 * @apiSuccess {Number} models.available 当前可用容量。
 * @apiSuccess {String} models.reason 不可用原因标识；就绪时为空字符串。
 * @apiSuccess {Object[]} runtimes 执行器 id/version、current、mode、capabilities、ready、capacity、available、reason。
 * @apiSuccess {String} runtimes.id 模型或执行器的名称标识。
 * @apiSuccess {String} runtimes.version 版本标识。
 * @apiSuccess {String} runtimes.mode 执行器运行模式。
 * @apiSuccess {String[]} runtimes.capabilities 支持的能力标识列表。
 * @apiSuccess {Boolean} runtimes.current 是否为当前发布的版本。
 * @apiSuccess {Boolean} runtimes.ready 当前是否就绪。
 * @apiSuccess {Number} runtimes.capacity 可执行的总容量。
 * @apiSuccess {Number} runtimes.available 当前可用容量。
 * @apiSuccess {String} runtimes.reason 不可用原因标识；就绪时为空字符串。
 * @apiSuccess {Object[]} skills 技能 name/version/digest、dependencies 与 compatibleRuntimes。
 * @apiSuccess {String} skills.name 名称。
 * @apiSuccess {String} skills.version 版本标识。
 * @apiSuccess {String} skills.digest 内容的 SHA-256 摘要。
 * @apiSuccess {String[]} skills.dependencies 依赖的技能发布摘要列表。
 * @apiSuccess {Boolean} [skills.ready] 业务资源的数据源与分析配置是否通过当前准入检查；不是容量预留。
 * @apiSuccess {String} [skills.reason] 不可用原因；通过检查时为空字符串。
 * @apiSuccess {Object[]} skills.compatibleRuntimes 兼容的执行器名称与版本列表。
 * @apiSuccess {String} skills.compatibleRuntimes.id 模型或执行器的名称标识。
 * @apiSuccess {String} skills.compatibleRuntimes.version 版本标识。
 * @apiSuccess {Object[]} tools 工具 name/permission/kind、所属 skill/release、runnerType、compatibleRuntimes 及就绪状态和容量。
 * @apiSuccess {String} tools.name 名称。
 * @apiSuccess {String} tools.permission 调用工具所需权限。
 * @apiSuccess {String} tools.kind 类别标识。取值：platform、resource、executable。
 * @apiSuccess {Object[]} tools.compatibleRuntimes 兼容的执行器名称与版本列表。
 * @apiSuccess {String} tools.compatibleRuntimes.id 模型或执行器的名称标识。
 * @apiSuccess {String} tools.compatibleRuntimes.version 版本标识。
 * @apiSuccess {String} [tools.skill] 工具所属技能名。
 * @apiSuccess {String} [tools.release] 技能发布的 SHA-256 摘要。
 * @apiSuccess {String} [tools.runnerType] 执行工具需要的 Runner 类型。取值：tool、sandbox。
 * @apiSuccess {Boolean} tools.ready 当前是否就绪。
 * @apiSuccess {Number} tools.capacity 可执行的总容量。
 * @apiSuccess {Number} tools.available 当前可用容量。
 * @apiSuccess {String} tools.reason 不可用原因标识；就绪时为空字符串。
 *
 * @apiExample {http} 请求地址与请求头
 *     GET http://211.157.179.37:7010/harness/v1/task-profiles
 *     Authorization: Bearer <Cube Token>
 *
 * @apiSuccessExample {json} 使用默认模型和执行器（名称与授权内容以实际返回为准）
 *     HTTP/1.1 200 OK
 *     {
 *         "defaults": {
 *             "model": {
 *                 "id": "Baize",
 *                 "version": "1"
 *             },
 *             "runtime": {
 *                 "id": "resources",
 *                 "version": "1"
 *             }
 *         },
 *         "models": [
 *             {
 *                 "id": "Baize",
 *                 "version": "1",
 *                 "current": true,
 *                 "capabilities": [
 *                     "resources",
 *                     "resource_decisions"
 *                 ],
 *                 "nativeTools": false,
 *                 "ready": true,
 *                 "capacity": 2,
 *                 "available": 1,
 *                 "reason": ""
 *             }
 *         ],
 *         "runtimes": [
 *             {
 *                 "id": "resources",
 *                 "version": "1",
 *                 "current": true,
 *                 "mode": "resources-only",
 *                 "capabilities": [
 *                     "resources"
 *                 ],
 *                 "ready": true,
 *                 "capacity": 2,
 *                 "available": 1,
 *                 "reason": ""
 *             }
 *         ],
 *         "skills": [],
 *         "tools": []
 *     }
 */

/**
 * @api {post} /harness/v1/runs 创建任务
 * @apiName HarnessCreateRun
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 根据自然语言指令创建异步任务，可附带输入文件，并指定模型、执行器和技能。适合资料整理、内容生成及已授权工具支持的业务操作。
 *                 <p>可先查询任务能力目录，再按需指定 <code>model</code>、<code>runtime</code> 和 <code>skills</code>；省略模型和执行器时使用默认值。省略技能时允许使用当前用户获授权且与执行器兼容的技能，传空数组表示不使用技能。所选技能的用途和输入要求以技能说明为准。</p>
 *                 <p>使用附件时，先完成“创建输入文件会话 → 上传输入文件内容 → 确认输入文件就绪”，将返回的 <code>inputId</code> 填入 <code>input.attachmentIds</code>。无附件时也须传空数组。</p>
 *                 <p>返回 HTTP <code>202</code> 表示已受理。保存 <code>runId</code>，通过“查询任务”或“查询任务事件”获取进度和结果；需要补充信息或审批时按相应接口继续。超时重试使用相同的 <code>Idempotency-Key</code> 和请求体，避免重复创建。</p>
 *                 <p>同一幂等请求返回最初受理时的快照；查询当前状态请使用“查询任务”。模型、技能和执行器必须来自当前用户可用的能力目录。</p>
 * @apiUse HarnessAuth
 * @apiUse HarnessIdempotency
 * @apiUse HarnessJson
 * @apiBody {String} [model] 模型名称或名称@版本，例如 Baize、Baize@1，以任务能力目录返回的名称和版本为准。
 * @apiBody {String} [runtime] 执行器名称或名称@版本，例如 resources、trusted-python，以任务能力目录返回的名称和版本为准。
 * @apiBody {String[]} [skills] 本次可用技能名称，采用命名空间/名称形式，使用任务能力目录返回的实际值；省略为全部获授权且兼容的技能，[] 为禁用。
 * @apiBody {Object} input 用户输入。
 * @apiBody {String{1..32768}} input.instruction 任务指令。
 * @apiBody {String[]} input.attachmentIds 最多 16 个输入文件 ID；无附件也必须传 []。<br/>
 * 只能使用本用户 ready 且未到期的 Input。
 * @apiParamExample {json} 请求体
 *     {"input":{"instruction":"请总结以下记录：东区运行正常，西区温度升高，已安排复检。","attachmentIds":[]}}
 * @apiExample {curl} 创建任务（省略选择时使用服务器默认值）
 *     curl -X POST "http://211.157.179.37:7010/harness/v1/runs" -H "Authorization: Bearer <Cube Token>" -H "Content-Type: application/json" -H "Idempotency-Key: create-run-001" --data-raw '{"input":{"instruction":"请总结以下记录：东区运行正常，西区温度升高，已安排复检。","attachmentIds":[]}}'
 * @apiUse HarnessSnapshot
 * @apiSuccessExample {json} 受理快照
 *     HTTP/1.1 202 Accepted
 *     {"runId":"run-001","version":1,"eventSeq":1,"state":"queued","executionSettlement":"pending","executionProfileVersion":"v1","selection":{"model":{"id":"Baize","version":"1"},"runtime":{"id":"resources","version":"1"},"skills":[],"tools":[]}}
 * @apiError (接口错误) IDEMPOTENCY_CONFLICT HTTP 409，同键请求内容不同。
 * @apiError (接口错误) HARNESS_BUSY HTTP 429，活跃任务容量已满。
 * @apiError (接口错误) HARNESS_NOT_READY HTTP 503，任务服务暂不可用，请稍后重试。
 * @apiError (接口错误) MODEL_NOT_READY HTTP 503，所选模型暂不可用，请刷新能力目录后选择或稍后重试。
 * @apiError (接口错误) RUNTIME_NOT_READY HTTP 503，所选执行器或工具暂不可用，请稍后重试。
 * @apiError (接口错误) SCHEMA_INVALID HTTP 400，请求包含 tools 等不支持的字段。
 * @apiError (接口错误) SKILL_RUNTIME_MISMATCH HTTP 400，技能与执行器不兼容。
 *
 * @apiParamExample {json} 不使用技能：根据给定文本生成摘要
 *     {
 *         "model": "Baize@1",
 *         "runtime": "resources@1",
 *         "skills": [],
 *         "input": {
 *             "instruction": "请将以下巡检记录整理为三条摘要：东区设备运行正常，西区设备出现温度升高，已安排复检。",
 *             "attachmentIds": []
 *         }
 *     }
 *
 * @apiParamExample {json} 使用附件和技能（假设已获授权的技能名为 inspection/summary）
 *     {
 *         "runtime": "resources",
 *         "skills": [
 *             "inspection/summary"
 *         ],
 *         "input": {
 *             "instruction": "使用所选技能整理附件中的巡检资料，列出异常项和复检建议。",
 *             "attachmentIds": [
 *                 "input-001"
 *             ]
 *         }
 *     }
 */

/**
 * @api {get} /harness/v1/runs/:runId 查询任务
 * @apiName HarnessReadRun
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 查询任务当前状态、已产生的结果、待补充信息和用量。创建任务后使用返回的 <code>runId</code> 调用本接口。
 *                 <p><code>queued</code> 表示排队，<code>running</code> 表示执行中；<code>waiting_input</code> 时展示 <code>interaction.details.question</code> 并补充输入；<code>waiting_approval</code> 时由有权限的审批人处理。<code>cancelling</code>、<code>reconciling</code> 时继续查询。</p>
 *                 <p><code>succeeded</code>、<code>failed</code>、<code>cancelled</code> 和 <code>timed_out</code> 表示任务已结束。成功回答读取 <code>result.result.text</code>，生成文件读取 <code>result.result.artifacts</code>；失败时读取 <code>result.result.errorCode</code>。</p>
 *                 <p><code>usageSettlement=provisional</code> 表示用量仍可能更新，<code>settled</code> 表示已结算。取消或超时后，<code>executionSettlement</code> 可能尚未确认，可继续查询直至更新。普通用户查询本人任务，获授权的审批人可读取同域待审批任务。</p>
 * @apiUse HarnessAuth
 * @apiParam {String} runId 任务 ID。
 * @apiSuccess {Object} snapshot 当前任务快照。
 * @apiSuccess {String} snapshot.runId 任务 ID。
 * @apiSuccess {Number} snapshot.version 当前任务版本；用于 expectedVersion。
 * @apiSuccess {Number} snapshot.eventSeq 最新事件序号。
 * @apiSuccess {String} snapshot.state queued、running、waiting_input、waiting_approval、cancelling、reconciling、succeeded、failed、cancelled 或 timed_out。
 * @apiSuccess {String} snapshot.executionSettlement pending、confirmed 或 unknown。
 * @apiSuccess {String} snapshot.executionProfileVersion 本次任务采用的执行配置版本，仅供诊断关联。
 * @apiSuccess {Object} snapshot.selection 实际模型/执行器 id 与 version、技能 name/version/digest 及工具名单。
 * @apiSuccess {Object} snapshot.selection.model 模型的名称和版本。
 * @apiSuccess {String} snapshot.selection.model.id 模型或执行器的名称标识。
 * @apiSuccess {String} snapshot.selection.model.version 版本标识。
 * @apiSuccess {Object} snapshot.selection.runtime 执行器的名称和版本。
 * @apiSuccess {String} snapshot.selection.runtime.id 模型或执行器的名称标识。
 * @apiSuccess {String} snapshot.selection.runtime.version 版本标识。
 * @apiSuccess {Object[]} snapshot.selection.skills 获授权的技能版本列表。
 * @apiSuccess {String} snapshot.selection.skills.name 名称。
 * @apiSuccess {String} snapshot.selection.skills.version 版本标识。
 * @apiSuccess {String} snapshot.selection.skills.digest 内容的 SHA-256 摘要。
 * @apiSuccess {String[]} snapshot.selection.tools 可用工具列表。
 * @apiSuccess {String} usageSettlement settled 或 provisional。
 * @apiSuccess {Object} usage 用量汇总。
 * @apiSuccess {Number} usage.estimatedInput 估算输入 Token 数。
 * @apiSuccess {Number} usage.estimatedOutput 估算输出 Token 数。
 * @apiSuccess {Number} usage.actualInput 实际输入 Token 数。
 * @apiSuccess {Number} usage.actualOutput 实际输出 Token 数。
 * @apiSuccess {Number/Null} usage.pending 尚待结算的用量记录数。
 * @apiSuccess {Object/Null} result 最终结果；尚未产生时为 null。
 * @apiSuccess {String} result.state 任务结束时的状态。
 * @apiSuccess {String} result.executionSettlement 产生最终结果时的执行确认状态。
 * @apiSuccess {Object} result.result 任务结果对象。回答文本和生成文件使用下列字段；不同执行器可能返回其他业务字段。
 * @apiSuccess {String} [result.result.kind] 结果类别；文本回答为 final。
 * @apiSuccess {String} [result.result.text] kind=final 时的最终回答。
 * @apiSuccess {String} [result.result.errorCode] 失败、取消或超时时的稳定错误码。
 * @apiSuccess {Object[]} [result.result.artifacts] 启用文件服务且成功交付时返回的产物元数据；无产物时为 []。
 * @apiSuccess {String} result.result.artifacts.artifactId 产物 ID。
 * @apiSuccess {String} result.result.artifacts.runId 所属任务 ID。
 * @apiSuccess {String} result.result.artifacts.fileName 产物文件名。
 * @apiSuccess {String} result.result.artifacts.mediaType 文件媒体类型。
 * @apiSuccess {Number} result.result.artifacts.size 文件字节数。
 * @apiSuccess {String} result.result.artifacts.sha256 文件字节的 64 位小写十六进制 SHA-256。
 * @apiSuccess {Number} result.result.artifacts.createdAt 创建时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} result.result.artifacts.expiresAt 到期时间，Unix 毫秒时间戳。
 * @apiSuccess {String} result.result.artifacts.state 产物状态，如 ready、expired 或 deleted。
 * @apiSuccess {Boolean} result.result.artifacts.downloadable 是否可下载。
 * @apiSuccess {Object[]} result.usage 执行器提供的附加用量明细，可能为空数组；子字段随执行器变化。统计用量请读取顶层 usage 中的统一计数。
 * @apiSuccess {Object} [interaction] 存在待处理输入/审批时返回。
 * @apiSuccess {String} interaction.waitId 交互 ID，用于恢复或审批路径。
 * @apiSuccess {String} interaction.kind input 或 approval。
 * @apiSuccess {Number} interaction.expiresAt 交互到期时间。
 * @apiSuccess {String} interaction.argumentsHash 待审批参数摘要；审批时原样提交。
 * @apiSuccess {Object} interaction.details 待补充输入或审批的详情。
 * @apiSuccess {String} [interaction.details.configDigest] kind=approval 时本次执行配置的 SHA-256 摘要。
 * @apiSuccess {String} [interaction.details.checkpointDigest] kind=input 时上下文检查点的 SHA-256 摘要。
 * @apiSuccess {String} interaction.details.binding 本次交互的关联标识，仅供诊断，无需据此构造补充输入或审批请求。
 * @apiSuccess {String} [interaction.details.question] kind=input 时需要用户补充回答的问题。
 * @apiSuccess {String} [interaction.details.tool] kind=approval 时的待审批工具名。
 * @apiSuccess {String} [interaction.details.argumentsHash] kind=approval 时的参数摘要。
 * @apiSuccess {Object} [interaction.details.arguments] kind=approval 时的工具参数对象；字段名、类型和必填项由该工具的参数 Schema 定义，例如 {"path":"report.txt"}。
 * @apiSuccessExample {json} 排队中的任务
 *     HTTP/1.1 200 OK
 *     {
 *       "snapshot":{"runId":"run-001","version":1,"eventSeq":1,"state":"queued","executionSettlement":"pending","executionProfileVersion":"v1","selection":{"model":{"id":"Baize","version":"1"},"runtime":{"id":"resources","version":"1"},"skills":[],"tools":[]}},
 *       "usageSettlement":"settled",
 *       "usage":{"estimatedInput":0,"estimatedOutput":0,"actualInput":0,"actualOutput":0,"pending":0},
 *       "result":null
 *     }
 *
 * @apiExample {http} 请求地址与请求头
 *     GET http://211.157.179.37:7010/harness/v1/runs/run-001
 *     Authorization: Bearer <Cube Token>
 *
 * @apiSuccessExample {json} 任务完成：读取 result.result.text
 *     HTTP/1.1 200 OK
 *     {
 *         "snapshot": {
 *             "runId": "run-001",
 *             "version": 9,
 *             "eventSeq": 9,
 *             "state": "succeeded",
 *             "executionSettlement": "confirmed",
 *             "executionProfileVersion": "v1",
 *             "selection": {
 *                 "model": {
 *                     "id": "Baize",
 *                     "version": "1"
 *                 },
 *                 "runtime": {
 *                     "id": "resources",
 *                     "version": "1"
 *                 },
 *                 "skills": [],
 *                 "tools": []
 *             }
 *         },
 *         "usageSettlement": "settled",
 *         "usage": {
 *             "estimatedInput": 150,
 *             "estimatedOutput": 80,
 *             "actualInput": 132,
 *             "actualOutput": 56,
 *             "pending": 0
 *         },
 *         "result": {
 *             "state": "succeeded",
 *             "executionSettlement": "confirmed",
 *             "result": {
 *                 "kind": "final",
 *                 "text": "东区运行正常；西区设备温度升高；已安排复检。",
 *                 "artifacts": []
 *             },
 *             "usage": []
 *         }
 *     }
 *
 * @apiSuccessExample {json} 等待用户回答：使用 waitId 补充输入
 *     HTTP/1.1 200 OK
 *     {
 *         "snapshot": {
 *             "runId": "run-001",
 *             "version": 5,
 *             "eventSeq": 5,
 *             "state": "waiting_input",
 *             "executionSettlement": "pending",
 *             "executionProfileVersion": "v1",
 *             "selection": {
 *                 "model": {
 *                     "id": "Baize",
 *                     "version": "1"
 *                 },
 *                 "runtime": {
 *                     "id": "resources",
 *                     "version": "1"
 *                 },
 *                 "skills": [],
 *                 "tools": []
 *             }
 *         },
 *         "usageSettlement": "settled",
 *         "usage": {
 *             "estimatedInput": 120,
 *             "estimatedOutput": 30,
 *             "actualInput": 105,
 *             "actualOutput": 22,
 *             "pending": 0
 *         },
 *         "result": null,
 *         "interaction": {
 *             "waitId": "wait-001",
 *             "kind": "input",
 *             "expiresAt": 1792630800000,
 *             "argumentsHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
 *             "details": {
 *                 "checkpointDigest": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
 *                 "binding": "round-001",
 *                 "question": "摘要需要面向哪类读者？"
 *             }
 *         }
 *     }
 */

/**
 * @api {get} /harness/v1/runs/:runId/events 查询任务事件 / SSE
 * @apiName HarnessRunEvents
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 按事件序号查询任务进度，可用于时间线展示和断线后继续接收事件。默认返回 JSON 分页，设置 <code>Accept: text/event-stream</code> 可使用 SSE。
 *                 <p>首次请求使用 <code>after=0</code>；JSON 模式以下次返回的 <code>nextCursor</code> 继续查询。SSE 模式将最后完整收到的 <code>id</code> 作为下一次请求的 <code>Last-Event-ID</code>。事件的 <code>dataJson</code> 是 JSON 字符串，使用前需要再次解析。</p>
 *                 <p>SSE 返回一页事件后以注释 <code>: replay-page-complete</code> 结束，并非持续保持的连接。页结束或断线后可继续请求；空页按 <code>retry: 500</code> 至少等待 500 毫秒。判断任务是否结束应读取任务状态，不能只依据连接关闭。</p>
 * @apiUse HarnessAuth
 * @apiParam {String} runId 任务 ID。
 * @apiHeader {String} [Accept=application/json] 设置 text/event-stream 启用 SSE。
 * @apiHeader {String} [Last-Event-ID] 上次完整收到的 eventSeq；与 after 同时提供时必须一致。
 * @apiQuery {Number} [after=0] 非负十进制游标，最多 15 位数字；Last-Event-ID 也可作为游标。
 * @apiQuery {Number{1..100}} [limit=100] 单页事件上限；参数不允许重复，也不接受其他查询参数。
 * @apiSuccess {Object[]} events JSON 模式的事件列表。
 * @apiSuccess {String} events.runId 任务 ID。
 * @apiSuccess {Number} events.eventSeq 事件序号。
 * @apiSuccess {Number} events.runVersion 事件对应的任务版本。
 * @apiSuccess {String} events.type 原始事件类型。
 * @apiSuccess {Number} events.occurredAt 事件发生时间。
 * @apiSuccess {Number} events.schemaVersion 事件 schema 版本，目前为 1。
 * @apiSuccess {String} events.dataJson JSON 编码的事件业务数据字符串。
 * @apiSuccess {Number} nextCursor JSON 模式下一页游标；空页保持输入游标。
 * @apiSuccessExample {json} JSON 空页
 *     HTTP/1.1 200 OK
 *     {"events":[],"nextCursor":1}
 * @apiSuccessExample {text} SSE 帧（同一接口）
 *     HTTP/1.1 200 OK
 *     Content-Type: text/event-stream; charset=utf-8
 *
 *     retry: 500
 *
 *     id: 1
 *     event: run.created
 *     data: {"runId":"run-001","eventSeq":1,"runVersion":1,"type":"run.created","occurredAt":1792627200000,"schemaVersion":1,"dataJson":"{\"input\":{\"instruction\":\"请总结以下记录：东区运行正常，西区温度升高，已安排复检。\",\"attachmentIds\":[]}}"}
 *
 *     : replay-page-complete
 *
 * @apiExample {curl} 从已收到的事件 1 之后继续回放
 *     curl -N "http://211.157.179.37:7010/harness/v1/runs/run-001/events?limit=100" -H "Authorization: Bearer <Cube Token>" -H "Accept: text/event-stream" -H "Last-Event-ID: 1"
 * @apiError (接口错误) INVALID_EVENT_CURSOR HTTP 400，游标非法、指向未来或与 Last-Event-ID 不一致。
 * @apiError (接口错误) INVALID_EVENT_LIMIT HTTP 400，limit 不在 1～100。
 * @apiError (接口错误) EVENT_GAP HTTP 409，事件存在缺号；重新读取任务核对状态和事件。
 *
 * @apiExample {http} 按 JSON 分页查询事件
 *     GET http://211.157.179.37:7010/harness/v1/runs/run-001/events?after=0&limit=20
 *     Authorization: Bearer <Cube Token>
 */

/**
 * @api {post} /harness/v1/runs/:runId/cancel 取消任务
 * @apiName HarnessCancelRun
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 请求取消本人尚未结束的任务。适合用户主动停止、关闭不再需要的生成任务等场景。
 *                 <p>先查询任务，取得最新 <code>snapshot.version</code> 填入 <code>expectedVersion</code>。HTTP <code>202</code> 表示取消已受理，仍需查询任务直至进入结束状态。</p>
 *                 <p>重试同一次取消使用原幂等键。遇到 <code>VERSION_CONFLICT</code> 时先刷新任务，再使用新版本和新幂等键提交。</p>
 * @apiUse HarnessAuth
 * @apiUse HarnessIdempotency
 * @apiUse HarnessJson
 * @apiParam {String} runId 任务 ID。
 * @apiBody {Number} expectedVersion 最新 snapshot.version，非负整数。
 * @apiBody {String{..1024}} [reason] 取消原因。
 * @apiParamExample {json} 请求体
 *     {"expectedVersion":3,"reason":"用户取消"}
 * @apiUse HarnessSnapshot
 * @apiSuccessExample {json} 取消已受理
 *     HTTP/1.1 202 Accepted
 *     {"runId":"run-001","version":4,"eventSeq":4,"state":"cancelling","executionSettlement":"pending","executionProfileVersion":"v1","selection":{"model":{"id":"Baize","version":"1"},"runtime":{"id":"resources","version":"1"},"skills":[],"tools":[]}}
 * @apiError (接口错误) VERSION_CONFLICT HTTP 409，任务版本已变化。
 * @apiError (接口错误) RUN_TERMINAL HTTP 409，任务已处于终态。
 *
 * @apiExample {http} 请求地址与请求头
 *     POST http://211.157.179.37:7010/harness/v1/runs/run-001/cancel
 *     Authorization: Bearer <Cube Token>
 *     Content-Type: application/json
 *     Idempotency-Key: HarnessCancelRun-001
 *
 *     {
 *         "expectedVersion": 3,
 *         "reason": "用户取消"
 *     }
 */

/**
 * @api {post} /harness/v1/runs/:runId/resume 补充输入并恢复任务
 * @apiName HarnessResumeRun
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 为等待补充信息的任务提交回答或附件，让任务继续执行。例如补充报告格式、日期范围或缺少的资料。
 *                 <p>仅在 <code>snapshot.state=waiting_input</code> 且交互未到期时调用。先读取任务中的问题、<code>snapshot.version</code>、<code>interaction.waitId</code> 和 <code>expiresAt</code>，再提交回答。</p>
 *                 <p>附件须为本人已就绪且未到期的输入文件；无附件也要传空数组。返回 <code>202</code> 后继续查询任务。交互到期时应根据业务需要重新创建任务。</p>
 * @apiUse HarnessAuth
 * @apiUse HarnessIdempotency
 * @apiUse HarnessJson
 * @apiParam {String} runId 任务 ID。
 * @apiBody {Number} expectedVersion 最新 snapshot.version，非负整数。
 * @apiBody {String} waitId 当前 interaction.waitId。
 * @apiBody {Object} input 补充的输入。
 * @apiBody {String{1..32768}} input.instruction 补充指令。
 * @apiBody {String[]} input.attachmentIds 最多 16 个输入文件 ID；无附件必须传 []。
 * @apiParamExample {json} 请求体
 *     {"expectedVersion":5,"waitId":"wait-001","input":{"instruction":"请继续整理为摘要","attachmentIds":[]}}
 * @apiUse HarnessSnapshot
 * @apiError (接口错误) VERSION_CONFLICT HTTP 409，版本已变化。
 * @apiError (接口错误) WAIT_STATE_CONFLICT HTTP 409，交互状态不允许恢复。
 * @apiError (接口错误) WAIT_EXPIRED HTTP 410，交互已到期。
 *
 * @apiSuccessExample {json} 补充信息已受理：继续查询任务
 *     HTTP/1.1 202 Accepted
 *     {
 *         "runId": "run-001",
 *         "version": 6,
 *         "eventSeq": 6,
 *         "state": "running",
 *         "executionSettlement": "pending",
 *         "executionProfileVersion": "v1",
 *         "selection": {
 *             "model": {
 *                 "id": "Baize",
 *                 "version": "1"
 *             },
 *             "runtime": {
 *                 "id": "resources",
 *                 "version": "1"
 *             },
 *             "skills": [],
 *             "tools": []
 *         }
 *     }
 *
 * @apiExample {http} 请求地址与请求头
 *     POST http://211.157.179.37:7010/harness/v1/runs/run-001/resume
 *     Authorization: Bearer <Cube Token>
 *     Content-Type: application/json
 *     Idempotency-Key: HarnessResumeRun-001
 *
 *     {
 *         "expectedVersion": 5,
 *         "waitId": "wait-001",
 *         "input": {
 *             "instruction": "面向设备维护人员，请突出异常项和复检建议。",
 *             "attachmentIds": []
 *         }
 *     }
 */

/**
 * @api {post} /harness/v1/runs/:runId/approvals/:approvalId/decision 提交审批决定
 * @apiName HarnessDecideApproval
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 对等待审批的任务同意或拒绝待执行操作。调用者须具有审批权限，与任务属于同一域，且不能审批自己的任务。
 *                 <p>先查询任务，向审批人展示 <code>interaction.details.tool</code> 和 <code>interaction.details.arguments</code>；路径中的 <code>approvalId</code> 使用 <code>interaction.waitId</code>。提交时使用最新版本，并原样携带 <code>interaction.argumentsHash</code>，确保审批对象与已查看的内容一致。</p>
 *                 <p><code>approve</code> 使任务继续执行，<code>reject</code> 使任务进入取消流程。返回 <code>202</code> 后继续查询任务；版本或参数已变化时刷新内容后重新审批。</p>
 * @apiUse HarnessAuth
 * @apiUse HarnessIdempotency
 * @apiUse HarnessJson
 * @apiParam {String} runId 任务 ID。
 * @apiParam {String} approvalId 当前 interaction.waitId。
 * @apiBody {Number} expectedVersion 最新 snapshot.version，非负整数。
 * @apiBody {String="approve","reject"} decision 审批决定。
 * @apiBody {String} argumentsHash 当前待审批参数的 64 位小写 SHA-256。
 * @apiBody {String{..1024}} [reason] 审批原因。
 * @apiParamExample {json} 请求体（摘要以查询结果为准）
 *     {"expectedVersion":7,"decision":"approve","argumentsHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","reason":"已核对待执行内容"}
 * @apiUse HarnessSnapshot
 * @apiError (接口错误) APPROVER_FORBIDDEN HTTP 403，审批人无权限或试图审批自己的任务。
 * @apiError (接口错误) APPROVAL_ARGUMENTS_CHANGED HTTP 409，参数摘要不一致。
 * @apiError (接口错误) VERSION_CONFLICT HTTP 409，版本已变化。
 * @apiError (接口错误) WAIT_STATE_CONFLICT HTTP 409，任务或交互不处于待审批状态。
 * @apiError (接口错误) WAIT_EXPIRED HTTP 410，交互已到期。
 *
 * @apiParamExample {json} 拒绝操作并说明原因
 *     {
 *         "expectedVersion": 7,
 *         "decision": "reject",
 *         "argumentsHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
 *         "reason": "请先核对目标文件后重新发起任务。"
 *     }
 *
 * @apiSuccessExample {json} 同意后继续执行
 *     HTTP/1.1 202 Accepted
 *     {
 *         "runId": "run-001",
 *         "version": 8,
 *         "eventSeq": 8,
 *         "state": "running",
 *         "executionSettlement": "pending",
 *         "executionProfileVersion": "v1",
 *         "selection": {
 *             "model": {
 *                 "id": "Baize",
 *                 "version": "1"
 *             },
 *             "runtime": {
 *                 "id": "resources",
 *                 "version": "1"
 *             },
 *             "skills": [],
 *             "tools": []
 *         }
 *     }
 *
 * @apiExample {http} 请求地址与请求头
 *     POST http://211.157.179.37:7010/harness/v1/runs/run-001/approvals/wait-001/decision
 *     Authorization: Bearer <Cube Token>
 *     Content-Type: application/json
 *     Idempotency-Key: HarnessDecideApproval-001
 *
 *     {
 *         "expectedVersion": 7,
 *         "decision": "approve",
 *         "argumentsHash": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
 *         "reason": "已核对待执行内容"
 *     }
 */

/**
 * @api {post} /harness/v1/runs/:runId/feedback 提交任务反馈
 * @apiName HarnessFeedback
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 为本人任务提交正面或负面评价及原因，可用于收集答案质量、文件内容或业务结果的使用反馈。
 *                 <p>反馈能力须由服务提供方开通；返回 <code>accepted=true</code> 表示已接收。重试同一次反馈时保持幂等键和内容不变，以取得相同的 <code>feedbackId</code>。</p>
 * @apiUse HarnessAuth
 * @apiUse HarnessIdempotency
 * @apiUse HarnessJson
 * @apiParam {String} runId 任务 ID。
 * @apiBody {String="positive","negative"} rating 反馈评价。
 * @apiBody {String{..4096}} [reason] 反馈原因。
 * @apiParamExample {json} 请求体
 *     {"rating":"positive","reason":"结果符合预期"}
 * @apiSuccess (202) {String} feedbackId 反馈 ID，64 位小写十六进制摘要。
 * @apiSuccess (202) {Boolean} accepted 固定为 true。
 * @apiSuccessExample {json} 反馈已受理
 *     HTTP/1.1 202 Accepted
 *     {"feedbackId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","accepted":true}
 * @apiError (接口错误) FEEDBACK_DISABLED HTTP 400，反馈入口未开启。
 * @apiError (接口错误) IDEMPOTENCY_CONFLICT HTTP 409，同键请求内容不同。
 *
 * @apiParamExample {json} 负面反馈：指出需要改进的内容
 *     {
 *         "rating": "negative",
 *         "reason": "摘要遗漏了西区设备的温度异常，请在后续结果中保留异常项。"
 *     }
 *
 * @apiExample {http} 请求地址与请求头
 *     POST http://211.157.179.37:7010/harness/v1/runs/run-001/feedback
 *     Authorization: Bearer <Cube Token>
 *     Content-Type: application/json
 *     Idempotency-Key: HarnessFeedback-001
 *
 *     {
 *         "rating": "positive",
 *         "reason": "结果符合预期"
 *     }
 */

/**
 * @api {post} /harness/v1/inputs 创建输入文件会话
 * @apiName HarnessCreateInput
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 为任务附件创建上传会话。先声明文件名、媒体类型、字节数和 SHA-256，再上传内容并确认就绪。
 *                 <p>支持 <code>text/plain</code>、<code>text/csv</code>、<code>application/json</code> 和 <code>application/octet-stream</code>。默认单文件上限为 10 MiB，具体额度由服务提供方确定，最大支持 64 MiB。文本文件使用 UTF-8；JSON 文件的顶层必须为对象，不能含重复键。</p>
 *                 <p>HTTP <code>201</code> 返回 <code>inputId</code>，此时状态为 <code>uploading</code>，还不能用于任务。依次调用“上传输入文件内容”和“确认输入文件就绪”，再将该 ID 填入任务的 <code>input.attachmentIds</code>。SHA-256 和大小均按实际上传的文件字节计算，注意换行和编码造成的差异。</p>
 * @apiUse HarnessAuth
 * @apiUse HarnessIdempotency
 * @apiUse HarnessJson
 * @apiBody {String{1..256}} fileName 文件名。
 * @apiBody {String} mediaType 文件的媒体类型，取上述支持类型之一。
 * @apiBody {Number} size 文件字节数，非负整数。
 * @apiBody {String} sha256 64 位小写十六进制 SHA-256。
 * @apiParamExample {json} 请求体（UTF-8 字节 hello）
 *     {"fileName":"hello.txt","mediaType":"text/plain","size":5,"sha256":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"}
 * @apiSuccess (201) {String} inputId 输入文件 ID；供 input.attachmentIds 引用。
 * @apiSuccess (201) {String} [runId] 已绑定时返回的任务 ID。
 * @apiSuccess (201) {String} fileName 文件名。
 * @apiSuccess (201) {String} mediaType 声明的文件媒体类型。
 * @apiSuccess (201) {Number} size 文件字节数。
 * @apiSuccess (201) {String} sha256 文件字节的 SHA-256，64 位小写十六进制。
 * @apiSuccess (201) {Number} createdAt 创建时间。
 * @apiSuccess (201) {Number} expiresAt 到期时间。
 * @apiSuccess (201) {String} state uploading、ready、expired、delete_pending 或 deleted；仅 ready 且未到期可绑定任务。
 * @apiSuccess (201) {Boolean} downloadable 输入文件为 false。
 * @apiSuccessExample {json} 输入会话已创建
 *     HTTP/1.1 201 Created
 *     {"inputId":"input-001","fileName":"hello.txt","mediaType":"text/plain","size":5,"sha256":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824","createdAt":1792627200000,"expiresAt":1792630800000,"state":"uploading","downloadable":false}
 *
 * @apiExample {http} 请求地址与请求头
 *     POST http://211.157.179.37:7010/harness/v1/inputs
 *     Authorization: Bearer <Cube Token>
 *     Content-Type: application/json
 *     Idempotency-Key: HarnessCreateInput-001
 *
 *     {
 *         "fileName": "hello.txt",
 *         "mediaType": "text/plain",
 *         "size": 5,
 *         "sha256": "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
 *     }
 */

/**
 * @api {get} /harness/v1/inputs/:inputId 查询输入文件
 * @apiName HarnessReadInput
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 查询本人已创建输入文件的名称、大小、摘要、状态和到期时间。
 *                 <p>创建或恢复任务前，确认 <code>state=ready</code> 且 <code>expiresAt</code> 尚未到期。状态为 <code>uploading</code> 时应先完成上传和确认；文件到期后需重新创建上传会话。</p>
 * @apiUse HarnessAuth
 * @apiParam {String} inputId 输入文件 ID。
 * @apiUse HarnessInputMetadata
 *
 * @apiExample {http} 请求地址与请求头
 *     GET http://211.157.179.37:7010/harness/v1/inputs/input-001
 *     Authorization: Bearer <Cube Token>
 *
 * @apiSuccessExample {json} 输入文件可用于任务
 *     HTTP/1.1 200 OK
 *     {
 *         "inputId": "input-001",
 *         "fileName": "hello.txt",
 *         "mediaType": "text/plain",
 *         "size": 5,
 *         "sha256": "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
 *         "createdAt": 1792627200000,
 *         "expiresAt": 1792713600000,
 *         "state": "ready",
 *         "downloadable": false
 *     }
 */

/**
 * @api {put} /harness/v1/inputs/:inputId/content 上传输入文件内容
 * @apiName HarnessUploadInput
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 将完整文件上传到已创建的输入会话。请求体直接使用文件二进制数据，<code>Content-Type</code> 为 <code>application/octet-stream</code>，文件自身媒体类型沿用创建会话时的声明。
 *                 <p>上传字节数和 SHA-256 必须与声明一致。成功返回 HTTP <code>200</code> 后，仍须调用“确认输入文件就绪”才能在任务中引用。上传失败时重新发送整个文件；本接口不支持分片上传或 multipart 表单。</p>
 * @apiUse HarnessAuth
 * @apiUse HarnessIdempotency
 * @apiParam {String} inputId 输入文件 ID。
 * @apiHeader {String} Content-Type application/octet-stream。
 * @apiBody {Binary} content 整个 HTTP 请求体为文件字节，不使用 JSON 包装。
 * @apiExample {curl} 上传 hello.txt（内容必须与创建会话时的 size/sha256 相符）
 *     curl -X PUT "http://211.157.179.37:7010/harness/v1/inputs/input-001/content" -H "Authorization: Bearer <Cube Token>" -H "Idempotency-Key: upload-input-001" -H "Content-Type: application/octet-stream" --data-binary @hello.txt
 * @apiUse HarnessInputMetadata
 * @apiError (接口错误) FILE_EXPIRED HTTP 410，文件会话已到期。
 * @apiError (接口错误) UNSUPPORTED_MEDIA_TYPE HTTP 415，上传请求的 Content-Type 错误。
 *
 * @apiSuccessExample {json} 上传完成：下一步调用 complete
 *     HTTP/1.1 200 OK
 *     {
 *         "inputId": "input-001",
 *         "fileName": "hello.txt",
 *         "mediaType": "text/plain",
 *         "size": 5,
 *         "sha256": "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
 *         "createdAt": 1792627200000,
 *         "expiresAt": 1792630800000,
 *         "state": "uploading",
 *         "downloadable": false
 *     }
 */

/**
 * @api {post} /harness/v1/inputs/:inputId/complete 确认输入文件就绪
 * @apiName HarnessCompleteInput
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 确认附件已完整上传，并将文件状态更新为 <code>ready</code>，供创建任务或补充输入时引用。
 *                 <p>提交创建会话时声明的 SHA-256。大小、摘要、类型或内容不匹配时返回错误，应核对原始文件并重新上传。重复确认不会延长有效期，后续使用前仍应检查 <code>expiresAt</code>。</p>
 * @apiUse HarnessAuth
 * @apiUse HarnessIdempotency
 * @apiUse HarnessJson
 * @apiParam {String} inputId 输入文件 ID。
 * @apiBody {String} sha256 文件的 64 位小写十六进制 SHA-256，与创建和上传内容一致。
 * @apiParamExample {json} 请求体
 *     {"sha256":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"}
 * @apiUse HarnessInputMetadata
 * @apiSuccessExample {json} 输入文件就绪
 *     HTTP/1.1 200 OK
 *     {"inputId":"input-001","fileName":"hello.txt","mediaType":"text/plain","size":5,"sha256":"2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824","createdAt":1792627200000,"expiresAt":1792713600000,"state":"ready","downloadable":false}
 * @apiError (接口错误) FILE_UPLOAD_INCOMPLETE HTTP 400，文件缺失或大小/摘要不一致。
 * @apiError (接口错误) FILE_EXPIRED HTTP 410，文件已到期或状态不允许完成。
 *
 * @apiExample {http} 请求地址与请求头
 *     POST http://211.157.179.37:7010/harness/v1/inputs/input-001/complete
 *     Authorization: Bearer <Cube Token>
 *     Content-Type: application/json
 *     Idempotency-Key: HarnessCompleteInput-001
 *
 *     {
 *         "sha256": "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
 *     }
 */

/**
 * @api {get} /harness/v1/runs/:runId/artifacts 查询任务产物
 * @apiName HarnessArtifacts
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 查询任务已生成的文件列表，例如摘要、报告或其他可交付文件。任务尚未生成文件时返回空数组。
 *                 <p>展示 <code>fileName</code>、<code>mediaType</code> 和 <code>size</code>；<code>state=ready</code>、<code>downloadable=true</code> 且未到期时，可使用 <code>artifactId</code> 下载内容。</p>
 * @apiUse HarnessAuth
 * @apiParam {String} runId 任务 ID。
 * @apiSuccess {Object[]} artifacts 产物列表；每项结构同“查询产物元数据”。
 * @apiSuccess {String} artifacts.artifactId 产物 ID。
 * @apiSuccess {String} artifacts.runId 所属任务 ID。
 * @apiSuccess {String} artifacts.fileName 文件名。
 * @apiSuccess {String} artifacts.mediaType 文件类型。
 * @apiSuccess {Number} artifacts.size 文件字节数。
 * @apiSuccess {String} artifacts.sha256 文件 SHA-256。
 * @apiSuccess {Number} artifacts.createdAt 创建时间。
 * @apiSuccess {Number} artifacts.expiresAt 到期时间。
 * @apiSuccess {String} artifacts.state 产物状态。
 * @apiSuccess {Boolean} artifacts.downloadable 是否可下载。
 * @apiSuccess {Object} [artifacts.origin] 服务器从 Provider 调用账本推导的来源；结构同产物元数据。
 * @apiSuccessExample {json} 暂无产物
 *     HTTP/1.1 200 OK
 *     {"artifacts":[]}
 *
 * @apiExample {http} 请求地址与请求头
 *     GET http://211.157.179.37:7010/harness/v1/runs/run-001/artifacts
 *     Authorization: Bearer <Cube Token>
 *
 * @apiSuccessExample {json} 存在可下载的任务文件
 *     HTTP/1.1 200 OK
 *     {
 *         "artifacts": [
 *             {
 *                 "artifactId": "artifact-001",
 *                 "runId": "run-001",
 *                 "fileName": "summary.txt",
 *                 "mediaType": "text/plain",
 *                 "size": 5,
 *                 "sha256": "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
 *                 "createdAt": 1792627260000,
 *                 "expiresAt": 1792713660000,
 *                 "state": "ready",
 *                 "downloadable": true
 *             }
 *         ]
 *     }
 */

/**
 * @api {get} /harness/v1/artifacts/:artifactId 查询产物元数据
 * @apiName HarnessReadArtifact
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 读取单个任务产物的文件名、媒体类型、大小、SHA-256 和有效期，可用于下载前展示文件信息或下载后校验完整性。
 *                 <p>本接口仅返回元数据。确认文件就绪、可下载且未到期后，再调用“下载产物内容”。</p>
 * @apiUse HarnessAuth
 * @apiParam {String} artifactId 产物 ID。
 * @apiSuccess {String} artifactId 产物 ID。
 * @apiSuccess {String} runId 所属任务 ID。
 * @apiSuccess {String} fileName 文件名。
 * @apiSuccess {String} mediaType 文件类型。
 * @apiSuccess {Number} size 文件字节数。
 * @apiSuccess {String} sha256 文件字节的 64 位小写十六进制 SHA-256。
 * @apiSuccess {Number} createdAt 创建时间。
 * @apiSuccess {Number} expiresAt 到期时间。
 * @apiSuccess {String} state uploading、ready、expired、delete_pending 或 deleted。
 * @apiSuccess {Boolean} downloadable 为 true 时可下载。
 * @apiSuccess {Object} [origin] 仅 Provider 产物具有，模型写同名文件不会生成此字段。
 * @apiSuccess {String} origin.kind provider。
 * @apiSuccess {String} origin.operation 实际 Provider operation，例如 common.capability.invoke.v1。
 * @apiSuccess {String} origin.jobId 已关联的工具作业 ID。
 * @apiSuccess {Boolean} origin.confirmed 调用已确认成功；展示媒体来源时必须为 true，且 run 已 succeeded。
 *
 * @apiExample {http} 请求地址与请求头
 *     GET http://211.157.179.37:7010/harness/v1/artifacts/artifact-001
 *     Authorization: Bearer <Cube Token>
 *
 * @apiSuccessExample {json} 文件元数据：下载后可据摘要校验
 *     HTTP/1.1 200 OK
 *     {
 *         "artifactId": "artifact-001",
 *         "runId": "run-001",
 *         "fileName": "summary.txt",
 *         "mediaType": "text/plain",
 *         "size": 5,
 *         "sha256": "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
 *         "createdAt": 1792627260000,
 *         "expiresAt": 1792713660000,
 *         "state": "ready",
 *         "downloadable": true
 *     }
 */

/**
 * @api {get} /harness/v1/artifacts/:artifactId/content 下载产物内容
 * @apiName HarnessDownloadArtifact
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 下载本人任务产物的完整文件内容。成功响应为二进制文件，可按 <code>Content-Disposition</code> 中的文件名保存。
 *                 <p>使用 Cube 用户令牌直接访问即可；也可以使用“获取产物下载句柄”返回的地址，并携带同一用户的令牌。客户端应检查状态码、文件长度及元数据中的 SHA-256，传输中断时重新下载完整文件。本接口不支持 Range 断点续传。</p>
 * @apiUse HarnessAuth
 * @apiParam {String} artifactId 产物 ID。
 * @apiQuery {String} [handle] download-handle 返回的短期签名；直接鉴权下载可省略。
 * @apiSuccess {Binary} content 文件原始字节，不是 JSON。
 * @apiSuccessExample {text} 文件响应示例
 *     HTTP/1.1 200 OK
 *     Content-Type: text/plain
 *     Content-Length: 5
 *     Content-Disposition: attachment; filename*=UTF-8''hello.txt
 *
 *     hello
 * @apiExample {curl} 下载到本地
 *     curl --fail "http://211.157.179.37:7010/harness/v1/artifacts/artifact-001/content" -H "Authorization: Bearer <Cube Token>" -o artifact.txt
 * @apiError (接口错误) FILE_EXPIRED HTTP 410，产物已到期。
 */

/**
 * @api {get} /harness/v1/artifacts/:artifactId/download-handle 获取产物下载句柄
 * @apiName HarnessDownloadHandle
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 获取产物的短期下载地址，便于应用在有效期内发起文件下载。
 *                 <p>返回的 <code>url</code> 是相对路径，拼接到业务基地址 <code>http://211.157.179.37:7010</code> 后使用，并携带原用户的 <code>Authorization</code> 请求头。地址到期后可重新获取；文件本身到期后无法通过新句柄延长保留时间。</p>
 * @apiUse HarnessAuth
 * @apiParam {String} artifactId 产物 ID。
 * @apiSuccess {String} url 相对 URL，形如 /harness/v1/artifacts/{artifactId}/content?handle=...。
 * @apiSuccess {Number} expiresAt 句柄到期时间。
 * @apiSuccess {Boolean} requiresAuthentication 固定为 true。
 *
 * @apiExample {http} 请求地址与请求头
 *     GET http://211.157.179.37:7010/harness/v1/artifacts/artifact-001/download-handle
 *     Authorization: Bearer <Cube Token>
 *
 * @apiSuccessExample {json} 短期地址仍需用户令牌
 *     HTTP/1.1 200 OK
 *     {
 *         "url": "/harness/v1/artifacts/artifact-001/content?handle=<服务返回的签名>",
 *         "expiresAt": 1792627560000,
 *         "requiresAuthentication": true
 *     }
 */

/**
 * @api {get} /harness/v1/runs/:runId/trace 导出任务诊断轨迹
 * @apiName HarnessTrace
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 导出任务的脱敏诊断信息，供具有诊断权限的开发者排查任务失败、取消及用量异常。该能力须由服务提供方开通，且仅能导出同域任务。
 *                 <p>返回内容包括状态、事件、模型及工具操作记录和用量。业务内容使用摘要脱敏，不包含可直接读取的原始提示词、工具参数或回答；业务结果请使用“查询任务”接口。</p>
 *                 <p><code>replayComplete</code> 表示诊断记录是否足以核对状态变化，不表示能够恢复原始业务内容。联系服务提供方时可提供 <code>runId</code>、<code>digest</code> 及相关错误码。</p>
 * @apiUse HarnessAuth
 * @apiParam {String} runId 任务 ID。
 * @apiSuccess {Number} schemaVersion 当前为 1。
 * @apiSuccess {String} kind 固定为 harness-redacted-state-trace。
 * @apiSuccess {String} runId 任务 ID。
 * @apiSuccess {String} redaction 固定为 all-content-strings-sha256。
 * @apiSuccess {Boolean} replayComplete 状态回放证据是否完整。
 * @apiSuccess {Object} snapshot 任务快照，另含 usageSettlement。
 * @apiSuccess {String} snapshot.runId 任务 ID。
 * @apiSuccess {Number} snapshot.version 版本标识。
 * @apiSuccess {Number} snapshot.eventSeq 最新事件序号。
 * @apiSuccess {String} snapshot.state 任务状态。取值：queued、running、waiting_input、waiting_approval、cancelling、reconciling、succeeded、failed、cancelled、timed_out。
 * @apiSuccess {String} snapshot.executionSettlement 执行确认状态。取值：pending、confirmed、unknown。
 * @apiSuccess {String} snapshot.executionProfileVersion 本次任务采用的执行配置版本。
 * @apiSuccess {Object} [snapshot.selection] 本次任务实际选择的模型、执行器、技能和工具。
 * @apiSuccess {Object} snapshot.selection.model 模型的名称和版本。
 * @apiSuccess {String} snapshot.selection.model.id 模型或执行器的名称标识。
 * @apiSuccess {String} snapshot.selection.model.version 版本标识。
 * @apiSuccess {Object} snapshot.selection.runtime 执行器的名称和版本。
 * @apiSuccess {String} snapshot.selection.runtime.id 模型或执行器的名称标识。
 * @apiSuccess {String} snapshot.selection.runtime.version 版本标识。
 * @apiSuccess {Object[]} snapshot.selection.skills 获授权的技能版本列表。
 * @apiSuccess {String} snapshot.selection.skills.name 名称。
 * @apiSuccess {String} snapshot.selection.skills.version 版本标识。
 * @apiSuccess {String} snapshot.selection.skills.digest 内容的 SHA-256 摘要。
 * @apiSuccess {String[]} snapshot.selection.tools 可用工具列表。
 * @apiSuccess {String} snapshot.usageSettlement 用量结算状态。取值：provisional、settled。
 * @apiSuccess {Object/Null} config 任务所用配置的引用及摘要。
 * @apiSuccess {String} config.digest 整个任务所用配置的 SHA-256 摘要。
 * @apiSuccess {Object} config.execution 本次任务采用的执行配置引用，仅供诊断关联。
 * @apiSuccess {String} config.execution.id 任务所用配置的名称。
 * @apiSuccess {String} config.execution.version 任务所用配置的版本。
 * @apiSuccess {String} config.execution.digest 配置内容的 SHA-256 摘要。
 * @apiSuccess {Object} config.model 本次任务采用的模型配置引用，仅供诊断关联。
 * @apiSuccess {String} config.model.id 任务所用配置的名称。
 * @apiSuccess {String} config.model.version 任务所用配置的版本。
 * @apiSuccess {String} config.model.digest 配置内容的 SHA-256 摘要。
 * @apiSuccess {Object} config.runtime 本次任务采用的执行器配置引用，仅供诊断关联。
 * @apiSuccess {String} config.runtime.id 任务所用配置的名称。
 * @apiSuccess {String} config.runtime.version 任务所用配置的版本。
 * @apiSuccess {String} config.runtime.digest 配置内容的 SHA-256 摘要。
 * @apiSuccess {Object} config.tenantPolicy 本次任务采用的授权策略引用，仅供诊断关联。
 * @apiSuccess {String} config.tenantPolicy.id 任务所用配置的名称。
 * @apiSuccess {String} config.tenantPolicy.version 任务所用配置的版本。
 * @apiSuccess {String} config.tenantPolicy.digest 配置内容的 SHA-256 摘要。
 * @apiSuccess {Object[]} events 状态事件及投影。
 * @apiSuccess {Number} events.event_seq 事件序号，按此值递增。
 * @apiSuccess {Number} events.run_version 事件对应的任务版本号。
 * @apiSuccess {String} events.event_type 原始事件类型。
 * @apiSuccess {Number} events.occurred_at 事件发生时间，Unix 毫秒时间戳。
 * @apiSuccess {Object} [events.projection] 事件提交时的任务状态投影；旧记录缺少投影时可省略。
 * @apiSuccess {String} events.projection.runId 任务 ID。
 * @apiSuccess {Number} events.projection.version 版本标识。
 * @apiSuccess {Number} events.projection.eventSeq 最新事件序号。
 * @apiSuccess {String} events.projection.state 任务状态。取值：queued、running、waiting_input、waiting_approval、cancelling、reconciling、succeeded、failed、cancelled、timed_out。
 * @apiSuccess {String} events.projection.executionSettlement 执行确认状态。取值：pending、confirmed、unknown。
 * @apiSuccess {String} events.projection.executionProfileVersion 本次任务采用的执行配置版本。
 * @apiSuccess {Object} [events.projection.selection] 本次任务实际选择的模型、执行器、技能和工具。
 * @apiSuccess {Object} events.projection.selection.model 模型的名称和版本。
 * @apiSuccess {String} events.projection.selection.model.id 模型或执行器的名称标识。
 * @apiSuccess {String} events.projection.selection.model.version 版本标识。
 * @apiSuccess {Object} events.projection.selection.runtime 执行器的名称和版本。
 * @apiSuccess {String} events.projection.selection.runtime.id 模型或执行器的名称标识。
 * @apiSuccess {String} events.projection.selection.runtime.version 版本标识。
 * @apiSuccess {Object[]} events.projection.selection.skills 获授权的技能版本列表。
 * @apiSuccess {String} events.projection.selection.skills.name 名称。
 * @apiSuccess {String} events.projection.selection.skills.version 版本标识。
 * @apiSuccess {String} events.projection.selection.skills.digest 内容的 SHA-256 摘要。
 * @apiSuccess {String[]} events.projection.selection.tools 可用工具列表。
 * @apiSuccess {String} events.projection.usageSettlement 用量结算状态。取值：provisional、settled。
 * @apiSuccess {Object[]} rounds 模型轮次。
 * @apiSuccess {String} rounds.intent_id 模型轮次或工具调用的意图 ID。
 * @apiSuccess {Number} rounds.attempt 该意图或作业的尝试序号。
 * @apiSuccess {String} rounds.intent_hash 原始意图的 SHA-256 摘要。
 * @apiSuccess {Number} rounds.origin_fence 操作诊断序号，仅供诊断关联，调用者无需计算。
 * @apiSuccess {String} rounds.status 操作记录的状态标识。
 * @apiSuccess {Object} rounds.payload_json 脱敏后的任意 JSON 对象；键替换为 keySha256:<摘要>，字符串值替换为 {"redactedSha256":"<摘要>"}，数字、布尔、null 及数组形状保留。无固定业务子字段。
 * @apiSuccess {Object/Null} rounds.result_json 脱敏结果对象；规则同 payload_json，无固定业务子字段。尚无结果时为 null。
 * @apiSuccess {String/Null} rounds.result_hash 结果内容摘要；尚无结果时为 null。
 * @apiSuccess {String} rounds.payload_json_sha256 脱敏前请求对象的 SHA-256 摘要。
 * @apiSuccess {String} [rounds.result_json_sha256] 脱敏前结果对象的 SHA-256 摘要。
 * @apiSuccess {String} [rounds.argumentsHash] 规范化工具参数的 SHA-256 摘要。
 * @apiSuccess {String} [rounds.tool] 工具名。
 * @apiSuccess {Object[]} calls 工具调用。
 * @apiSuccess {String} calls.intent_id 模型轮次或工具调用的意图 ID。
 * @apiSuccess {Number} calls.attempt 该意图或作业的尝试序号。
 * @apiSuccess {String} calls.intent_hash 原始意图的 SHA-256 摘要。
 * @apiSuccess {Number} calls.origin_fence 操作诊断序号，仅供诊断关联，调用者无需计算。
 * @apiSuccess {String} calls.status 操作记录的状态标识。
 * @apiSuccess {Object} calls.payload_json 脱敏后的任意 JSON 对象；键替换为 keySha256:<摘要>，字符串值替换为 {"redactedSha256":"<摘要>"}，数字、布尔、null 及数组形状保留。无固定业务子字段。
 * @apiSuccess {Object/Null} calls.result_json 脱敏结果对象；规则同 payload_json，无固定业务子字段。尚无结果时为 null。
 * @apiSuccess {String/Null} calls.result_hash 结果内容摘要；尚无结果时为 null。
 * @apiSuccess {String} calls.payload_json_sha256 脱敏前请求对象的 SHA-256 摘要。
 * @apiSuccess {String} [calls.result_json_sha256] 脱敏前结果对象的 SHA-256 摘要。
 * @apiSuccess {String} [calls.argumentsHash] 规范化工具参数的 SHA-256 摘要。
 * @apiSuccess {String} [calls.tool] 工具名。
 * @apiSuccess {Object[]} usage 用量记录。
 * @apiSuccess {String} usage.round_id 模型轮次 ID。
 * @apiSuccess {Number} usage.attempt 该意图或作业的尝试序号。
 * @apiSuccess {Number} usage.estimated_input 估算输入词元数。
 * @apiSuccess {Number} usage.estimated_output 估算输出词元数。
 * @apiSuccess {Number/Null} usage.actual_input 实际输入词元数；尚未结算时为 null。
 * @apiSuccess {Number/Null} usage.actual_output 实际输出词元数；尚未结算时为 null。
 * @apiSuccess {Number} usage.revision 用量记录修订号。
 * @apiSuccess {Object[]} modelJobs 模型作业记录。
 * @apiSuccess {String} modelJobs.job_id 作业 ID。
 * @apiSuccess {Number} modelJobs.attempt 该意图或作业的尝试序号。
 * @apiSuccess {String/Null} modelJobs.worker_id 执行该作业的 workerId；尚未分配时为 null。
 * @apiSuccess {String} modelJobs.status 操作记录的状态标识。
 * @apiSuccess {Number} modelJobs.actual_finished 实际执行是否已结束：1 已结束，0 尚未确认。
 * @apiSuccess {Number} modelJobs.cancel_requested 是否已请求取消：1 是，0 否。
 * @apiSuccess {Number} modelJobs.created_at 记录创建时间，Unix 毫秒时间戳。
 * @apiSuccess {Number/Null} modelJobs.dispatched_at 作业派发时间，Unix 毫秒时间戳；未派发时为 null。
 * @apiSuccess {Number/Null} modelJobs.finished_at 实际执行完成时间，Unix 毫秒时间戳；未完成时为 null。
 * @apiSuccess {Number/Null} modelJobs.cancelled_at 取消时间，Unix 毫秒时间戳；未取消时为 null。
 * @apiSuccess {String} modelJobs.round_id 模型轮次 ID。
 * @apiSuccess {String} modelJobs.model_id 模型 ID。
 * @apiSuccess {String} modelJobs.profile_digest 模型配置摘要。
 * @apiSuccess {String} modelJobs.backend_id 推理服务标识。
 * @apiSuccess {Object[]} toolJobs 工具作业记录。
 * @apiSuccess {String} toolJobs.job_id 作业 ID。
 * @apiSuccess {Number} toolJobs.attempt 该意图或作业的尝试序号。
 * @apiSuccess {String/Null} toolJobs.worker_id 执行该作业的 workerId；尚未分配时为 null。
 * @apiSuccess {String} toolJobs.status 操作记录的状态标识。
 * @apiSuccess {Number} toolJobs.actual_finished 实际执行是否已结束：1 已结束，0 尚未确认。
 * @apiSuccess {Number} toolJobs.cancel_requested 是否已请求取消：1 是，0 否。
 * @apiSuccess {Number} toolJobs.created_at 记录创建时间，Unix 毫秒时间戳。
 * @apiSuccess {Number/Null} toolJobs.dispatched_at 作业派发时间，Unix 毫秒时间戳；未派发时为 null。
 * @apiSuccess {Number/Null} toolJobs.finished_at 实际执行完成时间，Unix 毫秒时间戳；未完成时为 null。
 * @apiSuccess {Number/Null} toolJobs.cancelled_at 取消时间，Unix 毫秒时间戳；未取消时为 null。
 * @apiSuccess {String} toolJobs.call_id 工具调用 ID。
 * @apiSuccess {String} toolJobs.release_digest 工具所属技能发布摘要。
 * @apiSuccess {String} toolJobs.tool_name 工具名。
 * @apiSuccess {String} toolJobs.effect_status 工具副作用的结算状态。
 * @apiSuccess {Number/Null} toolJobs.cleanup_after 最早可清理时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} toolJobs.cleanup_done 清理是否完成：1 已完成，0 未完成。
 * @apiSuccess {Object[]} inferences 推理操作记录，仅供诊断。
 * @apiSuccess {String} inferences.inference_id 推理操作记录 ID，仅供诊断关联。
 * @apiSuccess {String} inferences.job_id 作业 ID。
 * @apiSuccess {Number} inferences.wire_attempt 传输尝试序号。
 * @apiSuccess {String} inferences.backend_id 推理服务标识。
 * @apiSuccess {String} inferences.status 操作记录的状态标识。
 * @apiSuccess {Number} inferences.actual_finished 实际执行是否已结束：1 已结束，0 尚未确认。
 * @apiSuccess {Number} inferences.cancel_requested 是否已请求取消：1 是，0 否。
 * @apiSuccess {String/Null} inferences.error_code 错误码；无错误时为 null。
 * @apiSuccess {Number} inferences.created_at 记录创建时间，Unix 毫秒时间戳。
 * @apiSuccess {Number/Null} inferences.dispatched_at 作业派发时间，Unix 毫秒时间戳；未派发时为 null。
 * @apiSuccess {Number/Null} inferences.finished_at 实际执行完成时间，Unix 毫秒时间戳；未完成时为 null。
 * @apiSuccess {Number/Null} inferences.cancelled_at 取消时间，Unix 毫秒时间戳；未取消时为 null。
 * @apiSuccess {Object[]} files 文件元数据。
 * @apiSuccess {String} files.file_id 文件 ID。
 * @apiSuccess {String} files.kind 类别标识。
 * @apiSuccess {Number} files.size_bytes 文件大小，单位：字节。
 * @apiSuccess {String/Null} files.sha256 文件字节的 SHA-256 摘要；尚不可用时为 null。
 * @apiSuccess {String} files.status 操作记录的状态标识。
 * @apiSuccess {Number} files.created_at 记录创建时间，Unix 毫秒时间戳。
 * @apiSuccess {Number/Null} files.ready_at 文件就绪时间，Unix 毫秒时间戳；未就绪时为 null。
 * @apiSuccess {Number} files.expires_at 文件到期时间，Unix 毫秒时间戳。
 * @apiSuccess {Number/Null} files.deleted_at 文件删除时间，Unix 毫秒时间戳；未删除时为 null。
 * @apiSuccess {Number} files.attempts 已尝试的文件清理次数。
 * @apiSuccess {Object[]} feedback 反馈记录摘要。
 * @apiSuccess {String} feedback.feedback_id 反馈 ID。
 * @apiSuccess {String} feedback.payload_hash 反馈原文内容的 SHA-256 摘要。
 * @apiSuccess {String} feedback.rating 用户反馈评级。
 * @apiSuccess {Number} feedback.created_at 记录创建时间，Unix 毫秒时间戳。
 * @apiSuccess {Object/Null} resultCommitment 最终结果提交的脱敏证据。
 * @apiSuccess {String} resultCommitment.result_hash 最终结果的 SHA-256 摘要。
 * @apiSuccess {Number} resultCommitment.committed_at 结果提交时间，Unix 毫秒时间戳。
 * @apiSuccess {String} timingCoverage 时间指标覆盖说明；缺失的阶段时间不作推测。
 * @apiSuccess {Object} metrics 轮次、调用和作业时序等指标。
 * @apiSuccess {Number} metrics.rounds 导出的模型轮次数。
 * @apiSuccess {Number} metrics.calls 导出的工具调用数。
 * @apiSuccess {Object} metrics.modelJobs 对应操作的数量与耗时统计。
 * @apiSuccess {Number} metrics.modelJobs.total 记录总数。
 * @apiSuccess {Number} metrics.modelJobs.unsettledPhysical 实际执行尚未确认结束的数量。
 * @apiSuccess {Number} metrics.modelJobs.queued 处于 queued 状态的数量。
 * @apiSuccess {Object[]} metrics.modelJobs.timings 各记录的耗时统计。
 * @apiSuccess {String} metrics.modelJobs.timings.id 作业或推理记录 ID。
 * @apiSuccess {Number/Null} metrics.modelJobs.timings.queueMillis 创建至派发耗时；缺少时间戳时为 null。
 * @apiSuccess {Number/Null} metrics.modelJobs.timings.executionMillis 派发至完成耗时；缺少时间戳时为 null。
 * @apiSuccess {Number/Null} metrics.modelJobs.timings.cancelToPhysicalMillis 请求取消至实际执行结束的耗时；缺少时间戳时为 null。
 * @apiSuccess {Object} metrics.toolJobs 对应操作的数量与耗时统计。
 * @apiSuccess {Number} metrics.toolJobs.total 记录总数。
 * @apiSuccess {Number} metrics.toolJobs.unsettledPhysical 实际执行尚未确认结束的数量。
 * @apiSuccess {Number} metrics.toolJobs.queued 处于 queued 状态的数量。
 * @apiSuccess {Object[]} metrics.toolJobs.timings 各记录的耗时统计。
 * @apiSuccess {String} metrics.toolJobs.timings.id 作业或推理记录 ID。
 * @apiSuccess {Number/Null} metrics.toolJobs.timings.queueMillis 创建至派发耗时；缺少时间戳时为 null。
 * @apiSuccess {Number/Null} metrics.toolJobs.timings.executionMillis 派发至完成耗时；缺少时间戳时为 null。
 * @apiSuccess {Number/Null} metrics.toolJobs.timings.cancelToPhysicalMillis 请求取消至实际执行结束的耗时；缺少时间戳时为 null。
 * @apiSuccess {Object} metrics.inferences 对应操作的数量与耗时统计。
 * @apiSuccess {Number} metrics.inferences.total 记录总数。
 * @apiSuccess {Number} metrics.inferences.unsettledPhysical 实际执行尚未确认结束的数量。
 * @apiSuccess {Number} metrics.inferences.queued 处于 queued 状态的数量。
 * @apiSuccess {Object[]} metrics.inferences.timings 各记录的耗时统计。
 * @apiSuccess {String} metrics.inferences.timings.id 作业或推理记录 ID。
 * @apiSuccess {Number/Null} metrics.inferences.timings.queueMillis 创建至派发耗时；缺少时间戳时为 null。
 * @apiSuccess {Number/Null} metrics.inferences.timings.executionMillis 派发至完成耗时；缺少时间戳时为 null。
 * @apiSuccess {Number/Null} metrics.inferences.timings.cancelToPhysicalMillis 请求取消至实际执行结束的耗时；缺少时间戳时为 null。
 * @apiSuccess {String} digest 导出内容的 SHA-256 摘要。
 * @apiError (接口错误) TRACE_READER_REQUIRED HTTP 403，缺少诊断信息读取权限。
 * @apiError (接口错误) TRACE_DISABLED HTTP 400，trace 未开启。
 * @apiError (接口错误) TRACE_EXPIRED HTTP 400，超过 trace 保留期。
 * @apiError (接口错误) TRACE_EXPORT_TOO_LARGE HTTP 400，导出条数或大小超限。
 *
 * @apiExample {http} 使用具备诊断权限的用户令牌
 *     GET http://211.157.179.37:7010/harness/v1/runs/run-001/trace
 *     Authorization: Bearer <Cube Token>
 */
