/**
 * @apiDefine Contact_Service 触点服务
 */

/**
 * @apiDefine Contact_Request 触点
 * @apiBody {Number} id 触点ID 。
 * @apiBody {String} name 触点名称。
 * @apiBody {String} domain 触点所属的域。
 * @apiBody {String} namePY 触点名称拼音。
 * @apiBody {Number} timestamp 触点数据时间戳。
 */

/**
 * @apiDefine Contact_Response 触点
 * @apiSuccess {Number} id 触点ID 。
 * @apiSuccess {String} name 触点名称。
 * @apiSuccess {String} domain 触点所属的域。
 * @apiSuccess {String} namePY 触点名称拼音。
 * @apiSuccess {Number} timestamp 触点数据时间戳。
 * @apiSuccess {Array} [devices] 触点使用的设备列表。
 * @apiSuccess {String} [devices.name] 设备名称。
 * @apiSuccess {String} [devices.platform] 设备平台描述。
 */

/**
 * @apiDefine Token_Response 令牌
 * @apiSuccess {String} code 令牌码。
 * @apiSuccess {Number} cid 触点ID。
 * @apiSuccess {String} domain 触点所属的域。
 * @apiSuccess {Boolean} [ferry] 该令牌是否是中转令牌。
 * @apiSuccess {String} [appKey] 令牌使用的应用标识。
 * @apiSuccess {Number} issue 令牌颁发时间戳。
 * @apiSuccess {Number} expiry 令牌到期时间戳。
 * @apiSuccess {Object} [description] 令牌描述的服务访问信息。
 * @apiSuccess {String} [description.address] 服务地址。
 * @apiSuccess {Number} [description.port] 服务端口。
 * @apiSuccess {Object} [description.primaryContent] 各能力的主访问信息。
 */



/**
 * @api {get} /contact/get/:token 获取触点信息
 * @apiName 获取触点信息
 * @apiGroup Contact_Service
 * @apiVersion 1.0.0
 *
 * @apiDescription 查询用户或业务对象对应的触点资料。使用 <code>code</code>、<code>name</code> 或 <code>id</code> 与 <code>domain</code> 的组合进行查询，建议每次只选择一种方式。
 *                 <p>多个条件同时提供时按 <code>code</code>、<code>name</code>、<code>id+domain</code> 的优先级处理。先判断 HTTP 状态，再读取触点 ID、名称和域信息；仅需确认是否存在时使用“检查触点是否存在”。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiQuery {String} [code] 指定触点的令牌码。与 <code>id</code> 和 <code>domain</code> 参数二选一。
 * @apiQuery {String} [name] 指定触点的名称。与 <code>id</code> 和 <code>domain</code> 参数二选一。
 * @apiQuery {Number} [id] 指定触点ID 。与 <code>domain</code> 参数同时使用，且与 <code>code</code> 参数二选一。
 * @apiQuery {String} [domain] 指定触点的域。与 <code>id</code> 参数同时使用，且与 <code>code</code> 参数二选一。
 *                          <p>参数优先级：<code>code</code> &gt; <code>name</code> &gt; <code>id</code> + <code>domain</code> 。</p>
 * 
 * @apiUse Contact_Response
 * 
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "id": 98760001,
 *         "domain": "default_domain",
 *         "name": "张伟",
 *         "namePY": "ZHANG,WEI",
 *         "timestamp": 1713841775155,
 *         "devices": [{
 *             "name": "Server",
 *             "platform": "Cube Server"
 *         }]
 *     }
 * 
 * @apiError 400 接口服务器错误。
 * @apiError 403 参数错误。
 * @apiError 404 触点服务返回异常。
 * @apiError 406 访问服务发生错误。
 *
 * @apiParamExample {http} 完整请求示例
 *     GET http://211.157.179.37:7010/contact/get/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG?id=22003303&domain=default_domain
 *
 * @apiParamExample {http} 按名称查询（自动编码查询值）
 *     GET http://211.157.179.37:7010/contact/get/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG?name=developer%40example.com
 *
 * @apiParamExample {http} 按授权码查询
 *     GET http://211.157.179.37:7010/contact/get/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG?code=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 */


