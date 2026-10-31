/**
 * @apiDefine SpecializedDetection 专项检测
 */

/**
 * @api {post} /specialized-detection/base/:token 专项图片检测
 * @apiName SpecializedDetectionImage
 * @apiGroup SpecializedDetection
 * @apiVersion 1.0.0
 * @apiDescription 通过 SpecializedDetection Unit 检测图片中的火焰和烟雾。成功直接返回业务 JSON；空文本、零 token 和空 refs 均为合法成功。完整协议、PNG 回调与预算说明见源文档 SpecializedDetection.md。
 * @apiParam {String} token AIGC token。
 * @apiBody {String} channel 调用者所属频道；不占用聊天 processing，不写历史。
 * @apiBody {Object} input 专项输入，不需要 content。
 * @apiBody {String} [input.unit=SpecializedDetection] 固定能力名。
 * @apiBody {String[]} input.files 一个上传完成的 64 位文件码；不接受本地路径、HTTP 媒体地址或 FileLabel 对象。
 * @apiBody {Object} input.option 检测参数。
 * @apiBody {String} [input.option.operation=detect] 图片操作。
 * @apiBody {String} input.option.profile 非空检测 profile；已知值 fire-smoke、uav-fire-smoke 由 Java SpecializedDetectionProfile 枚举汇总，实际支持情况由 Python 判断，不支持返回对应状态（当前 code=1 / HTTP 400）。
 * @apiBody {Number} [input.option.confidence] 阈值 [0,1]，省略时使用 Python 配置。
 * @apiBody {Boolean} [input.option.sliced=false] 仅 uav-fire-smoke 可启用。
 * @apiSuccess {Number} sn Java 单元请求关联号。
 * @apiSuccess {String} unit SpecializedDetection。
 * @apiSuccess {Number} timestamp Java 接收结果的 Unix 毫秒时间。
 * @apiSuccess {String} response 可为空。
 * @apiSuccess {String} thought 可为空。
 * @apiSuccess {Object} resultPayload profile、width、height、refs。
 * @apiSuccess {Object} performance 实际 token 与推理耗时；零 token 不估算。
 * @apiError {Object} error state 为 HTTP 状态码，code 为 Unit 状态码，另含 reason、message。
 * @apiErrorExample {json} 忙碌:
 * HTTP/1.1 429 Too Many Requests
 * {"error":{"state":429,"code":7,"reason":"UNIT_REJECTED","message":"Specialized detection failed (code 7)"}}
 */

/**
 * @api {post} /specialized-detection/stream/:token 专项视频后台会话
 * @apiName SpecializedDetectionStream
 * @apiGroup SpecializedDetection
 * @apiVersion 1.0.0
 * @apiDescription start 仅创建后台会话，不保证首帧就绪；Python 直接向客户 callbackUrl POST image/png，X-VLM-Event-Id 用于去重，检测元数据嵌入 PNG 的 vlm_meta iTXt。status/stop 始终路由到原实例，验证域和联系人。stopping 仍占用容量，终态每实例保留最近 20 条；重启后不恢复会话，不自动重试 start。配置为 Java 预算，Python 忙碌/未就绪结果为最终权威。
 * @apiParam {String} token AIGC token。
 * @apiBody {String} channel 调用者所属频道。
 * @apiBody {Object} input 专项输入。
 * @apiBody {String[]} input.files start 为一个视频文件码或 RTSP(S) 地址，status/stop 为 []。
 * @apiBody {Object} input.option start 参数或控制参数。
 * @apiBody {String="start","status","stop"} input.option.operation 操作。
 * @apiBody {String} [input.option.profile] start 必填，已知值 fire-smoke、uav-fire-smoke；实际支持情况由 Python 判断，Java 无需配置 profiles。
 * @apiBody {String} [input.option.callbackUrl] start 必填，HTTP(S) PNG 接收地址。
 * @apiBody {Number} [input.option.confidence] start 阈值 [0,1]。
 * @apiBody {Boolean} [input.option.sliced=false] start 可选，仅无人机 profile 可启用。
 * @apiBody {Number} [input.option.intervalSeconds] start 回调间隔 [0,86400] 秒。
 * @apiBody {Number} [input.option.inferenceFps] start 抽帧率 [0.1,60]。
 * @apiBody {String} [input.option.streamId] status/stop 必填；控制只允许 operation 和 streamId。
 * @apiSuccess {Object} resultPayload 会话快照，含 streamId、status、profile、统计数据等。
 * @apiSuccess {String} response 可为空。
 * @apiSuccess {String} thought 可为空。
 * @apiSuccess {Object} performance 启动与控制结果，不冒充逐帧计量。
 * @apiSuccess {Number} sn Java 单元调用关联号。
 * @apiSuccess {String} unit SpecializedDetection。
 * @apiSuccess {Number} timestamp Java 接收结果的 Unix 毫秒时间。
 * @apiError {Object} error 400 参数；404 不存在；429 忙碌；503 未就绪；422 文件错误；502 单元错误；504 超时（远端可能仍运行）。含 state/code/reason/message。
 */
