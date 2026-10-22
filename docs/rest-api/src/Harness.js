/**
 * @apiDefine Harness Harness 任务服务
 * 业务基地址使用 dispatcher HTTP 地址（例如 http://127.0.0.1:7010），统一前缀为 /harness/v1。<br/>
 * dispatcher 需开启 harness.rest.enabled；任务执行需启用 store.enabled、execution.enabled 并发布 active TaskProfile。<br/>
 * 文件、反馈和 trace 分别受 files.enabled、feedback.enabled、trace.enabled 控制，默认关闭。
 *
 * <p>全部业务接口使用已有 Cube 用户 token：Authorization: Bearer &lt;Cube Token&gt;。<br/>
 * 普通资源按 token 对应的域和联系人隔离；审批及 trace 另需同域白名单权限。<br/>
 * Console 登录凭据独立；健康及运维接口使用单独的 service HTTP 地址，不属于本组业务接口。</p>
 *
 * <p>所有 POST/PUT 都必须携带 Idempotency-Key，格式为 [A-Za-z0-9_.:-]{1,128}。<br/>
 * 创建任务、控制、创建输入与反馈保存幂等回执：同键同请求重放原回执，同键异内容返回 409。<br/>
 * 上传按完整文件替换重传；重复 complete 不延长文件期限。<br/>
 * 路径中的资源 ID 同样为 1～128 位字母、数字或 _.:-。</p>
 *
 * <p>JSON 请求使用 application/json，最多 262144 字节、嵌套深度 32；拒绝非法 UTF-8、重复键、未知字段和尾随内容。<br/>
 * 文件上传使用 application/octet-stream 原始字节，不受 JSON 大小上限约束。<br/>
 * 时间戳均为 Unix 毫秒。除文件下载及 SSE 外，响应体直接为 JSON 对象，不包裹 code/data 或 status/body。</p>
 *
 * <p>常见错误状态：<br/>
 * 400 参数/schema/游标/摘要错误，401 未认证，403 权限不足，404 资源不存在，405 方法不支持，<br/>
 * 409 幂等/版本/事件缺号冲突，410 文件或交互到期，413 超限，415 媒体类型错误，429 容量或文件并发满，<br/>
 * 500 服务内部错误，503 未就绪/繁忙/链路不可用。<br/>
 * 部署和完整接入流程参见源码目录 Harness.md。</p>
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
 * @apiSuccess (202) {String} executionSettlement 物理执行结算：pending、confirmed 或 unknown。
 * @apiSuccess (202) {String} taskProfileVersion 本次任务冻结的 TaskProfile 版本。
 */

/**
 * @apiDefine HarnessProfileSummary
 * @apiSuccess {String} profileId 任务配置 ID；创建任务时填入 taskProfileId。
 * @apiSuccess {String} version 任务配置版本。
 * @apiSuccess {String} modelProfile 模型配置引用，格式为 id@version。
 * @apiSuccess {String} runtimeProfile 运行时配置引用，格式为 id@version。
 * @apiSuccess {String[]} skillReleases 固定的技能 release 摘要列表。
 * @apiSuccess {String} toolSetVersion 工具集版本。
 * @apiSuccess {String[]} requiredCapabilities 执行所需能力。
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
 * @apiDescription 查询服务就绪状态、已注册单元和实际容量。<br/>
 * 一个 service-harness JVM 可以接入多个同角色或不同角色的 Python 单元；各单元具有独立 workerId、实例身份、运行目录和租约。<br/>
 * 调度综合角色、能力、健康状态和实际可用容量；不可按 Java/Python 一对一关系或 worker 数量推算物理推理容量。<br/>
 * 执行未启用时仍可查询，此时 runsReady=false。
 * @apiUse HarnessAuth
 * @apiSuccess {String} service 服务名称。
 * @apiSuccess {Number} protocolVersion 当前能力协议版本；未启用执行时为 1，启用后为 3。
 * @apiSuccess {String} execution scripted、resource-loop 或 trusted-tool-loop。
 * @apiSuccess {Object[]} registeredWorkers 已注册单元；按角色返回身份、ready 状态、能力和容量信息。
 * @apiSuccess {Boolean} infrastructureReady 基础设施是否就绪；启用执行时为 true，不代表能够接单。
 * @apiSuccess {Boolean} runsReady 接单开启且存在就绪模型单元时为 true。
 * @apiSuccess {Boolean} [accepting] 启用执行后返回的接单开关。
 * @apiSuccess {String[]} [runnerTypes] 启用执行后返回的 Runner 类型列表。
 * @apiSuccess {Object} [capacity] 启用执行后返回的实际容量。
 * @apiSuccess {Number} capacity.registeredUnits 注册的模型单元数。
 * @apiSuccess {Number} capacity.available 当前可用执行量。
 * @apiSuccess {Number} capacity.executableConcurrency 实际可执行并发。
 * @apiSuccess {Object[]} capacity.backends 后端明细：<br/>
 * processingSlots/processingAvailable 为 Python 处理槽，<br/>
 * occupied/inferenceAvailable 为物理推理占用/可用量；<br/>
 * 共享引擎按 backendId 汇总。
 * @apiSuccess {Object/Null} [files] 启用执行后返回的文件能力指标；未启用文件时为 null。
 */

