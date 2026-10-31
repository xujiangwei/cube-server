/**
 * @apiDefine SpecializedDetection 专项检测
 */

/**
 * @api {post} /specialized-detection/base/:token 专项图片检测
 * @apiName SpecializedDetectionImage
 * @apiGroup SpecializedDetection
 * @apiVersion 1.0.0
 * @apiDescription 通过 SpecializedDetection Unit 检测图片中的火焰和烟雾。先创建频道并上传图片，将调用者所属频道码和上传后的文件码传入；文件须属于同一用户和域。
 *                 <p>成功直接返回业务 JSON；空文本、零 token 和空 <code>refs</code> 均为合法成功。请求体最大 <code>64 KiB</code>，不接收 <code>content</code>、提示词或聊天历史。</p>
 *                 <p>部署无需在 <code>aigc.properties</code> 或 <code>dispatcher.properties</code> 中添加专项配置。默认数据池 8 线程、64 个排队名额，每个 Python 实例最多一个图片在途请求；service 数据期限为 180 秒，dispatcher 等待 190 秒。</p>
 *                 <p>无需声明支持的 profiles。已知名称由 Java <code>cube.aigc.SpecializedDetectionProfile</code> 枚举汇总，目前为 <code>fire-smoke</code> 和 <code>uav-fire-smoke</code>；Java 不按 profile 筛选实例，由 Python 在请求时判断是否支持。不支持时返回 Python 对应状态，当前为 <code>code=1</code> / HTTP 400。</p>
 * @apiParam {String} token AIGC token。
 * @apiHeader {String} Content-Type application/json
 * @apiHeader {String} [x-baize-api-device] 获取令牌时使用的客户端设备名。
 * @apiHeader {String} [x-baize-api-platform] 获取令牌时使用的客户端平台。
 * @apiBody {String} channel 调用者所属频道；不占用聊天 processing，不写历史。
 * @apiBody {Object} input 专项输入，不需要 content。
 * @apiBody {String} [input.unit=SpecializedDetection] 固定能力名。
 * @apiBody {String[]} input.files 一个上传完成的 64 位文件码；不接受本地路径、HTTP 媒体地址或 FileLabel 对象。
 * @apiBody {Object} input.option 检测参数。
 * @apiBody {String} [input.option.operation=detect] 图片操作。
 * @apiBody {String} input.option.profile 非空检测 profile；已知值 fire-smoke、uav-fire-smoke 由 Java SpecializedDetectionProfile 枚举汇总，实际支持情况由 Python 判断，不支持返回对应状态（当前 code=1 / HTTP 400）。
 * @apiBody {Number} [input.option.confidence] 阈值 [0,1]，省略时使用 Python 配置。
 * @apiBody {Boolean} [input.option.sliced=false] 仅 uav-fire-smoke 可启用。
 * @apiParamExample {json} 图片检测请求:
 * {
 *   "channel": "inspection-001",
 *   "input": {
 *     "files": ["UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx"],
 *     "option": {"profile": "fire-smoke", "confidence": 0.4}
 *   }
 * }
 * @apiSuccess {Number} sn Java 单元请求关联号。
 * @apiSuccess {String} unit SpecializedDetection。
 * @apiSuccess {Number} timestamp Java 接收结果的 Unix 毫秒时间。
 * @apiSuccess {String} response 可为空。
 * @apiSuccess {String} thought 可为空。
 * @apiSuccess {Object} resultPayload 检测结果，坐标基于方向校正后的原图像素。
 * @apiSuccess {String} resultPayload.profile 本次请求的 profile。
 * @apiSuccess {Number} resultPayload.width 图片宽度，单位：像素。
 * @apiSuccess {Number} resultPayload.height 图片高度，单位：像素。
 * @apiSuccess {Object[]} resultPayload.refs 检测区域，未命中时为 []；命中时包含 content（fire / smoke）、classId、boxes 和 points。
 * @apiSuccess {Object[]} resultPayload.refs.boxes 检测框，字段为 x1、y1、x2、y2、confidence；左上角为 (x1,y1)，右下角为 (x2,y2)。
 * @apiSuccess {Object} performance 实际 token 与推理耗时；零 token 不估算。
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
 * @apiError {Object} error state 为 HTTP 状态码，code 为 Unit 状态码，另含 reason、message。400 参数或不支持的 profile；401 认证；403 归属；404 文件不存在；413 请求体过大；429 忙碌；503 未就绪；422 文件错误；502 单元错误；504 超时。
 * @apiErrorExample {json} Python 不支持请求的 profile:
 * HTTP/1.1 400 Bad Request
 * {"error":{"state":400,"code":1,"reason":"UNIT_REJECTED","message":"Specialized detection failed (code 1)"}}
 * @apiErrorExample {json} 忙碌:
 * HTTP/1.1 429 Too Many Requests
 * {"error":{"state":429,"code":7,"reason":"UNIT_REJECTED","message":"Specialized detection failed (code 7)"}}
 */

