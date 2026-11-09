/**
 * @apiDefine SpecializedDetection 专项检测
 */

/**
 * @api {post} /specialized-detection/base/:token 专项图片检测
 * @apiName SpecializedDetectionImage
 * @apiGroup SpecializedDetection
 * @apiVersion 1.0.0
 * @apiDescription 按所选 profile 检测图片中的目标，支持火烟和通用物体，返回类别、置信度及矩形位置。
 *                 <p>调用前先<a href="#api-Data_Management-申请对话频道">创建频道</a>，再通过<a href="#api-File_Processing-上传文件数据">上传文件数据</a>接口上传图片，并确认文件可查询。将频道码填入 <code>channel</code>，将文件码填入 <code>input.files</code>；频道和文件须属于当前用户和域。</p>
 *                 <p><code>fire-smoke</code> 用于火焰、烟雾检测；<code>uav-fire-smoke</code> 用于无人机巡检画面；<code>thing</code> 用于 COCO 80 类通用物体。可用方案和切片支持以节点注册及生效模型绑定为准。提高 <code>confidence</code> 可减少低置信度结果，降低阈值可保留更多候选目标。</p>
 *                 <p>本接口在一次请求中返回结果。遍历 <code>resultPayload.refs[].boxes[]</code> 绘制检测框，按 <code>content</code> 区分类别；坐标以方向校正后的原图像素为准，缩放展示时按 <code>width</code>、<code>height</code> 同比换算。<code>refs=[]</code> 表示未检出目标，仍是成功结果，应以检测区域判断是否命中。</p>
 *                 <p>请求体最大 <code>64 KiB</code>，每次提交一张图片。检测可能需要较长时间，客户端请求超时建议大于 <code>190 秒</code>；出现 <code>429</code> 或 <code>503</code> 时延迟重试，其他错误按下方状态说明处理。</p>
 * @apiParam {String} token 授权令牌。
 * @apiHeader {String} Content-Type application/json
 * @apiHeader {String} [x-baize-api-device] 获取令牌时使用的客户端设备名。
 * @apiHeader {String} [x-baize-api-platform] 获取令牌时使用的客户端平台。
 * @apiBody {String} channel 当前用户已创建的频道码。
 * @apiBody {Object} input 待检测图片及检测参数。
 * @apiBody {String} [input.unit=SpecializedDetection] 固定能力名。
 * @apiBody {String[]} input.files 一个上传完成的 64 位文件码；不接受本地路径、HTTP 媒体地址或文件元数据对象。
 * @apiBody {Object} input.option 检测参数。
 * @apiBody {String} [input.option.operation=detect] 图片操作。
 * @apiBody {String} input.option.profile 检测方案：<code>fire-smoke</code>、<code>uav-fire-smoke</code> 或通用物体 <code>thing</code>。按节点实际启用能力选路；未开通返回 HTTP 400，无就绪节点返回 503。
 * @apiBody {String[]} [input.option.classes] 非空规范类别名称数组，如 thing 使用 ["person","car"]。省略时输出该服务全部类别；重复名称去重，null、空数组及未知类别报错。
 * @apiBody {Number} [input.option.confidence] 阈值 [0,1]，省略时使用服务默认值。
 * @apiBody {Boolean} [input.option.sliced=false] 是否启用切片检测，按节点生效绑定的能力校验；当前仅 uav-fire-smoke 支持。
 * @apiParamExample {json} 图片检测请求:
 * {
 *   "channel": "inspection-001",
 *   "input": {
 *     "files": ["UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx"],
 *     "option": {"profile": "fire-smoke", "confidence": 0.4}
 *   }
 * }
 * @apiParamExample {json} 通用物体：只检测人和车
 * {"channel":"inspection-001","input":{"files":["aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"],"option":{"profile":"thing","classes":["person","car"],"confidence":0.4}}}
 * @apiSuccess {Number} sn 本次检测的请求编号，可用于关联请求与结果。
 * @apiSuccess {String} unit SpecializedDetection。
 * @apiSuccess {Number} timestamp 检测结果的 Unix 毫秒时间戳。
 * @apiSuccess {String} response 检测结果的文字说明，可能为空；目标信息以 resultPayload 为准。
 * @apiSuccess {String} thought 补充说明，可能为空。
 * @apiSuccess {Object} resultPayload 检测结果，坐标基于方向校正后的原图像素。
 * @apiSuccess {String} resultPayload.profile 本次请求的 profile。
 * @apiSuccess {String} resultPayload.labelSpace 类别契约：火烟为 fire-smoke-v1，thing 为 coco80-v1。
 * @apiSuccess {String} resultPayload.modelId 服务端生效模型配置 ID。
 * @apiSuccess {String} resultPayload.modelVariant 模型规格。
 * @apiSuccess {String} resultPayload.backend 推理后端。
 * @apiSuccess {Number} resultPayload.inputResolution 模型输入尺寸。
 * @apiSuccess {String} [resultPayload.artifactSha256] 真实模型工件摘要。
 * @apiSuccess {Number} resultPayload.width 图片宽度，单位：像素。
 * @apiSuccess {Number} resultPayload.height 图片高度，单位：像素。
 * @apiSuccess {Object[]} resultPayload.refs 检测区域，未命中时为 []；命中时包含 content、classId、boxes 和 points。
 * @apiSuccess {String} resultPayload.refs.content 当前类别契约中的规范类别名。
 * @apiSuccess {Number} resultPayload.refs.classId fire-smoke-v1 中 fire=0、smoke=1；coco80-v1 固定为 0…79，例如 person=0、car=2。
 * @apiSuccess {Array} resultPayload.refs.points 预留的检测点列表；当前火焰、烟雾模型固定返回 []，无需读取子字段。
 * @apiSuccess {Object[]} resultPayload.refs.boxes 检测框，字段为 x1、y1、x2、y2、confidence；左上角为 (x1,y1)，右下角为 (x2,y2)。
 * @apiSuccess {Number} resultPayload.refs.boxes.x1 左上角横坐标，单位：原图像素。
 * @apiSuccess {Number} resultPayload.refs.boxes.y1 左上角纵坐标，单位：原图像素。
 * @apiSuccess {Number} resultPayload.refs.boxes.x2 右下角横坐标，单位：原图像素。
 * @apiSuccess {Number} resultPayload.refs.boxes.y2 右下角纵坐标，单位：原图像素。
 * @apiSuccess {Number} resultPayload.refs.boxes.confidence 检测置信度，范围 [0,1]。
 * @apiSuccess {Object} performance 本次检测的用量与处理耗时。
 * @apiSuccess {Number} performance.inputTokens 实际输入词元数；0 是合法值。
 * @apiSuccess {Number} performance.outputTokens 实际输出词元数；0 是合法值。
 * @apiSuccess {String} performance.elapsed 耗时的十进制字符串，单位：毫秒。
 *
 * @apiSuccessExample {json} 同时检出火焰和烟雾
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 123456789,
 *         "unit": "SpecializedDetection",
 *         "timestamp": 1793322000000,
 *         "response": "",
 *         "thought": "",
 *         "resultPayload": {
 *             "profile": "fire-smoke",
 *             "width": 1920,
 *             "height": 1080,
 *             "refs": [
 *                 {
 *                     "content": "fire",
 *                     "classId": 0,
 *                     "boxes": [
 *                         {
 *                             "x1": 120,
 *                             "y1": 380,
 *                             "x2": 310,
 *                             "y2": 620,
 *                             "confidence": 0.93
 *                         }
 *                     ],
 *                     "points": []
 *                 },
 *                 {
 *                     "content": "smoke",
 *                     "classId": 1,
 *                     "boxes": [
 *                         {
 *                             "x1": 100,
 *                             "y1": 140,
 *                             "x2": 480,
 *                             "y2": 400,
 *                             "confidence": 0.87
 *                         }
 *                     ],
 *                     "points": []
 *                 }
 *             ]
 *         },
 *         "performance": {
 *             "inputTokens": 0,
 *             "outputTokens": 0,
 *             "elapsed": "86"
 *         }
 *     }
 *
 * @apiSuccessExample {json} 未检出目标（仍为成功）:
 * HTTP/1.1 200 OK
 * {
 *   "sn": 123456789,
 *   "unit": "SpecializedDetection",
 *   "timestamp": 1793322000000,
 *   "response": "",
 *   "thought": "",
 *   "resultPayload": {"profile": "fire-smoke", "width": 1920, "height": 1080, "refs": []},
 *   "performance": {"inputTokens": 0, "outputTokens": 0, "elapsed": "30"}
 * }
 * @apiError {Object} error state 为 HTTP 状态码，code 为业务状态码，另含 reason、message。400 参数或不支持的 profile；401 认证；403 归属；404 文件不存在；413 请求体过大；429 忙碌；503 未就绪；422 文件错误；502 检测服务错误；504 超时。
 * @apiError {Number} error.state HTTP 状态码，与本次 HTTP 响应状态一致。
 * @apiError {Number} error.code 业务状态码，例如 1 参数错误、7 忙碌、31 检测失败或超时。
 * @apiError {String} error.reason 稳定的错误原因标识，如 UNIT_REJECTED、REQUEST_TOO_LARGE、DETECTION_TIMEOUT。
 * @apiError {String} error.message 供排查问题的错误说明文本。
 * @apiErrorExample {json} 检测方案不可用：检查 profile 或联系服务提供方
 * HTTP/1.1 400 Bad Request
 * {"error":{"state":400,"code":1,"reason":"UNIT_REJECTED","message":"Specialized detection failed (code 1)"}}
 * @apiErrorExample {json} 忙碌:
 * HTTP/1.1 429 Too Many Requests
 * {"error":{"state":429,"code":7,"reason":"UNIT_REJECTED","message":"Specialized detection failed (code 7)"}}
 *
 * @apiExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/specialized-detection/base/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "channel": "inspection-001",
 *         "input": {
 *             "files": [
 *                 "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx"
 *             ],
 *             "option": {
 *                 "profile": "fire-smoke",
 *                 "confidence": 0.4
 *             }
 *         }
 *     }
 *
 * @apiParamExample {json} 无人机图片：启用小目标增强并调整阈值
 *     {
 *         "channel": "inspection-001",
 *         "input": {
 *             "files": [
 *                 "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx"
 *             ],
 *             "option": {
 *                 "profile": "uav-fire-smoke",
 *                 "confidence": 0.35,
 *                 "sliced": true
 *             }
 *         }
 *     }
 * @apiError 400 参数或检测方案不可用。检查字段、取值范围及 profile，确认所选方案已开通。
 * @apiError 401 认证失败。检查令牌有效期及设备、平台请求头。
 * @apiError 403 无权访问频道或文件。使用当前用户和域所属的资源。
 * @apiError 404 资源不存在。确认文件码正确且图片已上传完成。
 * @apiError 413 请求体过大。JSON 请求体须在 64 KiB 内，媒体通过文件接口上传。
 * @apiError 422 媒体无法处理。检查上传文件是否完整、格式是否可用。
 * @apiError 429 服务繁忙。降低并发并延迟重试。
 * @apiError 502 检测失败。可稍后重试，持续失败时提供错误码并联系服务提供方。
 * @apiError 503 服务暂不可用。稍后重试或联系服务提供方。
 * @apiError 504 请求超时。提示本次检测未能及时完成，避免连续重复提交同一图片。

 */

