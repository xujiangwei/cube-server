/**
 * @apiDefine Data_Management 数据管理
 */


/**
 * @api {post} /aigc/channel/:token 申请对话频道
 * @apiName 申请对话频道
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 创建对话会话，以频道码隔离不同会话的上下文。提供参与人名称 <code>participant</code>，可按已开通能力指定 <code>unit</code>。
 *                 <p>保存返回的 <code>code</code>，在聊天、多模态或知识库问答请求中作为频道码使用；该字段是字符串频道标识。频道空闲 30 分钟后会失效，后续请求提示频道不可用时重新申请。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {String} participant 对话参与人名称。
 * @apiBody {String} [unit] 指定该频道使用的大模型单元。默认值：<code>Baize</code>。
 * 
 * @apiParamExample {json} 请求示例
 *     {
 *         "participant": "来自火星的星星"
 *     }
 * 
 * @apiSuccess {String} code 频道代码。
 * @apiSuccess {Number} creationTime 创建时间戳。
 * @apiSuccess {String} participant 参与人名称。
 * @apiSuccess {String} language 频道使用的语言标识：<code>cn</code> 中文、<code>en</code> 英文。
 * @apiSuccess {Object} [authToken] 频道关联的鉴权令牌数据，仅在该频道带令牌时返回。
 * @apiSuccess {String} authToken.code 令牌码。
 * @apiSuccess {String} authToken.domain 令牌所属域。
 * @apiSuccess {String} authToken.appKey 令牌使用的应用标识。
 * @apiSuccess {Number} authToken.cid 令牌关联的触点 ID。
 * @apiSuccess {Number} authToken.issue 令牌颁发时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} authToken.expiry 令牌到期时间，Unix 毫秒时间戳。
 * @apiSuccess {Boolean} authToken.ferry 是否为中转令牌。
 * @apiSuccess {Object} [authToken.description] 服务访问描述。
 * @apiSuccess {String} authToken.description.address 服务地址。
 * @apiSuccess {Number} authToken.description.port 服务端口。
 * @apiSuccess {Object} authToken.description.primaryContent 按能力名索引的访问配置；其他能力可扩展自己的配置键。
 * @apiSuccess {Object} [authToken.description.primaryContent.FileStorage] 文件存储访问配置。
 * @apiSuccess {String} [authToken.description.primaryContent.FileStorage.fileURL] HTTP 文件接口基址。
 * @apiSuccess {String} [authToken.description.primaryContent.FileStorage.fileSecureURL] HTTPS 文件接口基址。
 * @apiSuccess {Object} [authToken.description.primaryContent.MultipointComm] 实时通信配置。
 * @apiSuccess {Object[]} [authToken.description.primaryContent.MultipointComm.iceServers] ICE / TURN 服务器列表。
 * @apiSuccess {String/String[]} authToken.description.primaryContent.MultipointComm.iceServers.urls 服务器 URL 或 URL 列表。
 * @apiSuccess {String} [authToken.description.primaryContent.MultipointComm.iceServers.username] TURN 用户名。
 * @apiSuccess {String} [authToken.description.primaryContent.MultipointComm.iceServers.credential] TURN 凭据。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "code": "KLKVaPXDZCPRjMPx",
 *         "creationTime": 1680139631664,
 *         "participant": "来自火星的星星",
 *         "language": "cn"
 *     }
 * 
 * @apiError 400 服务节点访问错误。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 * @apiError 404 必填参数错误。
 * @apiError 406 请求过于频繁，被限流。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/aigc/channel/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "participant": "来自火星的星星"
 *     }
 */