/**
 * @api {post} /specialized-detection/stream/:token 专项视频后台会话
 * @apiName SpecializedDetectionStream
 * @apiGroup SpecializedDetection
 * @apiVersion 1.0.0
 * @apiDescription start 创建后台会话，不保证媒体打开或首帧就绪；使用返回的 streamId 调用 status 查询、stop 停止。三个操作均使用本端点，控制时不传 profile、callbackUrl 或媒体源，不支持 update。
 *                 <p>部署无需在 <code>aigc.properties</code> 或 <code>dispatcher.properties</code> 中添加专项配置，也无需声明 profiles。Java 按可用预算分配请求，由 Python 判断 profile 支持情况；不支持时返回对应状态，当前为 <code>code=1</code> / HTTP 400。</p>
 *                 <p>默认每个 Python 实例可预留两个后台会话；数据池 8 线程、控制池 2 线程，两者各有 64 个排队名额。start 的 service 期限为 180 秒、dispatcher 等待 190 秒；status/stop 分别为 30 秒和 40 秒。预算为 Java 准入限制，Python 的实际容量和就绪状态仍以请求结果为准。</p>
 *                 <p>Python 直接向 <code>callbackUrl</code> POST PNG 二进制，<code>Content-Type: image/png</code>，使用 <code>X-VLM-Event-Id</code> 去重。检测元数据嵌入 PNG 的 <code>vlm_meta</code> iTXt，含 <code>eventId,streamId,unit,profile,sequence,timestamp,offsetSeconds,resultPayload,response,performance</code>。无命中不回调，回调失败不停止检测。</p>
 *                 <p>status/stop 始终路由到原 service 和 Python 连接，验证域和联系人。<code>starting/running/reconnecting/stopping</code> 均占用容量，终态每实例保留最近 20 条路由；重启后不恢复会话。start 超时不证明后台已停止，不自动重试，立即重复提交可能创建重复会话。</p>
 * @apiParam {String} token AIGC token。
 * @apiHeader {String} Content-Type application/json
 * @apiHeader {String} [x-baize-api-device] 获取令牌时使用的客户端设备名。
 * @apiHeader {String} [x-baize-api-platform] 获取令牌时使用的客户端平台。
 * @apiBody {String} channel 调用者所属频道。
 * @apiBody {Object} input 专项输入。
 * @apiBody {String} [input.unit=SpecializedDetection] 固定能力名。
 * @apiBody {String[]} input.files start 为一个视频文件码或 RTSP(S) 地址，status/stop 为 []。
 * @apiBody {Object} input.option start 参数或控制参数。
 * @apiBody {String="start","status","stop"} input.option.operation 操作。
 * @apiBody {String} [input.option.profile] start 必填，已知值 fire-smoke、uav-fire-smoke；实际支持情况由 Python 判断，Java 无需配置 profiles。
 * @apiBody {String} [input.option.callbackUrl] start 必填，HTTP(S) PNG 接收地址。
 * @apiBody {Number} [input.option.confidence] start 阈值 [0,1]。
 * @apiBody {Boolean} [input.option.sliced=false] start 可选，仅 uav-fire-smoke 可启用。
 * @apiBody {Number} [input.option.intervalSeconds] start 回调间隔 [0,86400] 秒，省略时使用 Python 配置。
 * @apiBody {Number} [input.option.inferenceFps] start 抽帧率 [0.1,60]，省略时使用 Python 配置。
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
 * @apiSuccess {String} resultPayload.streamId 后续查询和停止使用的会话标识。
 * @apiSuccess {String="starting","running","reconnecting","stopping","stopped","failed"} resultPayload.status 会话状态；只有 stopped、failed 为终态，stopping 仍占用预算。
 * @apiSuccess {String} response 可为空。
 * @apiSuccess {String} thought 可为空。
 * @apiSuccess {Object} performance 启动与控制结果，不冒充逐帧计量。
 * @apiSuccess {Number} sn Java 单元调用关联号。
 * @apiSuccess {String} unit SpecializedDetection。
 * @apiSuccess {Number} timestamp Java 接收结果的 Unix 毫秒时间。
 * @apiError {Object} error 400 参数或不支持的 profile；401 认证；403 归属；404 不存在；413 请求体超过 64 KiB；429 忙碌；503 未就绪；422 文件错误；502 单元错误；504 超时（远端可能仍运行）。含 state/code/reason/message。
 * @apiErrorExample {json} 启动请求超时:
 * HTTP/1.1 504 Gateway Timeout
 * {"error":{"state":504,"code":31,"reason":"DETECTION_TIMEOUT","message":"Detection request timed out; remote execution may still be running"}}
 */