/**
 * @api {get} /harness/v1/task-profiles 查询任务配置列表
 * @apiName HarnessTaskProfiles
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 返回调用者域内 active TaskProfile 的摘要。<br/>
 * TaskProfile 在服务端固定模型、运行时、技能、权限、上下文预算和执行限额。<br/>
 * 执行未启用或尚无 active 配置时列表为空。
 * @apiUse HarnessAuth
 * @apiSuccess {Object[]} profiles 任务配置摘要列表。
 * @apiSuccess {String} profiles.profileId 任务配置 ID，创建任务时使用。
 * @apiSuccess {String} profiles.version 任务配置版本。
 * @apiSuccess {String} profiles.modelProfile 模型配置引用，id@version。
 * @apiSuccess {String} profiles.runtimeProfile 运行时配置引用，id@version。
 * @apiSuccess {String[]} profiles.skillReleases 技能 release 摘要列表。
 * @apiSuccess {String} profiles.toolSetVersion 工具集版本。
 * @apiSuccess {String[]} profiles.requiredCapabilities 所需能力。
 * @apiSuccessExample {json} 尚无可用任务配置
 *     HTTP/1.1 200 OK
 *     {"profiles":[]}
 */

/**
 * @api {get} /harness/v1/task-profiles/:taskProfileId 查询任务配置
 * @apiName HarnessTaskProfile
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 查询当前域内一个 active TaskProfile 的摘要；<br/>
 * 不返回可由客户端自由改写的模型或工具配置。
 * @apiUse HarnessAuth
 * @apiParam {String} taskProfileId 任务配置 ID，来自任务配置列表。
 * @apiUse HarnessProfileSummary
 * @apiError (接口错误) PROFILE_NOT_FOUND HTTP 404，配置不存在或不是当前域的 active 配置。
 */

