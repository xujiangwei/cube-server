/**
 * @apiDefine Forest 森林场景
 */


/**
 * @api {post} /aigc/forest/detect/:token 检测树冠
 * @apiName ForestDetect
 * @apiGroup Forest
 * @apiVersion 1.0.0
 *
 * @apiDescription 检测单张图片中的树冠，返回树冠数量和矩形位置，可用于树冠计数和图片标注。
 *                 <p>调用前先使用<a href="#api-File_Processing-上传文件数据">上传文件数据</a>接口上传 JPEG 或 PNG 图片，再使用<a href="#api-File_Processing-查找已上传的文件">查找已上传的文件</a>接口确认文件可用，最后将上传得到的文件码填入 <code>fileCode</code>。文件须属于当前令牌对应的用户和域。</p>
 *                 <p>单张图片默认不超过 <code>20 MiB</code>、<code>2000 万像素</code>；实际限制和默认阈值可通过“查询森林能力”接口获取。请求体仅支持 <code>fileCode</code> 和 <code>scoreThreshold</code>，图片通过文件接口单独上传。</p>
 *                 <p>首次接入建议省略 <code>scoreThreshold</code>，使用服务默认值。需要减少低置信度结果时提高阈值，需要保留更多候选树冠时降低阈值。比较不同图片的检测数量时，建议使用相同阈值。</p>
 *                 <p>该接口在本次请求中返回检测结果，客户端应在等待期间显示处理状态，避免重复提交。默认检测超时为 <code>60 秒</code>，客户端和代理的请求超时应留出余量。</p>
 *                 <p>读取 <code>data.resultPayload.count</code> 显示树冠数量，遍历 <code>data.resultPayload.refs[].boxes[]</code> 绘制检测框。坐标以按 EXIF 方向校正后的图片为准，单位为像素，左上角为原点，X 向右、Y 向下；缩放显示时按返回的 <code>width</code>、<code>height</code> 换算坐标。<code>count=0</code> 且 <code>refs=[]</code> 表示本次未检出树冠，仍是成功结果。</p>
 *
 * @apiParam {String} token 授权令牌。
 *
 * @apiHeader {String} Content-Type application/json
 * @apiHeader {String} [x-baize-api-device] 客户端设备名，使用获取令牌时的设备信息。
 * @apiHeader {String} [x-baize-api-platform] 客户端平台，使用获取令牌时的平台信息。
 *
 * @apiBody {String} fileCode 已上传图片的文件码，由 64 位大小写字母或数字组成。使用上传接口返回的实际值。
 * @apiBody {Number{0-1}} [scoreThreshold] 树冠置信度阈值，取值范围为 <code>0</code> 至 <code>1</code>（含边界）。省略时使用服务默认值，通常为 <code>0.3</code>。必须传数值类型。
 *
 * @apiExample {url} 请求地址
 *     http://localhost:7010/aigc/forest/detect/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *
 * @apiParamExample {json} 使用默认阈值
 *     {
 *         "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx"
 *     }
 *
 * @apiParamExample {json} 指定置信度阈值
 *     {
 *         "fileCode": "UxUiHFebTvyQusIRBQCpANsMdYfWGlHGwVxHlbzAxepzvKBXapMJJZcNkOXiZpEx",
 *         "scoreThreshold": 0.5
 *     }
 *
 * @apiSuccess {Number} code 状态码，<code>0</code> 表示成功。
 * @apiSuccess {Object} data 检测结果。
 * @apiSuccess {String} data.response 检测结果的文字说明，可直接展示。
 * @apiSuccess {String} data.thought 保留字段，当前为空字符串。
 * @apiSuccess {Object} data.resultPayload 树冠数量和位置数据。
 * @apiSuccess {Number} data.resultPayload.width 方向校正后的图片宽度，单位：像素。
 * @apiSuccess {Number} data.resultPayload.height 方向校正后的图片高度，单位：像素。
 * @apiSuccess {Number} data.resultPayload.count 检测到的树冠数量，等于所有 <code>refs[].boxes</code> 中检测框的总数。
 * @apiSuccess {Object[]} data.resultPayload.refs 树冠区域列表。未检出树冠时为空数组。
 * @apiSuccess {String} data.resultPayload.refs.content 区域类别，当前为 <code>tree</code>。
 * @apiSuccess {Object[]} data.resultPayload.refs.boxes 该类别的矩形检测框列表。每个检测框对应一个树冠。
 * @apiSuccess {Number} data.resultPayload.refs.boxes.x1 检测框左上角 X 坐标。
 * @apiSuccess {Number} data.resultPayload.refs.boxes.y1 检测框左上角 Y 坐标。
 * @apiSuccess {Number} data.resultPayload.refs.boxes.x2 检测框右下角 X 坐标。框宽为 <code>x2-x1</code>。
 * @apiSuccess {Number} data.resultPayload.refs.boxes.y2 检测框右下角 Y 坐标。框高为 <code>y2-y1</code>。
 * @apiSuccess {Array} data.resultPayload.refs.points 保留字段，当前为空数组。
 * @apiSuccess {Object} data.performance 本次检测的耗时等信息。
 * @apiSuccess {Number} data.performance.inputTokens 当前为 <code>0</code>。
 * @apiSuccess {Number} data.performance.outputTokens 当前为 <code>0</code>。
 * @apiSuccess {String} data.performance.elapsed 检测处理耗时，单位：毫秒，以字符串形式返回。
 *
 * @apiSuccessExample {json} 检出树冠
 *     HTTP/1.1 200 OK
 *     {
 *         "code": 0,
 *         "data": {
 *             "response": "检测到 2 个树冠",
 *             "thought": "",
 *             "resultPayload": {
 *                 "width": 2048,
 *                 "height": 1536,
 *                 "count": 2,
 *                 "refs": [{
 *                     "content": "tree",
 *                     "boxes": [{
 *                         "x1": 120.5,
 *                         "y1": 96.0,
 *                         "x2": 308.0,
 *                         "y2": 284.5
 *                     }, {
 *                         "x1": 460.0,
 *                         "y1": 320.5,
 *                         "x2": 650.5,
 *                         "y2": 512.0
 *                     }],
 *                     "points": []
 *                 }]
 *             },
 *             "performance": {
 *                 "inputTokens": 0,
 *                 "outputTokens": 0,
 *                 "elapsed": "1280"
 *             }
 *         }
 *     }
 *
 * @apiSuccessExample {json} 未检出树冠
 *     HTTP/1.1 200 OK
 *     {
 *         "code": 0,
 *         "data": {
 *             "response": "检测到 0 个树冠",
 *             "thought": "",
 *             "resultPayload": {
 *                 "width": 2048,
 *                 "height": 1536,
 *                 "count": 0,
 *                 "refs": []
 *             },
 *             "performance": {
 *                 "inputTokens": 0,
 *                 "outputTokens": 0,
 *                 "elapsed": "1250"
 *             }
 *         }
 *     }
 *
 * @apiError (错误响应) {Number} code 非零状态码，表示本次检测失败。
 * @apiError (错误响应) {Object} data 错误信息。
 * @apiError (错误响应) {String} data.message 错误原因。
 * @apiError (错误响应) {Number} [data.httpStatus] HTTP 状态码，部分错误返回此字段。客户端应以响应的 HTTP 状态码为准。
 *
 * @apiError 400 参数错误。检查文件码、JSON 格式和阈值范围，删除请求体中不支持的字段；图片须为 JPEG 或 PNG。
 * @apiError 401 令牌或设备信息无效。检查令牌及设备请求头。
 * @apiError 403 无权访问图片。使用当前用户上传的图片。
 * @apiError 404 图片或检测能力不存在。先确认文件已上传完成，再确认服务支持树冠检测。
 * @apiError 405 请求方法错误，检测接口应使用 POST。
 * @apiError 413 图片字节数、像素数或请求体超过限制。按能力接口返回的限制压缩或缩小图片后重新上传；检测请求体上限为 <code>16 KiB</code>。
 * @apiError 422 无法读取或解码图片。检查文件是否完整、能否正常打开，必要时重新导出为 JPEG 或 PNG 后上传。
 * @apiError 429 服务繁忙。降低并发，间隔一段时间后重试。
 * @apiError 502 检测失败或结果异常。可稍后重试，持续失败时联系服务提供方。
 * @apiError 503 森林服务或检测能力暂不可用。通过能力接口确认 <code>ready</code> 和 <code>availableCapacity</code> 后再提交。
 * @apiError 504 检测超时。结束本次等待并提示用户稍后重试，避免立即重复提交同一图片。
 *
 * @apiErrorExample {json} 参数错误
 *     HTTP/1.1 400 Bad Request
 *     {
 *         "code": 1,
 *         "data": {
 *             "message": "scoreThreshold must be between 0 and 1",
 *             "httpStatus": 400
 *         }
 *     }
 *
 * @apiErrorExample {json} 服务繁忙
 *     HTTP/1.1 429 Too Many Requests
 *     {
 *         "code": 7,
 *         "data": {
 *             "message": "Forest request queue is full or stopping"
 *         }
 *     }
 */