/**
 * @api {get} /aigc/channel/:token 查询对话频道
 * @apiName 查询对话频道
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 使用申请频道时返回的字符串频道码查询当前会话，填入查询参数 <code>cc</code>。
 *                 <p>读取 <code>processing</code> 判断是否仍有请求正在处理，使用 <code>rounds</code> 展示对话轮数；存在 <code>lastRecord</code> 时可展示最近一次生成内容。HTTP 404 时检查频道码，失效后重新申请。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiQuery {String} cc 频道码。
 * 
 * @apiSuccess {String} code 频道代码。
 * @apiSuccess {Number} creationTime 创建时间戳。
 * @apiSuccess {String} participant 参与人名称。
 * @apiSuccess {String} language 频道使用的语言标识。
 * 
 * @apiError 400 未提供频道码。
 * @apiError 401 Token 无效。
 * @apiError 404 频道不存在或已超时销毁。
 *
 * @apiSuccessExample {json} 空闲频道：尚无对话记录
 *     HTTP/1.1 200 OK
 *     {
 *         "code": "KLKVaPXDZCPRjMPx",
 *         "creationTime": 1680139631664,
 *         "participant": "来自火星的星星",
 *         "language": "cn",
 *         "activeTimestamp": 1793322000000,
 *         "rounds": 0,
 *         "processing": false,
 *         "totalQueryWords": 0,
 *         "totalAnswerWords": 0,
 *         "lastMetaSn": 0
 *     }
 * @apiSuccess {Number} activeTimestamp 最近活动时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} rounds 对话轮数。
 * @apiSuccess {Boolean} processing 是否正在处理请求。
 * @apiSuccess {Number} totalQueryWords 累计提问字数。
 * @apiSuccess {Number} totalAnswerWords 累计回答字数。
 * @apiSuccess {Number} lastMetaSn 最近一次请求的关联序号。
 * @apiSuccess {Object} [lastRecord] 最近一次生成记录；尚无记录时不返回。
 *
 * @apiParamExample {http} 完整请求示例
 *     GET http://211.157.179.37:7010/aigc/channel/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG?cc=mnxRSaLdPuHEVaKA
 */


/**
 * @api {post} /app/activate/:token User - 创建应用层用户
 * @apiName 创建应用层用户
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 为客户端创建应用层用户，返回可用于后续业务的用户资料。
 *                 <p>提供 appAgent、实际设备信息和客户端渠道 channel；此处 channel 为客户端渠道，与对话频道码含义不同。需要关联已有账号时继续调用“签入应用层用户数据”。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {String} appAgent 客户端代理信息。
 * @apiBody {Object} device 客户端设备信息。
 * @apiBody {String} device.name 设备名称。
 * @apiBody {String} device.platform 设备平台描述。
 * @apiBody {String} channel 客户端渠道。
 * 
 * @apiParamExample {json} 请求示例
 *     {
 *         "appAgent": "Flutter/3 (2602BRT18C/Redmi/dash/dash/dash/mt6991/BP2A.250605.031.A3; Android) REL/36 ea69d9ad-fb36-4793-8ba6-9bd006b13f15",
 *         "device": {
 *             "name": "2602BRT18C",
 *             "platform": "Android"
 *         },
 *         "channel": "baize"
 *     }
 * 
 * @apiSuccess {Number} id 用户ID 。
 * @apiSuccess {String} name 用户名。
 * @apiSuccess {String} displayName 用户显示名。
 * @apiSuccess {String} avatar 用户头像。
 * @apiSuccess {String} phoneNumber 用户手机号码。
 * @apiSuccess {String} email 用户电子邮箱。
 * @apiSuccess {Number} registerTime 注册时间戳。
 * @apiSuccess {String} channel 客户端渠道标识。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "id": 1205560227,
 *         "name": "ME71461815",
 *         "displayName": "未登录",
 *         "avatar": "",
 *         "phoneNumber": "",
 *         "email": "",
 *         "registerTime": 0,
 *         "channel": "baize"
 *     }
 * 
 * @apiError 400 操作发生错误。
 * @apiError 401 令牌无效。
 * @apiError 403 参数错误。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/app/activate/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "appAgent": "Flutter/3 (2602BRT18C/Redmi/dash/dash/dash/mt6991/BP2A.250605.031.A3; Android) REL/36 ea69d9ad-fb36-4793-8ba6-9bd006b13f15",
 *         "device": {
 *             "name": "2602BRT18C",
 *             "platform": "Android"
 *         },
 *         "channel": "baize"
 *     }
 */