/**
 * @api {post} /harness/v1/runs 创建任务
 * @apiName HarnessCreateRun
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 使用服务端已发布的 TaskProfile 创建异步任务。<br/>
 * 202 仅表示已受理；之后查询任务或事件获取结果。<br/>
 * 客户端只能提交任务配置 ID 和输入，不能指定任意模型、工具、身份或 channelCode。<br/>
 * 技能加载器只读取 SKILL.md 正文，文本生成不会自动执行模型输出的代码。<br/>
 * 同一幂等请求重放原受理快照，不代表当前最新状态。
 * @apiUse HarnessAuth
 * @apiUse HarnessIdempotency
 * @apiUse HarnessJson
 * @apiBody {String{1..128}} taskProfileId active TaskProfile ID；先查询任务配置列表。
 * @apiBody {Object} input 用户输入。
 * @apiBody {String{1..32768}} input.instruction 任务指令。
 * @apiBody {String[]} input.attachmentIds 最多 16 个输入文件 ID；无附件也必须传 []。<br/>
 * 只能使用本用户 ready 且未到期的 Input。
 * @apiParamExample {json} 请求体
 *     {"taskProfileId":"resources-Baize2","input":{"instruction":"列出并阅读已授权的技能资源","attachmentIds":[]}}
 * @apiExample {curl} 创建任务（taskProfileId 以列表返回值为准）
 *     curl -X POST "http://127.0.0.1:7010/harness/v1/runs" -H "Authorization: Bearer <Cube Token>" -H "Content-Type: application/json" -H "Idempotency-Key: create-run-001" --data-raw '{"taskProfileId":"resources-Baize2","input":{"instruction":"列出并阅读已授权的技能资源","attachmentIds":[]}}'
 * @apiUse HarnessSnapshot
 * @apiSuccessExample {json} 受理快照
 *     HTTP/1.1 202 Accepted
 *     {"runId":"run-001","version":1,"eventSeq":1,"state":"queued","executionSettlement":"pending","taskProfileVersion":"v1"}
 * @apiError (接口错误) IDEMPOTENCY_CONFLICT HTTP 409，同键请求内容不同。
 * @apiError (接口错误) HARNESS_BUSY HTTP 429，活跃任务容量已满。
 * @apiError (接口错误) HARNESS_NOT_READY HTTP 503，执行未启用。
 */

/**
 * @api {get} /harness/v1/runs/:runId 查询任务
 * @apiName HarnessReadRun
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 返回任务快照、用量结算和最终结果。<br/>
 * 普通用户仅能查询本人任务；control.approver.subjects 白名单中的审批人还可读取同域任务以取得待审批内容。<br/>
 * usageSettlement 与 executionSettlement 分别表示用量和物理执行的结算状态，<br/>
 * 取消受理或逻辑超时不代表物理推理已停止；unknown 占用继续隔离并等待迟到证据对账。
 * @apiUse HarnessAuth
 * @apiParam {String} runId 任务 ID。
 * @apiSuccess {Object} snapshot 当前任务快照。
 * @apiSuccess {String} snapshot.runId 任务 ID。
 * @apiSuccess {Number} snapshot.version 当前任务版本；用于 expectedVersion。
 * @apiSuccess {Number} snapshot.eventSeq 最新事件序号。
 * @apiSuccess {String} snapshot.state queued、running、waiting_input、waiting_approval、cancelling、reconciling、succeeded、failed、cancelled 或 timed_out。
 * @apiSuccess {String} snapshot.executionSettlement pending、confirmed 或 unknown。
 * @apiSuccess {String} snapshot.taskProfileVersion 冻结的任务配置版本。
 * @apiSuccess {String} usageSettlement settled 或 provisional。
 * @apiSuccess {Object} usage 用量汇总。
 * @apiSuccess {Number} usage.estimatedInput 估算输入 Token 数。
 * @apiSuccess {Number} usage.estimatedOutput 估算输出 Token 数。
 * @apiSuccess {Number} usage.actualInput 实际输入 Token 数。
 * @apiSuccess {Number} usage.actualOutput 实际输出 Token 数。
 * @apiSuccess {Number/Null} usage.pending 尚待结算的用量记录数。
 * @apiSuccess {Object/Null} result 最终结果提交；未产生时为 null。
 * @apiSuccess {String} result.state 最终逻辑状态。
 * @apiSuccess {String} result.executionSettlement 最终结果提交时的物理结算状态。
 * @apiSuccess {Object} result.result 任务结果内容。
 * @apiSuccess {Object[]} result.usage 结果提交携带的用量记录。
 * @apiSuccess {Object} [interaction] 存在待处理输入/审批时返回。
 * @apiSuccess {String} interaction.waitId 交互 ID，用于恢复或审批路径。
 * @apiSuccess {String} interaction.kind input 或 approval。
 * @apiSuccess {Number} interaction.expiresAt 交互到期时间。
 * @apiSuccess {String} interaction.argumentsHash 待审批参数摘要；审批时原样提交。
 * @apiSuccess {Object} interaction.details 待补充输入或审批的详情。
 * @apiSuccessExample {json} 排队中的任务
 *     HTTP/1.1 200 OK
 *     {
 *       "snapshot":{"runId":"run-001","version":1,"eventSeq":1,"state":"queued","executionSettlement":"pending","taskProfileVersion":"v1"},
 *       "usageSettlement":"settled",
 *       "usage":{"estimatedInput":0,"estimatedOutput":0,"actualInput":0,"actualOutput":0,"pending":0},
 *       "result":null
 *     }
 */