/**
 * @api {post} /contact/new/:token 新建触点
 * @apiName 新建触点
 * @apiGroup Contact_Service
 * @apiVersion 1.0.0
 *
 * @apiDescription 为用户或业务对象创建触点，提供指定的 <code>id</code> 与 <code>name</code>。触点 ID 应由应用统一管理；创建前可使用“检查触点是否存在”避免重复。
 *                 <p>需要立即取得访问令牌时设置 <code>applyToken=true</code>，并按需指定毫秒单位的 <code>duration</code>；不申请令牌时设置为 <code>false</code>。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {Number} id 触点ID 。
 * @apiBody {String} name 触点名称。
 * @apiBody {Boolean} applyToken 是否申请该触点的访问令牌。
 * @apiBody {Number} [duration] 申请令牌的有效时长，单位：毫秒。
 * 
 * @apiParamExample {json} 请求示例
 *     {
 *         "id": 98760001,
 *         "name": "张伟",
 *         "applyToken": true
 *     }
 * 
 * @apiSuccess {Object} contact 触点数据。
 * @apiSuccess {Number} contact.id 触点 ID。
 * @apiSuccess {String} contact.domain 触点所属域。
 * @apiSuccess {String} contact.name 触点名称。
 * @apiSuccess {String} contact.namePY 触点名称拼音。
 * @apiSuccess {Number} contact.timestamp 触点数据的 Unix 毫秒时间戳。
 * @apiSuccess {Object[]} [contact.devices] 已登记的设备列表；没有设备时可省略。
 * @apiSuccess {String} contact.devices.name 设备名称。
 * @apiSuccess {String} contact.devices.platform 设备平台。
 * @apiSuccess {Object} [token] applyToken=true 时返回的触点访问令牌。
 * @apiSuccess {String} token.code 令牌码。
 * @apiSuccess {String} token.domain 令牌所属域。
 * @apiSuccess {String} token.appKey 令牌使用的应用标识。
 * @apiSuccess {Number} token.cid 令牌关联的触点 ID。
 * @apiSuccess {Number} token.issue 令牌颁发时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} token.expiry 令牌到期时间，Unix 毫秒时间戳。
 * @apiSuccess {Boolean} token.ferry 是否为中转令牌。
 * @apiSuccess {Object} [token.description] 服务访问描述。
 * @apiSuccess {String} token.description.address 服务地址。
 * @apiSuccess {Number} token.description.port 服务端口。
 * @apiSuccess {Object} token.description.primaryContent 按能力名索引的访问配置；其他能力可扩展自己的配置键。
 * @apiSuccess {Object} [token.description.primaryContent.FileStorage] 文件存储访问配置。
 * @apiSuccess {String} [token.description.primaryContent.FileStorage.fileURL] HTTP 文件接口基址。
 * @apiSuccess {String} [token.description.primaryContent.FileStorage.fileSecureURL] HTTPS 文件接口基址。
 * @apiSuccess {Object} [token.description.primaryContent.MultipointComm] 实时通信配置。
 * @apiSuccess {Object[]} [token.description.primaryContent.MultipointComm.iceServers] ICE / TURN 服务器列表。
 * @apiSuccess {String/String[]} token.description.primaryContent.MultipointComm.iceServers.urls 服务器 URL 或 URL 列表。
 * @apiSuccess {String} [token.description.primaryContent.MultipointComm.iceServers.username] TURN 用户名。
 * @apiSuccess {String} [token.description.primaryContent.MultipointComm.iceServers.credential] TURN 凭据。
 *
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "contact": {
 *             "domain": "default_domain",
 *             "name": "张伟",
 *             "id": 98760001,
 *             "namePY": "ZHANG,WEI",
 *             "timestamp": 1713849869324
 *         },
 *         "token": {
 *             "code": "sEvRQlTyICHRWewAUTMXkYewHAUJcMZu",
 *             "domain": "default_domain",
 *             "ferry": false,
 *             "description": {
 *                 "address": "211.157.179.37",
 *                 "port": 7000,
 *                 "primaryContent": {
 *                     "MultipointComm": {
 *                         "iceServers": [{
 *                             "urls": "turn:52.83.195.35:3478",
 *                             "credential": "cube887",
 *                             "username": "cube"
 *                         }]
 *                     },
 *                     "FileStorage": {
 *                         "fileURL": "http://211.157.179.37:7010/filestorage/file/",
 *                         "fileSecureURL": "https://211.157.179.37:7017/filestorage/file/"
 *                     }
 *                 }
 *             },
 *             "appKey": "default-opensource-appkey",
 *             "expiry": 2029209869370,
 *             "issue": 1713849869370,
 *             "cid": 98760001
 *         }
 *     }
 * 
 * @apiError 400 接口服务器错误。
 * @apiError 403 参数错误。
 * @apiError 404 触点服务返回异常。
 * @apiError 406 访问服务发生错误。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/contact/new/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "id": 98760001,
 *         "name": "张伟",
 *         "applyToken": true
 *     }
 */


