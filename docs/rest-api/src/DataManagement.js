/**
 * @apiDefine Data_Management 数据管理
 */


/**
 * @api {post} /aigc/channel/:token 申请对话频道
 * @apiName 申请对话频道
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 申请对话频道。每个对话频道对应一组独立的对话模型，对话内容仅在该频道内的模型进行处理。
 *                 <p>频道的空闲超时时间为30分钟，30分钟内无对话频道将自动销毁。</p>
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
 */

/**
 * @api {get} /aigc/channel/:token 查询对话频道
 * @apiName 查询对话频道
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 按频道码查询对话频道的当前状态。
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
 */


/**
 * @api {post} /app/activate/:token User - 创建应用层用户
 * @apiName 创建应用层用户
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 创建应用层用户。应用层用户是指承载具体应用数据时用于描述独立数据源的用户或账号。<br/>
 *                 使用此接口创建用户后，调用修改用户数据接口对用户信息进行修改，调用签入用户接口将该用户与此令牌进行绑定。
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
 */

/**
 * @api {get} /app/user/:token User - 签入应用层用户数据
 * @apiName 签入应用层用户数据
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 签入应用层用户，将指定用户激活使用。<br/>
 *                 通常应用用户包含了一个业务用户的若干信息，例如：手机号码，邮箱地址等。
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
 */


/**
 * @api {get} /aigc/event/:token Event - 查询用户事件
 * @apiName 查询用户事件
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 按照用户的触点ID，事件名称等参数查询事件清单。
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
 * @apiSuccess {Array} list 事件数据列表。
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
 *                     "ip": "127.0.0.1",
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
 *                     "ip": "127.0.0.1",
 *                     "token": "UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG"
 *                 }
 *             }
 *         ]
 *     }
 * 
 * @apiError 400 查询数据发生错误。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 */


/**
 * @api {get} /aigc/usage/:token Usage - 查询用户用量
 * @apiName 查询用户用量
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 按照用户的触点ID查询用量。
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
 */


/**
 * @api {get} /aigc/history/:token History - 查询互动对话的历史记录
 * @apiName 查询互动对话的历史记录
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 按照频道码、反馈评价或者触点ID查询互动对话的历史记录。
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
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "total": 16,
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
 *             },
 *             ...
 *         ]
 *     }
 * 
 * @apiError 400 查询数据发生错误。
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 */


/**
 * @api {post} /aigc/history/:token History - 修改互动对话历史的评价分数
 * @apiName 修改互动对话历史的评价分数
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 修改指定的互动对话历史记录的评价分数。
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
 */


/**
 * @api {post} /aigc/chart/data/:token Chart - 操作图表数据
 * @apiName 操作图表数据
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 对图表数据进行操作。包括查询数据、插入数据和删除数据。
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
 *             }, {
 *                 "date": "20日",
 *                 "month": "6月",
 *                 "year": "2023年",
 *                 "label": "气温,北京,天气,最低",
 *                 "value": 22
 *             },
 *             ...
 *         ]
 *     }
 * 
 * @apiParamExample {json} 删除原子数据
 *     {
 *         "action": "deleteAtoms",
 *         "snList": [ 102, 103, 104, 105, 106, ... ]
 *     }
 * 
 * @apiSuccess {Array} list 数据列表。<code>getAtoms</code> 操作时返回查询到的数据。
 * @apiSuccess {String} list.label 数据条目的标签。
 * @apiSuccess {String} list.year 数据 Year 描述。
 * @apiSuccess {String} list.month 数据 Month 描述。
 * @apiSuccess {String} list.date 数据 Date 描述。
 * @apiSuccess {Number} list.value 数据值。
 * @apiSuccess {Number} total 符合条件的数据总数量。
 * 
 * @apiSuccessExample {json} 获取原子数据
 *     HTTP/1.1 200 OK
 *     {
 *         "total": 14,
 *         "list": [
 *             {
 *                 "date": "19日",
 *                 "month": "6月",
 *                 "year": "2023年",
 *                 "label": "气温,北京,天气,最高",
 *                 "value": 30
 *             }, {
 *                  "date": "20日",
 *                  "month": "6月",
 *                  "year": "2023年",
 *                  "label": "气温,北京,天气,最高",
 *                  "value": 32
 *             },
 *             ...
 *         ]
 *     }
 * 
 * @apiSuccessExample {json} 插入原子数据
 *     HTTP/1.1 200 OK
 *     {
 *         "total": 7
 *     }
 * 
 * @apiSuccessExample {json} 删除原子数据
 *     HTTP/1.1 200 OK
 *     {
 *         "total": 7
 *     }
 * 
 * @apiError 401 Token 无效。
 * @apiError 403 参数错误。
 */


/**
 * @api {get} /aigc/prompt/:token Prompt - 获取提示词
 * @apiName 获取提示词
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 获取当前触点的提示词列表。
 * 
 * @apiParam {String} token 授权令牌。
 * 
 * @apiSuccess {Number} total 数据条目数。
 * @apiSuccess {Array} list 数据列表。
 * @apiSuccess {Number} list.id 提示词的 ID 。
 * @apiSuccess {String} list.act 提示词索引键。
 * @apiSuccess {String} list.prompt 提示词内容。
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "total": 7,
 *         "list": [{
 *             "id": 1,
 *             "act": "充当舆情数据分析师",
 *             "prompt": "你是舆情分析师，帮我查阅一下最近半年一汽大众在全网的舆情趋势。"
 *         }, ... ]
 *     }
 * 
 * @apiError 400 获取数据出错。
 * @apiError 401 Token 无效。
 */


/**
 * @api {post} /aigc/prompt/add/:token Prompt - 新增提示词
 * @apiName 新增提示词
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 给指定触点新增提示词。该接口不设置触点参数则新增提示词对所有触点有效。
 * 
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {Array} [prompts] 新增的提示词数组。与 <code>prompt</code> 二选一。
 * @apiBody {Object} [prompt] 单条提示词对象。与 <code>prompts</code> 二选一。
 * @apiBody {Array} [contactIds] 需要增加提示词的触点ID数组。与 <code>contactId</code> 二选一。
 * @apiBody {Number} [contactId] 需要增加提示词的触点ID 。与 <code>contactIds</code> 二选一。设置为 <code>0</code> 表示提示词为公共提示词。
 * 
 * @apiParamExample {json} 新增提示词
 *     {
 *         "prompts": [{
 *             "act": "充当舆情数据分析师",
 *             "prompt": "你是舆情分析师，帮我查阅一下最近半年一汽大众在全网的舆情趋势。"
 *         }, ... ],
 *         "contactIds": [ 71816522, 71816523, 71816524 ]
 *     }
 * 
 * @apiParamExample {json} 新增公共提示词
 *     {
 *         "prompts": [{
 *             "act": "充当舆情数据分析师",
 *             "prompt": "你是舆情分析师，帮我查阅一下最近半年一汽大众在全网的舆情趋势。"
 *         }, ... ],
 *         "contactIds": [ 0 ]
 *     }
 * 
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
 */


/**
 * @api {post} /aigc/prompt/remove/:token Prompt - 删除提示词
 * @apiName 删除提示词
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 删除指定条件的提示词。
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
 */


/**
 * @api {post} /aigc/prompt/update/:token Prompt - 更新提示词
 * @apiName 更新提示词
 * @apiGroup Data_Management
 * @apiVersion 1.0.0
 * 
 * @apiDescription 更新指定的提示词。
 * 
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {Number} id 指定待更新的提示词 ID 。
 * @apiBody {String} act 提示词索引键。
 * @apiBody {String} prompt 提示词内容。
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
 */