/**
 * @api {get} /harness/v1/runs/:runId/events 查询任务事件 / SSE
 * @apiName HarnessRunEvents
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 查询本人任务的事件回放页，按 eventSeq 递增返回 after 之后的事件。<br/>
 * 默认返回 JSON；Accept 包含 text/event-stream 时返回有限 SSE 回放页。<br/>
 * SSE 的 id 为 eventSeq，event 为原始 type，data 为完整事件 JSON（其中 dataJson 仍是 JSON 字符串）。<br/>
 * 完整页以注释 : replay-page-complete 结束。<br/>
 * 每个 gateway 最多 2 个 SSE 连接，写出期限为 2 秒；<br/>
 * 页结束或连接中断后，以最后完整收到的事件 ID 设置 Last-Event-ID 重连，空页遵守 retry: 500。<br/>
 * 不能把断连视为任务结束。
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
 *     data: {"runId":"run-001","eventSeq":1,"runVersion":1,"type":"run.created","occurredAt":1792627200000,"schemaVersion":1,"dataJson":"{\"taskProfileId\":\"resources-Baize2\",\"input\":{\"instruction\":\"列出并阅读已授权的技能资源\",\"attachmentIds\":[]}}"}
 *
 *     : replay-page-complete
 *
 * @apiExample {curl} 从已收到的事件 1 之后继续回放
 *     curl -N "http://127.0.0.1:7010/harness/v1/runs/run-001/events?limit=100" -H "Authorization: Bearer <Cube Token>" -H "Accept: text/event-stream" -H "Last-Event-ID: 1"
 * @apiError (接口错误) INVALID_EVENT_CURSOR HTTP 400，游标非法、指向未来或与 Last-Event-ID 不一致。
 * @apiError (接口错误) INVALID_EVENT_LIMIT HTTP 400，limit 不在 1～100。
 * @apiError (接口错误) EVENT_GAP HTTP 409，事件存在缺号；重新读取任务核对状态和事件。
 */

/**
 * @api {post} /harness/v1/runs/:runId/cancel 取消任务
 * @apiName HarnessCancelRun
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 取消本人的非终态任务，受理后进入 cancelling。<br/>
 * 202 表示取消请求已受理，实际推理可能仍在执行；继续查询快照与结算状态。<br/>
 * 同键重放原控制回执。<br/>
 * 版本冲突时先重读任务，再使用新版本和新的幂等键提交新的操作。
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
 *     {"runId":"run-001","version":4,"eventSeq":4,"state":"cancelling","executionSettlement":"pending","taskProfileVersion":"v1"}
 * @apiError (接口错误) VERSION_CONFLICT HTTP 409，任务版本已变化。
 * @apiError (接口错误) RUN_TERMINAL HTTP 409，任务已处于终态。
 */

/**
 * @api {post} /harness/v1/runs/:runId/resume 补充输入并恢复任务
 * @apiName HarnessResumeRun
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 仅用于本人处于 waiting_input 状态且仍未到期的输入交互。<br/>
 * 先查询任务取得最新 snapshot.version 和 interaction.waitId；受理后恢复 running。<br/>
 * need_input 会暂停生成并释放模型槽；附件仍要求为本人 ready 且未到期的 Input。
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
 */

/**
 * @api {post} /harness/v1/runs/:runId/approvals/:approvalId/decision 提交审批决定
 * @apiName HarnessDecideApproval
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 使用获准审批人的 Cube 用户 token，对同域 waiting_approval 任务的当前交互给出决定。<br/>
 * 审批人须在 control.approver.subjects 的“域/联系人ID”白名单内，且不能审批自己的任务。<br/>
 * 先读取任务取得版本、interaction.waitId 和 argumentsHash，审批时摘要必须原样提交；<br/>
 * approve 恢复 running，reject 进入 cancelling。
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
 */

