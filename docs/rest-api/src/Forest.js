/**
 * @apiDefine Forest 森林场景
 */

/**
 * @api {post} /aigc/forest/detect/:token 检测树冠
 * @apiName ForestDetect
 * @apiGroup Forest
 * @apiVersion 1.0.0
 * @apiDescription dispatcher 业务接口，由 service-forest 调用 Python OmniForest。先通过既有文件接口上传单张 JPEG/PNG，再提交文件码。文件须属于当前令牌所在域和当前用户。默认限制 20 MiB、2000 万像素，检测预算 60 秒。返回坐标针对 EXIF 方向校正后的图像；零个检测框同样成功。首期没有历史记录和异步任务。
 * @apiParam {String} token Cube 用户令牌，规则与现有 AIGC 接口一致。
 * @apiHeader {String} Content-Type application/json
 * @apiHeader {String} [x-baize-api-device] 客户端设备名，沿用既有令牌设备校验规则。
 * @apiHeader {String} [x-baize-api-platform] 客户端平台。
 * @apiBody {String} fileCode 已上传图片的 64 位文件码。
 * @apiBody {Number{0-1}} [scoreThreshold=0.3] 树冠置信度阈值。
 * @apiExample {json} 请求体:
 * {"fileCode":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","scoreThreshold":0.3}
 * @apiSuccess {Number} code 0 表示成功。
 * @apiSuccess {Object} data 检测结果，包含 response、thought、resultPayload、performance。
 * @apiSuccess {String} data.response 检测结果文字。
 * @apiSuccess {String} data.thought 保留字段，当前为空。
 * @apiSuccess {Object} data.resultPayload 结构化检测结果。
 * @apiSuccess {Number} data.resultPayload.width 校正方向后的图片宽度，像素。
 * @apiSuccess {Number} data.resultPayload.height 校正方向后的图片高度，像素。
 * @apiSuccess {Number} data.resultPayload.count 检测框总数。
 * @apiSuccess {Object[]} data.resultPayload.refs 树冠区域列表；空结果为 []。
 * @apiSuccess {Object} data.performance Python 处理用时等信息，elapsed 为毫秒字符串。
 * @apiSuccessExample {json} 成功:
 * {"code":0,"data":{"response":"检测到 1 个树冠","thought":"","resultPayload":{"width":100,"height":80,"count":1,"refs":[{"content":"tree","boxes":[{"x1":1,"y1":2,"x2":50,"y2":60}],"points":[]}]},"performance":{"inputTokens":0,"outputTokens":0,"elapsed":"120"}}}
 * @apiError (错误) {Number} code Cube AIGC 状态码。
 * @apiError (错误) {Object} data 错误说明，含 message，部分服务错误附带 httpStatus。
 * @apiErrorExample {json} 模型繁忙 (HTTP 429):
 * {"code":7,"data":{"message":"OmniForest detection failed","httpStatus":429}}
 * @apiError (错误) {String} data.message 错误原因。HTTP 状态：400 参数或 JSON 错误，401 令牌/设备无效，403 文件无权访问，404 文件/能力不存在，405 方法不匹配（detect 只接受 POST、capability 只接受 GET），413 文件或请求体过大，422 图片解码失败，429 模型忙/队列满，502 推理或结果错误，503 模块/能力不可用或模型未就绪，504 超时。HTTP 超时不会强制中断底层推理。
 * @apiErrorExample {json} 方法不匹配 (HTTP 405):
 * {"code":1,"data":{"message":"Use POST for detection"}}
 */

/**
 * @api {get} /aigc/forest/capability/:token 查询森林能力
 * @apiName ForestCapability
 * @apiGroup Forest
 * @apiVersion 1.0.0
 * @apiDescription 查询已加载森林模块的输入限制、连接状态、模型就绪状态和可用容量。Python 使用 warmup.png 完成真实推理后才报告 ready；状态每 10 秒同步，超过 30 秒未更新视为未知，不再调度。没有模型节点时也可返回能力信息。online 仅表示节点已注册并连接。
 * @apiParam {String} token Cube 用户令牌。
 * @apiHeader {String} [x-baize-api-device] 客户端设备名。
 * @apiHeader {String} [x-baize-api-platform] 客户端平台。
 * @apiSuccess {Number} code 0 表示成功。
 * @apiSuccess {Object} data 能力描述。
 * @apiSuccess {String} data.plugin OmniForest。
 * @apiSuccess {String} data.unit 固定为 forest-detection。
 * @apiSuccess {Boolean} data.online 是否存在已连接提供者。
 * @apiSuccess {Number} data.providers 当前 service 下已连接提供者数。
 * @apiSuccess {String} data.readiness 固定为 reported。
 * @apiSuccess {Boolean} data.ready 是否至少有一个完成 warmup 且状态有效的就绪实例。
 * @apiSuccess {Number} data.readyProviders 当前就绪实例数，包含繁忙实例。
 * @apiSuccess {Number} data.availableCapacity 扣除 Python 在途请求和 Java 预留后的剩余推理容量，同一请求不重复扣除。
 * @apiSuccess {Object[]} data.providerStatuses 各连接实例的 contactId、domain、state、stage、ready、error、updatedAt 和容量详情。updatedAt 是 Java 接收状态的 Unix 毫秒时间；未知或过期时为 0。state 为 registered、initializing、ready、failed、stopping、stopped 或 unknown。
 * @apiSuccess {String[]} data.actions 支持的业务动作，当前仅 detection。Java 调用插件时显式传 option.action=detection；缺省时也执行检测，未知动作返回参数错误码 1。树冠分割暂未实现。
 * @apiSuccess {String[]} data.formats 允许的图片格式，当前为 jpeg、png。
 * @apiSuccess {Number} data.maxFiles 单次请求允许的文件数，固定为 1。
 * @apiSuccess {Number} data.maxBytes 单张图片的字节上限，默认 20971520（20 MiB）。
 * @apiSuccess {Number} data.maxPixels 单张图片的像素上限，默认 20000000（2000 万像素）。
 * @apiSuccess {Number} data.scoreThreshold 默认树冠置信度阈值，默认 0.3。
 * @apiSuccess {String} data.mode 推理模式，当前固定为 tile。
 * @apiSuccessExample {json} 没有提供者时:
 * {"code":0,"data":{"plugin":"OmniForest","unit":"forest-detection","online":false,"providers":0,"ready":false,"readyProviders":0,"availableCapacity":0,"providerStatuses":[],"readiness":"reported","actions":["detection"],"formats":["jpeg","png"],"maxFiles":1,"maxBytes":20971520,"maxPixels":20000000,"scoreThreshold":0.3,"mode":"tile"}}
 */