/**
 * @api {post} /app/user/:token User - 签入应用层用户数据
 * @apiName 签入应用层用户数据
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 使用账号信息签入应用层用户，并将用户与当前访问令牌关联。使用 <code>POST</code> 提交 JSON，<code>name</code> 是用户唯一识别串，<code>password</code> 使用密码的 MD5 摘要。
 *                 <p>已有用户签入时设置 <code>register=false</code>，注册新用户时设置为 <code>true</code>。检查应答的 <code>valid</code>，确认通过后再使用 <code>user</code> 中的用户资料。设备信息须与实际客户端一致。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {String} name 用户名，确定用户的唯一识别串。
 * @apiBody {String} password 用户密码的散列码，使用 MD5 散列。
 * @apiBody {Object} device 客户端设备信息。
 * @apiBody {String} device.name 设备名称。
 * @apiBody {String} device.platform 设备平台描述。
 * @apiBody {String} [address] 用户地址信息。
 * @apiBody {Boolean} register 是否使用该用户信息注册为新用户。
 * 
 * @apiSuccess {Boolean} valid 是否有效。
 * @apiSuccess {Object} user 签入的用户数据。
 * @apiSuccess {String} user.name 用户名。
 * @apiSuccess {String} user.channel 客户端渠道标识。
 * @apiSuccess {String} user.displayName 用户显示名。
 * @apiSuccess {String} user.avatar 用户头像。
 * @apiSuccess {String} user.phoneNumber 用户手机号码。
 * @apiSuccess {String} user.email 用户电子邮箱。
 * @apiSuccess {Number} user.registerTime 注册时间戳。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "valid": true,
 *         "user": {
 *             "name": "",
 *             "channel": "",
 *             "displayName": "",
 *             "avatar": "",
 *             "phoneNumber": "",
 *             "email": "",
 *             "registerTime": 0 
 *         }
 *     }
 * 
 * @apiError 400 操作发生错误。
 * @apiError 401 令牌无效。
 * @apiError 403 参数错误。
 *
 * @apiParamExample {json} 以已有账号签入
 *     {
 *         "name": "developer@example.com",
 *         "password": "e10adc3949ba59abbe56e057f20f883e",
 *         "device": {
 *             "name": "web-client",
 *             "platform": "Web"
 *         },
 *         "register": false
 *     }
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/app/user/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "name": "developer@example.com",
 *         "password": "e10adc3949ba59abbe56e057f20f883e",
 *         "device": {
 *             "name": "web-client",
 *             "platform": "Web"
 *         },
 *         "register": false
 *     }
 */