/**
 * @api {post} /harness/v1/runs/:runId/feedback 提交任务反馈
 * @apiName HarnessFeedback
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 使用任务所有者的 Cube 用户 token 提交反馈。<br/>
 * 需要 feedback.enabled=true，默认关闭；与 trace 开关独立，不要求 trace reader 权限。<br/>
 * 同键同内容返回相同 feedbackId。
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
 */

/**
 * @api {post} /harness/v1/inputs 创建输入文件会话
 * @apiName HarnessCreateInput
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 开启 files.enabled 后，先声明文件，再 PUT 上传完整字节，最后 POST complete 确认为 ready。<br/>
 * 默认文件上限 10 MiB，服务端可配置至 64 MiB；<br/>
 * 支持 text/plain、text/csv、application/json 和 application/octet-stream。<br/>
 * SHA-256 针对实际文件字节计算；文本须为严格 UTF-8，JSON 须为顶层 object 并拒绝重复键。<br/>
 * 创建成功返回 201。
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
 */

/**
 * @api {get} /harness/v1/inputs/:inputId 查询输入文件
 * @apiName HarnessReadInput
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 查询本人输入文件的元数据和状态；<br/>
 * 使用附件前确认 state=ready 且未到期。
 * @apiUse HarnessAuth
 * @apiParam {String} inputId 输入文件 ID。
 * @apiUse HarnessInputMetadata
 */

/**
 * @api {put} /harness/v1/inputs/:inputId/content 上传输入文件内容
 * @apiName HarnessUploadInput
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 请求体为完整文件原始字节，Content-Type 必须精确为 application/octet-stream（文件自身类型在创建会话时声明）。<br/>
 * 传输长度和 SHA-256 必须与声明一致，服务端校验并提交后才返回 200 元数据。<br/>
 * 成功上传后仍须调用 complete 将输入设为 ready。<br/>
 * 失败后按完整文件替换重传；不支持分片或 multipart。
 * @apiUse HarnessAuth
 * @apiUse HarnessIdempotency
 * @apiParam {String} inputId 输入文件 ID。
 * @apiHeader {String} Content-Type application/octet-stream。
 * @apiBody {Binary} content 整个 HTTP 请求体为文件字节，不使用 JSON 包装。
 * @apiExample {curl} 上传 hello.txt（内容必须与创建会话时的 size/sha256 相符）
 *     curl -X PUT "http://127.0.0.1:7010/harness/v1/inputs/input-001/content" -H "Authorization: Bearer <Cube Token>" -H "Idempotency-Key: upload-input-001" -H "Content-Type: application/octet-stream" --data-binary @hello.txt
 * @apiUse HarnessInputMetadata
 * @apiError (接口错误) FILE_EXPIRED HTTP 410，文件会话已到期。
 * @apiError (接口错误) UNSUPPORTED_MEDIA_TYPE HTTP 415，上传请求的 Content-Type 错误。
 */

/**
 * @api {post} /harness/v1/inputs/:inputId/complete 确认输入文件就绪
 * @apiName HarnessCompleteInput
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 校验已上传文件的大小、SHA-256、媒体类型及文本/JSON 内容，成功后返回 ready 元数据。<br/>
 * 只有该状态的输入才能绑定任务；重复 complete 不延长有效期。
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
 */

/**
 * @api {get} /harness/v1/runs/:runId/artifacts 查询任务产物
 * @apiName HarnessArtifacts
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 查询本人任务的产物元数据列表；<br/>
 * 下载前检查 state、downloadable 和 expiresAt。
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
 * @apiSuccessExample {json} 暂无产物
 *     HTTP/1.1 200 OK
 *     {"artifacts":[]}
 */

/**
 * @api {get} /harness/v1/artifacts/:artifactId 查询产物元数据
 * @apiName HarnessReadArtifact
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 查询本人任务产物的元数据，不返回文件内容。
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
 */