/**
 * @api {post} /contact/delete/:token 删除触点
 * @apiName 删除触点
 * @apiGroup Contact_Service
 * @apiVersion 1.0.0
 *
 * @apiDescription 删除指定 ID 的触点及其访问令牌。调用前确认目标触点，完成后应清除客户端保存的对应令牌。
 *                 <p>此操作不会自动清理该触点的文件、日志和消息等关联内容；如业务需要清理文件，应另行调用文件接口。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {Number} id 触点ID 。
 * 
 * @apiParamExample {json} 请求示例
 *     {
 *         "id": 98760001
 *     }
 * 
 * @apiUse Contact_Response
 *
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "id": 98760001,
 *         "domain": "default_domain",
 *         "name": "张伟",
 *         "namePY": "ZHANG,WEI",
 *         "timestamp": 1713841775155,
 *         "devices": [{
 *             "name": "Server",
 *             "platform": "Cube Server"
 *         }]
 *     }
 * 
 * @apiError 400 接口服务器错误。
 * @apiError 403 参数错误。
 * @apiError 404 触点服务返回异常。
 * @apiError 406 访问服务发生错误。
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/contact/delete/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *
 *     {
 *         "id": 98760001
 *     }
 */


/**
 * @api {get} /contact/exists/:token 检查触点是否存在
 * @apiName 检查触点是否存在
 * @apiGroup Contact_Service
 * @apiVersion 1.0.0
 *
 * @apiDescription 按授权码、名称或 ID 与域的组合检查触点。建议每次只提供一种查询方式；多种条件同时提供时优先使用 <code>code</code>，其次 <code>name</code>，最后 <code>id+domain</code>。
 *                 <p>触点不存在仍返回 HTTP 200，须读取 <code>exists</code> 判断结果，不能仅以 HTTP 状态判断存在。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiQuery {String} [code] 指定触点的令牌码。
 * @apiQuery {String} [name] 指定触点的名称。
 * @apiQuery {Number} [id] 指定触点ID 。
 * @apiQuery {String} [domain] 指定触点的域。
 *                          <p>参数优先级：<code>code</code> &gt; <code>name</code> &gt; <code>id</code> + <code>domain</code> 。</p>
 * 
 * @apiSuccess {Boolean} exists 触点是否存在。
 * @apiSuccess {String} [code] 回显查询时使用的令牌码。
 * @apiSuccess {String} [name] 回显查询时使用的名称。
 * @apiSuccess {Number} [id] 回显查询时使用的触点ID 。
 * @apiSuccess {String} [domain] 回显查询时使用的域。
 *
 * @apiSuccessExample {json} 应答示例
 *     HTTP/1.1 200 OK
 *     {
 *         "id": 98760001,
 *         "domain": "default_domain",
 *         "exists": true
 *     }
 *
 * @apiError 400 触点服务返回异常。
 * @apiError 401 令牌无效。
 * @apiError 403 参数错误。
 * @apiError 406 访问服务发生错误。
 *
 * @apiSuccessExample {json} 未找到触点
 *     HTTP/1.1 200 OK
 *     {
 *         "exists": false
 *     }
 *
 * @apiParamExample {http} 完整请求示例
 *     GET http://211.157.179.37:7010/contact/exists/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG?id=22003303&domain=default_domain
 *
 * @apiParamExample {http} 按名称查询（自动编码查询值）
 *     GET http://211.157.179.37:7010/contact/exists/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG?name=developer%40example.com
 *
 * @apiParamExample {http} 按授权码查询
 *     GET http://211.157.179.37:7010/contact/exists/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG?code=UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 */