/**
 * @api {get} /aigc/event/:token Event - 查询用户事件
 * @apiName 查询用户事件
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 按用户触点 ID、事件名称与起始时间查询活动记录，例如对话、知识库问答、文件上传与删除。时间使用 Unix 毫秒时间戳。
 *                 <p>使用 <code>pi</code> 和 <code>ps</code> 控制分页；<code>pi</code> 从 0 开始。未提供 <code>end</code> 时查询到当前时间；根据返回的总数与列表继续翻页。事件名称区分大小写，取值见参数表。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiQuery {Number} cid 待查询用户的触点ID 。
 * @apiQuery {String} event 指定查询的事件名称：
 *                          <p><span class="default-value">登录时授权会话事件：</span><code>Session</code></p>
 *                          <p><span class="default-value">对话事件：</span><code>Chat</code></p>
 *                          <p><span class="default-value">知识库问答事件：</span><code>Knowledge</code></p>
 *                          <p><span class="default-value">提交文件事件：</span><code>NewFile</code></p>
 *                          <p><span class="default-value">删除文件事件：</span><code>DeleteFile</code></p>
 *                          <p><span class="default-value">导入知识库文档事件：</span><code>ImportKnowledgeDoc</code></p>
 *                          <p><span class="default-value">移除知识库文档事件：</span><code>RemoveKnowledgeDoc</code></p>
 * @apiQuery {Number} start 查询事件的起始时间戳，例如：1692801670646 。
 * @apiQuery {Number} [end] 查询事件的截止时间戳。不设置该参数时，以当前时间作为截止时间戳。
 * @apiQuery {Number} [pi] 查询分页索引（Page Index），接口仅返回指定页索引位置包含的数据条目。不设置该参数时，默认为：<code>0</code> 。
 * @apiQuery {Number} [ps] 查询分页数据条目数（Page Size），接口仅返回该参数指定个数据条目数。不设置该参数时，默认为：<code>10</code> 。
 * 
 * @apiSuccess {Number} contactId 事件的触点ID 。
 * @apiSuccess {String} event 事件名称。
 * @apiSuccess {Number} start 查询的起始时间戳。
 * @apiSuccess {Number} end 查询的截止时间戳。
 * @apiSuccess {Number} pageIndex 查询的当前分页索引。
 * @apiSuccess {Number} pageSize 查询的当前分页数据条目数。
 * @apiSuccess {Number} total 符合查询条件的事件总数量。
 * @apiSuccess {Object[]} list 事件数据列表；data 按事件类型变化。
 * @apiSuccess {Number} list.contactId 事件关联的触点 ID。
 * @apiSuccess {String} list.event 事件名称，如 Session。
 * @apiSuccess {String} list.time 事件发生时间的显示文本。
 * @apiSuccess {Number} list.timestamp 事件发生时间，Unix 毫秒时间戳。
 * @apiSuccess {Object} [list.data] 由事件名称决定的业务键值对象，无统一固定字段。例如 Session 事件可包含 auth（Boolean）、ip（String）、token（String）：{"auth":true,"ip":"211.157.179.37","token":"UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG"}。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "contactId": 22003303,
 *         "event": "Session",
 *         "start": 1692801670646,
 *         "end": 1702951390006,
 *         "pageIndex": 0,
 *         "pageSize": 10,
 *         "total": 2,
 *         "list": [
 *             {
 *                 "contactId": 22003303,
 *                 "event": "Session",
 *                 "time": "2023-12-17 16:27:50",
 *                 "timestamp": 1702801670646,
 *                 "data": {
 *                     "auth": true,
 *                     "ip": "211.157.179.37",
 *                     "token": "UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG"
 *                  }
 *             },
 *             {
 *                 "contactId": 22003303,
 *                 "event": "Session",
 *                 "time": "2023-12-19 10:02:06",
 *                 "timestamp": 1702951326208,
 *                 "data": {
 *                     "auth": true,
 *                     "ip": "211.157.179.37",
 *                     "token": "UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG"
 *                 }
 *             }
 *         ]
 *     }
 * 
 * @apiError 400 查询数据发生错误。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 *
 * @apiParamExample {http} 完整请求示例
 *     GET http://211.157.179.37:7010/aigc/event/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG?cid=22003303&event=Chat&start=1692801670646&pi=0&ps=10
 */