/**
 * @api {post} /specialized-detection/stream/:token 专项视频后台会话
 * @apiName SpecializedDetectionStream
 * @apiGroup SpecializedDetection
 * @apiVersion 1.0.0
 * @apiDescription 按所选 profile 持续检测视频文件或 RTSP(S) 实时画面中的火烟或通用物体，在命中目标时向指定地址推送带检测框的 PNG 图片。适合视频巡检、摄像头监控和告警图片留存。
 *                 <p>先创建频道。检测视频文件时先上传并取得文件码；检测实时视频时提供服务端可访问的 RTSP(S) 地址。使用 <code>operation=start</code> 创建会话，保存返回的 <code>resultPayload.streamId</code>，之后通过同一接口的 <code>status</code> 查询进度、<code>stop</code> 停止会话。</p>
 *                 <p>启动成功表示会话已创建，媒体仍可能处于 <code>starting</code> 状态。查询返回 <code>running</code> 后开始检测；<code>reconnecting</code> 表示正在重新连接视频源；<code>stopping</code> 表示正在停止；<code>stopped</code>、<code>failed</code> 表示会话已结束。视频文件可读取 <code>progress</code> 展示进度，实时视频的进度为 <code>null</code>。</p>
 *                 <p>接收回调的地址须可被检测服务访问，并支持 <code>POST</code> 二进制请求。请求类型为 <code>image/png</code>，<code>X-VLM-Event-Id</code> 为事件标识，同一事件可能重复投递，请据此去重。检测元数据位于 PNG 的 <code>vlm_meta</code> 文本信息中，包含会话标识、时间、画面位置和检测框；下方示例展示读取方法。</p>
 *                 <p>只有命中目标的画面才会回调，未收到回调不代表会话已结束。可通过 <code>inferenceFps</code> 调整每秒检测帧数，通过 <code>intervalSeconds</code> 控制命中画面的推送间隔；回调发送失败不会自动停止检测，可通过状态中的 <code>callbackFailures</code> 排查。</p>
 *                 <p>查询和停止时使用创建会话的用户身份，并将 <code>files</code> 设为空数组；<code>option</code> 仅传 <code>operation</code> 和 <code>streamId</code>。启动请求超时后，检测仍可能继续，请先核查已收到的回调或联系服务提供方，避免立即重复创建；服务重启后需重新建立会话。</p>
 * @apiParam {String} token 授权令牌。
 * @apiHeader {String} Content-Type application/json
 * @apiHeader {String} [x-baize-api-device] 获取令牌时使用的客户端设备名。
 * @apiHeader {String} [x-baize-api-platform] 获取令牌时使用的客户端平台。
 * @apiBody {String} channel 调用者所属频道。
 * @apiBody {Object} input 专项输入。
 * @apiBody {String} [input.unit=SpecializedDetection] 固定能力名。
 * @apiBody {String[]} input.files start 为一个视频文件码或 RTSP(S) 地址，status/stop 为 []。
 * @apiBody {Object} input.option start 参数或控制参数。
 * @apiBody {String="start","status","stop"} input.option.operation 操作。
 * @apiBody {String} [input.option.profile] start 必填：fire-smoke、uav-fire-smoke 或 thing，按实际启用且就绪的节点选路。
 * @apiBody {String[]} [input.option.classes] start 可选，非空规范类别名称数组；先过滤再判断视频命中，绑定到会话，不用于 status/stop。
 * @apiBody {String} [input.option.callbackUrl] start 必填，检测服务可访问的 HTTP(S) PNG 接收地址。
 * @apiBody {Number} [input.option.confidence] start 可选，置信度阈值 [0,1]；提高阈值可减少低置信度检测框。
 * @apiBody {Boolean} [input.option.sliced=false] start 可选，按节点生效绑定校验；当前仅 uav-fire-smoke 支持。
 * @apiBody {Number} [input.option.intervalSeconds] start 回调间隔 [0,86400] 秒，省略时使用服务默认值。
 * @apiBody {Number} [input.option.inferenceFps] start 抽帧率 [0.1,60]，省略时使用服务默认值。
 * @apiBody {String} [input.option.streamId] status/stop 必填；控制只允许 operation 和 streamId。
 * @apiParamExample {json} 启动 RTSP 检测:
 * {
 *   "channel": "inspection-001",
 *   "input": {
 *     "files": ["rtsp://camera.example.com/live"],
 *     "option": {
 *       "operation": "start",
 *       "profile": "uav-fire-smoke",
 *       "sliced": true,
 *       "callbackUrl": "https://receiver.example.com/detection",
 *       "intervalSeconds": 10,
 *       "inferenceFps": 1
 *     }
 *   }
 * }
 * @apiParamExample {json} 查询会话（替换 streamId）:
 * {
 *   "channel": "inspection-001",
 *   "input": {"files": [], "option": {"operation": "status", "streamId": "<启动返回的streamId>"}}
 * }
 * @apiParamExample {json} 停止会话（替换 streamId）:
 * {
 *   "channel": "inspection-001",
 *   "input": {"files": [], "option": {"operation": "stop", "streamId": "<启动返回的streamId>"}}
 * }
 * @apiSuccess {Object} resultPayload 会话快照，含 streamId、status、profile、统计数据等。
 * @apiSuccess {String} resultPayload.profile 本次会话使用的检测 profile。
 * @apiSuccess {String} resultPayload.sourceType 输入类型：video 为视频文件，rtsp 为 RTSP(S)。
 * @apiSuccess {Number} resultPayload.startedAt 会话创建时间，Unix 毫秒时间戳。
 * @apiSuccess {Number/Null} resultPayload.stoppedAt 会话结束时间，Unix 毫秒时间戳；尚未结束时为 null。
 * @apiSuccess {String/Null} resultPayload.stopReason 停止原因；尚无原因时为 null。
 * @apiSuccess {String/Null} resultPayload.lastError 最近一次错误说明；无错误时为 null。
 * @apiSuccess {Number/Null} resultPayload.lastDataAt 最近收到输入数据的 Unix 毫秒时间戳；尚无数据时为 null。
 * @apiSuccess {Number/Null} resultPayload.durationSeconds 视频总时长，单位：秒；未知或 RTSP 时为 null。
 * @apiSuccess {Number} resultPayload.positionSeconds 当前媒体位置，单位：秒。
 * @apiSuccess {Number/Null} resultPayload.progress 已处理媒体进度 [0,1]；总时长未知时为 null。
 * @apiSuccess {Number} resultPayload.inferenceFps 实际采用的每秒检测帧数。
 * @apiSuccess {Number} resultPayload.intervalSeconds 实际采用的命中回调间隔，单位：秒。
 * @apiSuccess {Object} resultPayload.input 输入媒体状态。
 * @apiSuccess {String} resultPayload.input.url 输入源的描述地址，仅供展示和诊断；后续查询、停止仍使用 streamId，文件访问仍使用原文件码。
 * @apiSuccess {String} resultPayload.input.status 输入当前的会话状态。
 * @apiSuccess {Number/Null} resultPayload.input.width 输入宽度，单位：像素；探测完成前为 null。
 * @apiSuccess {Number/Null} resultPayload.input.height 输入高度，单位：像素；探测完成前为 null。
 * @apiSuccess {Number/Null} resultPayload.input.fps 输入帧率；探测完成前为 null。
 * @apiSuccess {Number} resultPayload.decodedFrames 解码帧数，从会话创建起累计。
 * @apiSuccess {Number} resultPayload.droppedFrames 丢弃的输入帧数，从会话创建起累计。
 * @apiSuccess {Number} resultPayload.inferredFrames 完成推理的帧数，从会话创建起累计。
 * @apiSuccess {Number} resultPayload.matchedFrames 检测命中的帧数，从会话创建起累计。
 * @apiSuccess {Number} resultPayload.postedFrames 成功推送回调的帧数，从会话创建起累计。
 * @apiSuccess {Number} resultPayload.droppedCallbacks 因积压丢弃的回调数，从会话创建起累计。
 * @apiSuccess {Number} resultPayload.callbackFailures 回调发送失败数，从会话创建起累计。
 * @apiSuccess {Number} resultPayload.inferenceFailures 推理失败数，从会话创建起累计。
 * @apiSuccess {String} resultPayload.streamId 后续查询和停止使用的会话标识。
 * @apiSuccess {String="starting","running","reconnecting","stopping","stopped","failed"} resultPayload.status 会话状态；stopped、failed 表示已结束；收到 stopping 时应继续查询，直至结束。
 * @apiSuccess {String} response 本次操作的文字说明，可能为空。
 * @apiSuccess {String} thought 补充说明，可能为空。
 * @apiSuccess {Object} performance 本次启动、查询或停止请求的用量与耗时；不代表整个视频的累计用量。
 * @apiSuccess {Number} performance.inputTokens 实际输入词元数；0 是合法值。
 * @apiSuccess {Number} performance.outputTokens 实际输出词元数；0 是合法值。
 * @apiSuccess {String} performance.elapsed 耗时的十进制字符串，单位：毫秒。
 * @apiSuccess {Number} sn 本次操作的请求编号。
 * @apiSuccess {String} unit SpecializedDetection。
 * @apiSuccess {Number} timestamp 本次操作结果的 Unix 毫秒时间戳。
 * @apiError {Object} error 400 参数或不支持的 profile；401 认证；403 归属；404 不存在；413 请求体超过 64 KiB；429 忙碌；503 未就绪；422 文件错误；502 检测服务错误；504 超时（远端可能仍运行）。含 state/code/reason/message。
 * @apiError {Number} error.state HTTP 状态码，与本次 HTTP 响应状态一致。
 * @apiError {Number} error.code 业务状态码，例如 1 参数错误、7 忙碌、31 检测失败或超时。
 * @apiError {String} error.reason 稳定的错误原因标识，如 UNIT_REJECTED、REQUEST_TOO_LARGE、DETECTION_TIMEOUT。
 * @apiError {String} error.message 供排查问题的错误说明文本。
 * @apiErrorExample {json} 启动请求超时:
 * HTTP/1.1 504 Gateway Timeout
 * {"error":{"state":504,"code":31,"reason":"DETECTION_TIMEOUT","message":"Detection request timed out; remote execution may still be running"}}
 *
 * @apiExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/specialized-detection/stream/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "channel": "inspection-001",
 *         "input": {
 *             "files": [
 *                 "rtsp://camera.example.com/live"
 *             ],
 *             "option": {
 *                 "operation": "start",
 *                 "profile": "uav-fire-smoke",
 *                 "sliced": true,
 *                 "callbackUrl": "https://receiver.example.com/detection",
 *                 "intervalSeconds": 10,
 *                 "inferenceFps": 1
 *             }
 *         }
 *     }
 *
 * @apiParamExample {json} 检测已上传的视频文件：每秒检测两帧，命中画面每五秒推送一次
 *     {
 *         "channel": "inspection-001",
 *         "input": {
 *             "files": [
 *                 "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx"
 *             ],
 *             "option": {
 *                 "operation": "start",
 *                 "profile": "fire-smoke",
 *                 "confidence": 0.4,
 *                 "callbackUrl": "https://receiver.example.com/detection",
 *                 "intervalSeconds": 5,
 *                 "inferenceFps": 2
 *             }
 *         }
 *     }
 *
 * @apiExample {python} 在回调接收程序中读取 PNG 元数据（需安装 Pillow）
 *     import json
 *     from io import BytesIO
 *     from PIL import Image
 *
 *     def read_detection(png_bytes):
 *         # png_bytes 为回调 POST 请求的原始二进制请求体
 *         with Image.open(BytesIO(png_bytes)) as image:
 *             metadata = json.loads(image.text["vlm_meta"])
 *         event_id = metadata["eventId"]  # 与 X-VLM-Event-Id 对应，用于去重
 *         stream_id = metadata["streamId"]
 *         for region in metadata["resultPayload"]["refs"]:
 *             for box in region["boxes"]:
 *                 print(stream_id, region["content"], box["confidence"], box)
 *         return event_id
 *
 * @apiSuccessExample {json} 启动成功：保存 streamId 后继续查询状态
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 123456789,
 *         "unit": "SpecializedDetection",
 *         "timestamp": 1793322000000,
 *         "response": "",
 *         "thought": "",
 *         "resultPayload": {
 *             "streamId": "stream-001",
 *             "status": "starting",
 *             "profile": "fire-smoke",
 *             "sourceType": "video",
 *             "startedAt": 1793322000000,
 *             "stoppedAt": null,
 *             "stopReason": null,
 *             "lastError": null,
 *             "lastDataAt": null,
 *             "durationSeconds": null,
 *             "positionSeconds": 0,
 *             "progress": null,
 *             "inferenceFps": 2,
 *             "intervalSeconds": 5,
 *             "input": {
 *                 "url": "<输入视频的描述地址>",
 *                 "status": "starting",
 *                 "width": null,
 *                 "height": null,
 *                 "fps": null
 *             },
 *             "decodedFrames": 0,
 *             "droppedFrames": 0,
 *             "inferredFrames": 0,
 *             "matchedFrames": 0,
 *             "postedFrames": 0,
 *             "droppedCallbacks": 0,
 *             "callbackFailures": 0,
 *             "inferenceFailures": 0
 *         },
 *         "performance": {
 *             "inputTokens": 0,
 *             "outputTokens": 0,
 *             "elapsed": "12"
 *         }
 *     }
 *
 * @apiSuccessExample {json} 查询视频进度：已处理 25%，成功推送四张命中图片
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 123456819,
 *         "unit": "SpecializedDetection",
 *         "timestamp": 1793322030000,
 *         "response": "",
 *         "thought": "",
 *         "resultPayload": {
 *             "streamId": "stream-001",
 *             "status": "running",
 *             "profile": "fire-smoke",
 *             "sourceType": "video",
 *             "startedAt": 1793322000000,
 *             "stoppedAt": null,
 *             "stopReason": null,
 *             "lastError": null,
 *             "lastDataAt": 1793322030000,
 *             "durationSeconds": 120,
 *             "positionSeconds": 30,
 *             "progress": 0.25,
 *             "inferenceFps": 2,
 *             "intervalSeconds": 5,
 *             "input": {
 *                 "url": "<输入视频的描述地址>",
 *                 "status": "running",
 *                 "width": 1920,
 *                 "height": 1080,
 *                 "fps": 25
 *             },
 *             "decodedFrames": 750,
 *             "droppedFrames": 0,
 *             "inferredFrames": 60,
 *             "matchedFrames": 12,
 *             "postedFrames": 4,
 *             "droppedCallbacks": 0,
 *             "callbackFailures": 0,
 *             "inferenceFailures": 0
 *         },
 *         "performance": {
 *             "inputTokens": 0,
 *             "outputTokens": 0,
 *             "elapsed": "12"
 *         }
 *     }
 *
 * @apiSuccessExample {json} 停止完成：status 为 stopped 时结束轮询
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 123456820,
 *         "unit": "SpecializedDetection",
 *         "timestamp": 1793322031000,
 *         "response": "",
 *         "thought": "",
 *         "resultPayload": {
 *             "streamId": "stream-001",
 *             "status": "stopped",
 *             "profile": "fire-smoke",
 *             "sourceType": "video",
 *             "startedAt": 1793322000000,
 *             "stoppedAt": 1793322031000,
 *             "stopReason": "requested",
 *             "lastError": null,
 *             "lastDataAt": 1793322030000,
 *             "durationSeconds": 120,
 *             "positionSeconds": 30,
 *             "progress": 0.25,
 *             "inferenceFps": 2,
 *             "intervalSeconds": 5,
 *             "input": {
 *                 "url": "<输入视频的描述地址>",
 *                 "status": "stopped",
 *                 "width": 1920,
 *                 "height": 1080,
 *                 "fps": 25
 *             },
 *             "decodedFrames": 750,
 *             "droppedFrames": 0,
 *             "inferredFrames": 60,
 *             "matchedFrames": 12,
 *             "postedFrames": 4,
 *             "droppedCallbacks": 0,
 *             "callbackFailures": 0,
 *             "inferenceFailures": 0
 *         },
 *         "performance": {
 *             "inputTokens": 0,
 *             "outputTokens": 0,
 *             "elapsed": "12"
 *         }
 *     }
 *
 * @apiSuccessExample {http} 命中回调请求（发送至 callbackUrl，并非本接口的响应）
 *     POST https://receiver.example.com/detection
 *     Content-Type: image/png
 *     X-VLM-Event-Id: stream-001:60
 *
 *     <带检测框和 vlm_meta 文本信息的 PNG 二进制数据>
 *
 * @apiSuccessExample {json} 回调 PNG 的 vlm_meta 内容示例（从图片中解析得到）
 *     {
 *         "eventId": "stream-001:60",
 *         "streamId": "stream-001",
 *         "unit": "SpecializedDetection",
 *         "profile": "fire-smoke",
 *         "sequence": 60,
 *         "timestamp": 1793322030000,
 *         "offsetSeconds": 30,
 *         "resultPayload": {
 *             "profile": "fire-smoke",
 *             "width": 1920,
 *             "height": 1080,
 *             "refs": [
 *                 {
 *                     "content": "fire",
 *                     "classId": 0,
 *                     "boxes": [
 *                         {
 *                             "x1": 120,
 *                             "y1": 380,
 *                             "x2": 310,
 *                             "y2": 620,
 *                             "confidence": 0.93
 *                         }
 *                     ],
 *                     "points": []
 *                 }
 *             ]
 *         },
 *         "response": "",
 *         "performance": {
 *             "inputTokens": 0,
 *             "outputTokens": 0,
 *             "elapsed": "40"
 *         }
 *     }
 * @apiError 400 参数或检测方案不可用。检查字段、取值范围及 profile，确认所选方案已开通。
 * @apiError 401 认证失败。检查令牌有效期及设备、平台请求头。
 * @apiError 403 无权访问频道或文件。使用当前用户和域所属的资源。
 * @apiError 404 资源不存在。确认文件已上传完成，或核对视频会话的 streamId。
 * @apiError 413 请求体过大。JSON 请求体须在 64 KiB 内，媒体通过文件接口上传。
 * @apiError 422 媒体无法处理。检查上传文件是否完整、格式是否可用，或视频源能否访问。
 * @apiError 429 服务繁忙。降低并发并延迟重试。
 * @apiError 502 检测失败。可稍后重试，持续失败时提供错误码并联系服务提供方。
 * @apiError 503 服务暂不可用。稍后重试或联系服务提供方。
 * @apiError 504 请求超时。视频启动超时后可能仍在运行，避免立即重复创建会话。

 */