/**
 * @api {get} /harness/v1/artifacts/:artifactId/content 下载产物内容
 * @apiName HarnessDownloadArtifact
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 下载本人产物的完整原始字节；保留文件 Content-Type、Content-Length 和带 UTF-8 文件名的 Content-Disposition。<br/>
 * 不支持 Range。<br/>
 * 也可使用 download-handle 返回的相对 URL，但仍需同一用户的 Bearer。<br/>
 * 客户端应检查 HTTP 状态、实际长度和元数据摘要；响应提交后发生错误会中止连接，按完整文件重试。
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
 *     curl --fail "http://127.0.0.1:7010/harness/v1/artifacts/artifact-001/content" -H "Authorization: Bearer <Cube Token>" -o artifact.txt
 * @apiError (接口错误) FILE_EXPIRED HTTP 410，产物已到期。
 */

/**
 * @api {get} /harness/v1/artifacts/:artifactId/download-handle 获取产物下载句柄
 * @apiName HarnessDownloadHandle
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 生成短期签名的相对下载 URL。<br/>
 * 将 url 拼接到同一 dispatcher 业务基地址，并携带同一用户的 Authorization；它不是匿名下载链接。<br/>
 * 句柄不会延长文件保留期。
 * @apiUse HarnessAuth
 * @apiParam {String} artifactId 产物 ID。
 * @apiSuccess {String} url 相对 URL，形如 /harness/v1/artifacts/{artifactId}/content?handle=...。
 * @apiSuccess {Number} expiresAt 句柄到期时间。
 * @apiSuccess {Boolean} requiresAuthentication 固定为 true。
 */

/**
 * @api {get} /harness/v1/runs/:runId/trace 导出任务诊断轨迹
 * @apiName HarnessTrace
 * @apiGroup Harness
 * @apiVersion 1.0.0
 * @apiDescription 需要 trace.enabled=true，且调用者在 trace.reader.subjects 的“域/联系人ID”白名单内；仅可导出同域任务。<br/>
 * trace 默认关闭，导出包含脱敏状态、事件、作业和账本证据，所有内容字符串以 SHA-256 脱敏。<br/>
 * replayComplete 表示状态回放证据是否完整，不能据此重放原始提示词或恢复未脱敏内容。
 * @apiUse HarnessAuth
 * @apiParam {String} runId 任务 ID。
 * @apiSuccess {Number} schemaVersion 当前为 1。
 * @apiSuccess {String} kind 固定为 harness-redacted-state-trace。
 * @apiSuccess {String} runId 任务 ID。
 * @apiSuccess {String} redaction 固定为 all-content-strings-sha256。
 * @apiSuccess {Boolean} replayComplete 状态回放证据是否完整。
 * @apiSuccess {Object} snapshot 任务快照，另含 usageSettlement。
 * @apiSuccess {Object/Null} config 冻结配置的引用及摘要。
 * @apiSuccess {Object[]} events 状态事件及投影。
 * @apiSuccess {Object[]} rounds 模型轮次。
 * @apiSuccess {Object[]} calls 工具调用。
 * @apiSuccess {Object[]} usage 用量账本。
 * @apiSuccess {Object[]} modelJobs 模型作业记录。
 * @apiSuccess {Object[]} toolJobs 工具作业记录。
 * @apiSuccess {Object[]} inferences Java 推理账本。
 * @apiSuccess {Object[]} files 文件账本元数据。
 * @apiSuccess {Object[]} feedback 反馈记录摘要。
 * @apiSuccess {Object/Null} resultCommitment 最终结果提交的脱敏证据。
 * @apiSuccess {String} timingCoverage 时间指标覆盖说明；缺失的阶段时间不作推测。
 * @apiSuccess {Object} metrics 轮次、调用和作业时序等指标。
 * @apiSuccess {String} digest 导出内容的 SHA-256 摘要。
 * @apiError (接口错误) TRACE_READER_REQUIRED HTTP 403，缺少 trace reader 权限。
 * @apiError (接口错误) TRACE_DISABLED HTTP 400，trace 未开启。
 * @apiError (接口错误) TRACE_EXPIRED HTTP 400，超过 trace 保留期。
 * @apiError (接口错误) TRACE_EXPORT_TOO_LARGE HTTP 400，导出条数或大小超限。
 */