/**
 * @api {get} /aigc/usage/:token Usage - 查询用户用量
 * @apiName 查询用户用量
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 使用用户触点 ID <code>cid</code> 查询其用量统计，适合展示账户使用情况。
 *                 <p>按下方返回字段读取各项统计值，数值为 0 时也应正常展示；查询失败时先检查令牌与用户 ID。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiQuery {Number} cid 待查询用户的触点ID 。
 * 
 * @apiSuccess {Number} contactId 用量所属的触点ID 。
 * @apiSuccess {Number} total 数据总条目数。
 * @apiSuccess {Array} usages 用量列表。
 * @apiSuccess {String} usages.model 用量对应的模型名。
 * @apiSuccess {Number} usages.inputTokens 输入模型的提示词词元数量。
 * @apiSuccess {Number} usages.outputTokens 模型输出的内容词元数量。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "contactId": 22003303,
 *         "total": 3,
 *         "usages": [{
 *             "model": "OmniVL",
 *             "inputTokens": 101081,
 *             "outputTokens": 35089
 *         }, {
 *             "model": "OmniGround",
 *             "inputTokens": 12321,
 *             "outputTokens": 1375
 *         }, {
 *             "model": "Baize2",
 *             "inputTokens": 8418,
 *             "outputTokens": 9778
 *         }]
 *     }
 * 
 * @apiError 400 查询数据发生错误。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 *
 * @apiParamExample {http} 完整请求示例
 *     GET http://211.157.179.37:7010/aigc/usage/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG?cid=22003303
 */


/**
 * @api {get} /aigc/history/:token History - 查询互动对话的历史记录
 * @apiName 查询互动对话的历史记录
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 按频道、触点或评价分数筛选对话记录，<code>start</code> 为必填的 Unix 毫秒时间戳，<code>end</code> 省略时查到当前时间。
 *                 <p>查询某次会话时提供 <code>channel</code>；查询某用户时提供 <code>cid</code>；筛选评价时提供 <code>feedback</code>。从 <code>histories[]</code> 读取问答和 <code>sn</code>，后者可用于修改评价。空数组表示该时间范围内无匹配记录。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiQuery {String} [channel] 按照指定频道码查询。
 * @apiQuery {Number} [cid] 按照指定用户的触点ID查询。
 * @apiQuery {Number} [feedback] 按问答的反馈评价查询。评价数值：
 *                               <p><span class="default-value">评价为“好”：</span><code>3</code></p>
 *                               <p><span class="default-value">评价为“中”：</span><code>2</code></p>
 *                               <p><span class="default-value">评价为“差”：</span><code>1</code></p>
 *                               <p><span class="default-value">没有评价：</span><code>0</code></p>
 * @apiQuery {Number} start 查询历史的起始时间戳，例如：1692801670646 。
 * @apiQuery {Number} [end] 查询历史的截止时间戳。不设置该参数时，以当前时间作为截止时间戳。
 * 
 * @apiSuccess {Number} total 符合查询条件的总数量。
 * @apiSuccess {Number} start 查询的起始时间戳。
 * @apiSuccess {Number} end 查询的截止时间戳。
 * @apiSuccess {Array} histories 历史数据列表。
 * @apiSuccess {Number} histories.id 对话记录的 ID 。
 * @apiSuccess {Number} histories.sn 对话记录的序列号。
 * @apiSuccess {String} histories.channel 对话的频道码。
 * @apiSuccess {String} histories.unit 对话使用的模型。
 * @apiSuccess {Number} histories.queryContactId 提问的触点ID 。
 * @apiSuccess {Number} histories.queryTime 提问时的时间戳。
 * @apiSuccess {String} histories.queryContent 提问的内容。
 * @apiSuccess {Number} histories.answerContactId 回答的触点ID 。
 * @apiSuccess {Number} histories.answerTime 回答时的时间戳。
 * @apiSuccess {String} histories.answerContent 回答的内容。
 * @apiSuccess {Number} histories.feedback 该条问答的反馈评价。
 * @apiSuccess {Number} histories.contextId 上下文 ID 。
 * 
 *
 *
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "total": 1,
 *         "start": 1692801670646,
 *         "end": 1702951390006,
 *         "histories": [
 *             {
 *                 "id": 1,
 *                 "sn": 3121624789,
 *                 "channel": "mnxRSaLdPuHEVaKA",
 *                 "unit": "Baize",
 *                 "queryContactId": 22003303,
 *                 "queryTime": 1702952287805,
 *                 "queryContent": "你是谁",
 *                 "answerContactId": 531001,
 *                 "answerTime": 1703150277455,
 *                 "answerContent": "我是一个大型语言模型，由白泽训练。",
 *                 "feedback": 2,
 *                 "contextId": 0
 *             }
 *         ]
 *     }
 * @apiError 400 查询数据发生错误。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 *
 * @apiParamExample {http} 完整请求示例
 *     GET http://211.157.179.37:7010/aigc/history/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG?channel=mnxRSaLdPuHEVaKA&start=1692801670646&end=1703951390006
 */


