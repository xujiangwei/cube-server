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
 * @apiSuccess {Number} issues 令牌颁发时间戳。
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
 * @apiDescription 获取指定触点的信息。
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
 */


/**
 * @api {post} /contact/new/:token 新建触点
 * @apiName 新建触点
 * @apiGroup Contact_Service
 * @apiVersion 1.0.0
 *
 * @apiDescription 新建指定 ID 和相关参数的触点。
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
 * @apiUse Contact_Response
 * @apiSuccess {Object} token 触点的访问令牌。
 * @apiUse Token_Response
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
 *                 "address": "127.0.0.1",
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
 *                         "fileURL": "http://127.0.0.1:7010/filestorage/file/",
 *                         "fileSecureURL": "https://127.0.0.1:7017/filestorage/file/"
 *                     }
 *                 }
 *             },
 *             "appKey": "default-opensource-appkey",
 *             "expiry": 2029209869370,
 *             "issues": 1713849869370,
 *             "cid": 98760001
 *         }
 *     }
 * 
 * @apiError 400 接口服务器错误。
 * @apiError 403 参数错误。
 * @apiError 404 触点服务返回异常。
 * @apiError 406 访问服务发生错误。
 */


/**
 * @api {post} /contact/delete/:token 删除触点
 * @apiName 删除触点
 * @apiGroup Contact_Service
 * @apiVersion 1.0.0
 *
 * @apiDescription 删除指定 ID 的触点。该操作将从触点服务里删除该触点的数据，同时删除该触点的所有令牌，但是不会删除与该触点关联的文件、日志、消息等保存在其他服务里的数据。
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
 */


/**
 * @api {get} /contact/exists/:token 检查触点是否存在
 * @apiName 检查触点是否存在
 * @apiGroup Contact_Service
 * @apiVersion 1.0.0
 *
 * @apiDescription 按令牌码、名称或 ID + 域检查触点是否已存在。触点不存在时仍返回 <code>200</code> ，通过 <code>exists</code> 字段区分。
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
 */


/**
 * @api {post} /contact/vc/:token 申请短信验证码
 * @apiName 申请短信验证码
 * @apiGroup Contact_Service
 * @apiVersion 1.0.0
 *
 * @apiDescription 向指定手机号码发送验证码。设备信息由请求头 <code>x-baize-api-device</code> 与 <code>x-baize-api-platform</code> 提供并随请求下发。
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiBody {String} phoneNumber 接收验证码的手机号码。
 * 
 * @apiParamExample {json} 请求示例
 *     {
 *         "phoneNumber": "13800138000"
 *     }
 * 
 * @apiSuccess {Object} payload 触点服务返回的数据负载，直接作为响应体返回（无外层包装），字段由短信服务实现决定。
 *
 * @apiError 400 请求体解析失败。
 * @apiError 401 令牌无效。
 * @apiError 403 访问触点服务失败。
 * @apiError 404 验证码发送失败。
 */


/**
 * @api {get} /contact/vc/:token 校验短信验证码
 * @apiName 校验短信验证码
 * @apiGroup Contact_Service
 * @apiVersion 1.0.0
 *
 * @apiDescription 校验指定手机号收到的验证码。验证码在传输时使用 MD5 散列。
 *
 * @apiParam {String} token 授权令牌。
 * 
 * @apiQuery {String} number 手机号码。
 * @apiQuery {String} code 验证码的 MD5 散列值。
 *
 * @apiSuccess {Object} payload 触点服务返回的数据负载，直接作为响应体返回（无外层包装），校验通过时包含签入结果。
 *
 * @apiError 400 请求处理失败。
 * @apiError 401 令牌无效。
 * @apiError 403 访问触点服务失败。
 * @apiError 404 验证码校验失败。
 */