/**
 * @api {post} /contact/vc/:token 申请短信验证码
 * @apiName 申请短信验证码
 * @apiGroup Contact_Service
 * @apiVersion 1.0.0
 *
 * @apiDescription 向指定手机号发送验证码。请求体同时提供国际电话区号、国家或地区代码和手机号；设备信息通过 <code>x-baize-api-device</code>、<code>x-baize-api-platform</code> 请求头提供。
 *                 <p>成功后提示用户输入短信中的验证码，再调用“校验短信验证码”。以返回的 <code>timestamp</code> 和 <code>duration</code> 判断有效期；不要将示例验证码用于实际校验。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {String} dialCode 国际电话区号，例如 <code>86</code>。
 * @apiBody {String} isoCode 国家或地区 ISO 代码，例如 <code>CN</code>。
 * @apiBody {String} phoneNumber 接收验证码的手机号码。
 * 
 *
 * @apiParamExample {json} 请求示例
 *     {
 *         "dialCode": "86",
 *         "isoCode": "CN",
 *         "phoneNumber": "13800138000"
 *     }
 * @apiSuccess {String} token 此次验证码关联的令牌码。
 * @apiSuccess {String} dialCode 国际电话区号。
 * @apiSuccess {String} isoCode 国家或地区 ISO 代码。
 * @apiSuccess {String} phoneNumber 接收验证码的手机号。
 * @apiSuccess {Number} timestamp 验证码签发时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} duration 验证码有效时长，单位：毫秒。
 * @apiSuccess {String} [codeMD5] 验证码的 MD5 摘要；存在时返回。
 *
 * @apiError 400 请求体解析失败。
 * @apiError 401 令牌无效。
 * @apiError 403 访问触点服务失败。
 * @apiError 404 验证码发送失败。
 *
 * @apiSuccessExample {json} 成功应答（有效时长以实际返回值为准）
 *     HTTP/1.1 200 OK
 *     {
 *         "token": "UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *         "dialCode": "86",
 *         "isoCode": "CN",
 *         "phoneNumber": "13800138000",
 *         "timestamp": 1793322000000,
 *         "duration": 300000,
 *         "codeMD5": "e10adc3949ba59abbe56e057f20f883e"
 *     }
 *
 * @apiParamExample {http} 完整请求示例
 *     POST http://211.157.179.37:7010/contact/vc/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG
 *     Content-Type: application/json
 *     x-baize-api-device: web-client
 *     x-baize-api-platform: Web
 *
 *     {
 *         "dialCode": "86",
 *         "isoCode": "CN",
 *         "phoneNumber": "13800138000"
 *     }
 */


/**
 * @api {get} /contact/vc/:token 校验短信验证码
 * @apiName 校验短信验证码
 * @apiGroup Contact_Service
 * @apiVersion 1.0.0
 *
 * @apiDescription 先申请短信验证码，再将用户收到的验证码按原字符串计算 MD5，将十六进制摘要填入查询参数 <code>code</code>，手机号填入 <code>number</code>。例如示例验证码 <code>123456</code> 的 MD5 为 <code>e10adc3949ba59abbe56e057f20f883e</code>。
 *                 <p>使用与申请时一致的设备请求头。HTTP 200 表示校验成功并返回验证码关联信息；失败时检查手机号、验证码与有效期，按需重新申请。</p>
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiQuery {String} number 手机号码。
 * @apiQuery {String} code 验证码的 MD5 散列值。
 *
 * @apiSuccess {String} token 此次验证码关联的令牌码。
 * @apiSuccess {String} dialCode 国际电话区号。
 * @apiSuccess {String} isoCode 国家或地区 ISO 代码。
 * @apiSuccess {String} phoneNumber 接收验证码的手机号。
 * @apiSuccess {Number} timestamp 验证码签发时间，Unix 毫秒时间戳。
 * @apiSuccess {Number} duration 验证码有效时长，单位：毫秒。
 * @apiSuccess {String} [codeMD5] 验证码的 MD5 摘要；存在时返回。
 *
 * @apiError 400 请求处理失败。
 * @apiError 401 令牌无效。
 * @apiError 403 访问触点服务失败。
 * @apiError 404 验证码校验失败。
 *
 * @apiSuccessExample {json} 成功应答（有效时长以实际返回值为准）
 *     HTTP/1.1 200 OK
 *     {
 *         "token": "UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG",
 *         "dialCode": "86",
 *         "isoCode": "CN",
 *         "phoneNumber": "13800138000",
 *         "timestamp": 1793322000000,
 *         "duration": 300000,
 *         "codeMD5": "e10adc3949ba59abbe56e057f20f883e"
 *     }
 *
 * @apiParamExample {http} 完整请求示例
 *     GET http://211.157.179.37:7010/contact/vc/UQYrEcbPaZhHSBvJippdpPrwvIfvuPlG?number=13800138000&code=e10adc3949ba59abbe56e057f20f883e
 *     x-baize-api-device: web-client
 *     x-baize-api-platform: Web
 */