/**
 * @api {post} /aigc/history/:token History - 修改互动对话历史的评价分数
 * @apiName 修改互动对话历史的评价分数
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 为指定问答记录保存评价，适合实现点赞、一般与差评反馈。
 *                 <p>使用历史记录中的 sn 关联目标问答，并按参数表设置 feedback；成功后根据返回的 sn 与 feedback 更新界面。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {Number} sn 历史记录的 SN 序列号。
 * @apiBody {Number} feedback 反馈的评价分数。评价数值：
 *                            <p><span class="default-value">评价为“好”：</span><code>3</code></p>
 *                            <p><span class="default-value">评价为“中”：</span><code>2</code></p>
 *                            <p><span class="default-value">评价为“差”：</span><code>1</code></p>
 *                            <p><span class="default-value">没有评价：</span><code>0</code></p>
 * 
 * @apiParamExample {json} 请求示例
 *     {
 *         "sn": 3121624789,
 *         "feedback": 2
 *     }
 * 
 * @apiSuccess {Number} sn 历史记录的 SN 序列号。
 * @apiSuccess {Number} feedback 反馈的评价分数。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "sn": 3121624789,
 *         "feedback": 2
 *     }
 * 
 * @apiError 400 修改数据时发生错误。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/aigc/history/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "sn": 3121624789,
 *         "feedback": 2
 *     }
 */