/**
 * @api {get} /aigc/forest/capability/:token 查询森林能力
 * @apiName ForestCapability
 * @apiGroup Forest
 * @apiVersion 1.0.0
 *
 * @apiDescription 查询森林场景当前支持的操作、图片限制、默认阈值和服务可用状态。建议在进入检测页面或批量提交图片前调用，并据此校验待上传的图片。
 *                 <p>当 <code>ready=true</code> 且 <code>availableCapacity&gt;0</code> 时，可提交检测请求；<code>ready=false</code> 时提示服务暂不可用；<code>ready=true</code> 且 <code>availableCapacity=0</code> 时提示服务繁忙并稍后查询。</p>
 *                 <p><code>online=true</code> 仅表示存在在线服务实例，是否可提交检测应结合 <code>ready</code> 和 <code>availableCapacity</code> 判断。能力查询成功不代表检测一定成功，可用容量可能变化，客户端仍需处理检测接口的 <code>429</code>、<code>503</code> 等应答。</p>
 *                 <p>本接口使用 GET，无需请求体。返回 <code>code=0</code> 表示查询成功，即使此时没有可用检测服务也可能查询成功。</p>
 *
 * @apiParam {String} token 授权令牌。
 *
 * @apiHeader {String} [x-baize-api-device] 客户端设备名，使用获取令牌时的设备信息。
 * @apiHeader {String} [x-baize-api-platform] 客户端平台，使用获取令牌时的平台信息。
 *
 * @apiExample {url} 请求示例
 *     http://localhost:7010/aigc/forest/capability/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *
 * @apiSuccess {Number} code 状态码，<code>0</code> 表示查询成功。
 * @apiSuccess {Object} data 能力信息。
 * @apiSuccess {String} data.plugin 能力名称，当前为 <code>OmniForest</code>。
 * @apiSuccess {String} data.unit 服务单元名称，当前为 <code>OmniForest</code>。
 * @apiSuccess {String[]} data.actions 支持的操作，当前为 <code>["detection"]</code>，表示树冠检测。
 * @apiSuccess {String[]} data.formats 支持的图片格式，当前为 <code>["jpeg","png"]</code>。
 * @apiSuccess {Number} data.maxFiles 单次检测允许的图片数量，当前为 <code>1</code>。多张图片应分别提交。
 * @apiSuccess {Number} data.maxBytes 单张图片的字节数上限，默认 <code>20971520</code>（20 MiB）。
 * @apiSuccess {Number} data.maxPixels 单张图片的像素数上限，即宽度乘以高度，默认 <code>20000000</code>。
 * @apiSuccess {Number} data.scoreThreshold 检测请求省略 <code>scoreThreshold</code> 时使用的阈值，默认 <code>0.3</code>。
 * @apiSuccess {Boolean} data.online 是否存在在线服务实例。
 * @apiSuccess {Number} data.providers 在线服务实例数。
 * @apiSuccess {Boolean} data.ready 是否存在已就绪的检测服务。为 <code>true</code> 时仍需检查可用容量。
 * @apiSuccess {Number} data.readyProviders 已就绪的服务实例数，包含正在处理请求的实例。
 * @apiSuccess {Number} data.availableCapacity 当前可用的检测容量，<code>0</code> 表示暂时没有空闲容量。
 * @apiSuccess {Object[]} data.providerStatuses 服务实例的状态明细，包含 <code>contactId</code>、<code>domain</code>、<code>state</code>、<code>stage</code>、<code>ready</code>、<code>error</code> 及容量等信息，可用于展示状态或排查不可用原因。没有在线实例时为空数组。
 * @apiSuccess {String} data.readiness 状态信息标识，当前为 <code>reported</code>。
 * @apiSuccess {String} data.mode 检测模式标识，当前为 <code>tile</code>。调用检测接口时无需传入。
 *
 * @apiSuccessExample {json} 检测服务可用
 *     HTTP/1.1 200 OK
 *     {
 *         "code": 0,
 *         "data": {
 *             "plugin": "OmniForest",
 *             "unit": "OmniForest",
 *             "actions": ["detection"],
 *             "formats": ["jpeg", "png"],
 *             "maxFiles": 1,
 *             "maxBytes": 20971520,
 *             "maxPixels": 20000000,
 *             "scoreThreshold": 0.3,
 *             "online": true,
 *             "providers": 1,
 *             "ready": true,
 *             "readyProviders": 1,
 *             "availableCapacity": 2,
 *             "providerStatuses": [{
 *                 "unit": "OmniForest",
 *                 "plugin": "omniforest",
 *                 "contactId": 22003303,
 *                 "domain": "default_domain",
 *                 "state": "ready",
 *                 "stage": "ready",
 *                 "ready": true,
 *                 "error": "",
 *                 "dummy": false,
 *                 "actions": ["detection"],
 *                 "maxConcurrency": 2,
 *                 "inFlight": 0,
 *                 "activeRequests": [],
 *                 "availableCapacity": 2,
 *                 "updatedAt": 1792972800000
 *             }],
 *             "readiness": "reported",
 *             "mode": "tile"
 *         }
 *     }
 *
 * @apiError (错误响应) {Number} code 非零状态码，表示查询失败。
 * @apiError (错误响应) {Object} data 错误信息。
 * @apiError (错误响应) {String} data.message 错误原因。
 *
 * @apiError 401 令牌或设备信息无效。检查令牌及设备请求头。
 * @apiError 405 请求方法错误，能力接口应使用 GET。
 * @apiError 502 查询失败，可稍后重试。
 * @apiError 503 森林服务暂不可用，可稍后重试或联系服务提供方。
 *
 * @apiErrorExample {json} 森林服务暂不可用
 *     HTTP/1.1 503 Service Unavailable
 *     {
 *         "code": 30,
 *         "data": {
 *             "message": "Forest capability unavailable"
 *         }
 *     }
 */