/**
 * @api {post} /aigc/chart/data/:token Chart - 操作图表数据
 * @apiName 操作图表数据
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 按 action 查询、插入或删除图表的原子数据。
 *                 <p>getAtoms 使用 label 筛选，insertAtoms 使用 list 提交完整数据项，deleteAtoms 使用 snList 指定删除目标。日期和标签应与同一业务图表保持一致，避免混入不同统计口径。</p>
 *
 * @apiParam {String} token 授权令牌。
 *
 * @apiBody {String} action 指定操作：
 *                          <p><span class="default-value">获取原子数据：</span><code>getAtoms</code></p>
 *                          <p><span class="default-value">插入原子数据：</span><code>insertAtoms</code></p>
 *                          <p><span class="default-value">删除原子数据：</span><code>deleteAtoms</code></p>
 * @apiBody {String} [label] 数据条目的标签。<code>getAtoms</code> 操作时作为查询条件使用。
 * @apiBody {Array} [list] 待插入的原子数据列表。<code>insertAtoms</code> 操作时使用。
 * @apiBody {String} [list.label] 数据条目的标签。
 * @apiBody {String} [list.year] 数据 Year 描述。
 * @apiBody {String} [list.month] 数据 Month 描述。
 * @apiBody {String} [list.date] 数据 Date 描述。
 * @apiBody {Number} [list.value] 数据值。
 * @apiBody {Array} [snList] 数据记录的 SN 列表。<code>deleteAtoms</code> 操作时使用。
 * 
 * @apiParamExample {json} 获取原子数据
 *     {
 *         "action": "getAtoms",
 *         "label": "气温,北京"
 *     }
 * 
 *
 * @apiParamExample {json} 插入原子数据
 *     {
 *         "action": "insertAtoms",
 *         "list": [
 *             {
 *                 "date": "19日",
 *                 "month": "6月",
 *                 "year": "2023年",
 *                 "label": "气温,北京,天气,最低",
 *                 "value": 21
 *             },
 *             {
 *                 "date": "20日",
 *                 "month": "6月",
 *                 "year": "2023年",
 *                 "label": "气温,北京,天气,最低",
 *                 "value": 22
 *             }
 *         ]
 *     }
 *
 * @apiParamExample {json} 删除原子数据
 *     {
 *         "action": "deleteAtoms",
 *         "snList": [
 *             102,
 *             103,
 *             104,
 *             105,
 *             106
 *         ]
 *     }
 * @apiSuccess {Array} list 数据列表。<code>getAtoms</code> 操作时返回查询到的数据。
 * @apiSuccess {String} list.label 数据条目的标签。
 * @apiSuccess {String} list.year 数据 Year 描述。
 * @apiSuccess {String} list.month 数据 Month 描述。
 * @apiSuccess {String} list.date 数据 Date 描述。
 * @apiSuccess {Number} list.value 数据值。
 * @apiSuccess {Number} total 符合条件的数据总数量。
 * 
 *
 *
 * @apiSuccessExample {json} 获取原子数据
 *     HTTP/1.1 200 OK
 *     {
 *         "total": 2,
 *         "list": [
 *             {
 *                 "date": "19日",
 *                 "month": "6月",
 *                 "year": "2023年",
 *                 "label": "气温,北京,天气,最高",
 *                 "value": 30
 *             },
 *             {
 *                 "date": "20日",
 *                 "month": "6月",
 *                 "year": "2023年",
 *                 "label": "气温,北京,天气,最高",
 *                 "value": 32
 *             }
 *         ]
 *     }
 * @apiSuccessExample {json} 插入原子数据
 *     HTTP/1.1 200 OK
 *     {
 *         "total": 2
 *     }
 * 
 * @apiSuccessExample {json} 删除原子数据
 *     HTTP/1.1 200 OK
 *     {
 *         "total": 5
 *     }
 * 
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/aigc/chart/data/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "action": "getAtoms",
 *         "label": "气温,北京"
 *     }
 */


/**
 * @api {get} /aigc/prompt/:token Prompt - 获取提示词
 * @apiName 获取提示词
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 获取当前触点可使用的提示词清单，用于提示词选择器或常用指令面板。
 *                 <p>从 list 读取每条提示词的 id、act 与 prompt；更新或删除时使用该 id。空列表表示没有可用提示词。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiSuccess {Number} total 数据条目数。
 * @apiSuccess {Array} list 数据列表。
 * @apiSuccess {Number} list.id 提示词的 ID 。
 * @apiSuccess {String} list.act 提示词索引键。
 * @apiSuccess {String} list.prompt 提示词内容。
 * 
 *
 *
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "total": 1,
 *         "list": [
 *             {
 *                 "id": 1,
 *                 "act": "充当舆情数据分析师",
 *                 "prompt": "你是舆情分析师，帮我查阅一下最近半年一汽大众在全网的舆情趋势。"
 *             }
 *         ]
 *     }
 * @apiError 400 获取数据出错。
 * @apiError 401 Token 无效。
 *
 * @apiParamExample {http} 完整请求示例
 *     GET http://211.157.179.37:7010/aigc/prompt/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 */


/**
 * @api {post} /aigc/prompt/add/:token Prompt - 新增提示词
 * @apiName 新增提示词
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 创建可复用的提示词，使用 act 描述用途、prompt 保存提示词正文。
 *                 <p>通过 contactIds 指定可见用户；使用 [0] 时向所有触点提供公共提示词。新增后调用“获取提示词”取得 ID，以便后续更新或删除。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {Object[]} [prompts] 新增的提示词数组。与 <code>prompt</code> 二选一。
 * @apiBody {String} prompts.act 提示词的角色或用途名称。
 * @apiBody {String} prompts.prompt 提示词正文。
 * @apiBody {Object} [prompt] 单条提示词对象。与 <code>prompts</code> 二选一。
 * @apiBody {String} prompt.act 提示词的角色或用途名称。
 * @apiBody {String} prompt.prompt 提示词正文。
 * @apiBody {Array} [contactIds] 需要增加提示词的触点ID数组。与 <code>contactId</code> 二选一。
 * @apiBody {Number} [contactId] 需要增加提示词的触点ID 。与 <code>contactIds</code> 二选一。设置为 <code>0</code> 表示提示词为公共提示词。
 * 
 *
 * @apiParamExample {json} 新增提示词
 *     {
 *         "prompts": [
 *             {
 *                 "act": "充当舆情数据分析师",
 *                 "prompt": "你是舆情分析师，帮我查阅一下最近半年一汽大众在全网的舆情趋势。"
 *             }
 *         ],
 *         "contactIds": [
 *             71816522,
 *             71816523,
 *             71816524
 *         ]
 *     }
 *
 * @apiParamExample {json} 新增公共提示词
 *     {
 *         "prompts": [
 *             {
 *                 "act": "充当舆情数据分析师",
 *                 "prompt": "你是舆情分析师，帮我查阅一下最近半年一汽大众在全网的舆情趋势。"
 *             }
 *         ],
 *         "contactIds": [
 *             0
 *         ]
 *     }
 * @apiSuccess {Number} total 已新增数据条目数。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "total": 12
 *     }
 * 
 * @apiError 400 数据出错。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/aigc/prompt/add/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "prompts": [
 *             {
 *                 "act": "充当舆情数据分析师",
 *                 "prompt": "你是舆情分析师，帮我查阅一下最近半年一汽大众在全网的舆情趋势。"
 *             }
 *         ],
 *         "contactIds": [
 *             71816522,
 *             71816523,
 *             71816524
 *         ]
 *     }
 */


/**
 * @api {post} /aigc/prompt/remove/:token Prompt - 删除提示词
 * @apiName 删除提示词
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 按 ID 列表删除提示词。
 *                 <p>先从“获取提示词”确认目标 id，再提交删除；返回的 total 表示本次删除数量。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {Array} idList 指定待删除的提示词 ID 数组。
 * 
 * @apiParamExample {json} 删除提示词
 *     {
 *         "idList": [ 18721, 19741 ]
 *     }
 * 
 * @apiSuccess {Number} total 处理的数据条目数。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "total": 2
 *     }
 * 
 * @apiError 400 数据出错。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/aigc/prompt/remove/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "idList": [
 *             18721,
 *             19741
 *         ]
 *     }
 */


/**
 * @api {post} /aigc/prompt/update/:token Prompt - 更新提示词
 * @apiName 更新提示词
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 更新已有提示词的用途说明和正文。
 *                 <p>先从“获取提示词”取得实际 id，再提交需要保存的内容。返回的 total 表示本次操作结果数量。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {Number} id 指定待更新的提示词 ID 。
 * @apiBody {String} act 提示词索引键。
 * @apiBody {String} prompt 提示词内容。
 * @apiBody {String} prompt.act 提示词的角色或用途名称。
 * @apiBody {String} prompt.prompt 提示词正文。
 * 
 * @apiParamExample {json} 更新提示词
 *     {
 *         "id": 18721,
 *         "act": "充当舆情数据分析师",
 *         "prompt": "你是舆情分析师，帮我查阅一下最近半年一汽大众在全网的舆情趋势。"
 *     }
 * 
 * @apiSuccess {Number} total 处理的数据条目数。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "total": 1
 *     }
 * 
 * @apiError 400 数据出错。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/aigc/prompt/update/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "id": 18721,
 *         "act": "充当舆情数据分析师",
 *         "prompt": "你是舆情分析师，帮我查阅一下最近半年一汽大众在全网的舆情趋势。"
 *     }
 */
